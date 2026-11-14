package com.example.pleosapp.ui.family

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.pleosapp.engine.SpiritPreset
import com.example.pleosapp.ui.CardLayout.dp
import com.example.pleosapp.ui.CardLayout.label
import com.example.pleosapp.ui.spirit.SpiritBadgeView

/** 가족 정령 화면이 함께 쓰는 칸과 버튼. 차 안에서 누르기 쉽도록 터치 영역을 넉넉하게 둡니다. */
object FamilyTiles {
    private const val PILL = "#F3E3CB"
    private const val TILE = "#FFF9EE"
    private const val TILE_DISABLED = "#EFE3D2"
    private const val TILE_LINE = "#E3D3C0"

    fun Context.spiritBadge(preset: SpiritPreset, awake: Boolean, sizeDp: Int): SpiritBadgeView =
        SpiritBadgeView(this).apply {
            show(preset, awake)
            contentDescription = "${preset.displayName} ${if (awake) "깨어 있음" else "잠듦"}"
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp * 5 / 6))
        }

    /** 작은 정령 아래에 이름을 적은 칸. [onClick]이 없으면 표시만 합니다. */
    fun Context.spiritTile(
        preset: SpiritPreset,
        caption: String,
        awake: Boolean,
        sizeDp: Int,
        captionSp: Float = 20f,
        onClick: (() -> Unit)? = null,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
        addView(spiritBadge(preset, awake, sizeDp))
        addView(label(caption, captionSp).apply { gravity = Gravity.CENTER })
        if (onClick != null) {
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    fun Context.pillButton(text: String, onClick: () -> Unit): TextView = label(text, 24f).apply {
        gravity = Gravity.CENTER
        minHeight = dp(64)
        setPadding(dp(24), dp(12), dp(24), dp(12))
        background = rounded(PILL, radiusDp = 20)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** 고르는 화면의 큰 칸. 고를 수 없는 자리는 흐리게 그리고 눌리지 않습니다. */
    fun Context.pickTile(content: View, enabled: Boolean, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(150)
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = rounded(if (enabled) TILE else TILE_DISABLED, radiusDp = 16, line = TILE_LINE)
            addView(content)
            if (enabled) {
                isClickable = true
                setOnClickListener { onClick() }
            }
        }

    private fun Context.rounded(color: String, radiusDp: Int, line: String? = null) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(Color.parseColor(color))
        if (line != null) setStroke(dp(2), Color.parseColor(line))
    }
}
