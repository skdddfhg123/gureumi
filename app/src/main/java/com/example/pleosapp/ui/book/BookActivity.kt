package com.example.pleosapp.ui.book

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.pleosapp.R
import com.example.pleosapp.engine.Badge
import com.example.pleosapp.engine.CareItem
import com.example.pleosapp.engine.CareRules
import com.example.pleosapp.engine.CareStatus
import com.example.pleosapp.engine.CareTerms
import com.example.pleosapp.engine.DiaryTemplate
import com.example.pleosapp.engine.DriveEvent
import com.example.pleosapp.engine.FamilyRepository
import com.example.pleosapp.engine.HomeSpeech
import com.example.pleosapp.engine.Guest
import com.example.pleosapp.engine.Josa
import com.example.pleosapp.engine.Place
import com.example.pleosapp.engine.PlaceState
import com.example.pleosapp.engine.KeepsakeRepository
import com.example.pleosapp.engine.Ladder
import com.example.pleosapp.engine.Moment
import com.example.pleosapp.engine.PetRepository
import com.example.pleosapp.engine.PetState
import com.example.pleosapp.engine.Reward
import com.example.pleosapp.engine.RewardKind
import com.example.pleosapp.engine.TimeTogether
import com.example.pleosapp.ui.game.GameStyle
import com.example.pleosapp.ui.game.GameStyle.paperText
import com.example.pleosapp.ui.game.GameStyle.sizeTo
import com.example.pleosapp.ui.game.HomeWorld
import com.example.pleosapp.ui.game.MotionTicker
import com.example.pleosapp.ui.game.PaperDrawable
import com.example.pleosapp.ui.game.Seen
import com.example.pleosapp.ui.game.SoftSounds
import com.example.pleosapp.ui.game.StageLayout
import com.example.pleosapp.ui.recall.RecallPrefs
import com.example.pleosapp.ui.recall.RecallSettingsNote
import com.example.pleosapp.ui.recall.RecallShelf
import com.example.pleosapp.ui.share.PostcardActivity
import com.example.pleosapp.ui.share.Postcards
import com.example.pleosapp.ui.spirit.HeroArts
import com.example.pleosapp.ui.spirit.HeroPose
import com.example.pleosapp.ui.spirit.SpiritSprites
import com.example.pleosapp.ui.spirit.SpriteSpiritView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 펼친 앨범 한 권. 성장 기록, 함께한 기록, 정비 수첩이 같은 책의 다른 쪽입니다.
 * 앱 밖 설정 화면처럼 보이지 않게 그림책 위에 글을 얹습니다.
 */
class BookActivity : Activity() {

    enum class Page(val title: String) {
        GROWTH("성장 기록"),
        ALBUM("함께한 기록"),
        CARE("정비 수첩"),
    }

    private lateinit var stage: StageLayout
    private lateinit var spread: ImageView
    private lateinit var leftPage: LinearLayout
    private lateinit var rightPage: LinearLayout
    private lateinit var leftScroll: ScrollView
    private lateinit var rightScroll: ScrollView
    private lateinit var tabs: Map<Page, TextView>
    private lateinit var closeTag: TextView
    private lateinit var actor: SpriteSpiritView
    private lateinit var sounds: SoftSounds
    private lateinit var seen: Seen
    private lateinit var recallNote: RecallSettingsNote

    private val ticker = MotionTicker()
    private var page = Page.GROWTH
    private var unit = 10f
    private var caption: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    /** 엽서를 열었는지. 빠르게 두 번 눌러도 엽서가 두 겹으로 뜨지 않습니다. 책이 다시 앞에 오면 풉니다. */
    private var opened = false

    /**
     * 막 "챙겼어"를 누른 곳과 누른 시각. 몇 초 동안 되돌릴 수 있고, 그동안은 원장에 적지 않습니다.
     * 잘못 누르면 엔진오일 알림 주기가 그날부터 다시 셉니다. 수첩을 떠나면 바로 적습니다.
     */
    private val pendingCare = LinkedHashMap<CareItem, Long>()

    /** 아직 때가 아닌데 누른 곳. 한 번 더 누르면 챙긴 것으로 봅니다. 처음 며칠은 모든 곳이 때가 아닙니다. */
    private var confirming: CareItem? = null
    private val commitCare = Runnable { flushCare() }
    private val endConfirm = Runnable {
        confirming = null
        render()
    }

    private val onEvent: (DriveEvent) -> Unit = { event ->
        if (event is DriveEvent.TripStarted) runOnUiThread { finish() }
    }
    private val onChange: () -> Unit = { runOnUiThread { if (!isDestroyed) render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(GameStyle.INTERIOR))
        page = Page.entries[intent.getIntExtra(EXTRA_PAGE, 0).coerceIn(0, Page.entries.lastIndex)]
        PetRepository.init(this)
        FamilyRepository.init(this)
        KeepsakeRepository.init(this)
        sounds = SoftSounds(this)
        seen = Seen(this)
        setContentView(buildStage())
        PetRepository.addListener(onEvent)
        KeepsakeRepository.addListener(onChange)
    }

