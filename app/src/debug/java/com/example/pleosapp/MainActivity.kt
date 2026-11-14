package com.example.pleosapp

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.pleosapp.engine.Badge
import com.example.pleosapp.engine.CareItem
import com.example.pleosapp.engine.EngineConfig
import com.example.pleosapp.engine.FamilyRepository
import com.example.pleosapp.engine.FamilyState
import com.example.pleosapp.engine.GiftState
import com.example.pleosapp.engine.Guest
import com.example.pleosapp.engine.PetRepository
import com.example.pleosapp.engine.Place
import com.example.pleosapp.engine.PlaceState
import com.example.pleosapp.engine.ReturnGift
import com.example.pleosapp.engine.ReturnKind
import com.example.pleosapp.engine.SeatSlot
import com.example.pleosapp.engine.SpiritPreset
import com.example.pleosapp.engine.TripSummary
import com.example.pleosapp.engine.VehicleKind
import com.example.pleosapp.engine.DriveEvent
import com.example.pleosapp.engine.KeepsakeRepository
import com.example.pleosapp.spike.NaviProbe
import com.example.pleosapp.ui.arrival.ArrivalCardActivity
import com.example.pleosapp.spike.ProbeState
import com.example.pleosapp.spike.SttProbe
import com.example.pleosapp.spike.VehicleProbe
import com.example.pleosapp.spike.VehicleProbeService
import com.example.pleosapp.spike.VoiceProbe
import com.example.pleosapp.ui.spirit.SpiritPreviewActivity
import com.example.pleosapp.verify.SceneInput
import java.io.File

/**
 * 기술 검증 콘솔(debug 빌드 전용). 버튼 또는 adb 인텐트(`--es spike <command>`)로 각 검증을 실행합니다.
 * 명령: service, signals, door, tts, stt_mic, stt_file, net, navi_route, navi_rest, navi_info, navi_cancel,
 * rear_belt(`--ez buckled true|false`), rear_door(`--ez open true|false`). 벨트는 에뮬레이터에서 값이 안 바뀌어 여기서 넣습니다.
 * 두 명령 모두 `--es seat front|left|center|right`로 자리를 고릅니다.
 * rear_away(`--es seat left --el ms 7200000`)는 내린 자리를 그만큼 오래 비운 것처럼 앞당깁니다. 소리 내어 반기는 장면에 씁니다.
 * family_add(`--es preset som --es seat left`), family_clear는 시험용으로 가족 정령을 넣고 지웁니다.
 * scene(`--es time night --es sky rain`)은 시간과 날씨 장면을 미리 봅니다. 값을 빼면 실제 값으로 돌아갑니다.
 * feathers(`--ei count 10`)는 깃털을 넣고, gift_open은 기다리는 선물을 엽니다. 사다리 칸을 시연할 때 씁니다.
 * care_done(`--es item oil|rotation|filter|wash`)은 정비를 수첩에 적습니다.
 * rest는 쉬어 간 날을, habit(`--es badge soft|signal|belt`)은 그 배지를 채운 트립을 넣습니다. 오래 달려야 나오는 장면에 씁니다.
 * smooth는 부드러운 정지를 엔진 밖에서 넣어 깃털 소리를 시험하고, focus_hold(`--ei usage 16 --el ms 8000`)는 오디오 포커스를 잡아 거절을 만듭니다.
 * kind(`--es value ELECTRIC|ENGINE`)는 차종을 바꿉니다. 서비스가 다시 뜨면 차가 알려 준 값으로 돌아갑니다.
 *
 * 콘솔이 앞으로 나오면 홈이 가려져 장면이 보이지 않습니다. 콘솔을 한 번 연 뒤에는 차와 가족 입력을 방송으로 넣습니다.
 * `adb shell am broadcast --user 10 -a com.example.pleosapp.SPIKE --es spike rear_belt --ez buckled true`
 */
class MainActivity : Activity() {

