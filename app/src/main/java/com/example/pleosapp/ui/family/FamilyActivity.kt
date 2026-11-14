package com.example.pleosapp.ui.family

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import com.example.pleosapp.R
import com.example.pleosapp.engine.DriveEvent
import com.example.pleosapp.engine.FamilyRepository
import com.example.pleosapp.engine.FamilySpeech
import com.example.pleosapp.engine.FamilySpirit
import com.example.pleosapp.engine.FamilyState
import com.example.pleosapp.engine.Josa
import com.example.pleosapp.engine.KeepsakeRepository
import com.example.pleosapp.engine.PetRepository
import com.example.pleosapp.engine.ReturnGift
import com.example.pleosapp.engine.SpiritPreset
import com.example.pleosapp.spike.VoiceProbe
import com.example.pleosapp.ui.CardLayout
import com.example.pleosapp.ui.game.GameStyle
import com.example.pleosapp.ui.game.GameStyle.paperText
import com.example.pleosapp.ui.game.GameStyle.sizeTo
import com.example.pleosapp.ui.game.PaperDrawable
import com.example.pleosapp.ui.game.StageLayout
import com.example.pleosapp.ui.spirit.SpiritSprites
import com.example.pleosapp.ui.CardLayout.dp
import com.example.pleosapp.ui.CardLayout.label
import com.example.pleosapp.ui.family.FamilyTiles.pickTile
import com.example.pleosapp.ui.family.FamilyTiles.pillButton
import com.example.pleosapp.ui.family.FamilyTiles.spiritTile
import kotlin.math.max
import kotlin.math.min

/**
 * 정령 입양식과 가족 정령 관리(M3 명세 3-1). 주차 중에만 쓰는 화면이라 주행으로 바뀌면 플랫폼이 가리고,
 * 출발하면 스스로 닫습니다. 이름은 고정 목록에서만 고르고 호칭은 가정하지 않습니다.
 */
class FamilyActivity : Activity() {

    private enum class Step { LIST, PICK_SPIRIT }

    private lateinit var content: LinearLayout
    private lateinit var stage: StageLayout
    private lateinit var spread: ImageView
    private lateinit var board: SeatBoardView
    private lateinit var sheet: ScrollView
    private lateinit var closeTag: TextView

    /** 카드판에서 눌러 고른 아기. */
    private var selected: SpiritPreset? = null
    private var step = Step.LIST
    private var greeting: String? = null
    private var voice: VoiceProbe? = null

