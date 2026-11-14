package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneRulesTest {

    @Test
    fun `하루가 네 장면으로 나뉜다`() {
        assertEquals(DayPart.NIGHT, SceneRules.dayPart(5, 10))
        assertEquals(DayPart.MORNING, SceneRules.dayPart(6, 10))
        assertEquals(DayPart.DAY, SceneRules.dayPart(12, 10))
        assertEquals(DayPart.EVENING, SceneRules.dayPart(17, 10))
        assertEquals(DayPart.NIGHT, SceneRules.dayPart(20, 10))
    }

    @Test
    fun `겨울에는 해가 일찍 진다`() {
        assertEquals(DayPart.NIGHT, SceneRules.dayPart(18, 12))
        assertEquals(DayPart.DAY, SceneRules.dayPart(18, 7))
        assertEquals(DayPart.EVENING, SceneRules.dayPart(19, 7))
    }

    @Test
    fun `신호가 없으면 맑다`() {
        assertEquals(Sky.CLEAR, SceneRules.sky(false, null, 1_000L, emptySet()))
    }

    @Test
    fun `와이퍼가 돌면 비다`() {
        assertEquals(Sky.RAIN, SceneRules.sky(true, null, 1_000L, emptySet()))
    }

    @Test
    fun `와이퍼가 멈춘 뒤 잠시는 비로 본다`() {
        val stopped = 1_000L
        assertEquals(Sky.RAIN, SceneRules.sky(false, stopped, stopped + SceneRules.RAIN_HOLD_MS - 1, emptySet()))
        assertEquals(Sky.CLEAR, SceneRules.sky(false, stopped, stopped + SceneRules.RAIN_HOLD_MS, emptySet()))
    }

    @Test
    fun `안개등이 켜지면 안개다`() {
        assertEquals(Sky.FOG, SceneRules.sky(false, null, 1_000L, setOf("REAR_FOG_LAMP")))
    }

    @Test
    fun `빙판 경고가 가장 먼저다`() {
        assertEquals(Sky.SNOW, SceneRules.sky(true, null, 1_000L, setOf("ICY_ROAD", "FRONT_FOG_LAMP")))
    }
}
