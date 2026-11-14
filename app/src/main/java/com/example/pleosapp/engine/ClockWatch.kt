package com.example.pleosapp.engine

import kotlin.math.abs

/**
 * 벽시계가 옮겨졌는지 봅니다. 부팅 뒤 경과 시간은 옮겨지지 않으니 둘의 차이가 바뀌면 시계가 옮겨진 것입니다.
 * 호출자가 한 스레드에서 불러야 합니다.
 */
class ClockWatch(private val toleranceMs: Long = 2_000) {
    private var offset: Long? = null

    /** 지난번 뒤로 시계가 옮겨진 크기. 없으면 0입니다. */
    fun moved(wallMs: Long, elapsedMs: Long): Long {
        val now = wallMs - elapsedMs
        val last = offset ?: now
        val delta = now - last
        if (offset != null && abs(delta) < toleranceMs) return 0
        offset = now
        return delta
    }
}
