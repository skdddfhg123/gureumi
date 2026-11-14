package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 작은 정령의 왕복: 내리면 배웅, 다시 타면 반김. 방향 B의 핵심 장면입니다. */
class RoundTripTest {

    private class Rig(leftSeats: Set<SeatSlot> = emptySet()) {
        val events = mutableListOf<DriveEvent>()
        val engine = DriveEventEngine(leftSeats = leftSeats) { events += it }
        var now = 1_000L

        inline fun <reified T : DriveEvent> count() = events.count { it is T }
        fun gear(g: Gear) = engine.onGear(g, now)
        fun idle(ms: Long) { now += ms; engine.tick(now) }
        fun belt(seat: SeatSlot, buckled: Boolean) = engine.onSeatBelt(seat, buckled, now)
        fun door(seat: SeatSlot, open: Boolean) = engine.onRearDoor(seat, open, now)
        fun openAndClose(seat: SeatSlot) {
            door(seat, true)
            door(seat, false)
        }

        fun unbuckledAll() = SeatSlot.entries.forEach { belt(it, false) }

        /** 출발해서 P에 들어온 순간까지. 도착 판정 3초는 지나지 않았습니다. */
        fun driveToPark() {
            gear(Gear.DRIVE)
            idle(60_000)
            gear(Gear.PARK)
        }

        fun arrive() {
            driveToPark()
            idle(3_000)
        }

        val lastTrip get() = events.filterIsInstance<DriveEvent.TripEnded>().last().summary
    }

    private val left = SeatSlot.REAR_LEFT
    private val right = SeatSlot.REAR_RIGHT
    private val center = SeatSlot.REAR_CENTER
    private val front = SeatSlot.FRONT_RIGHT

    private fun belted(vararg seats: SeatSlot) = Rig().apply {
        unbuckledAll()
        seats.forEach { belt(it, true) }
    }

    @Test
    fun `벨트를 풀어도 문이 닫히기 전에는 배웅하지 않는다`() {
        val rig = belted(left)
        rig.arrive()
        rig.belt(left, false)
        rig.door(left, true)
        assertEquals(0, rig.count<DriveEvent.SeatLeft>())
        rig.door(left, false)
        assertEquals(1, rig.count<DriveEvent.SeatLeft>())
    }

    @Test
    fun `벨트를 맨 채 문만 여닫으면 내린 것이 아니다`() {
        val rig = belted(left)
        rig.arrive()
        rig.openAndClose(left)
        assertEquals(0, rig.count<DriveEvent.SeatLeft>())
    }

    @Test
    fun `도착 판정을 기다리는 3초 안에 벨트를 풀어도 배웅한다`() {
        val rig = belted(left)
        rig.driveToPark()
        rig.idle(1_000)
        rig.belt(left, false)
        rig.idle(5_000)
        rig.openAndClose(left)
        assertEquals(1, rig.count<DriveEvent.SeatLeft>())
    }

    @Test
    fun `도착 판정을 기다리는 동안 빠르게 내려도 배웅한다`() {
        val rig = belted(left)
        rig.driveToPark()
        rig.belt(left, false)
        rig.idle(1_000)
        rig.openAndClose(left)
        assertEquals(1, rig.count<DriveEvent.SeatLeft>())
        rig.idle(3_000)
        assertEquals(1, rig.count<DriveEvent.TripEnded>())
    }

    @Test
    fun `벨트를 다시 매면 내리는 중이 아니다`() {
        val rig = belted(left)
        rig.arrive()
        rig.belt(left, false)
        rig.belt(left, true)
        rig.openAndClose(left)
        assertEquals(0, rig.count<DriveEvent.SeatLeft>())
    }

    @Test
    fun `다시 벨트를 매면 비어 있던 시간과 함께 반긴다`() {
        val rig = belted(left)
        rig.arrive()
        rig.belt(left, false)
        rig.openAndClose(left)
        val leftAt = rig.now
        rig.idle(2 * EngineConfig.HOUR_MS)
        rig.openAndClose(left)
        rig.belt(left, true)
        val back = rig.events.filterIsInstance<DriveEvent.SeatReturned>().single()
        assertEquals(left, back.seat)
        assertEquals(rig.now - leftAt, back.absentMs)
        assertTrue(back.parked)
    }

