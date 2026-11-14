package com.example.pleosapp.ui.home

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import com.example.pleosapp.R
import com.example.pleosapp.engine.CareRules
import com.example.pleosapp.engine.DayPart
import com.example.pleosapp.engine.DriveEvent
import com.example.pleosapp.engine.DrivingGate
import com.example.pleosapp.engine.FamilyRepository
import com.example.pleosapp.engine.FamilySpeech
import com.example.pleosapp.engine.HomeSpeech
import com.example.pleosapp.engine.Josa
import com.example.pleosapp.engine.KeepsakeRepository
import com.example.pleosapp.engine.Ladder
import com.example.pleosapp.engine.Mood
import com.example.pleosapp.engine.PetRepository
import com.example.pleosapp.engine.Place
import com.example.pleosapp.engine.RecallId
import com.example.pleosapp.engine.Reward
import com.example.pleosapp.engine.RewardKind
import com.example.pleosapp.engine.SceneState
import com.example.pleosapp.engine.SeatSlot
import com.example.pleosapp.engine.SkySignals
import com.example.pleosapp.engine.SpiritPhrases
import com.example.pleosapp.engine.TimeTogether
import com.example.pleosapp.spike.ProbeState
import com.example.pleosapp.spike.VehicleProbeService
import com.example.pleosapp.spike.VerifyBuild
import com.example.pleosapp.spike.VoiceProbe
import com.example.pleosapp.ui.book.BookActivity
import com.example.pleosapp.ui.family.FamilyActivity
import com.example.pleosapp.ui.game.FeatherBurstView
import com.example.pleosapp.ui.game.GameStyle
import com.example.pleosapp.ui.game.GameStyle.paperText
import com.example.pleosapp.ui.game.GameStyle.sizeTo
import com.example.pleosapp.ui.game.GiftChooser
import com.example.pleosapp.ui.game.GiftReveal
import com.example.pleosapp.ui.game.HomeScene
import com.example.pleosapp.ui.game.HomeWorld
import com.example.pleosapp.ui.game.JarView
import com.example.pleosapp.ui.game.MotionTicker
import com.example.pleosapp.ui.game.NookLight
import com.example.pleosapp.ui.game.PaperDrawable
import com.example.pleosapp.ui.game.PaperNote
import com.example.pleosapp.ui.game.ScenePreview
import com.example.pleosapp.ui.game.Seen
import com.example.pleosapp.ui.game.SoftSounds
import com.example.pleosapp.ui.game.SpeechBubbleView
import com.example.pleosapp.ui.game.StageLayout
import com.example.pleosapp.ui.game.TextRules
import com.example.pleosapp.ui.recall.RecallHost
import com.example.pleosapp.ui.river.RiverActivity
import com.example.pleosapp.ui.spirit.DuoScene
import com.example.pleosapp.ui.spirit.FriendPose
import com.example.pleosapp.ui.spirit.HeroPose
import com.example.pleosapp.ui.spirit.SpiritSprites
import kotlin.math.min
import kotlin.math.sin

/**
 * 구름이네 집. 앱의 첫 화면이자 서비스의 두 번째 시작 경로입니다. 상태는 읽기만 합니다.
 * 내 차는 구름이네 가족의 집이고, 내가 달린 만큼 그 집이 자랍니다. 창밖은 차를 대어 둔 강가이고,
 * 병에는 깃털이 쌓이고, 아기는 함께 탈 때 깨어나고, 주워 온 것은 선반에 남습니다. 말풍선은 일을 시키지 않고 초대만 합니다.
 * 누를 곳은 물건입니다: 구름이, 깃털 병, 수첩, 앨범, 아기. 글자 칩은 장소 이름과 마이크뿐입니다(컨셉 점검 5-4).
 */
class HomeActivity : Activity() {

    /** 집 안의 입구. 넓은 창에서는 물건에 붙는 꼬리표이고, 좁은 창에서는 아래쪽 탭입니다. 누르는 곳은 물건 전체입니다. */
    private inner class Door(val spot: HomeScene.Spot?, val art: Int, label: String, val onOpen: () -> Unit) {
        val tag: TextView = paperText(label, bold = true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { onOpen() }
        }
        val hit = View(this@HomeActivity).apply {
            contentDescription = label
            setOnClickListener { onOpen() }
        }
    }

    private lateinit var stage: StageLayout
    private lateinit var world: HomeWorld
    private lateinit var sounds: SoftSounds
    private lateinit var bubble: SpeechBubbleView
    private lateinit var nameTag: TextView
    private lateinit var doors: List<Door>
    private lateinit var growthDoor: Door
    private lateinit var careDoor: Door
    private lateinit var careDot: View
    private lateinit var albumDot: View
    private lateinit var albumDoor: Door
    private lateinit var seen: Seen

    /** 아기가 하나도 없을 때만 담요 위에 놓이는 꼬리표. 가족 액자를 뺀 뒤 아기를 처음 데려오는 곳입니다. */
    private lateinit var familyDoor: Door

    /** 강가로 가는 탭. 첫 물건이 생겨 강가가 열린 뒤에만 보입니다. 배경에 옆문이 그려지면 문으로 바꿉니다. */
    private lateinit var riverTab: TextView
    private var downX = 0f
    private var downY = 0f
    private lateinit var burst: FeatherBurstView
    private lateinit var note: PaperNote
    private lateinit var reveal: GiftReveal
    private lateinit var chooser: GiftChooser
    private lateinit var recall: RecallHost

    /** 속도를 왜 보는지 먼저 말하는 쪽지. 누르면 허락을 묻습니다. 한 번 연 홈에서는 한 번만 띄웁니다. */
    private lateinit var speedNote: PaperNote
    private var speedNoteShown = false

    /**
     * 속도 허락 창을 띄우기 직전의 이유 표시(`shouldShowRequestPermissionRationale`)와 시각입니다.
     * 답 없이 닫힌 창을 가리는 데 씁니다. 이유 표시는 한 번 거절하면 참이 되고, 두 번 거절하면 다시 거짓입니다.
     * 창이 떠 있는 사이 홈을 다시 만들면 기본값으로 돌아갑니다. 기본값은 물은 기록을 지우지 않는 쪽입니다.
     */
    private var speedRationaleBefore = true
    private var speedAskedAt = 0L

    /**
     * 처음 켠 날에는 세 마디가 먼저이고, 속도 쪽지는 ③을 읽을 틈을 둔 뒤에 띄웁니다.
     * 이 시각 전에는 쪽지를 띄우지 않습니다.
     */
    private var speedNoteNotBefore = 0L
    private val speedNoteAfterIntro = Runnable { showSpeedNote() }
    private val notifyLater = Runnable { askNotifications() }

    /** 입구를 마지막으로 연 시각. 빠르게 두 번 눌러도 같은 화면이 두 겹으로 열리지 않습니다. 홈으로 돌아오면 지웁니다. */
    private var openedAt = 0L

    /** 쪽지나 카드가 막 펼쳐진 순간에 시작한 손짓을 끝까지 삼키는 중인지. */
    private var swallowing = false

    /** 이름 부르기 단추. 검증 빌드에만 보입니다. 대답은 구름이 목소리로 읽습니다. */
    private lateinit var nameCall: NameCall
    private var voice: VoiceProbe? = null

    private val handler = Handler(Looper.getMainLooper())
    private var unit = 10f
    private var pokeIndex = 0
    private var lastBubble: CharSequence = ""
    private var giftsWaiting = 0
    private var bubbleHoldUntil = 0L

    /**
     * 처음 켠 날 세 마디에서 지금 띄운 차례입니다. 세 마디 중이 아니면 -1입니다.
     * ③에 닿거나 다른 화면을 열면 본 것으로 적습니다(`Seen.intro`). 그 뒤로는 [HomeSpeech.line]이 ③을 둡니다.
     */
    private var introStep = -1

    /** 움직임을 보여 준 마지막 차례. 구름이 대답 뒤 같은 마디로 돌아와도 병이 다시 빛나지 않습니다. */
    private var introCued = -1
    private var introArmed = false

