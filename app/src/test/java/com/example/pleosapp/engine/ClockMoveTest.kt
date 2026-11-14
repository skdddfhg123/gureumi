package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 시계 점프 시나리오에서 찾은 문제의 회귀 시험입니다. 벽시계가 옮겨져도 잰 시간은 실제 시간이어야 합니다. */
class ClockMoveTest {

    private class Rig {
        val events = mutableListOf<DriveEvent>()
        val engine = DriveEventEngine(EngineConfig(arrivalHoldMs = HOLD_MS)) { events += it }
        var now = 1_000_000L

        fun idle(ms: Long) {
            now += ms
            engine.tick(now)
        }

        fun moveClock(deltaMs: Long) {
            now += deltaMs
            engine.onClockMoved(deltaMs)
        }

        fun speed(mps: Float, seconds: Int) = repeat(seconds * 10) {
            now += 100
            engine.onSpeed(mps, now)
        }

        val ended get() = events.filterIsInstance<DriveEvent.TripEnded>()
    }

    @Test
    fun `트립 중에 시계가 뒤로 가도 길이는 실제 시간이다`() {
        val rig = Rig()
        rig.engine.onGear(Gear.DRIVE, rig.now)
        rig.idle(3_000)
        rig.moveClock(-HOUR)
        rig.idle(6_000)
        rig.engine.onGear(Gear.PARK, rig.now)
        rig.idle(HOLD_MS)
        assertEquals(9_000L, rig.ended.single().summary.durationMs)
    }

    @Test
    fun `트립 중에 시계가 앞으로 가도 길이가 늘지 않는다`() {
        val rig = Rig()
        rig.engine.onGear(Gear.DRIVE, rig.now)
        rig.idle(3_000)
        rig.moveClock(HOUR)
        rig.idle(6_000)
        rig.engine.onGear(Gear.PARK, rig.now)
        rig.idle(HOLD_MS)
        assertEquals(9_000L, rig.ended.single().summary.durationMs)
    }

    @Test
    fun `P 뒤에 시계가 뒤로 가도 3초 뒤에 도착한다`() {
        val rig = Rig()
        rig.engine.onGear(Gear.DRIVE, rig.now)
        rig.idle(9_000)
        rig.engine.onGear(Gear.PARK, rig.now)
        rig.idle(1_000)
        rig.moveClock(-HOUR)
        rig.idle(HOLD_MS - 1_000)
        assertEquals(1, rig.ended.size)
    }

    @Test
    fun `도착 시각은 옮겨진 시계로 적고 트립 ID는 시작과 같다`() {
        val rig = Rig()
        val started = rig.now
        rig.engine.onGear(Gear.DRIVE, rig.now)
        rig.idle(3_000)
        rig.moveClock(-HOUR)
        rig.idle(6_000)
        val parkedAt = rig.now
        rig.engine.onGear(Gear.PARK, rig.now)
        rig.idle(HOLD_MS)
        val trip = rig.ended.single()
        assertEquals(parkedAt, trip.atMs)
        assertEquals(started, trip.summary.tripId)
        assertEquals(started, rig.events.filterIsInstance<DriveEvent.TripStarted>().single().tripId)
    }

    @Test
    fun `시계가 뒤로 가도 속도 신호를 버리지 않는다`() {
        val rig = Rig()
        rig.engine.onGear(Gear.DRIVE, rig.now)
        rig.speed(10f, 5)
        rig.moveClock(-HOUR)
        rig.speed(10f, 5)
        rig.engine.onGear(Gear.PARK, rig.now)
        rig.idle(HOLD_MS)
        val metres = rig.ended.single().summary.distanceM
        assertTrue("거리 $metres", metres in 90f..101f)
    }

