package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 회복 보상 규칙: 10분 이상 지속된 경고등이 꺼질 때만, 코드당 하루 한 번. 경고등 깜빡임으로는 깃털이 늘지 않습니다. */
class PetStateTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    private fun PetState.alert(code: String, at: Long) = apply(DriveEvent.HealthAlert(at, code))
    private fun PetState.recover(code: String, at: Long) = apply(DriveEvent.HealthRecovered(at, code))

    @Test
    fun `경고등을 짧게 껐다 켜도 깃털은 늘지 않는다`() {
        var s = PetState()
        var t = 0L
        repeat(10) {
            s = s.alert("TPMS", t)
            t += 30_000L
            s = s.recover("TPMS", t)
            t += 30_000L
        }
        assertEquals(0, s.feathers)
        assertFalse(s.ailments.contains("TPMS"))
    }

    @Test
    fun `충분히 지속된 경고등이 꺼지면 회복 보상을 받고 상태가 낫는다`() {
        val s = PetState().alert("TPMS", 0L).recover("TPMS", 15 * minute)
        assertEquals(5, s.feathers)
        assertTrue(s.ailments.isEmpty())
        assertEquals(Mood.HAPPY, s.mood)
    }

    @Test
    fun `같은 코드는 하루 한 번만 보상한다`() {
        var s = PetState().alert("TPMS", 0L).recover("TPMS", 15 * minute)
        s = s.alert("TPMS", 20 * minute).recover("TPMS", 40 * minute)
        assertEquals(5, s.feathers)
        s = s.alert("TPMS", 2 * hour).recover("TPMS", 26 * hour)
        assertEquals(10, s.feathers)
    }

    @Test
    fun `보상 여부와 무관하게 회복 이벤트는 항상 질환을 지운다`() {
        val s = PetState().alert("ABS", 0L).alert("TPMS", 0L).recover("ABS", 1_000L).recover("TPMS", 2_000L)
        assertTrue(s.ailments.isEmpty())
        assertEquals(0, s.feathers)
    }
}