    /** 말풍선에 띄운 대답을 말하기 시작한 시각. 다른 말로 바뀌면 0입니다. */
    private var answeredAt = 0L

    /** 챙겨 준 횟수. 늘면 구름이가 기뻐합니다. */
    private var seenCare = -1
    private var greeted = false
    private var resumed = false
    private var started = false
    private var revealed = false
    private var revealedLine: String? = null
    private var wideNow = true
    private val quietSeats = HashSet<SeatSlot>()
    private val waved = HashSet<SeatSlot>()
    private var wavedReady = false

    /** 입구 이름표를 잠깐 다 보여 줄 시각. 화면을 만지면 다시 나타납니다. */
    private var tagsUntil = 0L

    private val restoreBubble = Runnable { renderBubble() }
    private val nextIntro = Runnable {
        introArmed = false
        stepIntro()
    }
    private val doze = Runnable {
        if (resumed && world.night) {
            world.dozing = true
            render()
        }
    }
    private val onEvent: (DriveEvent) -> Unit = { event ->
        runOnUiThread {
            react(event)
            render()
        }
    }
    private val onChange: () -> Unit = { runOnUiThread(::render) }
    private val onRestriction: (Boolean) -> Unit = {
        runOnUiThread {
            if (DrivingGate.restricted) {
                nameCall.cancel()
                pauseIntro()
            }
            holdMotion()
            render()
            // 주행 제한으로 미뤘던 속도 허락 쪽지는 제한이 풀린 뒤에 띄웁니다.
            if (!DrivingGate.restricted) showSpeedNote()
        }
    }

    /** 원장이 길면 기다리지 않습니다. 다 읽을 때까지는 마지막으로 본 모습으로 집을 그립니다. */
    private var sceneDue = false
    private var seenRecords = 0
    private var lastPokeAt = 0L
    private var pokesInRow = 0
    private var albumNews = false
    private val sceneWait = Runnable {
        sceneDue = true
        render()
    }

