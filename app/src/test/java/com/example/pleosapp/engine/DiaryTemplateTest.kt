package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiaryTemplateTest {

    private fun trip(
        durationMs: Long = 25 * 60_000L,
        distanceM: Float = 12_300f,
        smoothStops: Int = 12,
        hardBrakes: Int = 0,
        signalsUsed: Int = 6,
        signalsMissed: Int = 0,
    ) = TripSummary(1L, durationMs, distanceM, smoothStops, hardBrakes, signalsUsed, signalsMissed)

    @Test
    fun `항상 두 줄이고 문장당 느낌표는 하나 이하`() {
        val lines = DiaryTemplate.lines(trip(hardBrakes = 2, signalsMissed = 1), PetState())
        assertEquals(2, lines.size)
        lines.forEach { assertTrue(it, it.count { c -> c == '!' } <= 1) }
    }

    @Test
    fun `첫 줄은 거리를 말하지 않고 시간만 말한다`() {
        assertEquals("오늘은 25분쯤 같이 달렸어.", DiaryTemplate.lines(trip(), PetState())[0])
        assertEquals("오늘은 25분쯤 같이 달렸어.", DiaryTemplate.lines(trip(distanceM = 0f), PetState())[0])
    }

    @Test
    fun `한 시간이 넘으면 시간으로 말한다`() {
        fun first(minutes: Long) = DiaryTemplate.lines(trip(durationMs = minutes * 60_000L), PetState())[0]
        assertEquals("오늘은 59분쯤 같이 달렸어.", first(59))
        assertEquals("오늘은 1시간쯤 같이 달렸어.", first(60))
        assertEquals("오늘은 1시간 반쯤 같이 달렸어.", first(100))
        assertEquals("오늘은 2시간쯤 같이 달렸어.", first(125))
    }

    @Test
    fun `짧은 트립도 최소 1분으로 말한다`() {
        assertTrue(DiaryTemplate.lines(trip(durationMs = 12_000L, distanceM = 0f), PetState())[0].contains("1분"))
    }

    @Test
    fun `둘째 줄은 더 많이 모은 행동을 고른다`() {
        assertTrue(DiaryTemplate.lines(trip(smoothStops = 12, signalsUsed = 6), PetState())[1].contains("12번"))
        assertTrue(DiaryTemplate.lines(trip(smoothStops = 2, signalsUsed = 9), PetState())[1].contains("깜빡이"))
    }

    /** "깜빡이를 1번이나"는 뜻이 어긋납니다. 한두 번은 숫자 없이 말합니다. */
    @Test
    fun `한두 번은 숫자 없이 칭찬한다`() {
        fun second(smooth: Int, signals: Int) = DiaryTemplate.lines(trip(smoothStops = smooth, signalsUsed = signals), PetState())[1]
        assertEquals("부드럽게 멈춰 줘서 깃털을 모았어.", second(2, 1))
        assertEquals("깜빡이를 미리 켜 줘서 깃털을 모았어.", second(0, 1))
        assertEquals("부드럽게 멈춘 게 3번! 깃털을 그만큼 모았어.", second(3, 0))
        assertEquals("깜빡이를 3번이나 미리 켜 줬어! 깃털을 그만큼 모았어.", second(0, 3))
    }

    @Test
    fun `둘째 줄 우선순위는 건강 › 칭찬 › 급제동 › 놓친 깜빡이`() {
        val sick = PetState(ailments = setOf("TPMS"))
        assertTrue(DiaryTemplate.lines(trip(hardBrakes = 3, signalsMissed = 2), sick)[1].contains("타이어"))
        assertTrue(DiaryTemplate.lines(trip(hardBrakes = 3, signalsMissed = 2), PetState())[1].contains("12번"))
        val plain = trip(smoothStops = 0, signalsUsed = 0)
        assertEquals("아까 조금 출렁였어, 다음엔 천천히 멈춰 줘.", DiaryTemplate.lines(plain.copy(hardBrakes = 1, signalsMissed = 2), PetState())[1])
        assertEquals("깜빡이를 먼저 켜 주면 깃털을 더 모을 수 있어.", DiaryTemplate.lines(plain.copy(signalsMissed = 2), PetState())[1])
    }

    @Test
    fun `둘째 줄은 뒷자리 벨트와 휴식 고마움을 운전 칭찬보다 먼저 말한다`() {
        val belted = trip().copy(rearBelts = 1, restedBefore = true)
        // 자리 이름은 말하지 않습니다.
        assertEquals("친구가 벨트를 먼저 챙겨 줬어, 고마워.", DiaryTemplate.lines(belted, PetState())[1])
        val rested = trip().copy(restedBefore = true)
        assertTrue(DiaryTemplate.lines(rested, PetState())[1].contains("바깥 공기"))
    }

    @Test
    fun `마무리는 깃털을 모았을 때만 자란다고 말한다`() {
        val plain = trip(smoothStops = 0, signalsUsed = 0)
        assertTrue(DiaryTemplate.lines(plain, PetState(lastGain = 3))[1].contains("자랄"))
        val none = DiaryTemplate.lines(plain, PetState())[1]
        assertTrue(none, none.contains("같이 달린"))
    }

    @Test
    fun `사다리를 다 채운 뒤에는 자란다고 말하지 않는다`() {
        val plain = trip(smoothStops = 0, signalsUsed = 0)
        val done = PetState(feathers = FULL_LADDER, lastGain = 3, together = TogetherState(days = 365))
        assertEquals("벌써 365일을 함께했어, 고마워.", DiaryTemplate.lines(plain, done)[1])
    }

    @Test
    fun `다 채우기 한 칸 전에는 아직 자란다고 말한다`() {
        val plain = trip(smoothStops = 0, signalsUsed = 0)
        val almost = PetState(feathers = FULL_LADDER - 1, lastGain = 3, together = TogetherState(days = 365))
        assertTrue(DiaryTemplate.lines(plain, almost)[1].contains("자랄"))
    }

    @Test
    fun `TTS 문단은 두 줄을 공백으로 잇는다`() {
        val state = PetState()
        assertEquals(DiaryTemplate.lines(trip(), state).joinToString(" "), DiaryTemplate.speech(trip(), state))
    }

    private companion object {
        /** 17칸을 모두 채우는 깃털. 10, 20, 30, 40, 50에 60이 12번입니다. */
        const val FULL_LADDER = 870
    }
}
