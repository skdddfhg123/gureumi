package com.example.pleosapp.ui.river

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.ColorFilter
import android.graphics.text.LineBreaker
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.pleosapp.R
import com.example.pleosapp.engine.DayPart
import com.example.pleosapp.engine.DriveEvent
import com.example.pleosapp.engine.DrivingGate
import com.example.pleosapp.engine.FamilyRepository
import com.example.pleosapp.engine.Guest
import com.example.pleosapp.engine.Josa
import com.example.pleosapp.engine.KeepsakeRepository
import com.example.pleosapp.engine.PetRepository
import com.example.pleosapp.engine.Place
import com.example.pleosapp.engine.ReturnKind
import com.example.pleosapp.engine.Reward
import com.example.pleosapp.engine.RewardKind
import com.example.pleosapp.engine.SkySignals
import com.example.pleosapp.engine.Visit
import com.example.pleosapp.ui.game.GameStyle
import com.example.pleosapp.ui.game.GameStyle.paperText
import com.example.pleosapp.ui.game.GameStyle.sizeTo
import com.example.pleosapp.ui.game.HomeScene
import com.example.pleosapp.ui.game.MotionTicker
import com.example.pleosapp.ui.game.NookLight
import com.example.pleosapp.ui.game.PaperDrawable
import com.example.pleosapp.ui.game.SoftSounds
import com.example.pleosapp.ui.game.SpeechBubbleView
import com.example.pleosapp.ui.game.StageLayout
import com.example.pleosapp.ui.game.TextRules
import com.example.pleosapp.ui.recall.SeasonDrift
import com.example.pleosapp.ui.spirit.FriendArts
import com.example.pleosapp.ui.spirit.FriendPose
import com.example.pleosapp.ui.spirit.GuestArts
import com.example.pleosapp.ui.spirit.HeroArts
import com.example.pleosapp.ui.spirit.HeroPose
import com.example.pleosapp.ui.spirit.SpiritSprites
import com.example.pleosapp.ui.spirit.SpriteSpiritView
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 강가. 홈에서 왼쪽으로 넘기면 오는 두 번째 장소입니다. 물가 칸에 물건을 놓으면 다녀오는 동안 강가 손님이 들릅니다.
 * 손님은 좋아하는 물건 곁에 있다가 다음 도착까지 남고, 흔적을 떨어뜨립니다(docs/design/river-spec.md).
 * 주차 중에만 쓰며 출발하면 스스로 닫힙니다.
 */
class RiverActivity : Activity() {

    private lateinit var stage: StageLayout
    private lateinit var river: RiverView
    private lateinit var hero: SpriteSpiritView
    private lateinit var guest: SpriteSpiritView
    private lateinit var friends: List<SpriteSpiritView>
    private lateinit var title: TextView
    private lateinit var homeTab: TextView
    private lateinit var nextPlace: TextView
    private lateinit var bubble: SpeechBubbleView
    private lateinit var picker: LinearLayout
    private lateinit var sounds: SoftSounds
    private val ticker = MotionTicker(BUSY_FPS, IDLE_FPS)
    private var unit = 10f
    private var started = false
    private var downX = 0f
    private var downY = 0f
    private var picking: Int? = null
    private var shownVisit: Visit? = null

    /** 방금 한 일에 대한 한마디. 다음 화면 갱신까지 말풍선이 이 말을 합니다. */
    private var saying: String? = null

    /** 밤에 종이 이름표와 탭에 입히는 빛. 종이를 새로 만들 때마다 다시 입힙니다. */
    private var paperFilter: ColorFilter? = null

    private val onEvent: (DriveEvent) -> Unit = { event -> if (event is DriveEvent.TripStarted) runOnUiThread { finish() } }
    private val onChange: () -> Unit = { runOnUiThread(::render) }
    private val onRestriction: (Boolean) -> Unit = { runOnUiThread(::holdMotion) }

