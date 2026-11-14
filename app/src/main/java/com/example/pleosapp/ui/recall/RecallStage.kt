package com.example.pleosapp.ui.recall

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import com.example.pleosapp.R
import com.example.pleosapp.engine.Growth
import com.example.pleosapp.engine.Reward
import com.example.pleosapp.engine.TimeTogether
import com.example.pleosapp.ui.game.GameStyle
import com.example.pleosapp.ui.game.MotionTicker
import com.example.pleosapp.ui.game.StageLayout
import com.example.pleosapp.ui.game.TextRules
import com.example.pleosapp.ui.spirit.HeroArts
import com.example.pleosapp.ui.spirit.HeroPose
import com.example.pleosapp.ui.spirit.SpriteSpiritView
import kotlin.math.max
import kotlin.math.min

/**
 * 키 재기 쪽. 펼친 책 위에 지금까지의 구름이를 작은 쪽부터 나란히 세웁니다.
 * 그림은 [HeroArts]로만 찾으므로 외형이 바뀌어도 그대로 돕니다. 지난 모습은 빛이 조금 바랜 색입니다.
 */
class RulerPage(context: Context, ticker: MotionTicker) {

    private val actors = List(Growth.Stage.entries.size) {
        SpriteSpiritView(context).apply { visibility = View.GONE }
    }
    private val marks = RulerMarks(context)

    /** 그림 위에 얹는 겹. 쪽이 키 재기일 때만 보입니다. */
    val layer = StageLayout(context).apply {
        visibility = View.GONE
        addView(marks)
        actors.forEach(::addView)
        onResize = { w, h -> arrange(w, h) }
    }

    private var stages: List<Growth.Stage> = emptyList()
    private var form: Reward? = null
    private var dates: Map<Growth.Stage, String> = emptyMap()

    init {
        ticker.add(*actors.toTypedArray())
    }

    /** [dates]가 비어 있으면 날짜를 쓰지 않습니다. */
    fun show(stage: Growth.Stage, form: Reward?, dates: Map<Growth.Stage, String>) {
        stages = Growth.Stage.entries.take(stage.ordinal + 1)
        this.form = form
        this.dates = dates
        layer.visibility = View.VISIBLE
        arrange(layer.width, layer.height)
        layer.requestLayout()
        actors.getOrNull(stages.lastIndex)?.celebrate()
    }

    fun hide() {
        layer.visibility = View.GONE
    }

    private fun arrange(w: Int, h: Int) {
        if (w == 0 || h == 0 || stages.isEmpty()) return
        val context = layer.context
        val scale = max(w / SPREAD_W, h / SPREAD_H)
        val offsetX = (w - SPREAD_W * scale) / 2f
        val offsetY = (h - SPREAD_H * scale) / 2f
        val spots = spotsFor(stages.size)
        val tallest = (if (stages.size > 3) TALLEST_MANY else TALLEST) * scale
        val rows = ArrayList<RulerMarks.Row>()
        actors.forEachIndexed { index, actor ->
            val stage = stages.getOrNull(index)
            actor.visibility = if (stage == null) View.GONE else View.VISIBLE
            if (stage == null) return@forEachIndexed
            val current = index == stages.lastIndex
            val art = HeroArts.of(context, HeroPose.SIT, form.takeIf { current })
            val height = tallest * TimeTogether.heroScale(stage)
            actor.setArt(art)
            actor.setAmbient(if (current) null else FADED)
            actor.unit = height / art.base.height
            val room = (tallest * ACTOR_ROOM).toInt()
            val x = offsetX + spots[index] * scale
            val feet = offsetY + BASELINE * scale
            layer.place(actor, (x - room / 2f).toInt(), (feet - room).toInt(), room, room)
            rows += RulerMarks.Row(x, feet - height - 10f * scale, stage.title, dates[stage], current)
        }
        marks.show(rows, offsetY + BASELINE * scale, scale)
        layer.place(marks, 0, 0, w, h)
    }

    /** 책의 고리를 넘지 않게 왼쪽 쪽과 오른쪽 쪽에 나눠 놓습니다. */
    private fun spotsFor(count: Int): FloatArray = when (count) {
        1 -> floatArrayOf(1110f)
        2 -> floatArrayOf(430f, 1110f)
        3 -> floatArrayOf(250f, 560f, 1110f)
        4 -> floatArrayOf(260f, 520f, 960f, 1240f)
        else -> floatArrayOf(200f, 400f, 620f, 935f, 1215f)
    }

    private companion object {
        const val SPREAD_W = 1536f
        const val SPREAD_H = 1024f
        const val BASELINE = 700f
        const val TALLEST = 430f
        const val TALLEST_MANY = 320f
        const val ACTOR_ROOM = 1.3f

        /** 지난 모습의 빛. */
        const val FADED = 0xEADCCC
    }
}

/** 키 재기의 연필 자국과 이름. 책 위에 쓴 글씨처럼 보이게 낮은 색으로 그립니다. */
private class RulerMarks(context: Context) : View(context) {

    class Row(val x: Float, val top: Float, val title: String, val date: String?, val current: Boolean)

