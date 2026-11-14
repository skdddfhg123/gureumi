package com.example.pleosapp.ui.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.SparseArray
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.BounceInterpolator
import com.example.pleosapp.R
import com.example.pleosapp.engine.GiftState
import com.example.pleosapp.engine.Growth
import com.example.pleosapp.engine.HomeSpeech
import com.example.pleosapp.engine.Reward
import com.example.pleosapp.engine.SeatSlot
import com.example.pleosapp.ui.spirit.ReducedSlot
import com.example.pleosapp.ui.spirit.SpiritSprites
import java.util.Random
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 깃털이 쌓이는 유리병. 숫자 대신 병이 차오르는 모습으로 얼마나 모였는지 보여 줍니다.
 * 깃털은 병 속에서 살랑이고, 도착하면 위에서 하나씩 떨어져 쌓입니다.
 */
class JarView(context: Context) : View(context) {

    private val jar = SpiritSprites.bitmap(context, R.drawable.jar_empty)
    private val feathers = listOf(
        R.drawable.feather_1, R.drawable.feather_2, R.drawable.feather_3,
        R.drawable.feather_4, R.drawable.feather_5, R.drawable.feather_6,
    ).map { SpiritSprites.bitmap(context, it) }
    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 90 }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, GLOW, 0, Shader.TileMode.CLAMP)
    }
    private val breathGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, BREATH_GLOW, 0, Shader.TileMode.CLAMP)
    }
    private val bounce = BounceInterpolator()
    private val rect = RectF()
    private val body = RectF()
    private var count = 0
    private var pourFrom = 0
    private var pourStart = 0L
    private var pourDone: (() -> Unit)? = null
    private var lit = false
    private var dim = false
    private var waiting = false

    /** 숨 한 번을 시작한 시각. 0이면 없습니다. */
    private var breathAt = 0L

    fun show(count: Int) {
        this.count = count
        pourFrom = count
        pourDone = null
    }

    /** 깃털을 하나씩 떨어뜨립니다. 다 쌓이면 [onDone]을 부릅니다. */
    fun pour(from: Int, to: Int, onDone: (() -> Unit)? = null) {
        count = to
        pourFrom = from
        pourStart = SystemClock.uptimeMillis()
        pourDone = onDone
    }

    /** 깃털이 떨어지는 중인지. */
    val busy: Boolean get() = pourDone != null

    /** 밤에는 병 속 깃털이 은은하게 빛납니다. */
    fun setLit(lit: Boolean) {
        this.lit = lit
    }

    /** 차 배터리가 약하면 깃털 빛도 흐려져 깜빡입니다. */
    fun setDim(dim: Boolean) {
        this.dim = dim
    }

    /** 선물이 기다리면 병이 살짝 빛나며 숨을 쉽니다. */
    fun setWaiting(waiting: Boolean) {
        this.waiting = waiting
    }

    /**
     * 병이 숨 쉬듯 한 번 부풀며 빛납니다. 처음 켠 날 구름이가 깃털 병을 말할 때입니다.
     * 낮에는 선물을 기다리는 빛만으로는 잘 안 보여 빛을 조금 진하게 하고 바닥을 붙인 채 부풉니다.
     */
    fun breatheOnce() {
        breathAt = SystemClock.uptimeMillis()
        pivotX = width / 2f
        pivotY = height.toFloat()
        val half = BREATH_MS / 2
        animate().scaleX(BREATH_SCALE).scaleY(BREATH_SCALE).setDuration(half).setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(half).start() }
            .start()
        invalidate()
    }

    /** 밤과 흐린 날의 빛을 입습니다. */
    fun setAmbient(filter: ColorFilter?) {
        image.colorFilter = filter
        glass.colorFilter = filter
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val seconds = MotionTicker.seconds(now)
        val bodyHeight = width * jar.height / jar.width.toFloat()
        body.set(0f, height - bodyHeight, width.toFloat(), height.toFloat())
        if (lit || waiting) drawGlow(canvas, glow, 0.9f + 0.1f * sin(seconds * if (waiting) 2.4f else 1.1f), 255)
        // 숨 한 번은 빛이 차올랐다 잦아듭니다. 밤빛 위에도 한 겹 더 얹어 보이게 합니다.
        val breath = (now - breathAt).toFloat() / BREATH_MS
        if (breathAt > 0L && breath < 1f) {
            val swell = sin(PI.toFloat() * breath)
            drawGlow(canvas, breathGlow, 0.9f + 0.35f * swell, (255 * swell).toInt())
        }
        canvas.drawBitmap(jar, null, body, image)
        for (index in 0 until min(count, VISIBLE)) drawFeather(canvas, index, now, seconds)
        canvas.drawBitmap(jar, null, body, glass)
        val finished = pourDone
        if (finished != null && now >= pourStart + (count - pourFrom) * DROP_GAP_MS + DROP_MS) {
            pourDone = null
            finished()
        }
    }

    /** 병 몸통 뒤의 둥근 빛. [scale]은 몸통 폭에 견준 반지름입니다. */
    private fun drawGlow(canvas: Canvas, paint: Paint, scale: Float, alpha: Int) {
        val radius = body.width() * scale
        paint.alpha = alpha
        canvas.save()
        canvas.translate(body.centerX(), body.centerY())
        canvas.scale(radius, radius)
        canvas.drawCircle(0f, 0f, 1f, paint)
        canvas.restore()
    }

    /** 바닥부터 차곡차곡 쌓입니다. 같은 깃털은 늘 같은 자리에 있습니다. */
    private fun drawFeather(canvas: Canvas, index: Int, now: Long, seconds: Float) {
        val slot = SLOTS[index]
        val restX = body.left + slot[0] * body.width()
        val restY = body.top + slot[1] * body.height()
        var y = restY
        var spin = 0f
        if (index >= pourFrom) {
            val progress = (now - pourStart - (index - pourFrom) * DROP_GAP_MS).toFloat() / DROP_MS
            if (progress < 0f) return
            if (progress < 1f) {
                y = restY + (-body.height() * 0.5f - restY) * (1f - bounce.getInterpolation(progress))
                spin = (1f - progress) * 170f
            }
        }
        val feather = feathers[index % feathers.size]
        val w = body.width() * FEATHER_WIDTH
        val h = w * feather.height / feather.width
        val sway = sin(seconds * 0.9f + index * 1.3f) * 3f
        canvas.save()
        canvas.translate(restX, y)
        canvas.rotate(slot[2] + sway + spin)
        rect.set(-w / 2f, -h / 2f, w / 2f, h / 2f)
        if (dim) image.alpha = (150 + 60 * sin(seconds * 2.3f + index)).toInt()
        canvas.drawBitmap(feather, null, rect, image)
        image.alpha = 255
        canvas.restore()
    }

    companion object {
        /** 병 하나에 그리는 깃털 수. 병이 커져도 찬 비율만 보여 줍니다. */
        const val VISIBLE = 12

        private const val DROP_MS = 620L
        private const val DROP_GAP_MS = 150L
        private const val FEATHER_WIDTH = 0.5f
        private const val GLOW = 0x77FFD08A

        /** 숨 한 번의 길이. 선물을 기다리는 빛이 한 번 부푸는 길이(2π/2.4초)와 같습니다. */
        private const val BREATH_MS = 2_600L

        /** 숨 한 번의 빛. 선물을 기다리는 빛과 같은 색이고 조금 더 진합니다. */
        private const val BREATH_GLOW = 0xB3FFD08A.toInt()
        private const val BREATH_SCALE = 1.05f

        /** 깃털 자리: 병 안의 x, y 비율과 기울기. 바닥부터입니다. */
        private val SLOTS = arrayOf(
            floatArrayOf(0.36f, 0.82f, -24f), floatArrayOf(0.64f, 0.82f, 18f), floatArrayOf(0.5f, 0.74f, 96f),
            floatArrayOf(0.33f, 0.68f, -48f), floatArrayOf(0.67f, 0.68f, 40f), floatArrayOf(0.5f, 0.6f, -80f),
            floatArrayOf(0.36f, 0.54f, 30f), floatArrayOf(0.64f, 0.54f, -36f), floatArrayOf(0.5f, 0.47f, 70f),
            floatArrayOf(0.35f, 0.41f, -60f), floatArrayOf(0.65f, 0.41f, 52f), floatArrayOf(0.5f, 0.34f, -12f),
        )

        /** 첫 병은 깃털 둘이 먼저 들어 있습니다. 시작부터 비어 있으면 멀어 보입니다. 필요한 수는 그대로입니다. */
        private const val HEAD_START = 2

        fun visible(fill: Int, capacity: Int): Int = ceil(fill * VISIBLE / capacity.toFloat()).toInt()

        fun visible(jar: Growth.Jar): Int =
            if (jar.opened == 0) (HEAD_START + visible(jar.fill, jar.capacity) * (VISIBLE - HEAD_START) / VISIBLE) else visible(jar.fill, jar.capacity)

        /** [order]번째 깃털이 병 바닥에 처음 닿는 시각. 소리를 맞출 때 씁니다. */
        fun landDelay(order: Int): Long = order * DROP_GAP_MS + DROP_MS * 4 / 11
    }
}

