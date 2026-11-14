package com.example.pleosapp.engine

enum class TripSound { CHIME, FEATHER }

/**
 * 달리는 동안 하나뿐인 반응. 출발과 부드러운 정지에만 소리를 내고, 급제동과 놓친 깜빡이에는 아무 소리도 내지 않습니다.
 * 이 클래스는 낼지만 정합니다. [play]는 실제로 재생했는지 돌려주고, 포커스를 못 받아 건너뛴 소리는 횟수에 넣지 않습니다.
 */
class TripSounds(
    private val play: (TripSound) -> Boolean,
    private val log: (String) -> Unit = {},
) {
    private var feathers = 0
    private var lastFeatherAt: Long? = null

    /** [rearBelted]이면 뒷자리에 자는 아이가 있을 수 있어 깃털 소리를 내지 않습니다. */
    fun onEvent(event: DriveEvent, rearBelted: Boolean) {
        when (event) {
            is DriveEvent.TripStarted -> {
                feathers = 0
                lastFeatherAt = null
                sound(TripSound.CHIME)
            }
            is DriveEvent.SmoothStop -> feather(event.atMs, rearBelted)
            else -> Unit
        }
    }

    private fun feather(atMs: Long, rearBelted: Boolean) {
        val last = lastFeatherAt
        when {
            rearBelted -> log("SOUND skip FEATHER rear belted")
            feathers >= MAX_FEATHERS -> log("SOUND skip FEATHER trip limit")
            last != null && atMs - last < MIN_FEATHER_GAP_MS -> log("SOUND skip FEATHER gap")
            sound(TripSound.FEATHER) -> {
                feathers++
                lastFeatherAt = atMs
            }
        }
    }

    private fun sound(sound: TripSound): Boolean {
        val played = play(sound)
        log(if (played) "SOUND play $sound" else "SOUND skip $sound no focus")
        return played
    }

    private companion object {
        const val MAX_FEATHERS = 3
        const val MIN_FEATHER_GAP_MS = 60_000L
    }
}
