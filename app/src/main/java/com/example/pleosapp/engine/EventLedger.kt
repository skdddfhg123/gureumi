package com.example.pleosapp.engine

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** 디버그 빌드가 느린 저장장치를 흉내 낼 때 끼웁니다. 제품에서는 비어 있습니다. */
object SyncHook {
    @Volatile
    var before: (() -> Unit)? = null
}

/**
 * 이벤트를 한 줄씩 덧붙이는 append-only 원장(JSON Lines). 펫 상태는 이 파일을 재생해 복원합니다.
 * 전원이 끊겨 마지막 줄이 잘려도 그 줄만 건너뜁니다. 클라우드 동기화의 입력이 될 파일입니다.
 */
class EventLedger(private val file: File) {

    private var tailChecked = false

    fun append(event: DriveEvent) {
        if (!event.persisted) return
        file.parentFile?.mkdirs()
        val line = encode(event).toString() + "\n"
        val prefix = if (tailChecked || !endsTorn()) "" else "\n"
        tailChecked = true
        FileOutputStream(file, true).use { out ->
            out.write((prefix + line).toByteArray())
            SyncHook.before?.invoke()
            // 페이지 캐시에만 있으면 시동이 꺼지는 순간 사라집니다. 이벤트는 드물어 비용이 작습니다.
            out.fd.sync()
        }
    }

