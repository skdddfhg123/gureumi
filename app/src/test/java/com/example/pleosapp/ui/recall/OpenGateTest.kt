package com.example.pleosapp.ui.recall

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 그림책과 엽서를 여는 손짓을 한 번만 받는지 봅니다. */
class OpenGateTest {

    private var now = 10_000L
    private val gate = OpenGate(clock = { now })

    @Test
    fun `빠른 두 번째 손짓은 열지 않는다`() {
        assertTrue(gate.pass())
        now += 30
        assertFalse(gate.pass())
        now += 75
        assertFalse(gate.pass())
    }

    @Test
    fun `잠깐 지나면 다시 연다`() {
        assertTrue(gate.pass())
        now += OpenGate.HOLD_MS - 1
        assertFalse(gate.pass())
        now += 1
        assertTrue(gate.pass())
    }

    @Test
    fun `덮고 돌아오면 바로 다시 연다`() {
        assertTrue(gate.pass())
        now += 200
        gate.reset()
        assertTrue(gate.pass())
        assertFalse(gate.pass())
    }

    @Test
    fun `막힌 손짓은 막는 시간을 늘리지 않는다`() {
        assertTrue(gate.pass())
        now += 1_000
        assertFalse(gate.pass())
        now += OpenGate.HOLD_MS - 1_000
        assertTrue(gate.pass())
    }
}