/**
 * 집 안 소품. [floor]가 아니면 선반의 소품을, [floor]면 마루에 놓인 소품을 그립니다.
 * 소품은 모두 받침 면 위에 서고 발밑 그늘이 있습니다(장면 규칙 2-1). 넣고 뺀 목록은 docs/design/home-reset-2026-11-04.md 4장입니다.
 * 바닥 소품 층은 정령들과 함께 바닥선으로 앞뒤를 정합니다. 담요 위 정령은 바닥 소품 뒤에, 러그 앞줄 정령은 앞에 옵니다.
 */
class DecorView(context: Context, private val floor: Boolean = false) : View(context), Grounded {

    override val feet: Float get() = scene?.viewY(HomeScene.FLOOR_FEET) ?: bottom.toFloat()

    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, SHADOW, 0, Shader.TileMode.CLAMP)
    }
    private val ambientShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, AMBIENT_SHADOW, 0, Shader.TileMode.CLAMP)
    }
    private val rect = RectF()
    private val placed = SparseArray<ReducedSlot>()
    private var scene: NookView? = null
    private var gifts = GiftState()
    private var fernArt = R.drawable.prop_fern
    private var party = false
    private var fernSpot = HomeScene.FERN

    fun attach(scene: NookView) {
        this.scene = scene
    }

    /** 화분은 함께한 날만큼 자랍니다. */
    fun setFern(art: Int, spot: HomeScene.Spot) {
        fernArt = art
        fernSpot = spot
        invalidate()
    }

    /** 바닥 소품 층은 주기로 다시 그리지 않으므로 바뀔 때 새로 그립니다. */
    fun show(gifts: GiftState, party: Boolean = false) {
        this.gifts = gifts
        this.party = party
        invalidate()
    }

    fun setAmbient(filter: ColorFilter?) {
        image.colorFilter = filter
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val current = scene ?: return
        val owned = gifts.owned
        if (floor) {
            place(canvas, current, R.drawable.prop_carebook_v2, HomeScene.CAREBOOK, 0f)
            place(canvas, current, R.drawable.prop_album_v2, HomeScene.ALBUM, 0f)
            if (party) place(canvas, current, R.drawable.prop_party, HomeScene.PARTY, 0f)
            if (Reward.DECOR_TRAY in owned) place(canvas, current, R.drawable.prop_tray, HomeScene.TRAY, 0f)
            return
        }
        val seconds = MotionTicker.seconds(SystemClock.uptimeMillis())
        if (Reward.DECOR_FERN in owned) place(canvas, current, fernArt, fernSpot, sin(seconds * 0.9f) * 1.8f)
        drawStoneRow(canvas, current)
        // 들꽃 컵 그림에는 닿는 그늘이 그려져 있어 코드 그늘을 겹치지 않습니다(R-121).
        if (Reward.DECOR_VASE in owned) place(canvas, current, R.drawable.prop_vase, HomeScene.VASE, 0f, shade = false)
        if (Reward.DECOR_MOBILE in owned) drawMobile(canvas, current, seconds)
    }

    /**
     * 천장 손잡이에 매단 모빌(R-118). 세 장을 천천히 넘겨 테가 돌아가고, 손잡이를 축으로 아주 조금 흔들립니다.
     * 매단 것이라 그늘이 없습니다. 맨 아래 돌이 선반 윗면에 닿습니다.
     */
    private fun drawMobile(canvas: Canvas, current: NookView, seconds: Float) {
        val frame = MOBILE_TURN[((seconds / MOBILE_FRAME_SEC).toInt()) % MOBILE_TURN.size]
        val resource = SpiritSprites.named(context, "prop_mobile_hang_$frame").takeIf { it != 0 } ?: return
        val source = SpiritSprites.bitmap(context, resource)
        val spot = HomeScene.MOBILE
        val h = spot.height * current.sceneScale
        val w = h * source.width / source.height
        val x = current.viewX(HomeScene.MOBILE_HOOK_X)
        val top = current.viewY(HomeScene.MOBILE_HOOK_Y)
        rect.set(x - w / 2f, top, x + w / 2f, top + h)
        val slot = placed.get(resource) ?: ReducedSlot().also { placed.put(resource, it) }
        canvas.save()
        canvas.rotate(sin(seconds * 0.7f) * MOBILE_SWAY, x, top)
        canvas.drawBitmap(slot.of(source, null, h.roundToInt()), null, rect, image)
        canvas.restore()
    }

    private fun place(canvas: Canvas, current: NookView, resource: Int, spot: HomeScene.Spot, sway: Float, shade: Boolean = true) {
        val source = SpiritSprites.bitmap(context, resource)
        val scale = current.sceneScale
        val h = spot.height * scale
        val w = h * source.width / source.height
        val x = current.viewX(spot.x)
        val y = current.viewY(spot.bottom)
        if (shade) drawShadow(canvas, source, x, y, w)
        rect.set(x - w / 2f, y - h, x + w / 2f, y)
        val slot = placed.get(resource) ?: ReducedSlot().also { placed.put(resource, it) }
        canvas.save()
        canvas.rotate(sway, x, y)
        canvas.drawBitmap(slot.of(source, null, h.roundToInt()), null, rect, image)
        canvas.restore()
    }

    /**
     * 소품이 닿는 자리에 그늘을 두 겹 깝니다. 닿는 폭에 맞춘 진한 그늘과 조금 넓은 옅은 그늘입니다.
     * 그림 폭이 아니라 칠해진 바닥 폭으로 재서, 여백까지 그늘이 지지 않습니다. 매달린 소품은 그늘이 없습니다.
     */
    private fun drawShadow(canvas: Canvas, source: Bitmap, x: Float, y: Float, w: Float) {
        val foot = SpiritSprites.footprint(source)
        val cx = x + (foot[0] - 0.5f) * w
        val reach = w * foot[1]
        canvas.save()
        canvas.translate(cx, y - 2f)
        canvas.scale(reach * AMBIENT_REACH, reach * AMBIENT_REACH * SHADOW_FLAT)
        canvas.drawCircle(0f, 0f, 1f, ambientShadow)
        canvas.restore()
        canvas.save()
        canvas.translate(cx, y - 1f)
        canvas.scale(reach * CONTACT_REACH, reach * CONTACT_REACH * SHADOW_FLAT)
        canvas.drawCircle(0f, 0f, 1f, shadow)
        canvas.restore()
    }

    /**
     * 돌 줄. 새싹이 주워 온 조약돌을 선반에 크기순으로 세웁니다(컨셉 점검 3장). 왼쪽이 가장 큰 돌입니다.
     * 아기와 함께 탄 날만큼 늘어납니다([GiftState.familyDays]). 자리보다 많으면 자리까지만 둡니다.
     */
    private fun drawStoneRow(canvas: Canvas, current: NookView) {
        val count = min(gifts.familyDays, HomeScene.STONE_ROW.size)
        for (i in 0 until count) {
            val resource = SpiritSprites.named(context, "stone_${i + 1}")
            if (resource != 0) place(canvas, current, resource, HomeScene.STONE_ROW[i], 0f)
        }
    }

    private companion object {
        const val SHADOW = 0x803A241C.toInt()
        const val AMBIENT_SHADOW = 0x383A241C
        const val CONTACT_REACH = 0.58f // 진한 그늘 반지름. 닿는 폭보다 조금 넓습니다.
        const val AMBIENT_REACH = 0.9f // 옅은 그늘 반지름
        const val SHADOW_FLAT = 0.18f // 그늘 높이 대 폭

        /** 모빌이 도는 순서. 앞, 옆, 뒤, 옆으로 천천히 돌아갑니다. */
        val MOBILE_TURN = intArrayOf(1, 2, 3, 2)
        const val MOBILE_FRAME_SEC = 1.6f
        const val MOBILE_SWAY = 0.8f // 손잡이를 축으로 흔들리는 각도(도)
    }
}

