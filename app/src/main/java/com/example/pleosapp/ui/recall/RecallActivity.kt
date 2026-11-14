package com.example.pleosapp.ui.recall

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.example.pleosapp.R
import com.example.pleosapp.engine.DriveEvent
import com.example.pleosapp.engine.FamilyRepository
import com.example.pleosapp.engine.Growth
import com.example.pleosapp.engine.KeepsakeRepository
import com.example.pleosapp.engine.PetRepository
import com.example.pleosapp.engine.PostcardLines
import com.example.pleosapp.engine.RecallBook
import com.example.pleosapp.engine.RecallFacts
import com.example.pleosapp.engine.RecallId
import com.example.pleosapp.engine.RecallPage
import com.example.pleosapp.engine.RecallStory
import com.example.pleosapp.engine.TimeTogether
import com.example.pleosapp.spike.VoiceProbe
import com.example.pleosapp.ui.game.GameStyle
import com.example.pleosapp.ui.game.GameStyle.paperText
import com.example.pleosapp.ui.game.GameStyle.sizeTo
import com.example.pleosapp.ui.game.MotionTicker
import com.example.pleosapp.ui.game.PaperDrawable
import com.example.pleosapp.ui.game.StageLayout
import com.example.pleosapp.ui.game.TextRules
import com.example.pleosapp.ui.share.PostcardActivity
import kotlin.math.max
import kotlin.math.min

/**
 * 회상 그림책. 그림이 천천히 움직이고, 구름이가 한 쪽씩 읽고, 작은 오르골이 깔립니다.
 * 주차 중에만 열리고 출발하면 바로 닫힙니다. 언제든 닫을 수 있고 앨범에서 다시 봅니다.
 * 뒷자리 안전벨트가 그대로면 잠든 아이일 수 있어 소리 없이 글자만 보여 줍니다.
 */
class RecallActivity : Activity() {

    private lateinit var stage: StageLayout
    private lateinit var under: ImageView
    private lateinit var over: ImageView
    private lateinit var driftUnder: DriftingPicture
    private lateinit var driftOver: DriftingPicture
    private lateinit var ruler: RulerPage
    private lateinit var heading: TextView
    private lateinit var caption: TextView
    private lateinit var tape: View
    private lateinit var dots: PageDots
    private lateinit var closeTag: TextView
    private lateinit var settingsTag: TextView
    private lateinit var shareTag: TextView
    private lateinit var settingsNote: RecallSettingsNote

    private lateinit var prefs: RecallPrefs
    private lateinit var story: RecallStory
    private lateinit var id: RecallId
    private var voice: VoiceProbe? = null
    private var music: RecallMusic? = null

    private val ticker = MotionTicker()
    private val handler = Handler(Looper.getMainLooper())
    private var unit = 10f
    private var index = -1
    private var shownArt = 0
    private var overOnTop = false
    private var started = false
    private var paused = false
    private var invited = false

    /** 엽서 주소가 정해져 있을 때만 보냅니다. */
    private val canShare: Boolean get() = getString(R.string.share_base_url).isNotBlank()

    /** 쪽마다 하나씩 늘립니다. 늦게 도착한 낭독 소식이 다른 쪽을 넘기지 않게 합니다. */
    private var pageToken = 0
    private var speakingToken = -1

    private val advance = Runnable { next() }

    private val onEvent: (DriveEvent) -> Unit = { event ->
        if (event is DriveEvent.TripStarted) runOnUiThread { finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(GameStyle.INTERIOR))
        PetRepository.init(this)
        FamilyRepository.init(this)
        KeepsakeRepository.init(this)
        prefs = RecallPrefs(this)
        val target = RecallId.parse(intent.getStringExtra(EXTRA_ID).orEmpty())
        if (target == null || PetRepository.state.together.inTrip) {
            finish()
            return
        }
        id = target
        invited = intent.getBooleanExtra(EXTRA_INVITED, false)
        story = write()
        setContentView(buildStage())
        PetRepository.addListener(onEvent)
        val quiet = PetRepository.state.lastTrip?.rearStillBelted == true
        if (!quiet) {
            music = RecallMusic(this)
            voice = VoiceProbe(this).also { it.onSpeaking = { speaking -> runOnUiThread { onSpeaking(speaking) } } }
        }
        if (invited) prefs.markSeen(id, System.currentTimeMillis())
    }

