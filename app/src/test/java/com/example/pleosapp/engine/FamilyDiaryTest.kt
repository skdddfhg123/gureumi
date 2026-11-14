package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 가족 고마움과 대사(M3 명세 3-2, 3-3). */
class FamilyDiaryTest {

    private val family = FamilyState()
        .adopt(SpiritPreset.SOM, SeatSlot.REAR_LEFT)
        .adopt(SpiritPreset.SAETEOL, SeatSlot.REAR_RIGHT)
        .adopt(SpiritPreset.YANGTTE, SeatSlot.REAR_CENTER)

    /** 맡긴 말은 5분 넘게 달린 트립에만 전하므로 10분 트립입니다. */
    private fun trip(seats: Set<SeatSlot> = emptySet(), tapped: Set<SeatSlot> = emptySet(), rearBelts: Int = 0) =
        TripSummary(7, 600_000, 1_000f, 1, 0, 0, 0, rearBelts = rearBelts, seats = seats, tappedSeats = tapped)

    private fun second(t: TripSummary, f: FamilyState = family, state: PetState = PetState()) = DiaryTemplate.lines(t, state, f)[1]

    @Test
    fun `정령 하나면 이름을 넣어 고마워한다`() {
        assertEquals("도토리가 벨트를 먼저 챙겨 줬어, 고마워.", second(trip(setOf(SeatSlot.REAR_LEFT))))
    }

    @Test
    fun `정령 둘이면 이어 준다`() {
        assertEquals(
            "도토리랑 새싹이 벨트를 먼저 챙겨 줬어, 고마워.",
            second(trip(setOf(SeatSlot.REAR_LEFT, SeatSlot.REAR_RIGHT))),
        )
    }

    @Test
    fun `정령 셋이면 모두가로 줄인다`() {
        val all = setOf(SeatSlot.REAR_LEFT, SeatSlot.REAR_RIGHT, SeatSlot.REAR_CENTER)
        assertEquals("모두가 벨트를 먼저 챙겨 줬어, 고마워.", second(trip(all)))
    }

    @Test
    fun `안 탄 정령은 말하지 않는다`() {
        assertFalse(second(trip(setOf(SeatSlot.REAR_LEFT))).contains("새싹"))
    }

    @Test
    fun `화면에서 깨운 정령에게는 고마움 문장을 쓰지 않는다`() {
        val line = second(trip(tapped = setOf(SeatSlot.REAR_LEFT)))
        assertFalse(line.contains("도토리"))
        assertFalse(line.contains("벨트"))
    }

    @Test
    fun `정령이 없으면 지금 문장을 그대로 쓴다`() {
        assertEquals(
            "친구가 벨트를 먼저 챙겨 줬어, 고마워.",
            second(trip(setOf(SeatSlot.REAR_LEFT), rearBelts = 1), FamilyState()),
        )
    }

    @Test
    fun `자리에 정령이 없으면 손님에게 지금 문장을 쓴다`() {
        val guest = trip(setOf(SeatSlot.FRONT_RIGHT), rearBelts = 1)
        assertEquals("친구가 벨트를 먼저 챙겨 줬어, 고마워.", second(guest))
    }

    @Test
    fun `엔진 문장에는 자리 이름이 없다`() {
        val lines = listOf(FamilySpeech.ASK_DEFAULT, FamilySpeech.BESIDE, FamilySpeech.welcome("라일락"), FamilySpeech.welcome("라일락", SeatSlot.REAR_LEFT)) +
            SeatSlot.entries.map { second(trip(setOf(it), rearBelts = 1), FamilyState()) }
        lines.forEach { line -> listOf("뒷자리", "조수석", "옆자리").forEach { assertFalse(line, line.contains(it)) } }
    }

    @Test
    fun `전달된 말은 둘째 줄에 나온다`() {
        val delivered = family.hold(SpiritPreset.SOM, 1).settleArrival(trip(), ailing = false)
        assertEquals("도토리가 전해 달래. 이따 봐!", second(trip(), delivered))
    }

    @Test
    fun `전달된 말이 있어도 다른 트립 일기에는 나오지 않는다`() {
        val delivered = family.hold(SpiritPreset.SOM, 1).settleArrival(trip(), ailing = false)
        assertFalse(second(trip().copy(tripId = 8), delivered).contains("전해 달래"))
    }

    @Test
    fun `건강 안내가 전달된 말보다 먼저다`() {
        val delivered = family.hold(SpiritPreset.SOM, 1).settleArrival(trip(), ailing = false)
        val sick = PetState(ailments = setOf("TPMS"))
        assertEquals(sick.ailmentLine(), second(trip(), delivered, sick))
    }

    @Test
    fun `받침에 맞춰 조사를 고른다`() {
        assertEquals("라일락이랑", Josa.and("라일락"))
        assertEquals("도토리랑", Josa.and("도토리"))
        assertEquals("라일락이", Josa.subject("라일락"))
        assertEquals("도토리가", Josa.subject("도토리"))
    }

    @Test
    fun `대사를 자리와 이름으로 만든다`() {
        assertEquals("찰칵! 도토리가 일어났어.", FamilySpeech.woke(listOf("도토리")))
        assertEquals("찰칵찰칵! 도토리랑 새싹이 일어났어.", FamilySpeech.woke(listOf("도토리", "새싹")))
        assertEquals("도토리가 깨어나게 벨트 찰칵해 줄래?", FamilySpeech.askBelt(listOf("도토리")))
        assertEquals("도토리랑 개나리가 깨어나게 벨트 찰칵해 줄래?", FamilySpeech.askBelt(listOf("도토리", "개나리")))
        assertEquals("반가워! 라일락이 우리 차에 왔어.", FamilySpeech.welcome("라일락"))
        assertEquals("잘 다녀와. 새싹이 자리를 지키고 있을 거야.", FamilySpeech.sendOff("새싹"))
        assertEquals("왔구나! 도토리가 반가워해.", FamilySpeech.welcomeBack("도토리"))
        assertEquals("왔구나! 도토리랑 새싹이 반가워해.", FamilySpeech.welcomeBack(listOf("도토리", "새싹")))
        assertEquals("개나리가 도토리를 주워 왔어.", FamilySpeech.brought("개나리", "도토리"))
    }

    @Test
    fun `대사에는 이모지가 없다`() {
        val lines = listOf(FamilySpeech.GATHERED, FamilySpeech.woke(listOf("도토리")), FamilySpeech.thanks(listOf("도토리")))
        assertTrue(lines.all { line -> line.all { it.code < 0x2600 || it in '가'..'힣' } })
    }
}
