package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 달리는 동안의 소리 규칙(운전자 중심 보강 명세 2장). */
class TripSoundsTest {

    private class Rig(var granted: Boolean = true) {
        val played = mutableListOf<TripSound>()
        val logs = mutableListOf<String>()
        val sounds = TripSounds({ sound -> granted.also { if (it) played += sound } }, { logs += it })

        fun start(atMs: Long = 0, rearBelted: Boolean = false) = sounds.onEvent(DriveEvent.TripStarted(atMs, atMs), rearBelted)

        fun stop(atMs: Long, rearBelted: Boolean = false) = sounds.onEvent(DriveEvent.SmoothStop(atMs, 1f), rearBelted)
    }

    private val minute = 60_000L

    @Test
    fun `트립마다 출발 소리를 한 번 낸다`() {
        val rig = Rig()
        rig.start(0)
        rig.start(10 * minute)
        assertEquals(listOf(TripSound.CHIME, TripSound.CHIME), rig.played)
    }

    @Test
    fun `출발 소리는 뒷자리 안전벨트와 상관없다`() {
        val rig = Rig()
        rig.start(rearBelted = true)
        assertEquals(listOf(TripSound.CHIME), rig.played)
    }

    @Test
    fun `부드러운 정지 소리는 트립당 세 번까지다`() {
        val rig = Rig()
        rig.start()
        (1L..5L).forEach { rig.stop(it * minute) }
        assertEquals(3, rig.played.count { it == TripSound.FEATHER })
        assertTrue(rig.logs.last(), "limit" in rig.logs.last())
    }

    @Test
    fun `새 트립은 횟수와 간격을 다시 센다`() {
        val rig = Rig()
        rig.start()
        (1L..3L).forEach { rig.stop(it * minute) }
        rig.start(3 * minute + 1)
        rig.stop(3 * minute + 2)
        assertEquals(4, rig.played.count { it == TripSound.FEATHER })
    }

    @Test
    fun `정지 소리는 60초 안에 다시 나지 않는다`() {
        val rig = Rig()
        rig.start()
        rig.stop(minute)
        rig.stop(2 * minute - 1)
        rig.stop(2 * minute)
        assertEquals(2, rig.played.count { it == TripSound.FEATHER })
        assertTrue(rig.logs.any { "gap" in it })
    }

    @Test
    fun `뒷자리 안전벨트가 채워져 있으면 정지 소리를 내지 않고 횟수에도 넣지 않는다`() {
        val rig = Rig()
        rig.start()
        repeat(4) { rig.stop(minute + it, rearBelted = true) }
        assertEquals(listOf(TripSound.CHIME), rig.played)
        assertTrue(rig.logs.last(), "rear belted" in rig.logs.last())
        rig.stop(minute + 10)
        assertEquals(TripSound.FEATHER, rig.played.last())
    }

    @Test
    fun `포커스를 못 받으면 건너뛰고 횟수에 넣지 않는다`() {
        val rig = Rig(granted = false)
        rig.start()
        rig.stop(minute)
        assertTrue(rig.played.isEmpty())
        assertEquals(listOf("SOUND skip CHIME no focus", "SOUND skip FEATHER no focus"), rig.logs)
        rig.granted = true
        rig.stop(minute + 1)
        assertEquals(listOf(TripSound.FEATHER), rig.played)
    }

    @Test
    fun `급제동과 놓친 깜빡이에는 소리가 없다`() {
        val rig = Rig()
        rig.sounds.onEvent(DriveEvent.HardBrake(1, 4f, 10f), false)
        rig.sounds.onEvent(DriveEvent.SignalMissed(2, TurnSide.LEFT), false)
        rig.sounds.onEvent(DriveEvent.SignalUsed(3, TurnSide.LEFT, 500), false)
        rig.sounds.onEvent(DriveEvent.BeltsMissing(4), false)
        assertTrue(rig.played.isEmpty())
    }

    @Test
    fun `재생과 건너뜀을 모두 기록한다`() {
        val rig = Rig()
        rig.start()
        rig.stop(minute)
        rig.stop(minute + 1)
        assertEquals(listOf("SOUND play CHIME", "SOUND play FEATHER", "SOUND skip FEATHER gap"), rig.logs)
    }
}
