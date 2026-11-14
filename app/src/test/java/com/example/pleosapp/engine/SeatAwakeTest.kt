package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 안전벨트 출석과 화면에서 고르기(M3 명세 3-2, 3-5). */
class SeatAwakeTest {

    private class Rig {
        val events = mutableListOf<DriveEvent>()
        val engine = DriveEventEngine { events += it }
        var now = 1_000L

        fun gear(g: Gear) = engine.onGear(g, now)
        fun idle(ms: Long) { now += ms; engine.tick(now) }
        fun belt(seat: SeatSlot, buckled: Boolean) = engine.onSeatBelt(seat, buckled, now)
        fun tap(seat: SeatSlot) = engine.onSeatTap(seat, now)
        fun board(seat: SeatSlot) {
            engine.onRearDoor(seat, true, now)
            engine.onRearDoor(seat, false, now)
        }
        fun drive() {
            gear(Gear.DRIVE)
            idle(10_000)
            gear(Gear.PARK)
            idle(3_000)
        }

        /** 시작할 때 차가 보내는 첫 값처럼 안전벨트가 풀려 있음을 알립니다. */
        fun unbuckledAll() = SeatSlot.entries.forEach { belt(it, false) }

        val awake get() = events.filterIsInstance<DriveEvent.SeatsAwake>().last()
        val lastTrip get() = events.filterIsInstance<DriveEvent.TripEnded>().last().summary
    }

    @Test
    fun `체결한 자리만 깨어나고 도착하면 모두 잠든다`() {
        val rig = Rig()
        rig.unbuckledAll()
        rig.belt(SeatSlot.REAR_LEFT, true)
        assertEquals(setOf(SeatSlot.REAR_LEFT), rig.awake.seats)
        assertEquals(setOf(SeatSlot.REAR_LEFT), rig.awake.newly)
        assertTrue(rig.awake.parked)

        rig.drive()
        assertEquals(setOf(SeatSlot.REAR_LEFT), rig.lastTrip.seats)
        assertTrue(rig.awake.seats.isEmpty())
    }

    @Test
    fun `앱이 시작할 때 이미 채워진 안전벨트는 인사 없이 깨운다`() {
        val rig = Rig()
        rig.belt(SeatSlot.REAR_RIGHT, true)
        assertEquals(setOf(SeatSlot.REAR_RIGHT), rig.awake.seats)
        assertTrue(rig.awake.newly.isEmpty())
    }

    @Test
    fun `주행 중 체결도 함께한 자리로 남지만 주차 상태가 아니다`() {
        val rig = Rig()
        rig.unbuckledAll()
        rig.gear(Gear.DRIVE)
        rig.idle(5_000)
        rig.belt(SeatSlot.REAR_LEFT, true)
        assertFalse(rig.awake.parked)
        rig.idle(5_000)
        rig.gear(Gear.PARK)
        rig.idle(3_000)
        assertEquals(setOf(SeatSlot.REAR_LEFT), rig.lastTrip.seats)
    }

    @Test
    fun `정차 중 안전벨트를 풀면 다시 잠들고 함께한 자리에서 빠진다`() {
        val rig = Rig()
        rig.unbuckledAll()
        rig.belt(SeatSlot.REAR_LEFT, true)
        rig.belt(SeatSlot.REAR_LEFT, false)
        assertTrue(rig.awake.seats.isEmpty())
        rig.drive()
        assertTrue(rig.lastTrip.seats.isEmpty())
    }

    @Test
    fun `조수석 체결은 깨어나지만 깃털 이벤트가 없다`() {
        val rig = Rig()
        rig.unbuckledAll()
        rig.belt(SeatSlot.FRONT_RIGHT, true)
        assertEquals(setOf(SeatSlot.FRONT_RIGHT), rig.awake.seats)
        assertEquals(0, rig.events.count { it is DriveEvent.RearBeltBuckled })
        rig.drive()
        assertEquals(setOf(SeatSlot.FRONT_RIGHT), rig.lastTrip.seats)
        assertEquals(0, rig.lastTrip.rearBelts)
    }

