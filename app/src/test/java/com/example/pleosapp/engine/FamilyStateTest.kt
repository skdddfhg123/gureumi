package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 정령 구성과 말 전해 주기(M3 명세 3-1, 3-4). */
class FamilyStateTest {

    private val family = FamilyState()
        .adopt(SpiritPreset.SOM, SeatSlot.REAR_LEFT)
        .adopt(SpiritPreset.SAETEOL, SeatSlot.REAR_RIGHT)

    /** 맡긴 말은 5분 넘게 달린 트립에만 전합니다. 기본 트립은 10분입니다. */
    private fun trip(id: Long = 7, seats: Set<SeatSlot> = emptySet(), tapped: Set<SeatSlot> = emptySet(), durationMs: Long = 600_000) =
        TripSummary(id, durationMs, 1_000f, 0, 0, 0, 0, seats = seats, tappedSeats = tapped)

    @Test
    fun `자리 하나에 정령 둘이 앉지 못한다`() {
        val next = family.adopt(SpiritPreset.YANGTTE, SeatSlot.REAR_LEFT)
        assertEquals(family, next)
    }

    @Test
    fun `같은 정령을 두 번 고르지 못한다`() {
        assertEquals(family, family.adopt(SpiritPreset.SOM, SeatSlot.FRONT_RIGHT))
    }

    @Test
    fun `정령은 네 개까지다`() {
        val full = family
            .adopt(SpiritPreset.YANGTTE, SeatSlot.FRONT_RIGHT)
            .adopt(SpiritPreset.ANGAE, SeatSlot.REAR_CENTER)
        assertEquals(4, full.spirits.size)
        assertFalse(full.canAdopt)
    }

    @Test
    fun `빈자리로만 옮길 수 있다`() {
        assertEquals(family, family.moveTo(SpiritPreset.SOM, SeatSlot.REAR_RIGHT))
        val moved = family.moveTo(SpiritPreset.SOM, SeatSlot.FRONT_RIGHT)
        assertEquals(SpiritPreset.SOM, moved.spiritAt(SeatSlot.FRONT_RIGHT)?.preset)
        assertNull(moved.spiritAt(SeatSlot.REAR_LEFT))
    }

    @Test
    fun `정령을 지우면 맡긴 말과 전달 기록도 사라진다`() {
        val held = family.hold(SpiritPreset.SOM, 0)
        assertNull(held.remove(SpiritPreset.SOM).pending)
        val delivered = held.settleArrival(trip(), ailing = false)
        assertNull(delivered.remove(SpiritPreset.SOM).delivered)
    }

    @Test
    fun `다른 정령을 지워도 맡긴 말은 남는다`() {
        val held = family.hold(SpiritPreset.SOM, 0)
        assertEquals(held.pending, held.remove(SpiritPreset.SAETEOL).pending)
    }

    @Test
    fun `맡긴 말은 한 건만 두고 새로 맡기면 바뀐다`() {
        val held = family.hold(SpiritPreset.SOM, 0).hold(SpiritPreset.SAETEOL, 1)
        assertEquals(HeldMessage(SpiritPreset.SAETEOL, 1), held.pending)
    }

    @Test
    fun `없는 정령이나 없는 문장은 맡지 않는다`() {
        assertEquals(family, family.hold(SpiritPreset.YANGTTE, 0))
        assertEquals(family, family.hold(SpiritPreset.SOM, 99))
    }

    @Test
    fun `함께 탄 트립에서는 전하지 않는다`() {
        val held = family.hold(SpiritPreset.SOM, 0)
        assertEquals(held, held.settleArrival(trip(seats = setOf(SeatSlot.REAR_LEFT)), ailing = false))
    }

    @Test
    fun `화면에서 깨운 정령이 있어도 함께 탄 트립이다`() {
        val held = family.hold(SpiritPreset.SOM, 0)
        assertEquals(held, held.settleArrival(trip(tapped = setOf(SeatSlot.REAR_RIGHT)), ailing = false))
    }

    @Test
    fun `혼자 탄 트립에서 한 번만 전한다`() {
        val held = family.hold(SpiritPreset.SOM, 0)
        val first = held.settleArrival(trip(id = 7), ailing = false)
        assertNull(first.pending)
        assertEquals("도토리가 전해 달래. 오늘도 조심해서 다녀와.", first.deliveredLine(7))

        val second = first.settleArrival(trip(id = 8), ailing = false)
        assertEquals(first, second)
        assertNull(second.deliveredLine(8))
    }

    @Test
    fun `정령이 없는 자리의 손님만 탔으면 혼자 탄 트립으로 본다`() {
        val held = family.hold(SpiritPreset.SOM, 0)
        val next = held.settleArrival(trip(seats = setOf(SeatSlot.REAR_CENTER)), ailing = false)
        assertNull(next.pending)
    }

    /** 발레 기사가 2분 옮기는 트립에 아이의 말을 읽지 않습니다. */
    @Test
    fun `5분이 안 되는 트립에는 맡긴 말을 전하지 않는다`() {
        val held = family.hold(SpiritPreset.SOM, 0)
        assertEquals(held, held.settleArrival(trip(durationMs = 2 * 60_000), ailing = false))
        assertEquals(held, held.settleArrival(trip(durationMs = FamilyState.CARRY_MIN_TRIP_MS), ailing = false))
        assertNull(held.settleArrival(trip(durationMs = FamilyState.CARRY_MIN_TRIP_MS + 1_000), ailing = false).pending)
    }

    @Test
    fun `건강 안내가 있으면 전달을 미룬다`() {
        val held = family.hold(SpiritPreset.SOM, 0)
        val delayed = held.settleArrival(trip(id = 7), ailing = true)
        assertEquals(held, delayed)
        assertTrue(delayed.settleArrival(trip(id = 8), ailing = false).deliveredLine(8) != null)
    }

    @Test
    fun `문장 보기는 세 개이고 트립이 바뀌면 달라진다`() {
        val first = SpiritPhrases.offered(0)
        assertEquals(3, first.size)
        assertEquals(3, first.toSet().size)
        assertTrue(first != SpiritPhrases.offered(1))
        assertTrue(SpiritPhrases.offered(-5).all { it in SpiritPhrases.ALL.indices })
    }
}
