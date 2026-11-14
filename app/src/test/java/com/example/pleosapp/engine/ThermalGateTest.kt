package com.example.pleosapp.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalGateTest {

    @After
    fun `열 상태를 풀어 둔다`() = ThermalGate.update(0)

    @Test
    fun `심각 단계부터 움직임을 세 배 늦춘다`() {
        ThermalGate.update(2)
        assertEquals(1, ThermalGate.slowdown)
        ThermalGate.update(3)
        assertEquals(3, ThermalGate.slowdown)
        ThermalGate.update(6)
        assertEquals(3, ThermalGate.slowdown)
    }

    @Test
    fun `식으면 원래 빠르기로 돌아온다`() {
        ThermalGate.update(3)
        ThermalGate.update(0)
        assertEquals(1, ThermalGate.slowdown)
    }
}
