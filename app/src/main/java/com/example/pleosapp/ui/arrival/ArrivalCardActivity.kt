package com.example.pleosapp.ui.arrival

import android.app.Activity
import android.app.NotificationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.pleosapp.R
import com.example.pleosapp.engine.ArrivalAloud
import com.example.pleosapp.engine.Badge
import com.example.pleosapp.engine.CareRules
import com.example.pleosapp.engine.DayPart
import com.example.pleosapp.engine.DiaryTemplate
import com.example.pleosapp.engine.DriveEvent
import com.example.pleosapp.engine.EngineConfig
import com.example.pleosapp.engine.FamilyRepository
import com.example.pleosapp.engine.FamilySpeech
import com.example.pleosapp.engine.FamilySpirit
import com.example.pleosapp.engine.Growth
import com.example.pleosapp.engine.HomeSpeech
import com.example.pleosapp.engine.Josa
import com.example.pleosapp.engine.ReturnGift
import com.example.pleosapp.engine.KeepsakeRepository
import com.example.pleosapp.engine.Moment
import com.example.pleosapp.engine.PetRepository
import com.example.pleosapp.engine.PetState
import com.example.pleosapp.engine.ReturnKind
import com.example.pleosapp.engine.Reward
import com.example.pleosapp.engine.RewardKind
import com.example.pleosapp.engine.SceneRules
import com.example.pleosapp.engine.SceneState
import com.example.pleosapp.engine.SkySignals
import com.example.pleosapp.engine.SpiritPhrases
import com.example.pleosapp.engine.SpiritPreset
import com.example.pleosapp.engine.SpokenDiary
import com.example.pleosapp.engine.TripSummary
import com.example.pleosapp.spike.ProbeState
import com.example.pleosapp.spike.VehicleProbeService
import com.example.pleosapp.spike.VoiceProbe
import com.example.pleosapp.ui.CardLayout.dp
import com.example.pleosapp.ui.family.FamilyTiles.pillButton
import com.example.pleosapp.ui.family.FamilyTiles.spiritTile
import com.example.pleosapp.ui.game.FeatherBurstView
import com.example.pleosapp.ui.game.GameStyle
import com.example.pleosapp.ui.game.GameStyle.paperText
import com.example.pleosapp.ui.game.GameStyle.sizeTo
import com.example.pleosapp.ui.game.GiftChooser
import com.example.pleosapp.ui.game.GiftReveal
import com.example.pleosapp.ui.game.HomeScene
import com.example.pleosapp.ui.game.HomeWorld
import com.example.pleosapp.ui.game.JarView
import com.example.pleosapp.ui.game.MomentScene
import com.example.pleosapp.ui.game.PaperDrawable
import com.example.pleosapp.ui.game.PaperNote
import com.example.pleosapp.ui.game.ScenePreview
import com.example.pleosapp.ui.game.Seen
import com.example.pleosapp.ui.game.SoftSounds
import com.example.pleosapp.ui.game.SpeechBubbleView
import com.example.pleosapp.ui.game.StageLayout
import com.example.pleosapp.ui.game.StampStripView
import com.example.pleosapp.ui.recall.RecallHost
import com.example.pleosapp.ui.share.PostcardActivity
import com.example.pleosapp.ui.share.Postcards
import com.example.pleosapp.ui.spirit.DuoScene
import com.example.pleosapp.ui.spirit.FriendPose
import com.example.pleosapp.ui.spirit.HeroPose
import com.example.pleosapp.ui.spirit.SpiritSprites
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

/**
 * 도착 화면. 트립이 끝나면(P단) 서비스가 띄웁니다. 홈과 같은 집에서 그날의 보상을 차례로 보여 줍니다:
 * 도장이 찍히고, 깃털이 병에 쌓이고, 병이 차면 깃털이 날리고, 작은 정령이 주워 온 것을 건넵니다.
 * 일기는 구름이 목소리로 읽습니다. D로 바뀌면(TripStarted) 즉시 닫힙니다. 낭독이 끝날 때까지 열려 있고,
 * 낭독이 없으면 10초, 끝난 뒤에는 3초 더 보여 줍니다(터치하면 연장, 최대 30초).
 */
class ArrivalCardActivity : Activity() {

    private lateinit var stage: StageLayout
    private lateinit var world: HomeWorld
    private lateinit var note: ScrollView
    private lateinit var noteBody: LinearLayout
    private lateinit var noteHeading: TextView
    private lateinit var tape: View
    private lateinit var messageBox: LinearLayout
    private lateinit var shareBox: LinearLayout
    private lateinit var countdown: TextView
    private lateinit var stamps: StampStripView
    private lateinit var close: TextView
    private lateinit var bubble: SpeechBubbleView
    private lateinit var jarTag: TextView
    private lateinit var recallTag: TextView
    private lateinit var recall: RecallHost
    private lateinit var carried: ImageView
    private lateinit var burst: FeatherBurstView
    private lateinit var reveal: GiftReveal
    private lateinit var chooser: GiftChooser
    private lateinit var moment: MomentScene

    private lateinit var sounds: SoftSounds
    private lateinit var seen: Seen
    private var diary: List<TextView> = emptyList()
    private var companions: List<FamilySpirit> = emptyList()
    private var trip: TripSummary? = null

    /** 출발 알림이 엔진 스레드에서 바로 끊으므로 그 스레드에서도 보이게 둡니다. */
    @Volatile
    private var voice: VoiceProbe? = null
    private var diaryText = ""

