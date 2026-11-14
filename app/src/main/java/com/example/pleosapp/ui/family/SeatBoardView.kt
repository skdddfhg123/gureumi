package com.example.pleosapp.ui.family

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import com.example.pleosapp.R
import com.example.pleosapp.engine.FamilyState
import com.example.pleosapp.engine.SpiritPreset
import com.example.pleosapp.ui.game.GameStyle
import com.example.pleosapp.ui.game.SeatBoard
import com.example.pleosapp.ui.game.TextRules
import com.example.pleosapp.ui.spirit.ReducedSlot
import com.example.pleosapp.ui.spirit.SpiritSprites
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 가족 카드판. 카드 한 장이 아기 사진 한 장입니다. 칸을 누르면 그 아기를 고르고, 빈칸을 누르면 새 아기를 들입니다.
 * 칸은 들인 순서입니다. 자리 순서로 두면 탈 때마다 자리가 바뀌어 아기 칸이 뒤섞입니다.
 * 빈칸은 점선으로 비워 둡니다. 숫자나 목록이 없어야 가족이 설정 항목처럼 보이지 않습니다.
 */
class SeatBoardView(context: Context) : View(context) {

    private val board = SpiritSprites.bitmap(context, R.drawable.prop_board)
    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = GameStyle.CORAL
    }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = GameStyle.INK_SOFT
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GameStyle.INK
        typeface = GameStyle.TITLE
        textAlign = Paint.Align.CENTER
    }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, SHADE, 0, Shader.TileMode.CLAMP)
    }
    private val rect = RectF()
    private val area = RectF()
    private val card = RectF()
    private val stickers = Array(SeatBoard.CARDS.size) { ReducedSlot() }
    private var family = FamilyState()
    private var selected: SpiritPreset? = null
    private var pickingFree = false

    /** 칸을 누르면 그 칸 번호(0~3)로 부릅니다. */
    var onCard: ((Int) -> Unit)? = null

    init {
        contentDescription = "가족 카드판"
    }

    /** [pickingFree]면 새 아기를 고르는 중이라 빈칸을 도드라지게 보여 줍니다. */
    fun show(family: FamilyState, selected: SpiritPreset?, pickingFree: Boolean) {
        this.family = family
        this.selected = selected
        this.pickingFree = pickingFree
        invalidate()
    }

    private fun measureBoard() {
        val scale = min(width / board.width.toFloat(), height / board.height.toFloat())
        val w = board.width * scale
        val h = board.height * scale
        area.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    private fun cellX(index: Int) = area.left + area.width() * SeatBoard.POCKETS[index][0]

    private fun cellY(index: Int) = area.top + area.height() * SeatBoard.POCKETS[index][1]

    override fun onDraw(canvas: Canvas) {
        measureBoard()
        canvas.drawBitmap(board, null, area, image)
        SeatBoard.CARDS.indices.forEach { index ->
            cardOf(index)
            val spirit = family.spirits.getOrNull(index)
            if (spirit != null) drawBaby(canvas, index, spirit.preset) else drawEmpty(canvas)
            if (spirit != null && spirit.preset == selected) {
                ring.strokeWidth = card.height() * 0.035f
                canvas.drawRoundRect(card, card.width() * 0.06f, card.width() * 0.06f, ring)
            }
        }
    }

    /** 칸 [index]의 카드 테두리를 [card]에 둡니다. */
    private fun cardOf(index: Int) {
        val c = SeatBoard.CARDS[index]
        card.set(area.left + area.width() * c[0], area.top + area.height() * c[1], area.left + area.width() * c[2], area.top + area.height() * c[3])
    }

    /**
     * 카드 한 장이 아기 사진 한 장입니다. 아기는 카드 안에서 같은 키, 같은 바닥선에 앉고 발밑에 그늘이 집니다.
     * 이름은 바닥선 아래 카드 여백에 적어 그림과 겹치지 않습니다.
     * 이름만 적습니다. 가족은 자리를 바꿔 타서 자리 이름은 틀린 말이 됩니다.
     */
    private fun drawBaby(canvas: Canvas, index: Int, preset: SpiritPreset) {
        val art = SpiritSprites.bitmap(context, SpiritSprites.family(preset, awake = true))
        val h = card.height() * BABY_IN_CARD
        val w = h * art.width / art.height
        val floor = card.top + card.height() * FLOOR_IN_CARD
        val foot = SpiritSprites.footprint(art)
        val left = card.centerX() - foot[0] * w
        val reach = w * foot[1]
        canvas.save()
        canvas.translate(card.centerX(), floor)
        canvas.scale(reach * 0.62f, reach * 0.62f * 0.2f)
        canvas.drawCircle(0f, 0f, 1f, shade)
        canvas.restore()
        rect.set(left, floor - h, left + w, floor)
        canvas.save()
        canvas.clipRect(card)
        canvas.drawBitmap(stickers[index].of(art, null, h.roundToInt()), null, rect, image)
        canvas.restore()
        label.textSize = maxOf(card.height() * NAME_SIZE, TextRules.SMALL * resources.displayMetrics.density)
        canvas.drawText(preset.displayName, card.centerX(), card.top + card.height() * NAME_LINE, label)
    }

    private fun drawEmpty(canvas: Canvas) {
        val cx = card.centerX()
        val cy = card.centerY()
        val size = card.height() * 0.5f
        empty.strokeWidth = size * 0.04f
        empty.pathEffect = DashPathEffect(floatArrayOf(size * 0.12f, size * 0.1f), 0f)
        empty.alpha = if (pickingFree) 255 else EMPTY_ALPHA
        canvas.drawCircle(cx, cy, size * 0.62f, empty)
        empty.pathEffect = null
        canvas.drawLine(cx - size * 0.16f, cy, cx + size * 0.16f, cy, empty)
        canvas.drawLine(cx, cy - size * 0.16f, cx, cy + size * 0.16f, empty)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) return true
        if (event.actionMasked != MotionEvent.ACTION_UP) return super.onTouchEvent(event)
        measureBoard()
        val reach = area.height() * REACH
        val index = SeatBoard.CARDS.indices.minByOrNull { hypot(event.x - cellX(it), event.y - cellY(it)) } ?: return true
        if (hypot(event.x - cellX(index), event.y - cellY(index)) <= reach) {
            performClick()
            onCard?.invoke(index)
        }
        return true
    }

    private companion object {
        // 아기는 카드 키의 55%이고 발이 카드 위에서 62%에 닿습니다. 머리가 핀을 가리지 않습니다.
        // 이름은 발 아래 띠(글자 바닥이 카드 위에서 84%)에 따로 적어, 그림이나 카드 테두리와 겹치지 않습니다.
        const val BABY_IN_CARD = 0.55f
        const val FLOOR_IN_CARD = 0.62f
        const val NAME_SIZE = 0.14f
        const val NAME_LINE = 0.84f
        const val REACH = 0.24f
        const val EMPTY_ALPHA = 130
        const val SHADE = 0x663A241C
    }
}
