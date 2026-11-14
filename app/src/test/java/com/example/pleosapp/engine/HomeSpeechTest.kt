package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSpeechTest {

    @Test
    fun `아픈 말은 두 줄로 나눈다`() {
        val pet = PetState(ailments = setOf("TPMS"))
        assertEquals("발이 아파.\n타이어 공기압을 봐 줘.", HomeSpeech.line(pet, FamilyState(), emptySet()))
    }

    /** 비 오는 밤에 하늘이 좋다고 하지 않고, 찔러도 늘지 않는 깃털을 찾았다고 하지 않습니다. */
    @Test
    fun `하늘 말은 맑은 아침과 낮에만 찌를 때 섞인다`() {
        assertTrue(HomeSpeech.SKY_POKE in HomeSpeech.pokes(SceneState(DayPart.DAY, Sky.CLEAR)))
        assertTrue(HomeSpeech.SKY_POKE in HomeSpeech.pokes(SceneState(DayPart.MORNING, Sky.CLEAR)))
        listOf(SceneState(DayPart.NIGHT, Sky.CLEAR), SceneState(DayPart.EVENING, Sky.CLEAR), SceneState(DayPart.DAY, Sky.RAIN)).forEach {
            assertEquals(it.toString(), HomeSpeech.POKES, HomeSpeech.pokes(it))
        }
        assertFalse(HomeSpeech.POKES.any { "찾았어" in it })
    }

    @Test
    fun `정비하면 배운 동작을 말한다`() {
        assertTrue("콩콩" in HomeSpeech.careThanks(CareItem.ROTATION))
        assertTrue("부르르" in HomeSpeech.careThanks(CareItem.OIL))
    }

    @Test
    fun `선반에 놓이지 않는 소품은 선반을 말하지 않는다`() {
        listOf(Reward.DECOR_LAMP, Reward.DECOR_MOBILE, Reward.DECOR_FEATHERBED, Reward.SCENE_BOAT).forEach {
            assertFalse(HomeSpeech.rewardLine(it), "선반" in HomeSpeech.rewardLine(it))
        }
        assertTrue("노을빛" in HomeSpeech.rewardLine(Reward.DECOR_LAMP))
        assertTrue("컵" in HomeSpeech.rewardLine(Reward.DECOR_VASE))
    }

    @Test
    fun `구름이는 새로 얻은 것을 말할 때 제 이름을 부르지 않는다`() {
        Reward.entries.forEach {
            assertFalse(HomeSpeech.rewardLine(it), "구름이" in HomeSpeech.rewardLine(it))
        }
    }

    @Test
    fun `모습과 흉내말 동작은 공개 카드 제목을 되풀이하지 않는다`() {
        assertEquals("귀를 쫑긋 세울 수 있어.\n눌러 봐.", HomeSpeech.rewardLine(Reward.MOTION_FLAP))
        assertEquals("꼬리로 톡톡 칠 수 있어.\n눌러 봐.", HomeSpeech.rewardLine(Reward.MOTION_WAG))
        assertEquals("콧노래를 배웠어.\n눌러 봐.", HomeSpeech.rewardLine(Reward.MOTION_HUM))
        assertEquals("앨범을 펼쳐 봤어.\n어때?", HomeSpeech.rewardLine(Reward.FORM_READ))
        // 공개 카드가 제목으로 이름을 보여 줍니다. 모습의 한마디는 이름 대신 "어때?"만 묻습니다.
        Reward.entries.filter { it.kind == RewardKind.FORM }.forEach {
            val line = HomeSpeech.rewardLine(it)
            assertFalse(line, it.title in line)
            assertTrue(line, line.endsWith("어때?"))
        }
    }

    private val trip = TripSummary(tripId = 1, durationMs = 60_000, distanceM = 100f, smoothStops = 0, hardBrakes = 0, signalsUsed = 0, signalsMissed = 0)
    private val one = FamilyState().adopt(SpiritPreset.SOM, SeatSlot.REAR_LEFT)
    private val two = one.adopt(SpiritPreset.SAETEOL, SeatSlot.REAR_RIGHT)

    @Test
    fun `처음에는 자기를 소개한다`() {
        assertEquals(HomeSpeech.HELLO, HomeSpeech.line(PetState(), FamilyState(), emptySet()))
    }

    @Test
    fun `처음 켠 날 세 마디를 본 뒤에는 아기 데려오기를 권한다`() {
        assertEquals(HomeSpeech.HELLO, HomeSpeech.line(PetState(), FamilyState(), emptySet(), introSeen = false))
        assertEquals(HomeSpeech.FIRST_ADOPT, HomeSpeech.line(PetState(), FamilyState(), emptySet(), introSeen = true))
    }

    @Test
    fun `세 마디는 첫인사로 시작해 본 뒤에 두는 말로 끝난다`() {
        assertEquals(listOf(HomeSpeech.HELLO, HomeSpeech.FIRST_JAR, HomeSpeech.FIRST_ADOPT), HomeSpeech.FIRST_RUN)
        assertEquals(HomeSpeech.line(PetState(), FamilyState(), emptySet()), HomeSpeech.FIRST_RUN.first())
        assertEquals(HomeSpeech.line(PetState(), FamilyState(), emptySet(), introSeen = true), HomeSpeech.FIRST_RUN.last())
    }

    @Test
    fun `트립이나 아기가 있으면 세 마디를 본 것과 상관없다`() {
        listOf(false, true).forEach { seen ->
            assertEquals(HomeSpeech.INVITE, HomeSpeech.line(PetState(trips = 1, lastTrip = trip), FamilyState(), emptySet(), introSeen = seen))
            assertEquals(HomeSpeech.PLAYING, HomeSpeech.line(PetState(), one, emptySet(), introSeen = seen))
        }
    }

    @Test
    fun `세 마디를 본 뒤에도 아프거나 어지러우면 몸 이야기를 먼저 한다`() {
        val sick = PetState(ailments = setOf("TPMS"))
        assertEquals("발이 아파.\n타이어 공기압을 봐 줘.", HomeSpeech.line(sick, FamilyState(), emptySet(), introSeen = true))
        assertEquals(HomeSpeech.DIZZY, HomeSpeech.line(PetState(sickness = 2), FamilyState(), emptySet(), introSeen = true))
    }

    @Test
    fun `처음 켠 날 새 두 마디는 두 줄이고 줄마다 15자 이하다`() {
        // 말풍선 한 줄은 15자 안팎입니다(HomeActivity.placeBubble).
        listOf(HomeSpeech.FIRST_JAR, HomeSpeech.FIRST_ADOPT).forEach { line ->
            val rows = line.split("\n")
            assertEquals(line, 2, rows.size)
            rows.forEach { assertTrue(it, it.length <= 15) }
        }
    }

    @Test
    fun `정령이 없으면 처음 몇 번은 가족을 권하고 그 뒤에는 조르지 않는다`() {
        assertEquals(HomeSpeech.INVITE, HomeSpeech.line(PetState(trips = 1, lastTrip = trip), FamilyState(), emptySet()))
        assertEquals(HomeSpeech.DAY, HomeSpeech.line(PetState(trips = 5, lastTrip = trip), FamilyState(), emptySet()))
    }

    @Test
    fun `밤에는 잠든 정령과 안전벨트를 말한다`() {
        val line = HomeSpeech.line(PetState(), one, emptySet(), SceneState(DayPart.NIGHT, Sky.CLEAR))
        assertEquals(HomeSpeech.WAITING, line)
        assertTrue(line, "안전벨트를 매면" in line)
    }

    @Test
    fun `낮에도 둥지에서 자는 정령을 논다고 말하지 않는다`() {
        // 11/5부터 안 탄 아기는 낮에도 둥지에서 잡니다(배경 v2).
        listOf(DayPart.MORNING, DayPart.DAY, DayPart.EVENING).forEach { part ->
            val line = HomeSpeech.line(PetState(), one, emptySet(), SceneState(part, Sky.CLEAR))
            assertEquals(HomeSpeech.PLAYING, line)
            assertTrue(line, "둥지에서 자고" in line)
            assertFalse(line, "놀" in line)
            assertTrue(line, "안전벨트를 매면" in line)
        }
    }

    @Test
    fun `혼자 타는 날이 이어지면 빈자리를 말하지 않는다`() {
        assertEquals(HomeSpeech.DAY, HomeSpeech.line(PetState(trips = 5, lastTrip = trip), one, emptySet()))
    }

    @Test
    fun `깨어난 정령의 이름을 말한다`() {
        assertEquals("찰칵! 도토리가 일어났어.", HomeSpeech.line(PetState(), two, setOf(SeatSlot.REAR_LEFT)))
    }

    @Test
    fun `모두 깨어나면 다 모였다고 말한다`() {
        assertEquals(FamilySpeech.GATHERED, HomeSpeech.line(PetState(), two, setOf(SeatSlot.REAR_LEFT, SeatSlot.REAR_RIGHT)))
    }

    @Test
    fun `아픈 상태가 가장 먼저다`() {
        val sick = PetState(ailments = setOf("TPMS"))
        assertEquals("발이 아파.\n타이어 공기압을 봐 줘.", HomeSpeech.line(sick, two, setOf(SeatSlot.REAR_LEFT)))
    }

    @Test
    fun `시간대에 맞춰 인사한다`() {
        val pet = PetState(trips = 5, lastTrip = trip)
        assertEquals(HomeSpeech.NIGHT, HomeSpeech.line(pet, FamilyState(), emptySet(), SceneState(DayPart.NIGHT, Sky.CLEAR)))
        assertEquals(HomeSpeech.MORNING, HomeSpeech.line(pet, FamilyState(), emptySet(), SceneState(DayPart.MORNING, Sky.CLEAR)))
    }

    @Test
    fun `밤에는 선물을 권하지 않는다`() {
        val pet = PetState(trips = 5, lastTrip = trip)
        val evening = SceneState(DayPart.EVENING, Sky.CLEAR)
        val night = SceneState(DayPart.NIGHT, Sky.CLEAR)
        assertEquals(HomeSpeech.GIFT, HomeSpeech.line(pet, FamilyState(), emptySet(), evening, giftsWaiting = 1))
        assertEquals(HomeSpeech.GIFT_NIGHT, HomeSpeech.line(pet, FamilyState(), emptySet(), night, giftsWaiting = 1))
    }

    @Test
    fun `날씨 이야기가 인사보다 먼저다`() {
        val pet = PetState(trips = 5, lastTrip = trip)
        assertEquals(HomeSpeech.RAIN, HomeSpeech.line(pet, one, emptySet(), SceneState(DayPart.NIGHT, Sky.RAIN)))
    }

    @Test
    fun `함께한 날은 일주일부터 사흘에 한 번 말한다`() {
        val pet = { days: Int -> PetState(trips = 5, lastTrip = trip, together = TogetherState(days = days)) }
        assertEquals("벌써 9일째 같이 달렸어.", HomeSpeech.line(pet(9), FamilyState(), emptySet()))
        assertEquals(HomeSpeech.DAY, HomeSpeech.line(pet(10), FamilyState(), emptySet()))
        assertEquals(HomeSpeech.DAY, HomeSpeech.line(pet(6), FamilyState(), emptySet()))
        assertEquals(HomeSpeech.NIGHT, HomeSpeech.line(pet(9), FamilyState(), emptySet(), SceneState(DayPart.NIGHT, Sky.CLEAR)))
    }

    @Test
    fun `말풍선 순서는 건강 가족 소프트 필요 정비 날씨 선물 인사다`() {
        val fine = PetState(trips = 5, lastTrip = trip)
        val hungry = fine.copy(ailments = setOf("LOW_FUEL"))
        val rain = SceneState(DayPart.DAY, Sky.RAIN)
        assertEquals("찰칵! 도토리가 일어났어.", HomeSpeech.line(hungry, two, setOf(SeatSlot.REAR_LEFT), rain, 1, CareItem.OIL))
        assertEquals(HomeSpeech.FUEL, HomeSpeech.line(hungry, two, emptySet(), rain, 1, CareItem.OIL))
        assertEquals(HomeSpeech.careLine(CareItem.OIL), HomeSpeech.line(fine, two, emptySet(), rain, 1, CareItem.OIL))
        assertEquals(HomeSpeech.RAIN, HomeSpeech.line(fine, two, emptySet(), rain, 1))
        assertEquals(HomeSpeech.GIFT, HomeSpeech.line(fine, two, emptySet(), SceneState(DayPart.DAY, Sky.CLEAR), 1))
    }

    @Test
    fun `별 하나는 모였다고 말하지 않는다`() {
        assertEquals("첫 별이 생겼어.\n고마워.", HomeSpeech.careBookLine(1, needsCare = false))
        assertEquals("별이 2개나 모였어.\n늘 고마워.", HomeSpeech.careBookLine(2, needsCare = false))
    }

    @Test
    fun `아플 때는 날씨보다 몸 이야기를 한다`() {
        val sick = PetState(ailments = setOf("TPMS"))
        assertEquals("발이 아파.\n타이어 공기압을 봐 줘.", HomeSpeech.line(sick, FamilyState(), emptySet(), SceneState(DayPart.DAY, Sky.RAIN)))
    }

    @Test
    fun `유리병은 레벨마다 비워진다`() {
        assertEquals(10, PetState(feathers = 0).jar.left)
        assertEquals(20, PetState(feathers = 10).jar.left)
        assertEquals(18, PetState(feathers = 12).jar.left)
        assertEquals(2, PetState(feathers = 12).jar.fill)
    }

    @Test
    fun `여정이 없으면 도장이 없다`() {
        assertEquals(HomeSpeech.Stamps(belt = false, softStop = false, blinker = false, rest = false), HomeSpeech.stamps(PetState()))
    }

    @Test
    fun `지난 여정에서 한 일만 도장을 찍는다`() {
        val smooth = trip.copy(smoothStops = 2, restedBefore = true)
        assertEquals(
            HomeSpeech.Stamps(belt = false, softStop = true, blinker = false, rest = true),
            HomeSpeech.stamps(PetState(lastTrip = smooth)),
        )
    }

    @Test
    fun `운전석 안전벨트도 도장이 된다`() {
        val state = PetState()
            .apply(DriveEvent.TripStarted(1, 1))
            .apply(DriveEvent.BeltsReady(1))
            .apply(DriveEvent.TripEnded(2, trip))
        assertEquals(true, HomeSpeech.stamps(state).belt)
    }

    @Test
    fun `새 여정이 시작하면 안전벨트 도장을 다시 찍어야 한다`() {
        val state = PetState()
            .apply(DriveEvent.BeltsReady(1))
            .apply(DriveEvent.TripStarted(2, 2))
            .apply(DriveEvent.TripEnded(3, trip))
        assertEquals(false, HomeSpeech.stamps(state).belt)
    }

    private val blip = trip.copy(tripId = 9, durationMs = 5_000)

    private fun arrived() = PetState()
        .apply(DriveEvent.TripStarted(1, 1))
        .apply(DriveEvent.BeltsReady(1))
        .apply(DriveEvent.SmoothStop(2, 1.5f))
        .apply(DriveEvent.TripEnded(3, trip.copy(smoothStops = 1, driverBelted = true)))

    @Test
    fun `주차 중 짧은 움직임은 지난 일기와 도장을 바꾸지 않는다`() {
        val before = arrived()
        val moved = before.apply(DriveEvent.TripStarted(4, 9)).apply(DriveEvent.TripEnded(5, blip))
        assertEquals(before.lastTrip, moved.lastTrip)
        assertEquals(HomeSpeech.stamps(before), HomeSpeech.stamps(moved))
        assertEquals(before.lastGain, moved.lastGain)
        assertEquals(before.trips + 1, moved.trips)
    }

    @Test
    fun `짧은 움직임에 주운 깃털은 다음 도착에 함께 붓는다`() {
        val moved = arrived()
            .apply(DriveEvent.TripStarted(4, 9))
            .apply(DriveEvent.BeltsReady(4))
            .apply(DriveEvent.TripEnded(5, blip))
        val next = moved
            .apply(DriveEvent.TripStarted(6, 10))
            .apply(DriveEvent.SmoothStop(7, 1.5f))
            .apply(DriveEvent.TripEnded(8, trip.copy(tripId = 10, smoothStops = 1)))
        assertEquals(next.feathers - arrived().feathers, next.lastGain)
    }

    @Test
    fun `첫 움직임이 짧으면 아직 같이 달린 적이 없다`() {
        val moved = PetState().apply(DriveEvent.TripStarted(1, 9)).apply(DriveEvent.TripEnded(2, blip))
        assertEquals(null, moved.lastTrip)
        assertEquals(HomeSpeech.stamps(PetState()), HomeSpeech.stamps(moved))
    }
}