    override fun onStart() {
        super.onStart()
        ticker.start()
    }

    /** 연 엽서를 닫고 책으로 돌아오면 다시 열 수 있습니다. */
    override fun onResume() {
        super.onResume()
        opened = false
    }

    override fun onStop() {
        ticker.stop()
        super.onStop()
    }

    /** 되돌릴 틈이 남은 기록도 수첩을 떠나면 적습니다. 출발해서 닫힐 때도 같습니다. */
    override fun onPause() {
        handler.removeCallbacks(endConfirm)
        confirming = null
        flushCare(all = true)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        PetRepository.removeListener(onEvent)
        KeepsakeRepository.removeListener(onChange)
        sounds.release()
        super.onDestroy()
    }

    private fun buildStage(): StageLayout {
        spread = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(SpiritSprites.bitmap(context, R.drawable.book_spread))
        }
        leftPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rightPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 쪽보다 내용이 길면 위아래 끝을 종이 속으로 흐리게 합니다. 쪽 끝에 반쯤 걸린 줄이
        // 잘린 그림처럼 보이지 않고, 밀면 더 있다는 표시가 됩니다. 내용이 쪽 안에 들면 흐리지 않습니다.
        leftScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            addView(leftPage)
        }
        rightScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            addView(rightPage)
        }
        actor = SpriteSpiritView(this).apply { grounded = false }
        ticker.add(actor)
        tabs = Page.entries.associateWith { target ->
            paperText(target.title, bold = true).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { open(target) }
            }
        }
        closeTag = paperText("닫기", bold = true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { finish() }
        }
        recallNote = RecallSettingsNote(this, RecallPrefs(this)).apply { onClosed = ::render }
        stage = StageLayout(this).apply {
            setBackgroundColor(GameStyle.PAPER)
            addView(spread)
            addView(leftScroll)
            addView(rightScroll)
            tabs.values.forEach(::addView)
            addView(closeTag)
            addView(recallNote)
            clipToSelf(leftScroll)
            clipToSelf(rightScroll)
            onResize = ::arrange
        }
        return stage
    }

    private fun open(target: Page) {
        if (target == page) return
        sounds.pop()
        page = target
        render()
    }

    /** 가로 창은 양쪽을 펼쳐 씁니다. 세로로 좁은 창은 위아래로 이어 읽습니다. */
    private fun arrange(w: Int, h: Int) {
        val wide = w >= h * WIDE_RATIO
        unit = if (wide) min(w / 170f, h / 121f) else min(w / 96f, h / 150f)
        val u = unit
        listOf(leftScroll, rightScroll).forEach { it.setFadingEdgeLength((PAGE_FADE * u).toInt()) }
        val tabHeight = (7.4f * u).toInt()
        val tabWidth = (23f * u).toInt()
        val radius = 2f * u
        (tabs.values + closeTag).forEach {
            it.background = PaperDrawable(this, radius)
            it.elevation = 0.8f * u
            it.sizeTo(3f * u, 15f)
        }
        val gap = (1.2f * u).toInt()
        if (wide) {
            spread.visibility = View.VISIBLE
            val scale = max(w / SPREAD_W, h / SPREAD_H)
            val x = { design: Float -> ((w - SPREAD_W * scale) / 2f + design * scale).toInt() }
            val y = { design: Float -> ((h - SPREAD_H * scale) / 2f + design * scale).toInt() }
            stage.place(spread, 0, 0, w, h)
            stage.place(leftScroll, x(LEFT_X), y(PAGE_TOP), x(LEFT_X + PAGE_W) - x(LEFT_X), y(PAGE_BOTTOM) - y(PAGE_TOP))
            stage.place(rightScroll, x(RIGHT_X), y(PAGE_TOP), x(RIGHT_X + PAGE_W) - x(RIGHT_X), y(PAGE_BOTTOM) - y(PAGE_TOP))
            val top = gap * 2
            var cursor = (w - (tabWidth + gap) * tabs.size) / 2
            tabs.values.forEach {
                stage.place(it, cursor, top, tabWidth, tabHeight)
                cursor += tabWidth + gap
            }
            // 닫기는 어느 화면에서나 오른쪽 위에 둡니다.
            stage.place(closeTag, w - tabWidth / 2 - gap, top, tabWidth / 2, tabHeight)
        } else {
            spread.visibility = View.GONE
            val tabRoom = tabHeight + gap * 3
            val closeWidth = (14f * u).toInt()
            val each = (w - gap * 5 - closeWidth) / tabs.size
            var cursor = gap
            tabs.values.forEach {
                stage.place(it, cursor, gap, each, tabHeight)
                cursor += each + gap
            }
            stage.place(closeTag, cursor, gap, closeWidth, tabHeight)
            val side = (3f * u).toInt()
            val split = tabRoom + (h - tabRoom) * 3 / 8
            stage.place(leftScroll, side, tabRoom, w - side * 2, split - tabRoom)
            stage.place(rightScroll, side, split, w - side * 2, h - split - gap)
        }
        stage.post(::render)
    }

    private fun render() {
        leftPage.removeAllViews()
        rightPage.removeAllViews()
        caption = null
        // 지금 쪽의 탭은 종이가 앞으로 나옵니다. 나머지는 살짝 들어갑니다.
        tabs.forEach { (target, tag) ->
            val open = target == page
            tag.translationY = if (open) 0f else unit
            tag.elevation = if (open) unit else 0f
            tag.alpha = 1f
        }
        when (page) {
            Page.GROWTH -> renderGrowth()
            Page.ALBUM -> renderAlbum()
            Page.CARE -> renderCare()
        }
    }

    private fun title(text: String) = paperText(text, bold = true).apply {
        sizeTo(4.6f * unit, 20f)
        setPadding(0, 0, 0, (1.4f * unit).toInt())
    }

    private fun line(text: String, color: Int = GameStyle.INK, size: Float = 3.2f) = paperText(GameStyle.keepWords(text), color = color).apply {
        sizeTo(size * unit, 16f)
        setPadding(0, (0.5f * unit).toInt(), 0, (0.5f * unit).toInt())
    }

    private fun section(text: String) = paperText(text, bold = true, color = GameStyle.INK_SOFT).apply {
        sizeTo(3f * unit, 15f)
        setPadding(0, unit.toInt(), 0, (0.4f * unit).toInt())
    }

    /* 성장 기록 */

    private fun renderGrowth() {
        val pet = PetRepository.state
        val keep = KeepsakeRepository.gifts
        val care = KeepsakeRepository.care
        val jar = pet.jar
        leftPage.addView(title("구름이"))
        leftPage.addView(line("Lv.${pet.level} · 함께한 날 ${pet.together.days}일", GameStyle.INK_SOFT, 2.8f))
        // 닮은 습관 줄은 맨 아래에 붙어 쪽이 넘치면 보이지 않았습니다. 그 줄이 있으면 구름이 그림을 줄여 쪽 안에 둡니다.
        val likeness = Badge.likeness(pet)
        val textRoom = GROWTH_TEXT_ROOM + if (likeness != null) LIKENESS_ROOM else 0f
        val size = min(leftScroll.width, (leftScroll.height - (textRoom * unit).toInt()).coerceAtLeast((30f * unit).toInt()))
        actor.setArt(HeroArts.of(this, HeroPose.SIT, keep.form))
        actor.unit = size / ACTOR_HEIGHT
        actor.setMotions(emptyList())
        leftPage.addView(actor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, size))
        leftPage.addView(line(when {
            keep.pending(jar.opened) > 0 -> "병이 가득 찼어. 열어 볼래?"
            keep.next == null -> "병을 모두 열었어."
            else -> "깃털 ${jar.left}개 더 모으면 Lv.${pet.level + 1}"
        }))
        keep.next?.let { leftPage.addView(line(it.kind.teaser, GameStyle.INK_SOFT)) }
        likeness?.let { leftPage.addView(line(HomeSpeech.likenessLine(it), GameStyle.INK_SOFT)) }

        val owned = CareRules.owned(pet, keep, care)
        val note = line("칸을 눌러 봐.", GameStyle.INK_SOFT, 2.8f)
        caption = note
        // 모은 것을 갈래별로 나눕니다. 한 격자에 섞이면 업적 목록처럼 보입니다.
        RewardKind.entries.forEach { kind ->
            // 지금 홈에서 볼 수 있는 보상만 둡니다. 아직 놓지 않은 보상은 홈에 놓으면 다시 보입니다.
            val rung = Ladder.RUNGS.filter { it.kind == kind && HomeWorld.showsAtHome(this, it) }
            // 함께한 날의 창밖 풍경은 11/5에 뺐습니다. 창밖은 늘 지금 장소입니다.
            val offLadder = if (kind == RewardKind.MOTION) LEARNED else emptyList()
            val shelf = rung + offLadder
            if (shelf.isEmpty()) return@forEach
            rightPage.addView(section(kind.shelf))
            addGrid(shelf, owned, keep.form, pet)
        }
        // 강가 손님 도감. 강가가 열린 뒤에만 보입니다.
        if (keep.places.isOpen(Place.RIVER)) {
            rightPage.addView(section("강가 손님"))
            addGuests(keep.places)
        }
        rightPage.addView(note)
    }

    /** 만난 손님은 그림과 다녀간 횟수, 못 만난 손님은 빈 동그라미와 구름이의 힌트 한마디입니다. 점수판처럼 숫자를 앞세우지 않습니다. */
    private fun addGuests(places: PlaceState) {
        val cell = (min(rightPage.width.takeIf { it > 0 } ?: rightScroll.width, rightScroll.width) / COLUMNS)
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Guest.entries.filter { it.place == Place.RIVER }.forEach { guest ->
            val met = guest in places.met
            val art = SpiritSprites.named(this, "guest_${guest.id}_sit").takeIf { met && it != 0 }?.let {
                val source = SpiritSprites.bitmap(this, it)
                SpiritSprites.reduce(source, SpiritSprites.painted(source), (cell * GUEST_TILE).toInt())
            }
            val tile = RewardTile(this, art, if (met) null else "?", chosen = false, focus = 0.5f)
            tile.contentDescription = if (met) guest.title else "아직 못 만난 손님"
            tile.setOnClickListener {
                sounds.pop()
                rightScroll.post { rightScroll.smoothScrollTo(0, rightPage.height) }
                caption?.text = if (met) {
                    val traces = places.collected(guest).takeIf { it > 0 }?.let { " ${guest.traceTitle} ${it}개를 주웠어." }.orEmpty()
                    "${Josa.subject(guest.title)} ${places.visits(guest)}번 놀러 왔어.$traces"
                } else {
                    guest.hint + "."
                }
            }
            strip.addView(tile, LinearLayout.LayoutParams(cell, cell))
        }
        rightPage.addView(strip)
    }

    private fun addGrid(rewards: List<Reward>, owned: Set<Reward>, form: Reward?, pet: PetState) {
        val cell = (min(rightPage.width.takeIf { it > 0 } ?: rightScroll.width, rightScroll.width) / COLUMNS)
        rewards.chunked(COLUMNS).forEach { row ->
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { reward ->
                val has = reward in owned
                val focus = if (reward == Reward.SCENE_LIGHTHOUSE) LIGHTHOUSE_FOCUS else 0.5f
                // 고른 칸 동그라미는 입은 옷에만 둡니다. 창밖 칸은 받으면 늘 있어 고르지 않습니다.
                val tile = RewardTile(this, if (has) thumbnail(reward) else null, lockLabel(reward), reward == form, focus, scene = reward.kind == RewardKind.SCENE)
                tile.contentDescription = if (has) reward.title else "아직 열리지 않은 칸"
                tile.setOnClickListener { onTile(reward, has, pet) }
                strip.addView(tile, LinearLayout.LayoutParams(cell, cell))
            }
            rightPage.addView(strip)
        }
    }

    private fun onTile(reward: Reward, has: Boolean, pet: PetState) {
        sounds.pop()
        // 칸의 설명은 쪽 맨 아래에 써집니다. 글까지 보이게 내립니다.
        rightScroll.post { rightScroll.smoothScrollTo(0, rightPage.height) }
        if (!has) {
            caption?.text = lockedHint(reward, pet)
            return
        }
        when (reward.kind) {
            RewardKind.FORM -> KeepsakeRepository.updateGifts { it.wear(if (it.form == reward) null else reward) }
            // 강가 나비와 돛배는 받으면 늘 강가에 있습니다. 골라 켜는 칸이 아닙니다.
            RewardKind.SCENE -> Unit
            RewardKind.MOTION -> actor.play(reward)
            RewardKind.DECOR -> Unit
        }
        // 고르면 쪽을 다시 그리므로 글은 그 뒤에 씁니다.
        caption?.text = reward.title
    }

    /** 잠긴 칸의 글자. 습관과 정비 칸은 눌러서 봅니다. */
    private fun lockLabel(reward: Reward): String? = if (reward in Ladder.RUNGS) "Lv.${Ladder.level(reward)}" else null

    /** 사다리 칸은 무엇이 나올지 말하지 않습니다. 습관 칸은 무엇을 하면 되는지 알려 줍니다. */
    private fun lockedHint(reward: Reward, pet: PetState): String {
        val badge = Badge.entries.firstOrNull { it.reward == reward }
        if (badge != null) return "${badge.hint}. ${dots(badge.progress(pet), badge.target)}"
        val item = CareRules.teacher(reward, KeepsakeRepository.care.kind)
        if (item != null) return "${Josa.objective(item.title)} 챙기면 배워."
        return "Lv.${Ladder.level(reward)}에서 열려."
    }

    /** 분수로 쓰면 점수판처럼 보입니다. 열 칸이 차오르는 모습만 보여 줍니다. */
    private fun dots(progress: Int, target: Int): String {
        val filled = (progress * DOTS / target).coerceIn(0, DOTS)
        return "●".repeat(filled) + "○".repeat(DOTS - filled)
    }

    private fun thumbnail(reward: Reward): Bitmap {
        val own = when (reward.kind) {
            RewardKind.FORM -> SpiritSprites.named(this, "hero_${reward.id}")
            RewardKind.DECOR -> if (reward == Reward.DECOR_FERN) plantArt() else SpiritSprites.named(this, HomeWorld.propArt(reward))
            RewardKind.SCENE -> SpiritSprites.named(this, "window_${reward.id}")
            RewardKind.MOTION -> SpiritSprites.named(this, "hero_${MOTION_POSE[reward] ?: reward.id}")
        }
        if (reward.kind == RewardKind.SCENE) {
            val source = if (own != 0) own else if (reward == Reward.SCENE_WINTER) R.drawable.window_snow else R.drawable.window_day
            return SpiritSprites.thumbnail(this, source, SCENE_SAMPLE)
        }
        val art = SpiritSprites.bitmap(this, if (own != 0) own else R.drawable.hero_cheer)
        // 매단 모빌은 끈이 길어 칸에 통째로 넣으면 테가 점처럼 작아집니다. 테와 깃털이 달린 아래쪽만 씁니다.
        if (reward == Reward.DECOR_MOBILE && own != 0) {
            val top = (art.height * MOBILE_HOOP_TOP).toInt()
            return Bitmap.createBitmap(art, 0, top, art.width, art.height - top)
        }
        return art
    }

    private fun plantArt() = SpiritSprites.named(this, "plant_${TimeTogether.plantStep(PetRepository.state.together.days)}")

    /* 함께한 기록 */

    private fun renderAlbum() {
        val pet = PetRepository.state
        val keep = KeepsakeRepository.gifts
        seen.moments = seen.moments + keep.album.map { it.id }
        leftPage.addView(title("지난 일기"))
        val trip = pet.lastTrip
        // 아기가 새 일을 배운 트립이면 셋째 줄이 붙습니다. 서비스 대체 낭독처럼 gifts를 넘깁니다.
        val lines = trip?.let { DiaryTemplate.lines(it, pet, FamilyRepository.state, keep) } ?: listOf("아직 같이 달린 적이 없어.")
        lines.filter { it.isNotBlank() }.forEach { leftPage.addView(line(it)) }
        if (trip != null && getString(R.string.share_base_url).isNotBlank()) {
            leftPage.addView(paperText("가족에게 보내기", bold = true).apply {
                gravity = Gravity.CENTER
                background = PaperDrawable(context, 1.6f * unit)
                sizeTo(3f * unit, 15f)
                setPadding((2.4f * unit).toInt(), (1.2f * unit).toInt(), (2.4f * unit).toInt(), (1.2f * unit).toInt())
                isClickable = true
                setOnClickListener { if (mayOpen()) startActivity(PostcardActivity.intent(this@BookActivity)) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = unit.toInt()
            })
        }
        if (keep.shelf.isNotEmpty()) {
            leftPage.addView(section("친구들이 주워 온 것"))
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val size = (8f * unit).toInt()
            keep.shelf.takeLast(SHELF_SHOWN).forEach { gift ->
                val art = SpiritSprites.named(this, "return_${gift.kind.id}")
                if (art != 0) {
                    strip.addView(ImageView(this).apply {
                        setImageBitmap(SpiritSprites.bitmap(context, art))
                        contentDescription = gift.kind.title
                    }, LinearLayout.LayoutParams(size, size))
                }
            }
            leftPage.addView(strip)
        }
        rightPage.addView(section("함께한 순간"))
        val cell = rightScroll.width / MOMENT_COLUMNS
        // 일어난 순서대로 앞에서부터 채웁니다.
        (keep.album.toList() + Moment.entries.filterNot { it in keep.album }).chunked(MOMENT_COLUMNS).forEach { row ->
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { moment ->
                val open = moment in keep.album
                strip.addView(MomentFrame(this, FRAME_TILT[moment.ordinal % FRAME_TILT.size], if (open) SpiritSprites.thumbnail(this, Postcards.art(this, moment), MOMENT_SAMPLE) else null, moment.title).apply {
                    contentDescription = if (open) moment.title else "빈 사진 자리"
                    if (open) setOnClickListener { showMoment(moment) }
                }, LinearLayout.LayoutParams(cell, cell * 9 / 10))
            }
            rightPage.addView(strip)
        }
        // 좁은 창의 왼쪽 쪽은 낮아서 사진이 잘립니다. 오른쪽 쪽 끝에 둡니다.
        if (spread.visibility == View.VISIBLE) RecallShelf.addTo(leftPage, this, unit, leftScroll.width, recallNote)
        else RecallShelf.addTo(rightPage, this, unit, rightScroll.width, recallNote)
    }

    /** 순간을 크게 펼칩니다. 주소가 정해져 있으면 그 자리에서 폰으로 보낼 수 있습니다. */
    private fun showMoment(moment: Moment) {
        if (!mayOpen()) return
        sounds.pop()
        startActivity(PostcardActivity.intent(this, moment))
    }

    /**
     * 책의 입구가 함께 쓰는 막이입니다. 한 번 열면 책이 다시 앞에 올 때까지 열지 않습니다.
     * 0.1초 안에 두 번 누르면 엽서가 덮기 전에 두 번째도 받아 두 겹으로 떴습니다.
     * 홈 입구와 달리 시간으로 풀지 않습니다. 여는 화면이 엽서뿐이라 열면 책이 늘 가려졌다 돌아옵니다.
     * 그림책 표지는 RecallShelf가 따로 막습니다.
     */
    private fun mayOpen(): Boolean {
        if (opened) return false
        opened = true
        return true
    }

    /* 정비 수첩 */

    private fun renderCare() {
        val pet = PetRepository.state
        val care = KeepsakeRepository.care
        val now = System.currentTimeMillis()
        val odo = KeepsakeRepository.odoKm ?: care.firstOdoKm ?: 0f
        val needs = pet.ailments.isNotEmpty() || care.nextDue(odo, now) != null
        leftPage.addView(title("정비 수첩"))
        val helper = (24f * unit).toInt()
        actor.setArt(HeroArts.of(this, if (needs) HeroPose.POINT else HeroPose.STAND, KeepsakeRepository.gifts.form))
        actor.unit = helper / ACTOR_HEIGHT
        actor.setMotions(emptyList())
        leftPage.addView(line(HomeSpeech.careBookLine(care.stars + pet.stars, needs).replace("\n", " ")))
        CareRules.items(care.kind).forEach { item -> leftPage.addView(careRow(item, odo, now)) }
        // 구름이는 왼쪽 쪽 아래에 섭니다. 오른쪽 맨 아래에 두면 기록이 길 때 머리만 쪽 끝에 걸렸습니다.
        leftPage.addView(actor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (30f * unit).toInt()))

        if (pet.ailments.isNotEmpty()) {
            rightPage.addView(section("지금 살펴볼 곳"))
            pet.ailments.sorted().forEach { code ->
                rightPage.addView(paperText(CareTerms.title(code), bold = true).apply { sizeTo(3.2f * unit, 16f) })
                rightPage.addView(line(PetState.healthLine(code), GameStyle.INK_SOFT, 2.8f))
            }
        }
        // 경고등이 켜진 동안에는 그 말이 먼저입니다. 주기만 보는 몸 상태와 말이 어긋납니다.
        if (pet.ailments.isEmpty()) {
            rightPage.addView(section("구름이는 지금"))
            CareRules.items(care.kind).forEach { rightPage.addView(line(HomeSpeech.careFeeling(it, care.status(it, odo, now)), size = 3f)) }
        }
        rightPage.addView(section("챙긴 기록"))
        val records = care.records.takeLast(RECORDS_SHOWN).asReversed()
        if (records.isEmpty()) rightPage.addView(line("아직 적힌 게 없어.", GameStyle.INK_SOFT))
        val date = SimpleDateFormat("M월 d일", Locale.KOREA)
        records.forEach { rightPage.addView(line("${date.format(Date(it.atMs))} ${it.item.title}${if (it.starred) " ★" else ""}", size = 3f)) }
        if (pet.stars > 0) rightPage.addView(line("경고등을 살펴서 받은 별 ${pet.stars}개 ★", size = 3f))
        rightPage.addView(line("주기는 일반적인 기준이야. 차량 설명서를 먼저 따라 줘.", GameStyle.INK_SOFT, 2.6f))
    }

    private fun careRow(item: CareItem, odo: Float, now: Long): View {
        val care = KeepsakeRepository.care
        val status = care.status(item, odo, now)
        val held = item in pendingCare
        val asking = confirming == item
        val state = when {
            held -> "챙겼어! 고마워"
            asking -> "아직 때가 아니야. 그래도 챙겼어?"
            status == CareStatus.DUE -> "때가 됐어"
            status == CareStatus.SOON -> "곧 챙겨 줘"
            else -> restText(item, odo, now)
        }
        // 코랄 글자는 종이와 대비 4.5:1을 넘는 진한 코랄로 씁니다.
        val stateColor = when {
            held -> GameStyle.INK
            asking || status != CareStatus.OK -> GameStyle.CORAL_INK
            else -> GameStyle.INK_SOFT
        }
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(paperText(item.title, bold = true).apply { sizeTo(3.2f * unit, 16f) })
            addView(paperText(GameStyle.keepWords(state), color = stateColor).apply { sizeTo(2.7f * unit, 14f) })
        }
        val label = when {
            held -> "되돌리기"
            asking -> "응, 챙겼어"
            else -> "챙겼어"
        }
        val button = paperText(label, bold = true).apply {
            gravity = Gravity.CENTER
            background = PaperDrawable(context, 1.6f * unit)
            sizeTo(2.8f * unit, 14f)
            isClickable = true
            setPadding((2f * unit).toInt(), 0, (2f * unit).toInt(), 0)
            setOnClickListener {
                when {
                    held -> undoCare(item)
                    asking || status != CareStatus.OK -> holdCare(item)
                    else -> askCare(item)
                }
            }
        }
        // 한 번이라도 챙긴 곳은 도장이 또렷합니다. 아직이면 연하게 찍혀 있습니다.
        val stamp = ImageView(this).apply {
            setImageBitmap(SpiritSprites.bitmap(context, careStamp(item)))
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = if (care.has(item) || held) 1f else STAMP_FAINT
            rotation = STAMP_TILT
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (0.9f * unit).toInt(), 0, (0.9f * unit).toInt())
            addView(stamp, LinearLayout.LayoutParams((6.8f * unit).toInt(), (6.8f * unit).toInt()).apply { marginEnd = (1.6f * unit).toInt() })
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (6.4f * unit).toInt()))
        }
    }

    /** 때가 아닌데 누르면 한 번 더 묻습니다. 몇 초 안에 다시 누르지 않으면 그대로 둡니다. */
    private fun askCare(item: CareItem) {
        sounds.pop()
        confirming = item
        handler.removeCallbacks(endConfirm)
        handler.postDelayed(endConfirm, CONFIRM_MS)
        render()
    }

    /** 챙긴 것으로 보고 되돌리기를 띄웁니다. 원장에는 되돌릴 틈이 지난 뒤 누른 시각으로 적습니다. */
    private fun holdCare(item: CareItem) {
        sounds.sparkle()
        handler.removeCallbacks(endConfirm)
        confirming = null
        pendingCare[item] = System.currentTimeMillis()
        flushCare()
        render()
    }

    private fun undoCare(item: CareItem) {
        sounds.pop()
        pendingCare.remove(item)
        flushCare()
        render()
    }

    /** 되돌릴 틈이 지난 기록을 원장에 적고, 남은 것이 있으면 다음 차례를 맞춥니다. [all]이면 남은 것도 모두 적습니다. */
    private fun flushCare(all: Boolean = false) {
        handler.removeCallbacks(commitCare)
        val now = System.currentTimeMillis()
        val ready = pendingCare.filterValues { all || now - it >= UNDO_MS }
        ready.keys.forEach(pendingCare::remove)
        ready.forEach { (item, atMs) -> KeepsakeRepository.markDone(item, atMs) }
        pendingCare.values.minOrNull()?.let { first -> handler.postDelayed(commitCare, (first + UNDO_MS - now).coerceAtLeast(0L)) }
    }

    private fun careStamp(item: CareItem) = when (item) {
        CareItem.OIL -> R.drawable.stamp_oil
        CareItem.ROTATION -> R.drawable.stamp_tire
        CareItem.FILTER -> R.drawable.stamp_filter
        CareItem.WASH -> R.drawable.stamp_wash
    }

    private fun restText(item: CareItem, odo: Float, now: Long): String {
        val care = KeepsakeRepository.care
        care.leftKm(item, odo)?.let { return "${"%,d".format(it.roundToInt())} km 남았어" }
        if (!care.has(item)) return "아직 챙긴 적 없어"
        val days = care.daysSince(item, now)?.toInt() ?: 0
        return if (days == 0) "오늘 챙겼어" else "${days}일 전에 챙겼어"
    }

    companion object {
        private const val EXTRA_PAGE = "page"

        /** 쪽 위아래 끝을 흐리게 하는 길이(unit). 그림책 칸 한 줄의 절반쯤입니다. */
        private const val PAGE_FADE = 6f
        private const val WIDE_RATIO = 1.15f
        private const val SPREAD_W = 1536f
        private const val SPREAD_H = 1024f

        /** 책 그림에서 글을 얹을 자리. */
        private const val LEFT_X = 170f
        private const val RIGHT_X = 860f
        private const val PAGE_W = 510f
        private const val PAGE_TOP = 190f
        private const val PAGE_BOTTOM = 800f

        private const val COLUMNS = 6

        /** 손님 칸 그림을 칸의 이만큼 높이로 미리 줄입니다. */
        private const val GUEST_TILE = 0.8f
        private const val DOTS = 10
        private val FRAME_TILT = floatArrayOf(-2f, 1.5f, -1f, 2f, -1.5f, 1f, 2f, -2f)
        private const val MOMENT_COLUMNS = 3
        private const val MOMENT_SAMPLE = 4
        private const val SCENE_SAMPLE = 8
        private const val MOBILE_HOOP_TOP = 0.52f // 매단 모빌 그림에서 테가 시작하는 높이(그림 키에 대한 비율, 테 위 끈 조금 포함)

        /** 등대는 그림 오른쪽에 있어서 가운데를 내다보면 빈 바다만 보입니다. */
        private const val LIGHTHOUSE_FOCUS = 0.86f
        private const val STAMP_FAINT = 0.4f

        /** 동작 칸의 그림. 같은 얼굴이 줄지어 있으면 뭐가 다른지 안 보입니다. */
        private val MOTION_POSE = mapOf(
            Reward.MOTION_TILT to "tilt",
            Reward.MOTION_STRETCH to "stretch",
            Reward.MOTION_NOD to "thanks",
            Reward.MOTION_HOP to "cheer",
            Reward.MOTION_FLAP to "wave",
            Reward.MOTION_SHAKE to "stand",
            Reward.MOTION_WAG to "point",
            Reward.MOTION_SWAY to "holdjar",
            Reward.MOTION_HUM to "letter",
        )
        private const val STAMP_TILT = -6f

        /** "챙겼어" 뒤 되돌릴 수 있는 시간과, 때가 아닌 곳을 한 번 더 묻는 시간. */
        private const val UNDO_MS = 5_000L
        private const val CONFIRM_MS = 4_000L
        private const val SHELF_SHOWN = 6
        private const val RECORDS_SHOWN = 6
        private const val ACTOR_HEIGHT = 560f

        /** 성장 기록 왼쪽 쪽의 글줄 자리(이름, 레벨, 병, 다음 선물). 남는 높이를 구름이 그림이 씁니다. */
        private const val GROWTH_TEXT_ROOM = 24f

        /** 닮은 습관 줄 하나의 자리. 글 3.2와 위아래 여백에 한 줄이 넘칠 때를 조금 더 둡니다. */
        private const val LIKENESS_ROOM = 7f

        private val LEARNED = listOf(
            Reward.MOTION_SWAY, Reward.MOTION_TILT, Reward.MOTION_NOD,
            Reward.MOTION_SHAKE, Reward.MOTION_HOP, Reward.MOTION_STRETCH,
        )

        fun intent(context: Context, page: Page): Intent =
            Intent(context, BookActivity::class.java).putExtra(EXTRA_PAGE, page.ordinal)
    }
}