    /** 강가에 머무는 사이 해가 지면 배경도 바뀝니다. */
    private val sceneTick = object : Runnable {
        override fun run() {
            renderScene()
            river.postDelayed(this, SCENE_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sounds = SoftSounds(this)
        setContentView(buildStage())
        PetRepository.addListener(onEvent)
        KeepsakeRepository.addListener(onChange)
        FamilyRepository.addListener(onChange)
        SkySignals.addListener(onChange)
        DrivingGate.addListener(onRestriction)
        render()
    }

    override fun onStart() {
        super.onStart()
        started = true
        holdMotion()
    }

    override fun onStop() {
        started = false
        holdMotion()
        super.onStop()
    }

    override fun onDestroy() {
        PetRepository.removeListener(onEvent)
        KeepsakeRepository.removeListener(onChange)
        FamilyRepository.removeListener(onChange)
        SkySignals.removeListener(onChange)
        DrivingGate.removeListener(onRestriction)
        river.removeCallbacks(sceneTick)
        sounds.release()
        super.onDestroy()
    }

    /** 가려져 있거나 주행 제한 중이면 그리지 않습니다. */
    private fun holdMotion() {
        river.removeCallbacks(sceneTick)
        if (started && !DrivingGate.restricted) {
            ticker.start()
            river.postDelayed(sceneTick, SCENE_TICK_MS)
        } else {
            ticker.stop()
        }
    }

    private fun buildStage(): StageLayout {
        river = RiverView(this).apply {
            onSlot = ::openPicker
            onTrace = ::collect
        }
        hero = SpriteSpiritView(this).apply {
            contentDescription = "구름이"
            setOnClickListener { poke() }
        }
        guest = SpriteSpiritView(this).apply {
            visibility = View.GONE
            setOnClickListener { greetGuest() }
        }
        friends = List(RiverScene.FRIENDS.size) { SpriteSpiritView(this).apply { visibility = View.GONE } }
        title = paperText("강가", bold = true).apply { gravity = Gravity.CENTER }
        homeTab = paperText("‹ 집으로", bold = true).apply {
            gravity = Gravity.CENTER
            contentDescription = "집으로 돌아가기"
            setOnClickListener { goHome() }
        }
        // "쉼터"는 뜻이 여럿으로 읽혀 쓰지 않습니다(장면 규칙 2-8의 5). 밤에도 대비를 지키게 진한 글자입니다.
        // 손님을 다 만나면 엔진이 숲을 열지만 숲 화면은 아직 없습니다. 그때는 약속 대신 지금 상태를 말합니다.
        val forest = if (KeepsakeRepository.gifts.places.isOpen(Place.FOREST)) "숲길을 닦고 있어" else "손님을 다 만나면 숲길이 열려"
        nextPlace = paperText(forest, color = GameStyle.INK).apply { gravity = Gravity.CENTER }
        bubble = SpeechBubbleView(this)
        picker = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        ticker.add(hero, guest, *friends.toTypedArray())
        // 돛배가 있으면 돛과 물결 때문에 강가를 가끔 다시 그립니다. 없으면 다시 그리지 않습니다.
        ticker.addEvery({ if (river.boatShown) BOAT_REDRAW_MS else Long.MAX_VALUE }, river)
        ticker.busy = { hero.quick || guest.quick || friends.any { it.quick } }
        stage = StageLayout(this).apply {
            setBackgroundColor(GameStyle.INTERIOR)
            addView(river)
            friends.forEach(::addView)
            addView(guest)
            addView(hero)
            listOf<View>(title, homeTab, nextPlace, bubble, picker).forEach(::addView)
            onResize = ::arrange
        }
        stage.sortByFeet(friends + guest + hero)
        return stage
    }

    private fun arrange(w: Int, h: Int) {
        val u = min(w / 170f, h / 121f)
        unit = u
        val gap = (2.4f * u).toInt()
        river.fit(w, h)
        stage.place(river, 0, 0, w, h)
        placeActor(hero, RiverScene.HERO, river.sceneScale * RiverScene.HERO.height / HomeScene.HERO.height)
        friends.forEachIndexed { index, actor ->
            val spot = RiverScene.FRIENDS[index]
            placeActor(actor, spot, river.sceneScale * spot.height / FRIEND_HEIGHT)
        }
        val radius = 1.4f * u
        listOf(title, homeTab, nextPlace).forEach {
            it.background = paper(radius)
            it.setPadding(gap, 0, gap, 0)
        }
        // 장소 이름과 단추는 32, 안내는 24 이상, 말풍선은 36 이상입니다(장면 규칙 2-8).
        title.sizeTo(3.4f * u, TextRules.MAIN)
        homeTab.sizeTo(3.3f * u, TextRules.MAIN)
        nextPlace.sizeTo(2.6f * u, TextRules.SMALL)
        bubble.sizeTo(3.7f * u, TextRules.BUBBLE)
        bubble.setPaddingFor(u)
        val line = (7.4f * u).toInt()
        stage.place(title, gap, gap, (20f * u).toInt(), line)
        stage.place(homeTab, gap, h - gap - line, (26f * u).toInt(), line)
        val nextWidth = (paperWidth(nextPlace) + gap * 2).toInt()
        stage.place(nextPlace, w - gap - nextWidth, h - gap - (6.4f * u).toInt(), nextWidth, (6.4f * u).toInt())
        val bubbleLeft = river.viewX(RiverScene.HERO.x + BUBBLE_SIDE).toInt()
        // 돛배가 있으면 말풍선을 돛대 꼭대기 위로 올립니다. 말풍선이 잎 돛을 가리면 배가 막대기처럼 보입니다.
        river.boat = Reward.SCENE_BOAT in KeepsakeRepository.gifts.owned
        val bubbleBottom = minOf(RiverScene.HERO.bottom - BUBBLE_ABOVE, (river.boatTop ?: Float.MAX_VALUE) - BUBBLE_GAP)
        stage.place(bubble, bubbleLeft, 0, (50f * u).toInt(), river.viewY(bubbleBottom).toInt(), wrapHeight = true)
        // 고르는 칸은 하늘 쪽에 띄웁니다. 아래에 두면 놓을 칸이 가려져 어디에 놓는지 안 보입니다.
        stage.place(picker, gap * 4, gap * 2 + (8f * u).toInt(), w - gap * 8, (24f * u).toInt())
        shownVisit = null
        stage.requestLayout()
        render()
    }

    private fun placeActor(actor: SpriteSpiritView, spot: HomeScene.Spot, scale: Float) {
        val room = spot.height * river.sceneScale * ACTOR_ROOM
        actor.unit = scale
        stage.place(actor, (river.viewX(spot.x) - room / 2f).toInt(), (river.viewY(spot.bottom) - room).toInt(), room.toInt(), room.toInt())
    }

    private fun paper(radius: Float) = PaperDrawable(this, radius).apply { colorFilter = paperFilter }

    /** 글자 폭과 좌우 여백. */
    private fun paperWidth(view: TextView): Float = view.paint.measureText(view.text.toString()) + view.paddingLeft + view.paddingRight

    /** 강가도 집처럼 시간, 날씨, 계절을 따릅니다. 밤에는 정령과 종이도 같은 빛을 입습니다. 종이 규칙은 집과 같습니다. */
    private fun renderScene() {
        val state = NookLight.now()
        if (!river.light(state, SeasonDrift.season())) return
        val light = NookLight.ambient(state).takeIf { it != NO_TINT }
        (friends + guest + hero).forEach { it.setAmbient(light) }
        val night = state.part == DayPart.NIGHT
        paperFilter = GameStyle.paperLight(night)
        listOf(title, homeTab, nextPlace, picker).forEach { it.background?.colorFilter = paperFilter }
        bubble.setPaper(if (night) GameStyle.PAPER_NIGHT else GameStyle.PAPER)
    }

    private fun render() {
        renderScene()
        val gifts = KeepsakeRepository.gifts
        val places = gifts.places
        val placed = places.slots(Place.RIVER)
        river.show(placed, places.traces)
        river.boat = Reward.SCENE_BOAT in gifts.owned
        hero.setArt(HeroArts.of(this, HeroPose.SIT, gifts.form?.takeIf { it.kind == RewardKind.FORM }))
        // 강가에는 지금 함께 탄 아기가 둘까지 나옵니다.
        val riders = FamilyRepository.aboard.take(friends.size)
        friends.forEachIndexed { index, actor ->
            val spirit = riders.getOrNull(index)
            actor.visibility = if (spirit == null) View.GONE else View.VISIBLE
            if (spirit != null) actor.setArt(FriendArts.of(this, spirit.preset, FriendPose.SIT))
        }
        showGuest(places.visitor?.takeIf { it.guest.place == Place.RIVER }, placed)
        // 홈 말풍선처럼 낱말 가운데서 줄이 바뀌지 않게 묶습니다. 밀도가 높으면 "볼/까?"로 쪼개졌습니다.
        bubble.text = GameStyle.keepWords(saying ?: line(placed, places.visitor, places.traces))
    }

    /** 손님은 좋아하는 물건 곁에 섭니다. 그 물건을 치웠으면 첫 칸 곁에 섭니다. 그림이 없으면 그리지 않습니다. */
    private fun showGuest(visit: Visit?, placed: List<ReturnKind?>) {
        val art = visit?.let { GuestArts.of(this, it.guest, if (it.happy) "happy" else "visit") }
        if (visit == null || art == null) {
            guest.visibility = View.GONE
            shownVisit = null
            return
        }
        guest.contentDescription = visit.guest.title
        if (visit != shownVisit) {
            val slot = placed.indexOfFirst { it != null && it in visit.guest.likes }.takeIf { it >= 0 } ?: 0
            val spot = RiverScene.guestBeside(slot)
            val painted = SpiritSprites.painted(art.base).height().coerceAtLeast(1)
            placeActor(guest, spot, river.sceneScale * spot.height / painted)
            shownVisit = visit
            stage.requestLayout()
        }
        guest.setArt(art)
        guest.visibility = View.VISIBLE
    }

    /** 처음 보는 사람도 다음에 할 일을 알 수 있게, 지금 강가 상태에 맞는 한마디를 합니다. */
    private fun line(placed: List<ReturnKind?>, visit: Visit?, traces: List<Guest>): String = when {
        visit != null && traces.isNotEmpty() -> "${Josa.subject(visit.guest.title)} 놀러 왔어!\n떨어진 걸 눌러서 주워 봐."
        visit != null -> "${Josa.subject(visit.guest.title)} 놀러 왔어!\n눌러서 인사해 봐."
        traces.isNotEmpty() -> "누가 뭘 떨어뜨리고 갔어.\n눌러서 주워 봐."
        KeepsakeRepository.gifts.kinds.isEmpty() -> "아직 놓을 물건이 없어.\n다녀오면 하나씩 생겨."
        placed.all { it == null } -> "물가에 뭘 놓아 볼까?\n빈 동그라미를 눌러 봐."
        else -> "누가 놀러 올까?\n다녀오면 알 수 있어."
    }

    private fun greetGuest() {
        val visit = shownVisit ?: return
        guest.cheer()
        say("${Josa.subject(visit.guest.title)} 반가워해.")
    }

    private fun collect(guest: Guest) {
        KeepsakeRepository.updateGifts { it.collect(guest) }
        sounds.chime()
        hero.cheer()
        say("${Josa.objective(guest.traceTitle)} 주웠어.\n성장 기록에 붙였어.")
    }

    private fun say(text: String) {
        saying = text
        render()
        bubble.removeCallbacks(clearSaying)
        bubble.postDelayed(clearSaying, SAY_MS)
    }

    private val clearSaying = Runnable {
        saying = null
        render()
    }

    /** 칸을 누르면 가진 물건이 하늘 쪽에 펼쳐집니다. 놓인 칸이면 빼기도 함께 나옵니다. */
    private fun openPicker(index: Int) {
        picking = index
        picker.removeAllViews()
        picker.background = paper(1.4f * unit)
        val gifts = KeepsakeRepository.gifts
        val placed = gifts.places.slots(Place.RIVER)
        val usedElsewhere = placed.filterIndexed { i, kind -> i != index && kind != null }.toSet()
        val choices = gifts.kinds.filter { it !in usedElsewhere }
        if (choices.isEmpty()) {
            picker.addView(label("아직 놓을 물건이 없어. 다녀오면 생겨."))
        } else {
            // 물건이 많으면 줄 끝에서 이름이 잘렸습니다("동글한 열"). 물건 칸은 남은 폭을 똑같이 나눠 갖습니다.
            choices.forEach { kind -> picker.addView(itemButton(kind), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)) }
        }
        if (placed.getOrNull(index) != null) picker.addView(textButton("빼기") { place(null) })
        picker.addView(textButton("닫기") { closePicker() })
        picker.visibility = View.VISIBLE
    }

    private fun place(kind: ReturnKind?) {
        val index = picking ?: return
        KeepsakeRepository.updateGifts { if (kind == null) it.take(Place.RIVER, index) else it.put(Place.RIVER, index, kind) }
        closePicker()
        if (kind != null) {
            hero.cheer()
            say("${Josa.objective(kind.title)} 놓았어.\n누가 좋아할까?")
        } else {
            render()
        }
    }

    private fun closePicker() {
        picking = null
        picker.visibility = View.GONE
    }

    private fun itemButton(kind: ReturnKind): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val size = (11f * unit).roundToInt()
        val resource = SpiritSprites.named(context, "return_${kind.id}")
        if (resource != 0) {
            addView(ImageView(context).apply {
                setImageBitmap(SpiritSprites.reduce(SpiritSprites.bitmap(context, resource), null, size))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(size, size))
        }
        // 칸이 좁으면 두 줄로 내려 씁니다. 낱말 가운데서 자르지 않습니다.
        addView(label(kind.title).apply {
            sizeTo(2.1f * unit, 13f)
            maxLines = 2
            // 정적 검사(lint)가 바라는 상수입니다. 값은 Layout 상수와 같습니다.
            // 컴파일 때 숫자로 들어가 API 28 기기도 LineBreaker 클래스를 찾지 않습니다.
            breakStrategy = LineBreaker.BREAK_STRATEGY_BALANCED
        })
        contentDescription = "${kind.title} 놓기"
        setPadding((1.4f * unit).toInt(), 0, (1.4f * unit).toInt(), 0)
        setOnClickListener { place(kind) }
    }

    private fun textButton(text: String, onClick: () -> Unit): View = paperText(text, bold = true).apply {
        gravity = Gravity.CENTER
        sizeTo(2.5f * unit, 14f)
        setPadding((2f * unit).toInt(), 0, (2f * unit).toInt(), 0)
        setOnClickListener { onClick() }
    }

    private fun label(text: String): TextView = paperText(text).apply {
        gravity = Gravity.CENTER
        sizeTo(2.3f * unit, 14f)
    }

    /** 오른쪽으로 넘기면 집으로 돌아갑니다. 위아래로 많이 움직였으면 넘김으로 보지 않습니다. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx > stage.width * SWIPE_SHARE && abs(dy) < stage.height * SWIPE_SLACK && picking == null) {
                    goHome()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    /** 뒤로 가기는 열린 고르기부터 닫습니다. 고르기가 없으면 집으로 넘어갑니다. */
    @Deprecated("주차 중 화면이라 예측 뒤로 가기 애니메이션을 쓰지 않습니다.")
    override fun onBackPressed() {
        if (picking != null) closePicker() else goHome()
    }

    private fun goHome() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.place_in_left, R.anim.place_out_right)
    }

    companion object {
        private const val BUSY_FPS = 15
        private const val IDLE_FPS = 9
        private const val ACTOR_ROOM = 1.5f
        private const val FRIEND_HEIGHT = 260f
        private const val BUBBLE_SIDE = 120f
        private const val BUBBLE_ABOVE = 330f
        private const val BUBBLE_GAP = 14f // 돛대 꼭대기와 말풍선 사이(강가 그림 좌표)
        private const val SWIPE_SHARE = 0.22f
        private const val SWIPE_SLACK = 0.15f
        private const val SAY_MS = 4_000L
        private const val SCENE_TICK_MS = 60_000L
        private const val BOAT_REDRAW_MS = 250L
        private const val NO_TINT = 0xFFFFFF

        fun intent(context: Context): Intent = Intent(context, RiverActivity::class.java)
    }
}
