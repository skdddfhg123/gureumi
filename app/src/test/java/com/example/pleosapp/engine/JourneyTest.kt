package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 가족의 한 주를 엔진부터 앨범까지 이어서 돌립니다. 이벤트를 받는 순서는 `VehicleProbeService`와 같습니다. */
class JourneyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private inner class Car(ledgerName: String = "journey.jsonl") {
        val ledger = EventLedger(temp.root.resolve(ledgerName))
        var now = 1_793_500_000_000L
        var pet = PetState()
        var family = FamilyState()
        var gifts = GiftState()
        val spoken = mutableListOf<String>()
        val engine = DriveEventEngine { event -> accept(event) }

        /** 앞서 안전벨트로 깨어난 자리. `FamilyRepository.onSeats`처럼 계속 탄 사람을 가립니다. */
        private var belted: Set<SeatSlot> = emptySet()

        private fun accept(event: DriveEvent) {
            ledger.append(event)
            pet = pet.apply(event)
            when (event) {
                is DriveEvent.TripEnded -> if (event.summary.durationMs >= MIN_TRIP_MS) {
                    family = family.settleArrival(event.summary, pet.ailmentLine() != null).arrived(event.summary, event.atMs)
                    gifts = Memories.onEvent(gifts, event, family)
                    if (!event.summary.rearStillBelted) spoken += DiaryTemplate.speech(event.summary, pet, family)
                }
                is DriveEvent.SeatLeft -> {
                    family = family.seatLeft(event.seat)
                    gifts = Memories.onEvent(gifts, event, family)
                }
                is DriveEvent.SeatReturned -> {
                    family = family.seatReturned(event.seat)
                    gifts = Memories.onEvent(gifts, event, family)
                    if (event.greetsAloud) family.spiritAt(event.seat)?.let { spoken += FamilySpeech.welcomeBack(it.preset.displayName) }
                }
                is DriveEvent.SeatsAwake -> {
                    family = family.seated(event.belted, belted, keep = if (event.parked) family.away else event.belted + family.away)
                    belted = event.belted
                    gifts = Memories.onEvent(gifts, event, family)
                }
                is DriveEvent.RestTaken -> gifts = Memories.onEvent(gifts, event, family)
                else -> Unit
            }
        }

        fun pass(ms: Long) {
            now += ms
            engine.tick(now)
        }

        fun belt(seat: SeatSlot, buckled: Boolean) = engine.onSeatBelt(seat, buckled, now)

        fun door(seat: SeatSlot) {
            engine.onRearDoor(seat, true, now)
            engine.onRearDoor(seat, false, now)
        }

        fun drive(ms: Long = 20 * EngineConfig.MINUTE_MS) {
            engine.onGear(Gear.DRIVE, now)
            pass(ms)
            engine.onGear(Gear.PARK, now)
        }

        /** 아이가 벨트를 풀고 내립니다. */
        fun getOut(seat: SeatSlot) {
            belt(seat, false)
            pass(5_000)
            door(seat)
        }