/**
 * 수집 칸 하나. 가진 것은 그림으로, 아직 없는 것은 점선 동그라미로 보여 줍니다. [focus]는 띠 그림에서 내다볼 곳입니다.
 * [scene]은 창밖 풍경 칸입니다. 둥근 창으로 자르는 것은 이 칸뿐입니다.
 */
private class RewardTile(context: Context, private val art: Bitmap?, private val lock: String?, private val chosen: Boolean, private val focus: Float, private val scene: Boolean = false) : View(context) {

    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GameStyle.INK_SOFT
        typeface = GameStyle.BODY
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val window = Path()

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val radius = size * 0.42f
        val cx = width / 2f
        val cy = height / 2f
        if (art == null) {
            ring.color = EMPTY
            ring.strokeWidth = size * 0.02f
            ring.pathEffect = DashPathEffect(floatArrayOf(size * 0.05f, size * 0.04f), 0f)
            canvas.drawCircle(cx, cy, radius, ring)
            if (lock != null) {
                label.textSize = size * 0.2f
                canvas.drawText(lock, cx, cy + label.textSize * 0.36f, label)
            }
            return
        }
        // 비율만 보면 옆으로 긴 접시와 깃털 방석도 창처럼 잘렸습니다. 갈래를 먼저 봅니다.
        // 나비와 돛배의 창밖 그림은 3:2라 지금처럼 통째로 둡니다.
        if (scene && art.width > art.height * WIDE_ART) {
            // 창밖 그림은 띠처럼 길어서 동그란 창으로 가운데를 내다봅니다.
            val side = art.height
            val left = ((art.width - side) * focus).toInt()
            rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            window.reset()
            window.addCircle(cx, cy, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(window)
            canvas.drawBitmap(art, Rect(left, 0, left + side, side), rect, image)
            canvas.restore()
        } else {
            val scale = min(radius * 1.76f / art.width, radius * 1.76f / art.height)
            val w = art.width * scale
            val h = art.height * scale
            rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            canvas.drawBitmap(art, null, rect, image)
        }
        if (chosen) {
            ring.color = GameStyle.CORAL
            ring.strokeWidth = size * 0.035f
            ring.pathEffect = null
            canvas.drawCircle(cx, cy, radius, ring)
        }
    }

