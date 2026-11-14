package com.example.pleosapp.engine

import com.example.pleosapp.engine.SeatSlot.FRONT_RIGHT
import com.example.pleosapp.engine.SeatSlot.REAR_CENTER
import com.example.pleosapp.engine.SeatSlot.REAR_LEFT
import com.example.pleosapp.engine.SeatSlot.REAR_RIGHT
import com.example.pleosapp.engine.SpiritPreset.ANGAE
import com.example.pleosapp.engine.SpiritPreset.SAETEOL
import com.example.pleosapp.engine.SpiritPreset.SOM
import com.example.pleosapp.engine.SpiritPreset.YANGTTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 자리 대신 탄 사람 수와 이름으로 함께 탈 아기를 정합니다(강가 장소 명세 6장).
 * 안전벨트를 채운 자리 수만큼, 부른 아기와 오래 안 탄 아기가 그 자리에 앉습니다.
 */
class RidersTest {

    /** 자리를 고르지 않고 넷을 들이면 조수석부터 차례로 앉습니다. */
    private val all = SpiritPreset.entries.fold(FamilyState()) { family, preset -> family.adopt(preset) }

    private fun FamilyState.riding(belted: Set<SeatSlot>) = companions(belted).map { it.preset }.toSet()

    private fun FamilyState.seatsAreUnique() = assertEquals(spirits.size, spirits.map { it.seat }.toSet().size)

    @Test
    fun `어느 자리에 채워도 탄 사람 수가 같으면 같은 아기가 탄다`() {
        // 아기를 자리 위치에 묶지 않습니다. 조수석도 뒷자리와 같은 한 사람입니다.
        val family = all.copy(lastRodeAt = mapOf(SAETEOL to 40L, SOM to 10L, YANGTTE to 30L, ANGAE to 20L))
        val pairs = SeatSlot.entries.flatMap { a -> SeatSlot.entries.filter { it.ordinal > a.ordinal }.map { b -> setOf(a, b) } }
        pairs.forEach { belted -> assertEquals(belted.toString(), listOf(SOM, ANGAE), family.seated(belted).ridersIn(belted)) }
        SeatSlot.entries.forEach { seat -> assertEquals(listOf(SOM), family.seated(setOf(seat)).ridersIn(setOf(seat))) }
    }

    @Test
    fun `부른 아기는 어느 자리에 채워도 먼저 탄다`() {
        val family = all.copy(lastRodeAt = mapOf(SAETEOL to 40L, SOM to 10L, YANGTTE to 30L, ANGAE to 20L)).call(SAETEOL)
        SeatSlot.entries.forEach { seat -> assertEquals(listOf(SAETEOL), family.seated(setOf(seat)).ridersIn(setOf(seat))) }
    }

    @Test
    fun `탄 아기 목록은 앉은 자리와 상관없이 프리셋 순서다`() {
        val belted = SeatSlot.entries.toSet()
        val reversed = SpiritPreset.entries.reversed().fold(FamilyState()) { family, preset -> family.adopt(preset) }
        assertEquals(SpiritPreset.entries, reversed.seated(belted).ridersIn(belted))
        assertEquals(SpiritPreset.entries, all.seated(belted).ridersIn(belted))
    }

    @Test
    fun `자리 값으로 그 자리에 앉힌 아기와 자리를 지키는 아기를 안다`() {
        val seated = all.call(ANGAE).seated(setOf(REAR_RIGHT))
        assertEquals(ANGAE, seated.riderAt(REAR_RIGHT))
        assertEquals(seated.spirits.first { it.seat == FRONT_RIGHT }.preset, seated.riderAt(FRONT_RIGHT))
        val kept = seated.seatLeft(REAR_RIGHT)
        assertEquals(listOf(ANGAE), kept.keeping)
        assertEquals(emptyList<SpiritPreset>(), kept.seatReturned(REAR_RIGHT).keeping)
    }

    @Test
    fun `들일 때 자리를 고르지 않아도 빈 자리에 앉는다`() {
        assertEquals(SeatSlot.entries, all.spirits.map { it.seat })
        assertFalse(all.canAdopt)
        assertEquals(all, all.adopt(SAETEOL))
    }

