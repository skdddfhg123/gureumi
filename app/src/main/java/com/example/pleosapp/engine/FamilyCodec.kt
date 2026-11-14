package com.example.pleosapp.engine

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 가족 파일 형식. 이름 문자열은 저장하지 않고 목록 번호만 저장합니다.
 * 11/3부터 자리 값은 지금 앉은 곳일 뿐입니다. 옛 파일의 자리도 그대로 읽어 처음 앉을 곳으로 씁니다.
 * 11/5부터 자리 값이 없거나 깨져도 아기는 그대로 읽습니다. 앞으로 자리 값을 쓰지 않게 되어도 이 파일을 읽습니다.
 */
object FamilyCodec {

    fun encode(state: FamilyState): String {
        val spirits = JSONArray()
        state.spirits.forEach { spirits.put(JSONObject().put("preset", it.preset.id).put("seat", it.seat.name)) }
        val json = JSONObject().put("spirits", spirits)
        state.pending?.let { json.put("pending", JSONObject().put("preset", it.preset.id).put("phrase", it.phrase)) }
        state.delivered?.let {
            json.put("delivered", JSONObject().put("tripId", it.tripId).put("preset", it.preset.id).put("phrase", it.phrase))
        }
        if (state.away.isNotEmpty()) json.put("away", JSONArray(state.away.map { it.name }))
        if (state.called.isNotEmpty()) json.put("called", JSONArray(state.called.map { it.id }))
        if (state.lastRodeAt.isNotEmpty()) json.put("lastRodeAt", JSONObject().also { rode -> state.lastRodeAt.forEach { (preset, at) -> rode.put(preset.id, at) } })
        state.lastRide?.let { json.put("lastRide", JSONObject().put("tripId", it.tripId).put("presets", JSONArray(it.presets.map { preset -> preset.id }))) }
        return json.toString()
    }

    /** 파일이 깨졌으면 비어 있는 상태로 시작합니다. 가족 정보 때문에 앱이 멈추면 안 됩니다. */
    fun decode(text: String): FamilyState = try {
        val json = JSONObject(text)
        val spirits = readSpirits(json.optJSONArray("spirits"))
        FamilyState(
            spirits = spirits,
            pending = json.optJSONObject("pending")?.let(::readHeld),
            delivered = json.optJSONObject("delivered")?.let(::readDelivered),
            // 지키는 자리는 아기가 앉은 자리만 남깁니다. 자리 값을 고쳐 읽은 아기 때문에 빈 자리를 지키지 않게 합니다.
            away = readAway(json.optJSONArray("away")).filterTo(LinkedHashSet()) { seat -> spirits.any { it.seat == seat } },
            called = readPresets(json.optJSONArray("called")),
            // 숫자가 아닌 시각과 트립 번호가 없는 기록은 건너뜁니다. 탈 차례가 조금 바뀔 뿐 가족은 그대로입니다.
            lastRodeAt = json.optJSONObject("lastRodeAt")?.let { rode ->
                SpiritPreset.entries.mapNotNull { preset -> (rode.opt(preset.id) as? Number)?.let { preset to it.toLong() } }.toMap()
            }.orEmpty(),
            lastRide = json.optJSONObject("lastRide")?.takeIf { it.opt("tripId") is Number }
                ?.let { Ride(it.getLong("tripId"), readPresets(it.optJSONArray("presets"))) },
        )
    } catch (_: JSONException) {
        FamilyState()
    } catch (_: IllegalArgumentException) {
        FamilyState()
    }

    /**
     * 자리 값은 엔진이 탈 차례대로 앉힌 곳이라 뜻이 없습니다. 자리 값 하나 때문에 가족을 잃지 않습니다.
     * 자리가 없거나 모르는 값이거나 앞의 아기와 겹치면 빈 자리를 줍니다. 같은 아기가 또 나오면 앞의 것만 씁니다.
     */
    private fun readSpirits(array: JSONArray?): List<FamilySpirit> {
        if (array == null) return emptyList()
        val read = (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val preset = SpiritPreset.fromId(item.optString("preset")) ?: return@mapNotNull null
            preset to runCatching { SeatSlot.fromStored(item.optString("seat")) }.getOrNull()
        }.distinctBy { it.first }.take(FamilyState.MAX_SPIRITS)
        val taken = HashSet<SeatSlot>()
        val kept = read.map { (preset, seat) -> preset to seat?.takeIf { taken.add(it) } }
        return kept.mapNotNull { (preset, seat) ->
            (seat ?: SeatSlot.entries.firstOrNull { taken.add(it) })?.let { FamilySpirit(preset, it) }
        }
    }

    /** 11/3 이전 파일에는 없는 필드입니다. 모르는 이름과 겹친 이름은 건너뜁니다. */
    private fun readPresets(array: JSONArray?): List<SpiritPreset> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { SpiritPreset.fromId(array.optString(it)) }.distinct()
    }

    /** 11/1 이전 파일에는 없는 필드입니다. 모르는 자리는 건너뜁니다. */
    private fun readAway(array: JSONArray?): Set<SeatSlot> {
        if (array == null) return emptySet()
        return (0 until array.length()).mapNotNullTo(LinkedHashSet()) { index ->
            SeatSlot.entries.firstOrNull { it.name == array.optString(index) }
        }
    }

    private fun readHeld(json: JSONObject): HeldMessage? {
        val preset = SpiritPreset.fromId(json.getString("preset")) ?: return null
        val phrase = json.getInt("phrase")
        return HeldMessage(preset, phrase).takeIf { phrase in SpiritPhrases.ALL.indices }
    }

    private fun readDelivered(json: JSONObject): DeliveredMessage? {
        val preset = SpiritPreset.fromId(json.getString("preset")) ?: return null
        val phrase = json.getInt("phrase")
        return DeliveredMessage(json.getLong("tripId"), preset, phrase).takeIf { phrase in SpiritPhrases.ALL.indices }
    }
}
