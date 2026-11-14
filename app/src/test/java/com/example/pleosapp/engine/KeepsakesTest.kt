package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepsakesTest {

    private fun opened(count: Int) = (1..count).fold(GiftState()) { state, _ -> state.open() }

    @Test
    fun `사다리는 열일곱 칸이고 순서가 고정이다`() {
        assertEquals(
            listOf(
                "read", "fern", "flap", "butterfly", "lamp", "wag", "scarf", "mobile", "boat",
                "tray", "vase", "cape", "featherbed", "hum", "apron", "raincoat", "overalls",
            ),
            Ladder.RUNGS.map { it.id },
        )
    }

    @Test
    fun `11월 4일에 뺀 칸은 사다리에 없고 이어받은 칸은 사다리에 있다`() {
        assertEquals(setOf(Reward.SCENE_SEA, Reward.SCENE_WINTER, Reward.DECOR_BOOKS, Reward.SCENE_FOREST), Ladder.REPLACED.keys)
        assertTrue(Ladder.REPLACED.keys.none { it in Ladder.RUNGS })
        assertTrue(Ladder.REPLACED.values.all { it in Ladder.RUNGS })
        assertTrue(Ladder.REPLACED.keys.none { it in opened(Ladder.RUNGS.size).owned })
    }

    @Test
    fun `모습 칸의 순서는 엽서 번호라 뒤에만 더한다`() {
        // 엽서 주소와 웹 엽서(share/index.html)가 이 순서의 번호로 옷을 고릅니다.
        assertEquals(
            listOf(Reward.FORM_READ, Reward.FORM_SCARF, Reward.FORM_CAPE, Reward.FORM_APRON, Reward.FORM_RAINCOAT),
            Ladder.RUNGS.filter { it.kind == RewardKind.FORM }.take(5),
        )
    }

    @Test
    fun `뺀 칸을 고른 옛 기록은 그 자리를 이어받은 칸으로 읽는다`() {
        val old = """{"chosen":["read","sea","winter","books","forest","sea"],"scene":"sea","form":"read"}"""
        val read = KeepsakeCodec.decodeGifts(old)
        assertEquals(
            listOf(Reward.FORM_READ, Reward.SCENE_BUTTERFLY, Reward.SCENE_BOAT, Reward.DECOR_FEATHERBED, Reward.FORM_OVERALLS),
            read.chosen,
        )
        assertEquals(Reward.SCENE_BUTTERFLY, read.scene)
        assertEquals(Reward.FORM_READ, read.form)
        // 칸 수는 그대로라 기다리는 병이 늘거나 줄지 않습니다.
        assertEquals(0, read.pending(5))
    }

    @Test
    fun `뺀 창밖을 쓰던 기록은 이어받은 풍경을 쓰고, 풍경이 아니면 기본 창이다`() {
        assertEquals(Reward.SCENE_BOAT, KeepsakeCodec.decodeGifts("""{"scene":"winter"}""").scene)
        assertNull(KeepsakeCodec.decodeGifts("""{"scene":"forest"}""").scene)
        assertNull(KeepsakeCodec.decodeGifts("""{"scene":"books"}""").scene)
        assertEquals(Reward.SCENE_LIGHTHOUSE, KeepsakeCodec.decodeGifts("""{"scene":"lighthouse"}""").scene)
    }

    @Test
    fun `연 칸 수만 있는 옛 기록도 같은 자리의 새 칸을 가진다`() {
        val read = KeepsakeCodec.decodeGifts("""{"claims":5}""")
        assertEquals(Ladder.RUNGS.take(5), read.chosen)
        assertTrue(Reward.SCENE_BUTTERFLY in read.owned)
    }

    @Test
    fun `사다리 칸은 겹치지 않고 습관과 정비의 동작을 넣지 않는다`() {
        assertEquals(Ladder.RUNGS.size, Ladder.RUNGS.toSet().size)
        val elsewhere = Badge.entries.map { it.reward } + CareItem.entries.mapNotNull { it.reward }
        assertTrue(Ladder.RUNGS.none { it in elsewhere })
    }

    @Test
    fun `첫 칸은 레벨 2에서 열리고 마지막 칸은 레벨 18이다`() {
        assertEquals(2, Ladder.level(Ladder.RUNGS.first()))
        assertEquals(18, Ladder.level(Ladder.RUNGS.last()))
    }

    @Test
    fun `병을 열 때마다 레벨과 칸이 하나씩 맞물린다`() {
        var feathers = 0
        var gifts = GiftState()
        Ladder.RUNGS.forEachIndexed { index, rung ->
            feathers += Growth.jar(feathers).capacity
            val pet = PetState(feathers = feathers)
            assertEquals(Ladder.level(rung), pet.level)
            assertEquals(rung, gifts.next)
            gifts = gifts.open(pet.jar.opened)
            assertEquals(index + 1, gifts.claims)
        }
        assertEquals(0, gifts.pending(PetState(feathers = feathers).jar.opened))
    }

    @Test
    fun `가득 찬 병만큼 선물이 기다린다`() {
        assertEquals(2, GiftState().pending(2))
        assertEquals(1, GiftState().open().pending(2))
        assertEquals(0, opened(2).pending(1))
    }

    @Test
    fun `안 열어도 선물은 사라지지 않는다`() {
        assertEquals(5, GiftState().pending(5))
    }

    @Test
    fun `병이 없으면 열어도 칸이 앞서가지 않는다`() {
        assertEquals(GiftState(), GiftState().open(0))
        assertEquals(1, GiftState().open(1).open(1).claims)
    }

    @Test
    fun `연 칸 수로 가진 것을 안다`() {
        assertEquals(Ladder.RUNGS.take(3).toSet(), opened(3).owned)
        assertEquals(Ladder.RUNGS[3], opened(3).next)
    }

    @Test
    fun `모습과 풍경은 열면 바로 입는다`() {
        assertEquals(Reward.FORM_READ, opened(1).form)
        assertEquals(Reward.SCENE_BUTTERFLY, opened(4).scene)
    }

    @Test
    fun `동작과 소품은 입는 것이 아니다`() {
        val state = opened(3)
        assertEquals(Reward.FORM_READ, state.form)
        assertNull(state.scene)
        assertTrue(Reward.MOTION_FLAP in state.owned)
    }

    @Test
    fun `벗으면 기본 모습으로 돌아간다`() {
        assertNull(opened(1).wear(null).form)
    }

    @Test
    fun `다음 칸은 종류만 알린다`() {
        val teaser = opened(3).next?.kind?.teaser.orEmpty()
        assertEquals("다음은 새 풍경이야.", teaser)
        assertFalse(teaser.contains(Reward.SCENE_BUTTERFLY.title))
    }

    @Test
    fun `다 모으면 쪽지가 나온다`() {
        val full = opened(Ladder.RUNGS.size)
        assertNull(full.next)
        val read = full.open()
        assertEquals(1, read.notes)
        assertEquals(Ladder.RUNGS.size, read.claims)
    }

    @Test
    fun `쪽지는 돌아가며 나오고 병 수를 앞서지 않는다`() {
        val full = opened(Ladder.RUNGS.size)
        assertEquals(1, full.pending(Ladder.RUNGS.size + 1))
        assertEquals(0, full.open().pending(Ladder.RUNGS.size + 1))
        assertTrue(full.nextNote().isNotBlank())
    }

    private val one = FamilyState().adopt(SpiritPreset.SAETEOL, SeatSlot.REAR_LEFT)
    private val two = one.adopt(SpiritPreset.SOM, SeatSlot.REAR_RIGHT)
    private val three = two.adopt(SpiritPreset.YANGTTE, SeatSlot.REAR_CENTER)

    private fun trip(tripId: Long) = TripSummary(tripId, 600_000, 1_000f, 0, 0, 0, 0)

    private fun GiftState.trips(count: Int, family: FamilyState = one) =
        (1..count).fold(this) { state, n -> state.afterTrip(trip(lastTripId + n), family.spirits, (lastTripId + n) * 1_000L) }

    private fun tripEnded(
        tripId: Long = 9,
        seats: Set<SeatSlot> = emptySet(),
        tapped: Set<SeatSlot> = emptySet(),
    ) = DriveEvent.TripEnded(tripId, TripSummary(tripId, 600_000, 1_000f, 0, 0, 0, 0, seats = seats, tappedSeats = tapped))

    @Test
    fun `첫 가족 주행에 반드시 귀환품이 온다`() {
        val state = GiftState().trips(1)
        assertEquals(ReturnGift(ReturnKind.STONE, SpiritPreset.SAETEOL, 1), state.shelf.single())
        assertTrue(Moment.FIRST_RETURN in state.album)
    }

    @Test
    fun `첫 보물은 아기가 처음 주워 온 날에 남는다`() {
        // 혼자 탄 날은 구름이가 주워 옵니다. 앨범 그림에는 아기가 있어서 함께한 순간으로 세지 않습니다.
        val solo = GiftState().trips(1, FamilyState())
        assertNull(solo.shelf.single().bringer)
        assertFalse(Moment.FIRST_RETURN in solo.album)
        // 함께 타면 두 번째 트립에 아기가 처음 가져옵니다.
        val together = solo.trips(2)
        assertNotNull(together.shelf.last().bringer)
        assertTrue(Moment.FIRST_RETURN in together.album)
    }

    @Test
    fun `혼자 타면 세 번마다, 함께 타면 더 자주 물건이 온다`() {
        val solo = (1..10).map { GiftState().trips(it, FamilyState()).shelf.size }
        assertEquals(listOf(1, 1, 1, 2, 2, 2, 3, 3, 3, 4), solo)
        val family = (1..10).map { GiftState().trips(it).shelf.size }
        assertEquals(listOf(1, 1, 2, 3, 3, 4, 5, 5, 6, 7), family)
    }

    @Test
    fun `혼자 3트립이면 물건 하나, 함께 2트립이면 하나 이상`() {
        val start = GiftState().trips(1, FamilyState())
        assertEquals(2, start.trips(3, FamilyState()).shelf.size)
        assertTrue(start.trips(2).shelf.size >= 2)
        assertEquals(1, GiftState().trips(3, FamilyState()).shelf.size)
    }

    @Test
    fun `혼자 탄 날은 구름이가 주워 오고 함께 탄 횟수는 그대로다`() {
        val state = GiftState().trips(1, FamilyState())
        assertNull(state.shelf.single().bringer)
        assertEquals(0, state.familyTrips)
        assertFalse(Moment.THANKS in state.album)
        assertEquals("구름이가 매끈한 돌을 주워 왔어.", FamilySpeech.brought(state.shelf.single()))
        assertEquals(state.shelf.single(), state.justBrought)
    }

    @Test
    fun `8초 미만의 트립은 세지 않는다`() {
        val short = TripSummary(1, TimeTogether.MIN_TRIP_MS - 1, 10f, 0, 0, 0, 0)
        assertEquals(GiftState(), GiftState().afterTrip(short, one.spirits, 1L))
        assertEquals(GiftState(), GiftState().afterTrip(short, emptyList(), 1L))
    }

    @Test
    fun `방금 생긴 물건만 justBrought로 보인다`() {
        val first = GiftState().trips(1, FamilyState())
        assertEquals(first.shelf.last(), first.justBrought)
        assertNull(first.trips(1, FamilyState()).justBrought)
    }

    @Test
    fun `귀환품은 뽑기가 아니라 정해진 순서로 온다`() {
        val first = GiftState().trips(40, two).shelf
        assertEquals(first, GiftState().trips(40, two).shelf)
        assertEquals(ReturnKind.entries.take(12), first.take(12).map { it.kind })
        assertEquals(ReturnKind.STONE, first[12].kind)
    }

    @Test
    fun `함께 탄 정령이 돌아가며 귀환품을 가져온다`() {
        val presets = GiftState().trips(4, two).shelf.map { it.bringer }
        assertEquals(listOf(SpiritPreset.SAETEOL, SpiritPreset.SOM, SpiritPreset.SAETEOL), presets)
    }

    /** 한국 시간 2026년 11월 [day]일 [hour]시 [minute]분. */
    private fun kst(day: Int, hour: Int, minute: Int = 0) =
        java.time.LocalDateTime.of(2026, 11, day, hour, minute).atOffset(java.time.ZoneOffset.ofHours(9)).toInstant().toEpochMilli()

    private fun GiftState.rideAt(atMs: Long, family: FamilyState = one) = afterTrip(trip(lastTripId + 1), family.spirits, atMs)

    @Test
    fun `아기와 함께 탄 날은 하루에 한 번만 센다`() {
        val state = GiftState().rideAt(kst(5, 8)).rideAt(kst(5, 18)).rideAt(kst(6, 8))
        assertEquals(3, state.familyTrips)
        assertEquals(2, state.familyDays)
        assertEquals(TimeTogether.dayOf(kst(6, 8)), state.lastFamilyDay)
    }

    @Test
    fun `함께 탄 날은 한국 시간 자정에 바뀐다`() {
        // 23:50과 다음 날 00:10은 세계 표준시로는 같은 날입니다.
        assertEquals(2, GiftState().rideAt(kst(5, 23, 50)).rideAt(kst(6, 0, 10)).familyDays)
        assertEquals(1, GiftState().rideAt(kst(5, 0, 10)).rideAt(kst(5, 23, 50)).familyDays)
    }

    @Test
    fun `혼자 탄 날과 늦게 온 지난 날의 도착은 함께 탄 날로 세지 않는다`() {
        assertEquals(0, GiftState().rideAt(kst(5, 8), FamilyState()).familyDays)
        assertEquals(1, GiftState().rideAt(kst(5, 8), FamilyState()).rideAt(kst(5, 18)).familyDays)
        assertEquals(1, GiftState().rideAt(kst(6, 8)).rideAt(kst(5, 18)).familyDays)
    }

    @Test
    fun `함께 탄 날 수를 저장하고 옛 파일은 함께 탄 트립 수로 어림한다`() {
        val state = GiftState().rideAt(kst(5, 8)).rideAt(kst(6, 8))
        val read = KeepsakeCodec.decodeGifts(KeepsakeCodec.encodeGifts(state))
        assertEquals(2, read.familyDays)
        assertEquals(state.lastFamilyDay, read.lastFamilyDay)
        val old = KeepsakeCodec.decodeGifts("""{"claims":2,"familyTrips":5}""")
        assertEquals(5, old.familyDays)
        assertNull(old.lastFamilyDay)
    }

    @Test
    fun `혼자 탄 트립은 함께 탄 트립으로 세지 않는다`() {
        val state = GiftState().trips(1).trips(1, FamilyState())
        assertEquals(1, state.familyTrips)
        assertEquals(1, state.itemPoints)
    }

    @Test
    fun `함께 도착한 첫날이 고마운 날이다`() {
        val state = Memories.onEvent(GiftState(), tripEnded(seats = setOf(SeatSlot.REAR_LEFT)), one)
        assertTrue(Moment.THANKS in state.album)
        assertEquals(1, state.familyTrips)
    }

    @Test
    fun `화면에서 깨운 정령도 함께한 트립이다`() {
        val state = Memories.onEvent(GiftState(), tripEnded(tapped = setOf(SeatSlot.REAR_LEFT)), one)
        assertEquals(1, state.familyTrips)
    }

    @Test
    fun `정령이 없는 뒷자리의 손님은 함께한 트립이 아니다`() {
        val after = Memories.onEvent(GiftState(), tripEnded(seats = setOf(SeatSlot.REAR_RIGHT)), one)
        assertEquals(0, after.familyTrips)
        assertNull(after.shelf.single().bringer)
    }

    @Test
    fun `처음 깨어난 날이 첫 찰칵이다`() {
        val awake = DriveEvent.SeatsAwake(1, setOf(SeatSlot.REAR_LEFT), setOf(SeatSlot.REAR_LEFT), waiting = false, parked = true)
        assertTrue(Moment.FIRST_CLICK in Memories.onEvent(GiftState(), awake, one).album)
    }

    @Test
    fun `벨트 신호가 없어도 화면에서 깨우면 첫 찰칵이 열린다`() {
        val tapped = DriveEvent.SeatsAwake(1, setOf(SeatSlot.REAR_LEFT), emptySet(), waiting = false, parked = true)
        assertTrue(Moment.FIRST_CLICK in Memories.onEvent(GiftState(), tapped, one).album)
    }

    @Test
    fun `정령이 없는 자리는 첫 찰칵이 아니다`() {
        val guest = DriveEvent.SeatsAwake(1, setOf(SeatSlot.REAR_RIGHT), setOf(SeatSlot.REAR_RIGHT), waiting = false, parked = true)
        assertEquals(GiftState(), Memories.onEvent(GiftState(), guest, one))
    }

    @Test
    fun `둘 이상 깨어 있는 트립이 다 모인 날이다`() {
        val both = setOf(SeatSlot.REAR_LEFT, SeatSlot.REAR_RIGHT)
        assertTrue(Moment.GATHERED in Memories.onEvent(GiftState(), tripEnded(seats = both), two).album)
    }

    @Test
    fun `셋 중 둘만 타도 다 모인 날이다`() {
        val both = setOf(SeatSlot.REAR_LEFT, SeatSlot.REAR_RIGHT)
        assertTrue(Moment.GATHERED in Memories.onEvent(GiftState(), tripEnded(seats = both), three).album)
    }

    @Test
    fun `혼자 깨어 있으면 다 모인 날이 아니다`() {
        val state = Memories.onEvent(GiftState(), tripEnded(seats = setOf(SeatSlot.REAR_LEFT)), two)
        assertFalse(Moment.GATHERED in state.album)
    }

    @Test
    fun `출발하지 않으면 다 모인 날이 아니다`() {
        val both = setOf(SeatSlot.REAR_LEFT, SeatSlot.REAR_RIGHT)
        val awake = DriveEvent.SeatsAwake(1, both, both, waiting = false, parked = true)
        assertFalse(Moment.GATHERED in Memories.onEvent(GiftState(), awake, two).album)
    }

    @Test
    fun `맡긴 말이 전해진 트립이 건넨 쪽지다`() {
        val trip = TripSummary(9, 600_000, 1_000f, 0, 0, 0, 0)
        val delivered = one.hold(SpiritPreset.SAETEOL, 0).settleArrival(trip, ailing = false)
        assertTrue(Moment.LETTER in Memories.onEvent(GiftState(), DriveEvent.TripEnded(9, trip), delivered).album)
    }

    @Test
    fun `다른 트립의 전달은 건넨 쪽지가 아니다`() {
        val trip = TripSummary(9, 600_000, 1_000f, 0, 0, 0, 0)
        val delivered = one.hold(SpiritPreset.SAETEOL, 0).settleArrival(trip, ailing = false)
        assertFalse(Moment.LETTER in Memories.onEvent(GiftState(), tripEnded(tripId = 10), delivered).album)
    }

    @Test
    fun `내린 자리에 정령이 있어야 첫 배웅이다`() {
        assertTrue(Moment.SEND_OFF in Memories.onEvent(GiftState(), DriveEvent.SeatLeft(1, SeatSlot.REAR_LEFT), one).album)
        assertEquals(GiftState(), Memories.onEvent(GiftState(), DriveEvent.SeatLeft(1, SeatSlot.REAR_RIGHT), one))
    }

    @Test
    fun `다시 탄 자리에 정령이 있어야 첫 반김이다`() {
        val back = DriveEvent.SeatReturned(2, SeatSlot.REAR_LEFT, 1, parked = true)
        assertTrue(Moment.WELCOME in Memories.onEvent(GiftState(), back, one).album)
        val guest = DriveEvent.SeatReturned(2, SeatSlot.REAR_RIGHT, 1, parked = true)
        assertEquals(GiftState(), Memories.onEvent(GiftState(), guest, one))
    }

    @Test
    fun `혼자 타도 쉬어 간 날은 열린다`() {
        assertTrue(Moment.REST_DAY in Memories.onEvent(GiftState(), DriveEvent.RestTaken(1, 900_000), FamilyState()).album)
    }

    @Test
    fun `앨범 여덟 순간을 모두 채울 수 있다`() {
        val family = two.hold(SpiritPreset.SAETEOL, 0)
        val trip = TripSummary(9, 600_000, 1_000f, 0, 0, 0, 0)
        val delivered = family.settleArrival(trip, ailing = false)
        val both = setOf(SeatSlot.REAR_LEFT, SeatSlot.REAR_RIGHT)
        val events = listOf(
            DriveEvent.SeatsAwake(1, both, both, waiting = false, parked = true),
            DriveEvent.RestTaken(2, 900_000),
            tripEnded(tripId = 3, seats = both),
            DriveEvent.TripEnded(9, trip),
            DriveEvent.SeatLeft(10, SeatSlot.REAR_LEFT),
            DriveEvent.SeatReturned(11, SeatSlot.REAR_LEFT, 1, parked = true),
        )
        val state = events.fold(GiftState()) { gifts, event -> Memories.onEvent(gifts, event, delivered) }
        assertEquals(Moment.entries.toSet(), state.album)
    }

    @Test
    fun `같은 순간은 한 번만 들어간다`() {
        val once = GiftState().remember(Moment.THANKS)
        assertEquals(once, once.remember(Moment.THANKS))
    }

    @Test
    fun `앨범은 일어난 순서를 지킨다`() {
        val state = GiftState().remember(Moment.WELCOME).remember(Moment.REST_DAY).remember(Moment.FIRST_CLICK)
        val back = KeepsakeCodec.decodeGifts(KeepsakeCodec.encodeGifts(state))
        assertEquals(listOf(Moment.WELCOME, Moment.REST_DAY, Moment.FIRST_CLICK), back.album.toList())
    }

    @Test
    fun `배지는 습관을 쌓으면 동작을 가르친다`() {
        val pet = PetState(counts = TripCounts(softStops = 30, signals = 49, rearBelts = 20))
        assertEquals(listOf(Badge.SOFT, Badge.BELT), Badge.earned(pet))
        assertEquals(
            setOf(Reward.MOTION_SWAY, Reward.MOTION_NOD),
            CareRules.owned(pet, GiftState(), CareState()),
        )
    }

    @Test
    fun `배지는 모두 동작으로 돌아온다`() {
        assertTrue(Badge.entries.all { it.reward.kind == RewardKind.MOTION })
    }

    @Test
    fun `금지된 조건의 배지는 없다`() {
        assertEquals(setOf("soft", "signal", "belt"), Badge.entries.map { it.id }.toSet())
    }

    @Test
    fun `별은 표시일 뿐 얻는 것을 바꾸지 않는다`() {
        val plain = PetState()
        val starred = PetState(stars = 50)
        assertEquals(CareRules.owned(plain, GiftState(), CareState()), CareRules.owned(starred, GiftState(), CareState()))
        assertTrue(Badge.earned(starred).isEmpty())
    }

    @Test
    fun `가진 것은 사다리와 습관과 정비를 합친다`() {
        val pet = PetState(counts = TripCounts(softStops = 30))
        val care = CareState(firstOdoKm = 0f).done(CareItem.OIL, 5L, 10_000f)
        val owned = CareRules.owned(pet, opened(3), care)
        assertEquals(Ladder.RUNGS.take(3).toSet() + Reward.MOTION_SWAY + Reward.MOTION_SHAKE, owned)
    }

    @Test
    fun `정비하면 동작을 배운다`() {
        val care = CareState(firstOdoKm = 0f).done(CareItem.OIL, 5L, 10_000f)
        assertEquals(setOf(Reward.MOTION_SHAKE), CareRules.owned(PetState(), GiftState(), care))
    }

    @Test
    fun `세차는 동작 없이 앞유리만 맑아진다`() {
        val care = CareState(firstOdoKm = 0f, firstSeenAtMs = 0L).done(CareItem.WASH, 18 * day, 0f)
        assertTrue(CareRules.owned(PetState(), GiftState(), care).isEmpty())
    }

    @Test
    fun `경고등을 꺼도 같은 동작을 배운다`() {
        val fixed = PetState()
            .apply(DriveEvent.HealthAlert(0L, "TPMS"))
            .apply(DriveEvent.HealthRecovered(24 * 60 * 60_000L, "TPMS"))
        assertTrue(Reward.MOTION_HOP in CareRules.owned(fixed, GiftState(), CareState()))
    }

    @Test
    fun `처음 본 주행거리부터 주기를 센다`() {
        val state = CareState().observe(40_000f).observe(50_000f)
        assertEquals(40_000f, state.firstOdoKm)
        assertEquals(CareStatus.DUE, state.status(CareItem.OIL, 50_000f))
    }

    @Test
    fun `주행거리가 없으면 괜찮은 것으로 본다`() {
        assertEquals(CareStatus.OK, CareState().status(CareItem.OIL, 12_000f))
        assertNull(CareState().nextDue(12_000f))
    }

    @Test
    fun `주기의 90퍼센트부터 곧 챙길 때다`() {
        val state = CareState(firstOdoKm = 0f)
        assertEquals(CareStatus.OK, state.status(CareItem.OIL, 8_999f))
        assertEquals(CareStatus.SOON, state.status(CareItem.OIL, 9_000f))
        assertEquals(CareStatus.DUE, state.status(CareItem.OIL, 10_000f))
    }

    @Test
    fun `때가 된 정비는 별을 주고 주기를 다시 시작한다`() {
        val state = CareState(firstOdoKm = 0f).done(CareItem.OIL, 5L, 10_200f)
        assertEquals(1, state.stars)
        assertEquals(5L, state.lastStarAtMs)
        assertEquals(CareStatus.OK, state.status(CareItem.OIL, 10_300f))
    }

    @Test
    fun `미리 눌러도 기록은 남지만 별은 없다`() {
        val state = CareState(firstOdoKm = 0f).done(CareItem.OIL, 5L, 100f)
        assertEquals(0, state.stars)
        assertEquals(1, state.records.size)
        assertEquals(10_000f, state.leftKm(CareItem.OIL, 100f))
    }

    @Test
    fun `같은 항목을 연달아 눌러 별을 벌 수 없다`() {
        val state = CareState(firstOdoKm = 0f).done(CareItem.OIL, 5L, 10_000f).done(CareItem.OIL, 6L, 10_000f)
        assertEquals(1, state.stars)
    }

    @Test
    fun `가장 급한 항목을 먼저 알린다`() {
        val state = CareState(firstOdoKm = 0f)
        assertEquals(CareItem.OIL, state.nextDue(9_500f))
        assertEquals(CareItem.ROTATION, state.done(CareItem.OIL, 1L, 9_500f).nextDue(9_500f))
    }

    private val day = 24 * 60 * 60_000L

    @Test
    fun `평소에는 아무것도 달라지지 않는다`() {
        assertEquals(CareLook(), CareRules.look(PetState(), CareState(firstOdoKm = 0f, firstSeenAtMs = 0L), 100f, day))
    }

    @Test
    fun `세차한 지 오래되면 앞유리가 점점 뿌예진다`() {
        val care = CareState(firstOdoKm = 0f, firstSeenAtMs = 0L)
        val early = CareRules.look(PetState(), care, 0f, 10 * day).dust
        val late = CareRules.look(PetState(), care, 0f, 18 * day).dust
        assertTrue(early > 0f)
        assertTrue(late > early)
    }

    @Test
    fun `방치해도 먼지는 한계를 넘지 않는다`() {
        val care = CareState(firstOdoKm = 0f, firstSeenAtMs = 0L)
        assertEquals(0.7f, CareRules.look(PetState(), care, 0f, 400 * day).dust, 0.0001f)
    }

    @Test
    fun `세차하면 앞유리가 맑아진다`() {
        val care = CareState(firstOdoKm = 0f, firstSeenAtMs = 0L).done(CareItem.WASH, 18 * day, 0f)
        assertEquals(0f, CareRules.look(PetState(), care, 0f, 18 * day).dust)
    }

    @Test
    fun `워셔액이 부족하면 바로 뿌옇다`() {
        val pet = PetState(ailments = setOf(PetState.WASHER_CODE))
        assertTrue(CareRules.look(pet, CareState(firstSeenAtMs = 0L), 0f, day).dust >= 0.8f)
    }

    @Test
    fun `오일 교환 때가 되면 털이 푸석해지고 교환하면 반짝인다`() {
        val care = CareState(firstOdoKm = 0f)
        assertTrue(CareRules.look(PetState(), care, 9_500f, day).dullFur)
        val done = CareRules.look(PetState(), care.done(CareItem.OIL, day, 9_500f), 9_500f, day)
        assertFalse(done.dullFur)
        assertTrue(done.gleaming)
    }

    @Test
    fun `타이어 경고등이 켜지면 발이 불편하다`() {
        assertTrue(CareRules.look(PetState(ailments = setOf("TPMS")), CareState(), 0f, day).soreFoot)
    }

    @Test
    fun `필터를 갈 때가 되면 재채기한다`() {
        val care = CareState(firstOdoKm = 0f)
        assertTrue(CareRules.look(PetState(), care, 14_000f, day).sneezy)
        assertFalse(CareRules.look(PetState(), care.done(CareItem.FILTER, day, 14_000f), 14_000f, day).sneezy)
    }

    @Test
    fun `배터리 경고가 켜지면 병 불빛이 약해진다`() {
        assertTrue(CareRules.look(PetState(ailments = setOf("LOW_VOLT_BATTERY")), CareState(), 0f, day).dimLight)
    }

    @Test
    fun `연료가 부족하면 배고플 뿐 아프지 않다`() {
        val pet = PetState(trips = 3).apply(DriveEvent.HealthAlert(0L, "LOW_FUEL"))
        assertEquals(Mood.HAPPY, pet.mood)
        assertTrue(CareRules.look(pet, CareState(), 0f, day).hungry)
        assertEquals(HomeSpeech.FUEL, HomeSpeech.line(pet, FamilyState(), emptySet()))
    }

    @Test
    fun `주유는 별을 주지 않는다`() {
        val pet = PetState()
            .apply(DriveEvent.HealthAlert(0L, "LOW_FUEL"))
            .apply(DriveEvent.HealthRecovered(day, "LOW_FUEL"))
        assertEquals(0, pet.stars)
    }

    @Test
    fun `정비 뒤 일주일 동안만 반짝인다`() {
        val care = CareState(firstOdoKm = 0f).done(CareItem.OIL, 0L, 9_500f)
        assertTrue(CareRules.look(PetState(), care, 9_500f, 6 * day).gleaming)
        assertFalse(CareRules.look(PetState(), care, 9_500f, 8 * day).gleaming)
    }

    @Test
    fun `경고등에 실제 이름을 붙인다`() {
        assertEquals("타이어 공기압", CareTerms.title("TPMS"))
        assertEquals("차량 점검", CareTerms.title("UNKNOWN"))
    }

    @Test
    fun `선물 파일을 읽고 쓴다`() {
        val state = opened(5)
            .copy(notes = 2, badgesSeen = setOf(Badge.SOFT))
            .trips(4, two)
            .remember(Moment.WELCOME)
            .copy(momentAt = mapOf(Moment.WELCOME to 1_793_500_000_000L))
        assertEquals(state, KeepsakeCodec.decodeGifts(KeepsakeCodec.encodeGifts(state)))
    }

    @Test
    fun `순간이 처음 든 시각은 파일에도 남는다`() {
        val first = Memories.onEvent(GiftState(), DriveEvent.RestTaken(7, 900_000), FamilyState())
        val again = Memories.onEvent(first, DriveEvent.RestTaken(9, 900_000), FamilyState())
        assertEquals(7L, again.momentAt[Moment.REST_DAY])
        assertEquals(again, KeepsakeCodec.decodeGifts(KeepsakeCodec.encodeGifts(again)))
    }

    @Test
    fun `정비 파일을 읽고 쓴다`() {
        val state = CareState(firstOdoKm = 100f, firstSeenAtMs = 3L).done(CareItem.FILTER, 7L, 16_000f)
        assertEquals(state, KeepsakeCodec.decodeCare(KeepsakeCodec.encodeCare(state)))
    }

    @Test
    fun `낡은 선물 파일도 읽고 사다리 칸 수로 이어 간다`() {
        val old = """{"owned":["dew","rocket"],"claims":2,"notes":1,"weatherTrips":{"RAIN":3},"visitorsSeen":["cat"],"form":"blossom","scene":"trunk"}"""
        val state = KeepsakeCodec.decodeGifts(old)
        assertEquals(2, state.claims)
        assertEquals(Ladder.RUNGS.take(2).toSet(), state.owned)
        assertNull(state.form)
        assertNull(state.scene)
    }

    @Test
    fun `파일의 모르는 값은 건너뛴다`() {
        val text = """{"album":["click","gone"],"shelf":[{"kind":"stone","preset":"gone"},{"kind":"rock","preset":"som"},7,{"kind":"maple","preset":"som"}],"badgesSeen":["night"]}"""
        val state = KeepsakeCodec.decodeGifts(text)
        assertEquals(setOf(Moment.FIRST_CLICK), state.album)
        // 가져온 아기를 모르면 물건은 남기고 구름이가 주워 온 것으로 읽습니다. 강가에 놓을 물건이라 버리지 않습니다.
        assertEquals(listOf(ReturnGift(ReturnKind.STONE, null), ReturnGift(ReturnKind.MAPLE, SpiritPreset.SOM)), state.shelf)
        assertTrue(state.badgesSeen.isEmpty())
        assertTrue(KeepsakeCodec.decodeCare("""{"records":[{"item":"nitro"}]}""").records.isEmpty())
    }

    @Test
    fun `음수가 적힌 파일을 읽어도 죽지 않는다`() {
        val state = KeepsakeCodec.decodeGifts("""{"claims":-3,"notes":-1,"familyTrips":-2}""")
        assertTrue(state.owned.isEmpty())
        assertTrue(state.nextNote().isNotBlank())
        assertEquals(1, state.afterTrip(trip(1), one.spirits, 1L).familyTrips)
    }
}