    @Test
    fun `혼자 다녀온 뒤에 다시 타도 반긴다`() {
        val rig = belted(left)
        rig.arrive()
        rig.belt(left, false)
        rig.openAndClose(left)
        rig.idle(70_000)
        rig.arrive()
        assertEquals(setOf<SeatSlot>(), rig.lastTrip.seats)
        rig.idle(70_000)
        rig.openAndClose(left)
        rig.belt(left, true)
        assertEquals(1, rig.count<DriveEvent.SeatReturned>())
    }

    @Test
    fun `내린 적 없는 자리는 반기지 않는다`() {
        val rig = belted(left)
        rig.arrive()
        rig.belt(left, false)
        rig.belt(left, true)
        assertEquals(0, rig.count<DriveEvent.SeatReturned>())
    }

    @Test
    fun `반김은 한 번뿐이다`() {
        val rig = belted(left)
        rig.arrive()
        rig.belt(left, false)
        rig.openAndClose(left)
        rig.belt(left, true)
        rig.belt(left, false)
        rig.belt(left, true)
        assertEquals(1, rig.count<DriveEvent.SeatReturned>())
    }

    @Test
    fun `오래 떨어졌을 때만 소리 내어 반긴다`() {
        val long = DriveEvent.SeatReturned(1, left, EngineConfig.LONG_ABSENCE_MS, parked = true)
        val short = DriveEvent.SeatReturned(1, left, EngineConfig.LONG_ABSENCE_MS - 1, parked = true)
        assertTrue(long.greetsAloud)
        assertFalse(short.greetsAloud)
    }

    @Test
    fun `주행 중에는 소리 내어 반기지 않는다`() {
        val rig = belted(left)
        rig.arrive()
        rig.belt(left, false)
        rig.openAndClose(left)
        rig.idle(2 * EngineConfig.HOUR_MS)
        rig.gear(Gear.DRIVE)
        rig.idle(5_000)
        rig.belt(left, true)
        val back = rig.events.filterIsInstance<DriveEvent.SeatReturned>().single()
        assertFalse(back.parked)
        assertFalse(back.greetsAloud)
    }

    @Test
    fun `왕복 이벤트는 원장에 남기지 않는다`() {
        assertFalse(DriveEvent.SeatLeft(1, left).persisted)
        assertFalse(DriveEvent.SeatReturned(1, left, 1, parked = true).persisted)
    }

    @Test
    fun `왕복은 깃털을 주지 않는다`() {
        val state = PetState()
            .apply(DriveEvent.SeatLeft(1, left))
            .apply(DriveEvent.SeatReturned(2, left, 1, parked = true))
        assertEquals(0, state.feathers)
    }

    @Test
    fun `가운데 자리는 양옆 뒷문으로 내린다`() {
        val rig = belted(center)
        rig.arrive()
        rig.belt(center, false)
        rig.openAndClose(right)
        assertEquals(listOf(center), rig.events.filterIsInstance<DriveEvent.SeatLeft>().map { it.seat })
        rig.idle(1_000)
        rig.belt(center, true)
        assertEquals(1, rig.count<DriveEvent.SeatReturned>())
    }

    @Test
    fun `가운데 자리가 내린 문은 탑승으로 치지 않는다`() {
        val rig = belted(center)
        rig.arrive()
        rig.idle(70_000)
        rig.belt(center, false)
        rig.openAndClose(right)
        rig.idle(30_000)
        assertEquals(0, rig.count<DriveEvent.RearBeltRequested>())
    }

    @Test
    fun `조수석도 문으로 왕복한다`() {
        val rig = belted(front)
        rig.arrive()
        rig.belt(front, false)
        rig.openAndClose(front)
        assertEquals(listOf(front), rig.events.filterIsInstance<DriveEvent.SeatLeft>().map { it.seat })
        rig.belt(front, true)
        assertEquals(1, rig.count<DriveEvent.SeatReturned>())
    }

    @Test
    fun `조수석 문은 뒷자리 벨트 부탁을 부르지 않는다`() {
        val rig = Rig()
        rig.unbuckledAll()
        rig.gear(Gear.PARK)
        rig.openAndClose(front)
        rig.idle(30_000)
        assertEquals(0, rig.count<DriveEvent.RearBeltRequested>())
    }