    @Test
    fun `시계를 옮겨도 벨트 보상 간격은 실제 시간으로 잰다`() {
        val rig = Rig()
        rig.engine.onTelltales(emptySet(), rig.now)
        rig.engine.onGear(Gear.DRIVE, rig.now)
        rig.idle(9_000)
        rig.engine.onGear(Gear.PARK, rig.now)
        rig.idle(HOLD_MS)
        rig.moveClock(HOUR)
        rig.engine.onGear(Gear.DRIVE, rig.now)
        assertEquals("앞으로 옮겼다고 10분 간격이 지난 것이 아닙니다", 1, rig.events.count { it is DriveEvent.BeltsReady })
    }

    @Test
    fun `시계를 옮겨도 보상 대기는 실제 시간으로 잰다`() {
        val rig = Rig()
        rig.engine.onGear(Gear.DRIVE, rig.now)
        rig.speed(10f, 1)
        rig.engine.onTurnSignal(TurnSide.LEFT, rig.now)
        rig.engine.onSteering(-20f, rig.now)
        rig.engine.onSteering(0f, rig.now)
        rig.moveClock(-HOUR)
        rig.speed(10f, 6)
        rig.engine.onSteering(-20f, rig.now)
        assertEquals(2, rig.events.count { it is DriveEvent.SignalUsed })
    }

    @Test
    fun `시계가 앞으로 가도 방금 켠 깜빡이로 친다`() {
        val rig = Rig()
        rig.engine.onGear(Gear.DRIVE, rig.now)
        rig.speed(10f, 1)
        rig.engine.onTurnSignal(TurnSide.LEFT, rig.now)
        rig.engine.onTurnSignal(TurnSide.OFF, rig.now)
        rig.moveClock(HOUR)
        rig.speed(10f, 1)
        rig.engine.onSteering(-20f, rig.now)
        assertEquals(1, rig.events.count { it is DriveEvent.SignalUsed })
    }

    @Test
    fun `시계가 뒤로 가도 뒷문 부탁은 6초 뒤에 나온다`() {
        val rig = Rig()
        rig.engine.onSeatBelt(SeatSlot.REAR_LEFT, false, rig.now)
        rig.engine.onRearDoor(SeatSlot.REAR_LEFT, true, rig.now)
        rig.engine.onRearDoor(SeatSlot.REAR_LEFT, false, rig.now)
        rig.idle(1_000)
        rig.moveClock(-HOUR)
        rig.idle(5_000)
        assertEquals(1, rig.events.count { it is DriveEvent.RearBeltRequested })
    }

    @Test
    fun `도착 직후의 뒷문은 시계가 앞으로 가도 내리는 중이다`() {
        val rig = Rig()
        rig.engine.onSeatBelt(SeatSlot.REAR_LEFT, false, rig.now)
        rig.engine.onGear(Gear.DRIVE, rig.now)
        rig.idle(9_000)
        rig.engine.onGear(Gear.PARK, rig.now)
        rig.idle(HOLD_MS)
        rig.moveClock(HOUR)
        rig.engine.onRearDoor(SeatSlot.REAR_LEFT, true, rig.now)
        rig.engine.onRearDoor(SeatSlot.REAR_LEFT, false, rig.now)
        rig.idle(7_000)
        assertEquals(0, rig.events.count { it is DriveEvent.RearBeltRequested })
    }

    @Test
    fun `내려 있던 시간은 실제 시간이다`() {
        val rig = Rig()
        rig.engine.onSeatBelt(SeatSlot.REAR_LEFT, false, rig.now)
        rig.engine.onSeatBelt(SeatSlot.REAR_LEFT, true, rig.now)
        rig.engine.onSeatBelt(SeatSlot.REAR_LEFT, false, rig.now)
        rig.engine.onRearDoor(SeatSlot.REAR_LEFT, true, rig.now)
        rig.engine.onRearDoor(SeatSlot.REAR_LEFT, false, rig.now)
        rig.moveClock(HOUR)
        rig.idle(10_000)
        rig.engine.onSeatBelt(SeatSlot.REAR_LEFT, true, rig.now)
        assertEquals(10_000L, rig.events.filterIsInstance<DriveEvent.SeatReturned>().single().absentMs)
    }