    /** 지금 원장과 앨범의 사실로 문장을 씁니다. 그림책은 저장해 두지 않고 그때그때 만듭니다. */
    private fun write(): RecallStory = RecallBook.story(id, facts(), prefs.settings)

    private fun facts() = RecallFacts.of(
        PetRepository.state, KeepsakeRepository.gifts, KeepsakeRepository.care, FamilyRepository.state, System.currentTimeMillis(),
    )

    private fun buildStage(): StageLayout {
        under = picture()
        over = picture().apply { alpha = 0f }
        driftUnder = DriftingPicture(under)
        driftOver = DriftingPicture(over)
        ticker.onTick {
            driftUnder.tick()
            driftOver.tick()
        }
        ruler = RulerPage(this, ticker)
        heading = paperText(story.title, bold = true).apply {
            gravity = Gravity.CENTER
            rotation = -1f
        }
        caption = paperText().apply {
            gravity = Gravity.CENTER
            rotation = 0.4f
            alpha = 0f
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> stickTape() }
        }
        tape = View(this).apply {
            setBackgroundColor(TAPE)
            rotation = -3f
        }
        dots = PageDots(this)
        closeTag = paperText("닫기", bold = true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            contentDescription = "그림책 닫기"
            setOnClickListener { if (index == story.pages.lastIndex) finishStory() else finish() }
        }
        settingsTag = paperText(TimeTogether.HIDE_TITLE, color = GameStyle.INK_SOFT).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { openSettings() }
        }
        shareTag = paperText("가족에게 보내기", bold = true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            visibility = View.INVISIBLE
            setOnClickListener { sendPostcard() }
        }
        settingsNote = RecallSettingsNote(this, prefs).apply { onClosed = ::afterSettings }
        return StageLayout(this).apply {
            stage = this
            setBackgroundColor(GameStyle.INTERIOR)
            isClickable = true
            setOnClickListener { skip() }
            listOf(under, over, ruler.layer, heading, caption, tape, dots, closeTag, settingsTag, shareTag, settingsNote).forEach(::addView)
            onResize = ::arrange
        }
    }

    private fun picture() = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }

    private fun arrange(w: Int, h: Int) {
        val wide = w >= h * WIDE_RATIO
        val u = if (wide) min(w / 170f, h / 121f) else min(w / 96f, h / 150f)
        unit = u
        val gap = (2.4f * u).toInt()
        val radius = 2.4f * u
        listOf(heading, caption, closeTag, settingsTag, shareTag).forEach {
            it.background = PaperDrawable(this, radius)
            it.elevation = 1f * u
        }
        // 제목과 단추는 주 글자, 읽어 주는 글은 말풍선 크기 아래로 줄이지 않습니다(장면 규칙 2-8).
        heading.sizeTo(4.4f * u, TextRules.MAIN)
        heading.setPadding((3f * u).toInt(), 0, (3f * u).toInt(), 0)
        caption.sizeTo(4.2f * u, TextRules.BUBBLE)
        caption.setPadding((5f * u).toInt(), (3f * u).toInt(), (5f * u).toInt(), (3.4f * u).toInt())
        closeTag.sizeTo(3f * u, TextRules.MAIN)
        settingsTag.sizeTo(2.7f * u, TextRules.MAIN)
        shareTag.sizeTo(3f * u, TextRules.MAIN)
        tape.elevation = 1.2f * u

        val full = listOf<View>(under, over, ruler.layer)
        full.forEach { stage.place(it, 0, 0, w, h) }
        val titleWidth = heading.paint.measureText(story.title).toInt() + (10f * u).toInt()
        stage.place(heading, gap, gap, titleWidth, max((10f * u).toInt(), (heading.textSize * 1.8f).toInt()))
        val tagHeight = max((8f * u).toInt(), (closeTag.textSize * 1.9f).toInt())
        val closeWidth = fitWidth(closeTag, 15f * u)
        stage.place(closeTag, w - gap - closeWidth, gap, closeWidth, tagHeight)
        val settingsWidth = fitWidth(settingsTag, 21f * u)
        stage.place(settingsTag, w - gap * 2 - closeWidth - settingsWidth, gap, settingsWidth, tagHeight)
        val shareWidth = fitWidth(shareTag, 30f * u)
        stage.place(shareTag, w - gap * 3 - closeWidth - settingsWidth - shareWidth, gap, shareWidth, tagHeight)

        stage.place(tape, 0, 0, (18f * u).toInt(), (4f * u).toInt())
        val captionWidth = min(w - gap * 2, (128f * u).toInt())
        val captionLeft = (w - captionWidth) / 2
        val dotsHeight = (1.4f * u).toInt()
        val captionBottom = h - gap - dotsHeight - gap / 2
        stage.place(caption, captionLeft, captionBottom - (34f * u).toInt(), captionWidth, (34f * u).toInt(), wrapHeight = true)
        stage.place(dots, captionLeft, h - gap - dotsHeight, captionWidth, dotsHeight)
        stage.post(::begin)
    }

    /** 단추 종이 너비. 작은 창에서 글자가 기준 크기로 커져도 종이 밖으로 나가지 않습니다. */
    private fun fitWidth(tag: TextView, width: Float): Int =
        max(width, tag.paint.measureText(tag.text.toString()) + 6f * unit).toInt()

    private fun begin() {
        if (started) return
        started = true
        stage.alpha = 0f
        stage.animate().alpha(1f).setDuration(FIRST_FADE_MS).start()
        music?.start()
        show(0)
    }

    /** 글이 길어져 종이가 커져도 테이프는 종이 위쪽 가운데에 붙어 있습니다. */
    private fun stickTape() {
        tape.x = caption.left + (caption.width - tape.width) / 2f
        tape.y = caption.top - tape.height / 2f
    }

    private fun show(target: Int) {
        val page = story.pages[target]
        val token = ++pageToken
        index = target
        handler.removeCallbacks(advance)
        dots.show(story.pages.size, target)
        heading.visibility = if (target == 0) View.VISIBLE else View.INVISIBLE
        shareTag.visibility = if (target == story.pages.lastIndex && canShare) View.VISIBLE else View.INVISIBLE
        caption.animate().alpha(0f).setDuration(CAPTION_FADE_MS).withEndAction {
            if (token == pageToken) {
                caption.text = page.line
                caption.animate().alpha(1f).setDuration(CAPTION_FADE_MS).start()
            }
        }.start()
        showPicture(page, token)
        speak(page, token)
    }

    private fun showPicture(page: RecallPage, token: Int) {
        val art = RecallArt.of(this, page.art)
        showRuler(page)
        if (art == shownArt) return
        shownArt = art
        Thread {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val bitmap = BitmapFactory.decodeResource(resources, art, options)
            runOnUiThread { if (token == pageToken && !isDestroyed) fadeIn(bitmap, page.ruler) }
        }.start()
    }

    /** 위 장의 투명도 하나로 두 장을 오갑니다. 앞 장의 움직임은 그대로 두어 끊기지 않습니다. */
    private fun fadeIn(bitmap: Bitmap, still: Boolean) {
        val toOver = !overOnTop
        overOnTop = toOver
        val target = if (toOver) over else under
        val drift = if (toOver) driftOver else driftUnder
        target.setImageBitmap(bitmap)
        drift.start(index, motion = !still)
        over.animate().alpha(if (toOver) 1f else 0f).setDuration(if (index == 0) 0L else PICTURE_FADE_MS).start()
    }

    private fun showRuler(page: RecallPage) {
        if (!page.ruler) {
            ruler.hide()
            return
        }
        val facts = facts()
        val dates = if (prefs.hideDates) emptyMap() else datesOf(facts)
        ruler.show(rulerStage(facts), KeepsakeRepository.gifts.form, dates)
    }

    /** 자람 그림책은 그 단계까지, 고비 그림책은 지금까지 세웁니다. */
    private fun rulerStage(facts: RecallFacts): Growth.Stage = (id as? RecallId.Grown)?.stage ?: facts.stage

    private fun datesOf(facts: RecallFacts): Map<Growth.Stage, String> {
        val first = facts.firstTripAt?.let { mapOf(Growth.Stage.FLUFF to it) }.orEmpty()
        return (first + facts.stageAt).mapValues { TimeTogether.dateText(it.value, facts.nowMs) }
    }

    private fun speak(page: RecallPage, token: Int) {
        val current = voice
        handler.postDelayed(advance, timeoutFor(page, spoken = current != null))
        if (current == null) return
        current.stop()
        handler.postDelayed({ if (token == pageToken && !paused) current.speak(page.line) }, SPEAK_DELAY_MS)
    }

    /** 낭독 소식이 오지 않아도 멈추지 않게 글자 수만큼 기다린 뒤 넘깁니다. */
    private fun timeoutFor(page: RecallPage, spoken: Boolean): Long {
        val reading = page.line.length * MS_PER_CHAR + AFTER_LINE_MS
        return if (spoken) reading + VOICE_SLACK_MS else max(reading, MIN_PAGE_MS)
    }

    private fun onSpeaking(speaking: Boolean) {
        if (isFinishing || isDestroyed) return
        music?.duck(speaking)
        if (speaking) {
            speakingToken = pageToken
            return
        }
        if (speakingToken != pageToken || paused) return
        handler.removeCallbacks(advance)
        handler.postDelayed(advance, if (index == story.pages.lastIndex) lastPageMs() else AFTER_LINE_MS)
    }

    /** 엽서를 보낼 수 있으면 누를 틈을 더 둡니다. */
    private fun lastPageMs(): Long = if (canShare) LAST_PAGE_SHARE_MS else LAST_PAGE_MS

    private fun next() {
        if (paused) return
        if (index >= story.pages.lastIndex) {
            finishStory()
        } else {
            show(index + 1)
        }
    }

    /** 화면을 누르면 다음 쪽으로 넘깁니다. 마지막 쪽에서는 덮습니다. */
    private fun skip() {
        if (index < 0) return
        voice?.stop()
        next()
    }

    /** 다 읽고 덮습니다. 초대로 연 책이면 홈에 사진이 걸립니다. 창밖은 늘 강가라 풍경을 두지 않습니다. */
    private fun finishStory() {
        if (invited) prefs.markHung(id)
        finish()
    }

    /**
     * 덮으면서 가족에게 엽서로 보냅니다. 함께한 날은 엽서에 찍혀 나갑니다.
     * 이미 덮는 중이면 두 번째 손짓을 무시합니다. 빠르게 두 번 누르면 엽서가 두 겹으로 떴습니다.
     */
    private fun sendPostcard() {
        if (isFinishing) return
        handler.removeCallbacks(advance)
        voice?.stop()
        startActivity(PostcardActivity.intent(this, line = PostcardLines.DAYS))
        finishStory()
    }

    private fun openSettings() {
        paused = true
        handler.removeCallbacks(advance)
        voice?.stop()
        settingsNote.show(unit)
    }

    private fun afterSettings() {
        paused = false
        if (prefs.off) {
            finish()
            return
        }
        story = write()
        show(index.coerceIn(0, story.pages.lastIndex))
    }

    override fun onStart() {
        super.onStart()
        ticker.start()
    }

    /** 다른 화면으로 가면 소리를 남기지 않고 덮습니다. 앨범에서 다시 볼 수 있습니다. */
    override fun onStop() {
        ticker.stop()
        if (!isFinishing) finish()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        PetRepository.removeListener(onEvent)
        voice?.release()
        music?.release()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "recall"
        private const val EXTRA_INVITED = "invited"
        private const val WIDE_RATIO = 1.15f
        private const val FIRST_FADE_MS = 900L
        private const val PICTURE_FADE_MS = 1_100L
        private const val CAPTION_FADE_MS = 320L
        private const val SPEAK_DELAY_MS = 500L
        private const val MS_PER_CHAR = 210L
        private const val AFTER_LINE_MS = 1_600L
        private const val LAST_PAGE_MS = 3_200L
        private const val LAST_PAGE_SHARE_MS = 9_000L
        private const val MIN_PAGE_MS = 4_000L
        private const val VOICE_SLACK_MS = 4_000L
        private const val TAPE = 0xB8E8B4A0.toInt()

        /** [invited]면 초대로 연 것입니다. 본 것으로 치고, 다 읽으면 홈에 사진이 걸립니다. */
        fun intent(context: Context, id: RecallId, invited: Boolean = false): Intent =
            Intent(context, RecallActivity::class.java).putExtra(EXTRA_ID, id.key).putExtra(EXTRA_INVITED, invited)
    }
}