    private lateinit var vehicle: Vehicle
    private lateinit var voice: VoiceProbe
    private lateinit var stt: SttProbe
    private lateinit var navi: NaviProbe
    private var probe: VehicleProbe? = null
    private lateinit var petView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    private val appendLog: (String) -> Unit = { line ->
        runOnUiThread {
            if (" EVT " in line) petView.text = PetRepository.state.statusLine()
            logView.append(line + "\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vehicle = Vehicle(this).also { it.initialize() }
        voice = VoiceProbe(this)
        stt = SttProbe(this)
        navi = NaviProbe(this)
        PetRepository.init(this)
        setContentView(buildConsole())
        ProbeState.addListener(appendLog)
        DebugInput.listen(this)
        handleCommand(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCommand(intent)
    }

    private fun handleCommand(intent: Intent?) {
        when (intent?.getStringExtra("spike")) {
            "service" -> VehicleProbeService.start(this)
            "signals" -> if (probe == null) probe = VehicleProbe(vehicle).also { it.registerAll() }
            "door" -> readDriverDoor()
            "tts" -> voice.speak(intent.getStringExtra("text") ?: "안녕! 나는 구름이야. 오늘도 안전하게 가자.")
            "stt_mic" -> stt.recognize(micMode = true, pcmFile = null)
            "stt_file" -> stt.recognize(micMode = false, pcmFile = File(filesDir, "test.pcm"))
            "net" -> stt.checkNetwork()
            "navi_route" -> navi.requestRoute()
            "navi_rest" -> navi.addRestStop()
            "navi_info" -> navi.queryInfo()
            "navi_cancel" -> navi.cancel()
            "spirit" -> startActivity(Intent(this, SpiritPreviewActivity::class.java))
            "arrival" -> startActivity(Intent(this, ArrivalCardActivity::class.java))
            else -> if (intent != null) DebugInput.handle(this, intent)
        }
    }

    private fun buildConsole(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "서비스 시작" to "service",
            "신호 로그" to "signals",
            "도어 조회" to "door",
            "TTS" to "tts",
            "STT(마이크)" to "stt_mic",
            "STT(파일)" to "stt_file",
            "네트워크" to "net",
            "경로" to "navi_route",
            "휴게소" to "navi_rest",
            "내비정보" to "navi_info",
            "구름이" to "spirit",
        ).forEach { (label, command) ->
            row.addView(Button(this).apply {
                text = label
                textSize = 20f
                setOnClickListener { handleCommand(Intent().putExtra("spike", command)) }
            })
        }
        petView = TextView(this).apply {
            textSize = 24f
            text = PetRepository.state.statusLine()
        }
        logView = TextView(this).apply { textSize = 16f }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(petView)
        root.addView(row)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun readDriverDoor() {
        vehicle.getDoor().getCurrentDoorPosition(
            area = DoorArea(DoorValue.ROW_1_LEFT),
            onComplete = { pos: Int? -> ProbeState.log("운전석 도어 위치: $pos") },
            onFailed = { e: Exception -> ProbeState.log("FAIL door ${e.message}") },
        )
    }

    override fun onDestroy() {
        ProbeState.removeListener(appendLog)
        probe?.unregisterAll()
        voice.release()
        stt.release()
        navi.release()
        vehicle.release()
        super.onDestroy()
    }
}

/** 화면을 띄우지 않고 넣을 수 있는 입력. 시연 때 홈을 보여 준 채로 씁니다. */
private object DebugInput {
    private const val ACTION = "com.example.pleosapp.SPIKE"
    private const val SENDER_PERMISSION = "android.permission.DUMP"
    private const val MAX_FEATHERS = 50
    private const val DEMO_REST_MS = 20 * EngineConfig.MINUTE_MS
    private const val DEMO_TRIP_MS = 10 * EngineConfig.MINUTE_MS
    private const val DEMO_TRIP_M = 5_000f
    private var listening = false

    fun listen(context: Context) {
        if (listening) return
        listening = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = handle(context, intent)
        }
        // adb shell만 보낼 수 있게 DUMP 권한을 요구합니다. 차 안의 다른 앱은 시험 입력을 넣지 못합니다.
        context.applicationContext.registerReceiver(receiver, IntentFilter(ACTION), SENDER_PERMISSION, null, Context.RECEIVER_EXPORTED)
    }

    fun handle(context: Context, intent: Intent) {
        val bridge = VehicleProbeService.activeBridge
        when (intent.getStringExtra("spike")) {
            "rear_belt" -> bridge?.onSeatBelt(seatFrom(intent), intent.getBooleanExtra("buckled", true))
            "rear_door" -> bridge?.onRearDoor(seatFrom(intent), intent.getBooleanExtra("open", false))
            "rear_away" -> bridge?.rewindLeft(seatFrom(intent), intent.getLongExtra("ms", EngineConfig.LONG_ABSENCE_MS))
            // 아기는 자리에 묶이지 않습니다. 옛 명령의 `seat`는 받아도 쓰지 않습니다.
            "family_add" -> {
                val preset = SpiritPreset.fromId(intent.getStringExtra("preset").orEmpty()) ?: SpiritPreset.SOM
                FamilyRepository.init(context)
                FamilyRepository.update { it.adopt(preset) }
            }
            "family_clear" -> {
                FamilyRepository.init(context)
                FamilyRepository.update { FamilyState() }
            }
            // 정차 중 이름을 부른 것과 같습니다. 부른 아기가 채운 안전벨트 자리에 먼저 앉습니다.
            "family_call" -> SpiritPreset.fromId(intent.getStringExtra("preset").orEmpty())?.let { bridge?.call(it) }
            "scene" -> previewScene(intent.getStringExtra("time"), intent.getStringExtra("sky"))
            "odo" -> KeepsakeRepository.onOdometer(intent.getFloatExtra("km", 0f))
            "kind" -> KeepsakeRepository.onVehicleKind(VehicleKind.fromStored(intent.getStringExtra("value").orEmpty()))
            "alert" -> PetRepository.accept(DriveEvent.HealthAlert(System.currentTimeMillis(), intent.getStringExtra("code").orEmpty()))
            "recover" -> PetRepository.accept(DriveEvent.HealthRecovered(System.currentTimeMillis(), intent.getStringExtra("code").orEmpty()))
            // 큰 수를 잘못 넣으면 메인 스레드가 오래 멈춥니다. 한 번에 50개까지입니다.
            "feathers" -> repeat(intent.getIntExtra("count", 10).coerceIn(0, MAX_FEATHERS)) {
                PetRepository.accept(DriveEvent.SmoothStop(System.currentTimeMillis(), 1f))
            }
            // 콘솔 속도 입력은 에뮬레이터를 끝낸 적이 있어 소리 시험은 이벤트를 바로 넣습니다.
            "smooth" -> bridge?.inject(DriveEvent.SmoothStop(System.currentTimeMillis(), 1f))
            "focus_hold" -> holdFocus(context, intent.getIntExtra("usage", AudioAttributes.USAGE_ASSISTANT), intent.getLongExtra("ms", 8_000L))
            "rest" -> bridge?.inject(DriveEvent.RestTaken(System.currentTimeMillis(), DEMO_REST_MS))
            "habit" -> Badge.fromId(intent.getStringExtra("badge").orEmpty())?.let { bridge?.inject(habitTrip(it)) }
            "gift_open" -> {
                KeepsakeRepository.init(context)
                KeepsakeRepository.updateGifts { it.open(PetRepository.state.jar.opened) }
            }
            "care_done" -> {
                KeepsakeRepository.init(context)
                CareItem.fromId(intent.getStringExtra("item").orEmpty())?.let { KeepsakeRepository.markDone(it, System.currentTimeMillis()) }
            }
            // 강가 시연 상태. 도착 화면이 필요한 장면은 혼자 탄 10분 트립을 넣습니다.
            "river_first" -> {
                KeepsakeRepository.init(context)
                KeepsakeRepository.updateGifts { it.copy(shelf = emptyList(), itemPoints = 0, places = PlaceState()) }
                bridge?.inject(plainTrip())
            }
            "river_guest" -> {
                val guest = Guest.fromId(intent.getStringExtra("guest")) ?: Guest.DUCK
                KeepsakeRepository.init(context)
                KeepsakeRepository.updateGifts { invite(it, guest) }
                bridge?.inject(plainTrip())
            }
            "river_full" -> {
                val now = System.currentTimeMillis()
                KeepsakeRepository.init(context)
                KeepsakeRepository.updateGifts { gifts ->
                    Guest.entries.fold(gifts) { state, guest ->
                        val atMs = now - (Guest.entries.size - guest.ordinal) * EngineConfig.MINUTE_MS
                        invite(state, guest).let { it.copy(places = it.places.arrive(atMs, atMs)) }
                    }
                }
            }
            "river_put" -> {
                val kind = ReturnKind.fromId(intent.getStringExtra("kind")) ?: ReturnKind.STONE
                KeepsakeRepository.init(context)
                KeepsakeRepository.updateGifts { it.put(Place.RIVER, intent.getIntExtra("slot", 0), kind) }
            }
        }
    }

    /**
     * `--es time morning|day|evening|night --es sky clear|rain|snow|fog`. 빠진 값은 실제 장면으로 돌아갑니다.
     * 목록 밖 값은 받지 않고 로그만 남깁니다. 예전에는 오타 하나로 앱이 죽었습니다.
     */
    private fun previewScene(time: String?, sky: String?) {
        if (!SceneInput.apply(time, sky)) ProbeState.log("SCENE unknown time=$time sky=$sky")
    }

    /** 음성 명령(16)이나 통화 벨(6)이 소리를 가로막는 순간을 만듭니다. 포커스 거절을 시험합니다. */
    private fun holdFocus(context: Context, usage: Int, ms: Long) {
        val audio = context.getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder().setUsage(usage).build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .build()
        ProbeState.log("focus hold usage=$usage result=${audio.requestAudioFocus(request)}")
        Handler(Looper.getMainLooper()).postDelayed({ audio.abandonAudioFocusRequest(request) }, ms)
    }

    /** 그 배지를 채울 만큼 습관을 쌓은 트립. 도착 카드도 그대로 뜹니다. */
    private fun habitTrip(badge: Badge): DriveEvent.TripEnded {
        val now = System.currentTimeMillis()
        val count = (badge.target - badge.progress(PetRepository.state)).coerceAtLeast(1)
        return DriveEvent.TripEnded(
            now,
            TripSummary(
                now, DEMO_TRIP_MS, DEMO_TRIP_M,
                smoothStops = if (badge == Badge.SOFT) count else 0, hardBrakes = 0,
                signalsUsed = if (badge == Badge.SIGNAL) count else 0, signalsMissed = 0,
                rearBelts = if (badge == Badge.BELT) count else 0,
            ),
        )
    }

    /** 칭찬할 일이 없는 혼자 탄 트립. 물건과 손님이 도착에서 정해지는 것을 보여 줍니다. */
    private fun plainTrip(): DriveEvent.TripEnded {
        val now = System.currentTimeMillis()
        return DriveEvent.TripEnded(now, TripSummary(now, DEMO_TRIP_MS, DEMO_TRIP_M, 0, 0, 0, 0))
    }

    /** 물건 12종을 다 갖게 하고 강가 칸에 그 손님이 좋아하는 것 둘만 놓습니다. 다음 도착에 그 손님이 신나게 놉니다. */
    private fun invite(gifts: GiftState, guest: Guest): GiftState {
        val missing = ReturnKind.entries.filter { it !in gifts.kinds }.map { ReturnGift(it, null) }
        val stocked = gifts.copy(shelf = gifts.shelf + missing, places = gifts.places.open(Place.RIVER, 0L))
        val cleared = (0 until PlaceState.SLOTS).fold(stocked) { state, index -> state.take(Place.RIVER, index) }
        return guest.likes.foldIndexed(cleared) { index, state, kind -> state.put(Place.RIVER, index, kind) }
    }

    /** `--es seat front|left|center|right`. 없으면 왼쪽 뒷자리입니다. */
    private fun seatFrom(intent: Intent): SeatSlot = when (intent.getStringExtra("seat")) {
        "front" -> SeatSlot.FRONT_RIGHT
        "center" -> SeatSlot.REAR_CENTER
        "right" -> SeatSlot.REAR_RIGHT
        else -> SeatSlot.REAR_LEFT
    }
}
