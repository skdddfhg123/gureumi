package com.example.pleosapp.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 내 운전을 닮는 구름이, 작은 정령의 일, 조수석 동승, 주행 제한 신호(운전자 중심 보강 명세 1, 3, 4, 7장). */
class DriverLayerTest {

    private fun pet(soft: Int = 0, signals: Int = 0, belts: Int = 0) =
        PetState(counts = TripCounts(softStops = soft, signals = signals, rearBelts = belts))

    @Test
    fun `습관 배지가 없으면 닮음이 없다`() {
        assertNull(Badge.likeness(PetState()))
        assertNull(Badge.likeness(pet(soft = 29, signals = 49, belts = 19)))
    }

    @Test
    fun `배지가 하나면 그 배지를 닮는다`() {
        assertEquals(Badge.SIGNAL, Badge.likeness(pet(soft = 29, signals = 50)))
    }

    @Test
    fun `여럿이면 목표 대비 가장 많이 쌓인 배지를 닮는다`() {
        // 횟수는 깜빡이가 많아도 목표의 배수는 안전벨트가 큽니다.
        assertEquals(Badge.BELT, Badge.likeness(pet(soft = 30, signals = 60, belts = 25)))
        assertEquals(Badge.SIGNAL, Badge.likeness(pet(soft = 30, signals = 90, belts = 20)))
    }

    @Test
    fun `진행률이 같으면 앞선 배지다`() {
        assertEquals(Badge.SOFT, Badge.likeness(pet(soft = 60, signals = 100, belts = 40)))
        assertEquals(Badge.SIGNAL, Badge.likeness(pet(signals = 55, belts = 22)))
    }

    @Test
    fun `받지 못한 습관은 진행률이 커도 닮지 않는다`() {
        assertEquals(Badge.SOFT, Badge.likeness(pet(soft = 30, signals = 49)))
    }

    @Test
    fun `급제동이 많아도 닮음을 잃지 않는다`() {
        val trip = TripSummary(1, 600_000, 1_000f, 30, 9, 0, 9)
        val state = PetState().apply(DriveEvent.TripEnded(1, trip))
        assertEquals(Badge.SOFT, Badge.likeness(state))
    }

    @Test
    fun `닮음은 동작에 맞는 말로 한다`() {
        // 흉내말과 춤에 "~을 하다"를 붙이지 않습니다. 꾸벅 인사는 "인사를 하다"라 그대로입니다.
        assertEquals("너를 닮아서 살랑살랑 춤을 자주 춰.", HomeSpeech.likenessLine(Badge.SOFT))
        assertEquals("너를 닮아서 고개를 자주 갸우뚱해.", HomeSpeech.likenessLine(Badge.SIGNAL))
        assertEquals("가족을 닮아서 꾸벅 인사를 자주 해.", HomeSpeech.likenessLine(Badge.BELT))
    }

    private val lastTrip = TripSummary(1, 60_000, 100f, 0, 0, 0, 0)

    private fun greeted(days: Int, soft: Int = 30) = HomeSpeech.line(
        pet(soft = soft).copy(trips = 5, lastTrip = lastTrip, together = TogetherState(days = days)),
        FamilyState(),
        emptySet(),
    )

    @Test
    fun `일주일부터 사흘에 한 번 함께한 날과 번갈아 닮음을 말한다`() {
        assertEquals("벌써 9일째 같이 달렸어.", greeted(9))
        assertEquals(HomeSpeech.likenessLine(Badge.SOFT), greeted(12))
        assertEquals("벌써 15일째 같이 달렸어.", greeted(15))
        assertEquals(HomeSpeech.likenessLine(Badge.SOFT), greeted(18))
    }

    @Test
    fun `사흘 차례가 아니면 닮음을 말하지 않는다`() {
        assertEquals(HomeSpeech.DAY, greeted(13))
    }

    @Test
    fun `일주일 전에는 닮음을 말하지 않는다`() {
        assertEquals(HomeSpeech.DAY, greeted(6))
    }

    @Test
    fun `닮음이 없으면 그 차례도 함께한 날을 말한다`() {
        assertEquals("벌써 12일째 같이 달렸어.", greeted(12, soft = 0))
    }

    @Test
    fun `밤에는 닮음을 말하지 않는다`() {
        val state = pet(soft = 30).copy(trips = 5, lastTrip = lastTrip, together = TogetherState(days = 12))
        assertEquals(HomeSpeech.NIGHT, HomeSpeech.line(state, FamilyState(), emptySet(), SceneState(DayPart.NIGHT, Sky.CLEAR)))
    }

    private fun trips(count: Int) = GiftState(familyTrips = count)