    @Test
    fun `뒷자리 가운데도 깃털을 받는다`() {
        val rig = Rig()
        rig.unbuckledAll()
        rig.belt(SeatSlot.REAR_CENTER, true)
        assertEquals(1, rig.events.count { it is DriveEvent.RearBeltBuckled })
    }

    @Test
    fun `화면에서 깨운 자리는 깃털 없이 표시용 목록에만 남는다`() {
        val rig = Rig()
        rig.unbuckledAll()
        rig.tap(SeatSlot.REAR_LEFT)
        assertEquals(setOf(SeatSlot.REAR_LEFT), rig.awake.seats)
        assertTrue(rig.awake.newly.isEmpty())
        rig.drive()
        assertEquals(0, rig.events.count { it is DriveEvent.RearBeltBuckled })
        assertTrue(rig.lastTrip.seats.isEmpty())
        assertEquals(setOf(SeatSlot.REAR_LEFT), rig.lastTrip.tappedSeats)
    }

    @Test
    fun `화면에서 한 번 더 누르면 다시 잠든다`() {
        val rig = Rig()
        rig.tap(SeatSlot.REAR_LEFT)
        rig.tap(SeatSlot.REAR_LEFT)
        assertTrue(rig.awake.seats.isEmpty())
        rig.drive()
        assertTrue(rig.lastTrip.tappedSeats.isEmpty())
    }

    @Test
    fun `주행 중에는 화면 입력을 받지 않는다`() {
        val rig = Rig()
        rig.gear(Gear.DRIVE)
        rig.idle(5_000)
        rig.tap(SeatSlot.REAR_LEFT)
        rig.idle(5_000)
        rig.gear(Gear.PARK)
        rig.idle(3_000)
        assertTrue(rig.lastTrip.tappedSeats.isEmpty())
    }

    @Test
    fun `안전벨트로 깨어난 자리는 화면으로 재우지 않는다`() {
        val rig = Rig()
        rig.unbuckledAll()
        rig.belt(SeatSlot.REAR_LEFT, true)
        rig.tap(SeatSlot.REAR_LEFT)
        assertEquals(setOf(SeatSlot.REAR_LEFT), rig.awake.seats)
    }

    @Test
    fun `화면에서 깨운 뒤 안전벨트를 채우면 안전벨트 자리로 인정한다`() {
        val rig = Rig()
        rig.unbuckledAll()
        rig.tap(SeatSlot.REAR_LEFT)
        rig.belt(SeatSlot.REAR_LEFT, true)
        assertEquals(setOf(SeatSlot.REAR_LEFT), rig.awake.newly)
        rig.drive()
        assertEquals(setOf(SeatSlot.REAR_LEFT), rig.lastTrip.seats)
        assertTrue(rig.lastTrip.tappedSeats.isEmpty())
    }

    @Test
    fun `해제 신호가 반복돼도 화면에서 깨운 자리는 잠들지 않는다`() {
        val rig = Rig()
        rig.unbuckledAll()
        rig.tap(SeatSlot.REAR_LEFT)
        rig.belt(SeatSlot.REAR_LEFT, false)
        assertEquals(setOf(SeatSlot.REAR_LEFT), rig.awake.seats)
    }

    @Test
    fun `안전벨트를 그대로 둔 채 다시 출발하면 다음 트립도 함께한다`() {
        val rig = Rig()
        rig.unbuckledAll()
        rig.belt(SeatSlot.REAR_LEFT, true)
        rig.drive()
        rig.drive()
        assertEquals(setOf(SeatSlot.REAR_LEFT), rig.lastTrip.seats)
    }

    @Test
    fun `탑승했지만 안 채운 자리가 있으면 기다리는 중으로 알린다`() {
        val rig = Rig()
        rig.unbuckledAll()
        rig.board(SeatSlot.REAR_RIGHT)
        rig.belt(SeatSlot.REAR_LEFT, true)
        assertTrue(rig.awake.waiting)
        rig.belt(SeatSlot.REAR_RIGHT, true)
        assertFalse(rig.awake.waiting)
    }

    @Test
    fun `자리 상태 이벤트는 원장에 남기지 않는다`() {
        assertFalse(DriveEvent.SeatsAwake(1, emptySet(), emptySet(), waiting = false, parked = true).persisted)
    }
}
