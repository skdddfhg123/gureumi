package com.example.pleosapp.engine

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.LowWasherFluidWaringListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Vehicle SDK 콜백을 엔진 입력으로 바꿉니다. SDK 콜백은 여러 바인더 스레드에서 오므로
 * 전용 스레드 하나로 모아 엔진이 순서대로 처리하게 합니다.
 */
class VehicleEngineBridge(
    private val vehicle: Vehicle,
    knownAilments: Set<String>,
    restored: RestClock = RestClock(),
    leftSeats: Set<SeatSlot> = emptySet(),
    /** 와이퍼를 구독할지. CAR_WINDOWS는 검증 빌드만 선언하므로 서비스가 권한을 확인해 넘깁니다. */
    private val readWipers: Boolean = false,
    private val onEvent: (DriveEvent) -> Unit,
) {

    private val config = EngineConfig()
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val engine = DriveEventEngine(config, knownAilments, restored, leftSeats, onEvent)
    private val clock = ClockWatch()
    private val unregisters = mutableListOf<() -> Unit>()

    /** 경고등과 워셔액은 따로 오지만 엔진에는 건강 신호 하나로 넘깁니다. */
    @Volatile private var telltaleCodes: Set<String>? = null
    @Volatile private var washerLow = PetState.WASHER_CODE in knownAilments

    private fun pushHealth() {
        // 첫 값이 오기 전의 빈 값을 넘기면 복원한 질병이 나은 것으로 오판해 별과 깃털이 나갑니다.
        val telltales = telltaleCodes ?: return
        val codes = if (washerLow) telltales + PetState.WASHER_CODE else telltales
        post { engine.onTelltales(codes, it) }
    }

    private fun post(atMs: Long = System.currentTimeMillis(), block: (now: Long) -> Unit) {
        val wallMs = System.currentTimeMillis()
        val elapsedMs = SystemClock.elapsedRealtime()
        try {
            executor.execute {
                runCatching {
                    clock.moved(wallMs, elapsedMs).takeIf { it != 0L }?.let(engine::onClockMoved)
                    block(atMs)
                }.onFailure { Log.e(TAG, "engine input failed", it) }
            }
        } catch (_: RejectedExecutionException) {
            Log.w(TAG, "late callback after release")
        }
    }

    /** 신호가 없어도 시간 판정이 제때 돌도록 예약합니다. */
    private fun tickAfter(delayMs: Long) {
        try {
            executor.schedule({ tick() }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            Log.w(TAG, "late tick after release")
        }
    }

    /** 권한이나 차종 때문에 등록이 실패해도 나머지 기능은 그대로 돕니다. */
    private fun guarded(key: String, block: () -> Unit) {
        runCatching(block).onFailure { Log.e(TAG, "$key unavailable", it) }
    }

    fun register() {
        val gear = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) {
                post { engine.onGear(gear.toEngineGear(), it) }
                tickAfter(config.arrivalHoldMs + TICK_MARGIN_MS)
            }
            override fun onFailed(e: Exception) = failed("gear", e)
        }
        guarded("gear") {
            vehicle.getGear().registerGearSelection(gear)
            unregisters += { vehicle.getGear().unregisterGearSelection(gear) }
        }

        val brake = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(status: Boolean?) {
                if (status != null) post { engine.onBrake(status, it) }
            }
            override fun onFailed(e: Exception) = failed("brake", e)
        }
        guarded("brake") {
            vehicle.getBrake().registerBrakePedalStatus(brake)
            unregisters += { vehicle.getBrake().unregisterBrakePedalStatus(brake) }
        }

        val turn = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = post { engine.onTurnSignal(state.toTurnSide(), it) }
            override fun onFailed(e: Exception) = failed("turnSignal", e)
        }
        guarded("turnSignal") {
            vehicle.getTurnSignal().registerTurnSignalState(turn)
            unregisters += { vehicle.getTurnSignal().unregisterTurnSignalState(turn) }
        }

        val steering = object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) {
                if (angle != null) post { engine.onSteering(angle, it) }
            }
            override fun onFailed(e: Exception) = failed("steering", e)
        }
        guarded("steering") {
            vehicle.getSteeringWheel().registerSteeringWheelAngle(steering)
            unregisters += { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steering) }
        }

        val telltales = object : TelltaleInfoListener {
            override fun onTelltaleInfoUpdated(telltales: Telltales?) {
                val active = telltales?.telltales.orEmpty()
                    .filter { it.state != TelltaleState.OFF }
                    .mapTo(HashSet()) { it.type.name }
                SkySignals.onTelltales(active)
                telltaleCodes = active
                pushHealth()
            }
            override fun onFailed(e: Exception) = failed("telltales", e)
        }
        guarded("washer") {
            val washer = object : LowWasherFluidWaringListener {
                override fun onLowWasherFluidWaringUpdated(low: Boolean?) {
                    washerLow = low == true
                    pushHealth()
                }

                override fun onFailed(e: Exception) = failed("washer", e)
            }
            vehicle.getSafety().registerLowWasherFluidWaringState(washer)
            unregisters += { vehicle.getSafety().unregisterLowWasherFluidWaringState(washer) }
        }
        guarded("odometer") {
            val odometer = object : DistanceDrivenListener {
                override fun onDistanceDrivenUpdated(distance: Float?) {
                    if (distance != null) KeepsakeRepository.onOdometer(distance)
                }

                override fun onFailed(e: Exception) = failed("odometer", e)
            }
            vehicle.getOdometer().registerDistanceDriven(odometer)
            unregisters += { vehicle.getOdometer().unregisterDistanceDriven(odometer) }
        }
        if (readWipers) guarded("wiper", ::registerWiper)
        guarded("telltales") {
            vehicle.getSafety().registerTelltales(telltales)
            unregisters += { vehicle.getSafety().unregisterTelltales(telltales) }
        }
        registerDoor(SeatSlot.REAR_LEFT, DoorValue.ROW_2_LEFT)
        registerDoor(SeatSlot.REAR_RIGHT, DoorValue.ROW_2_RIGHT)
        registerDoor(SeatSlot.FRONT_RIGHT, DoorValue.ROW_1_RIGHT)
        registerBelt(SeatSlot.REAR_LEFT, SeatValue.ROW_2_LEFT)
        registerBelt(SeatSlot.REAR_CENTER, SeatValue.ROW_2_CENTER)
        registerBelt(SeatSlot.REAR_RIGHT, SeatValue.ROW_2_RIGHT)
        registerBelt(SeatSlot.FRONT_RIGHT, SeatValue.ROW_1_RIGHT)
    }

    /** 비 오는 장면용입니다. [readWipers]가 참일 때, 곧 서비스가 CAR_WINDOWS 권한을 확인했을 때만 부릅니다. */
    @SuppressLint("MissingPermission")
    private fun registerWiper() {
        val windshield = WindowArea(WindowValue.FRONT_WINDSHIELD)
        val wiper = object : WiperStateListener {
            override fun onWiperStateUpdated(state: WiperState?) {
                SkySignals.onWiper(state == WiperState.ON, System.currentTimeMillis())
            }

            override fun onFailed(e: Exception) = failed("wiper", e)
        }
        vehicle.getWiper().registerWiperState(windshield, wiper)
        unregisters += { vehicle.getWiper().unregisterWiperState(windshield, wiper) }
    }

    private fun registerDoor(seat: SeatSlot, door: DoorValue) {
        guarded("door.$seat") {
            val area = DoorArea(door)
            val listener = object : DoorPositionListener {
                override fun onPositionUpdated(position: Int?) {
                    if (position != null) onRearDoor(seat, position != DOOR_CLOSED)
                }
                override fun onFailed(e: Exception) = failed("door.$seat", e)
            }
            vehicle.getDoor().registerDoorPosition(area, listener)
            unregisters += { vehicle.getDoor().unregisterDoorPosition(area, listener) }
        }
    }

    private fun registerBelt(seat: SeatSlot, belt: SeatValue) {
        guarded("belt.$seat") {
            val area = SeatArea(belt)
            val listener = object : SeatBeltBuckledStateListener {
                override fun onSeatBeltBuckledStateUpdated(state: Boolean?) {
                    if (state != null) onSeatBelt(seat, state)
                }
                override fun onFailed(e: Exception) = failed("belt.$seat", e)
            }
            vehicle.getSeat().registerSeatBeltBuckledState(area, listener)
            unregisters += { vehicle.getSeat().unregisterSeatBeltBuckledState(area, listener) }
        }
    }

    fun onRearDoor(seat: SeatSlot, open: Boolean) {
        post { engine.onRearDoor(seat, open, it) }
        if (!open) tickAfter(config.rearBeltAskDelayMs + TICK_MARGIN_MS)
    }

    fun onSeatBelt(seat: SeatSlot, buckled: Boolean) = post { engine.onSeatBelt(seat, buckled, it) }

    /**
     * 화면에서 그 자리의 아기를 눌렀습니다. 안전벨트를 아는 차는 그 아기를 부른 것으로 보고 먼저 앉힙니다.
     * 모르는 차는 그 자리를 깨우거나 재웁니다.
     */
    fun onSeatTap(seat: SeatSlot) = post { now ->
        val spirit = FamilyRepository.state.spiritAt(seat)
        if (engine.beltsKnown && spirit != null) callNow(spirit.preset, now) else engine.onSeatTap(seat, now)
    }

    /** 정차 중 아기를 누르거나 이름을 불렀습니다. 부른 아기가 먼저 탑니다. 안전벨트를 모르는 차는 그 아기를 깨웁니다. */
    fun call(preset: SpiritPreset) = post { callNow(preset, it) }

    /** 부른 것을 취소합니다. 이미 앉은 아기는 그대로 탑니다. 안전벨트를 모르는 차는 그 아기를 재웁니다. */
    fun uncall(preset: SpiritPreset) = post { now ->
        FamilyRepository.update { it.uncall(preset) }
        if (engine.beltsKnown) engine.refreshSeats(now) else wake(preset, awake = false, now)
    }

    private fun callNow(preset: SpiritPreset, now: Long) {
        FamilyRepository.update { it.call(preset) }
        if (engine.beltsKnown) engine.refreshSeats(now) else wake(preset, awake = true, now)
    }

    private fun wake(preset: SpiritPreset, awake: Boolean, now: Long) {
        val seat = FamilyRepository.state.spirits.firstOrNull { it.preset == preset }?.seat ?: return
        if ((seat in FamilyRepository.awakeSeats) != awake) engine.onSeatTap(seat, now)
    }

    /** 뒷자리 안전벨트가 하나라도 채워져 있는지. 어느 스레드에서 불러도 됩니다. */
    fun rearBelted(): Boolean = engine.rearBelted

    /** 트립 중이고 P가 아닌지. 어느 스레드에서 불러도 됩니다. */
    fun driving(): Boolean = engine.driving

    /** debug 시연 전용입니다. */
    fun rewindLeft(seat: SeatSlot, byMs: Long) = post { engine.rewindLeft(seat, byMs) }

    /** debug 시연 전용입니다. 한 시간을 달려야 나오는 이벤트를 엔진을 거치지 않고 넣습니다. */
    fun inject(event: DriveEvent) = post { onEvent(event) }

    /**
     * 콜백 도착 시각이 아니라 신호 시각으로 판정합니다. 전달이 밀리면 도착 시각 간격이 벌어졌다 좁아져
     * 가짜 감속도가 되기 때문입니다. 시각이 이상하면 도착 시각으로 대체합니다.
     */
    fun onSpeed(mps: Float, sensorNanos: Long) {
        val now = System.currentTimeMillis()
        val ageMs = (SystemClock.elapsedRealtimeNanos() - sensorNanos) / 1_000_000
        post(if (ageMs in 0..MAX_SIGNAL_AGE_MS) now - ageMs else now) { engine.onSpeed(mps, it) }
    }

    fun tick() = post { engine.tick(it) }

    fun release() {
        unregisters.forEach { runCatching(it) }
        unregisters.clear()
        executor.shutdown()
    }

    private fun failed(key: String, e: Exception) = Log.e(TAG, "$key listener failed", e).let { }

    private fun VehicleGear?.toEngineGear() = when (this) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_DRIVE, VehicleGear.GEAR_1, VehicleGear.GEAR_2, VehicleGear.GEAR_3, VehicleGear.GEAR_4,
        VehicleGear.GEAR_5, VehicleGear.GEAR_6, VehicleGear.GEAR_7, VehicleGear.GEAR_8, VehicleGear.GEAR_9 -> Gear.DRIVE
        else -> Gear.UNKNOWN
    }

    private fun TurnSignalValue?.toTurnSide() = when (this) {
        TurnSignalValue.LEFT -> TurnSide.LEFT
        TurnSignalValue.RIGHT -> TurnSide.RIGHT
        else -> TurnSide.OFF
    }

    private companion object {
        const val TAG = "DriveEngine"
        const val TICK_MARGIN_MS = 100L
        const val MAX_SIGNAL_AGE_MS = 2_000L
        const val DOOR_CLOSED = 0
    }
}