    @Test
    fun `함께 탄 횟수의 경계에서 할 줄 아는 일이 는다`() {
        assertEquals(FriendSkill.BASIC, trips(0).friendSkill)
        assertEquals(FriendSkill.BASIC, trips(2).friendSkill)
        assertEquals(FriendSkill.HABIT, trips(3).friendSkill)
        assertEquals(FriendSkill.HABIT, trips(9).friendSkill)
        assertEquals(FriendSkill.WORK, trips(10).friendSkill)
        assertEquals(FriendSkill.WORK, trips(29).friendSkill)
        assertEquals(FriendSkill.DUO, trips(30).friendSkill)
        assertEquals(FriendSkill.DUO, trips(500).friendSkill)
    }

    @Test
    fun `단계가 오른 트립에서만 새로 배웠다고 본다`() {
        assertNull(trips(0).justLearned)
        assertNull(trips(2).justLearned)
        assertEquals(FriendSkill.HABIT, trips(3).justLearned)
        assertNull(trips(4).justLearned)
        assertEquals(FriendSkill.WORK, trips(10).justLearned)
        assertEquals(FriendSkill.DUO, trips(30).justLearned)
        assertNull(trips(31).justLearned)
    }

    private val family = FamilyState()
        .adopt(SpiritPreset.SOM, SeatSlot.REAR_LEFT)
        .adopt(SpiritPreset.SAETEOL, SeatSlot.REAR_RIGHT)

    private fun trip(vararg seats: SeatSlot, tapped: Set<SeatSlot> = emptySet(), durationMs: Long = 60_000) =
        TripSummary(7, durationMs, 1_000f, 1, 0, 0, 0, seats = seats.toSet(), tappedSeats = tapped)

    @Test
    fun `단계가 오른 도착에 일기가 한 줄 는다`() {
        val lines = DiaryTemplate.lines(trip(SeatSlot.REAR_LEFT), PetState(), family, trips(10))
        assertEquals(3, lines.size)
        assertEquals("도토리가 내 일을 거들기 시작했어.", lines[2])
    }

    @Test
    fun `단계마다 할 줄 알게 된 일을 말한다`() {
        val line = { count: Int -> DiaryTemplate.lines(trip(SeatSlot.REAR_LEFT), PetState(), family, trips(count)).last() }
        assertEquals("도토리가 자기 몸짓을 보여 주기 시작했어.", line(3))
        assertEquals("도토리가 나랑 같이 놀기 시작했어.", line(30))
    }

    @Test
    fun `여럿이면 함께 탄 첫 정령의 이름을 쓴다`() {
        val both = DiaryTemplate.lines(trip(SeatSlot.REAR_LEFT, SeatSlot.REAR_RIGHT), PetState(), family, trips(10))
        assertEquals("도토리가 내 일을 거들기 시작했어.", both[2])
        val second = DiaryTemplate.lines(trip(SeatSlot.REAR_RIGHT), PetState(), family, trips(10))
        assertEquals("새싹이 내 일을 거들기 시작했어.", second[2])
    }

    @Test
    fun `화면에서 깨운 정령도 이름이 나온다`() {
        val lines = DiaryTemplate.lines(trip(tapped = setOf(SeatSlot.REAR_LEFT)), PetState(), family, trips(10))
        assertEquals("도토리가 내 일을 거들기 시작했어.", lines[2])
    }

    @Test
    fun `단계가 오르지 않은 도착은 두 줄이다`() {
        assertEquals(2, DiaryTemplate.lines(trip(SeatSlot.REAR_LEFT), PetState(), family, trips(11)).size)
    }

    @Test
    fun `혼자 온 도착은 지난 단계를 다시 말하지 않는다`() {
        assertEquals(2, DiaryTemplate.lines(trip(), PetState(), family, trips(10)).size)
    }

    @Test
    fun `건강 안내가 있어도 배운 일은 함께 적는다`() {
        val lines = DiaryTemplate.lines(trip(SeatSlot.REAR_LEFT), PetState(ailments = setOf("TPMS")), family, trips(3))
        assertTrue(lines[1], "타이어" in lines[1])
        assertEquals("도토리가 자기 몸짓을 보여 주기 시작했어.", lines[2])
    }

    @Test
    fun `도착 순서대로 돌려도 단계가 오른 도착에만 줄이 붙는다`() {
        var gifts = GiftState()
        val added = (1..10).map {
            val summary = trip(SeatSlot.REAR_LEFT)
            gifts = Memories.onEvent(gifts, DriveEvent.TripEnded(it.toLong(), summary), family)
            DiaryTemplate.lines(summary, PetState(), family, gifts).size
        }
        assertEquals(listOf(2, 2, 3, 2, 2, 2, 2, 2, 2, 3), added)
    }

    private val secondLine = { t: TripSummary, f: FamilyState -> DiaryTemplate.lines(t, PetState(), f)[1] }

    @Test
    fun `조수석에 사람이 타면 정령이 없어도 고마운 날이다`() {
        val after = Memories.onEvent(GiftState(), DriveEvent.TripEnded(9, trip(SeatSlot.FRONT_RIGHT)), FamilyState())
        assertTrue(Moment.THANKS in after.album)
        assertEquals(9L, after.momentAt[Moment.THANKS])
    }

