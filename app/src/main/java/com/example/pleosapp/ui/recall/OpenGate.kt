package com.example.pleosapp.ui.recall

import android.os.SystemClock

/**
 * 화면을 여는 손짓을 한 번만 받는 막이입니다. 홈 입구의 막이와 같은 꼴입니다.
 * 빠르게 두 번 누르면 새 화면이 덮기 전에 두 번째 손짓이 들어와 그림책이 두 번 열렸습니다.
 * 한 번 열면 [holdMs] 동안 다시 열지 않습니다. 연 화면을 덮고 돌아오면 [reset]으로 바로 풉니다.
 */
internal class OpenGate(
    private val holdMs: Long = HOLD_MS,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    private var openedAt: Long? = null

    /** 지금 열어도 되면 true이고, 그때부터 막습니다. */
    fun pass(): Boolean {
        val now = clock()
        val last = openedAt
        if (last != null && now - last < holdMs) return false
        openedAt = now
        return true
    }

    fun reset() {
        openedAt = null
    }

    companion object {
        /** 홈 입구와 같은 길이입니다. 새 화면이 뜨는 데 0.1초 안팎이 걸립니다. */
        const val HOLD_MS = 1_200L
    }
}
