package com.example.pleosapp.ui.game

import android.view.Choreographer
import android.view.View
import com.example.pleosapp.engine.ThermalGate

/**
 * 화면의 움직임을 한 박자로 묶습니다. 화면이 보일 때만 돌고, 가려지면 멈춥니다.
 * 뷰마다 타이머를 두지 않아야 같은 시각을 보고 함께 움직입니다.
 * 큰 화면을 매번 그리면 다른 앱까지 느려지므로 뷰마다 필요한 빈도만큼만 다시 그립니다.
 * 차가 뜨거우면 [ThermalGate.slowdown]배 느리게 돕니다.
 */
class MotionTicker(fps: Int = DEFAULT_FPS, idleFps: Int = fps) : Choreographer.FrameCallback {

    private class Group(val periodMs: () -> Long, val views: List<View>) {
        var lastMs = 0L
    }

    private val frameMs = 1000L / fps
    private val idleMs = 1000L / idleFps
    private val views = ArrayList<View>()
    private val smooth = ArrayList<View>()
    private val groups = ArrayList<Group>()
    private val tasks = ArrayList<(Float) -> Unit>()
    private var running = false
    private var lastMs = 0L
    private var smoothLastMs = 0L

    /** 몸짓이 있는 동안 true입니다. 숨만 쉴 때는 느린 박자로 돌아도 눈에 띄지 않습니다. */
    var busy: () -> Boolean = { true }

    /**
     * 뛰기, 갸웃, 하트처럼 한 장 사이에 크게 움직이는 1~2초 동안 true입니다.
     * 그동안만 [addSmooth]한 정령을 [smoothFps]로 다시 그립니다. 평소에는 [busy] 박자를 따릅니다.
     */
    var lively: () -> Boolean = { false }

    fun add(vararg targets: View) {
        views += targets
    }

    /**
     * 정령 뷰. 평소에는 [add]한 뷰와 같은 박자이고, [lively]인 동안만 [smoothFps]로 다시 그립니다.
     * 늘 30장으로 그리면 홈 CPU가 두 배가 되어 큰 몸짓에만 씁니다.
     * 그동안은 평소 박자도 정령을 그리는 장에만 얹어, 1초에 그리는 장 수가 [smoothFps]를 넘지 않습니다.
     */
    fun addSmooth(vararg targets: View) {
        smooth += targets
    }

    /** 하늘처럼 느린 것은 [periodMs]마다 한 번만 그립니다. 간격은 장면에 따라 바뀔 수 있습니다. */
    fun addEvery(periodMs: () -> Long, vararg targets: View) {
        groups += Group(periodMs, targets.toList())
    }

    /** 뷰를 다시 그리지 않고 속성만 바꾸는 움직임을 더합니다. 초 단위 시각을 받습니다. */
    fun onTick(task: (Float) -> Unit) {
        tasks += task
    }

    fun start() {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val now = frameTimeNanos / 1_000_000L
        val gap = (if (busy()) frameMs else idleMs) * ThermalGate.slowdown
        val smoothGap = if (smoothFps > 0 && smooth.isNotEmpty() && lively()) 1000L / smoothFps * ThermalGate.slowdown else 0L
        val glide = smoothGap > 0 && now - smoothLastMs >= smoothGap - SLOP_MS
        // 큰 몸짓 동안 두 박자가 따로 돌면 사이사이에 장이 더 생깁니다. 20장과 15장이면 1초 30장입니다.
        // 그래서 그동안은 평소 박자를 정령을 그리는 장에만 얹고, 반 장 안으로 다가온 박자는 이번 장에 그립니다.
        val beat = if (smoothGap > 0) glide && now - lastMs >= gap - smoothGap / 2 else now - lastMs >= gap - SLOP_MS
        if (beat || glide) ticks++
        if (beat) {
            lastMs = now
            val seconds = seconds(now)
            tasks.forEach { it(seconds) }
            views.forEach { it.invalidate() }
            if (smoothGap == 0L) smooth.forEach { it.invalidate() }
            groups.forEach { group ->
                if (now - group.lastMs >= group.periodMs() - SLOP_MS) {
                    group.lastMs = now
                    group.views.forEach { it.invalidate() }
                }
            }
        }
        if (glide) {
            smoothLastMs = now
            smooth.forEach { it.invalidate() }
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    companion object {
        private const val DEFAULT_FPS = 15
        private const val SLOP_MS = 3L
        private const val WRAP_MS = 3_600_000L

        /**
         * 큰 몸짓 동안 정령을 다시 그리는 빈도. 0이면 예전처럼 몸짓도 15입니다. 홈 화면 인텐트 `smooth_fps`로 바꿉니다.
         * 30장은 아기 넷 낮 홈에서 평균이 예산 10.5장을 넘어 20장으로 낮췄습니다.
         */
        const val SMOOTH_FPS = 20

        @Volatile
        var smoothFps = SMOOTH_FPS

        /** 지금까지 그리라고 요청한 횟수. 하드웨어와 무관해 검증 도구가 읽습니다. */
        var ticks = 0L
            private set

        /** 값이 커지면 삼각함수가 뭉개지므로 한 시간마다 되돌립니다. */
        fun seconds(uptimeMs: Long): Float = (uptimeMs % WRAP_MS) / 1000f
    }
}