    /** 뒷자리 안전벨트가 풀리기를 기다리는 끝 시각. 기다리지 않으면 0입니다. */
    private var waitRearUntil = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var unit = 10f
    private var remaining = AUTO_CLOSE_SEC
    private var elapsed = 0
    private var talking = false
    private var revealedLine: String? = null
    private var companionRow: View? = null

    /** 아이에게 전할 말을 묻는 동안에는 화면이 닫히지 않게 합니다. */
    private var choosing = false
    private var momentHold = 0L

    /** 이 화면이 일기를 읽기 시작했는지. 닫힐 때 [diaryReading]을 이 화면이 켠 것만 끕니다. */
    private var readingHere = false

    /** 쪽지나 카드가 막 펼쳐진 순간에 시작한 손짓을 끝까지 삼키는 중인지. */
    private var swallowing = false

    /** 엽서를 마지막으로 연 시각. 빠르게 두 번 눌러도 엽서가 두 겹으로 뜨지 않습니다. 카드로 돌아오면 지웁니다. */
    private var openedAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            elapsed++
            watchRear()
            // 선물을 고르거나 받은 선물을 보는 동안에는 세지 않습니다.
            if (!talking && !choosing && !picking()) remaining--
            if (remaining <= 0 || elapsed >= MAX_OPEN_SEC) {
                finish()
            } else {
                // 숫자로 재촉하지 않습니다. 화면은 조용히 닫힙니다.
                countdown.text = when {
                    talking -> "일기를 읽고 있어"
                    choosing || chooser.visibility == View.VISIBLE -> "고르는 동안 기다릴게"
                    else -> ""
                }
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    private val onEvent: (DriveEvent) -> Unit = { event ->
        when (event) {
            // 닫히기(onDestroy)까지 수백 ms가 걸려 그사이 D에서 일기를 읽었습니다.
            // 읽던 말은 이 스레드에서 바로 끊습니다. 닫기 전에 목소리를 놓아 준비 중이던 낭독도 시작하지 않습니다.
            is DriveEvent.TripStarted -> {
                voice?.stop()
                runOnUiThread {
                    stopReading()
                    voice?.release()
                    voice = null
                    finish()
                }
            }
            is DriveEvent.SeatLeft -> runOnUiThread { FamilyRepository.state.riderAt(event.seat)?.let { world.friendPose(it, FriendPose.WAVE, HomeWorld.POSE_MS) } }
            else -> Unit
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shownAtMs = System.currentTimeMillis()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) // 알림으로 열렸으면 알림은 치웁니다
        PetRepository.init(this)
        FamilyRepository.init(this)
        KeepsakeRepository.init(this)
        val state = PetRepository.state
        val family = FamilyRepository.state
        trip = state.lastTrip
        recall = RecallHost(this)
        // 서비스 대체 경로와 같은 인자로 부릅니다. gifts를 빼면 아기가 새 일을 배운 셋째 줄이 늘 빠졌습니다.
        val lines = (trip?.let { DiaryTemplate.lines(it, state, family, KeepsakeRepository.gifts) } ?: listOf("오늘도 잘 부탁해!", "", "")) + listOfNotNull(recall.arrivalLine())
        // 탄 아기는 트립 끝에 남긴 기록으로 봅니다. 자리는 다음 트립에 다른 아기가 앉을 수 있습니다.
        companions = trip?.let { t -> family.rideOf(t.tripId).ifEmpty { family.companions(t.seats + t.tappedSeats) } }.orEmpty()

        ScenePreview.show(window, currentScene().part)
        sounds = SoftSounds(this).apply { muted = trip?.rearStillBelted == true }
        seen = Seen(this)
        setContentView(buildStage(lines))
        PetRepository.addListener(onEvent)
        dressWorld(state)
        world.revealWhenReady(stage, window) { play(PetRepository.state) }

        // 알림으로 열렸으면 서비스가 이미 일기를 읽었으므로 다시 읽지 않습니다(결정 2026-10-30).
        // 카드가 막혀 서비스가 대신 읽은 트립도 다시 읽지 않습니다. 막힌 카드가 남았다가 나중에 뜨면 같은 일기를 또 읽었습니다.
        val alreadySpoken = intent.getBooleanExtra(EXTRA_ALREADY_SPOKEN, false) ||
            trip?.let { SpokenDiary.isSpoken(this, it.tripId) } == true
        // 언제 읽을지는 서비스와 같은 규칙입니다. 밤에 뒷벨트를 맨 채 도착하면 풀려도 읽지 않습니다.
        val aloud = trip?.let { ArrivalAloud.start(it.rearStillBelted, currentScene().part) } ?: ArrivalAloud.Start.NOW
        ProbeState.log("ArrivalCard opened: alreadySpoken=$alreadySpoken aloud=$aloud extras=${intent.extras?.keySet()}")
        diaryText = lines.filter { it.isNotBlank() }.joinToString(" ")
        when {
            alreadySpoken -> Unit
            aloud == ArrivalAloud.Start.NEVER -> ProbeState.log("ArrivalCard stays quiet: night, rear belted")
            // 뒷자리에 아이가 잠들어 있을 수 있어 안전벨트가 풀리기를 기다립니다.
            aloud == ArrivalAloud.Start.AFTER_REAR || intent.getBooleanExtra(EXTRA_WAIT_REAR, false) ->
                waitRearUntil = SystemClock.uptimeMillis() + EngineConfig.REAR_QUIET_WAIT_MS
            else -> readDiary()
        }
        handler.postDelayed(tick, 1_000L)
    }

    private fun readDiary() {
        // 읽는 동안 서비스가 정차 중 인사를 뒤로 미룹니다. 엔진이 뜨기 전에도 켜 둬야 인사가 끼어들지 않습니다.
        diaryReading = true
        readingHere = true
        // 같은 트립의 카드가 다시 떠도 또 읽지 않게 적어 둡니다.
        trip?.let { SpokenDiary.mark(this, it.tripId) }
        // speak보다 먼저 voice에 넣습니다. 그사이 출발(TripStarted)이 와도 엔진 스레드의 stop이 이 목소리에 닿습니다.
        val v = VoiceProbe(this)
        voice = v
        v.onSpeaking = { speaking -> runOnUiThread { onSpeaking(speaking) } }
        v.speak(diaryText)
    }

    /** 기다리는 동안 풀리면 읽고, 끝까지 풀리지 않으면 자는 아이로 보고 읽지 않습니다. */
    private fun watchRear() {
        if (waitRearUntil == 0L) return
        when {
            VehicleProbeService.activeBridge?.rearBelted() == false -> {
                waitRearUntil = 0L
                sounds.muted = false
                ProbeState.log("ArrivalCard speaking after rear belts released")
                readDiary()
            }
            SystemClock.uptimeMillis() >= waitRearUntil -> {
                waitRearUntil = 0L
                ProbeState.log("ArrivalCard stays quiet")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        world.ticker.start()
    }

    override fun onStop() {
        world.ticker.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // 엽서를 닫고 돌아오면 막이를 기다리지 않고 다시 엽니다.
        openedAt = 0L
    }

    /** 화면을 열어도 되는지. 홈 입구의 '한 번만 열기'와 같은 꼴입니다. */
    private fun mayOpen(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - openedAt < OPEN_GUARD_MS) return false
        openedAt = now
        return true
    }

    private fun onSpeaking(speaking: Boolean) {
        talking = speaking
        world.hero.setTalking(speaking)
        if (!speaking) {
            remaining = max(remaining, AFTER_SPEECH_SEC)
            stopReading()
        }
    }

    private fun stopReading() {
        if (!readingHere) return
        readingHere = false
        diaryReading = false
    }

    /** 선물을 고르거나 받은 선물을 보는 중인지. 이때는 화면이 저절로 닫히지 않습니다. */
    private fun picking(): Boolean = chooser.visibility == View.VISIBLE || reveal.visibility == View.VISIBLE

    /** 새 카드가 펼쳐질 때 30초 상한을 새로 셉니다. 늦게 펼친 카드가 보는 중에 닫히지 않습니다. */
    private fun freshWindow() {
        elapsed = 0
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 쪽지나 카드가 막 펼쳐진 순간의 두 번째 손짓은 아직 자리 잡지 않은 카드를 지나 뒤의 단추에 닿았습니다.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) swallowing = PaperNote.justOpened()
        if (swallowing) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowing = false
            return true
        }
        // 만지면 연장합니다. 선물을 기다리느라 길게 남은 시간을 줄이지는 않습니다.
        remaining = max(remaining, AUTO_CLOSE_SEC)
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        PetRepository.removeListener(onEvent)
        stopReading()
        voice?.release()
        sounds.release()
        super.onDestroy()
    }