/**
 * 둥지 앞 테두리 층. 잠든 아기 아래쪽을 덮어 둥지 안에 누운 모습이 됩니다.
 * 정령들과 함께 발 자리로 앞뒤를 정합니다. 둥지 앞에 앉은 아기와 바닥 소품은 이 층보다 앞입니다.
 * 그림은 빛을 입힌 실내판에서 잘라 둔 것이라([NookView.nestRim]) 따로 빛을 입히지 않습니다.
 */
class NestFrontView(context: Context) : View(context), Grounded {

    override val feet: Float get() = scene?.viewY(HomeScene.NEST_FRONT_FEET) ?: bottom.toFloat()

    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private var scene: NookView? = null

    fun attach(scene: NookView) {
        this.scene = scene
    }

    override fun onDraw(canvas: Canvas) {
        val current = scene ?: return
        val rim = current.nestRim ?: return
        val scale = current.sceneScale
        val x = current.viewX(HomeScene.NEST_FRONT_LEFT)
        val y = current.viewY(HomeScene.NEST_FRONT_TOP)
        rect.set(x, y, x + rim.width * scale, y + rim.height * scale)
        canvas.drawBitmap(rim, null, rect, image)
    }
}

/**
 * 오늘 지킨 운전 습관. 무엇을 하면 깃털이 모이는지 설명 없이 보여 주고, 지킨 것은 도장이 찍혀 있습니다.
 * 제목은 "오늘의 도장"이었는데 뜻이 안 읽혀 무엇을 셈하는지 적습니다. 글자는 기준(24) 아래로 줄지 않습니다.
 */
