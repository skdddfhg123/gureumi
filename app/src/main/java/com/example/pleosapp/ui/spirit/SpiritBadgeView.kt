package com.example.pleosapp.ui.spirit

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.example.pleosapp.engine.SpiritPreset
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 가족 정령을 그리는 작은 그림.
 * 여러 개가 함께 떠 있어도 CPU를 쓰지 않도록 움직이지 않고, 상태가 바뀔 때만 다시 그립니다.
 */
class SpiritBadgeView(context: Context) : View(context) {

    private var preset = SpiritPreset.SOM
    private var awake = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val asleepFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(SLEEP_SATURATION) })
    private val dest = RectF()
    private val reduced = ReducedSlot()

    fun show(preset: SpiritPreset, awake: Boolean) {
        if (this.preset == preset && this.awake == awake) return
        this.preset = preset
        this.awake = awake
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sprite = SpiritSprites.bitmap(context, SpiritSprites.family(preset, awake))
        val area = SpiritSprites.painted(sprite)
        val scale = min(width.toFloat() / area.width(), height.toFloat() / area.height())
        val w = area.width() * scale
        val h = area.height() * scale
        dest.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        paint.colorFilter = if (awake) null else asleepFilter
        canvas.drawBitmap(reduced.of(sprite, area, h.roundToInt()), null, dest, paint)
    }

    private companion object {
        const val SLEEP_SATURATION = 0.72f
    }
}
