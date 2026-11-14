package com.example.pleosapp.ui.river

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.example.pleosapp.R
import com.example.pleosapp.engine.DayPart
import com.example.pleosapp.engine.Guest
import com.example.pleosapp.engine.ReturnKind
import com.example.pleosapp.engine.SceneState
import com.example.pleosapp.engine.Season
import com.example.pleosapp.ui.game.HomeScene
import com.example.pleosapp.ui.game.MotionTicker
import com.example.pleosapp.ui.game.NookLight
import com.example.pleosapp.ui.game.ambientFilter
import com.example.pleosapp.ui.spirit.ReducedSlot
import com.example.pleosapp.ui.spirit.SpiritSprites
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 강가 장면. 배경, 물건 칸 넷, 손님이 떨어뜨린 흔적을 그립니다. 구름이와 아기, 손님은 무대의 다른 뷰가 그립니다.
 * 배경은 시간과 계절에 맞는 그림이고([RiverScene.backdrop]), 놓인 물건과 흔적은 집 안과 같은 빛을 입습니다.
 */
class RiverView(context: Context) : View(context) {

    private var backdrop: RiverScene.Look? = null
    private var background: Bitmap? = null
    private var ambient = NO_TINT
    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** 배경 위에 놓인 물건과 흔적. 배경 그림은 이미 그 시간의 빛이라 이 붓만 빛을 입습니다. */
    private val lit = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, SHADOW, 0, Shader.TileMode.CLAMP)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val reducedItems = Array(RiverScene.SLOTS.size) { ReducedSlot() }
    private val reducedTraces = Array(RiverScene.TRACES.size) { ReducedSlot() }
    private var items: List<ReturnKind?> = List(RiverScene.SLOTS.size) { null }
    private var traces: List<Guest> = emptyList()
    private var viewScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    /** 사다리 보상 돛배(R-124)를 받았는지. 받으면 늘 물 위에 있습니다. */
    var boat = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private var night = false
    private val boatFrames: List<Bitmap> by lazy { SpiritSprites.frames(context, "river_boat") }
    private val boatMoored: Bitmap? by lazy {
        SpiritSprites.named(context, "river_boat_moored").takeIf { it != 0 }?.let { SpiritSprites.bitmap(context, it) }
    }
    private val mirror = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = REFLECTION_ALPHA }

    /** 돛배가 보이는지. 돛이 부풀고 배가 오르내려서 이때만 강가를 가끔 다시 그립니다. */
    val boatShown: Boolean get() = boat && boatFrames.isNotEmpty()

    /** 돛대 꼭대기의 높이(강가 그림 좌표). 말풍선을 이보다 위에 둡니다. 배가 없으면 null입니다. */
    val boatTop: Float?
        get() {
            val frame = boatFrames.firstOrNull()?.takeIf { boat } ?: return null
            return RiverScene.BOAT_WATER - RiverScene.BOAT_LENGTH * frame.height / frame.width * BOAT_WATERLINE
        }

    /** 칸을 누르면 그 칸 번호를 부릅니다. */
    var onSlot: ((Int) -> Unit)? = null

    /** 흔적을 누르면 그 흔적을 남긴 손님을 부릅니다. */
    var onTrace: ((Guest) -> Unit)? = null

    init {
        contentDescription = "강가"
    }

    fun show(nextItems: List<ReturnKind?>, nextTraces: List<Guest>) {
        items = nextItems
        traces = nextTraces.take(RiverScene.TRACES.size)
        invalidate()
    }

    /** 시간, 날씨, 계절에 맞춰 배경을 고르고 물건에 빛을 입힙니다. 바뀌었을 때만 참입니다. */
    fun light(state: SceneState, season: Season): Boolean {
        val look = RiverScene.look(state, season)
        val color = NookLight.ambient(state)
        if (look == backdrop && color == ambient) return false
        if (look.art != backdrop?.art) {
            val resource = SpiritSprites.named(context, look.art).takeIf { it != 0 } ?: R.drawable.place_river
            background = SpiritSprites.bitmap(context, resource)
        }
        backdrop = look
        // 계절 그림에 씌운 저녁과 밤의 빛. 그 시간 그림을 그대로 쓰면 씌우지 않습니다.
        image.colorFilter = look.tint?.let(::ambientFilter)
        ambient = color
        lit.colorFilter = ambientFilter(color)
        mirror.colorFilter = lit.colorFilter
        night = state.part == DayPart.NIGHT
        invalidate()
        return true
    }

    val sceneScale: Float get() = viewScale

    fun viewX(x: Float): Float = offsetX + x * viewScale

    fun viewY(y: Float): Float = offsetY + y * viewScale

    /** 홈처럼 창을 꽉 채우고 가운데를 맞춥니다. */
    fun fit(w: Int, h: Int) {
        viewScale = max(w / RiverScene.WIDTH, h / RiverScene.HEIGHT)
        offsetX = (w - RiverScene.WIDTH * viewScale) / 2f
        offsetY = (h - RiverScene.HEIGHT * viewScale) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        background?.let {
            rect.set(viewX(0f), viewY(0f), viewX(RiverScene.WIDTH), viewY(RiverScene.HEIGHT))
            canvas.drawBitmap(it, null, rect, image)
        }
        if (boatShown) drawBoat(canvas)
        RiverScene.SLOTS.forEachIndexed { index, spot ->
            val kind = items.getOrNull(index)
            if (kind == null) drawEmpty(canvas, spot) else drawThing(canvas, reducedItems[index], "return_${kind.id}", spot)
        }
        traces.forEachIndexed { index, guest -> drawThing(canvas, reducedTraces[index], "trace_${guest.id}", RiverScene.TRACES[index]) }
    }

    /**
     * 구름이가 띄운 돛배. 낮에는 돛이 1-2-3-2로 숨 쉬듯 부풀고 배가 물결에 아주 조금 오르내립니다.
     * 밤에는 돛을 접고 말뚝에 매어 둡니다. 물에 비친 모습은 물선 아래로 뒤집어 옅게 그립니다(R-124 5번: 앱이 그림).
     */
    private fun drawBoat(canvas: Canvas) {
        val seconds = MotionTicker.seconds(SystemClock.uptimeMillis())
        val moored = if (night) boatMoored else null
        val frame = moored ?: boatFrames[SAIL_TURN[(seconds / SAIL_FRAME_SEC).toInt() % SAIL_TURN.size].coerceAtMost(boatFrames.lastIndex)]
        val w = RiverScene.BOAT_LENGTH * viewScale
        val h = w * frame.height / frame.width
        val bob = if (moored != null) 0f else sin(seconds * BOB_SPEED) * BOB * viewScale
        val water = viewY(RiverScene.BOAT_WATER) + bob
        val top = water - h * BOAT_WATERLINE
        val left = viewX(RiverScene.BOAT_X) - w * BOAT_HULL_CENTER
        rect.set(left, top, left + w, top + h)
        canvas.save()
        canvas.clipRect(left - w, water, left + w * 2f, water + h)
        canvas.scale(1f, -REFLECTION_SQUASH, 0f, water)
        canvas.drawBitmap(frame, null, rect, mirror)
        canvas.restore()
        // 그림에는 물이 없어 선체 바닥까지 그려져 있습니다. 물선 아래는 잘라 물에 잠긴 것처럼 보입니다.
        canvas.save()
        canvas.clipRect(left - w, top - h, left + w * 2f, water + SINK * viewScale)
        canvas.drawBitmap(frame, null, rect, lit)
        canvas.restore()
    }

    /** 바닥에 놓인 작은 그림. 미리 줄여 두고, 바닥에 닿는 폭만큼 그늘을 깝니다. 그림이 없으면 건너뜁니다. */
    private fun drawThing(canvas: Canvas, reduced: ReducedSlot, name: String, spot: HomeScene.Spot) {
        val resource = SpiritSprites.named(context, name)
        if (resource == 0) return
        val source = SpiritSprites.bitmap(context, resource)
        val h = spot.height * viewScale
        val w = h * source.width / source.height
        val cx = viewX(spot.x)
        val cy = viewY(spot.bottom)
        val foot = SpiritSprites.footprint(source)
        val reach = w * foot[1] * SHADOW_REACH
        canvas.save()
        canvas.translate(cx + (foot[0] - 0.5f) * w, cy - 1f)
        canvas.scale(reach, reach * SHADOW_FLAT)
        canvas.drawCircle(0f, 0f, 1f, shadow)
        canvas.restore()
        rect.set(cx - w / 2f, cy - h, cx + w / 2f, cy)
        canvas.drawBitmap(reduced.of(source, null, h.roundToInt()), null, rect, lit)
    }

    /**
     * 빈칸. 무언가 놓을 수 있는 자리로 보이게 받침 윗면에 점선 고리와 더하기를 눕혀 그립니다(장면 규칙 2-4의 4).
     * 고리는 시간의 빛을 입히지 않습니다. 밤에도 밝은 선과 그 밑의 어두운 선으로 대비를 지킵니다.
     */
    private fun drawEmpty(canvas: Canvas, spot: HomeScene.Spot) {
        val r = spot.height * viewScale * 0.62f
        val cx = viewX(spot.x)
        val cy = viewY(spot.bottom) - r * LYING * 0.9f
        canvas.save()
        canvas.scale(1f, LYING, cx, cy)
        ring.strokeWidth = r * 0.1f
        ring.pathEffect = DashPathEffect(floatArrayOf(r * 0.28f, r * 0.2f), 0f)
        ring.color = HINT_DARK
        canvas.drawCircle(cx, cy + r * 0.08f, r, ring)
        ring.color = HINT_LIGHT
        canvas.drawCircle(cx, cy, r, ring)
        ring.pathEffect = null
        ring.color = HINT_DARK
        drawPlus(canvas, cx, cy + r * 0.08f, r)
        ring.color = HINT_LIGHT
        drawPlus(canvas, cx, cy, r)
        canvas.restore()
    }

    private fun drawPlus(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawLine(cx - r * 0.38f, cy, cx + r * 0.38f, cy, ring)
        canvas.drawLine(cx, cy - r * 0.38f, cx, cy + r * 0.38f, ring)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) return traceAt(event.x, event.y) != null || slotAt(event.x, event.y) != null
        if (event.actionMasked != MotionEvent.ACTION_UP) return super.onTouchEvent(event)
        // 흔적은 칸 앞 풀밭에 떨어져 있어서 칸보다 먼저 봅니다.
        traceAt(event.x, event.y)?.let { guest ->
            performClick()
            onTrace?.invoke(guest)
            return true
        }
        val index = slotAt(event.x, event.y) ?: return false
        performClick()
        onSlot?.invoke(index)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun traceAt(x: Float, y: Float): Guest? {
        val index = traces.indices.minByOrNull { distance(RiverScene.TRACES[it], x, y) } ?: return null
        val spot = RiverScene.TRACES[index]
        return traces[index].takeIf { distance(spot, x, y) <= spot.height * viewScale * REACH * 1.4f }
    }

    private fun slotAt(x: Float, y: Float): Int? {
        val index = RiverScene.SLOTS.indices.minByOrNull { distance(RiverScene.SLOTS[it], x, y) } ?: return null
        val spot = RiverScene.SLOTS[index]
        return index.takeIf { distance(spot, x, y) <= spot.height * viewScale * REACH }
    }

    private fun distance(spot: HomeScene.Spot, x: Float, y: Float): Float =
        hypot(x - viewX(spot.x), y - (viewY(spot.bottom) - spot.height * viewScale * 0.45f))

    private companion object {
        const val NO_TINT = 0xFFFFFF
        const val SHADOW = 0x803A241C.toInt()
        const val SHADOW_REACH = 0.58f
        const val SHADOW_FLAT = 0.18f
        const val REACH = 1.1f
        const val LYING = 0.36f // 바닥에 눕힌 고리의 높이 대 폭
        const val HINT_LIGHT = 0xF2FBF1E1.toInt()
        const val HINT_DARK = 0x993A241C.toInt()

        /** 돛이 부푸는 차례와 한 장의 시간. 잔잔한 강가라 천천히 넘깁니다(R-124 검수). */
        val SAIL_TURN = intArrayOf(0, 1, 2, 1)
        const val SAIL_FRAME_SEC = 1.4f
        const val BOB = 1.5f // 오르내리는 폭(설계 픽셀)
        const val BOB_SPEED = 0.9f
        const val BOAT_WATERLINE = 0.95f // 그림 위에서 물에 닿는 선까지(그림 키에 대한 비율). 아래 5%가 물에 잠김
        const val BOAT_HULL_CENTER = 0.52f // 그림 왼쪽에서 선체 가운데까지(그림 폭에 대한 비율). 돛이 오른쪽으로 나와 그림 가운데와 다릅니다
        const val SINK = 1f // 물선 아래로 남기는 폭(설계 픽셀). 물선이 칼로 자른 듯 보이지 않게 합니다
        const val REFLECTION_ALPHA = 70
        const val REFLECTION_SQUASH = 0.6f
    }
}