class StampStripView(context: Context) : View(context) {

    private class Stamp(val art: Bitmap, val label: String, val ink: Int)

    private val stamps = listOf(
        Stamp(SpiritSprites.bitmap(context, R.drawable.stamp_belt), "안전벨트", GameStyle.SAGE),
        Stamp(SpiritSprites.bitmap(context, R.drawable.stamp_soft), "살살 멈춤", GameStyle.DUSTY_BLUE),
        Stamp(SpiritSprites.bitmap(context, R.drawable.stamp_blinker), "깜빡이", GameStyle.MUSTARD),
        Stamp(SpiritSprites.bitmap(context, R.drawable.stamp_rest), "쉬어 가기", GameStyle.CORAL),
    )
    private var earned = BooleanArray(stamps.size)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = GameStyle.PAPER
    }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = EMPTY
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GameStyle.INK
        typeface = GameStyle.BODY
        textAlign = Paint.Align.LEFT
    }
    private val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GameStyle.INK
        typeface = GameStyle.TITLE
    }
    private val stampedArt = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(GameStyle.PAPER, PorterDuff.Mode.SRC_IN)
    }
    private val faintArt = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(EMPTY, PorterDuff.Mode.SRC_IN)
    }
    private val rect = RectF()
    private var stampStart = 0L

    /** 좁은 자리에서는 글자를 빼고 도장만 놓습니다. */
    var compact = false

    fun show(value: HomeSpeech.Stamps) {
        earned = booleanArrayOf(value.belt, value.softStop, value.blinker, value.rest)
        invalidate()
    }

    /** 찍힌 도장을 차례로 쾅 찍습니다. 도착 화면에서 씁니다. */
    fun stamp(delayMs: Long) {
        stampStart = SystemClock.uptimeMillis() + delayMs
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        if (compact) {
            val slot = w / stamps.size
            val radius = min(h * 0.38f, slot * 0.36f)
            stamps.forEachIndexed { i, stamp -> drawStamp(canvas, stamp, earned[i], slot * (i + 0.5f), h / 2f, radius, i) }
            return
        }
        val least = TextRules.SMALL * resources.displayMetrics.density
        val pad = h * 0.3f
        heading.textSize = maxOf(h * 0.3f, least)
        label.textSize = maxOf(h * 0.24f, least)
        val titleWidth = heading.measureText(TITLE) + pad
        canvas.drawText(TITLE, pad, h / 2f + heading.textSize * 0.36f, heading)
        val slot = (w - titleWidth - pad) / stamps.size
        val radius = min(h * 0.33f, slot * 0.22f)
        stamps.forEachIndexed { i, stamp ->
            val cx = titleWidth + pad + slot * i + radius
            drawStamp(canvas, stamp, earned[i], cx, h / 2f, radius, i)
            canvas.drawText(stamp.label, cx + radius * 1.3f, h / 2f + label.textSize * 0.36f, label)
        }
    }

    private fun drawStamp(canvas: Canvas, stamp: Stamp, done: Boolean, cx: Float, cy: Float, radius: Float, index: Int) {
        val press = pressOf(index)
        canvas.save()
        if (done && press >= 0f) {
            val grow = 1f + (1f - press) * 0.9f
            canvas.rotate(TILT[index], cx, cy)
            canvas.scale(grow, grow, cx, cy)
            fill.color = stamp.ink
            fill.alpha = (235 * press).toInt()
            canvas.drawCircle(cx, cy, radius, fill)
            ring.strokeWidth = radius * 0.05f
            canvas.drawCircle(cx, cy, radius * 0.86f, ring)
            drawArt(canvas, stamp, cx, cy, radius, stampedArt)
        } else {
            empty.strokeWidth = radius * 0.07f
            empty.pathEffect = DashPathEffect(floatArrayOf(radius * 0.16f, radius * 0.13f), 0f)
            canvas.drawCircle(cx, cy, radius, empty)
            drawArt(canvas, stamp, cx, cy, radius, faintArt)
        }
        canvas.restore()
    }

    private fun drawArt(canvas: Canvas, stamp: Stamp, cx: Float, cy: Float, radius: Float, paint: Paint) {
        val size = radius * 1.2f
        val scale = size / maxOf(stamp.art.width, stamp.art.height)
        val w = stamp.art.width * scale
        val h = stamp.art.height * scale
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        canvas.drawBitmap(stamp.art, null, rect, paint)
    }

    /** 0이면 막 찍히는 중, 1이면 찍힌 뒤, 음수면 아직 찍히기 전입니다. */
    private fun pressOf(index: Int): Float {
        if (stampStart == 0L) return 1f
        val elapsed = SystemClock.uptimeMillis() - stampStart - index * STAMP_GAP_MS
        if (elapsed < 0L) return -1f
        return (elapsed / STAMP_MS.toFloat()).coerceAtMost(1f)
    }

    companion object {
        const val TITLE = "오늘 지킨 습관"
        private const val STAMP_MS = 220L
        private const val STAMP_GAP_MS = 320L
        private const val EMPTY = 0xFFB08F76.toInt()
        private val TILT = floatArrayOf(-7f, 5f, -4f, 8f)

        /** [index]번째 도장이 종이에 닿는 시각. 소리를 맞출 때 씁니다. */
        fun pressDelay(index: Int): Long = index * STAMP_GAP_MS + STAMP_MS
    }
}