    @Test
    fun `탈 차례는 부른 순서, 그다음 오래 안 탄 아기다`() {
        val rode = all.copy(lastRodeAt = mapOf(SAETEOL to 30L, SOM to 10L))
        assertEquals(listOf(YANGTTE, ANGAE, SOM, SAETEOL), rode.order())
        assertEquals(listOf(SAETEOL, ANGAE, YANGTTE, SOM), rode.call(SAETEOL).call(ANGAE).order())
        assertEquals(rode.order(), rode.call(SAETEOL).uncall(SAETEOL).order())
    }

    @Test
    fun `벨트 2개, 부른 아기 1명이면 부른 아기와 오래 안 탄 아기가 탄다`() {
        val family = all.copy(lastRodeAt = mapOf(SAETEOL to 10L, SOM to 30L, ANGAE to 20L)).call(YANGTTE)
        val belted = setOf(REAR_LEFT, REAR_RIGHT)
        val seated = family.seated(belted)
        assertEquals(setOf(YANGTTE, SAETEOL), seated.riding(belted))
        seated.seatsAreUnique()
    }

    @Test
    fun `탈 자리보다 많이 부르면 먼저 부른 순서까지만 탄다`() {
        val seated = all.call(ANGAE).call(YANGTTE).call(SOM).seated(setOf(FRONT_RIGHT))
        assertEquals(setOf(ANGAE), seated.riding(setOf(FRONT_RIGHT)))
        seated.seatsAreUnique()
    }

    @Test
    fun `사람이 아기보다 많으면 아기가 모두 탄다`() {
        val two = FamilyState().adopt(SAETEOL, REAR_LEFT).adopt(SOM, REAR_RIGHT)
        val belted = SeatSlot.entries.toSet()
        assertEquals(setOf(SAETEOL, SOM), two.seated(belted).riding(belted))
        assertEquals(two, two.seated(belted))
    }

    @Test
    fun `아무도 안 탔으면 부른 아기도 타지 않는다`() {
        val called = all.call(ANGAE)
        assertEquals(called, called.seated(emptySet()))
        assertEquals(listOf(ANGAE), called.called)
    }

    @Test
    fun `앞서 탄 아기는 사람이 일부 내려도 그대로 앉아 있다`() {
        val start = all.copy(lastRodeAt = mapOf(SAETEOL to 4L, SOM to 3L, YANGTTE to 2L, ANGAE to 1L))
        val two = setOf(REAR_LEFT, REAR_RIGHT)
        val boarded = start.seated(two)
        assertEquals(setOf(ANGAE, YANGTTE), boarded.riding(two))
        val after = boarded.seated(setOf(REAR_RIGHT), held = two)
        assertEquals(boarded.spirits, after.spirits)
        assertTrue(after.riding(setOf(REAR_RIGHT)).single() in setOf(ANGAE, YANGTTE))
    }

    @Test
    fun `정차 중에 부르면 계속 탄 아기 대신 부른 아기가 앉는다`() {
        val start = all.copy(lastRodeAt = mapOf(SAETEOL to 1L, SOM to 2L, YANGTTE to 3L, ANGAE to 4L))
        val boarded = start.seated(setOf(REAR_CENTER))
        assertEquals(setOf(SAETEOL), boarded.riding(setOf(REAR_CENTER)))
        val swapped = boarded.call(ANGAE).seated(setOf(REAR_CENTER), held = setOf(REAR_CENTER))
        assertEquals(setOf(ANGAE), swapped.riding(setOf(REAR_CENTER)))
        swapped.seatsAreUnique()
    }

    @Test
    fun `달리는 중에는 앉은 아기를 옮기지 않는다`() {
        val boarded = all.seated(setOf(FRONT_RIGHT))
        val driving = boarded.call(ANGAE).seated(setOf(FRONT_RIGHT), held = setOf(FRONT_RIGHT), keep = setOf(FRONT_RIGHT))
        assertEquals(boarded.spirits, driving.spirits)
    }

    @Test
    fun `자리를 지키는 아기는 다시 고를 때도 옮기지 않는다`() {
        val keeper = all.seatLeft(REAR_LEFT)
        val seated = keeper.call(SOM).seated(setOf(FRONT_RIGHT), keep = keeper.away)
        assertEquals(REAR_LEFT, seated.spirits.first { it.preset == SOM }.seat)
        assertEquals(setOf(SAETEOL), seated.riding(setOf(FRONT_RIGHT)))
    }