    private fun buildStage(lines: List<String>): View {
        world = HomeWorld(this)
        world.jar.setOnClickListener { openJar() }
        noteBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        noteHeading = paperText("오늘의 일기", bold = true)
        noteBody.addView(noteHeading)
        diary = lines.filter { it.isNotBlank() }.map { line ->
            paperText(GameStyle.keepWords(line)).apply {
                alpha = 0f
                noteBody.addView(this)
                noteBody.addView(rule())
            }
        }
        messageBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        noteBody.addView(messageBox)
        shareBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        noteBody.addView(shareBox)
        countdown = paperText(color = GameStyle.INK_SOFT).apply { gravity = Gravity.END }
        noteBody.addView(countdown, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        note = ScrollView(this).apply {
            rotation = 0.8f
            addView(noteBody)
        }
        tape = View(this).apply {
            setBackgroundColor(TAPE)
            rotation = -4f
        }
        stamps = StampStripView(this).apply { contentDescription = StampStripView.TITLE }
        close = paperText("닫기", bold = true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { finish() }
        }
        bubble = SpeechBubbleView(this).apply { visibility = View.INVISIBLE }
        jarTag = paperText(bold = true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { openJar() }
        }
        recallTag = paperText("그림책 펼치기", bold = true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            visibility = View.GONE
            setOnClickListener { openRecall() }
        }
        carried = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.INVISIBLE
        }
        burst = FeatherBurstView(this)
        reveal = GiftReveal(this, world.ticker, { world.age.fernArt }).apply { onClosed = ::afterReveal }
        chooser = GiftChooser(this) { world.age.fernArt }
        moment = MomentScene(this)
        world.ticker.add(stamps, burst)
        stage = StageLayout(this).apply {
            setBackgroundColor(GameStyle.INTERIOR)
            (world.layers + listOf(jarTag, carried, note, tape, stamps, close, bubble, recallTag, burst, chooser, reveal, moment)).forEach(::addView)
            clipToSelf(note)
            onResize = ::arrange
        }
        return stage
    }