/** 레벨이 오를 때 흩날리는 깃털. 시간만으로 자리가 정해지고, 날리는 동안에만 다시 그립니다. */
class FeatherBurstView(context: Context) : View(context) {

    private val feathers = listOf(
        R.drawable.feather_1, R.drawable.feather_2, R.drawable.feather_3,
        R.drawable.feather_4, R.drawable.feather_5, R.drawable.feather_6,
    ).map { SpiritSprites.bitmap(context, it) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val random = Random(7)
    private val seeds = FloatArray(COUNT * SEEDS) { random.nextFloat() }
    private var start = 0L
    private var originX = 0f
    private var originY = 0f

    init {
        isClickable = false
    }

    /** ([x], [y])에서 터집니다. 창 안의 비율로 받습니다. */
    fun burst(x: Float, y: Float) {
        originX = x
        originY = y
        start = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (start == 0L) return
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        if (t >= LIFE_SEC) {
            start = 0L
            return
        }
        val unit = width / 170f
        paint.alpha = (((LIFE_SEC - t) / FADE_SEC).coerceIn(0f, 1f) * 255).toInt()
        for (i in 0 until COUNT) {
            val a = seeds[i * SEEDS]
            val b = seeds[i * SEEDS + 1]
            val c = seeds[i * SEEDS + 2]
            val age = t - seeds[i * SEEDS + 3] * 0.2f
            if (age <= 0f) continue
            // 처음에 부풀듯 퍼지고, 깃털처럼 공기에 걸려 천천히 내려옵니다.
            val slow = 1f - 1f / (1f + age * 2.6f)
            val x = width * originX + (a - 0.5f) * 150f * unit * slow + sin(age * (1.6f + c * 2f) + a * 6f) * unit * 5f
            val y = height * originY - (34f + b * 44f) * unit * slow + age * age * unit * 5f + age * unit * 7f
            val feather = feathers[i % feathers.size]
            val w = (5f + c * 4f) * unit
            val h = w * feather.height / feather.width
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(sin(age * (1.4f + b * 1.6f) + a * 6f) * 60f + a * 360f)
            rect.set(-w / 2f, -h / 2f, w / 2f, h / 2f)
            canvas.drawBitmap(feather, null, rect, paint)
            canvas.restore()
        }
        postInvalidateOnAnimation()
    }

    private companion object {
        const val COUNT = 24
        const val SEEDS = 4
        const val LIFE_SEC = 4.2f
        const val FADE_SEC = 1.2f
    }
}

/** 카드판의 네 칸. 홈 선반의 판과 가족 화면의 판이 같은 자리를 씁니다. */
object SeatBoard {
    /** 판의 네 칸. 위가 앞, 아래가 뒤입니다. 칸이 넷뿐이라 가운데 뒷자리만 왼쪽 위에 둡니다. */
    val SEATS = listOf(SeatSlot.REAR_CENTER, SeatSlot.FRONT_RIGHT, SeatSlot.REAR_LEFT, SeatSlot.REAR_RIGHT)

