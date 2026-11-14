package com.example.pleosapp.ui.game

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Window
import com.example.pleosapp.R
import com.example.pleosapp.engine.DayPart
import kotlin.math.max

/**
 * 첫 장면이 나오기 전에 창을 채우는 흐린 집 그림입니다. scripts/build_preview.py가 만듭니다.
 * 첫 프레임은 오래 걸릴 수 있어서 빈 화면 대신 집이 먼저 보이게 합니다.
 * 창 배경은 프레임마다 다시 그려지므로 장면이 나오면 한 색으로 돌립니다.
 */
object ScenePreview {
    fun show(window: Window, part: DayPart) {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val art = when (part) {
            DayPart.MORNING -> R.drawable.preview_morning
            DayPart.DAY -> R.drawable.preview_day
            DayPart.EVENING -> R.drawable.preview_evening
            DayPart.NIGHT -> R.drawable.preview_night
        }
        window.setBackgroundDrawable(Filled(BitmapFactory.decodeResource(window.context.resources, art, options)))
    }

    fun clear(window: Window) = window.setBackgroundDrawable(ColorDrawable(GameStyle.INTERIOR))

    /** 비율을 지키며 창을 가득 채웁니다. 넘치는 쪽은 가운데를 남깁니다. */
    private class Filled(private val art: Bitmap) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val fit = Matrix()

        override fun onBoundsChange(bounds: Rect) {
            val scale = max(bounds.width() / art.width.toFloat(), bounds.height() / art.height.toFloat())
            fit.setScale(scale, scale)
            fit.postTranslate(bounds.left + (bounds.width() - art.width * scale) / 2f, bounds.top + (bounds.height() - art.height * scale) / 2f)
        }

        override fun draw(canvas: Canvas) = canvas.drawBitmap(art, fit, paint)

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Drawable 기본 계약")
        override fun getOpacity() = PixelFormat.OPAQUE
    }
}