    private val pencil = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GameStyle.INK_SOFT
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val base = Paint(pencil)
    private val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GameStyle.INK
        typeface = GameStyle.TITLE
        textAlign = Paint.Align.CENTER
    }
    /** 날짜도 진한 글자입니다. 그림책 종이(247, 223, 192)에서 보조 글자색은 대비가 4.2:1이라 모자랍니다. */
    private val day = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GameStyle.INK
        typeface = GameStyle.BODY
        textAlign = Paint.Align.CENTER
    }
    private var rows: List<Row> = emptyList()
    private var baseline = 0f
    private var scale = 1f

    fun show(rows: List<Row>, baseline: Float, scale: Float) {
        this.rows = rows
        this.baseline = baseline
        this.scale = scale
        pencil.strokeWidth = 2.4f * scale
        base.strokeWidth = 2f * scale
        base.pathEffect = DashPathEffect(floatArrayOf(10f * scale, 8f * scale), 0f)
        // 이름은 주 글자, 날짜는 가장 작은 글자 아래로 줄이지 않습니다(장면 규칙 2-8).
        val density = resources.displayMetrics.scaledDensity
        name.textSize = max(30f * scale, TextRules.MAIN * density)
        day.textSize = max(22f * scale, TextRules.SMALL * density)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (rows.isEmpty()) return
        val left = rows.first().x - 130f * scale
        val right = rows.last().x + 130f * scale
        canvas.drawLine(left, baseline + 8f * scale, right, baseline + 8f * scale, base)
        rows.forEach { row ->
            val half = 42f * scale
            canvas.drawLine(row.x - half, row.top, row.x + half, row.top, pencil)
            canvas.drawLine(row.x - half, row.top, row.x - half, row.top + 10f * scale, pencil)
            val y = baseline + 44f * scale
            // 지난 모습의 이름은 옅게 하지 않고 가는 글씨로 씁니다. 옅게 하면 책 바탕에서 대비가 모자랍니다.
            name.typeface = if (row.current) GameStyle.TITLE else GameStyle.BODY
            canvas.drawText(row.title, row.x, y, name)
            row.date?.let { canvas.drawText(it, row.x, y + day.textSize * 1.35f, day) }
        }
    }
}

/** 쪽이 몇 장이고 어디를 보고 있는지. 그림을 가리지 않게 작은 점으로 둡니다. */
class PageDots(context: Context) : View(context) {

    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GameStyle.CORAL
    }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GameStyle.LINE
    }
    private var count = 0
    private var current = 0

    fun show(count: Int, current: Int) {
        this.count = count
        this.current = current
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (count <= 1) return
        val radius = min(height / 2f, width / (count * 3f))
        val gap = radius * 3f
        val start = width / 2f - gap * (count - 1) / 2f
        for (i in 0 until count) canvas.drawCircle(start + gap * i, height / 2f, radius, if (i == current) on else off)
    }
}

/**
 * 그림이 천천히 밀리며 커집니다. 1초에 2픽셀도 안 움직이므로 박자를 낮춰도 끊겨 보이지 않습니다.
 * 뷰 속성만 바꿔 큰 그림을 다시 그리지 않습니다. 박자마다 [tick]을 부릅니다.
 */
class DriftingPicture(private val view: ImageView) {

    private var startMs = 0L
    private var from = 0f
    private var moving = false

    /** [motion]이 false면 고정합니다. 키 재기처럼 그림 위에 무언가를 얹을 때 씁니다. */
    fun start(index: Int, motion: Boolean) {
        moving = motion
        startMs = SystemClock.uptimeMillis()
        from = view.width * DRIFT * (if (index % 2 == 0) -1f else 1f)
        if (motion) {
            tick()
        } else {
            view.scaleX = 1f
            view.scaleY = 1f
            view.translationX = 0f
        }
    }

    fun tick() {
        if (!moving) return
        val progress = ((SystemClock.uptimeMillis() - startMs) / DRIFT_MS.toFloat()).coerceAtMost(1f)
        val scale = START_SCALE + (END_SCALE - START_SCALE) * progress
        view.scaleX = scale
        view.scaleY = scale
        view.translationX = from * (1f - progress * 2f)
    }

    private companion object {
        const val DRIFT = 0.012f
        const val START_SCALE = 1.04f
        const val END_SCALE = 1.1f
        const val DRIFT_MS = 22_000L
    }
}

/** 낭독 뒤에 깔리는 작은 오르골. 낭독을 가리지 않게 낮게 틀고 천천히 들어왔다 나갑니다. */
class RecallMusic(context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var volume = 0f

    init {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        player = runCatching {
            MediaPlayer.create(context, R.raw.recall_music, attributes, AudioManager.AUDIO_SESSION_ID_GENERATE)?.apply {
                isLooping = true
                setVolume(0f, 0f)
            }
        }.getOrNull()
    }

    fun start() {
        val current = player ?: return
        runCatching { current.start() }
        fadeTo(PEAK)
    }

    /** 낭독이 끝나면 조금 올리고, 다시 읽으면 내립니다. */
    fun duck(down: Boolean) = fadeTo(if (down) PEAK * DUCK else PEAK)

    private fun fadeTo(target: Float) {
        handler.removeCallbacksAndMessages(null)
        val step = object : Runnable {
            override fun run() {
                val current = player ?: return
                volume += (target - volume).coerceIn(-FADE_STEP, FADE_STEP)
                current.setVolume(volume, volume)
                if (volume != target) handler.postDelayed(this, FADE_TICK_MS)
            }
        }
        handler.post(step)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    private companion object {
        const val PEAK = 0.3f
        const val DUCK = 0.6f
        const val FADE_STEP = 0.02f
        const val FADE_TICK_MS = 60L
    }
}