    private companion object {
        const val EMPTY = 0xFFB08F76.toInt()
        const val WIDE_ART = 1.6f
    }
}

/** 앨범의 사진 자리. 빈 자리는 네 귀퉁이만 있어서 무엇이 올지 말하지 않습니다. */
private class MomentFrame(context: Context, tilt: Float, private val art: Bitmap?, private val title: String) : View(context) {

    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EMPTY
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GameStyle.PAPER }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GameStyle.INK
        typeface = GameStyle.BODY
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    init {
        isClickable = art != null
        rotation = tilt
    }

    override fun onDraw(canvas: Canvas) {
        val pad = min(width, height) * 0.06f
        rect.set(pad, pad, width - pad, height - pad)
        if (art != null) {
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), pad * 0.5f, pad * 0.5f, paper)
            rect.bottom = height * PHOTO_BOTTOM
            val scale = max(rect.width() / art.width, rect.height() / art.height)
            val w = rect.width() / scale
            val h = rect.height() / scale
            val left = ((art.width - w) / 2f).toInt()
            val top = ((art.height - h) / 2f).toInt()
            canvas.drawBitmap(art, Rect(left, top, left + w.toInt(), top + h.toInt()), rect, image)
            label.textSize = height * TITLE_SIZE
            canvas.drawText(title, width / 2f, height * TITLE_LINE, label)
            return
        }
        corner.strokeWidth = pad * 0.5f
        val arm = pad * 2.4f
        canvas.drawLine(rect.left, rect.top, rect.left + arm, rect.top, corner)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + arm, corner)
        canvas.drawLine(rect.right, rect.top, rect.right - arm, rect.top, corner)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + arm, corner)
        canvas.drawLine(rect.left, rect.bottom, rect.left + arm, rect.bottom, corner)
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - arm, corner)
        canvas.drawLine(rect.right, rect.bottom, rect.right - arm, rect.bottom, corner)
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - arm, corner)
    }

    private companion object {
        const val EMPTY = 0xFFC9AE96.toInt()
        const val PHOTO_BOTTOM = 0.8f
        const val TITLE_SIZE = 0.105f
        const val TITLE_LINE = 0.935f
    }
}
