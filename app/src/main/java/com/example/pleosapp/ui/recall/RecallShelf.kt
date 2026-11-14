package com.example.pleosapp.ui.recall

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.pleosapp.engine.PetRepository
import com.example.pleosapp.engine.RecallBook
import com.example.pleosapp.engine.RecallId
import com.example.pleosapp.engine.TimeTogether
import com.example.pleosapp.ui.game.GameStyle
import com.example.pleosapp.ui.game.GameStyle.paperText
import com.example.pleosapp.ui.game.GameStyle.sizeTo
import com.example.pleosapp.ui.game.PaperDrawable
import com.example.pleosapp.ui.game.PaperNote
import com.example.pleosapp.ui.game.TextRules
import com.example.pleosapp.ui.spirit.HeroArts
import com.example.pleosapp.ui.spirit.HeroPose
import com.example.pleosapp.ui.spirit.SpiritSprites
import kotlin.math.max
import kotlin.math.min

/** 앨범에 꽂힌 그림책과 회상 설정. 고비에 닿으면 저절로 늘어납니다. */
object RecallShelf {

    private const val COLUMNS = 2
    private const val THUMB_SAMPLE = 6

    /** 앨범 오른쪽에 붙입니다. [note]는 설정을 띄울 쪽지입니다. */
    fun addTo(page: LinearLayout, activity: Activity, unit: Float, width: Int, note: RecallSettingsNote) {
        val together = PetRepository.state.together
        if (together.days == 0) return
        val prefs = RecallPrefs(activity)
        page.addView(activity.paperText("함께한 날 ${together.days}일", bold = true).apply {
            sizeTo(3f * unit, TextRules.MAIN)
            setPadding(0, (2.4f * unit).toInt(), 0, (0.8f * unit).toInt())
        })
        if (!prefs.off) {
            val cell = width / COLUMNS
            val tiles = ArrayList<Pair<RecallTile, Int>>()
            // 칸을 빠르게 두 번 누르면 그림책이 두 번 열렸습니다. 한 번 연 뒤에는 책이 다시 앞에 올 때까지 막습니다.
            val gate = OpenGate()
            TimeTogether.shelf(together).chunked(COLUMNS).forEach { row ->
                val strip = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                row.forEach { id ->
                    val title = if (id is RecallId.Grown) "${RecallBook.title(id)} · ${id.stage.title}" else RecallBook.title(id)
                    val tile = RecallTile(activity, title, unit).apply {
                        contentDescription = title
                        this.gate = gate
                        setOnClickListener { if (gate.pass()) activity.startActivity(RecallActivity.intent(activity, id)) }
                        // 자람 그림책의 표지는 그때 크기의 구름이입니다.
                        if (id is RecallId.Grown) figure(HeroArts.of(activity, HeroPose.SIT, null).base, TimeTogether.heroScale(id.stage))
                    }
                    tiles += tile to RecallArt.of(activity, RecallBook.photoArt(id))
                    strip.addView(tile, LinearLayout.LayoutParams(cell, cell * 3 / 4))
                }
                page.addView(strip)
            }
            // 큰 그림을 줄여 읽는 일은 뒤에서 합니다. 앨범을 여는 손길을 막지 않습니다.
            Thread {
                tiles.forEach { (tile, art) ->
                    val loaded = SpiritSprites.thumbnail(activity, art, THUMB_SAMPLE)
                    tile.post { tile.show(loaded) }
                }
            }.start()
        }
        page.addView(activity.pill(TimeTogether.HIDE_TITLE, unit) { note.show(unit) },LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (6.4f * unit).toInt()).apply {
            topMargin = (1.2f * unit).toInt()
        })
    }

    private fun Context.pill(text: String, unit: Float, onClick: () -> Unit): TextView = paperText(text, bold = true).apply {
        gravity = Gravity.CENTER
        background = PaperDrawable(context, 1.6f * unit)
        sizeTo(2.8f * unit, TextRules.MAIN)
        isClickable = true
        setPadding((2.4f * unit).toInt(), 0, (2.4f * unit).toInt(), 0)
        setOnClickListener { onClick() }
    }
}

