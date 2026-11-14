package com.example.pleosapp.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EventLedgerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val samples = listOf(
        DriveEvent.TripStarted(1, 7),
        DriveEvent.BeltsReady(2),
        DriveEvent.BeltsMissing(2),
        DriveEvent.SmoothStop(3, 1.5f),
        DriveEvent.HardBrake(4, 7.25f, 20f),
        DriveEvent.SignalUsed(5, TurnSide.LEFT, 1_500),
        DriveEvent.SignalMissed(6, TurnSide.RIGHT),
        DriveEvent.RearBeltRequested(7, SeatSlot.REAR_LEFT),
        DriveEvent.RearBeltBuckled(7, SeatSlot.REAR_RIGHT, parked = true),
        DriveEvent.RestTaken(8, 900_000),
        DriveEvent.HealthAlert(9, "TPMS"),
        DriveEvent.HealthRecovered(10, "TPMS"),
        DriveEvent.TripEnded(
            11,
            TripSummary(
                7, 60_000, 1234.5f, 2, 1, 3, 4, 6.5f, 1, true,
                seats = setOf(SeatSlot.REAR_LEFT, SeatSlot.FRONT_RIGHT), tappedSeats = setOf(SeatSlot.REAR_CENTER),
                rearStillBelted = true,
                driverBelted = true,
            ),
        ),
    )

    @Test
    fun `자리 값이 없는 옛 줄과 옛 자리 이름을 그대로 읽는다`() {
        val file = temp.newFile("old.jsonl")
        file.writeText(
            """
            {"at":7,"type":"RearBeltRequested","seat":"LEFT"}
            {"at":8,"type":"RearBeltBuckled","seat":"RIGHT","parked":true}
            {"at":9,"type":"TripEnded","tripId":7,"durationMs":60000,"distanceM":1.0,"smoothStops":0,"hardBrakes":0,"signalsUsed":0,"signalsMissed":0}
            """.trimIndent(),
        )
        val events = EventLedger(file).readAll()
        assertEquals(DriveEvent.RearBeltRequested(7, SeatSlot.REAR_LEFT), events[0])
        assertEquals(DriveEvent.RearBeltBuckled(8, SeatSlot.REAR_RIGHT, parked = true), events[1])
        assertEquals(emptySet<SeatSlot>(), (events[2] as DriveEvent.TripEnded).summary.seats)
        assertEquals(false, (events[2] as DriveEvent.TripEnded).summary.rearStillBelted)
        assertEquals(false, (events[2] as DriveEvent.TripEnded).summary.driverBelted)
    }

    @Test
    fun `왕복 이벤트는 파일에 쓰지 않는다`() {
        val file = temp.newFile("round.jsonl")
        val ledger = EventLedger(file)
        ledger.append(DriveEvent.SeatLeft(1, SeatSlot.REAR_LEFT))
        ledger.append(DriveEvent.SeatReturned(2, SeatSlot.REAR_LEFT, 1, parked = true))
        assertEquals(0L, file.length())
    }

    @Test
    fun `깨어 있는 자리 이벤트는 파일에 쓰지 않는다`() {
        val file = temp.newFile("seats.jsonl")
        EventLedger(file).append(DriveEvent.SeatsAwake(1, setOf(SeatSlot.REAR_LEFT), emptySet(), waiting = false, parked = true))
        assertEquals(0L, file.length())
    }

    @Test
    fun `모든 이벤트를 저장하고 그대로 읽는다`() {
        val ledger = EventLedger(temp.newFolder().resolve("sub/events.jsonl"))
        samples.forEach(ledger::append)
        assertEquals(samples, ledger.readAll())
    }

    @Test
    fun `원장을 재생하면 같은 펫 상태가 복원된다`() {
        val ledger = EventLedger(temp.newFile())
        samples.forEach(ledger::append)
        val live = samples.fold(PetState(), PetState::apply)
        assertEquals(live, ledger.readAll().fold(PetState(), PetState::apply))
    }

    @Test
    fun `전원 차단으로 잘린 마지막 줄은 건너뛴다`() {
        val file = temp.newFile()
        val ledger = EventLedger(file)
        ledger.append(DriveEvent.SmoothStop(1, 1f))
        file.appendText("""{"at":2,"type":"Hard""")
        assertEquals(listOf<DriveEvent>(DriveEvent.SmoothStop(1, 1f)), ledger.readAll())
    }

    @Test
    fun `잘린 줄 뒤에 쓴 새 줄은 살아남는다`() {
        val file = temp.newFile()
        EventLedger(file).append(DriveEvent.SmoothStop(1, 1f))
        file.appendText("""{"at":2,"type":"Hard""")
        val reopened = EventLedger(file)
        reopened.append(DriveEvent.SmoothStop(3, 1f))
        reopened.append(DriveEvent.SmoothStop(4, 1f))
        assertEquals(listOf<DriveEvent>(DriveEvent.SmoothStop(1, 1f), DriveEvent.SmoothStop(3, 1f), DriveEvent.SmoothStop(4, 1f)), reopened.readAll())
    }

    @Test
    fun `온전한 파일에는 빈 줄을 넣지 않는다`() {
        val file = temp.newFile()
        EventLedger(file).append(DriveEvent.SmoothStop(1, 1f))
        EventLedger(file).append(DriveEvent.SmoothStop(2, 1f))
        assertEquals(2, file.readLines().size)
    }

    @Test
    fun `이전 형식의 줄과 없어진 이벤트를 읽어도 멈추지 않는다`() {
        val file = temp.newFile()
        file.writeText(
            """{"at":1,"type":"Drowsy","drivingMs":7200000}
{"at":2,"type":"TripEnded","tripId":1,"durationMs":60000,"distanceM":100.0,"smoothStops":1,"hardBrakes":0,"signalsUsed":0,"signalsMissed":0}
{"at":3,"type":"SignalUsed","side":"UPWARD","leadMs":1}
""",
        )
        val events = EventLedger(file).readAll()
        assertEquals(listOf<DriveEvent>(DriveEvent.TripEnded(2, TripSummary(1, 60_000, 100f, 1, 0, 0, 0))), events)
    }

    /** R8이 클래스 이름을 줄여도 원장 글자는 그대로여야 합니다. 이 글자를 바꾸면 쌓인 원장을 못 읽습니다. */
    @Test
    fun `원장 한 줄의 종류 글자는 고정이다`() {
        val file = temp.newFile()
        val ledger = EventLedger(file)
        samples.forEach(ledger::append)
        val types = file.readLines().map { JSONObject(it).getString("type") }
        assertEquals(
            listOf(
                "TripStarted", "BeltsReady", "BeltsMissing", "SmoothStop", "HardBrake", "SignalUsed", "SignalMissed",
                "RearBeltRequested", "RearBeltBuckled", "RestTaken", "HealthAlert", "HealthRecovered", "TripEnded",
            ),
            types,
        )
    }

    /** 11/8까지 기기에 쌓인 줄 모양 그대로입니다. 읽는 쪽이 이 글자를 계속 읽어야 합니다. */
    @Test
    fun `지금까지 쌓인 원장 줄을 그대로 읽는다`() {
        val file = temp.newFile()
        file.writeText(
            """
            {"at":1,"type":"TripStarted","tripId":7}
            {"at":2,"type":"BeltsReady"}
            {"at":2,"type":"BeltsMissing"}
            {"at":3,"type":"SmoothStop","peakDecel":1.5}
            {"at":4,"type":"HardBrake","decel":7.25,"fromSpeed":20}
            {"at":5,"type":"SignalUsed","side":"LEFT","leadMs":1500}
            {"at":6,"type":"SignalMissed","side":"RIGHT"}
            {"at":7,"type":"RearBeltRequested","seat":"REAR_LEFT"}
            {"at":7,"type":"RearBeltBuckled","seat":"REAR_RIGHT","parked":true}
            {"at":8,"type":"RestTaken","restMs":900000}
            {"at":9,"type":"HealthAlert","code":"TPMS"}
            {"at":10,"type":"HealthRecovered","code":"TPMS"}
            {"at":11,"type":"TripEnded","tripId":7,"durationMs":60000,"distanceM":1234.5,"smoothStops":2,"hardBrakes":1,"signalsUsed":3,"signalsMissed":4,"peakBrakeDecel":6.5,"rearBelts":1,"restedBefore":true,"seats":["REAR_LEFT","FRONT_RIGHT"],"tappedSeats":["REAR_CENTER"],"rearStillBelted":true,"driverBelted":true}
            """.trimIndent() + "\n",
        )
        assertEquals(samples, EventLedger(file).readAll())
    }

    @Test
    fun `파일이 없으면 빈 목록이다`() {
        assertEquals(emptyList<DriveEvent>(), EventLedger(temp.newFolder().resolve("none.jsonl")).readAll())
    }
}