    /** 공책 줄. 일기장처럼 보이게 합니다. */
    private fun rule(): View = View(this).apply {
        setBackgroundColor(RULE)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2))
    }

    /** 가로 창은 왼쪽에 집, 오른쪽에 일기입니다. 세로로 좁은 창은 위아래로 나눕니다. */
    private fun arrange(w: Int, h: Int) {
        val wide = w >= h * WIDE_RATIO
        val u = if (wide) min(w / 170f, h / 121f) else min(w / 96f, h / 150f)
        unit = u
        stamps.compact = !wide
        // 좁은 창에서는 정령이 이미 집에 보이고 일기장이 작습니다. 버튼만 둡니다.
        companionRow?.visibility = if (wide) View.VISIBLE else View.GONE
        val gap = (2.4f * u).toInt()
        style(u)
        val barHeight = (9f * u).toInt()
        val closeWidth = (15f * u).toInt()
        val barTop = h - barHeight - gap
        stage.place(close, w - gap - closeWidth, barTop, closeWidth, barHeight)
        if (wide) {
            world.placeWide(stage, w, h)
            val scene = world.scene
            val noteTop = (3f * u).toInt()
            val noteHeight = barTop - noteTop - gap
            val noteWidth = (w * NOTE_WIDTH).toInt()
            val noteLeft = w - noteWidth - (3f * u).toInt()
            stage.place(note, noteLeft, noteTop, noteWidth, noteHeight, hangs = true)
            stage.place(tape, noteLeft + noteWidth / 2 - (9f * u).toInt(), noteTop - (1.4f * u).toInt(), (18f * u).toInt(), (4f * u).toInt())
            stage.place(stamps, gap, barTop, noteLeft - gap * 2, barHeight)
            val bubbleLeft = scene.viewX(BUBBLE_X).toInt()
            val bubbleWidth = min((40f * u).toInt(), noteLeft - bubbleLeft - (3.2f * u).toInt())
            stage.place(bubble, bubbleLeft, (3f * u).toInt(), bubbleWidth, scene.viewY(BUBBLE_BOTTOM).toInt() - (3f * u).toInt(), wrapHeight = true)
            stage.place(recallTag, bubbleLeft, scene.viewY(BUBBLE_BOTTOM).toInt() + gap / 2, (30f * u).toInt(), (7.4f * u).toInt())
            val spot = HomeScene.JAR
            val tagWidth = (24f * u).toInt()
            stage.place(jarTag, (scene.viewX(spot.x) - tagWidth / 2f).toInt().coerceAtLeast(gap / 2), (scene.viewY(spot.bottom) + 0.8f * u).toInt(), tagWidth, (6.2f * u).toInt())
        } else {
            val sceneHeight = (h * 0.42f).toInt()
            world.placeNarrow(stage, w, h, sceneHeight)
            val noteTop = sceneHeight + gap
            val noteHeight = barTop - noteTop - gap
            stage.place(note, gap, noteTop, w - gap * 2, noteHeight, hangs = true)
            stage.place(tape, w / 2 - (9f * u).toInt(), noteTop - (1.4f * u).toInt(), (18f * u).toInt(), (4f * u).toInt())
            stage.place(stamps, gap, barTop, w - gap * 2 - closeWidth - gap, barHeight)
            val bubbleLeft = (w * 0.3f).toInt()
            val bubbleWidth = min(w - bubbleLeft - gap, (46f * u).toInt())
            stage.place(bubble, bubbleLeft, gap, bubbleWidth, (sceneHeight * 0.34f).toInt(), wrapHeight = true)
            stage.place(recallTag, bubbleLeft, gap + (sceneHeight * 0.34f).toInt() + gap / 2, (30f * u).toInt(), (7.4f * u).toInt())
            val tagWidth = (24f * u).toInt()
            stage.place(jarTag, w - tagWidth - gap, sceneHeight - (7f * u).toInt(), tagWidth, (6.2f * u).toInt())
        }
        world.forgetScene()
        // 열린 카드는 새 창 크기에 맞지 않습니다. 선물은 이미 받았으니 닫아도 잃는 것이 없습니다.
        // 고르던 카드는 닫아도 병이 그대로라 다시 누르면 됩니다.
        reveal.close()
        chooser.close()
        handler.post(::renderScene)
    }

    /** 말풍선도 낱말 가운데서 끊기지 않게 합니다. */
    private fun say(line: String) {
        bubble.text = GameStyle.keepWords(line)
    }

    private fun style(u: Float) {
        val radius = 2.4f * u
        note.background = PaperDrawable(this, radius)
        note.elevation = 1f * u
        noteBody.setPadding((4.6f * u).toInt(), (5f * u).toInt(), (4.6f * u).toInt(), (2.6f * u).toInt())
        noteHeading.sizeTo(4.4f * u, 20f)
        noteHeading.setPadding(0, 0, 0, (1.8f * u).toInt())
        diary.forEach {
            it.sizeTo(3.3f * u, 18f)
            it.setPadding(0, (1.4f * u).toInt(), 0, (1.1f * u).toInt())
        }
        messageBox.setPadding(0, (1.6f * u).toInt(), 0, 0)
        countdown.sizeTo(2.5f * u, 14f)
        countdown.setPadding(0, (1.2f * u).toInt(), 0, 0)
        tape.elevation = 1.2f * u

        stamps.background = PaperDrawable(this, radius)
        stamps.elevation = 0.8f * u
        close.background = PaperDrawable(this, radius)
        close.elevation = 0.8f * u
        close.sizeTo(3.4f * u, 18f)

        jarTag.background = PaperDrawable(this, 2f * u)
        jarTag.elevation = 0.8f * u
        jarTag.sizeTo(2.8f * u, 14f)
        recallTag.background = PaperDrawable(this, 2f * u)
        recallTag.elevation = 1f * u
        recallTag.sizeTo(3f * u, 15f)
        bubble.sizeTo(3.3f * u, 18f)
        bubble.setPaddingFor(u)
    }

    private fun currentScene(): SceneState {
        val now = Calendar.getInstance()
        val part = SceneRules.dayPart(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MONTH) + 1)
        return SkySignals.scene(part, System.currentTimeMillis())
    }

    private fun renderScene() {
        val scene = currentScene()
        if (!world.applyScene(scene, KeepsakeRepository.gifts)) return
        val filter = world.paperFilter()
        listOf<View>(note, stamps, close, jarTag, recallTag).forEach { it.background?.colorFilter = filter }
        bubble.setPaper(if (scene.part == DayPart.NIGHT) GameStyle.PAPER_NIGHT else GameStyle.PAPER)
    }

    private fun dressWorld(state: PetState) {
        val care = KeepsakeRepository.care
        val keep = KeepsakeRepository.gifts
        val motions = CareRules.owned(state, keep, care).filter { it.kind == RewardKind.MOTION }.sortedBy { it.ordinal }
        world.dress(state, keep, CareRules.look(state, care, KeepsakeRepository.odoKm, System.currentTimeMillis()), motions)
        world.dressArrival(FamilyRepository.state, companions)
        world.showDecor(keep)
    }

    /** 그날의 보상을 순서대로 보여 줍니다. 한꺼번에 나오면 아무것도 기억에 남지 않습니다. */
    private fun play(state: PetState) {
        val before = state.feathers - state.lastGain
        val from = Growth.jar(before)
        val to = state.jar
        world.jar.show(JarView.visible(from))
        jarTag.text = if (state.lastGain > 0) "깃털 ${state.lastGain}개 주웠어" else "깃털은 다음에"
        showNote()
        val earned = HomeSpeech.stamps(state)
        stamps.show(earned)
        stamps.stamp(STAMP_DELAY_MS)
        val pressed = listOf(earned.belt, earned.softStop, earned.blinker, earned.rest)
        pressed.forEachIndexed { i, done ->
            if (done) handler.postDelayed({ sounds.stamp() }, STAMP_DELAY_MS + StampStripView.pressDelay(i))
        }
        if (trip != null && companions.isNotEmpty()) showMessageButton(companions, trip!!)
        showShareButton()

        diary.forEachIndexed { i, line ->
            line.animate().alpha(1f).setStartDelay(NOTE_DELAY_MS + LINE_DELAY_MS + i * LINE_GAP_MS).setDuration(LINE_FADE_MS).start()
        }
        handler.postDelayed({
            world.hero.celebrate()
            world.heroPose(HeroPose.CHEER, HomeWorld.POSE_MS)
        }, HOP_DELAY_MS)
        handler.postDelayed({ pour(state, from, to) }, POUR_DELAY_MS)
    }

    /** 처음 몇 초는 집과 정령만 봅니다. 일기는 그 뒤에 내려옵니다. */
    private fun showNote() {
        val drop = 6f * unit
        listOf(note, tape).forEach { view ->
            view.alpha = 0f
            view.translationY = -drop
            view.animate().alpha(1f).translationY(0f).setStartDelay(NOTE_DELAY_MS).setDuration(NOTE_FADE_MS).start()
        }
    }

    private fun pour(state: PetState, from: Growth.Jar, to: Growth.Jar) {
        val start = JarView.visible(from)
        if (to.opened > from.opened) {
            pourSounds(start, JarView.VISIBLE)
            world.jar.pour(start, JarView.VISIBLE) { levelUp(state, to) }
        } else {
            val end = JarView.visible(to)
            pourSounds(start, end)
            world.jar.pour(start, end) { settle(state) }
        }
    }

    private fun pourSounds(from: Int, to: Int) {
        for (index in from until to) {
            handler.postDelayed({ sounds.feather(index) }, JarView.landDelay(index - from))
        }
    }

    /** 병이 가득 찬 순간. 이 화면에서 가장 큰 기쁨입니다. */
    private fun levelUp(state: PetState, to: Growth.Jar) {
        burst.burst(BURST_X, BURST_Y)
        sounds.chime()
        world.hero.celebrate()
        world.heroPose(HeroPose.CHEER, HomeWorld.POSE_MS)
        say("병이 가득 찼어!")
        popBanner()
        // 가득 찬 병은 선물을 열 때까지 그대로 둡니다.
        settle(state)
    }

    private fun popBanner() {
        bubble.visibility = View.VISIBLE
        bubble.scaleX = 0.7f
        bubble.scaleY = 0.7f
        bubble.alpha = 0f
        bubble.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420L).setInterpolator(OvershootInterpolator(2.6f)).start()
    }

    private fun hideBanner(after: () -> Unit) {
        if (bubble.visibility != View.VISIBLE) {
            after()
            return
        }
        bubble.animate().alpha(0f).setDuration(240L).withEndAction {
            bubble.alpha = 1f
            bubble.visibility = View.INVISIBLE
            after()
        }.start()
    }

    /** 다 쌓인 뒤의 차례. 새 동작을 하나씩 보여 주고, 주워 온 것을 받고, 선물로 마칩니다. */
    private fun settle(state: PetState) {
        val keep = KeepsakeRepository.gifts
        val fresh = Badge.earned(state).filterNot { it in keep.badgesSeen }
        KeepsakeRepository.updateGifts { gifts -> gifts.copy(badgesSeen = gifts.badgesSeen + fresh) }
        val wait = if (bubble.visibility == View.VISIBLE) BANNER_HOLD_MS else 0L
        handler.postDelayed({ showBadges(state, fresh) }, wait)
    }

    private fun showBadges(state: PetState, badges: List<Badge>) {
        if (badges.isEmpty()) {
            hideBanner { showReturned(state) }
            return
        }
        val badge = badges.first()
        say("새 동작을 배웠어.\n${badge.reward.title}")
        popBanner()
        sounds.sparkle()
        world.hero.play(badge.reward)
        remaining = max(remaining, BADGE_WAIT_SEC)
        handler.postDelayed({ showBadges(state, badges.drop(1)) }, BANNER_HOLD_MS)
    }

    /** 작은 정령이 다녀온 증거. 두 손으로 내밀고, 구름이가 눈웃음으로 받습니다. */
    private fun showReturned(state: PetState) {
        // 선물을 고르거나 받은 선물을 보는 동안에는 건네지 않습니다. 카드에 가려 건네는 모습을 못 봅니다.
        if (picking()) {
            handler.postDelayed({ showReturned(state) }, MOMENT_RETRY_MS)
            return
        }
        val gift = KeepsakeRepository.gifts.shelf.lastOrNull()
        val fresh = KeepsakeRepository.gifts.shelf.size > seen.shelf
        seen.shelf = KeepsakeRepository.gifts.shelf.size
        // 혼자 탄 날의 물건은 구름이가 주워 와 직접 건넵니다.
        if (gift != null && fresh && gift.bringer == null) {
            showHeroReturned(state, gift)
            return
        }
        val companion = companions.firstOrNull { it.preset == gift?.bringer }
        val actor = companion?.let { world.friendOf(it.preset)?.first }
        if (gift == null || !fresh || companion == null || actor == null) {
            showMoment(state)
            return
        }
        world.friendPose(companion.preset, FriendPose.OFFER, RETURN_HOLD_MS)
        world.heroPose(HeroPose.THANKS, RETURN_HOLD_MS)
        val art = SpiritSprites.named(this, "return_${gift.kind.id}")
        if (art != 0) {
            carried.setImageBitmap(SpiritSprites.bitmap(this, art))
            val size = (9f * unit).toInt()
            stage.place(carried, actor.left + actor.width / 2 - size / 2, actor.bottom - (actor.height * 0.42f).toInt(), size, size)
            carried.visibility = View.VISIBLE
            stage.requestLayout()
        }
        sounds.pop()
        // 함께 그린 장면이 있으면 그것으로 건넵니다. 그 그림은 돌을 건네는 장면이라 돌일 때만 씁니다.
        // 다른 물건이면 말("동그란 열매")과 그림(돌)이 어긋납니다.
        if (companion.preset == DuoScene.GIFT.preset && gift.kind == GIFT_SCENE_ITEM && world.duoScene(DuoScene.GIFT, RETURN_HOLD_MS)) {
            carried.visibility = View.INVISIBLE
        }
        say(FamilySpeech.brought(companion.preset.displayName, gift.kind.title))
        bubble.visibility = View.VISIBLE
        remaining = max(remaining, RETURN_WAIT_SEC)
        handler.postDelayed({
            carried.visibility = View.INVISIBLE
            bubble.visibility = View.INVISIBLE
            showMoment(state)
        }, RETURN_HOLD_MS)
    }

    /** 혼자 탄 날 구름이가 주워 온 물건. 구름이가 두 손에 들고 보여 주고, 물건은 강가 칸에 놓을 수 있습니다. */
    private fun showHeroReturned(state: PetState, gift: ReturnGift) {
        world.heroPose(HeroPose.CHEER, RETURN_HOLD_MS)
        val art = SpiritSprites.named(this, "return_${gift.kind.id}")
        if (art != 0) {
            val hero = world.hero
            carried.setImageBitmap(SpiritSprites.bitmap(this, art))
            val size = (9f * unit).toInt()
            stage.place(carried, hero.left + hero.width / 2 - size / 2, hero.bottom - (hero.height * HERO_HANDS).toInt(), size, size)
            carried.visibility = View.VISIBLE
            stage.requestLayout()
        }
        sounds.pop()
        say("오는 길에 ${Josa.objective(gift.kind.title)} 주웠어.\n강가에 놓아 볼까?")
        bubble.visibility = View.VISIBLE
        remaining = max(remaining, RETURN_WAIT_SEC)
        handler.postDelayed({
            carried.visibility = View.INVISIBLE
            bubble.visibility = View.INVISIBLE
            showMoment(state)
        }, RETURN_HOLD_MS)
    }

    /** 앨범에 새 순간이 들어왔으면 한 도착에 하나만 크게 펼칩니다. 나머지는 다음 도착의 몫입니다. */
    private fun showMoment(state: PetState) {
        // 한마디를 고르는 동안에는 덮지 않습니다. 고르고 난 뒤에 펼칩니다.
        // 선물을 고르거나 받은 선물을 보는 동안에도 기다립니다. 순간 그림이 고르기 카드를 덮었습니다.
        if (choosing || picking() || SystemClock.uptimeMillis() < momentHold) {
            handler.postDelayed({ showMoment(state) }, MOMENT_RETRY_MS)
            return
        }
        val already = seen.moments
        val fresh = Moment.entries.firstOrNull { it in KeepsakeRepository.gifts.album && it.id !in already }
        if (fresh == null) {
            offerGift(state)
            return
        }
        seen.moments = already + fresh.id
        remaining = max(remaining, MOMENT_WAIT_SEC)
        freshWindow()
        sounds.sparkle()
        moment.show(unit, Postcards.art(this, fresh), fresh.title, fresh.hint) { offerGift(state) }
    }

    /** 선물이 있으면 병이 숨 쉬며 기다립니다. */
    private fun offerGift(state: PetState) {
        val jar = state.jar
        val waiting = KeepsakeRepository.gifts.pending(jar.opened)
        world.jar.setWaiting(waiting > 0)
        jarTag.text = if (waiting > 0) "선물 열기" else "선물까지 깃털 ${jar.left}개"
        if (waiting > 0) {
            sounds.pop()
            say(HomeSpeech.giftLine(currentScene().part))
            bubble.visibility = View.VISIBLE
            remaining = max(remaining, GIFT_WAIT_SEC)
        } else {
            offerRecall()
        }
    }

    /** 고비에 닿은 날은 맨 끝에 그림책을 권합니다. 누르지 않아도 앨범에 남습니다. */
    private fun offerRecall() {
        val line = recall.inviteLine() ?: return
        if (bubble.visibility != View.VISIBLE) {
            say(line)
            bubble.visibility = View.VISIBLE
        }
        recallTag.visibility = View.VISIBLE
        remaining = max(remaining, GIFT_WAIT_SEC)
    }

    private fun openRecall() {
        if (recall.openWaiting()) finish()
    }

    /**
     * 병을 열면 홈처럼 후보 셋 가운데 하나를 고릅니다. 후보가 하나면 바로 열고, 사다리를 다 모았으면 구름이 쪽지입니다.
     * 고르는 동안에는 도착 화면이 저절로 닫히지 않게 기다립니다.
     */
    private fun openJar() {
        // 병이 여러 개 찼을 때 두 번 누르면 쪽지가 둘 열려 첫 쪽지를 잃었습니다. 카드가 열려 있으면 받지 않습니다.
        if (picking()) return
        val state = PetRepository.state
        val keep = KeepsakeRepository.gifts
        if (keep.pending(state.jar.opened) == 0) return
        remaining = max(remaining, GIFT_WAIT_SEC)
        freshWindow()
        val choices = keep.choices()
        when {
            choices.isEmpty() -> {
                bubble.visibility = View.INVISIBLE
                KeepsakeRepository.updateGifts { it.open(state.jar.opened) }
                sounds.chime()
                revealedLine = null
                reveal.showNote(unit, recall.note(keep.notes))
                world.hero.celebrate()
            }
            choices.size == 1 -> claimGift(choices.first())
            else -> {
                sounds.pop()
                chooser.show(unit, choices, ::claimGift)
            }
        }
    }

    /** 고른 선물을 열고 보상 화면을 띄웁니다. 다음 병의 갈래는 남은 첫 후보로 알려 줍니다. 홈과 같습니다. */
    private fun claimGift(reward: Reward) {
        bubble.visibility = View.INVISIBLE
        KeepsakeRepository.updateGifts { it.choose(reward, PetRepository.state.jar.opened) }
        sounds.chime()
        remaining = max(remaining, GIFT_WAIT_SEC)
        freshWindow()
        revealedLine = HomeSpeech.rewardLine(reward)
        reveal.show(unit, reward, KeepsakeRepository.gifts.choices().firstOrNull())
        world.hero.celebrate()
    }

    private fun afterReveal() {
        val state = PetRepository.state
        burst.burst(BURST_X, BURST_Y)
        sounds.sparkle()
        world.hero.celebrate()
        dressWorld(state)
        renderScene()
        val waiting = KeepsakeRepository.gifts.pending(state.jar.opened)
        world.jar.setWaiting(waiting > 0)
        jarTag.text = if (waiting > 0) "선물이 또 있어" else "선물까지 깃털 ${state.jar.left}개"
        world.jar.show(if (waiting > 0) JarView.VISIBLE else JarView.visible(state.jar))
        if (waiting == 0) handler.post(::offerRecall)
        revealedLine?.let {
            say(it)
            bubble.visibility = View.VISIBLE
        }
    }

    /** 오늘을 엽서로 보냅니다. 주소를 정하기 전에는 입구를 숨깁니다. */
    private fun showShareButton() {
        if (getString(R.string.share_base_url).isBlank()) return
        shareBox.addView(
            pillButton("가족에게 보내기") {
                // 빠르게 두 번 누르면 엽서가 두 겹으로 떠서 닫기를 한 번 눌러도 엽서가 남았습니다.
                if (mayOpen()) {
                    remaining = max(remaining, GIFT_WAIT_SEC)
                    startActivity(PostcardActivity.intent(this))
                }
            }.apply {
                background = PaperDrawable(this@ArrivalCardActivity, dp(16).toFloat())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                }
            },
        )
    }

    private fun showMessageButton(companions: List<FamilySpirit>, trip: TripSummary) {
        companionRow = HorizontalScrollView(this).apply {
            visibility = if (stage.width >= stage.height * WIDE_RATIO) View.VISIBLE else View.GONE
            addView(LinearLayout(this@ArrivalCardActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                companions.forEach { addView(spiritTile(it.preset, it.preset.displayName, awake = true, sizeDp = 84)) }
            })
        }
        messageBox.addView(companionRow)
        messageBox.addView(
            pillButton("쪽지 남기기") {
                if (companions.size == 1) showPhrases(companions.single().preset, trip) else showSpiritChoice(companions, trip)
            }.apply { background = PaperDrawable(this@ArrivalCardActivity, dp(16).toFloat()) },
        )
    }

    private fun showSpiritChoice(companions: List<FamilySpirit>, trip: TripSummary) {
        startChoosing()
        messageBox.removeAllViews()
        companionRow = null
        messageBox.addView(paperText("누가 쪽지를 남길까?", color = GameStyle.INK_SOFT).apply { sizeTo(3f * unit, 16f) })
        messageBox.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                companions.forEach { companion ->
                    addView(spiritTile(companion.preset, companion.preset.displayName, awake = true, sizeDp = 84, onClick = {
                        showPhrases(companion.preset, trip)
                    }))
                }
            },
        )
    }

    private fun startChoosing() {
        choosing = true
        elapsed = 0
    }

    private fun showPhrases(preset: SpiritPreset, trip: TripSummary) {
        startChoosing()
        messageBox.removeAllViews()
        companionRow = null
        messageBox.addView(paperText("${Josa.subject(preset.displayName)} 전할 말을 골라 줘.", color = GameStyle.INK_SOFT).apply { sizeTo(3f * unit, 16f) })
        SpiritPhrases.offered(trip.tripId).forEach { phrase ->
            messageBox.addView(
                pillButton(SpiritPhrases.ALL[phrase]) {
                    FamilyRepository.update { it.hold(preset, phrase) }
                    choosing = false
                    // "맡았어!"를 읽을 틈을 둡니다.
                    momentHold = SystemClock.uptimeMillis() + MOMENT_HOLD_MS
                    messageBox.removeAllViews()
                    messageBox.addView(paperText("맡았어! 혼자 운전하는 날 전해 줄게.").apply { sizeTo(3.3f * unit, 18f) })
                }.apply {
                    background = PaperDrawable(this@ArrivalCardActivity, dp(16).toFloat())
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(8)
                    }
                },
            )
        }
    }

    companion object {
        /** 둘이 함께 건네는 그림(`duo_gift`)에 그려진 물건. 이 물건일 때만 그 장면을 씁니다. */
        private val GIFT_SCENE_ITEM = ReturnKind.STONE

        /** 마지막으로 도착 화면이 뜬 시각. 서비스가 "화면이 못 떴으면 TTS만" 판단에 씁니다. */
        @Volatile
        var shownAtMs = 0L

        /**
         * 도착 화면이 일기를 읽는 동안 참입니다. 정차 중 인사가 낭독을 끊으면 카드 엔진에 끝 알림이 오지 않아
         * "일기를 읽고 있어"인 채 남았습니다. 서비스는 이 값이 참이면 인사를 뒤로 미룹니다.
         */
        @Volatile
        var diaryReading = false
            private set

        /** 도착 화면이 막혔을 때 서비스가 남기는 "여정 일기" 알림의 ID. */
        const val NOTIFICATION_ID = 2

        /** 알림 경로에서 이미 TTS로 읽었음을 알리는 인텐트 extra. */
        const val EXTRA_ALREADY_SPOKEN = "already_spoken"
        const val EXTRA_WAIT_REAR = "wait_rear"

        private const val AUTO_CLOSE_SEC = 10
        private const val AFTER_SPEECH_SEC = 3
        private const val GIFT_WAIT_SEC = 12
        private const val MAX_OPEN_SEC = 30
        private const val WIDE_RATIO = 1.15f
        private const val NOTE_WIDTH = 0.38f
        private const val BANNER_HOLD_MS = 2_400L
        private const val NOTE_DELAY_MS = 2_600L
        private const val NOTE_FADE_MS = 500L
        private const val BADGE_WAIT_SEC = 8
        private const val RETURN_WAIT_SEC = 8
        private const val MOMENT_WAIT_SEC = 14
        private const val MOMENT_RETRY_MS = 1_000L
        private const val MOMENT_HOLD_MS = 2_400L
        private const val RETURN_HOLD_MS = 3_400L

        /** 엽서를 한 번 연 뒤 다시 열지 않는 시간. 홈 입구와 같습니다. 카드로 돌아오면 그 전에 끝납니다. */
        private const val OPEN_GUARD_MS = 1_200L

        /** 구름이 손 높이. 뷰 아래에서 잰 비율입니다. */
        private const val HERO_HANDS = 0.42f
        private const val RULE = 0x55C9AE96
        private const val HOP_DELAY_MS = 200L
        private const val STAMP_DELAY_MS = 500L
        private const val LINE_DELAY_MS = 300L
        private const val LINE_GAP_MS = 500L
        private const val LINE_FADE_MS = 500L
        private const val POUR_DELAY_MS = 2_100L
        private const val TAPE = 0xB8E8B4A0.toInt()
        private const val BURST_X = 0.1f
        private const val BURST_Y = 0.5f
        private const val BUBBLE_X = 610f
        private const val BUBBLE_BOTTOM = 340f
    }
}
