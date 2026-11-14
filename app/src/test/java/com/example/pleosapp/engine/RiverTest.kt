package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 강가 장소 명세(`docs/design/river-spec.md`)의 장소, 물건, 손님, 고르는 선물, 저장 옮기기. */
class RiverTest {

    private fun trip(tripId: Long) = TripSummary(tripId, 600_000, 1_000f, 0, 0, 0, 0)

    /** 혼자 [count]번 도착합니다. 트립 ID는 이어서 붙입니다. */
    private fun GiftState.solo(count: Int = 1): GiftState =
        (1..count).fold(this) { state, _ -> state.afterTrip(trip(state.lastTripId + 1), emptyList(), (state.lastTripId + 1) * HOUR_MS) }

    /** 물건을 가진 채 강가가 열린 상태입니다. */
    private fun withItems(vararg kinds: ReturnKind) =
        GiftState(shelf = kinds.map { ReturnGift(it, null) }, places = PlaceState().open(Place.RIVER, 0L))

    @Test
    fun `첫 물건이 생긴 도착에 강가가 열리고 그 도착에는 손님이 없다`() {
        val first = GiftState().solo()
        assertEquals(listOf(Place.HOME, Place.RIVER), first.places.opened)
        assertEquals(1L, first.places.openedOn(Place.RIVER))
        assertNull(first.places.visitor)
        assertFalse(first.places.isOpen(Place.FOREST))
        assertEquals(1L, first.solo().places.openedOn(Place.RIVER))
    }

    @Test
    fun `물건이 없으면 강가가 닫혀 있고 칸에 놓을 수 없다`() {
        val closed = GiftState()
        assertEquals(listOf(Place.HOME), closed.places.opened)
        assertEquals(closed, closed.put(Place.RIVER, 0, ReturnKind.STONE))
    }

    @Test
    fun `가진 물건만 놓고 같은 종류는 한 칸에만 놓인다`() {
        val state = withItems(ReturnKind.STONE, ReturnKind.MAPLE)
        assertEquals(state, state.put(Place.RIVER, 0, ReturnKind.SHELL))
        val one = state.put(Place.RIVER, 0, ReturnKind.STONE)
        assertEquals(listOf(ReturnKind.STONE, null, null, null), one.places.slots(Place.RIVER))
        val moved = one.put(Place.RIVER, 2, ReturnKind.STONE)
        assertEquals(listOf(null, null, ReturnKind.STONE, null), moved.places.slots(Place.RIVER))
        assertEquals(moved, moved.put(Place.RIVER, PlaceState.SLOTS, ReturnKind.MAPLE))
        assertEquals(moved, moved.put(Place.HOME, 0, ReturnKind.MAPLE))
        assertEquals(List<ReturnKind?>(PlaceState.SLOTS) { null }, moved.take(Place.RIVER, 2).places.slots(Place.RIVER))
        // 놓아도 줄지 않습니다.
        assertEquals(state.shelf, moved.shelf)
    }

    @Test
    fun `아무것도 안 놓으면 손님이 오지 않는다`() {
        val state = withItems(ReturnKind.STONE).solo(3)
        assertNull(state.places.visitor)
        assertTrue(state.places.met.isEmpty())
        assertNull(withItems(ReturnKind.ACORN).put(Place.RIVER, 0, ReturnKind.ACORN).solo().places.visitor)
    }

    @Test
    fun `놓은 물건이 같으면 같은 손님이 온다`() {
        val placed = withItems(ReturnKind.STONE, ReturnKind.MAPLE).put(Place.RIVER, 0, ReturnKind.STONE)
        assertEquals(Guest.DUCK, placed.solo().places.visitor?.guest)
        assertEquals(placed.solo(), placed.solo())
        assertEquals(listOf(Guest.DUCK, Guest.DUCK), listOf(placed.solo(), placed.solo(2)).map { it.places.visitor?.guest })
    }