    @Test
    fun `시계가 앞으로 가도 뒷벨트 보상 간격은 그대로다`() {
        val rig = Rig()
        rig.engine.onSeatBelt(SeatSlot.REAR_LEFT, false, rig.now)
        rig.engine.onSeatBelt(SeatSlot.REAR_LEFT, true, rig.now)
        rig.engine.onSeatBelt(SeatSlot.REAR_LEFT, false, rig.now)
        rig.moveClock(HOUR)
        rig.engine.onSeatBelt(SeatSlot.REAR_LEFT, true, rig.now)
        assertEquals(1, rig.events.count { it is DriveEvent.RearBeltBuckled })
    }

    @Test
    fun `시계가 뒤로 가도 쉰 시간은 실제 시간이다`() {
        val rig = Rig()
        rig.engine.onGear(Gear.DRIVE, rig.now)
        rig.idle(HOUR)
        rig.engine.onGear(Gear.PARK, rig.now)
        rig.idle(HOLD_MS)
        rig.idle(10 * MINUTE)
        rig.moveClock(-HOUR)
        rig.idle(10 * MINUTE)
        rig.engine.onGear(Gear.DRIVE, rig.now)
        assertEquals(20 * MINUTE + HOLD_MS, rig.events.filterIsInstance<DriveEvent.RestTaken>().single().restMs)
    }

    @Test
    fun `시계가 앞으로 간 만큼은 달린 시간에 들지 않는다`() {
        val rig = Rig()
        rig.engine.onGear(Gear.DRIVE, rig.now)
        rig.idle(10 * MINUTE)
        rig.moveClock(HOUR)
        rig.idle(10 * MINUTE)
        rig.engine.onGear(Gear.PARK, rig.now)
        rig.idle(HOLD_MS)
        rig.idle(20 * MINUTE)
        rig.engine.onGear(Gear.DRIVE, rig.now)
        assertEquals(0, rig.events.count { it is DriveEvent.RestTaken })
    }

    @Test
    fun `시계가 멈칫한 만큼 짧은 트립도 음수가 되지 않는다`() {
        val rig = Rig()
        rig.engine.onGear(Gear.DRIVE, rig.now)
        rig.engine.onGear(Gear.PARK, rig.now - 500)
        rig.idle(HOLD_MS)
        assertEquals(0L, rig.ended.single().summary.durationMs)
    }

    @Test
    fun `길이가 음수로 적힌 옛 트립을 재생해도 죽지 않고 깃털이 줄지 않는다`() {
        val ledger = listOf(
            DriveEvent.TripEnded(1_000, TripSummary(1, -3_590_912, 0f, 0, 0, 0, 0)),
            DriveEvent.TripStarted(2_000, 2_000),
            DriveEvent.TripEnded(3_000, TripSummary(2_000, 600_000, 0f, 0, 0, 0, 0)),
        )
        val pet = ledger.fold(PetState(), PetState::apply)
        assertEquals(2, pet.feathers)
        assertEquals(Growth.Stage.FLUFF, pet.stage)
        assertEquals(600_000L, pet.restClock.driveMs)
    }

    @Test
    fun `성장 단계는 음수 깃털에도 값을 준다`() {
        assertEquals(Growth.Stage.FLUFF, Growth.stage(-1))
        assertEquals(0, Growth.baseFeathers(-3_590_912))
    }

    @Test
    fun `시계가 그대로면 옮겨진 값이 없다`() {
        val watch = ClockWatch()
        assertEquals(0L, watch.moved(1_000, 500))
        assertEquals(0L, watch.moved(6_000, 5_500))
        assertEquals(0L, watch.moved(9_000 + 1_500, 8_500))
    }

    @Test
    fun `시계가 옮겨지면 크기를 한 번만 알린다`() {
        val watch = ClockWatch()
        watch.moved(1_000, 500)
        assertEquals(-HOUR, watch.moved(6_000 - HOUR, 5_500))
        assertEquals(0L, watch.moved(9_000 - HOUR, 8_500))
        assertEquals(HOUR, watch.moved(12_000, 11_500))
    }

    private companion object {
        const val HOLD_MS = 3_000L
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
    }
}