    @Test
    fun `조수석 문이 닫혀도 기다리는 자리로 세지 않는다`() {
        val rig = Rig()
        rig.unbuckledAll()
        rig.gear(Gear.PARK)
        rig.openAndClose(front)
        rig.belt(left, true)
        assertFalse(rig.events.filterIsInstance<DriveEvent.SeatsAwake>().last().waiting)
    }

    @Test
    fun `P가 아닐 때 내리면 다음 도착까지 미룬다`() {
        val rig = belted(left)
        rig.gear(Gear.DRIVE)
        rig.idle(60_000)
        rig.belt(left, false)
        rig.openAndClose(left)
        assertEquals(0, rig.count<DriveEvent.SeatLeft>())
        rig.idle(60_000)
        rig.gear(Gear.PARK)
        rig.idle(3_000)
        assertEquals(1, rig.count<DriveEvent.SeatLeft>())
    }

    @Test
    fun `미룬 배웅은 도착 다음에 나온다`() {
        val rig = belted(left)
        rig.gear(Gear.DRIVE)
        rig.idle(60_000)
        rig.belt(left, false)
        rig.openAndClose(left)
        rig.gear(Gear.PARK)
        rig.idle(3_000)
        val ended = rig.events.indexOfLast { it is DriveEvent.TripEnded }
        val left = rig.events.indexOfLast { it is DriveEvent.SeatLeft }
        assertTrue(ended < left)
    }

    @Test
    fun `미룬 배웅도 비어 있던 시간은 문이 닫힌 때부터 잰다`() {
        val rig = belted(left)
        rig.gear(Gear.DRIVE)
        rig.idle(60_000)
        rig.belt(left, false)
        rig.openAndClose(left)
        val closedAt = rig.now
        rig.idle(60_000)
        rig.gear(Gear.PARK)
        rig.idle(3_000)
        rig.idle(70_000)
        rig.openAndClose(left)
        rig.belt(left, true)
        assertEquals(rig.now - closedAt, rig.events.filterIsInstance<DriveEvent.SeatReturned>().single().absentMs)
    }

    @Test
    fun `알리기 전에 돌아오면 배웅도 반김도 없다`() {
        val rig = belted(left)
        rig.gear(Gear.DRIVE)
        rig.idle(60_000)
        rig.belt(left, false)
        rig.openAndClose(left)
        rig.idle(10_000)
        rig.belt(left, true)
        rig.gear(Gear.PARK)
        rig.idle(3_000)
        assertEquals(0, rig.count<DriveEvent.SeatLeft>())
        assertEquals(0, rig.count<DriveEvent.SeatReturned>())
    }

    @Test
    fun `벨트를 푼 채 도착하면 빈자리에 부탁하지 않는다`() {
        val rig = belted(left)
        rig.gear(Gear.DRIVE)
        rig.idle(60_000)
        rig.belt(left, false)
        rig.gear(Gear.PARK)
        rig.idle(70_000)
        rig.openAndClose(left)
        rig.idle(30_000)
        assertEquals(0, rig.count<DriveEvent.RearBeltRequested>())
        assertEquals(1, rig.count<DriveEvent.SeatLeft>())
    }

    @Test
    fun `문이 열린 채 출발하고 닫히면 다음 도착에 배웅한다`() {
        val rig = belted(left)
        rig.arrive()
        rig.belt(left, false)
        rig.door(left, true)
        rig.idle(70_000)
        rig.gear(Gear.DRIVE)
        rig.idle(1_000)
        rig.door(left, false)
        rig.idle(60_000)
        rig.gear(Gear.PARK)
        rig.idle(3_000)
        assertEquals(1, rig.count<DriveEvent.SeatLeft>())
    }

    @Test
    fun `풀린 벨트로 출발하면 지난 내림 판단은 지운다`() {
        val rig = belted(left)
        rig.arrive()
        rig.belt(left, false)
        rig.idle(70_000)
        rig.arrive()
        rig.idle(70_000)
        rig.openAndClose(left)
        rig.idle(30_000)
        assertEquals(0, rig.count<DriveEvent.SeatLeft>())
        assertEquals(1, rig.count<DriveEvent.RearBeltRequested>())
    }