    @Test
    fun `점수가 높은 손님이 오고 같으면 오래 안 온 손님이 온다`() {
        val state = withItems(ReturnKind.STONE, ReturnKind.SEED, ReturnKind.MAPLE)
            .put(Place.RIVER, 0, ReturnKind.STONE).put(Place.RIVER, 1, ReturnKind.MAPLE)
        val first = state.solo()
        val second = first.solo()
        val third = second.solo()
        assertEquals(listOf(Guest.DUCK, Guest.FROG, Guest.DUCK), listOf(first, second, third).map { it.places.visitor?.guest })
        assertFalse(first.places.visitor!!.happy)
        val happy = third.put(Place.RIVER, 2, ReturnKind.SEED).solo()
        assertEquals(Guest.DUCK, happy.places.visitor?.guest)
        assertEquals(2, happy.places.visitor?.score)
        assertTrue(happy.places.visitor!!.happy)
    }

    @Test
    fun `다녀간 손님은 다음 도착까지 있고 흔적을 남긴다`() {
        val visited = withItems(ReturnKind.STONE).put(Place.RIVER, 0, ReturnKind.STONE).solo()
        assertEquals(visited.lastTripId, visited.places.visitor?.tripId)
        assertEquals(listOf(Guest.DUCK), visited.places.traces)
        assertEquals(1, visited.places.visits(Guest.DUCK))
        val empty = visited.take(Place.RIVER, 0).solo()
        assertNull(empty.places.visitor)
        assertEquals(listOf(Guest.DUCK), empty.places.traces)
    }

    @Test
    fun `짧은 트립에는 손님도 오지 않는다`() {
        val placed = withItems(ReturnKind.STONE).put(Place.RIVER, 0, ReturnKind.STONE)
        assertEquals(placed, placed.afterTrip(TripSummary(1, TimeTogether.MIN_TRIP_MS - 1, 10f, 0, 0, 0, 0), emptyList(), 1L))
    }

    @Test
    fun `흔적을 거두면 도감에 붙고 넘치면 오래된 것이 떠내려간다`() {
        val state = withItems(ReturnKind.STONE).put(Place.RIVER, 0, ReturnKind.STONE).solo(6)
        assertEquals(PlaceState.MAX_TRACES, state.places.traces.size)
        assertEquals(6, state.places.visits(Guest.DUCK))
        val collected = state.collect(Guest.DUCK)
        assertEquals(PlaceState.MAX_TRACES - 1, collected.places.traces.size)
        assertEquals(1, collected.places.collected(Guest.DUCK))
        assertEquals(collected, collected.collect(Guest.FROG))
    }

    @Test
    fun `강가 손님 셋을 다 만나면 숲 쉼터가 열린다`() {
        var state = withItems(ReturnKind.STONE, ReturnKind.MAPLE, ReturnKind.SHELL).put(Place.RIVER, 0, ReturnKind.STONE).solo()
        state = state.put(Place.RIVER, 0, ReturnKind.MAPLE).solo()
        assertFalse(state.places.isOpen(Place.FOREST))
        state = state.put(Place.RIVER, 0, ReturnKind.SHELL).solo()
        assertEquals(Guest.KINGFISHER, state.places.visitor?.guest)
        assertEquals(Guest.entries.toSet(), state.places.met)
        assertEquals(state.lastTripId, state.places.openedOn(Place.FOREST))
        assertEquals(Place.entries, state.places.opened)
    }

    @Test
    fun `손님마다 좋아하는 물건 하나는 일찍 나온다`() {
        val firstLiked = Guest.entries.map { guest -> ReturnKind.entries.indexOfFirst { it in guest.likes } }
        assertEquals(listOf(0, 1, 4), firstLiked)
        Guest.entries.forEach { assertTrue(it.hint.isNotBlank()) }
    }

    @Test
    fun `선물 3개 중 고르지 않은 것은 다음 병에 다시 나온다`() {
        val gifts = GiftState()
        assertEquals(Ladder.RUNGS.take(3), gifts.choices())
        val picked = gifts.choose(Ladder.RUNGS[2], jarsOpened = 1)
        assertEquals(listOf(Ladder.RUNGS[2]), picked.chosen)
        assertEquals(listOf(Ladder.RUNGS[0], Ladder.RUNGS[1], Ladder.RUNGS[3]), picked.choices())
        assertEquals(Ladder.RUNGS[0], picked.next)
        assertEquals(1, picked.claims)
        assertEquals(picked, picked.choose(Ladder.RUNGS[0], jarsOpened = 1))
        assertEquals(picked, picked.choose(Ladder.RUNGS[5], jarsOpened = 2))
        assertEquals(Ladder.RUNGS[0], picked.choose(Ladder.RUNGS[0], jarsOpened = 2).form)
    }

