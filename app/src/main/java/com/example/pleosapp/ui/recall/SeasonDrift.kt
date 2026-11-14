package com.example.pleosapp.ui.recall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import com.example.pleosapp.engine.Drift
import com.example.pleosapp.engine.Reward
import com.example.pleosapp.engine.SceneState
import com.example.pleosapp.engine.Season
import com.example.pleosapp.engine.TimeTogether
import com.example.pleosapp.ui.game.MotionTicker
import com.example.pleosapp.ui.spirit.SpiritSprites
import java.util.Calendar
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 창밖에 날리는 계절. 봄에는 꽃잎, 여름 낮에는 민들레 솜털, 여름밤에는 반딧불, 가을에는 낙엽, 겨울에는 작은 눈송이입니다.
 * 무엇이 날릴지는 달, 시간대, 창에 든 풍경으로 정합니다(`TimeTogether.drift`). 조건이나 보상이 붙지 않습니다.
 * 자리는 시간만으로 정해져서 상태가 없습니다. 창밖 그림과 실내판 사이에 그려서 창틀 뒤로 지나갑니다. 좌표는 설계 좌표입니다.
 */
class SeasonDrift(private val context: Context) {

    private val random = Random(23)
    private val seeds = FloatArray(MAX * SEEDS) { random.nextFloat() }
    private val sprite = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, FIREFLY_GLOW, 0, Shader.TileMode.CLAMP)
    }
    private val rect = RectF()
    private var kind: Drift? = null
    private var count = 0

    /** 바람이 부는 동안만 날립니다. 늘 날리면 배경이 되고, 창밖을 쉬지 않고 다시 그려야 합니다. */
    private var active = 0

    /** 날리는 것이 있을 때 창밖을 다시 그릴 간격. 바람이 잦아들면 쉽니다. */
    val redrawMs: Long
        get() {
            val gusting = MotionTicker.seconds(SystemClock.uptimeMillis()) % GUST_EVERY_SEC < GUST_SEC
            return if (kind == null || count == 0 || !gusting) Long.MAX_VALUE else DRIFT_MS
        }
    private var sprites: List<Bitmap> = emptyList()

    /** 장면, 창에 든 풍경, 함께한 날로 무엇이 얼마나 날릴지 정합니다. */
    fun show(state: SceneState, view: Reward?, days: Int, filter: ColorFilter?) {
        val next = TimeTogether.drift(season(), state, view)
        if (next != kind) {
            kind = next
            sprites = next?.let(::spritesOf).orEmpty()
        }
        count = TimeTogether.driftCount(days)
        sprite.colorFilter = filter
        dot.colorFilter = filter
    }

    /** 그림이 오면 앞의 이름이 잡힙니다. 아직 없으면 귀환품 그림을 작게 씁니다. */
    private fun spritesOf(target: Drift): List<Bitmap> {
        val names = when (target) {
            Drift.PETAL -> listOf("fx_petal_1", "fx_petal_2", "fx_petal_3")
            Drift.LEAF -> listOf("fx_leaf_1", "fx_leaf_2", "fx_leaf_3", "return_maple")
            Drift.SEED -> listOf("fx_seed_1", "fx_seed_2", "fx_seed_3", "return_seed")
            Drift.FIREFLY, Drift.FLAKE -> emptyList()
        }
        val found = names.map { SpiritSprites.named(context, it) }.filter { it != 0 }
        val own = if (found.size > 1) found.filterNot { it == found.last() && names.last().startsWith("return") } else found
        return own.map { SpiritSprites.bitmap(context, it) }
    }

    fun draw(canvas: Canvas, t: Float) {
        val phase = t % GUST_EVERY_SEC
        if (phase >= GUST_SEC) return
        // 바람이 일 때 하나씩 늘고 잦아들 때 하나씩 사라집니다.
        val strength = (min(phase, GUST_SEC - phase) / GUST_EDGE_SEC).coerceAtMost(1f)
        active = (count * strength).toInt()
        when (kind) {
            Drift.PETAL -> drawFall(canvas, t, speed = 34f, wind = -16f, sway = 30f, size = 22f, turn = 70f)
            Drift.LEAF -> drawFall(canvas, t, speed = 30f, wind = -24f, sway = 46f, size = 34f, turn = 54f)
            Drift.SEED -> drawFall(canvas, t, speed = -9f, wind = 20f, sway = 24f, size = 30f, turn = 8f)
            Drift.FIREFLY -> drawFireflies(canvas, t)
            Drift.FLAKE -> drawFlakes(canvas, t)
            null -> Unit
        }
    }

    /** 위에서 아래로, 또는 [speed]가 음수면 아래에서 위로 떠갑니다. 몸을 뒤집으며 내려옵니다. */
    private fun drawFall(canvas: Canvas, t: Float, speed: Float, wind: Float, sway: Float, size: Float, turn: Float) {
        for (i in 0 until active) {
            val a = seeds[i * SEEDS]
            val b = seeds[i * SEEDS + 1]
            val c = seeds[i * SEEDS + 2]
            val d = seeds[i * SEEDS + 3]
            val pace = 0.7f + c * 0.6f
            val y = TOP + floorMod(b * HEIGHT + t * speed * pace, HEIGHT)
            val x = LEFT + floorMod(a * WIDTH + t * wind * pace, WIDTH) + sin(t * (0.5f + d * 0.5f) + a * 6f) * sway
            val tumble = cos(t * (0.8f + c) + b * 6f)
            val s = size * (0.75f + d * 0.5f)
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(a * 360f + t * turn * pace)
            canvas.scale(0.35f + abs(tumble) * 0.65f, 1f)
            if (sprites.isEmpty()) {
                dot.color = PETALS[i % PETALS.size]
                rect.set(-s * 0.5f, -s * 0.3f, s * 0.5f, s * 0.3f)
                canvas.drawOval(rect, dot)
            } else {
                val art = sprites[i % sprites.size]
                val w = s
                val h = s * art.height / art.width
                rect.set(-w / 2f, -h / 2f, w / 2f, h / 2f)
                canvas.drawBitmap(art, null, rect, sprite)
            }
            canvas.restore()
        }
    }

    /** 반딧불은 낮은 곳을 떠돌며 천천히 켜졌다 꺼집니다. */
    private fun drawFireflies(canvas: Canvas, t: Float) {
        for (i in 0 until active) {
            val a = seeds[i * SEEDS]
            val b = seeds[i * SEEDS + 1]
            val c = seeds[i * SEEDS + 2]
            val x = LEFT + a * WIDTH + sin(t * (0.21f + c * 0.2f) + i) * 54f
            val y = FIREFLY_TOP + b * FIREFLY_HEIGHT + cos(t * (0.27f + c * 0.2f) + i * 2f) * 22f
            val wave = sin(t * (0.7f + c * 0.8f) + i * 1.9f)
            val light = (wave * wave * wave).coerceAtLeast(0f)
            if (light <= 0.02f) continue
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(26f, 26f)
            glow.alpha = (light * 255).toInt()
            canvas.drawCircle(0f, 0f, 1f, glow)
            canvas.restore()
            dot.color = FIREFLY
            dot.alpha = (light * 255).toInt()
            canvas.drawCircle(x, y, 3f, dot)
        }
    }

    private fun drawFlakes(canvas: Canvas, t: Float) {
        dot.color = FLAKE
        for (i in 0 until count * 2) {
            val a = seeds[i * SEEDS]
            val b = seeds[i * SEEDS + 1]
            val c = seeds[i * SEEDS + 2]
            val y = TOP + floorMod(b * HEIGHT + t * (14f + c * 12f), HEIGHT)
            val x = LEFT + a * WIDTH + sin(t * 0.5f + i) * 22f
            dot.alpha = (130 + c * 90).toInt()
            canvas.drawCircle(x, y, 2.4f + c * 3f, dot)
        }
    }

    private fun floorMod(value: Float, span: Float): Float = ((value % span) + span) % span

    companion object {
        /** debug 빌드에서 계절을 미리 볼 때만 씁니다. 0이면 실제 달입니다. */
        @Volatile
        var previewMonth = 0

        fun season(): Season =
            TimeTogether.season(previewMonth.takeIf { it in 1..12 } ?: (Calendar.getInstance().get(Calendar.MONTH) + 1))

        /** 달에 맞는 계절 그림. 겨울은 사다리의 겨울 둥지가 맡습니다. 아직 없으면 0입니다. */
        fun art(context: Context, prefix: String): Int = when (season()) {
            Season.SPRING -> SpiritSprites.named(context, "${prefix}_spring")
            Season.SUMMER -> SpiritSprites.named(context, "${prefix}_summer")
            Season.AUTUMN -> SpiritSprites.named(context, "${prefix}_autumn")
            Season.WINTER -> 0
        }

        private const val DRIFT_MS = 125L
        private const val GUST_SEC = 8f
        private const val GUST_EVERY_SEC = 24f
        private const val GUST_EDGE_SEC = 1.5f
        private const val MAX = 24
        private const val SEEDS = 4
        private const val LEFT = 110f
        private const val WIDTH = 1320f
        private const val TOP = 190f
        private const val HEIGHT = 380f
        private const val FIREFLY_TOP = 400f
        private const val FIREFLY_HEIGHT = 130f
        private const val FIREFLY = 0xFFFFF2A8.toInt()
        private const val FIREFLY_GLOW = 0xAAF2E58A.toInt()
        private const val FLAKE = 0xFFFDFDFB.toInt()
        private val PETALS = intArrayOf(0xFFF6CFC8.toInt(), 0xFFFBE6DC.toInt(), 0xFFF2BFC0.toInt())
    }
}
