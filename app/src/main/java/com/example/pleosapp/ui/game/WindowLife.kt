package com.example.pleosapp.ui.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.example.pleosapp.engine.DayPart
import com.example.pleosapp.engine.Sky
import com.example.pleosapp.ui.spirit.SpiritSprites
import java.util.Random
import kotlin.math.sin

/**
 * 창밖을 가끔 지나가는 것. 새, 갈매기, 나비가 드물게 날아가고 바다에는 돛단배가 떠 있습니다.
 * 늘 보이면 배경이 되고, 가끔 지나가야 오래 봐도 눈길이 갑니다.
 */
class WindowLife private constructor(
    private val flyers: List<Flyer>,
    private val boat: List<Bitmap>,
    private val ripple: Bitmap?,
) {
    /** 지나가는 것 하나. 날 때마다 하나를 고릅니다. [wobble]은 오르내리는 폭입니다. */
    private class Flyer(val frames: List<Bitmap>, val width: Float, val speed: Float, val wobble: Float)

    private var flying: Flyer? = null
    private val rect = RectF()
    private val random = Random()
    private val ripplePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = RIPPLE_ALPHA }
    private var flightStart = NEVER
    private var nextFlightAt = FIRST_FLIGHT_SEC
    private var direction = 1f
    private var height = 0f

    /** 날아가는 동안에는 창밖을 자주 다시 그립니다. 그 밖에는 느려도 됩니다. */
    val redrawMs: Long get() = if (flightStart != NEVER) FLIGHT_MS else Long.MAX_VALUE

    fun draw(canvas: Canvas, t: Float, paint: Paint) {
        ripple?.let { drawRipple(canvas, it, t) }
        if (boat.isNotEmpty()) drawBoat(canvas, t, paint)
        if (flyers.isNotEmpty()) drawFlyer(canvas, t, paint)
    }

    private fun drawRipple(canvas: Canvas, band: Bitmap, t: Float) {
        val shift = (t * RIPPLE_SPEED) % HomeScene.WIDTH
        rect.set(-shift, RIPPLE_TOP, HomeScene.WIDTH - shift, RIPPLE_TOP + band.height)
        canvas.drawBitmap(band, null, rect, ripplePaint)
        rect.offset(HomeScene.WIDTH, 0f)
        canvas.drawBitmap(band, null, rect, ripplePaint)
    }

    /** 돛단배는 물결을 타고 아주 천천히 건너갑니다. */
    private fun drawBoat(canvas: Canvas, t: Float, paint: Paint) {
        val span = BOAT_RIGHT - BOAT_LEFT
        val x = BOAT_LEFT + (t * BOAT_SPEED) % span
        val frame = boat[((t / BOAT_FRAME_SEC).toInt() % boat.size)]
        val h = BOAT_WIDTH * frame.height / frame.width
        val y = BOAT_WATER - h + sin(t * BOAT_BOB) * 1.5f
        rect.set(x, y, x + BOAT_WIDTH, y + h)
        canvas.drawBitmap(frame, null, rect, paint)
    }

    private fun drawFlyer(canvas: Canvas, t: Float, paint: Paint) {
        if (flightStart == NEVER) {
            // 시각이 한 시간마다 되돌아가도 다음 비행이 밀리지 않게 합니다.
            if (t < nextFlightAt && nextFlightAt - t < FLIGHT_GAP_MAX_SEC) return
            flightStart = t
            direction = if (random.nextBoolean()) 1f else -1f
            height = FLY_TOP + random.nextFloat() * FLY_RANGE
            flying = flyers[random.nextInt(flyers.size)]
        }
        val current = flying ?: flyers.first()
        val flyer = current.frames
        val flyerWidth = current.width
        val travelled = (t - flightStart) * current.speed
        val span = HomeScene.WIDTH + flyerWidth * 2f
        if (travelled !in 0f..span) {
            flightStart = NEVER
            nextFlightAt = t + FLIGHT_GAP_MIN_SEC + random.nextFloat() * (FLIGHT_GAP_MAX_SEC - FLIGHT_GAP_MIN_SEC)
            return
        }
        val x = if (direction > 0f) travelled - flyerWidth else HomeScene.WIDTH + flyerWidth - travelled
        val wings = (t * WING_BEATS).toInt() % PING_PONG.size
        val frame = flyer[PING_PONG[wings].coerceAtMost(flyer.lastIndex)]
        val h = flyerWidth * frame.height / frame.width
        val y = height + sin(travelled * WOBBLE) * current.wobble
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(direction, 1f)
        rect.set(-flyerWidth / 2f, -h / 2f, flyerWidth / 2f, h / 2f)
        canvas.drawBitmap(frame, null, rect, paint)
        canvas.restore()
    }

    companion object {
        private const val NEVER = -1f
        private const val BUTTERFLY_WIDTH = 34f // 아기 손바닥만(R-123 6번). 창 가까이 지나가 새와 비슷한 크기로 보입니다.
        private const val BUTTERFLY_SPEED = 110f
        private const val BUTTERFLY_WOBBLE = 22f
        private const val FIRST_FLIGHT_SEC = 8f
        private const val FLIGHT_GAP_MIN_SEC = 22f
        private const val FLIGHT_GAP_MAX_SEC = 55f
        private const val FLIGHT_MS = 110L
        private const val WING_BEATS = 8f
        private const val WOBBLE = 0.012f
        private const val FLY_TOP = 285f
        private const val FLY_RANGE = 70f
        private const val RIPPLE_TOP = 395f
        private const val RIPPLE_SPEED = 5f
        private const val RIPPLE_ALPHA = 80
        private const val BOAT_WIDTH = 60f
        private const val BOAT_LEFT = 120f
        private const val BOAT_RIGHT = 1420f
        private const val BOAT_WATER = 440f
        private const val BOAT_SPEED = 2.2f
        private const val BOAT_BOB = 0.9f
        private const val BOAT_FRAME_SEC = 1.4f
        private val PING_PONG = intArrayOf(0, 1, 2, 1)

        /**
         * 맑은 낮 시간에만 나옵니다. 밤과 궂은 날의 창은 조용합니다.
         * 창밖은 늘 강가라 강가의 새가 지나갑니다. 바다의 갈매기와 돛단배는 나오지 않습니다.
         * 강가 나비 보상(R-123)을 받았으면 노랑나비와 흰나비도 가끔 지나갑니다. 새보다 작고 느리고 더 오르내립니다.
         */
        fun of(context: Context, look: NookLook): WindowLife? {
            val state = look.state
            if (state.sky != Sky.CLEAR || state.part == DayPart.NIGHT) return null
            val flyers = buildList {
                frames(context, "bird").takeIf { it.isNotEmpty() }?.let { add(Flyer(it, 40f, 170f, 12f)) }
                if (look.butterflies) {
                    listOf("butterfly_yellow", "butterfly_white").forEach { name ->
                        frames(context, name).takeIf { it.isNotEmpty() }?.let { add(Flyer(it, BUTTERFLY_WIDTH, BUTTERFLY_SPEED, BUTTERFLY_WOBBLE)) }
                    }
                }
            }
            return WindowLife(flyers, emptyList(), null)
        }

        private fun frames(context: Context, name: String): List<Bitmap> = SpiritSprites.frames(context, "fly_$name")
    }
}