    @Test
    fun `다 고르면 후보가 없고 쪽지가 나온다`() {
        val full = GiftState(chosen = Ladder.RUNGS.reversed())
        assertTrue(full.choices().isEmpty())
        assertNull(full.next)
        assertEquals(1, full.open(Ladder.RUNGS.size + 1).notes)
    }

    @Test
    fun `옛 상태(연 칸 7, 자리 넷)를 옮겨도 가진 것과 아기가 그대로다`() {
        val oldGifts = """{"claims":7,"notes":0,"familyTrips":5,"album":["return"],""" +
            """"shelf":[{"kind":"stone","preset":"saeteol"},{"kind":"maple","preset":"som"}]}"""
        val gifts = KeepsakeCodec.decodeGifts(oldGifts)
        assertEquals(Ladder.RUNGS.take(7), gifts.chosen)
        assertEquals(Ladder.RUNGS.take(7).toSet(), gifts.owned)
        assertEquals(7, gifts.claims)
        assertEquals(listOf(ReturnKind.STONE, ReturnKind.MAPLE), gifts.kinds)
        assertEquals(listOf(SpiritPreset.SAETEOL, SpiritPreset.SOM), gifts.shelf.map { it.bringer })
        assertEquals(0L, gifts.places.openedOn(Place.RIVER))
        // 함께 탄 다섯 번째 트립은 4번째 트립의 물건 뒤 한 번이라 2점입니다.
        assertEquals(2, gifts.itemPoints)
        val oldFamily = """{"spirits":[{"preset":"saeteol","seat":"FRONT_RIGHT"},{"preset":"som","seat":"REAR_LEFT"},""" +
            """{"preset":"yangtte","seat":"REAR_CENTER"},{"preset":"angae","seat":"REAR_RIGHT"}],"away":["REAR_LEFT"]}"""
        val family = FamilyCodec.decode(oldFamily)
        assertEquals(SpiritPreset.entries, family.spirits.map { it.preset })
        assertTrue(family.called.isEmpty())
        assertEquals(SpiritPreset.entries, family.order())
    }

    @Test
    fun `물건이 없던 옛 파일은 강가가 닫혀 있다`() {
        val gifts = KeepsakeCodec.decodeGifts("""{"claims":2,"familyTrips":0}""")
        assertEquals(listOf(Place.HOME), gifts.places.opened)
        assertEquals(0, gifts.itemPoints)
    }

    @Test
    fun `장소와 손님을 저장했다 읽어도 같다`() {
        val state = withItems(ReturnKind.STONE, ReturnKind.MAPLE, ReturnKind.SHELL)
            .put(Place.RIVER, 0, ReturnKind.STONE).solo()
            .put(Place.RIVER, 0, ReturnKind.MAPLE).solo()
            .put(Place.RIVER, 3, ReturnKind.SHELL).solo()
            .collect(Guest.DUCK)
            .let { it.copy(chosen = listOf(Ladder.RUNGS[4], Ladder.RUNGS[0])) }
        assertTrue(state.places.isOpen(Place.FOREST))
        assertEquals(state, KeepsakeCodec.decodeGifts(KeepsakeCodec.encodeGifts(state)))
    }

    @Test
    fun `깨진 장소 값은 건너뛴다`() {
        val text = """{"shelf":[{"kind":"stone"}],"places":{"open":{"river":3,"moon":1},""" +
            """"slots":{"river":["stone","stone","lava",null,"maple"]},"visits":{"duck":-2,"frog":1},"traces":["owl","frog"],""" +
            """"visitor":{"guest":"owl"}}}"""
        val places = KeepsakeCodec.decodeGifts(text).places
        assertEquals(listOf(Place.HOME, Place.RIVER), places.opened)
        assertEquals(listOf(ReturnKind.STONE, null, null, null), places.slots(Place.RIVER))
        assertEquals(setOf(Guest.FROG), places.met)
        assertEquals(listOf(Guest.FROG), places.traces)
        assertNull(places.visitor)
    }

    private companion object {
        const val HOUR_MS = 60 * 60_000L
    }
}
