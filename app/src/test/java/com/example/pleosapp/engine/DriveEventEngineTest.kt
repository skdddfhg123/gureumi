package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class DriveEventEngineTest {

    private class Rig(config: EngineConfig = EngineConfig(arrivalHoldMs = 0)) {
        val events = mutableListOf<DriveEvent>()
        val engine = DriveEventEngine(config) { events += it }
        var now = 1_000L
        var v = 0f

        inline fun <reified T : DriveEvent> count() = events.count { it is T }

        fun gear(g: Gear) = engine.onGear(g, now)
        fun brake(pressed: Boolean) = engine.onBrake(pressed, now)
        fun signal(side: TurnSide) = engine.onTurnSignal(side, now)
        fun steer(deg: Float) = engine.onSteering(deg, now)
        fun idle(ms: Long) { now += ms }

        /** 100ms 간격 샘플로 속도를 target까지 가속도 accel(m/s²)로 바꿉니다. */
        fun rampTo(target: Float, accel: Float) {
            while (v != target) {
                now += STEP_MS
                val delta = accel * STEP_MS / 1000f
                v = if (v < target) minOf(v + delta, target) else max(v - delta, target)
                engine.onSpeed(v, now)
            }
        }

        fun cruise(ms: Long) {
            var left = ms
            while (left > 0) {
                now += STEP_MS
                engine.onSpeed(v, now)
                left -= STEP_MS
            }
        }

        fun startDriving(speed: Float = 20f) {
            gear(Gear.DRIVE)
            rampTo(speed, 3f)
            cruise(1_000)
        }

        companion object { const val STEP_MS = 100L }
    }

    @Test
    fun `기어 전이로 트립이 시작되고 끝나며 중복 콜백은 무시한다`() {
        val rig = Rig()
        rig.gear(Gear.PARK)
        rig.gear(Gear.PARK)
        rig.gear(Gear.DRIVE)
        rig.gear(Gear.DRIVE)
        rig.gear(Gear.REVERSE)
        rig.gear(Gear.PARK)
        assertEquals(1, rig.count<DriveEvent.TripStarted>())
        assertEquals(1, rig.count<DriveEvent.TripEnded>())
    }

    @Test
    fun `속도를 적분해 트립 거리를 계산한다`() {
        val rig = Rig()
        rig.gear(Gear.DRIVE)
        rig.rampTo(10f, 100f)
        rig.cruise(10_000)
        rig.gear(Gear.PARK)
        val summary = rig.events.filterIsInstance<DriveEvent.TripEnded>().single().summary
        assertEquals(100f, summary.distanceM, 5f)
    }

    @Test
    fun `브레이크를 밟고 강하게 감속하면 급제동이다`() {
        val rig = Rig()
        rig.startDriving()
        rig.brake(true)
        rig.rampTo(5f, 7f)
        assertEquals(1, rig.count<DriveEvent.HardBrake>())
    }

    @Test
    fun `브레이크 없이 관성으로 감속하면 급제동이 아니다`() {
        val rig = Rig()
        rig.startDriving(30f)
        rig.rampTo(25f, 4f)
        assertEquals(0, rig.count<DriveEvent.HardBrake>())
    }

    @Test
    fun `한 번의 제동은 급제동 한 건이다`() {
        val rig = Rig()
        rig.startDriving(30f)
        rig.brake(true)
        rig.rampTo(0f, 8f)
        assertEquals(1, rig.count<DriveEvent.HardBrake>())
    }

    @Test
    fun `속도가 낮으면 급제동으로 치지 않는다`() {
        val rig = Rig()
        rig.startDriving(2f)
        rig.brake(true)
        rig.rampTo(0f, 8f)
        assertEquals(0, rig.count<DriveEvent.HardBrake>())
    }

    @Test
    fun `튀는 샘플 하나는 급제동이 아니다`() {
        val rig = Rig()
        rig.startDriving(20f)
        rig.brake(true)
        rig.now += 100
        rig.engine.onSpeed(5f, rig.now)
        rig.cruise(1_000)
        assertEquals(0, rig.count<DriveEvent.HardBrake>())
    }

    @Test
    fun `샘플이 끊긴 구간은 감속으로 계산하지 않는다`() {
        val rig = Rig()
        rig.startDriving(20f)
        rig.brake(true)
        rig.idle(5_000)
        rig.engine.onSpeed(5f, rig.now)
        rig.v = 5f
        rig.cruise(1_000)
        assertEquals(0, rig.count<DriveEvent.HardBrake>())
    }

    @Test
    fun `완만하게 멈추면 부드러운 정지다`() {
        val rig = Rig()
        rig.startDriving()
        rig.brake(true)
        rig.rampTo(0f, 2f)
        assertEquals(1, rig.count<DriveEvent.SmoothStop>())
    }

    @Test
    fun `급제동으로 멈추면 부드러운 정지가 아니다`() {
        val rig = Rig()
        rig.startDriving()
        rig.brake(true)
        rig.rampTo(0f, 7f)
        assertEquals(0, rig.count<DriveEvent.SmoothStop>())
    }

    @Test
    fun `저속에서 멈춘 것은 보상하지 않는다`() {
        val rig = Rig()
        rig.startDriving(3f)
        rig.brake(true)
        rig.rampTo(0f, 1f)
        assertEquals(0, rig.count<DriveEvent.SmoothStop>())
    }

    @Test
    fun `같은 방향 깜빡이를 켜고 조향하면 칭찬한다`() {
        val rig = Rig()
        rig.startDriving()
        rig.signal(TurnSide.LEFT)
        rig.cruise(1_500)
        rig.steer(-30f)
        val used = rig.events.filterIsInstance<DriveEvent.SignalUsed>().single()
        assertEquals(TurnSide.LEFT, used.side)
        assertEquals(1_500L, used.leadMs)
    }

    @Test
    fun `깜빡이가 꺼진 직후의 조향도 인정한다`() {
        val rig = Rig()
        rig.startDriving()
        rig.signal(TurnSide.RIGHT)
        rig.cruise(1_000)
        rig.signal(TurnSide.OFF)
        rig.cruise(1_000)
        rig.steer(30f)
        assertEquals(1, rig.count<DriveEvent.SignalUsed>())
    }

    @Test
    fun `깜빡이가 없거나 반대쪽이거나 너무 오래됐으면 놓친 것이다`() {
        val rig = Rig()
        rig.startDriving()
        rig.steer(30f)
        rig.steer(0f)
        rig.signal(TurnSide.LEFT)
        rig.steer(30f)
        rig.steer(0f)
        rig.signal(TurnSide.OFF)
        rig.cruise(9_000)
        rig.steer(-30f)
        assertEquals(3, rig.count<DriveEvent.SignalMissed>())
        assertEquals(0, rig.count<DriveEvent.SignalUsed>())
    }

    @Test
    fun `정차 중 조향과 복귀 조향은 판정하지 않는다`() {
        val rig = Rig()
        rig.gear(Gear.DRIVE)
        rig.steer(90f)
        rig.steer(0f)
        assertEquals(0, rig.count<DriveEvent.SignalMissed>())

        rig.rampTo(20f, 5f)
        rig.steer(30f)
        rig.steer(20f)
        rig.steer(30f)
        assertEquals(1, rig.count<DriveEvent.SignalMissed>())
    }

    @Test
    fun `출발 시 벨트 경고등으로 벨트 상태를 판정한다`() {
        val ready = Rig()
        ready.engine.onTelltales(emptySet(), ready.now)
        ready.gear(Gear.DRIVE)
        assertEquals(1, ready.count<DriveEvent.BeltsReady>())

        val missing = Rig()
        missing.engine.onTelltales(setOf("SEAT_BELT"), missing.now)
        missing.gear(Gear.DRIVE)
        assertEquals(1, missing.count<DriveEvent.BeltsMissing>())

        val unknown = Rig()
        unknown.gear(Gear.DRIVE)
        assertEquals(0, unknown.count<DriveEvent.BeltsReady>() + unknown.count<DriveEvent.BeltsMissing>())
    }

    @Test
    fun `보상은 쿨다운 안에서 한 번만 준다`() {
        val rig = Rig()
        rig.startDriving()
        rig.signal(TurnSide.LEFT)
        rig.steer(-30f)
        rig.steer(0f)
        rig.steer(-30f)
        assertEquals(1, rig.count<DriveEvent.SignalUsed>())
    }

    @Test
    fun `트립 보상에는 상한이 있다`() {
        val rig = Rig(EngineConfig(baseRewardsPerTrip = 2, rewardsPerKm = 0, rewardCooldownMs = 0))
        rig.startDriving()
        rig.signal(TurnSide.LEFT)
        repeat(5) {
            rig.steer(-30f)
            rig.steer(0f)
        }
        assertEquals(2, rig.count<DriveEvent.SignalUsed>())
    }

    @Test
    fun `1시간 이상 달리고 15분 쉬고 다시 출발하면 휴식이다`() {
        val rig = Rig()
        rig.gear(Gear.DRIVE)
        rig.idle(EngineConfig.HOUR_MS)
        rig.gear(Gear.PARK)
        rig.idle(15 * EngineConfig.MINUTE_MS)
        assertEquals(0, rig.count<DriveEvent.RestTaken>())
        rig.gear(Gear.DRIVE)
        assertEquals(1, rig.count<DriveEvent.RestTaken>())
    }

    @Test
    fun `짧게 달린 뒤 쉬면 휴식이 아니다`() {
        val rig = Rig()
        rig.gear(Gear.DRIVE)
        rig.idle(30 * EngineConfig.MINUTE_MS)
        rig.gear(Gear.PARK)
        rig.idle(20 * EngineConfig.MINUTE_MS)
        rig.gear(Gear.DRIVE)
        assertEquals(0, rig.count<DriveEvent.RestTaken>())
    }

    @Test
    fun `목적지 도착 뒤 오래 세워 둔 것은 휴식이 아니고 주행 시간은 초기화된다`() {
        val rig = Rig()
        rig.gear(Gear.DRIVE)
        rig.idle(EngineConfig.HOUR_MS)
        rig.gear(Gear.PARK)
        rig.idle(10 * EngineConfig.HOUR_MS)
        rig.gear(Gear.DRIVE)
        rig.idle(EngineConfig.HOUR_MS / 2)
        rig.gear(Gear.PARK)
        rig.idle(20 * EngineConfig.MINUTE_MS)
        rig.gear(Gear.DRIVE)
        assertEquals(0, rig.count<DriveEvent.RestTaken>())
    }

    @Test
    fun `잠깐 정차해도 연속 운전 시간은 이어진다`() {
        val rig = Rig()
        rig.gear(Gear.DRIVE)
        rig.idle(40 * EngineConfig.MINUTE_MS)
        rig.gear(Gear.PARK)
        rig.idle(5 * EngineConfig.MINUTE_MS)
        rig.gear(Gear.DRIVE)
        rig.idle(30 * EngineConfig.MINUTE_MS)
        rig.gear(Gear.PARK)
        rig.idle(15 * EngineConfig.MINUTE_MS)
        rig.gear(Gear.DRIVE)
        assertEquals(1, rig.count<DriveEvent.RestTaken>())
    }

    @Test
    fun `경고등이 켜지면 알리고 꺼지면 회복을 알린다`() {
        val rig = Rig()
        rig.engine.onTelltales(setOf("TPMS", "HEADLAMP_LOW"), rig.now)
        rig.engine.onTelltales(setOf("TPMS"), rig.now)
        rig.engine.onTelltales(emptySet(), rig.now)
        assertEquals(listOf("TPMS"), rig.events.filterIsInstance<DriveEvent.HealthAlert>().map { it.code })
        assertEquals(listOf("TPMS"), rig.events.filterIsInstance<DriveEvent.HealthRecovered>().map { it.code })
    }

    @Test
    fun `앱이 꺼진 사이 정비했으면 시작할 때 회복을 알린다`() {
        val events = mutableListOf<DriveEvent>()
        val engine = DriveEventEngine(knownAilments = setOf("TPMS")) { events += it }
        engine.onTelltales(emptySet(), 1_000)
        assertEquals(listOf<DriveEvent>(DriveEvent.HealthRecovered(1_000, "TPMS")), events)
    }

    @Test
    fun `복원된 질병이 그대로면 다시 알리지 않는다`() {
        val events = mutableListOf<DriveEvent>()
        val engine = DriveEventEngine(knownAilments = setOf("TPMS")) { events += it }
        engine.onTelltales(setOf("TPMS"), 1_000)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `재시작해도 트립 ID가 겹치지 않는다`() {
        val first = Rig().apply { now = 5_000; gear(Gear.DRIVE) }
        val second = Rig().apply { now = 9_000; gear(Gear.DRIVE) }
        val ids = (first.events + second.events).filterIsInstance<DriveEvent.TripStarted>().map { it.tripId }
        assertEquals(listOf(5_000L, 9_000L), ids)
    }

    @Test
    fun `펫은 이벤트로 자라고 아프다`() {
        var pet = PetState()
        pet = pet.apply(DriveEvent.TripStarted(0, 1))
        repeat(150) { pet = pet.apply(DriveEvent.SmoothStop(0, 1f)) }
        assertEquals(2, pet.growthStep)
        assertEquals(Mood.HAPPY, pet.mood)

        repeat(2) { pet = pet.apply(DriveEvent.HardBrake(0, 7f, 20f)) }
        assertEquals(Mood.DIZZY, pet.mood)
        assertTrue(pet.apply(DriveEvent.TripStarted(0, 2)).sickness == 0)

        pet = pet.apply(DriveEvent.HealthAlert(0, "TPMS"))
        assertEquals(Mood.SICK, pet.mood)
        assertEquals("발이 아파. 타이어 공기압을 봐 줘.", pet.ailmentLine())
        pet = pet.apply(DriveEvent.HealthRecovered(0, "TPMS"))
        assertFalse(pet.mood == Mood.SICK)
    }
}