    /**
     * 카드의 가운데. 그림 안의 비율입니다. `prop_board` 그림에서 카드 테두리를 재서 잡았습니다.
     * 카드마다 조금씩 기울어 높이가 다릅니다. 핀은 카드 윗변 바로 위에 있습니다.
     */
    val POCKETS = arrayOf(floatArrayOf(0.324f, 0.4f), floatArrayOf(0.673f, 0.425f), floatArrayOf(0.326f, 0.707f), floatArrayOf(0.675f, 0.726f))

    /** 카드의 왼쪽, 위, 오른쪽, 아래. 그림(341×327) 안의 비율입니다. 색 종이 테두리를 재서 잡았습니다. */
    val CARDS = arrayOf(
        floatArrayOf(0.155f, 0.266f, 0.46f, 0.544f),
        floatArrayOf(0.493f, 0.275f, 0.812f, 0.575f),
        floatArrayOf(0.155f, 0.59f, 0.463f, 0.856f),
        floatArrayOf(0.487f, 0.602f, 0.809f, 0.881f),
    )
}

/** 이미 보여 준 것. 화면에서만 쓰는 값이라 원장과 따로 둡니다. */
class Seen(context: Context) {
    private val store = context.getSharedPreferences("seen", Context.MODE_PRIVATE)