    private val onEvent: (DriveEvent) -> Unit = { event ->
        if (event is DriveEvent.TripStarted) runOnUiThread { finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PetRepository.init(this)
        FamilyRepository.init(this)
        KeepsakeRepository.init(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(buildStage())
        val adding = intent.getBooleanExtra(EXTRA_ADD, false) && FamilyRepository.state.canAdopt
        step = if (adding) Step.PICK_SPIRIT else Step.LIST
        PetRepository.addListener(onEvent)
        render()
    }

    /**
     * 앨범과 같은 책을 폅니다. 왼쪽에 가족 카드판, 오른쪽에 지금 할 일입니다.
     * 세로로 좁은 창은 책을 접고 위아래로 놓습니다.
     */
    private fun buildStage(): StageLayout {
        spread = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(SpiritSprites.bitmap(context, R.drawable.book_spread))
        }
        board = SeatBoardView(this).apply { onCard = ::onCard }
        sheet = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        closeTag = paperText("닫기", bold = true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { finish() }
        }
        stage = StageLayout(this).apply {
            setBackgroundColor(GameStyle.PAPER)
            addView(spread)
            addView(board)
            addView(sheet)
            addView(closeTag)
            clipToSelf(sheet)
            onResize = ::arrange
        }
        return stage
    }

    private fun arrange(w: Int, h: Int) {
        val wide = w >= h * WIDE_RATIO
        val u = if (wide) min(w / 170f, h / 121f) else min(w / 96f, h / 150f)
        closeTag.background = PaperDrawable(this, 2f * u)
        closeTag.elevation = 0.8f * u
        closeTag.sizeTo(3f * u, 15f)
        val tagWidth = (14f * u).toInt()
        val tagHeight = (7.4f * u).toInt()
        val gap = (2.4f * u).toInt()
        stage.place(closeTag, w - tagWidth - gap, gap, tagWidth, tagHeight)
        if (wide) {
            spread.visibility = View.VISIBLE
            val scale = max(w / SPREAD_W, h / SPREAD_H)
            val x = { design: Float -> ((w - SPREAD_W * scale) / 2f + design * scale).toInt() }
            val y = { design: Float -> ((h - SPREAD_H * scale) / 2f + design * scale).toInt() }
            stage.place(spread, 0, 0, w, h)
            stage.place(board, x(LEFT_X), y(PAGE_TOP), x(LEFT_X + PAGE_W) - x(LEFT_X), y(PAGE_BOTTOM) - y(PAGE_TOP))
            stage.place(sheet, x(RIGHT_X), y(PAGE_TOP), x(RIGHT_X + PAGE_W) - x(RIGHT_X), y(PAGE_BOTTOM) - y(PAGE_TOP))
        } else {
            spread.visibility = View.GONE
            val top = tagHeight + gap * 2
            val split = top + (h - top) * 4 / 9
            stage.place(board, gap, top, w - gap * 2, split - top)
            stage.place(sheet, gap * 2, split, w - gap * 4, h - split - gap)
        }
    }

    override fun onDestroy() {
        PetRepository.removeListener(onEvent)
        voice?.release()
        super.onDestroy()
    }

    /** 앱 창 크기는 분할 상태에 따라 바뀝니다. 재생성하지 않고 지금 단계만 다시 그립니다. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    private val narrow: Boolean get() = resources.configuration.screenWidthDp < NARROW_WINDOW_DP

    @Deprecated("차량 시스템 뒤로 키도 같은 단계 이동을 따릅니다.")
    override fun onBackPressed() {
        if (!stepBack()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun stepBack(): Boolean {
        if (step == Step.LIST) return false
        step = Step.LIST
        render()
        return true
    }

    private fun render() {
        content.removeAllViews()
        val family = FamilyRepository.state
        selected = selected?.takeIf { preset -> family.spirits.any { it.preset == preset } }
        board.show(family, selected, pickingFree = step == Step.PICK_SPIRIT)
        when (step) {
            Step.LIST -> renderList()
            Step.PICK_SPIRIT -> renderSpiritPicker()
        }
    }

    private fun renderList() {
        val family = FamilyRepository.state
        val spirit = family.spirits.firstOrNull { it.preset == selected }
        if (spirit != null) {
            renderSpirit(spirit)
            return
        }
        content.addView(label("우리 가족", TITLE_SP, bold = true))
        greeting?.let { content.addView(label(it, BODY_SP, color = CardLayout.MUTED).spaced()) }
        // 넷이 다 차면 빈칸이 없어 빈칸 안내를 뺍니다. 낱말 가운데서 줄이 바뀌지 않게 묶습니다.
        val hint = when {
            family.spirits.isEmpty() -> "빈칸을 눌러 함께 탈 친구를 데려와."
            family.canAdopt -> "칸을 눌러 봐. 빈칸에는 새 친구가 와."
            else -> "칸을 눌러 봐."
        }
        content.addView(
            label(GameStyle.keepWords(hint), BODY_SP, color = CardLayout.MUTED).spaced(),
        )
        val traces = KeepsakeRepository.gifts.shelf.takeLast(TRACES).asReversed()
        if (traces.isNotEmpty()) {
            content.addView(label("요즘 주워 온 것", BODY_SP, bold = true).apply { setPadding(0, dp(8), 0, dp(8)) })
            traces.forEach { content.addView(traceRow(it)) }
        }
        content.addView(label("이 차에만 적어 둘게.", NOTE_SP, color = CardLayout.MUTED).apply { setPadding(0, dp(16), 0, 0) })
        if (family.spirits.isNotEmpty()) {
            content.addView(label("모두 비우기", NOTE_SP, color = CardLayout.MUTED).apply {
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true
                setOnClickListener { confirmClear() }
            })
        }
    }

    /** 작은 정령이 다녀온 흔적. 누가 무엇을 가져왔는지만 적습니다. 횟수는 세지 않습니다. */
    private fun traceRow(gift: ReturnGift): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(2), 0, dp(2))
        addView(ImageView(context).apply {
            setImageBitmap(SpiritSprites.bitmap(context, SpiritSprites.named(context, "return_${gift.kind.id}")))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(TRACE_DP), dp(TRACE_DP)).apply { marginEnd = dp(12) })
        addView(label(FamilySpeech.brought(gift), NOTE_SP))
    }

    /**
     * 고른 정령. 빼기는 여기까지 들어와야 보입니다. 가족 옆에 지우기가 늘 붙어 있지 않게 합니다.
     * 자리는 묻지도 적지도 않습니다. 가족은 자리를 바꿔 타고, 누가 탈지는 탄 사람 수와 부른 순서로 정합니다.
     */
    private fun renderSpirit(spirit: FamilySpirit) {
        content.addView(label(spirit.preset.displayName, TITLE_SP, bold = true).spaced())
        // 기본 화면에서도 '함/께'로 쪼개져 낱말을 묶습니다.
        content.addView(label(GameStyle.keepWords("홈 둥지에서 이 아기를 누르면 다음에 먼저 함께 가."), BODY_SP, color = CardLayout.MUTED).spaced())
        content.addView(paperButton("가족에서 빼기") { confirmRemove(spirit.preset) })
        content.addView(paperButton("뒤로") {
            selected = null
            render()
        })
    }

    /** 카드판의 칸을 눌렀습니다. 아기 칸이면 그 아기를 보고, 빈칸이면 새 아기를 들입니다. */
    private fun onCard(index: Int) {
        if (step != Step.LIST) return
        val family = FamilyRepository.state
        val spirit = family.spirits.getOrNull(index)
        if (spirit != null) {
            selected = spirit.preset
            render()
        } else if (family.canAdopt) {
            startAdopt()
        }
    }

    private fun renderSpiritPicker() {
        content.addView(label("누구를 데려올까?", TITLE_SP, bold = true))
        content.addView(label("함께 탈 친구를 골라 줘.", BODY_SP, color = CardLayout.MUTED).spaced())
        // 넷이 두 줄로 들어가고 "뒤로"까지 쪽 안에 보이는 크기입니다. 110이면 "뒤로" 아래가 쪽 밖으로 잘렸습니다.
        val badgeDp = if (narrow) 84 else 90
        grid(FamilyRepository.state.availablePresets, columns = 2) { preset ->
            pickTile(spiritTile(preset, preset.displayName, awake = true, sizeDp = badgeDp, captionSp = 24f), enabled = true) { choose(preset) }
        }
        content.addView(paperButton("뒤로") { if (!stepBack()) finish() })
    }

    /** 들이면 빈칸에 들어옵니다. 자리는 묻지 않습니다. 탄 사람 수만큼, 부른 아기부터 함께 갑니다. */
    private fun choose(preset: SpiritPreset) {
        FamilyRepository.update { it.adopt(preset) }
        greeting = FamilySpeech.welcome(preset.displayName)
        speak(greeting.orEmpty())
        step = Step.LIST
        render()
    }

    private fun startAdopt() {
        greeting = null
        step = Step.PICK_SPIRIT
        render()
    }

    private fun speak(text: String) {
        val engine = voice ?: VoiceProbe(this).also { voice = it }
        engine.speak(text)
    }

    private fun confirmRemove(preset: SpiritPreset) {
        AlertDialog.Builder(this)
            // 낱말째 줄을 바꿉니다. 묶지 않으면 "요."만 다음 줄에 남았습니다.
            .setMessage(GameStyle.keepWords("${Josa.objective(preset.displayName)} 가족에서 뺄까요? 맡겨 둔 쪽지도 함께 사라져요."))
            .setPositiveButton("빼기") { _, _ ->
                FamilyRepository.update { it.remove(preset) }
                greeting = null
                render()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            // 빼기 확인 창과 같이 낱말째 줄을 바꿉니다.
            .setMessage(GameStyle.keepWords("가족을 모두 비울까요? 맡겨 둔 쪽지도 함께 사라져요."))
            .setPositiveButton("모두 비우기") { _, _ ->
                FamilyRepository.update { FamilyState() }
                greeting = null
                render()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 책 위에서도 또렷한 종이 버튼. */
    private fun paperButton(text: String, onClick: () -> Unit): View = pillButton(text, onClick).apply {
        background = PaperDrawable(context, dp(16).toFloat())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        }
    }

    private fun <T> grid(items: List<T>, columns: Int, tile: (T) -> View) {
        items.chunked(columns).forEachIndexed { index, chunk ->
            val cells = chunk.map(tile).toMutableList()
            // 빈칸은 Space로 둡니다. 일반 View는 스크롤 안에서 화면 높이만큼 늘어나 줄 전체를 키웁니다.
            repeat(columns - chunk.size) { cells += Space(this) }
            val line = row(*cells.toTypedArray())
            content.addView(if (index == 0) line else line.topped())
        }
    }

    private fun row(vararg cells: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        cells.forEachIndexed { index, cell ->
            addView(
                cell,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (index > 0) marginStart = dp(12)
                },
            )
        }
    }

    private fun <T : View> T.spaced(): T = apply { setPadding(0, dp(8), 0, dp(16)) }

    private fun TextView.centered(): TextView = apply { gravity = Gravity.CENTER }

    private fun <T : View> T.gapped(): T = apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(12)
        }
    }

    private fun <T : View> T.topped(): T = apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        }
    }

    companion object {
        private const val TITLE_SP = 44f
        private const val BODY_SP = 30f
        private const val NOTE_SP = 24f
        private const val TRACES = 3
        private const val TRACE_DP = 46
        private const val WIDE_RATIO = 1.15f
        private const val SPREAD_W = 1536f
        private const val SPREAD_H = 1024f
        private const val LEFT_X = 170f
        private const val RIGHT_X = 860f
        private const val PAGE_W = 510f
        private const val PAGE_TOP = 190f
        private const val PAGE_BOTTOM = 800f
        private const val EXTRA_ADD = "add"
        private const val NARROW_WINDOW_DP = 700

        fun intent(context: Context, adding: Boolean): Intent =
            Intent(context, FamilyActivity::class.java).putExtra(EXTRA_ADD, adding)
    }
}