        fun arrive() = pass(EngineConfig().arrivalHoldMs)
    }

    private val left = SeatSlot.REAR_LEFT
    private val right = SeatSlot.REAR_RIGHT

    private fun family(car: Car) {
        car.family = FamilyState().adopt(SpiritPreset.SAETEOL, left).adopt(SpiritPreset.SOM, right)
        SeatSlot.entries.forEach { car.belt(it, false) }
    }

    @Test
    fun `첫 등원 하루가 앨범과 선반에 남는다`() {
        val car = Car()
        family(car)

        car.pass(60_000)
        car.belt(left, true)
        car.belt(right, true)
        car.drive()
        car.arrive()
        assertEquals(1, car.gifts.familyTrips)
        assertEquals(1, car.gifts.shelf.size)
        assertEquals(listOf(Moment.FIRST_CLICK, Moment.THANKS, Moment.FIRST_RETURN, Moment.GATHERED), car.gifts.album.toList())
        assertTrue("벨트를 맨 채라 낭독이 없어야 합니다", car.spoken.isEmpty())

        car.getOut(left)
        car.getOut(right)
        assertEquals(setOf(left, right), car.family.away)
        assertTrue(Moment.SEND_OFF in car.gifts.album)
    }

    @Test
    fun `혼자 탄 날에는 맡긴 말을 전하고 앨범에 남는다`() {
        val car = Car()
        family(car)
        car.belt(left, true)
        car.drive()
        car.pass(1_000)
        car.belt(left, false)
        car.arrive()
        car.family = car.family.hold(SpiritPreset.SAETEOL, 1)
        car.door(left)

        car.pass(60 * EngineConfig.MINUTE_MS)
        car.drive()
        car.arrive()
        assertNull(car.family.pending)
        assertTrue(Moment.LETTER in car.gifts.album)
        assertTrue(car.spoken.last(), "새싹이 전해 달래. 이따 봐!" in car.spoken.last())
    }

    @Test
    fun `저녁에 다시 태우면 소리 내어 반기고 다음 트립도 함께한다`() {
        val car = Car()
        family(car)
        car.belt(left, true)
        car.drive()
        car.arrive()
        car.getOut(left)

        car.pass(8 * EngineConfig.HOUR_MS)
        car.door(left)
        car.belt(left, true)
        assertEquals("왔구나! 새싹이 반가워해.", car.spoken.last())
        assertTrue(car.family.away.isEmpty())
        assertTrue(Moment.WELCOME in car.gifts.album)

        car.pass(30_000)
        car.drive()
        car.arrive()
        assertEquals(2, car.gifts.familyTrips)
    }

    @Test
    fun `긴 길에서 쉬어 가면 깃털과 앨범이 함께 남는다`() {
        val car = Car()
        car.drive(EngineConfig.HOUR_MS)
        car.arrive()
        val before = car.pet.feathers
        car.pass(20 * EngineConfig.MINUTE_MS)
        car.drive(10 * EngineConfig.MINUTE_MS)
        car.arrive()
        assertEquals(before + 3 + 2, car.pet.feathers)
        assertTrue(Moment.REST_DAY in car.gifts.album)
        assertTrue(car.pet.lastTrip?.restedBefore == true)
    }

    @Test
    fun `짧게 움직인 트립은 가족 트립으로 세지 않는다`() {
        val car = Car()
        family(car)
        car.belt(left, true)
        car.drive(2_000)
        car.arrive()
        assertEquals(0, car.gifts.familyTrips)
        assertEquals(1, car.pet.trips)
    }

    @Test
    fun `원장만 다시 읽어도 깃털과 레벨이 그대로다`() {
        val car = Car("replay.jsonl")
        family(car)
        car.belt(left, true)
        repeat(6) {
            car.drive()
            car.arrive()
            car.pass(EngineConfig.HOUR_MS)
        }
        val replayed = car.ledger.readAll().fold(PetState(), PetState::apply)
        assertEquals(car.pet.feathers, replayed.feathers)
        assertEquals(car.pet.level, replayed.level)
        assertEquals(car.pet.counts, replayed.counts)
    }

    @Test
    fun `저장했다 읽어도 다음 칸과 다음 귀환품이 이어진다`() {
        val car = Car()
        family(car)
        car.belt(left, true)
        repeat(5) {
            car.drive()
            car.arrive()
            car.pass(EngineConfig.HOUR_MS)
        }
        // 함께 탄 트립은 2점입니다. 1, 3, 4번째에 물건이 생기고 5번째 뒤에는 2점이 남습니다.
        assertEquals(3, car.gifts.shelf.size)
        assertEquals(2, car.gifts.itemPoints)
        val saved = KeepsakeCodec.decodeGifts(KeepsakeCodec.encodeGifts(car.gifts))
        assertEquals(car.gifts, saved)
        val next = TripSummary(car.gifts.lastTripId + 1, 600_000, 1_000f, 0, 0, 0, 0)
        val after = saved.afterTrip(next, car.family.companions(setOf(left)), next.tripId)
        assertEquals(car.gifts.familyTrips + 1, after.familyTrips)
        assertEquals(saved.shelf.size + 1, after.shelf.size)
    }

    private companion object {
        const val MIN_TRIP_MS = 8_000L
    }
}