    @Test
    fun `트립이 끝나면 탄 아기 시각을 남기고 부른 순서를 비운다`() {
        val trip = TripSummary(7, 600_000, 1_000f, 0, 0, 0, 0, seats = setOf(FRONT_RIGHT))
        val arrived = all.call(ANGAE).seated(setOf(FRONT_RIGHT)).arrived(trip, 99L)
        assertTrue(arrived.called.isEmpty())
        assertEquals(mapOf(ANGAE to 99L), arrived.lastRodeAt)
        assertEquals(listOf(ANGAE), arrived.rideOf(7).map { it.preset })
        assertTrue(arrived.rideOf(8).isEmpty())
        assertEquals(ANGAE, arrived.order().last())
    }

    @Test
    fun `아기를 지우면 부른 순서와 탄 시각도 지운다`() {
        val removed = all.call(SOM).copy(lastRodeAt = mapOf(SOM to 5L)).remove(SOM)
        assertTrue(removed.called.isEmpty())
        assertTrue(removed.lastRodeAt.isEmpty())
        assertEquals(removed, removed.call(SOM))
    }

    @Test
    fun `가족 파일의 새 값을 저장했다 읽어도 같다`() {
        val trip = TripSummary(7, 600_000, 1_000f, 0, 0, 0, 0, seats = setOf(FRONT_RIGHT, REAR_LEFT))
        val state = all.call(YANGTTE).seated(trip.seats).arrived(trip, 99L).call(SOM)
        assertEquals(state, FamilyCodec.decode(FamilyCodec.encode(state)))
        assertEquals(FamilyState(spirits = all.spirits), FamilyCodec.decode(FamilyCodec.encode(FamilyState(spirits = all.spirits))))
    }

    @Test
    fun `엔진은 안전벨트로 깨어난 자리만 탄 사람으로 센다`() {
        val seats = mutableListOf<DriveEvent.SeatsAwake>()
        val engine = DriveEventEngine { if (it is DriveEvent.SeatsAwake) seats += it }
        assertFalse(engine.beltsKnown)
        engine.onSeatTap(REAR_LEFT, 1)
        assertEquals(setOf(REAR_LEFT), seats.last().seats)
        assertTrue(seats.last().belted.isEmpty())
        engine.onSeatBelt(FRONT_RIGHT, false, 2)
        assertTrue(engine.beltsKnown)
        engine.onSeatBelt(FRONT_RIGHT, true, 3)
        assertEquals(setOf(FRONT_RIGHT), seats.last().belted)
        val before = seats.size
        engine.refreshSeats(4)
        assertEquals(before + 1, seats.size)
        assertEquals(setOf(FRONT_RIGHT), seats.last().belted)
    }

    @Test
    fun `여행 내내 탄 아기와 앨범이 맞는다`() {
        // 첫날은 둘이 타고, 다음 날 한 사람이 타면 어제 안 탄 아기가 탑니다.
        val events = mutableListOf<DriveEvent>()
        var family = SpiritPreset.entries.take(3).fold(FamilyState()) { f, p -> f.adopt(p) }
        var gifts = GiftState()
        var held = emptySet<SeatSlot>()
        var now = 1_000_000L
        val engine = DriveEventEngine { event ->
            events += event
            when (event) {
                is DriveEvent.SeatsAwake -> {
                    family = family.seated(event.belted, held, keep = if (event.parked) family.away else event.belted + family.away)
                    held = event.belted
                    gifts = Memories.onEvent(gifts, event, family)
                }
                is DriveEvent.TripEnded -> {
                    family = family.arrived(event.summary, event.atMs)
                    gifts = Memories.onEvent(gifts, event, family)
                }
                else -> Unit
            }
        }
        fun day(vararg belted: SeatSlot) {
            SeatSlot.entries.forEach { engine.onSeatBelt(it, false, now) }
            belted.forEach { engine.onSeatBelt(it, true, now) }
            engine.onGear(Gear.DRIVE, now)
            now += 20 * EngineConfig.MINUTE_MS
            engine.tick(now)
            engine.onGear(Gear.PARK, now)
            now += EngineConfig().arrivalHoldMs
            engine.tick(now)
            belted.forEach { engine.onSeatBelt(it, false, now) }
            now += EngineConfig.HOUR_MS
            engine.tick(now)
        }
        day(REAR_LEFT, REAR_RIGHT)
        val first = family.lastRide!!.presets.toSet()
        assertEquals(setOf(SAETEOL, SOM), first)
        day(REAR_CENTER)
        assertEquals(listOf(YANGTTE), family.lastRide!!.presets)
        assertEquals(2, gifts.familyTrips)
        assertTrue(Moment.GATHERED in gifts.album)
        family.seatsAreUnique()
    }
}
