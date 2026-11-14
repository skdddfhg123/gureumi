package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** 도착 일기를 언제 읽을지. 서비스와 카드가 같은 규칙을 씁니다. */
class ArrivalAloudTest {

    @Test
    fun `뒷벨트가 풀린 채 도착하면 밤에도 바로 읽는다`() {
        DayPart.entries.forEach { assertEquals(it.name, ArrivalAloud.Start.NOW, ArrivalAloud.start(rearStillBelted = false, part = it)) }
    }

    @Test
    fun `밤이 아닐 때 뒷벨트를 맨 채 도착하면 풀릴 때까지 기다린다`() {
        listOf(DayPart.MORNING, DayPart.DAY, DayPart.EVENING).forEach {
            assertEquals(it.name, ArrivalAloud.Start.AFTER_REAR, ArrivalAloud.start(rearStillBelted = true, part = it))
        }
    }

    @Test
    fun `밤에 뒷벨트를 맨 채 도착하면 풀려도 읽지 않는다`() {
        assertEquals(ArrivalAloud.Start.NEVER, ArrivalAloud.start(rearStillBelted = true, part = DayPart.NIGHT))
    }

    /** 밤 10시 학원 귀가와 새벽 귀가는 어느 달이든 밤입니다. */
    @Test
    fun `밤 10시와 새벽 5시는 달마다 밤이다`() {
        (1..12).forEach { month ->
            assertEquals("22시 ${month}월", DayPart.NIGHT, SceneRules.dayPart(22, month))
            assertEquals("5시 ${month}월", DayPart.NIGHT, SceneRules.dayPart(5, month))
        }
    }
}
