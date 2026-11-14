package com.example.pleosapp.spike

import com.example.pleosapp.engine.Mood
import com.example.pleosapp.engine.PetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Gleo가 읽는 답. 한국어 한 문장이고 'pet'이나 숫자가 없습니다. */
class GleoAnswerTest {

    private val answers = Mood.entries.map { GleoActionReceiver.moodAnswer(it) } + listOf(
        GleoActionReceiver.healthAnswer(PetState()),
        GleoActionReceiver.healthAnswer(PetState(ailments = setOf("TPMS"))),
        GleoActionReceiver.healthAnswer(PetState(ailments = setOf("LOW_FUEL"))),
        GleoActionReceiver.healthAnswer(PetState(ailments = setOf("UNKNOWN_LAMP"))),
    )

    @Test
    fun `답은 숫자와 영어 없이 한 문장이다`() {
        answers.forEach { answer ->
            assertFalse(answer, answer.any { it.isDigit() || it in 'a'..'z' || it in 'A'..'Z' })
            assertEquals(answer, 1, answer.count { it == '.' || it == '?' || it == '!' })
            assertEquals(answer, '.', answer.last())
        }
    }

    @Test
    fun `켜진 경고등은 구름이 말의 할 일로 답한다`() {
        assertEquals("지금 켜진 경고등은 없어.", GleoActionReceiver.healthAnswer(PetState()))
        assertEquals("켜진 경고등이 있어, 타이어 공기압을 봐 줘.", GleoActionReceiver.healthAnswer(PetState(ailments = setOf("TPMS"))))
    }
}
