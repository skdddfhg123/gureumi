package com.example.pleosapp.ui.spirit

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.example.pleosapp.engine.Mood
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 구름이를 코드로 그리는 뷰. 좌표는 docs/assets/gureumi/ 아래 SVG 초안의 240×200 설계 공간이고,
 * 뷰 크기에 맞춰 통째로 확대합니다(히어로 560px, 소형 160px 모두 선명).
 * 상태 전환 400ms 보간, idle bob 2.5초, 깜빡임 3~6초, 축하 1.2초 원샷 — docs/M1_SPEC.md 3.1.
 * 애니메이션은 창에 붙어 있고 보일 때만 돌고, 떨어지면 멈춥니다.
 */
class SpiritView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs), SpiritRenderer {

    var currentMood: Mood = Mood.HAPPY
        private set
    var currentLevel: Int = 1
        private set
    private var talking = false

    // 전환: from → to 를 blend(0~1)로 섞어 그립니다.
    private var from = SpiritLook.HAPPY
    private var to = SpiritLook.HAPPY
    private var blend = 1f
    private var transition: ValueAnimator? = null

    private var celebrateStart = 0L
    private var celebrateUntil = 0L
    private var blinkUntil = 0L
    private var nextBlinkAt = 0L
    private val startMs = SystemClock.uptimeMillis()
    private var running = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(120f, 112f, 100f, intArrayOf(0xF2FFE9A8.toInt(), 0x00FFE9A8), null, Shader.TileMode.CLAMP)
    }
    private val path = Path()
    private val drop = Path().apply { // SVG: q7 12 0 17 q-7 -5 0 -17
        moveTo(0f, 0f); quadTo(7f, 12f, 0f, 17f); quadTo(-7f, 12f, 0f, 0f); close()
    }
    private val star = Path().apply { // SVG 4각 별, 반지름 9
        moveTo(0f, -9f); lineTo(2.6f, -2.6f); lineTo(9f, 0f); lineTo(2.6f, 2.6f)
        lineTo(0f, 9f); lineTo(-2.6f, 2.6f); lineTo(-9f, 0f); lineTo(-2.6f, -2.6f); close()
    }

    private var skipFrame = false
    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            step(SystemClock.uptimeMillis())
            // idle 모션(bob·깜빡임)은 30fps면 충분합니다. 전환·축하는 ValueAnimator가 매 프레임 invalidate 합니다.
            skipFrame = !skipFrame
            if (!skipFrame) invalidate()
            postOnAnimation(this)
        }
    }

    // ---- SpiritRenderer ----

    override fun setMood(mood: Mood) {
        if (currentMood == mood) return
        currentMood = mood
        if (celebrateUntil == 0L) startTransition(SpiritLook.of(mood), TRANSITION_MS)
    }

    override fun setLevel(level: Int) {
        currentLevel = level.coerceIn(1, 5)
        invalidate()
    }

    override fun celebrate() {
        val now = SystemClock.uptimeMillis()
        celebrateStart = now
        celebrateUntil = now + CELEBRATE_MS
        startTransition(SpiritLook.CELEBRATE, GLOW_MS)
    }

    override fun setTalking(talking: Boolean) {
        this.talking = talking
        invalidate()
    }

    // ---- 애니메이션 구동 ----

    private fun startTransition(target: SpiritLook, durationMs: Long) {
        transition?.cancel()
        from = SpiritLook.lerp(from, to, blend)
        to = target
        blend = 0f
        transition = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { blend = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun step(now: Long) {
        if (celebrateUntil != 0L && now >= celebrateUntil) {
            celebrateUntil = 0L
            startTransition(SpiritLook.of(currentMood), TRANSITION_MS)
        }
        val look = if (blend >= 0.5f) to else from
        if (look.blinks) {
            if (nextBlinkAt == 0L) nextBlinkAt = now + blinkDelay()
            if (now >= nextBlinkAt) {
                blinkUntil = now + BLINK_MS
                nextBlinkAt = blinkUntil + blinkDelay()
            }
        } else {
            blinkUntil = 0L
            nextBlinkAt = 0L
        }
    }

    private fun blinkDelay() = Random.nextLong(3_000L, 6_000L)

    private fun start() {
        if (running || !isAttachedToWindow) return
        running = true
        postOnAnimation(frame)
    }

    private fun stop() {
        running = false
        removeCallbacks(frame)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        transition?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) start() else stop()
    }

    // ---- 그리기 ----

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = (now - startMs) / 1000f
        val look = SpiritLook.lerp(from, to, blend)
        val levelScale = 1f + 0.05f * (currentLevel - 1) // Lv.1 1.0 → Lv.5 1.2
        val s = min(width / DESIGN_W, height / DESIGN_H) * FIT * levelScale

        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(s, s)
        canvas.translate(-DESIGN_W / 2f, -DESIGN_H / 2f)

        if (look.glow > 0.01f) {
            glowPaint.alpha = (255 * look.glow).toInt()
            canvas.drawCircle(120f, 112f, 100f, glowPaint)
        }
        fill.color = withAlpha(look.shadow, look.shadowAlpha)
        canvas.drawOval(60f, look.shadowY - look.shadowRy, 180f, look.shadowY + look.shadowRy, fill)

        // 몸: bob + 기울기(흔들림)
        val bob = sin(2 * PI * t / BOB_PERIOD).toFloat() * (DESIGN_H * 0.04f) * look.bobAmp
        val wobble = if (look.wobbleDeg > 0f) sin(2 * PI * t / WOBBLE_PERIOD).toFloat() * look.wobbleDeg else 0f
        canvas.save()
        canvas.translate(0f, bob)
        canvas.rotate(look.tiltDeg + wobble, 120f, 120f)
        drawBody(canvas, look)
        drawFace(canvas, look, now, t)
        canvas.restore()

        drawProps(canvas, look, t, now)
        canvas.restore()
    }

    private fun drawBody(canvas: Canvas, look: SpiritLook) {
        fill.color = look.shade
        canvas.save()
        canvas.translate(0f, 6f)
        drawSilhouette(canvas)
        canvas.restore()
        fill.color = look.body
        drawSilhouette(canvas)
        if (currentLevel >= 5) {
            fill.color = STAR_YELLOW
            drawStar(canvas, 196f, 44f, 0.8f)
        }
    }

    /** 원 3개 + 밑변 타원(기본 실루엣은 레벨과 무관). Lv.3·Lv.4에서 작은 뭉게가 하나씩 더 붙어 3 → 5개가 됩니다. */
    private fun drawSilhouette(canvas: Canvas) {
        canvas.drawCircle(78f, 112f, 40f, fill)
        canvas.drawCircle(118f, 92f, 52f, fill)
        canvas.drawCircle(160f, 112f, 38f, fill)
        canvas.drawOval(38f, 100f, 202f, 160f, fill)
        if (currentLevel >= 3) canvas.drawCircle(60f, 84f, 16f, fill)
        if (currentLevel >= 4) canvas.drawCircle(176f, 86f, 14f, fill)
    }

    private fun drawFace(canvas: Canvas, look: SpiritLook, now: Long, t: Float) {
        fill.color = withAlpha(look.cheek, look.cheekAlpha)
        canvas.drawCircle(92f, 120f, look.cheekRadius, fill)
        canvas.drawCircle(148f, 120f, look.cheekRadius, fill)

        val blinking = now < blinkUntil
        val eyesFrom = if (blinking) EyeStyle.CLOSED else from.eyes
        val eyesTo = if (blinking) EyeStyle.CLOSED else to.eyes
        crossfade(eyesFrom, eyesTo) { style, alpha -> drawEyes(canvas, style, alpha) }

        if (look.brows > 0.01f) {
            stroke.color = withAlpha(INK, look.brows)
            stroke.strokeWidth = 2.6f
            canvas.drawLine(96f, 94f, 110f, 98f, stroke)
            canvas.drawLine(144f, 94f, 130f, 98f, stroke)
        }

        if (talking) {
            val open = (sin(2 * PI * TALK_HZ * t).toFloat() + 1f) / 2f
            fill.color = INK
            canvas.drawOval(113f, 122f - (2f + 6f * open), 127f, 122f + (2f + 6f * open), fill)
        } else {
            crossfade(from.mouth, to.mouth) { style, alpha -> drawMouth(canvas, style, alpha) }
        }
    }

    /** 전환 중이면 이전 모양을 (1-blend), 새 모양을 blend 투명도로 겹쳐 그립니다. */
    private inline fun <T> crossfade(a: T, b: T, draw: (T, Float) -> Unit) {
        if (a == b || blend >= 1f) {
            draw(b, 1f)
        } else {
            draw(a, 1f - blend)
            draw(b, blend)
        }
    }

    private fun drawEyes(canvas: Canvas, style: EyeStyle, alpha: Float) {
        for (x in EYE_X) when (style) {
            EyeStyle.DOT -> {
                fill.color = withAlpha(INK, alpha)
                canvas.drawCircle(x, 106f, 6f, fill)
                fill.color = withAlpha(WHITE, alpha)
                canvas.drawCircle(x + 2f, 104f, 2f, fill)
            }
            EyeStyle.SWIRL -> {
                stroke.color = withAlpha(INK, alpha)
                stroke.strokeWidth = 2.6f
                canvas.drawCircle(x, 106f, 6f, stroke)
                fill.color = withAlpha(INK, alpha)
                canvas.drawCircle(x + 1.5f, 107f, 2f, fill)
            }
            EyeStyle.CLOSED -> strokeQuad(canvas, x - 6f, 108f, x, 114f, x + 6f, 108f, 3f, withAlpha(INK, alpha))
            EyeStyle.HALF -> {
                fill.color = withAlpha(INK, alpha)
                canvas.drawOval(x - 6f, 107f - 3.6f, x + 6f, 107f + 3.6f, fill)
            }
            EyeStyle.ARC_UP -> strokeQuad(canvas, x - 6f, 106f, x, 99f, x + 6f, 106f, 3.2f, withAlpha(INK, alpha))
        }
    }

    private fun drawMouth(canvas: Canvas, style: MouthStyle, alpha: Float) {
        val ink = withAlpha(INK, alpha)
        when (style) {
            MouthStyle.SMILE -> strokeQuad(canvas, 110f, 120f, 120f, 129f, 130f, 120f, 3f, ink)
            MouthStyle.WAVY -> {
                stroke.color = ink
                stroke.strokeWidth = 3f
                path.reset()
                path.moveTo(108f, 122f)
                path.quadTo(113f, 117f, 118f, 122f)
                path.quadTo(123f, 127f, 128f, 122f)
                canvas.drawPath(path, stroke)
            }
            MouthStyle.FROWN -> strokeQuad(canvas, 110f, 126f, 120f, 118f, 130f, 126f, 3f, ink)
            MouthStyle.BIG_SMILE -> strokeQuad(canvas, 106f, 118f, 120f, 134f, 134f, 118f, 3.2f, ink)
        }
    }

    private fun drawProps(canvas: Canvas, look: SpiritLook, t: Float, now: Long) {
        if (look.sweat > 0.01f) {
            fill.color = withAlpha(DROP_BLUE, look.sweat)
            drawDrop(canvas, 184f, 66f)
        }
        if (look.motionLines > 0.01f) {
            val c = withAlpha(LINE_BLUE, look.motionLines)
            strokeQuad(canvas, 40f, 80f, 32f, 86f, 38f, 94f, 3f, c)
            strokeQuad(canvas, 30f, 100f, 22f, 106f, 28f, 114f, 3f, c)
        }
        if (look.rain > 0.01f) {
            RAIN.forEachIndexed { i, (x, y) ->
                val fall = ((t * 0.6f + i * 0.33f) % 1f)
                fill.color = withAlpha(DROP_BLUE, look.rain * (1f - 0.6f * fall))
                drawDrop(canvas, x, y + fall * 14f)
            }
        }
        if (look.stars > 0.01f) {
            val e = (now - celebrateStart) / 1000f
            fill.color = withAlpha(STAR_YELLOW, look.stars)
            STARS.forEachIndexed { i, (x, y) -> drawStar(canvas, x, y, 0.75f + 0.25f * sin(e * 8f + i).toFloat()) }
        }
        if (look.confetti > 0.01f) {
            val e = (now - celebrateStart) / 1000f
            CONFETTI.forEach { (x, y, r, color) ->
                fill.color = withAlpha(color, look.confetti)
                canvas.drawCircle(x, y + e * 20f, r, fill)
            }
        }
    }

    private fun drawDrop(canvas: Canvas, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.drawPath(drop, fill)
        canvas.restore()
    }

    private fun drawStar(canvas: Canvas, x: Float, y: Float, scale: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        canvas.drawPath(star, fill)
        canvas.restore()
    }

    private fun strokeQuad(canvas: Canvas, x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float, width: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = width
        path.reset()
        path.moveTo(x0, y0)
        path.quadTo(cx, cy, x1, y1)
        canvas.drawPath(path, stroke)
    }

    /** 색의 알파에 [alpha]를 곱합니다. */
    private fun withAlpha(color: Int, alpha: Float): Int {
        val base = (color ushr 24) / 255f
        val a = ((base * alpha).coerceIn(0f, 1f) * 255f).toInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private data class Dot(val x: Float, val y: Float, val r: Float, val color: Int)

    private companion object {
        const val DESIGN_W = 240f
        const val DESIGN_H = 200f
        /** 레벨 5(1.2배)에서도 뷰 안에 들어오도록 기본 축소. */
        const val FIT = 0.85f
        const val BOB_PERIOD = 2.5f
        const val WOBBLE_PERIOD = 0.8f
        const val TALK_HZ = 6f
        const val TRANSITION_MS = 400L
        const val GLOW_MS = 300L
        const val CELEBRATE_MS = 1_200L
        const val BLINK_MS = 120L

        val INK = 0xFF2B2B2B.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
        val DROP_BLUE = 0xFF7FB2F0.toInt()
        val LINE_BLUE = 0xFF9CC4F2.toInt()
        val STAR_YELLOW = 0xFFFFC94D.toInt()

        val EYE_X = floatArrayOf(104f, 136f)
        val RAIN = listOf(88f to 156f, 120f to 164f, 152f to 156f)
        val STARS = listOf(38f to 60f, 200f to 52f, 188f to 124f, 52f to 140f)
        val CONFETTI = listOf(
            Dot(70f, 40f, 4f, 0xFF9CC4F2.toInt()),
            Dot(170f, 30f, 3.5f, 0xFFFFB3C2.toInt()),
            Dot(214f, 96f, 3f, 0xFFB7E4C7.toInt()),
            Dot(24f, 110f, 3f, 0xFFFFD9A0.toInt()),
        )
    }
}