/** 그림책 한 권의 표지. 그림 아래 제목을 씁니다. */
private class RecallTile(context: Context, private val title: String, private val unit: Float) : View(context) {

    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GameStyle.INK
        typeface = GameStyle.TITLE
        textAlign = Paint.Align.CENTER
    }
    private val strip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GameStyle.PAPER }
    private val rect = RectF()
    private val source = Rect()
    private var art: Bitmap? = null
    private var hero: Bitmap? = null
    private var heroScale = 1f

    /** 같은 선반 칸들이 함께 쓰는 막이. 그림책을 덮고 책이 다시 앞에 오면 풉니다. */
    var gate: OpenGate? = null

    init {
        isClickable = true
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) gate?.reset()
    }

    fun show(loaded: Bitmap) {
        art = loaded
        invalidate()
    }

    fun figure(bitmap: Bitmap, scale: Float) {
        hero = bitmap
        heroScale = scale
    }

    override fun onDraw(canvas: Canvas) {
        val pad = min(width, height) * 0.06f
        fitLabel(width - pad * 4f)
        val band = max(5f * unit, label.textSize * 1.5f)
        rect.set(pad, pad, width - pad, height - pad)
        art?.let { picture ->
            val scale = max(rect.width() / picture.width, rect.height() / picture.height)
            val w = (rect.width() / scale).toInt()
            val h = (rect.height() / scale).toInt()
            source.set((picture.width - w) / 2, (picture.height - h) / 2, (picture.width + w) / 2, (picture.height + h) / 2)
            canvas.drawBitmap(picture, source, rect, image)
        }
        hero?.let { figure ->
            val h = (rect.height() - band) * 0.9f * heroScale
            val w = h * figure.width / figure.height
            val feet = rect.bottom - band
            rect.set(width / 2f - w / 2f, feet - h, width / 2f + w / 2f, feet)
            canvas.drawBitmap(figure, null, rect, image)
        }
        rect.set(pad, height - pad - band, width - pad, height - pad)
        canvas.drawRect(rect, strip)
        canvas.drawText(title, width / 2f, rect.centerY() + label.textSize * 0.36f, label)
    }

    /** 그림책 이름은 주 글자 크기입니다. 좁은 칸에서 넘치면 줄이되 가장 작은 글자 아래로는 줄이지 않습니다(2-8). */
    private fun fitLabel(room: Float) {
        val density = resources.displayMetrics.scaledDensity
        label.textSize = max(2.8f * unit, TextRules.MAIN * density)
        val needed = label.measureText(title)
        if (needed > room) label.textSize = max(TextRules.SMALL * density, label.textSize * room / needed)
    }
}

/**
 * 회상 설정. 가족이 바뀌면 회상이 아플 수 있어서 그림책 안에서 바로 고릅니다.
 * 끄면 그림책 초대, 한 줄 회상, 벽의 사진을 내립니다. 구름이 크기와 화분과 계절은 집의 일이라 그대로 둡니다.
 */
class RecallSettingsNote(context: Context, private val prefs: RecallPrefs) : PaperNote(context) {

    fun show(unit: Float) {
        card.removeAllViews()
        card.gravity = Gravity.START
        card.addView(heading(TimeTogether.HIDE_TITLE, unit).apply { setPadding(0, 0, 0, (1.4f * unit).toInt()) })
        card.addView(row("날짜 숨기기", "그림책에서 날짜를 빼 둘게.", prefs.hideDates, unit) {
            prefs.hideDates = !prefs.hideDates
            show(unit)
        })
        card.addView(row("회상 끄기", "그림책과 벽의 사진을 내려 둘게.", prefs.off, unit) {
            prefs.off = !prefs.off
            show(unit)
        })
        card.addView(context.paperText("앨범에서 언제든 바꿀 수 있어.", color = GameStyle.INK_SOFT).apply {
            sizeTo(2.8f * unit, TextRules.SMALL)
            setPadding(0, (1.4f * unit).toInt(), 0, 0)
        })
        if (visibility != View.VISIBLE) {
            val room = (parent as? View)?.width ?: width
            open(unit, min((room * 0.78f).toInt(), (104f * unit).toInt()))
        }
    }

    private fun row(title: String, detail: String, on: Boolean, unit: Float, onToggle: () -> Unit): View {
        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(context.paperText(title, bold = true).apply { sizeTo(3.4f * unit, TextRules.MAIN) })
            addView(context.paperText(detail, color = GameStyle.INK_SOFT).apply { sizeTo(2.8f * unit, TextRules.SMALL) })
        }
        // 켜짐은 산호색 종이로 알립니다. 산호색 글자는 종이 바탕에서 대비가 2.3:1이라 읽히지 않습니다.
        val toggle = context.paperText(if (on) "켜짐" else "꺼짐", bold = true, color = if (on) GameStyle.INK else GameStyle.INK_SOFT).apply {
            gravity = Gravity.CENTER
            background = PaperDrawable(context, 1.6f * unit).apply { if (on) colorFilter = LightingColorFilter(ON_PAPER, 0) }
            sizeTo(3f * unit, TextRules.MAIN)
        }
        val toggleWidth = max(13f * unit, toggle.paint.measureText("꺼짐") + 4f * unit).toInt()
        val toggleHeight = max(7f * unit, toggle.textSize * 1.9f).toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { onToggle() }
            setPadding(0, (1f * unit).toInt(), 0, (1f * unit).toInt())
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle, LinearLayout.LayoutParams(toggleWidth, toggleHeight))
        }
    }

    internal companion object {
        /** 켜짐 단추 종이에 곱하는 산호빛. 글자(INK)와 대비 7.7:1입니다. */
        const val ON_PAPER = 0xFFD9C4
    }
}