    /** 앱을 열어 둔 사이에 해가 지고 비가 멎어도 장면이 따라갑니다. */
    private val sceneTick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, SCENE_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readMotionSwitch(intent)
        ScenePreview.show(window, currentScene().part)
        sounds = SoftSounds(this)
        setContentView(buildStage())
        world.revealWhenReady(stage, window) {
            revealed = true
            holdMotion()
            // 첫 마디의 7초는 집이 보이기 시작한 때부터 셉니다.
            if (introStep >= 0) armIntro()
            showSpeedNote()
        }
        handler.postDelayed(sceneWait, SCENE_WAIT_MS)
        VehicleProbeService.start(this)
        PetRepository.addListener(onEvent)
        FamilyRepository.addListener(onChange)
        KeepsakeRepository.addListener(onChange)
        SkySignals.addListener(onChange)
        DrivingGate.addListener(onRestriction)
        // 원장 재생이 첫 화면을 막지 않도록 백그라운드에서 읽습니다.
        Thread {
            PetRepository.init(this)
            FamilyRepository.init(this)
            KeepsakeRepository.init(this)
            runOnUiThread { if (!isDestroyed) render() }
        }.start()
        // 알림 허락은 여기서 묻지 않습니다. 첫 도착 뒤 집에서 묻습니다.
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readMotionSwitch(intent)
    }

    /**
     * 정령을 다시 그리는 빈도를 바꿉니다. 같은 빌드로 예전 방식과 견주어 재려는 것입니다.
     * `am start -n …/.ui.home.HomeActivity --ei smooth_fps 0` 이면 예전처럼 대기 9, 몸짓 15입니다.
     */
    private fun readMotionSwitch(intent: Intent?) {
        // 측정용 스위치라 검증 빌드에서만 받습니다. 제출 빌드에서는 이 가지가 컴파일 때 빠집니다.
        if (!VerifyBuild.ON) return
        val fps = intent?.getIntExtra(EXTRA_SMOOTH_FPS, -1) ?: -1
        if (fps >= 0) MotionTicker.smoothFps = fps.coerceAtMost(MAX_SMOOTH_FPS)
    }

    override fun onStart() {
        super.onStart()
        started = true
        greeted = false
        holdMotion()
    }

    /**
     * 주행 제한 동안 플랫폼이 화면을 덮어도 앱은 앞에 남습니다. 보이지 않으니 그리지 않습니다.
     * 창밖 그림이 준비되기 전에도 무대는 감춰져 있으니 박자를 돌리지 않습니다.
     */
    private fun holdMotion() {
        handler.removeCallbacks(sceneTick)
        if (started && revealed && !DrivingGate.restricted) {
            world.ticker.start()
            handler.postDelayed(sceneTick, SCENE_TICK_MS)
        } else {
            world.ticker.stop()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        openedAt = 0L
        render()
        recall.takeHung()?.let(::hangPhoto)
        handler.postDelayed(doze, DOZE_MS)
        // 알림 허락은 집으로 돌아온 뒤에만 묻습니다. 트립이 끝나는 순간 묻으면 첫 도착 카드를 허락 창이 덮습니다.
        handler.postDelayed(notifyLater, NOTIFY_DELAY_MS)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(doze)
        handler.removeCallbacks(notifyLater)
        nameCall.cancel()
        voice?.stop()
        pauseIntro()
        super.onPause()
    }

    /** 밤에 자던 구름이는 화면을 건드리면 깨어납니다. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 쪽지나 카드가 막 펼쳐진 순간의 두 번째 손짓은 아직 자리 잡지 않은 카드를 지나 뒤의 입구에 닿았습니다.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) swallowing = PaperNote.justOpened()
        if (swallowing) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowing = false
            return true
        }
        handler.removeCallbacks(doze)
        handler.postDelayed(doze, DOZE_MS)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = ev.x
            downY = ev.y
            tagsUntil = SystemClock.uptimeMillis() + TAG_HOLD_MS
            render()
            handler.postDelayed(::render, TAG_HOLD_MS)
        }
        // 왼쪽으로 넘기면 강가로 갑니다. 위아래로 많이 움직였거나 카드가 열려 있으면 넘김으로 보지 않습니다.
        if (ev.actionMasked == MotionEvent.ACTION_UP && riverTab.visibility == View.VISIBLE) {
            val dx = ev.x - downX
            val dy = ev.y - downY
            if (-dx > stage.width * SWIPE_SHARE && kotlin.math.abs(dy) < stage.height * SWIPE_SLACK) {
                openRiver()
                return true
            }
        }
        if (world.dozing) {
            world.dozing = false
            render()
            world.hero.play(Reward.MOTION_STRETCH)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onStop() {
        started = false
        holdMotion()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        sounds.release()
        nameCall.release()
        voice?.release()
        PetRepository.removeListener(onEvent)
        FamilyRepository.removeListener(onChange)
        KeepsakeRepository.removeListener(onChange)
        SkySignals.removeListener(onChange)
        // 풀지 않으면 낮밤이나 창 크기가 바뀌어 홈을 다시 만들 때마다 옛 홈이 메모리에 남습니다.
        DrivingGate.removeListener(onRestriction)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (requestCode == NameCall.PERMISSION_REQUEST) nameCall.onPermission(granted)
        // 이미 떠 있던 서비스도 속도를 받도록 시작 명령을 다시 보냅니다. 서비스는 이때 속도 등록을 다시 해 봅니다.
        if (requestCode == SPEED_REQUEST && granted) VehicleProbeService.start(this)
        if (requestCode == SPEED_REQUEST) onSpeedResult(granted, grantResults)
    }

    /**
     * 속도 허락 창을 답 없이 닫았으면 물은 기록을 지웁니다. 다음에 홈을 새로 열 때 쪽지와 창이 다시 나옵니다.
     * 뒤로나 바깥 누르기로 닫아도 결과는 거절(-1)로 옵니다. 시스템은 닫힘을 거절로 세지 않아 이유 표시가 그대로입니다.
     * 그래서 묻기 전과 뒤의 이유 표시가 모두 거짓이면 닫힌 것으로 봅니다. 첫 거절은 뒤가, 두 번째 거절은 앞이 참이라 기록을 둡니다.
     * 정책으로 막힌 요청은 창 없이 곧바로 거절로 옵니다. 닫힘으로 세지 않아야 홈마다 쪽지가 뜨지 않습니다.
     * 요청이 끊기면 결과가 비어 옵니다. 이때도 답을 못 받았으니 기록을 지웁니다.
     */
    private fun onSpeedResult(granted: Boolean, grantResults: IntArray) {
        val rationale = shouldShowRequestPermissionRationale(SPEED_PERMISSION)
        val waited = SystemClock.uptimeMillis() - speedAskedAt
        val unanswered = !granted &&
            (grantResults.isEmpty() || (!speedRationaleBefore && !rationale && waited > SPEED_NO_DIALOG_MS))
        if (unanswered) seen.speedAsked = false
        // 기기 기록에서 닫힘과 거절을 가려 보려고 한 줄 남깁니다. 태그는 Spike입니다.
        ProbeState.log(
            "SpeedAsk result=${grantResults.contentToString()} rationaleBefore=$speedRationaleBefore " +
                "rationaleAfter=$rationale waitedMs=$waited unanswered=$unanswered",
        )
    }

    /**
     * 속도 허락이 없으면 부드러운 정지, 급제동, 깜빡이 판정이 조용히 멈춥니다. 처음 켰을 때 이유를 먼저 말하고 묻습니다.
     * 두 번 거절하면 시스템이 더 묻지 않으므로 쪽지도 띄우지 않습니다. 주행 제한 중에는 화면이 가려져 있어 풀린 뒤에 띄웁니다.
     */
    private fun showSpeedNote() {
        if (speedNoteShown || !revealed || DrivingGate.restricted || introPending()) return
        if (SystemClock.uptimeMillis() < speedNoteNotBefore || !speedAskDue()) return
        speedNoteShown = true
        pauseIntro()
        speedNote.show(unit, SPEED_TITLE, SPEED_LINES)
    }

    /**
     * 처음 켠 날 세 마디가 남았는지. 세 마디 중이거나, 원장을 아직 읽지 못해 세 마디를 할지 모를 때입니다.
     * 경고등처럼 다른 말이 앞서 세 마디가 멈추면 남지 않은 것으로 보고 쪽지를 띄웁니다.
     */
    private fun introPending(): Boolean = introStep >= 0 || (!KeepsakeRepository.ready && !seen.intro)

    /**
     * 알림 허락은 첫 도착 뒤 집에서 한 번만 묻습니다. 처음 켰을 때는 구름이 첫인사가 먼저입니다.
     * 속도 쪽지가 남았으면 그것부터 묻고, 카드나 쪽지가 열려 있거나 주행 중이면 다음에 집을 그릴 때 봅니다.
     */
    private fun askNotifications() {
        if (seen.notifyAsked || !resumed || !revealed || DrivingGate.restricted || cardOpen()) return
        if (PetRepository.state.trips < 1 || speedAskDue()) return
        seen.notifyAsked = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFY_REQUEST)
    }

    private fun speedAskDue(): Boolean =
        checkSelfPermission(SPEED_PERMISSION) != PackageManager.PERMISSION_GRANTED &&
            (!seen.speedAsked || shouldShowRequestPermissionRationale(SPEED_PERMISSION))

    /** 쪽지를 누르면 시스템이 허락을 묻습니다. 답 없이 닫힌 창을 가리려고 묻기 전 이유 표시와 시각을 적어 둡니다. */
    private fun askSpeed() {
        speedRationaleBefore = shouldShowRequestPermissionRationale(SPEED_PERMISSION)
        speedAskedAt = SystemClock.uptimeMillis()
        seen.speedAsked = true
        requestPermissions(arrayOf(SPEED_PERMISSION), SPEED_REQUEST)
    }

    /** 쪽지나 선물 카드가 열려 있는지. 열려 있는 동안에는 입구를 열지 않습니다. */
    private fun cardOpen(): Boolean = listOf<View>(note, chooser, reveal, speedNote).any { it.visibility == View.VISIBLE }

    /** 입구를 열어도 되는지. 한 번 열면 홈이 가려졌다 돌아오거나 잠깐 지나야 다시 엽니다. */
    private fun mayOpen(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (cardOpen() || now - openedAt < OPEN_GUARD_MS) return false
        openedAt = now
        return true
    }

    private fun buildStage(): StageLayout {
        world = HomeWorld(this)
        recall = RecallHost(this)
        world.hero.setOnClickListener { pokeSpirit() }
        world.hero.setOnLongClickListener { petSpirit() }
        world.jar.setOnClickListener { openJar() }
        world.friends.forEachIndexed { index, actor ->
            actor.setOnClickListener { pokeFriend(index) }
            actor.setOnLongClickListener { openFamily() }
        }
        bubble = SpeechBubbleView(this).apply { setOnClickListener { tapBubble() } }
        nameTag = paperText(PLACE_NAME, bold = true).apply {
            gravity = Gravity.CENTER
            rotation = -1f
        }
        growthDoor = Door(HomeScene.JAR, R.drawable.jar_empty, "성장 기록") { openJar() }
        careDoor = Door(HomeScene.CAREBOOK, R.drawable.prop_carebook_v2, "정비 수첩") { if (mayOpen()) openBook(BookActivity.Page.CARE) }
        albumDoor = Door(HomeScene.ALBUM, R.drawable.prop_album_v2, "함께한 기록") {
            if (mayOpen() && !recall.openWaiting()) openBook(BookActivity.Page.ALBUM)
        }
        doors = listOf(growthDoor, albumDoor, careDoor)
        familyDoor = Door(HomeScene.LILAC, R.drawable.family_saeteol_sleep, "아기 데려오기") {
            if (mayOpen()) {
                finishIntro()
                startActivity(FamilyActivity.intent(this, adding = true))
            }
        }
        careDot = dot()
        albumDot = dot()
        seen = Seen(this)
        riverTab = paperText("강가 ›", bold = true).apply {
            gravity = Gravity.CENTER
            contentDescription = "강가로 가기"
            visibility = View.GONE
            setOnClickListener { openRiver() }
        }
        nameCall = NameCall(this, ::callableNames, ::listenForName, ::answerName, ::missName)
        burst = FeatherBurstView(this)
        note = PaperNote(this)
        speedNote = PaperNote(this).apply { onClosed = ::askSpeed }
        reveal = GiftReveal(this, world.ticker, { world.age.fernArt }).apply { onClosed = ::afterReveal }
        chooser = GiftChooser(this) { world.age.fernArt }
        world.ticker.onTick { seconds -> bubble.translationY = sin(seconds * 1.1f) * unit * 0.25f }
        stage = StageLayout(this).apply {
            setBackgroundColor(GameStyle.INTERIOR)
            world.layers.forEach(::addView)
            (doors + familyDoor).forEach { addView(it.hit) }
            addView(nameTag)
            (doors + familyDoor).forEach { addView(it.tag) }
            listOf<View>(careDot, albumDot, riverTab, nameCall.button, bubble, burst, note, chooser, reveal, speedNote).forEach(::addView)
            onResize = ::arrange
        }
        return stage
    }

    /** 종이 조각 전부. 밤 빛을 한 번에 입힙니다. */
    private fun papers(): List<View> = listOf<View>(nameTag, riverTab, nameCall.button) + (doors + familyDoor).map { it.tag }

    private fun dot() = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(GameStyle.CORAL)
        }
        visibility = View.GONE
    }

    /** 가로 창은 집 전체를 채웁니다. 세로로 좁은 창은 위에 집을, 아래에 입구를 둡니다. */
    private fun arrange(w: Int, h: Int) {
        val wide = w >= h * WIDE_RATIO
        val u = if (wide) min(w / 170f, h / 121f) else min(w / 96f, h / 150f)
        unit = u
        wideNow = wide
        style(u, wide)
        if (wide) arrangeWide(w, h, u) else arrangeNarrow(w, h, u)
        // 창 크기가 바뀌면 종이를 새로 만들므로 밤 색을 다시 입힙니다.
        world.forgetScene()
        paperNight = null
        // 넓은 창에서 숨긴 이름표가 좁은 창에 그대로 남지 않게 다시 그립니다. 열린 카드는 새 크기에 맞지 않아 닫습니다.
        reveal.close()
        note.close()
        // 속도 허락 쪽지는 닫으면 허락을 묻게 되므로 닫지 않고 새 크기로 다시 펼칩니다.
        if (speedNote.visibility == View.VISIBLE) handler.post { speedNote.show(unit, SPEED_TITLE, SPEED_LINES) }
        handler.post(::render)
    }

    private fun arrangeWide(w: Int, h: Int, u: Float) {
        val gap = (2.4f * u).toInt()
        world.placeWide(stage, w, h)
        val scene = world.scene
        val line = (7.4f * u).toInt()
        stage.place(nameTag, gap, gap, (30f * u).toInt(), line)
        doors.forEachIndexed { index, door -> placeTagWide(door, TAG_TILT[index % TAG_TILT.size], w, u) }
        placeTagWide(familyDoor, 0.6f, w, u)
        val dot = (2.4f * u).toInt()
        val care = HomeScene.CAREBOOK
        stage.place(careDot, (scene.viewX(care.x) + DOT_SIDE * u).toInt(), (scene.viewY(care.bottom - care.height) - dot / 2).toInt(), dot, dot)
        val album = HomeScene.ALBUM
        stage.place(albumDot, (scene.viewX(album.x) + DOT_SIDE * u).toInt(), (scene.viewY(album.bottom - album.height) - dot / 2).toInt(), dot, dot)
        placeBubble()
        // 강가는 오른쪽에 있습니다. 넘기는 방향과 같은 쪽 가장자리에 탭을 둡니다. 배경 v2의 옆문이 오면 문이 이 일을 맡습니다.
        val riverWidth = (17f * u).toInt()
        stage.place(riverTab, w - riverWidth - gap, (h * RIVER_TAB_TOP).toInt(), riverWidth, line)
        // 이름 부르기는 맨 위 줄 오른쪽 끝입니다. 장소 이름과 같은 줄의 종이 단추입니다.
        val callWidth = nameCall.width()
        stage.place(nameCall.button, w - callWidth - gap, gap, callWidth, line)
    }

    /**
     * 꼬리표는 물건에 붙습니다. 선반의 병은 목에 매단 꼬리표가 몸통 앞에 늘어지고, 마루의 책은 바로 앞에 꼬리표가 놓입니다(장면 규칙 2-8의 6).
     * 누르는 곳은 꼬리표가 아니라 물건 전체입니다. 물건이 작아도 [TextRules.MIN_TOUCH]보다 작지 않습니다.
     */
    private fun placeTagWide(door: Door, tilt: Float, w: Int, u: Float) {
        val spot = door.spot ?: return
        val scene = world.scene
        door.tag.setCompoundDrawables(null, null, null, null)
        door.tag.rotation = tilt
        door.hit.visibility = View.VISIBLE
        val width = (door.tag.paint.measureText(widestLabel(door)) + 4f * u).toInt()
        val height = (5.6f * u).toInt()
        val x = scene.viewX(spot.x)
        val left = (x - width / 2f).toInt().coerceIn(0, w - width)
        val top = when (door) {
            growthDoor -> scene.viewY(spot.bottom - spot.height * JAR_TAG_DROP)
            familyDoor -> scene.viewY(spot.bottom) - height
            else -> scene.viewY(spot.bottom) + 0.6f * u
        }
        stage.place(door.tag, left, top.toInt(), width, height)
        val art = SpiritSprites.bitmap(this, door.art)
        val minTouch = TextRules.MIN_TOUCH * resources.displayMetrics.density
        val tall = maxOf(spot.height * scene.sceneScale, minTouch)
        val wideHit = if (door === familyDoor) tall * 2f else maxOf(tall * art.width / art.height, minTouch)
        stage.place(door.hit, (x - wideHit / 2f).toInt(), (scene.viewY(spot.bottom) - tall).toInt(), wideHit.toInt(), tall.toInt())
    }

    /** 말풍선은 구름이 머리 옆에 붙습니다. 구름이가 자라면 따라 올라가고, 폭은 말의 길이만큼입니다. */
    private fun placeBubble() {
        val u = unit
        val scene = world.scene
        val lines = bubble.text.split('\n')
        val longest = lines.maxOfOrNull { bubble.paint.measureText(it) } ?: 0f
        // 말풍선 글자가 36으로 커져 한 줄 15자 안팎이 들어가게 넓힙니다(장면 규칙 2-8의 4).
        val width = (longest + 8.8f * u).coerceIn(20f * u, 58f * u).toInt()
        if (wideNow) {
            val grown = world.age.heroScale
            val left = scene.viewX(HomeScene.HERO.x + BUBBLE_SIDE * grown).toInt()
            val bottom = scene.viewY(HomeScene.HERO.bottom - BUBBLE_ABOVE * grown).toInt()
            stage.place(bubble, left, 0, width, bottom, wrapHeight = true)
        } else {
            val gap = (2.4f * u).toInt()
            stage.place(bubble, (stage.width * 0.3f).toInt(), gap + (10f * u).toInt(), width.coerceAtMost((stage.width * 0.68f).toInt()), (stage.height * 0.2f).toInt(), wrapHeight = true)
        }
        stage.requestLayout()
    }

    private fun arrangeNarrow(w: Int, h: Int, u: Float) {
        val gap = (2.4f * u).toInt()
        val tabHeight = (17f * u).toInt()
        val sceneHeight = h - tabHeight - gap * 2
        world.placeNarrow(stage, w, h, sceneHeight)
        stage.place(nameTag, gap, gap, (40f * u).toInt(), (9f * u).toInt())
        val bubbleLeft = (w * 0.3f).toInt()
        stage.place(bubble, bubbleLeft, gap + (10f * u).toInt(), w - bubbleLeft - gap, (sceneHeight * 0.3f).toInt(), wrapHeight = true)
        val riverWidth = (24f * u).toInt()
        stage.place(riverTab, w - riverWidth - gap, gap, riverWidth, (9f * u).toInt())
        val callWidth = nameCall.width()
        stage.place(nameCall.button, w - riverWidth - gap - callWidth - gap / 2, gap, callWidth, (9f * u).toInt())
        val tabGap = gap / 2
        val tabWidth = (w - gap * 2 - tabGap * (doors.size - 1)) / doors.size
        val iconSize = (9f * u).toInt()
        doors.forEachIndexed { index, door ->
            door.hit.visibility = View.GONE
            val icon = BitmapDrawable(resources, SpiritSprites.bitmap(this, door.art))
            icon.setBounds(0, 0, iconSize * icon.intrinsicWidth / icon.intrinsicHeight, iconSize)
            door.tag.setCompoundDrawables(null, icon, null, null)
            stage.place(door.tag, gap + index * (tabWidth + tabGap), h - tabHeight - gap, tabWidth, tabHeight)
        }
        // 아기가 없을 때의 꼬리표는 집 가운데 담요 위에 둡니다.
        familyDoor.hit.visibility = View.GONE
        familyDoor.tag.setCompoundDrawables(null, null, null, null)
        val familyWidth = (34f * u).toInt()
        stage.place(familyDoor.tag, (w - familyWidth) / 2, (sceneHeight * 0.7f).toInt(), familyWidth, (8f * u).toInt())
        val dot = (1.8f * u).toInt()
        fun tabRight(index: Int) = gap + index * (tabWidth + tabGap) + tabWidth - dot * 2
        stage.place(careDot, tabRight(doors.indexOf(careDoor)), h - tabHeight - gap + dot / 2, dot, dot)
        stage.place(albumDot, tabRight(doors.indexOf(albumDoor)), h - tabHeight - gap + dot / 2, dot, dot)
    }

    /** 글자는 장소 이름과 단추가 32, 꼬리표가 24 이상, 말풍선이 36 이상입니다(장면 규칙 2-8). */
    private fun style(u: Float, wide: Boolean) {
        val radius = 1.4f * u
        nameTag.background = PaperDrawable(this, radius)
        nameTag.sizeTo(3.4f * u, TextRules.MAIN)
        bubble.sizeTo(3.7f * u, TextRules.BUBBLE)
        bubble.setPaddingFor(u)
        riverTab.background = PaperDrawable(this, radius)
        riverTab.sizeTo(3.3f * u, TextRules.MAIN)
        nameCall.style(u, wide)
        (doors + familyDoor).forEach {
            it.tag.background = PaperDrawable(this, radius)
            it.tag.sizeTo(2.7f * u, TextRules.SMALL)
            it.tag.compoundDrawablePadding = (0.4f * u).toInt()
        }
        careDot.elevation = 1.2f * u
        albumDot.elevation = 1.2f * u
    }

    private fun currentScene(): SceneState = NookLight.now()

    /** 마지막으로 종이에 입힌 밤 여부. 창 크기가 바뀌어 종이를 새로 만들면 null로 돌립니다. */
    private var paperNight: Boolean? = null

    private fun renderScene(state: SceneState) {
        // 고른 소품은 원장을 읽어야 압니다. 큰 그림을 두 번 올리지 않게 잠깐 기다립니다.
        if (!sceneDue && !KeepsakeRepository.ready) return
        world.applyScene(state, KeepsakeRepository.gifts)
        lightPapers(world.night)
    }

    /** 밤에는 종이 조각 전부와 말풍선이 같은 빛을 입습니다. 바뀔 때만 다시 칠합니다. */
    private fun lightPapers(night: Boolean) {
        if (paperNight == night) return
        paperNight = night
        val filter = GameStyle.paperLight(night)
        bubble.setPaper(if (night) GameStyle.PAPER_NIGHT else GameStyle.PAPER)
        papers().forEach {
            it.background?.colorFilter = filter
            it.invalidate()
        }
    }

    private fun render() {
        // 가려져 있는 동안의 변화는 돌아온 순간에 보여 줍니다. 그래야 기뻐하는 모습을 놓치지 않습니다.
        if (!resumed || DrivingGate.restricted) return
        renderScene(currentScene())
        val pet = PetRepository.state
        val keep = KeepsakeRepository.gifts
        val care = KeepsakeRepository.care
        val family = FamilyRepository.state
        val motions = CareRules.owned(pet, keep, care).filter { it.kind == RewardKind.MOTION }.sortedBy { it.ordinal }
        world.dress(pet, keep, CareRules.look(pet, care, KeepsakeRepository.odoKm, System.currentTimeMillis()), motions)
        // 그림책 초대가 있는 날은 모두 담요에 모입니다. 가장 기쁜 날이 허전하면 안 됩니다.
        world.dressFamily(family, if (recall.waiting() != null) family.spirits.map { it.preset }.toSet() else FamilyRepository.riders.toSet())
        // 큰 고비에는 백설기가 놓입니다. 그날 하루의 잔치입니다.
        val days = pet.together.days
        world.showDecor(keep, party = days >= FEAST_FROM && days in TimeTogether.MILESTONES)

        val jar = pet.jar
        giftsWaiting = keep.pending(jar.opened)
        // 선물을 기다리는 병은 가득 찬 채로 있습니다. 말과 그림이 같아야 합니다.
        world.jar.show(if (giftsWaiting > 0) JarView.VISIBLE else JarView.visible(jar))
        world.jar.setWaiting(giftsWaiting > 0)
        // 원장을 읽는 동안에는 빈 값이라 Lv.1과 첫 인사가 스칩니다. 다 읽은 뒤에 보여 줍니다.
        val ready = KeepsakeRepository.ready
        nameTag.alpha = if (ready) 1f else 0f
        bubble.visibility = if (ready) View.VISIBLE else View.INVISIBLE
        // 병의 꼬리표는 짧게 둡니다. 얼마나 찼는지는 병이 보여 주고, 숫자는 성장 기록에 있습니다.
        growthDoor.tag.text = when {
            giftsWaiting > 0 -> "선물 열기"
            wideNow -> "깃털 병"
            else -> "성장 기록"
        }
        albumDoor.tag.text = if (recall.waiting() != null) "그림책 펼치기" else "함께한 기록"
        riverTab.visibility = if (ready && riverOpen()) View.VISIBLE else View.GONE
        nameCall.button.visibility = if (ready && nameCall.available) View.VISIBLE else View.GONE
        // 점은 한 번에 하나만 둡니다. 그림책 초대가 있는 날은 그 말풍선만 보이게 합니다.
        val inviting = recall.waiting() != null
        val careNews = needsCare()
        albumNews = keep.album.any { it.id !in seen.moments }
        careDot.visibility = if (careNews && !inviting) View.VISIBLE else View.GONE
        albumDot.visibility = if (albumNews && !careNews && !inviting) View.VISIBLE else View.GONE
        val noFamily = family.spirits.isEmpty()
        familyDoor.tag.visibility = if (ready && noFamily) View.VISIBLE else View.GONE
        familyDoor.hit.visibility = if (wideNow && ready && noFamily) View.VISIBLE else View.GONE
        if (ready) showTags(pet.together.days) else doors.forEach { it.tag.alpha = 0f }
        if (ready) waveMissed(family.away)
        renderBubble()
        thankForCare()
        if (!greeted && KeepsakeRepository.ready) {
            greeted = true
            greet()
        }
    }

    /**
     * 익숙해지면 꼬리표가 집을 가리지 않습니다. 처음 며칠, 화면을 만진 직후, 선물이 기다릴 때만 보입니다.
     * 수첩과 앨범의 새 소식은 꼬리표 대신 점 하나로 알립니다(컨셉 점검 5-4).
     */
    private fun showTags(days: Int) {
        if (!wideNow) {
            doors.forEach {
                it.tag.animate().cancel()
                it.tag.alpha = 1f
            }
            return
        }
        val all = days < TAG_DAYS || SystemClock.uptimeMillis() < tagsUntil
        doors.forEach { door ->
            val news = door === growthDoor && giftsWaiting > 0 || door === albumDoor && recall.waiting() != null
            door.tag.animate().alpha(if (all || news) 1f else 0f).setDuration(TAG_FADE_MS).start()
        }
    }

    /** 꼬리표 폭은 가장 긴 말에 맞춥니다. 말이 바뀌어도 꼬리표가 잘리지 않습니다. */
    private fun widestLabel(door: Door): String = when (door) {
        growthDoor -> "선물 열기"
        albumDoor -> "그림책 펼치기"
        else -> door.tag.text.toString()
    }

    /** 강가는 첫 물건이 생긴 도착에 열립니다(docs/design/river-spec.md 2장). */
    private fun riverOpen(): Boolean = KeepsakeRepository.gifts.places.isOpen(Place.RIVER)

    private fun openRiver() {
        if (DrivingGate.restricted || !mayOpen()) return
        finishIntro()
        startActivity(RiverActivity.intent(this))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.place_in_right, R.anim.place_out_left)
    }

    /** 화면을 열면 먼저 알은체합니다. 첫 3초 안에 살아 있다는 것이 보여야 합니다. */
    private fun greet() {
        val pose = when {
            PetRepository.state.mood != Mood.HAPPY -> return
            recall.waiting() != null -> HeroPose.LETTER
            currentScene().part == DayPart.MORNING -> HeroPose.STRETCH
            else -> HeroPose.WAVE
        }
        world.heroPose(pose, GREET_MS)
        recall.anniversary()?.let { sayFor(it, CARE_LINE_MS) }
    }

    /** 그림책을 덮고 돌아온 순간. 새 사진이 벽에서 흔들립니다. */
    private fun hangPhoto(id: RecallId) {
        world.age.photos.swing(id)
        if (id is RecallId.Days) sayFor("사진 한 장 걸어 뒀어.", CARE_LINE_MS)
    }

    /** 작은 정령의 왕복. 내릴 때 손 흔들고, 돌아오면 반기고, 벨트를 매면 천 띠를 두릅니다. */
    private fun react(event: DriveEvent) {
        if (!resumed) return
        when (event) {
            is DriveEvent.SeatLeft -> {
                waved += event.seat
                greetSeat(event.seat, FriendPose.WAVE, FamilySpeech::sendOff)
            }
            is DriveEvent.SeatReturned -> {
                // 잠깐 내렸다 탔으면 조용히 반깁니다. 소리는 오랜만에 돌아왔을 때만 냅니다.
                if (!event.greetsAloud) quietSeats += event.seat
                greetSeat(event.seat, FriendPose.WELCOME, FamilySpeech::welcomeBack)
            }
            is DriveEvent.SeatsAwake -> {
                val riders = FamilyRepository.state.ridersIn(event.newly)
                val quiet = quietSeats.containsAll(event.newly)
                quietSeats.clear()
                if (riders.isEmpty()) return
                if (!quiet) sounds.stamp()
                riders.forEach {
                    world.friendPose(it, FriendPose.BELT, HomeWorld.POSE_MS)
                    world.friendOf(it)?.first?.cheer()
                }
                // 벨트를 매고 나면 달려와 안깁니다.
                if (!quiet && world.friendsJoinIn && DuoScene.HUG.preset in riders) handler.postDelayed({ world.duoScene(DuoScene.HUG) }, HUG_DELAY_MS)
            }
            else -> Unit
        }
    }

    /** 도착 화면에 가려 있는 사이 내린 자리에도 돌아오면 손을 흔듭니다. 어제 내린 자리까지 흔들지는 않습니다. */
    private fun waveMissed(away: Set<SeatSlot>) {
        if (!wavedReady) {
            wavedReady = true
            waved += away
            return
        }
        waved.retainAll(away)
        (away - waved).forEach { seat ->
            waved += seat
            greetSeat(seat, FriendPose.WAVE, FamilySpeech::sendOff)
        }
    }

    private fun greetSeat(seat: SeatSlot, pose: FriendPose, line: (String) -> String) {
        val preset = FamilyRepository.state.riderAt(seat) ?: return
        world.friendPose(preset, pose, HomeWorld.POSE_MS)
        sayFor(line(preset.displayName), SEAT_LINE_MS)
    }

    /** 정비하고 돌아온 순간. 털을 훑는 빛과 새 동작이 한꺼번에 보입니다. */
    private fun thankForCare() {
        if (!KeepsakeRepository.ready) return
        val care = KeepsakeRepository.care
        val count = care.records.size + PetRepository.state.stars
        val before = seenCare
        val recordsBefore = seenRecords
        seenCare = count
        seenRecords = care.records.size
        if (before >= 0 && count > before) {
            world.heroPose(HeroPose.THANKS, HomeWorld.POSE_MS)
            sounds.sparkle()
            // 경고등이 꺼진 별이면 수첩의 마지막 항목과 상관없습니다.
            sayFor(HomeSpeech.careThanks(care.records.lastOrNull()?.item?.takeIf { seenRecords > recordsBefore }), CARE_LINE_MS)
        }
    }

    private fun sayFor(line: CharSequence, durationMs: Long) {
        say(line)
        answeredAt = 0L
        holdBubble(durationMs)
    }

    private fun holdBubble(durationMs: Long) {
        bubbleHoldUntil = SystemClock.uptimeMillis() + durationMs
        handler.removeCallbacks(restoreBubble)
        handler.postDelayed(restoreBubble, durationMs)
        // 대답을 띄운 동안은 세 마디를 넘기지 않습니다. 대답이 끝나면 하던 마디의 7초를 새로 셉니다.
        pauseIntro()
    }

    private fun needsCare(): Boolean {
        if (PetRepository.state.ailments.isNotEmpty()) return true
        val care = KeepsakeRepository.care
        val odo = KeepsakeRepository.odoKm ?: care.firstOdoKm ?: 0f
        return care.nextDue(odo, System.currentTimeMillis()) != null
    }

    private fun renderBubble() {
        if (SystemClock.uptimeMillis() < bubbleHoldUntil) return
        val care = KeepsakeRepository.care
        val odo = KeepsakeRepository.odoKm ?: care.firstOdoKm ?: 0f
        val due = care.nextDue(odo, System.currentTimeMillis())
        val invite = recall.inviteLine().takeIf {
            giftsWaiting == 0 && PetRepository.state.mood == Mood.HAPPY && FamilyRepository.riders.isEmpty()
        }
        val line = invite ?: HomeSpeech.line(PetRepository.state, FamilyRepository.state, FamilyRepository.awakeSeats, currentScene(), giftsWaiting, due, seen.intro)
        // 첫인사가 나오는 때가 처음 켠 날 세 마디입니다. 원장을 다 읽은 뒤에만 셉니다. 경고등처럼 다른 말이 앞서면 멈춥니다.
        if (line == HomeSpeech.HELLO && KeepsakeRepository.ready) return showIntro()
        quitIntro()
        say(line)
        // 세 마디가 없거나 끝났으면 미뤄 둔 속도 쪽지를 띄웁니다. 알림 허락은 집으로 돌아올 때 묻습니다(onResume).
        showSpeedNote()
    }

    /** 말이 바뀔 때만 톡 튀어나옵니다. 새로 띄웠으면 참입니다. */
    private fun say(line: CharSequence): Boolean {
        if (line == lastBubble) return false
        lastBubble = line
        bubble.text = GameStyle.keepWords(line)
        placeBubble()
        pop(bubble, bubble.width * 0.1f, bubble.height.toFloat())
        return true
    }

    /** 톡 튀는 움직임. 0.9배에서 1배로 0.28초입니다. 말풍선과 처음 켠 날의 이름표가 함께 씁니다. */
    private fun pop(view: View, pivotX: Float = view.width / 2f, pivotY: Float = view.height / 2f) {
        view.pivotX = pivotX
        view.pivotY = pivotY
        view.scaleX = POP_FROM
        view.scaleY = POP_FROM
        view.animate().scaleX(1f).scaleY(1f).setDuration(POP_MS).setInterpolator(OvershootInterpolator(POP_TENSION)).start()
    }

    /** 지금 마디를 띄웁니다. 새로 띄웠거나 가려졌다 돌아왔으면 그 마디의 7초를 새로 셉니다. */
    private fun showIntro() {
        if (introStep < 0) introStep = 0
        val line = HomeSpeech.FIRST_RUN[introStep]
        val shown = say(line)
        if (shown && introStep > introCued) {
            introCued = introStep
            cue(line)
        }
        if (shown || !introArmed) armIntro()
    }

    /** 가려져 있거나 집이 아직 보이지 않으면 세지 않습니다. 다시 보이면 [render]가 새로 셉니다. */
    private fun armIntro() {
        pauseIntro()
        if (!resumed || !revealed || DrivingGate.restricted) return
        handler.postDelayed(nextIntro, INTRO_STEP_MS)
        introArmed = true
    }

    private fun pauseIntro() {
        handler.removeCallbacks(nextIntro)
        introArmed = false
    }

    /** 다음 마디로 넘깁니다. ③에 닿으면 본 것으로 적고, "아기 데려오기" 이름표가 한 번 튑니다. */
    private fun stepIntro() {
        // 쪽지가 말풍선을 덮은 동안에는 넘기지 않습니다. 쪽지를 닫고 홈을 다시 그리면 그 마디의 7초를 새로 셉니다.
        if (introStep < 0 || !resumed || DrivingGate.restricted || cardOpen() || SystemClock.uptimeMillis() < bubbleHoldUntil) return
        if (introStep + 1 < HomeSpeech.FIRST_RUN.lastIndex) {
            introStep += 1
            renderBubble()
            return
        }
        // ③을 읽을 틈을 두고 속도 쪽지를 띄웁니다. renderBubble이 그 전에 띄우지 않게 시각을 먼저 적습니다.
        speedNoteNotBefore = SystemClock.uptimeMillis() + SPEED_AFTER_INTRO_MS
        handler.removeCallbacks(speedNoteAfterIntro)
        handler.postDelayed(speedNoteAfterIntro, SPEED_AFTER_INTRO_MS)
        finishIntro()
        renderBubble()
        if (lastBubble == HomeSpeech.FIRST_ADOPT) cue(HomeSpeech.FIRST_ADOPT)
    }

    /** 세 마디를 끝내고 본 것으로 적습니다. ③에 닿았을 때와 다른 화면을 열 때(문, 이름표, 병, 이름 부르기)입니다. */
    private fun finishIntro() {
        if (introStep < 0) return
        introStep = -1
        pauseIntro()
        seen.intro = true
    }

    /** 경고등처럼 먼저 할 말이 생기면 멈춥니다. 본 것으로 적지 않아 다음에 ①부터 다시 나옵니다. */
    private fun quitIntro() {
        if (introStep < 0) return
        introStep = -1
        introCued = -1
        pauseIntro()
    }

    /** 말과 함께 그 물건이 살짝 움직입니다. ②는 병이 숨 쉬듯 한 번 빛나고, ③은 이름표가 한 번 튑니다. */
    private fun cue(line: String) {
        when (line) {
            HomeSpeech.FIRST_JAR -> world.jar.breatheOnce()
            HomeSpeech.FIRST_ADOPT -> pop(familyDoor.tag)
        }
    }

    /** 세 마디 중에는 말풍선을 누르면 다음 마디입니다. 구름이 대답이 떠 있으면 대답만 거두고 하던 마디로 돌아옵니다. */
    private fun tapBubble() {
        if (introStep < 0) {
            if (recall.waiting() != null && mayOpen()) recall.openWaiting()
            return
        }
        if (SystemClock.uptimeMillis() < bubbleHoldUntil) {
            bubbleHoldUntil = 0L
            answeredAt = 0L
            handler.removeCallbacks(restoreBubble)
            renderBubble()
            return
        }
        stepIntro()
    }

    /**
     * 병을 누르면 선물이 먼저입니다. 선물이 없으면 성장 기록을 펼칩니다.
     * 후보가 둘 이상이면 셋 중 하나를 고릅니다. 사다리를 다 모았으면 구름이 쪽지가 나옵니다.
     */
    private fun openJar() {
        // 병이 여러 개 찼을 때 두 번 누르면 쪽지가 둘 열려 첫 쪽지를 잃었습니다. 카드가 열려 있으면 받지 않습니다.
        if (cardOpen()) return
        finishIntro()
        val pet = PetRepository.state
        val keep = KeepsakeRepository.gifts
        if (keep.pending(pet.jar.opened) == 0) {
            if (mayOpen()) openBook(BookActivity.Page.GROWTH)
            return
        }
        val choices = keep.choices()
        when {
            choices.isEmpty() -> {
                KeepsakeRepository.updateGifts { it.open(pet.jar.opened) }
                sounds.chime()
                revealedLine = null
                reveal.showNote(unit, recall.note(keep.notes))
                world.hero.celebrate()
                bubble.alpha = 0f
            }
            choices.size == 1 -> claimGift(choices.first())
            else -> {
                sounds.pop()
                chooser.show(unit, choices, ::claimGift)
            }
        }
    }

    /** 고른 선물을 열고 보상 화면을 띄웁니다. 다음 병의 갈래는 남은 첫 후보로 알려 줍니다. */
    private fun claimGift(reward: Reward) {
        val jars = PetRepository.state.jar.opened
        KeepsakeRepository.updateGifts { it.choose(reward, jars) }
        sounds.chime()
        revealedLine = HomeSpeech.rewardLine(reward)
        reveal.show(unit, reward, KeepsakeRepository.gifts.choices().firstOrNull())
        world.hero.celebrate()
        bubble.alpha = 0f
    }

    private fun afterReveal() {
        bubble.alpha = 1f
        burst.burst(BURST_X, BURST_Y)
        sounds.sparkle()
        world.hero.celebrate()
        revealedLine?.let { sayFor(it, POKE_LINE_MS) }
        render()
    }

    private fun openBook(page: BookActivity.Page) {
        finishIntro()
        sounds.pop()
        startActivity(BookActivity.intent(this, page))
    }

    /** 쪽지를 든 아기를 누르면 구름이가 받아 읽어 줍니다. 하트 우편함 대신입니다. */
    private fun openNote() {
        if (cardOpen()) return
        val held = FamilyRepository.state.pending ?: return
        sounds.pop()
        world.heroPose(HeroPose.LETTER, HomeWorld.POSE_MS)
        note.show(unit, "맡긴 쪽지", listOf("${Josa.subject(held.preset.displayName)} 맡긴 말이야.", SpiritPhrases.ALL[held.phrase], "혼자 운전하는 날 전해 줄게."))
    }

    /** 가족 화면. 깨어 있는 아기를 누르거나, 아무 아기나 길게 누르면 열립니다. */
    private fun openFamily(): Boolean {
        if (!mayOpen()) return true
        finishIntro()
        sounds.pop()
        startActivity(FamilyActivity.intent(this, adding = false))
        return true
    }

    /** 콕 찌르면 볼이 부풀고, 자꾸 찌르면 쿠션 뒤로 숨습니다. 그림이 없으면 배운 동작을 합니다. */
    private fun pokeSpirit() {
        val now = SystemClock.uptimeMillis()
        pokesInRow = if (now - lastPokeAt < POKE_ROW_MS) pokesInRow + 1 else 1
        lastPokeAt = now
        val habit = when {
            pokesInRow >= HIDE_POKES -> HeroPose.HIDE
            pokesInRow % 2 == 0 -> HeroPose.PUFF
            else -> null
        }
        if (habit == null || !world.heroHabit(habit)) world.hero.poke()
        // 하늘 말은 맑은 아침·낮에만 섞습니다. 비 오는 밤에 "오늘 하늘이 참 좋다."라고 하지 않습니다.
        val pokes = HomeSpeech.pokes(currentScene())
        sayFor(pokes[pokeIndex++ % pokes.size], POKE_LINE_MS)
    }

    /** 길게 누르면 쓰다듬습니다. */
    private fun petSpirit(): Boolean {
        // 옷을 입어 쓰다듬기 그림을 못 쓰면 옷을 입은 채 하트만 떠오릅니다.
        if (!world.heroHabit(HeroPose.PET) && !world.outfitHolds(HeroPose.PET)) return false
        world.hero.heart()
        sayFor(HomeSpeech.PET, POKE_LINE_MS)
        return true
    }

    /** 쪽지를 든 아기는 쪽지를 건네고, 깨어 있는 아기는 가족 화면을 엽니다(컨셉 점검 5-4). */
    private fun pokeFriend(index: Int) {
        val spirit = world.cast.getOrNull(index) ?: return
        if (spirit.preset == world.noteHolder) return openNote()
        if (world.awake(spirit)) {
            openFamily()
            return
        }
        // 자는 아기를 누르면 다음에 함께 가자고 부릅니다. 안전벨트를 채우면 부른 아기부터 탑니다. 한 번 더 누르면 거둡니다.
        val bridge = VehicleProbeService.activeBridge ?: return
        val name = spirit.preset.displayName
        if (spirit.preset in FamilyRepository.state.called) {
            bridge.uncall(spirit.preset)
            sayFor("다음에 ${Josa.and(name)} 같이 가자.", POKE_LINE_MS)
        } else {
            bridge.call(spirit.preset)
            world.friendPose(spirit.preset, FriendPose.WAVE, HomeWorld.POSE_MS)
            sayFor("${Josa.subject(name)} 같이 가고 싶대.\n안전벨트를 매면 함께 가.", POKE_LINE_MS)
        }
    }

    /** 부를 수 있는 이름. 구름이와 우리 집 아기들입니다. */
    private fun callableNames(): List<String> =
        listOf(FamilySpeech.HERO_NAME) + FamilyRepository.state.spirits.map { it.preset.displayName }

    /** 마이크가 구름이 목소리를 듣지 않게 말을 멈추고 귀를 기울입니다. */
    private fun listenForName() {
        finishIntro()
        voice?.stop()
        sounds.pop()
        sayFor("누구를 부를까?\n이름을 불러 줘.", NameCall.LISTEN_MS)
    }

    /** 이름을 부르면 그 정령이 대답합니다. 아기를 부르면 다음에 함께 갑니다(강가 장소 명세 6, 7장). */
    private fun answerName(name: String) {
        if (name == FamilySpeech.HERO_NAME) {
            sounds.chime()
            world.hero.celebrate()
            answer("응, 나 여기 있어!")
            return
        }
        val spirit = FamilyRepository.state.spirits.firstOrNull { it.preset.displayName == name } ?: return missName(NameCall.Miss.UNCLEAR)
        sounds.chime()
        world.friendPose(spirit.preset, FriendPose.WAVE, HomeWorld.POSE_MS)
        world.friendOf(spirit.preset)?.first?.cheer()
        val who = Josa.subject(name)
        val bridge = VehicleProbeService.activeBridge
        answer(
            when {
                spirit.preset in FamilyRepository.riders -> "$who 여기 있대!"
                spirit.preset in FamilyRepository.state.called -> "$who 벌써 기다리고 있어.\n안전벨트를 매면 함께 가."
                bridge != null -> {
                    bridge.call(spirit.preset)
                    "$who 대답했어!\n안전벨트를 매면 함께 가."
                }
                else -> "$who 대답했어!"
            },
        )
    }

    /** 못 알아들으면 갸웃하고 한 번 더 부르게 합니다. */
    private fun missName(why: NameCall.Miss) {
        // 마이크를 허락하지 않아도 이름 부르기를 누른 것이라 세 마디를 끝냅니다.
        finishIntro()
        world.heroPose(HeroPose.TILT, HomeWorld.POSE_MS)
        answer(
            when (why) {
                NameCall.Miss.UNCLEAR -> "응? 잘 못 들었어.\n한 번 더 불러 줄래?"
                NameCall.Miss.BROKEN -> "지금은 귀가 잘 안 들려.\n조금 뒤에 다시 불러 줘."
                NameCall.Miss.DENIED -> "마이크를 허락하면\n이름을 들을 수 있어."
            },
        )
    }

    /** 대답은 말풍선과 구름이 목소리로 함께 냅니다. 말풍선은 말소리가 끝날 때까지 둡니다. */
    private fun answer(line: String) {
        sayFor(line, CARE_LINE_MS)
        answeredAt = SystemClock.uptimeMillis()
        val speaker = voice ?: VoiceProbe(this).also {
            it.onSpeaking = { speaking -> handler.post { holdForSpeech(speaking) } }
            voice = it
        }
        speaker.speak(line.replace('\n', ' '))
    }

    /** 대답을 말하는 동안 말풍선을 두고, 다 말하면 조금 뒤 평소 말로 돌아갑니다. 그사이 다른 말을 띄웠으면 그대로 둡니다. */
    private fun holdForSpeech(speaking: Boolean) {
        if (answeredAt == 0L) return
        if (speaking) return holdBubble(SPEECH_GUARD_MS)
        holdBubble(maxOf(AFTER_SPEECH_MS, CARE_LINE_MS - (SystemClock.uptimeMillis() - answeredAt)))
    }

    private companion object {
        const val WIDE_RATIO = 1.15f
        private const val SCENE_WAIT_MS = 1_500L
        private const val RIVER_TAB_TOP = 0.44f
        private const val SWIPE_SHARE = 0.22f
        private const val SWIPE_SLACK = 0.15f
        private const val POKE_ROW_MS = 1_800L
        private const val HIDE_POKES = 4
        private const val HUG_DELAY_MS = 2_400L
        private const val FEAST_FROM = 100
        private const val BURST_X = 0.09f
        private const val BURST_Y = 0.42f
        private const val DOT_SIDE = 7.2f

        /** 장소 이름. "구름이"가 차, 비버, 장소로 번갈아 읽히지 않게 집 이름으로 씁니다(컨셉 점검 4-1의 1). */
        private const val PLACE_NAME = "구름이네 집"

        /** 병 꼬리표의 위쪽. 병 바닥에서 병 키의 이만큼 위, 목 바로 아래입니다. 깃털은 바닥부터 차서 가리지 않습니다. */
        private const val JAR_TAG_DROP = 0.8f
        const val POKE_LINE_MS = 2_600L
        const val CARE_LINE_MS = 5_000L
        const val SPEECH_GUARD_MS = 20_000L // 말이 끝났다는 알림을 못 받아도 말풍선이 이보다 오래 남지 않습니다.
        const val AFTER_SPEECH_MS = 1_200L
        const val SEAT_LINE_MS = 4_000L
        const val SCENE_TICK_MS = 60_000L
        const val GREET_MS = 2_200L
        const val DOZE_MS = 40_000L

        /** 처음 켠 날 세 마디에서 한 마디를 두는 시간. 말풍선을 누르면 바로 다음 마디입니다(docs/guide/first-run.md 3-2). */
        const val INTRO_STEP_MS = 7_000L
        const val POP_FROM = 0.9f
        const val POP_MS = 280L
        const val POP_TENSION = 2.4f
        const val EXTRA_SMOOTH_FPS = "smooth_fps"
        const val MAX_SMOOTH_FPS = 60

        /** 입구를 한 번 연 뒤 다시 열지 않는 시간. 홈이 가려지면 그 전에 끝납니다. */
        const val OPEN_GUARD_MS = 1_200L

        /** 알림 허락. 첫 도착 뒤 집에서 한 번 묻습니다. */
        const val NOTIFY_REQUEST = 1

        /** 집으로 돌아온 뒤 알림을 묻기까지의 틈. 집이 다시 그려지고 말풍선이 먼저 뜹니다. */
        const val NOTIFY_DELAY_MS = 1_500L

        /** 알림 1, 이름 부르기 마이크 2([NameCall.PERMISSION_REQUEST]) 다음 번호입니다. */
        const val SPEED_REQUEST = 3

        /** 처음 켠 날 ③이 뜬 뒤 속도 쪽지까지의 틈. ③을 읽고 이름표가 튀는 것을 볼 시간입니다. */
        const val SPEED_AFTER_INTRO_MS = 3_000L
        const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED"

        /**
         * 이보다 빨리 온 속도 거절은 창 없이 온 것으로 봅니다. 정책으로 막힌 요청이 이렇게 옵니다.
         * 사람은 창이 뜬 걸 보고서야 닫습니다. 11/8 기기에서 속도 창은 요청 뒤 0.15~0.27초에 떴습니다.
         */
        const val SPEED_NO_DIALOG_MS = 500L

        /**
         * 속도 허락의 이유 쪽지. 기획서 5번의 "첫 실행 때 이유와 함께 묻습니다"와 맞춥니다.
         * AAOS는 차 속도(`CAR_SPEED`)를 위치 권한 묶음에 넣어, 시스템 창이 "위치 정보에 액세스"라고 묻습니다.
         * 앱은 위치 권한을 선언하지 않고 위치 API도 쓰지 않습니다. 쪽지가 이 말을 먼저 풀어 둡니다.
         */
        const val SPEED_TITLE = "속도를 봐도 될까?"
        val SPEED_LINES = listOf(
            "부드럽게 멈추고 깜빡이를 켠 걸 알아보려면 차 속도가 필요해.",
            "허락 창에는 '위치 정보'로 나와. 차 속도가 그 묶음에 들어 있거든.",
            "위치는 보지 않아. 속도도 운전을 알아보는 데만 써.",
            "누르면 허락을 물어볼게.",
        )

        /** 말풍선 자리. 구름이 머리 오른쪽 위의 잔잔한 벽입니다. */
        const val BUBBLE_SIDE = 96f
        const val BUBBLE_ABOVE = 330f
        const val TAG_DAYS = 3
        const val TAG_HOLD_MS = 6_000L
        const val TAG_FADE_MS = 400L
        val TAG_TILT = floatArrayOf(-1.2f, 0.8f, -0.8f)
    }
}
