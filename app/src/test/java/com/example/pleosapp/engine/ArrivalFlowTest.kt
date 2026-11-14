package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 도착 판정과 출발 전 뒷자리 벨트 장면(M2 명세). */
class ArrivalFlowTest {

    private class Rig {
        val events = mutableListOf<DriveEvent>()
        val engine = DriveEventEngine { events += it }
        var now = 1_000L

        inline fun <reified T : DriveEvent> count() = events.count { it is T }
        fun gear(g: Gear) = engine.onGear(g, now)
        fun idle(ms: Long) { now += ms; engine.tick(now) }
        fun door(open: Boolean) = engine.onRearDoor(SeatSlot.REAR_LEFT, open, now)
        fun belt(buckled: Boolean) = engine.onSeatBelt(SeatSlot.REAR_LEFT, buckled, now)
        fun board() { door(true); door(false) }
    }

    @Test
    fun `P를 3초 유지해야 트립이 끝나고 종료 시각은 P에 들어온 때다`() {
        val rig = Rig()
        rig.gear(Gear.DRIVE)
        rig.idle(10_000)
        val parkedAt = rig.now
        rig.gear(Gear.PARK)
        rig.idle(2_000)
        assertEquals(0, rig.count<DriveEvent.TripEnded>())
        rig.idle(1_000)
        assertEquals(parkedAt, rig.events.filterIsInstance<DriveEvent.TripEnded>().single().atMs)
    }

    @Test
    fun `P 뒤 3초 안에 다시 움직이면 같은 트립이다`() {
        val rig = Rig()
        rig.gear(Gear.DRIVE)
        rig.idle(10_000)
        rig.gear(Gear.PARK)
        rig.idle(1_000)
        rig.gear(Gear.REVERSE)
        rig.idle(5_000)
        assertEquals(1, rig.count<DriveEvent.TripStarted>())
        assertEquals(0, rig.count<DriveEvent.TripEnded>())
    }

    @Test
    fun `뒷문이 닫히고 벨트가 풀려 있으면 6초 뒤 한 번만 부탁한다`() {
        val rig = Rig()
        rig.gear(Gear.PARK)
        rig.belt(false)
        rig.board()
        rig.idle(5_000)
        assertEquals(0, rig.count<DriveEvent.RearBeltRequested>())
        rig.idle(1_000)
        rig.idle(30_000)
        assertEquals(1, rig.count<DriveEvent.RearBeltRequested>())
    }

    @Test
    fun `부탁 전에 벨트를 채우면 부탁하지 않고 고마워한다`() {
        val rig = Rig()
        rig.gear(Gear.PARK)
        rig.belt(false)
        rig.board()
        rig.idle(2_000)
        rig.belt(true)
        rig.idle(10_000)
        assertEquals(0, rig.count<DriveEvent.RearBeltRequested>())
        assertTrue(rig.events.filterIsInstance<DriveEvent.RearBeltBuckled>().single().parked)
    }

    @Test
    fun `벨트를 풀고 내리면 빈자리에 부탁하지 않는다`() {
        val rig = Rig()
        rig.gear(Gear.PARK)
        rig.belt(true)
        rig.belt(false)
        rig.board()
        rig.idle(30_000)
        assertEquals(0, rig.count<DriveEvent.RearBeltRequested>())
    }

    @Test
    fun `내린 뒤 다시 타면 다시 부탁한다`() {
        val rig = Rig()
        rig.gear(Gear.PARK)
        rig.belt(true)
        rig.belt(false)
        rig.board()
        rig.idle(30_000)
        rig.board()
        rig.idle(7_000)
        assertEquals(1, rig.count<DriveEvent.RearBeltRequested>())
    }

    @Test
    fun `도착 직후에는 뒷문이 움직여도 부탁하지 않는다`() {
        val rig = Rig()
        rig.belt(false)
        rig.gear(Gear.DRIVE)
        rig.idle(10_000)
        rig.gear(Gear.PARK)
        rig.idle(5_000)
        rig.board()
        rig.idle(20_000)
        assertEquals(0, rig.count<DriveEvent.RearBeltRequested>())
        rig.idle(60_000)
        rig.board()
        rig.idle(7_000)
        assertEquals(1, rig.count<DriveEvent.RearBeltRequested>())
    }

    @Test
    fun `벨트 신호를 모르면 부탁하지 않는다`() {
        val rig = Rig()
        rig.gear(Gear.PARK)
        rig.board()
        rig.idle(10_000)
        assertEquals(0, rig.count<DriveEvent.RearBeltRequested>())
    }

    @Test
    fun `주행 중에는 부탁하지 않고 체결은 조용히 기록한다`() {
        val rig = Rig()
        rig.gear(Gear.PARK)
        rig.belt(false)
        rig.board()
        rig.gear(Gear.DRIVE)
        rig.idle(30_000)
        assertEquals(0, rig.count<DriveEvent.RearBeltRequested>())
        rig.belt(true)
        assertEquals(false, rig.events.filterIsInstance<DriveEvent.RearBeltBuckled>().single().parked)
    }

    @Test
    fun `벨트를 반복해서 채워도 10분에 한 번만 보상한다`() {
        val rig = Rig()
        rig.belt(false)
        repeat(4) {
            rig.belt(true)
            rig.idle(1_000)
            rig.belt(false)
        }
        assertEquals(1, rig.count<DriveEvent.RearBeltBuckled>())
    }

    @Test
    fun `벨트와 최대 감속도가 트립 요약에 들어간다`() {
        val rig = Rig()
        rig.belt(false)
        rig.belt(true)
        rig.gear(Gear.DRIVE)
        var speed = 20f
        repeat(10) { rig.now += 100; rig.engine.onSpeed(speed, rig.now) }
        rig.engine.onBrake(true, rig.now)
        repeat(20) { speed -= 0.8f; rig.now += 100; rig.engine.onSpeed(speed, rig.now) }
        rig.engine.onBrake(false, rig.now)
        rig.gear(Gear.PARK)
        rig.idle(3_000)
        val summary = rig.events.filterIsInstance<DriveEvent.TripEnded>().single().summary
        assertEquals(1, summary.rearBelts)
        assertEquals(1, summary.hardBrakes)
        assertTrue("peak=${summary.peakBrakeDecel}", summary.peakBrakeDecel > rig.events.filterIsInstance<DriveEvent.HardBrake>().single().decel)
    }

    @Test
    fun `쉬고 이어서 달린 트립의 요약에 휴식이 남는다`() {
        val rig = Rig()
        rig.gear(Gear.DRIVE)
        rig.idle(EngineConfig.HOUR_MS)
        rig.gear(Gear.PARK)
        rig.idle(15 * EngineConfig.MINUTE_MS)
        rig.gear(Gear.DRIVE)
        rig.idle(60_000)
        rig.gear(Gear.PARK)
        rig.idle(3_000)
        val summaries = rig.events.filterIsInstance<DriveEvent.TripEnded>().map { it.summary.restedBefore }
        assertEquals(listOf(false, true), summaries)
    }

    @Test
    fun `순서가 뒤바뀐 속도 샘플은 버린다`() {
        val rig = Rig()
        rig.gear(Gear.DRIVE)
        rig.engine.onBrake(true, rig.now)
        rig.engine.onSpeed(20f, 2_000)
        rig.engine.onSpeed(20f, 2_300)
        rig.engine.onSpeed(0f, 2_200)
        rig.engine.onSpeed(20f, 2_600)
        assertEquals(0, rig.count<DriveEvent.HardBrake>())
    }
}
