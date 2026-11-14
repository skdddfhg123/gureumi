package com.example.pleosapp.engine

import java.util.concurrent.CopyOnWriteArrayList

enum class DayPart { MORNING, DAY, EVENING, NIGHT }

enum class Sky { CLEAR, RAIN, SNOW, FOG }

data class SceneState(val part: DayPart, val sky: Sky)

/**
 * 창밖 장면을 정하는 규칙. 서버나 위치 없이 시계와 차가 느끼는 신호만 봅니다.
 * 와이퍼는 비, 안개등은 안개, 빙판 경고는 눈입니다.
 */
object SceneRules {
    /** 달마다 해 지는 시각. 서울 기준이고 시 단위로 반올림했습니다. */
    private val SUNSET_HOUR = intArrayOf(18, 18, 19, 19, 20, 20, 20, 20, 19, 18, 17, 17)

    /** 밤사이 흐린 날 뒤에도 비가 그친 줄 모르면 안 되므로 와이퍼가 멈춘 뒤 잠시만 비로 봅니다. */
    const val RAIN_HOLD_MS = 30 * 60_000L

    private const val ICY_ROAD = "ICY_ROAD"
    private val FOG_LAMPS = setOf("FRONT_FOG_LAMP", "REAR_FOG_LAMP")

    fun dayPart(hour: Int, month: Int): DayPart {
        val sunset = SUNSET_HOUR[(month - 1).coerceIn(0, 11)]
        return when {
            hour < MORNING_START -> DayPart.NIGHT
            hour < MORNING_END -> DayPart.MORNING
            hour < sunset - 1 -> DayPart.DAY
            hour <= sunset -> DayPart.EVENING
            else -> DayPart.NIGHT
        }
    }

    fun sky(wiperRunning: Boolean, wiperStoppedAtMs: Long?, nowMs: Long, telltales: Set<String>): Sky {
        val raining = wiperRunning || (wiperStoppedAtMs != null && nowMs - wiperStoppedAtMs < RAIN_HOLD_MS)
        return when {
            ICY_ROAD in telltales -> Sky.SNOW
            raining -> Sky.RAIN
            telltales.any { it in FOG_LAMPS } -> Sky.FOG
            else -> Sky.CLEAR
        }
    }

    private const val MORNING_START = 6
    private const val MORNING_END = 10
}

/** 브리지가 넣어 주는 날씨 신호. 경고등과 와이퍼는 각자 다른 스레드에서 옵니다. */
object SkySignals {
    @Volatile private var wiperRunning = false
    @Volatile private var wiperStoppedAtMs: Long? = null
    @Volatile private var telltales: Set<String> = emptySet()
    @Volatile private var previewPart: DayPart? = null
    @Volatile private var previewSky: Sky? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun onWiper(running: Boolean, atMs: Long) {
        if (wiperRunning == running) return
        if (!running) wiperStoppedAtMs = atMs
        wiperRunning = running
        listeners.forEach { it() }
    }

    fun onTelltales(active: Set<String>) {
        if (telltales == active) return
        telltales = active
        listeners.forEach { it() }
    }

    fun scene(part: DayPart, nowMs: Long): SceneState = SceneState(
        previewPart ?: part,
        previewSky ?: SceneRules.sky(wiperRunning, wiperStoppedAtMs, nowMs, telltales),
    )

    /** debug 빌드에서 장면을 미리 볼 때만 씁니다. */
    fun setPreview(part: DayPart?, sky: Sky?) {
        previewPart = part
        previewSky = sky
        listeners.forEach { it() }
    }
}
