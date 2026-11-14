package com.example.pleosapp.engine

import kotlin.math.abs

/** 판정 임계값. 전부 에뮬레이터 기준 초안이며 실차 데이터로 조정해야 합니다. */
data class EngineConfig(
    /** 감속도를 재는 시간 창. 한 샘플의 튐으로 판정되지 않게 합니다. */
    val decelWindowMs: Long = 500,
    val minDecelWindowMs: Long = 250,
    /** 샘플 간격이 이보다 벌어지면 계산을 끊습니다(터널·서비스 지연 대비). */
    val maxSampleGapMs: Long = 2_000,

    val hardBrakeDecel: Float = 3.5f,
    val hardBrakeRearmDecel: Float = 1.5f,
    val hardBrakeMinSpeed: Float = 3f,

    val stopEpisodeMinSpeed: Float = 5f,
    val stoppedSpeed: Float = 0.3f,
    val smoothStopMaxDecel: Float = 2.5f,
    val cruiseDecel: Float = 0.5f,

    /** 좌측이 음수입니다(AAOS PERF_STEERING_ANGLE 규약). */
    val maneuverEnterDeg: Float = 15f,
    val maneuverExitDeg: Float = 7f,
    val maneuverMinSpeed: Float = 1.5f,
    val signalLookbackMs: Long = 8_000,

    /** 이만큼 연속으로 달린 뒤 쉬면 휴식 보상을 줍니다. 2시간까지 버티게 만들지 않으려고 1시간으로 둡니다. */
    val restEligibleDriveMs: Long = 1 * HOUR_MS,
    val restMs: Long = DEFAULT_REST_MS,
    /** 쉬고 나서 이 안에 다시 출발해야 "가는 길에 쉰 것"입니다. 목적지 도착과 구분합니다. */
    val restMaxGapMs: Long = 2 * HOUR_MS,

    /** P단을 이만큼 유지해야 도착으로 봅니다. 주차 중 P↔R 전환이 트립을 쪼개지 않게 합니다. */
    val arrivalHoldMs: Long = 3_000,
    /** 뒷문이 닫힌 뒤 벨트를 채울 시간을 주고 나서 부탁합니다. */
    val rearBeltAskDelayMs: Long = 6_000,
    /** 도착 직후 뒷문이 움직이는 것은 내리는 중으로 봅니다. */
    val boardingQuietMs: Long = 60_000,

    val rewardCooldownMs: Long = 5_000,
    /** 트립당 보상 상한 = 기본 + km당. 짧은 왕복으로 깃털을 모으는 것을 막습니다. */
    val baseRewardsPerTrip: Int = 10,
    val rewardsPerKm: Int = 6,
    /** 출발 벨트 보너스는 트립이 바뀌어도 이 간격 안에서는 한 번만(P↔D 반복으로 깃털을 모으는 것을 막습니다). */
    val beltRewardCooldownMs: Long = 10 * MINUTE_MS,

    val healthCodes: Set<String> = DEFAULT_HEALTH_CODES,
    val beltCode: String = "SEAT_BELT",
) {
    companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
        const val DEFAULT_REST_MS = 15 * MINUTE_MS

        /** 이만큼 비었다가 다시 타면 소리 내어 반깁니다. 초안이며 실제 등하원 간격으로 조정해야 합니다. */
        const val LONG_ABSENCE_MS = HOUR_MS

        /** 뒷자리 안전벨트를 맨 채 도착했을 때 낭독을 기다리는 시간. 이 안에 모두 풀리면 읽고, 지나면 자는 아이로 봅니다. */
        const val REAR_QUIET_WAIT_MS = 10_000L
        val DEFAULT_HEALTH_CODES = setOf(
            "TPMS", "ENGINE_OIL_PRESSURE", "ENGINE_TEMPERATURE", "LOW_VOLT_BATTERY",
            "ABS", "AIRBAG", "EV_SYSTEM", "ENGINE", "MASTER_WARNING", PetState.WASHER_CODE,
        ) + PetState.FOOD_CODES
    }
}

/**
 * 차량 신호를 받아 운전 이벤트를 만드는 판정기. Android에 의존하지 않고 시간도 입력으로만 받아
 * 단위 테스트로 재현할 수 있습니다. 스레드 안전하지 않으므로 호출자가 한 스레드에서 호출해야 합니다.
 */