    /** 전원이 끊겨 잘린 줄 바로 뒤에 이어 쓰면 새 줄까지 깨집니다. 앱이 뜬 뒤 첫 쓰기 때 한 번만 봅니다. */
    private fun endsTorn(): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return RandomAccessFile(file, "r").use { tail ->
            tail.seek(tail.length() - 1)
            tail.read() != '\n'.code
        }
    }

    fun readAll(): List<DriveEvent> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            try {
                decode(JSONObject(line))
            } catch (_: JSONException) {
                null
            } catch (_: IllegalArgumentException) {
                null // 열거형 값이 손상된 줄도 건너뜁니다.
            }
        }
    }

    private fun encode(event: DriveEvent): JSONObject {
        val json = JSONObject().put("at", event.atMs).put("type", typeOf(event))
        when (event) {
            is DriveEvent.TripStarted -> json.put("tripId", event.tripId)
            is DriveEvent.TripEnded -> with(event.summary) {
                json.put("tripId", tripId).put("durationMs", durationMs).put("distanceM", distanceM.toDouble())
                    .put("smoothStops", smoothStops).put("hardBrakes", hardBrakes)
                    .put("signalsUsed", signalsUsed).put("signalsMissed", signalsMissed)
                    .put("peakBrakeDecel", peakBrakeDecel.toDouble()).put("rearBelts", rearBelts)
                    .put("restedBefore", restedBefore)
                    .put("seats", JSONArray(seats.map { it.name })).put("tappedSeats", JSONArray(tappedSeats.map { it.name }))
                    .put("rearStillBelted", rearStillBelted)
                    .put("driverBelted", driverBelted)
            }
            is DriveEvent.SmoothStop -> json.put("peakDecel", event.peakDecel.toDouble())
            is DriveEvent.HardBrake -> json.put("decel", event.decel.toDouble()).put("fromSpeed", event.fromSpeed.toDouble())
            is DriveEvent.SignalUsed -> json.put("side", event.side.name).put("leadMs", event.leadMs)
            is DriveEvent.SignalMissed -> json.put("side", event.side.name)
            is DriveEvent.RearBeltRequested -> json.put("seat", event.seat.name)
            is DriveEvent.RearBeltBuckled -> json.put("seat", event.seat.name).put("parked", event.parked)
            is DriveEvent.RestTaken -> json.put("restMs", event.restMs)
            is DriveEvent.HealthAlert -> json.put("code", event.code)
            is DriveEvent.HealthRecovered -> json.put("code", event.code)
            is DriveEvent.BeltsReady, is DriveEvent.BeltsMissing, is DriveEvent.SeatsAwake,
            is DriveEvent.SeatLeft, is DriveEvent.SeatReturned -> Unit
        }
        return json
    }

    /**
     * 원장 한 줄의 종류 글자. 지금까지 쌓인 줄과 같아야 해서 손으로 적습니다.
     * 클래스 이름을 쓰면 R8이 이름을 줄인 판에서 재생이 모든 줄을 버립니다. 클래스 이름을 바꿔도 이 글자는 그대로 둡니다.
     */
    private fun typeOf(event: DriveEvent): String = when (event) {
        is DriveEvent.TripStarted -> "TripStarted"
        is DriveEvent.TripEnded -> "TripEnded"
        is DriveEvent.SmoothStop -> "SmoothStop"
        is DriveEvent.HardBrake -> "HardBrake"
        is DriveEvent.SignalUsed -> "SignalUsed"
        is DriveEvent.SignalMissed -> "SignalMissed"
        is DriveEvent.BeltsReady -> "BeltsReady"
        is DriveEvent.BeltsMissing -> "BeltsMissing"
        is DriveEvent.RearBeltRequested -> "RearBeltRequested"
        is DriveEvent.RearBeltBuckled -> "RearBeltBuckled"
        is DriveEvent.RestTaken -> "RestTaken"
        is DriveEvent.HealthAlert -> "HealthAlert"
        is DriveEvent.HealthRecovered -> "HealthRecovered"
        // 아래 셋은 원장에 남기지 않습니다(persisted = false). 빠짐없이 적어야 새 사건을 더할 때 컴파일러가 알려 줍니다.
        is DriveEvent.SeatsAwake -> "SeatsAwake"
        is DriveEvent.SeatLeft -> "SeatLeft"
        is DriveEvent.SeatReturned -> "SeatReturned"
    }

    /** 10/31 이전 줄에는 없는 필드입니다. */
    private fun readSeats(json: JSONObject, key: String): Set<SeatSlot> {
        val array = json.optJSONArray(key) ?: return emptySet()
        return (0 until array.length()).mapTo(LinkedHashSet()) { SeatSlot.fromStored(array.getString(it)) }
    }

    private fun decode(json: JSONObject): DriveEvent? {
        val at = json.getLong("at")
        return when (json.getString("type")) {
            "TripStarted" -> DriveEvent.TripStarted(at, json.getLong("tripId"))
            "TripEnded" -> DriveEvent.TripEnded(
                at,
                TripSummary(
                    json.getLong("tripId"), json.getLong("durationMs"), json.getDouble("distanceM").toFloat(),
                    json.getInt("smoothStops"), json.getInt("hardBrakes"),
                    json.getInt("signalsUsed"), json.getInt("signalsMissed"),
                    // 10/31 이전 줄에는 없는 필드입니다.
                    peakBrakeDecel = json.optDouble("peakBrakeDecel", 0.0).toFloat(),
                    rearBelts = json.optInt("rearBelts", 0),
                    restedBefore = json.optBoolean("restedBefore", false),
                    seats = readSeats(json, "seats"),
                    tappedSeats = readSeats(json, "tappedSeats"),
                    rearStillBelted = json.optBoolean("rearStillBelted", false),
                    driverBelted = json.optBoolean("driverBelted", false),
                ),
            )
            "SmoothStop" -> DriveEvent.SmoothStop(at, json.getDouble("peakDecel").toFloat())
            "HardBrake" -> DriveEvent.HardBrake(at, json.getDouble("decel").toFloat(), json.getDouble("fromSpeed").toFloat())
            "SignalUsed" -> DriveEvent.SignalUsed(at, TurnSide.valueOf(json.getString("side")), json.getLong("leadMs"))
            "SignalMissed" -> DriveEvent.SignalMissed(at, TurnSide.valueOf(json.getString("side")))
            "BeltsReady" -> DriveEvent.BeltsReady(at)
            "BeltsMissing" -> DriveEvent.BeltsMissing(at)
            "RearBeltRequested" -> DriveEvent.RearBeltRequested(at, SeatSlot.fromStored(json.getString("seat")))
            "RearBeltBuckled" -> DriveEvent.RearBeltBuckled(at, SeatSlot.fromStored(json.getString("seat")), json.getBoolean("parked"))
            "RestTaken" -> DriveEvent.RestTaken(at, json.getLong("restMs"))
            "HealthAlert" -> DriveEvent.HealthAlert(at, json.getString("code"))
            "HealthRecovered" -> DriveEvent.HealthRecovered(at, json.getString("code"))
            else -> null // 이전 버전의 `Drowsy` 등 모르는 종류는 건너뜁니다.
        }
    }
}