    var shelf: Int
        get() = store.getInt("shelf", 0)
        set(value) = store.edit().putInt("shelf", value).apply()

    /** 크게 펼쳐 보여 준 순간. 앨범에 들어왔어도 아직 못 본 것은 다음 도착 때 펼칩니다. */
    var moments: Set<String>
        get() = store.getStringSet("moments", emptySet()).orEmpty()
        set(value) = store.edit().putStringSet("moments", value).apply()

    /** 처음 켠 날 세 마디를 다 보았거나 다른 화면을 열어 끝냈는지. 경고등으로 멈춘 때는 적지 않아 다시 나옵니다. */
    var intro: Boolean
        get() = store.getBoolean("intro", false)
        set(value) = store.edit().putBoolean("intro", value).apply()

    /**
     * 속도 허락을 물어 허락이나 거절 답을 받았는지. 답 없이 창을 닫으면 false로 되돌려 다음에 다시 묻습니다.
     * 두 번 거절하면 시스템이 더 묻지 않으므로 그 뒤로는 이유 쪽지도 띄우지 않습니다.
     */
    var speedAsked: Boolean
        get() = store.getBoolean("speed_asked", false)
        set(value) = store.edit().putBoolean("speed_asked", value).apply()

    /** 알림 허락을 한 번 물었는지. 첫 도착 뒤 집에서 한 번만 묻습니다. 그 뒤 거절은 시스템 설정에서 바꿉니다. */
    var notifyAsked: Boolean
        get() = store.getBoolean("notify_asked", false)
        set(value) = store.edit().putBoolean("notify_asked", value).apply()
}

/** 시간대의 빛을 소품에 입힙니다. */
fun ambientFilter(color: Int): ColorFilter? = if (color == 0xFFFFFF) null else LightingColorFilter(color, 0)