class DriveEventEngine(
    private val config: EngineConfig = EngineConfig(),
    /** 원장에서 복원한 정령의 질병. 앱이 꺼진 사이 정비한 경우를 첫 경고등 값과 비교해 찾습니다. */
    knownAilments: Set<String> = emptySet(),
    /** 원장에서 복원한 휴식 시계. 이것이 없으면 시동을 끄고 쉰 시간이 휴식으로 인정되지 않습니다. */
    restored: RestClock = RestClock(),
    /** 내린 채로 앱이 다시 뜬 자리. 내린 시각은 남기지 않으므로 오래 떨어진 것으로 봅니다. */
    leftSeats: Set<SeatSlot> = emptySet(),
    private val emit: (DriveEvent) -> Unit,
) {
    private class Sample(val atMs: Long, val speed: Float)

    private var gear: Gear? = null
    private var inTrip = false

    private var tripId = 0L
    private var tripStartMs = 0L
    private var distanceM = 0f
    private var smoothStops = 0
    private var hardBrakes = 0
    private var signalsUsed = 0
    private var signalsMissed = 0
    private var rewardsGiven = 0
    private val lastRewardAt = HashMap<Class<*>, Long>()
    private var lastBeltRewardAt = -1L

    private val rawSpeeds = ArrayDeque<Float>()
    private val window = ArrayDeque<Sample>()
    private var speed = 0f
    private var brakePressed: Boolean? = null
    private var hardLatched = false
    private var episodeArmed = false
    private var episodeHard = false
    private var episodePeakDecel = 0f

    private var signal = TurnSide.OFF
    private var signalSide = TurnSide.OFF
    private var signalOnAt = 0L
    private var maneuverActive = false

    private var activeTelltales: Set<String>? = null
    private var ailments = knownAilments

    private var continuousDriveMs = restored.driveMs
    private var lastDriveTickAt = 0L
    private var restStartedAt: Long? = restored.parkedSinceMs
    private var restedBeforeTrip = false

    /** P단에 들어온 시각. [EngineConfig.arrivalHoldMs]가 지나면 트립을 끝냅니다. */
    private var pendingParkAt: Long? = null
    private var tripPeakBrakeDecel = 0f

    private class SeatState {
        var doorOpen = false
        var buckled: Boolean? = null
        var boardedAt: Long? = null
        var asked = false
        var lastBuckleRewardAt = -1L
        /** 벨트를 풀었으면 다음 문 닫힘은 내린 것입니다. */
        var leaving = false
        /** 내린 시각. 같은 자리 벨트가 다시 채워지면 반깁니다. */
        var leftAt: Long? = null
        /** P가 아닐 때 내렸습니다. 다음 도착 때 알립니다. */
        var leftUnannounced = false
        var awake = false
        /** 안전벨트 신호 없이 화면에서 깨웠습니다. */
        var tapped = false
    }

    private val seats = SeatSlot.entries.associateWith { SeatState() }

    init {
        leftSeats.forEach { seats.getValue(it).leftAt = 0L }
    }

    /** P에 들어온 뒤의 3초는 아직 트립이지만 차는 이미 서 있습니다. */
    private val parked: Boolean get() = !inTrip || pendingParkAt != null

    /** 뒷자리 안전벨트가 하나라도 채워져 있는지. 이 값과 [driving]은 다른 스레드에서 읽어도 됩니다. */
    @Volatile
    var rearBelted = false
        private set

    /**
     * 트립 중이고 P가 아닌지. 목소리는 이 값이 참이면 말하지 않습니다.
     * 원장에서 되살린 `together.inTrip`과 달리, 시동이 꺼진 채 끝난 트립이 남지 않습니다. 출발 사건을 내기 전에 켭니다.
     */
    @Volatile
    var driving = false
        private set

    private var rearBelts = 0
    private var driverBelted = false
    private var lastTripEndAt: Long? = null
    private val tripSeats = LinkedHashSet<SeatSlot>()
    private val tripTappedSeats = LinkedHashSet<SeatSlot>()

    fun onGear(next: Gear, atMs: Long) {
        if (next == Gear.UNKNOWN || next == gear) return
        gear = next
        tick(atMs)
        if (next == Gear.PARK) {
            if (inTrip) {
                pendingParkAt = atMs
                driving = false
                tick(atMs)
            }
            if (restStartedAt == null) restStartedAt = atMs
        } else if (!inTrip) {
            startTrip(atMs)
        } else {
            pendingParkAt = null
            driving = true
            restStartedAt = null
        }
    }

    fun onSpeed(mps: Float, atMs: Long) {
        val previous = window.lastOrNull()
        // 신호 시각을 쓰므로 전달 순서가 뒤바뀐 샘플이 올 수 있습니다.
        if (previous != null && atMs <= previous.atMs) return
        if (previous != null && atMs - previous.atMs > config.maxSampleGapMs) {
            window.clear()
            rawSpeeds.clear()
        }
        rawSpeeds.addLast(abs(mps))
        if (rawSpeeds.size > MEDIAN_SAMPLES) rawSpeeds.removeFirst()
        // 중앙값 필터: 튀는 샘플 하나가 시간 창의 끝값이 되어 급제동으로 오판되는 것을 막습니다.
        val v = if (rawSpeeds.size == MEDIAN_SAMPLES) rawSpeeds.sorted()[1] else rawSpeeds.last()
        window.lastOrNull()?.let { last ->
            if (inTrip) distanceM += (last.speed + v) / 2f * (atMs - last.atMs) / 1000f
        }
        window.addLast(Sample(atMs, v))
        while (window.size > 2 && atMs - window[1].atMs >= config.decelWindowMs) window.removeFirst()
        speed = v
        tick(atMs)
        if (inTrip) evaluateBraking(v, atMs)
    }

    fun onBrake(pressed: Boolean, atMs: Long) {
        brakePressed = pressed
        if (!pressed) hardLatched = false
        tick(atMs)
    }

    fun onTurnSignal(side: TurnSide, atMs: Long) {
        if (side != TurnSide.OFF && side != signal) {
            signalSide = side
            signalOnAt = atMs
        }
        signal = side
        tick(atMs)
    }

    fun onSteering(angleDeg: Float, atMs: Long) {
        val magnitude = abs(angleDeg)
        if (maneuverActive) {
            if (magnitude <= config.maneuverExitDeg) maneuverActive = false
        } else if (magnitude >= config.maneuverEnterDeg && inTrip && speed >= config.maneuverMinSpeed) {
            maneuverActive = true
            judgeManeuver(if (angleDeg < 0) TurnSide.LEFT else TurnSide.RIGHT, atMs)
        }
        tick(atMs)
    }

    fun onTelltales(active: Set<String>, atMs: Long) {
        val tracked = active intersect config.healthCodes
        (tracked - ailments).sorted().forEach { emit(DriveEvent.HealthAlert(atMs, it)) }
        (ailments - tracked).sorted().forEach { emit(DriveEvent.HealthRecovered(atMs, it)) }
        ailments = tracked
        activeTelltales = active
        tick(atMs)
    }

    /** 뒷문이 열렸다 닫히면 누군가 탄 것으로 봅니다. 착석 센서는 에뮬레이터에서 값이 뒤집혀 쓰지 않습니다. */
    fun onRearDoor(seat: SeatSlot, open: Boolean, atMs: Long) {
        val state = seats.getValue(seat)
        if (state.doorOpen && !open) {
            // 가운데 자리는 문이 없어서 양옆 뒷문으로 내립니다.
            val leavers = (if (seat.isRear) setOf(seat, SeatSlot.REAR_CENTER) else setOf(seat)).filter { seats.getValue(it).leaving }
            val justArrived = lastTripEndAt?.let { atMs - it < config.boardingQuietMs } ?: false
            if (leavers.isNotEmpty()) {
                leavers.forEach { depart(it, atMs) }
            } else if (seat.isRear && !inTrip && !justArrived) {
                state.boardedAt = atMs
                state.asked = false
            }
        }
        state.doorOpen = open
        tick(atMs)
    }

    private fun depart(seat: SeatSlot, atMs: Long) {
        val state = seats.getValue(seat)
        state.leaving = false
        state.leftAt = atMs
        if (parked) emit(DriveEvent.SeatLeft(atMs, seat)) else state.leftUnannounced = true
    }

    fun onSeatBelt(seat: SeatSlot, buckled: Boolean, atMs: Long) {
        val state = seats.getValue(seat)
        val justBuckled = buckled && state.buckled == false
        // 벨트를 푸는 순간에는 반응하지 않습니다. 문이 닫혀야 내린 것으로 봅니다.
        state.leaving = if (buckled) false else state.leaving || state.buckled == true
        state.buckled = buckled
        rearBelted = seats.any { (slot, it) -> slot.isRear && it.buckled == true }
        if (justBuckled) state.boardedAt = null
        if (justBuckled) returnTo(seat, atMs)
        // 깃털은 뒷자리만 받습니다. 조수석은 출발 안전벨트 보너스(BeltsReady)로 이미 보상합니다.
        if (justBuckled && seat.isRear) {
            val lastAt = state.lastBuckleRewardAt
            if (lastAt < 0 || atMs - lastAt >= config.beltRewardCooldownMs) {
                state.lastBuckleRewardAt = atMs
                rearBelts++
                emit(DriveEvent.RearBeltBuckled(atMs, seat, parked = !inTrip))
            }
        }
        if (buckled) {
            if (inTrip) tripSeats += seat
            val wasTapped = state.tapped
            state.tapped = false
            // 화면에서 깨운 자리라도 실제로 채우면 인사합니다.
            if (!state.awake || wasTapped) {
                state.awake = true
                // 앱이 시작할 때 이미 채워져 있던 안전벨트는 조용히 깨웁니다.
                emitSeats(atMs, newly = if (justBuckled) setOf(seat) else emptySet())
            }
        } else if (state.awake && !state.tapped && !inTrip) {
            // 화면에서 깨운 자리는 해제 신호가 반복되어도 그대로 둡니다.
            state.awake = false
            emitSeats(atMs, newly = emptySet())
        }
        tick(atMs)
    }

    private fun returnTo(seat: SeatSlot, atMs: Long) {
        val state = seats.getValue(seat)
        val leftAt = state.leftAt ?: return
        state.leftAt = null
        // 알리기 전에 돌아왔으면 아무 일도 없었던 것입니다.
        if (state.leftUnannounced) state.leftUnannounced = false
        else emit(DriveEvent.SeatReturned(atMs, seat, atMs - leftAt, parked))
    }

    /** 오래 비워 둔 것처럼 내린 시각을 앞당깁니다. 시연에서 한 시간을 기다릴 수 없어 debug 콘솔이 씁니다. */
    fun rewindLeft(seat: SeatSlot, byMs: Long) {
        val state = seats.getValue(seat)
        state.leftAt = state.leftAt?.minus(byMs)
    }

    /** 안전벨트 신호가 없거나 버클 변화가 없는 자리를 화면에서 깨우고 재웁니다. 주차 중에만 받습니다. */
    fun onSeatTap(seat: SeatSlot, atMs: Long) {
        if (inTrip) return
        val state = seats.getValue(seat)
        if (state.buckled == true) return
        state.tapped = !state.tapped
        state.awake = state.tapped
        emitSeats(atMs, newly = emptySet())
    }

    /** 이 차가 안전벨트 상태를 한 번이라도 알려 줬는지. 모르는 차는 화면에서 누른 아기만 탑니다. */
    val beltsKnown: Boolean get() = seats.values.any { it.buckled != null }

    /** 자리는 그대로지만 탈 아기를 다시 정해야 할 때 지금 자리를 다시 알립니다. 아기를 부르면 씁니다. */
    fun refreshSeats(atMs: Long) = emitSeats(atMs, newly = emptySet())

    private fun emitSeats(atMs: Long, newly: Set<SeatSlot>) {
        val awake = seats.filterValues { it.awake }.keys
        val waiting = seats.values.any { it.boardedAt != null && it.buckled == false }
        val belted = seats.filterValues { it.awake && !it.tapped }.keys
        emit(DriveEvent.SeatsAwake(atMs, awake.toSet(), newly, waiting, parked = !inTrip, belted = belted.toSet()))
    }

    /**
     * 시계가 [deltaMs]만큼 옮겨졌습니다. 차는 시동을 건 뒤 GNSS나 통신망으로 시계를 맞출 수 있습니다.
     * 재던 시각을 함께 옮겨 트립 길이와 기다림이 실제 시간 그대로 남게 합니다. 트립 ID는 원장과 맞춰야 해서 그대로 둡니다.
     */
    fun onClockMoved(deltaMs: Long) {
        tripStartMs += deltaMs
        lastDriveTickAt += deltaMs
        signalOnAt += deltaMs
        if (lastBeltRewardAt >= 0) lastBeltRewardAt += deltaMs
        lastRewardAt.replaceAll { _, at -> at + deltaMs }
        restStartedAt = restStartedAt?.plus(deltaMs)
        pendingParkAt = pendingParkAt?.plus(deltaMs)
        lastTripEndAt = lastTripEndAt?.plus(deltaMs)
        seats.values.forEach {
            it.boardedAt = it.boardedAt?.plus(deltaMs)
            it.leftAt = it.leftAt?.plus(deltaMs)
            if (it.lastBuckleRewardAt >= 0) it.lastBuckleRewardAt += deltaMs
        }
        // 샘플 시각은 옛 시계입니다. 다음 샘플부터 새로 잽니다.
        window.clear()
        rawSpeeds.clear()
    }

    /** 시간으로만 판정하는 규칙(도착, 벨트 부탁)을 진행합니다. 신호가 없어도 주기적으로 호출하세요. */
    fun tick(atMs: Long) {
        pendingParkAt?.let { parkedAt ->
            if (atMs - parkedAt >= config.arrivalHoldMs) endTrip(parkedAt)
        }
        if (inTrip) {
            continuousDriveMs += (atMs - lastDriveTickAt).coerceAtLeast(0)
            lastDriveTickAt = atMs
            return
        }
        askRearBelts(atMs)
    }

    private fun askRearBelts(atMs: Long) {
        seats.forEach { (seat, state) ->
            val boardedAt = state.boardedAt ?: return@forEach
            val waited = atMs - boardedAt >= config.rearBeltAskDelayMs
            if (waited && !state.asked && state.buckled == false && !state.doorOpen) {
                state.asked = true
                emit(DriveEvent.RearBeltRequested(atMs, seat))
            }
        }
    }

    /** 다시 출발할 때 판정합니다. 쉬고 이어서 달린 경우만 휴식이고, 그대로 주차를 끝낸 도착은 휴식이 아닙니다. */
    private fun settleRest(atMs: Long) {
        val gap = atMs - (restStartedAt ?: return)
        if (gap < config.restMs) return
        if (continuousDriveMs >= config.restEligibleDriveMs && gap <= config.restMaxGapMs) {
            restedBeforeTrip = true
            emit(DriveEvent.RestTaken(atMs, gap))
        }
        continuousDriveMs = 0
    }

    private fun startTrip(atMs: Long) {
        settleRest(atMs)
        inTrip = true
        driving = true
        tripId = atMs
        tripStartMs = atMs
        lastDriveTickAt = atMs
        restStartedAt = null
        distanceM = 0f
        smoothStops = 0
        hardBrakes = 0
        signalsUsed = 0
        signalsMissed = 0
        rewardsGiven = 0
        lastRewardAt.clear()
        hardLatched = false
        episodeArmed = false
        tripPeakBrakeDecel = 0f
        tripSeats.clear()
        tripTappedSeats.clear()
        var woke = false
        seats.forEach { (seat, state) ->
            state.boardedAt = null
            // 문이 열린 채 출발했으면 그 문이 닫힐 때 내린 것을 알 수 있습니다.
            if (!state.doorOpen) state.leaving = false
            if (state.buckled == true) {
                tripSeats += seat
                if (!state.awake) woke = true
                state.awake = true
            }
            if (state.tapped) tripTappedSeats += seat
        }
        // 시작 시각을 ID로 씁니다. 재시작해도 원장의 트립끼리 겹치지 않습니다.
        emit(DriveEvent.TripStarted(atMs, tripId))
        if (woke) emitSeats(atMs, newly = emptySet())
        activeTelltales?.let { active ->
            if (config.beltCode in active) {
                emit(DriveEvent.BeltsMissing(atMs))
            } else {
                driverBelted = true
                if (lastBeltRewardAt < 0 || atMs - lastBeltRewardAt >= config.beltRewardCooldownMs) {
                    if (reward(DriveEvent.BeltsReady(atMs))) lastBeltRewardAt = atMs
                }
            }
        }
    }

    private fun endTrip(atMs: Long) {
        inTrip = false
        driving = false
        pendingParkAt = null
        lastTripEndAt = atMs
        emit(
            DriveEvent.TripEnded(
                atMs,
                TripSummary(
                    tripId, (atMs - tripStartMs).coerceAtLeast(0), distanceM, smoothStops, hardBrakes, signalsUsed, signalsMissed,
                    peakBrakeDecel = tripPeakBrakeDecel, rearBelts = rearBelts, restedBefore = restedBeforeTrip,
                    seats = tripSeats.toSet(), tappedSeats = (tripTappedSeats - tripSeats).toSet(),
                    rearStillBelted = rearBelted,
                    driverBelted = driverBelted,
                ),
            ),
        )
        rearBelts = 0
        restedBeforeTrip = false
        driverBelted = false
        // 도착하면 모두 잠듭니다. 안전벨트를 그대로 둔 채 다시 출발하면 startTrip이 조용히 깨웁니다.
        val anyAwake = seats.values.any { it.awake }
        seats.values.forEach {
            it.awake = false
            it.tapped = false
        }
        if (anyAwake) emitSeats(atMs, newly = emptySet())
        seats.forEach { (seat, state) ->
            if (state.leftUnannounced) {
                state.leftUnannounced = false
                emit(DriveEvent.SeatLeft(atMs, seat))
            }
        }
    }

    private fun evaluateBraking(v: Float, atMs: Long) {
        val decel = windowDecel()
        if (decel != null) {
            if (hardLatched && decel < config.hardBrakeRearmDecel) hardLatched = false
            if (v >= config.stopEpisodeMinSpeed && decel <= config.cruiseDecel) {
                episodeArmed = true
                episodeHard = false
                episodePeakDecel = 0f
            }
            if (episodeArmed && decel > episodePeakDecel) episodePeakDecel = decel

            val fromSpeed = window.first().speed
            // 브레이크 조건이 없으면 고속 관성 주행의 감속까지 급제동으로 잡힙니다.
            if (decel >= config.hardBrakeDecel && brakePressed == true && !hardLatched &&
                fromSpeed >= config.hardBrakeMinSpeed
            ) {
                hardLatched = true
                episodeHard = true
                hardBrakes++
                emit(DriveEvent.HardBrake(atMs, decel, fromSpeed))
            }
            if (hardLatched && decel > tripPeakBrakeDecel) tripPeakBrakeDecel = decel
        }
        if (episodeArmed && v <= config.stoppedSpeed) {
            episodeArmed = false
            if (!episodeHard && episodePeakDecel <= config.smoothStopMaxDecel) {
                if (reward(DriveEvent.SmoothStop(atMs, episodePeakDecel))) smoothStops++
            }
        }
    }

    private fun windowDecel(): Float? {
        val first = window.firstOrNull() ?: return null
        val last = window.last()
        val spanMs = last.atMs - first.atMs
        if (spanMs < config.minDecelWindowMs) return null
        return (first.speed - last.speed) / (spanMs / 1000f)
    }

    private fun judgeManeuver(side: TurnSide, atMs: Long) {
        val leadMs = atMs - signalOnAt
        val signalled = signal == side || (signalSide == side && leadMs <= config.signalLookbackMs)
        if (signalled) {
            if (reward(DriveEvent.SignalUsed(atMs, side, leadMs))) signalsUsed++
        } else {
            signalsMissed++
            emit(DriveEvent.SignalMissed(atMs, side))
        }
    }

    /** 쿨다운과 트립 상한을 통과한 보상만 내보냅니다. */
    private fun reward(event: DriveEvent): Boolean {
        val lastAt = lastRewardAt[event.javaClass]
        if (lastAt != null && event.atMs - lastAt < config.rewardCooldownMs) return false
        val budget = config.baseRewardsPerTrip + (distanceM / 1000f * config.rewardsPerKm).toInt()
        if (rewardsGiven >= budget) return false
        rewardsGiven++
        lastRewardAt[event.javaClass] = event.atMs
        emit(event)
        return true
    }

    private companion object {
        const val MEDIAN_SAMPLES = 3
    }
}