    @Test
    fun `앱이 다시 떠도 내린 자리는 기억해 반긴다`() {
        val rig = Rig(leftSeats = setOf(left))
        rig.now = 1_793_500_000_000L
        rig.unbuckledAll()
        rig.gear(Gear.PARK)
        rig.belt(left, true)
        val back = rig.events.filterIsInstance<DriveEvent.SeatReturned>().single()
        assertTrue(back.greetsAloud)
    }

    @Test
    fun `시연에서는 내린 시각을 앞당겨 오래 떨어진 것처럼 만든다`() {
        val rig = belted(left)
        rig.arrive()
        rig.belt(left, false)
        rig.openAndClose(left)
        rig.idle(10_000)
        rig.engine.rewindLeft(left, EngineConfig.LONG_ABSENCE_MS)
        rig.belt(left, true)
        assertTrue(rig.events.filterIsInstance<DriveEvent.SeatReturned>().single().greetsAloud)
    }

    @Test
    fun `뒷벨트가 그대로면 도착을 조용히 알린다`() {
        val rig = belted(left)
        rig.arrive()
        assertTrue(rig.lastTrip.rearStillBelted)
    }

    @Test
    fun `도착 판정 전에 뒷벨트를 풀면 조용히 하지 않는다`() {
        val rig = belted(left)
        rig.driveToPark()
        rig.belt(left, false)
        rig.idle(3_000)
        assertFalse(rig.lastTrip.rearStillBelted)
    }

    @Test
    fun `혼자 탄 트립과 조수석만 맨 트립은 소리를 끄지 않는다`() {
        val alone = Rig().apply { unbuckledAll(); arrive() }
        assertFalse(alone.lastTrip.rearStillBelted)
        assertFalse(belted(front).apply { arrive() }.lastTrip.rearStillBelted)
    }

    @Test
    fun `뒷자리 벨트 신호를 모르면 소리를 끄지 않는다`() {
        assertFalse(Rig().apply { arrive() }.lastTrip.rearStillBelted)
    }

    @Test
    fun `뒷자리 안전벨트가 모두 풀려야 낭독할 수 있다`() {
        val rig = belted(left, SeatSlot.REAR_RIGHT)
        assertTrue(rig.engine.rearBelted)
        rig.belt(left, false)
        assertTrue(rig.engine.rearBelted)
        rig.belt(SeatSlot.REAR_RIGHT, false)
        assertFalse(rig.engine.rearBelted)
    }

    @Test
    fun `조수석 안전벨트는 뒷자리 판정에 들지 않는다`() {
        assertFalse(belted(front).engine.rearBelted)
    }

    @Test
    fun `가운데 뒷자리도 뒷자리 판정에 든다`() {
        assertTrue(belted(SeatSlot.REAR_CENTER).engine.rearBelted)
    }

    @Test
    fun `내린 자리는 가족 파일에 시각 없이 남는다`() {
        val family = FamilyState().adopt(SpiritPreset.SOM, left)
        val away = family.seatLeft(left)
        assertEquals(setOf(left), away.away)
        assertEquals(away, FamilyCodec.decode(FamilyCodec.encode(away)))
        assertEquals(family, away.seatReturned(left))
    }

    @Test
    fun `정령이 없는 자리는 지키지 않는다`() {
        assertEquals(FamilyState(), FamilyState().seatLeft(left))
    }

    @Test
    fun `정령을 지우거나 옮기면 지키던 자리도 풀린다`() {
        val family = FamilyState().adopt(SpiritPreset.SOM, left).seatLeft(left)
        assertTrue(family.remove(SpiritPreset.SOM).away.isEmpty())
        assertTrue(family.moveTo(SpiritPreset.SOM, right).away.isEmpty())
    }

    @Test
    fun `가족 파일의 모르는 자리는 건너뛴다`() {
        // 11/5부터 지키는 자리는 아기가 앉은 자리만 읽어서 그 자리에 아기를 둡니다.
        val state = FamilyCodec.decode("""{"spirits":[{"preset":"som","seat":"REAR_LEFT"}],"away":["ROOF","REAR_LEFT"]}""")
        assertEquals(setOf(left), state.away)
    }
}
