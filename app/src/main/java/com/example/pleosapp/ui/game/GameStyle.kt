package com.example.pleosapp.ui.game

import android.content.Context
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LightingColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import com.example.pleosapp.R
import com.example.pleosapp.ui.spirit.SpiritSprites
import java.io.File

/**
 * 화면의 온도를 정하는 값. 순백과 순흑을 쓰지 않고 모든 색에 갈색기를 둡니다.
 * 기준은 docs/concept/tone.md에 있습니다. 크기는 창 크기에서 나온 단위로 받아 픽셀을 고정하지 않습니다.
 */
object GameStyle {
    const val INK = 0xFF4A372E.toInt()

    /** 보조 글자. 종이 바탕과 대비 4.5:1을 넘게 11/4에 조금 진하게 했습니다(이전 0x8A6A58은 4.4:1). */
    const val INK_SOFT = 0xFF846452.toInt()
    const val LINE = 0xFFC9AE96.toInt()
    const val PAPER = 0xFFFBF1E1.toInt()
    const val PAPER_NIGHT = 0xFFD4C4B4.toInt()
    const val FEATHER = 0xFFD99A4E.toInt()
    const val CORAL = 0xFFE58C6B.toInt()

    /** 종이 위 코랄 글자. CORAL은 종이와 대비가 2.3:1이라 글자에는 이 색을 씁니다. */
    const val CORAL_INK = 0xFFA84A2A.toInt()
    const val SAGE = 0xFF8FAE8B.toInt()
    const val MUSTARD = 0xFFE3B45A.toInt()
    const val DUSTY_BLUE = 0xFF8DA9C4.toInt()
    const val ROSE = 0xFFDFA5A0.toInt()
    const val INTERIOR = 0xFF654536.toInt()
    const val DIM = 0xA63A241C.toInt()

    /** 밤에 종이 이름표와 탭을 어둡게 합니다. 밝은 종이만 떠 보이지 않게 합니다. */
    val PAPER_DIM: ColorFilter = LightingColorFilter(TextRules.PAPER_DIM_LIGHT, 0)

    /**
     * 밤에는 화면의 모든 종이 조각이 같은 빛을 입습니다. 홈과 강가가 같은 규칙입니다.
     * 조각마다 따로 입히면 낮처럼 밝은 조각만 튑니다(처음 보는 눈 점검 25, 53).
     */
    fun paperLight(night: Boolean): ColorFilter? = if (night) PAPER_DIM else null
    private const val SERIF_FILE = "/system/fonts/NotoSerifCJK-Regular.ttc"
    private const val SERIF_KOREAN = 1
    private val HANGUL_GAP = Regex("(?<=[가-힣])(?=[가-힣])")

    /** 기기에 있는 한글 명조를 씁니다. 없으면 기본 명조로 돌아갑니다. 글꼴 파일은 앱에 넣지 않습니다. */
    private val SERIF_KR: Typeface = Typeface.Builder(File(SERIF_FILE)).setTtcIndex(SERIF_KOREAN).setFallback("serif").build()

    val TITLE: Typeface = Typeface.create(SERIF_KR, Typeface.BOLD)
    val BODY: Typeface = SERIF_KR

    /** 낱말 가운데서 줄이 바뀌지 않게 합니다. 한글 글자 사이에 보이지 않는 묶음표를 넣습니다. */
    fun keepWords(text: CharSequence): String = HANGUL_GAP.replace(text, "\u2060")

    /**
     * 글자를 창 크기에 맞춥니다. 작은 창에서도 차 안에서 읽히는 크기 아래로는 줄이지 않습니다.
     * 어떤 글자도 [TextRules.SMALL]보다 작지 않습니다(장면 규칙 2-8).
     */
    fun TextView.sizeTo(px: Float, minSp: Float = 16f) {
        val floor = maxOf(minSp, TextRules.SMALL) * resources.displayMetrics.scaledDensity
        setTextSize(TypedValue.COMPLEX_UNIT_PX, maxOf(px, floor))
    }

    fun Context.paperText(value: String = "", bold: Boolean = false, color: Int = INK): TextView =
        TextView(this).apply {
            text = value
            includeFontPadding = false
            setTextColor(color)
            typeface = if (bold) TITLE else BODY
            setLineSpacing(0f, 1.2f)
            gravity = Gravity.CENTER_VERTICAL
        }
}

/**
 * 화면 기준: 글자, 대비, 누를 곳(장면 규칙 2-8). 크기는 dp이고, 에뮬레이터가 160dpi라 1dp가 1px입니다.
 * 안드로이드 클래스를 쓰지 않아 단위 테스트가 그대로 읽습니다.
 */
object TextRules {
    /** 단추와 이름. 차량 앱 지침의 주 글자 32dp입니다. */
    const val MAIN = 32f

    /** 가장 작은 글자. 차량 앱 지침의 보조 글자 24dp입니다. */
    const val SMALL = 24f

    /** 말풍선. 본문은 주 글자보다 크게 둡니다. */
    const val BUBBLE = 36f

    /** 글자와 바탕의 대비 하한. 밤에도 지킵니다. */
    const val CONTRAST = 4.5

    /** 누를 곳의 가장 작은 폭과 높이. 아이 손을 생각해 물건 전체를 누르게 합니다. */
    const val MIN_TOUCH = 76f

    /** 누를 곳끼리 떨어질 거리. */
    const val TOUCH_GAP = 23f

    /** 밤 종이에 곱하는 빛. [GameStyle.PAPER_DIM]이 씁니다. */
    const val PAPER_DIM_LIGHT = 0xC4B8B0

    /** 두 색(0xRRGGBB)의 대비. WCAG 상대 휘도로 잽니다. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** [color]에 [light]를 곱한 색. LightingColorFilter의 곱하기와 같습니다. */
    fun lit(color: Int, light: Int): Int {
        fun channel(shift: Int) = ((color shr shift and 0xFF) * (light shr shift and 0xFF) / 255) shl shift
        return channel(16) or channel(8) or channel(0)
    }

    private fun luminance(color: Int): Double {
        fun linear(shift: Int): Double {
            val c = (color shr shift and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }
}

/** 종이 조각. 테두리는 연한 갈색 한 줄이고, 그림자는 뷰의 elevation이 만듭니다. */
class PaperDrawable(context: Context, private val radius: Float) : Drawable() {

    private val tile = SpiritSprites.bitmap(context, R.drawable.paper_tile)
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = GameStyle.LINE
    }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        line.strokeWidth = (radius * 0.07f).coerceAtLeast(1.5f)
        rect.set(bounds)
        canvas.drawRoundRect(rect, radius, radius, paper)
        rect.inset(line.strokeWidth / 2f, line.strokeWidth / 2f)
        canvas.drawRoundRect(rect, radius, radius, line)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius)
    }

    override fun setAlpha(alpha: Int) {
        paper.alpha = alpha
        invalidateSelf()
    }

    /** 빛이 바뀌면 그 종이를 다시 그립니다. 글자가 그대로인 칩은 이것이 없으면 낮 색으로 남습니다(처음 보는 눈 점검 25, 53). */
    override fun setColorFilter(colorFilter: ColorFilter?) {
        if (paper.colorFilter === colorFilter) return
        paper.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
