package com.example.pleosapp.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.pleosapp.ui.game.GameStyle

/** 화면 공용 스타일. Pleos 앱 창은 차종과 분할 상태에 따라 크기·방향이 바뀌므로 픽셀 값을 쓰지 않습니다. */
object CardLayout {
    const val BACKGROUND = "#FBF1E1"
    const val TEXT = "#4A372E"
    const val MUTED = "#846452"
    const val FAINT = "#A89484"
    const val DIVIDER = "#E3D3C0"

    fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    fun Context.label(value: String, sizeSp: Float, bold: Boolean = false, color: String = TEXT) =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(Color.parseColor(color))
            typeface = if (bold) GameStyle.TITLE else GameStyle.BODY
            setLineSpacing(0f, 1.2f)
        }

    fun Context.divider() = View(this).apply {
        setBackgroundColor(Color.parseColor(DIVIDER))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2)).apply {
            topMargin = dp(16)
            bottomMargin = dp(16)
        }
    }

    /** 가로 창은 좌우 4:6, 세로 창은 상하 4:6으로 나눕니다. */
    fun arrange(root: LinearLayout, hero: View, body: View) {
        val portrait = root.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        root.orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val params = { weight: Float ->
            if (portrait) LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, weight)
            else LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
        }
        hero.layoutParams = params(0.4f)
        body.layoutParams = params(0.6f)
    }
}
