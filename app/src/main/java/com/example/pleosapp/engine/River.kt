package com.example.pleosapp.engine

import org.json.JSONArray
import org.json.JSONObject

/** 강을 따라 열리는 장소. 차 안 둥지는 처음부터 열려 있습니다. 지금 보는 장소는 화면만 압니다. */
enum class Place(val id: String, val title: String) {
    HOME("home", "차 안 둥지"),
    RIVER("river", "강가"),
    FOREST("forest", "숲 쉼터"),
    ;

    companion object {
        fun fromId(id: String?): Place? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 놓인 물건을 보고 다녀가는 손님. 좋아하는 것 하나는 일찍 나오는 물건이라 첫 손님이 빨리 옵니다.
 * 사람별 기록은 없고 몇 번 왔는지만 셉니다.
 */
enum class Guest(
    val id: String,
    val title: String,
    val place: Place,
    val likes: Set<ReturnKind>,
    val traceTitle: String,
    /** 아직 못 만난 손님의 실루엣 옆에 구름이가 하는 말. */
    val hint: String,
) {
    DUCK("duck", "오리", Place.RIVER, setOf(ReturnKind.STONE, ReturnKind.SEED), "노란 깃털", "물가 돌멩이를 좋아하는 친구가 있대"),
    FROG("frog", "개구리", Place.RIVER, setOf(ReturnKind.MAPLE, ReturnKind.CLOVER), "작은 연잎", "나뭇잎을 좋아하는 친구가 있대"),
    KINGFISHER("kingfisher", "물총새", Place.RIVER, setOf(ReturnKind.SHELL, ReturnKind.GLASS), "파란 깃털", "반짝이는 걸 좋아하는 친구가 있대"),
    ;

    /** 놓인 물건 중 이 손님이 좋아하는 것의 수입니다. */
    fun score(placed: Collection<ReturnKind?>): Int = likes.count { it in placed }

    companion object {
        fun fromId(id: String?): Guest? = entries.firstOrNull { it.id == id }
    }
}

/** 도착 때 다녀간 손님. 다음 도착까지 장소에 남아 있습니다. */
data class Visit(val guest: Guest, val score: Int, val tripId: Long, val atMs: Long) {
    /** 좋아하는 것이 둘 다 놓여 있으면 신나게 놀다 갑니다. 하나면 잠깐 다녀갑니다. */
    val happy: Boolean get() = score >= HAPPY_SCORE

    private companion object {
        const val HAPPY_SCORE = 2
    }
}

/** 장소와 칸, 다녀간 손님, 흔적과 도감. 선물 파일에 함께 저장합니다. */
data class PlaceState(
    /** 열린 장소와 연 트립. 0은 옛 기록을 옮기며 연 것이라 알리지 않습니다. */
    val openTrips: Map<Place, Long> = emptyMap(),
    /** 장소마다 칸 4개에 놓은 물건. 놓아도 줄지 않습니다. */
    val placed: Map<Place, List<ReturnKind?>> = emptyMap(),
    val visitCount: Map<Guest, Int> = emptyMap(),
    val lastVisitAt: Map<Guest, Long> = emptyMap(),
    val visitor: Visit? = null,
    /** 거두지 않은 흔적. 오래된 것부터이고, 넘치면 가장 오래된 것이 떠내려갑니다. */
    val traces: List<Guest> = emptyList(),
    val traceCount: Map<Guest, Int> = emptyMap(),
) {
    /** 열린 장소. 차 안 둥지가 먼저이고 나머지는 강을 따라가는 순서입니다. */
    val opened: List<Place> get() = Place.entries.filter(::isOpen)

    /** 만난 손님. 도감의 칸입니다. */
    val met: Set<Guest> get() = Guest.entries.filterTo(LinkedHashSet()) { visits(it) > 0 }

    fun isOpen(place: Place): Boolean = place == Place.HOME || place in openTrips

    /** 그 장소를 연 트립. 도착 카드는 이 값이 그 트립일 때 한 번 알립니다. */
    fun openedOn(place: Place): Long? = openTrips[place]

    fun slots(place: Place): List<ReturnKind?> = placed[place] ?: EMPTY_SLOTS

    fun visits(guest: Guest): Int = visitCount[guest] ?: 0

    fun collected(guest: Guest): Int = traceCount[guest] ?: 0

    fun open(place: Place, tripId: Long): PlaceState = if (isOpen(place)) this else copy(openTrips = openTrips + (place to tripId))

    /** 칸이 없거나 닫힌 장소, 없는 칸이면 그대로입니다. 같은 종류가 다른 칸에 있으면 그 칸은 비웁니다. */
    fun put(place: Place, index: Int, kind: ReturnKind): PlaceState {
        if (place == Place.HOME || !isOpen(place) || index !in 0 until SLOTS) return this
        val slots = slots(place).map { if (it == kind) null else it }.toMutableList()
        slots[index] = kind
        return copy(placed = placed + (place to slots.toList()))
    }

    fun take(place: Place, index: Int): PlaceState {
        if (index !in 0 until SLOTS || slots(place)[index] == null) return this
        val slots = slots(place).toMutableList()
        slots[index] = null
        return copy(placed = placed + (place to slots.toList()))
    }

    /**
     * 그 장소에 지금 올 손님. 뽑기가 아닙니다.
     * 좋아하는 물건이 많이 놓인 손님이 오고, 같으면 가장 오래 안 온 손님이 옵니다. 한 번도 안 온 손님이 먼저입니다.
     * 좋아하는 것이 하나도 없으면 아무도 오지 않습니다.
     */
    fun guestFor(place: Place): Guest? {
        if (!isOpen(place)) return null
        val slots = slots(place)
        return Guest.entries
            .filter { it.place == place && it.score(slots) > 0 }
            .sortedWith(compareByDescending<Guest> { it.score(slots) }.thenBy { lastVisitAt[it] ?: Long.MIN_VALUE })
            .firstOrNull()
    }

    /** 도착할 때 한 번 손님을 정합니다. 강가 손님을 다 만나면 숲 쉼터가 열립니다. */
    fun arrive(tripId: Long, atMs: Long): PlaceState {
        val guest = guestFor(Place.RIVER) ?: return copy(visitor = null)
        val after = copy(
            visitor = Visit(guest, guest.score(slots(guest.place)), tripId, atMs),
            visitCount = visitCount + (guest to visits(guest) + 1),
            lastVisitAt = lastVisitAt + (guest to atMs),
            traces = (traces + guest).takeLast(MAX_TRACES),
        )
        val riverGuests = Guest.entries.filter { it.place == Place.RIVER }
        return if (after.met.containsAll(riverGuests)) after.open(Place.FOREST, tripId) else after
    }

    /** 흔적 하나를 거둡니다. 거둔 흔적은 도감에 붙습니다. */
    fun collect(guest: Guest): PlaceState {
        val index = traces.indexOf(guest)
        if (index < 0) return this
        return copy(
            traces = traces.filterIndexed { i, _ -> i != index },
            traceCount = traceCount + (guest to collected(guest) + 1),
        )
    }

    companion object {
        const val SLOTS = 4
        const val MAX_TRACES = 4
        private val EMPTY_SLOTS: List<ReturnKind?> = List(SLOTS) { null }
    }
}

/** 장소 상태의 파일 형식. 선물 파일 안의 `places`입니다. 모르는 값은 건너뜁니다. */
object PlaceCodec {
    fun encode(state: PlaceState): JSONObject {
        val open = JSONObject()
        state.openTrips.forEach { (place, tripId) -> open.put(place.id, tripId) }
        val slots = JSONObject()
        state.placed.forEach { (place, kinds) -> slots.put(place.id, JSONArray(kinds.map { it?.id ?: JSONObject.NULL })) }
        val json = JSONObject().put("open", open).put("slots", slots)
            .put("visits", counts(state.visitCount)).put("lastVisitAt", times(state.lastVisitAt))
            .put("traces", JSONArray(state.traces.map { it.id })).put("collected", counts(state.traceCount))
        state.visitor?.let {
            json.put("visitor", JSONObject().put("guest", it.guest.id).put("score", it.score).put("tripId", it.tripId).put("atMs", it.atMs))
        }
        return json
    }

    fun decode(json: JSONObject?): PlaceState {
        if (json == null) return PlaceState()
        val open = json.optJSONObject("open") ?: JSONObject()
        val slots = json.optJSONObject("slots") ?: JSONObject()
        val traces = json.optJSONArray("traces") ?: JSONArray()
        return PlaceState(
            openTrips = Place.entries.filter { it != Place.HOME && open.has(it.id) }.associateWith { open.optLong(it.id) },
            placed = Place.entries.filter { it != Place.HOME && slots.has(it.id) }.associateWith { place ->
                val row = slots.optJSONArray(place.id) ?: JSONArray()
                // 같은 종류가 두 칸에 있으면 앞 칸만 남깁니다.
                val seen = HashSet<ReturnKind>()
                List(PlaceState.SLOTS) { index -> ReturnKind.fromId(row.optString(index))?.takeIf(seen::add) }
            },
            visitCount = Guest.entries.associateWith { (json.optJSONObject("visits")?.optInt(it.id) ?: 0).coerceAtLeast(0) }.filterValues { it > 0 },
            lastVisitAt = json.optJSONObject("lastVisitAt")
                ?.let { at -> Guest.entries.filter { at.has(it.id) }.associateWith { at.optLong(it.id) } }
                .orEmpty(),
            visitor = json.optJSONObject("visitor")?.let { row ->
                Guest.fromId(row.optString("guest"))?.let { Visit(it, row.optInt("score"), row.optLong("tripId"), row.optLong("atMs")) }
            },
            traces = (0 until traces.length()).mapNotNull { Guest.fromId(traces.optString(it)) }.takeLast(PlaceState.MAX_TRACES),
            traceCount = Guest.entries.associateWith { (json.optJSONObject("collected")?.optInt(it.id) ?: 0).coerceAtLeast(0) }.filterValues { it > 0 },
        )
    }

    private fun counts(map: Map<Guest, Int>): JSONObject = JSONObject().also { json -> map.forEach { (guest, n) -> json.put(guest.id, n) } }

    private fun times(map: Map<Guest, Long>): JSONObject = JSONObject().also { json -> map.forEach { (guest, at) -> json.put(guest.id, at) } }
}