    @Test
    fun `아기 없이 함께 탄 트립은 함께 탄 횟수를 늘리지 않고 물건은 구름이가 주워 온다`() {
        val after = Memories.onEvent(GiftState(), DriveEvent.TripEnded(9, trip(SeatSlot.FRONT_RIGHT)), FamilyState())
        assertEquals(0, after.familyTrips)
        assertNull(after.shelf.single().bringer)
        // 구름이가 주운 물건은 첫 보물 순간이 아닙니다. 앨범 그림에 아기가 있습니다.
        assertFalse(Moment.FIRST_RETURN in after.album)
    }

    @Test
    fun `8초 미만이면 조수석 동승이 아니다`() {
        val short = trip(SeatSlot.FRONT_RIGHT, durationMs = TimeTogether.MIN_TRIP_MS - 1)
        assertFalse(FamilyState().rodeBeside(short))
        assertTrue(FamilyState().rodeBeside(trip(SeatSlot.FRONT_RIGHT, durationMs = TimeTogether.MIN_TRIP_MS)))
    }

    @Test
    fun `화면에서 깨운 조수석은 동승이 아니다`() {
        assertFalse(FamilyState().rodeBeside(trip(tapped = setOf(SeatSlot.FRONT_RIGHT))))
    }

    @Test
    fun `조수석 동승 일기`() {
        assertEquals("오늘은 같이 타서 든든했어.", secondLine(trip(SeatSlot.FRONT_RIGHT), FamilyState()))
    }

    @Test
    fun `조수석에 정령이 있으면 가족 트립이다`() {
        val beside = FamilyState().adopt(SpiritPreset.SOM, SeatSlot.FRONT_RIGHT)
        assertFalse(beside.rodeBeside(trip(SeatSlot.FRONT_RIGHT)))
        assertEquals("도토리가 벨트를 먼저 챙겨 줬어, 고마워.", secondLine(trip(SeatSlot.FRONT_RIGHT), beside))
        val after = Memories.onEvent(GiftState(), DriveEvent.TripEnded(9, trip(SeatSlot.FRONT_RIGHT)), beside)
        assertEquals(1, after.familyTrips)
    }

    @Test
    fun `뒷자리 정령과 함께면 가족 고마움이 먼저고 횟수도 는다`() {
        val summary = trip(SeatSlot.FRONT_RIGHT, SeatSlot.REAR_LEFT)
        assertEquals("도토리가 벨트를 먼저 챙겨 줬어, 고마워.", secondLine(summary, family))
        val after = Memories.onEvent(GiftState(), DriveEvent.TripEnded(9, summary), family)
        assertEquals(1, after.familyTrips)
        assertEquals(1, after.shelf.size)
    }

    @Test
    fun `뒷자리 친구 고마움도 조수석보다 먼저다`() {
        val summary = trip(SeatSlot.FRONT_RIGHT).copy(rearBelts = 1)
        assertEquals("친구가 벨트를 먼저 챙겨 줬어, 고마워.", secondLine(summary, FamilyState()))
    }

    @Test
    fun `조수석 동승은 혼자 탄 날의 맡긴 말을 막지 않는다`() {
        val held = family.hold(SpiritPreset.SOM, 0).settleArrival(trip(SeatSlot.FRONT_RIGHT, durationMs = 600_000), ailing = false)
        assertEquals(FamilySpeech.carried("도토리", SpiritPhrases.ALL[0]), secondLine(trip(SeatSlot.FRONT_RIGHT), held))
    }

    @Test
    fun `혼자 탄 날은 조수석 말이 없다`() {
        assertNotEquals(FamilySpeech.BESIDE, secondLine(trip(), FamilyState()))
    }

    @After
    fun `주행 제한을 풀어 둔다`() = DrivingGate.update(false)

    @Test
    fun `주행 제한 신호가 바뀌면 듣는 쪽에 알린다`() {
        val heard = mutableListOf<Boolean>()
        val listener: (Boolean) -> Unit = { heard += it }
        DrivingGate.addListener(listener)
        DrivingGate.update(true)
        assertTrue(DrivingGate.restricted)
        DrivingGate.update(false)
        assertFalse(DrivingGate.restricted)
        DrivingGate.removeListener(listener)
        assertEquals(listOf(true, false), heard)
    }

    @Test
    fun `값이 같으면 다시 알리지 않는다`() {
        val heard = mutableListOf<Boolean>()
        val listener: (Boolean) -> Unit = { heard += it }
        DrivingGate.addListener(listener)
        repeat(3) { DrivingGate.update(true) }
        DrivingGate.removeListener(listener)
        assertEquals(listOf(true), heard)
    }

    @Test
    fun `지운 뒤에는 듣지 않는다`() {
        val heard = mutableListOf<Boolean>()
        val listener: (Boolean) -> Unit = { heard += it }
        DrivingGate.addListener(listener)
        DrivingGate.removeListener(listener)
        DrivingGate.update(true)
        assertTrue(heard.isEmpty())
    }
}
