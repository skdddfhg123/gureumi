package com.example.pleosapp.spike

import ai.pleos.playground.vehicle.Vehicle
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.example.pleosapp.R
import com.example.pleosapp.engine.ArrivalAloud
import com.example.pleosapp.engine.DayPart
import com.example.pleosapp.engine.DiaryTemplate
import com.example.pleosapp.engine.DriveEvent
import com.example.pleosapp.engine.DrivingGate
import com.example.pleosapp.engine.EngineConfig
import com.example.pleosapp.engine.FamilyRepository
import com.example.pleosapp.engine.KeepsakeRepository
import com.example.pleosapp.engine.FamilySpeech
import com.example.pleosapp.engine.Memories
import com.example.pleosapp.engine.PetRepository
import com.example.pleosapp.engine.SceneRules
import com.example.pleosapp.engine.SeatSlot
import com.example.pleosapp.engine.SkySignals
import com.example.pleosapp.engine.SpeechHold
import com.example.pleosapp.engine.SpokenDiary
import com.example.pleosapp.engine.ThermalGate
import com.example.pleosapp.engine.TripSounds
import com.example.pleosapp.engine.VehicleEngineBridge
import com.example.pleosapp.engine.VehicleKind
import com.example.pleosapp.ui.arrival.ArrivalCardActivity
import com.example.pleosapp.ui.home.HomeActivity
import java.util.Calendar

/**
 * 앱 화면이 없어도(다른 앱이 전면이어도) 차량 신호를 받아 정령을 키우는 포그라운드 서비스.
 * 부팅 수신기와 앱 실행 두 경로로 시작합니다. SDK 초기화는 콜드 부팅에서 18초까지 걸린 실측이 있어
 * 메인 스레드가 아닌 전용 스레드에서 합니다.
 */
class VehicleProbeService : Service() {

    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var speedProbe: SpeedProbe? = null

    /** 속도를 받고 있는지. 초기화 스레드에서만 만집니다. */
    private var speedRegistered = false
    private var voiceProbe: VoiceProbe? = null
    private var soundProbe: SoundProbe? = null
    private var bridge: VehicleEngineBridge? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private val handler = Handler(Looper.getMainLooper())
    private var initThread: HandlerThread? = null

    /** P 뒤 2초 대체 동작(TTS + 알림). 출발하면 취소해야 하므로 보관합니다. */
    private var arrivalFallback: Runnable? = null
    private var beats = 0

    // 아래 값은 메인 스레드에서만 만집니다. 여러 자리의 부탁과 찰칵을 한 문장으로 묶습니다.
    private val greetingSeats = LinkedHashSet<SeatSlot>()

    /** 방금 다시 탄 자리. 값은 소리 내어 반길지입니다. */
    private val returnedSeats = HashMap<SeatSlot, Boolean>()
    private val seatSpokenAt = HashMap<SeatSlot, Long>()
    private var greetingFlush: Runnable? = null
    private var gatherAnnounced = false
    private val askSeats = LinkedHashSet<SeatSlot>()
    private var askFlush: Runnable? = null

    /** 서비스가 직접 읽는 도착 일기(카드가 못 뜬 때). 정차 중 말이 그 낭독을 끊지 않게 봅니다. */
    private val ownReading = SpeechHold.OwnReading()

    // 도착 일기를 읽는 동안 미룬 말. 메인 스레드에서만 만집니다.
    private var heldLine: String? = null
    private var heldSinceMs = 0L
    private var holdPoll: Runnable? = null

    /** 초기화가 끝나기 전에 서비스가 끝나도 박동이 다시 걸리지 않게 합니다. */
    @Volatile
    private var destroyed = false

    private val heartbeat = object : Runnable {
        override fun run() {
            if (destroyed) return
            beats++
            bridge?.tick()
            ProbeState.log("HEARTBEAT #$beats")
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    /** 서비스가 되살아날 때 onStartCommand가 오지 않는 경로가 있어([onTaskRemoved]) 엔진은 여기서 띄웁니다. */
    override fun onCreate() {
        super.onCreate()
        val thread = HandlerThread("drive-init").also { it.start() }
        initThread = thread
        Handler(thread.looper).post(::initialize)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        // 홈은 속도 권한을 받은 뒤 서비스를 다시 시작합니다. 초기화와 같은 스레드에 줄 세워 초기화 뒤에 봅니다.
        initThread?.let { Handler(it.looper).post(::retrySpeed) }
        return START_STICKY
    }

    /**
     * 상주 알림을 올립니다. 되살아나는 길에서는 시스템이 막을 수 있어 감쌉니다. 막혀도 신호 받기는 이어 갑니다.
     * 서비스 종류를 넘기는 함수는 Android 10부터, 특수 용도 종류는 14부터 있습니다. Pleos는 14입니다.
     */
    private fun goForeground() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }.onFailure { ProbeState.log("FAIL foreground ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** 권한 없이 뜬 서비스는 속도 등록이 실패해 있습니다. 권한이 생겼으면 다시 등록합니다. */
    private fun retrySpeed() {
        if (speedRegistered) return
        val probe = speedProbe ?: return
        if (checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) return
        ProbeState.log("SpeedProbe retry after permission")
        speedRegistered = probe.register()
    }

    /**
     * 앱 화면이 앞일 때 프로세스가 죽으면 태스크가 함께 지워집니다. 시스템은 서비스를 되살리면서
     * onStartCommand 대신 이 함수만 부르고, 그사이 사라진 알림은 다시 올려야 합니다.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        ProbeState.log("VehicleProbeService task removed")
        goForeground()
    }

    private fun initialize() {
        val started = System.currentTimeMillis()
        PetRepository.init(this)
        FamilyRepository.init(this)
        KeepsakeRepository.init(this)
        val v = Vehicle(this).also { it.initialize() }
        vehicle = v
        val voice = VoiceProbe(this).also {
            voiceProbe = it
            it.onSpeaking = ownReading::onSpeaking
        }
        val sound = SoundProbe(this).also { soundProbe = it }
        val tripSounds = TripSounds(sound::play, ProbeState::log)
        val restored = PetRepository.state
        // 와이퍼는 검증 빌드만 권한을 선언합니다. 제출 빌드는 구독하지 않습니다.
        val wipers = checkSelfPermission(CAR_WINDOWS) == PackageManager.PERMISSION_GRANTED
        val engine = VehicleEngineBridge(v, restored.ailments, restored.restClock, FamilyRepository.state.away, readWipers = wipers) { event ->
            PetRepository.accept(event)
            // 자리와 시각이 남는 줄이라 제출 빌드에서는 컴파일 때 빠집니다.
            if (VerifyBuild.ON) ProbeState.log("EVT $event")
            onDriveEvent(event, voice)
            tripSounds.onEvent(event, bridge?.rearBelted() == true)
        }
        bridge = engine
        activeBridge = engine
        engine.register()
        watchCar(engine)
        watchThermal()
        if (!destroyed) handler.postDelayed(heartbeat, HEARTBEAT_MS)
        ProbeState.log("VehicleProbeService started in ${System.currentTimeMillis() - started}ms")
    }

    private fun onDriveEvent(event: DriveEvent, voice: VoiceProbe) {
        when (event) {
            // 트립 종료는 P단이므로 카드·음성이 안전합니다. 주차 중 짧은 움직임은 알리지 않습니다.
            is DriveEvent.TripEnded -> {
                handler.post { gatherAnnounced = false }
                if (event.summary.durationMs >= ARRIVAL_MIN_TRIP_MS) {
                    // 카드와 알림이 같은 일기를 보여 주도록 띄우기 전에 맡긴 말의 전달을 정합니다.
                    // 워셔액과 연료는 일기에 안내가 나오지 않으므로 전달을 미루지 않습니다.
                    // 탄 아기의 시각을 남겨 다음에 오래 안 탄 아기가 먼저 타게 합니다.
                    FamilyRepository.update {
                        it.settleArrival(event.summary, PetRepository.state.ailmentLine() != null).arrived(event.summary, event.atMs)
                    }
                    remember(event)
                    showArrivalCard(voice, ArrivalAloud.start(event.summary.rearStillBelted, partNow()))
                }
            }
            // 출발하면 주행 중 음성·알림이 나가지 않도록 대체 동작을 취소하고 낭독을 끊습니다.
            is DriveEvent.TripStarted -> {
                cancelArrivalFallback(voice)
                cancelGreeting()
            }
            is DriveEvent.RearBeltRequested -> queueAsk(event.seat, voice)
            // 정령이 있으면 자리별 인사(SeatsAwake)가 대신 말합니다.
            is DriveEvent.RearBeltBuckled ->
                if (event.parked && FamilyRepository.state.spirits.isEmpty()) say(voice, FamilySpeech.THANKS_DEFAULT)
            // 탈 아기를 먼저 앉혀야 앨범과 인사가 앉은 아기를 봅니다.
            is DriveEvent.SeatsAwake -> {
                onSeatsAwake(event, voice)
                remember(event)
            }
            is DriveEvent.SeatLeft -> {
                FamilyRepository.update { it.seatLeft(event.seat) }
                remember(event)
            }
            is DriveEvent.SeatReturned -> {
                FamilyRepository.update { it.seatReturned(event.seat) }
                remember(event)
                if (event.parked) handler.post { returnedSeats[event.seat] = event.greetsAloud }
            }
            is DriveEvent.RestTaken -> remember(event)
            else -> Unit
        }
    }

    /** 앨범과 선반은 사람이 정리하지 않아도 저절로 쌓입니다. */
    private fun remember(event: DriveEvent) {
        KeepsakeRepository.updateGifts { Memories.onEvent(it, event, FamilyRepository.state) }
    }

    /**
     * 정차 중 말. 도착 일기를 읽는 동안에는 끝날 때까지 미룹니다.
     * 읽지 않을 때는 지금처럼 부른 스레드에서 바로 말합니다. 그래야 출발 때의 stop이 이 말도 끊습니다.
     */
    private fun say(voice: VoiceProbe, line: String) {
        if (diaryReading()) handler.post { hold(voice, line) } else speakNow(voice, line)
    }

    private fun speakNow(voice: VoiceProbe, line: String) {
        ProbeState.log("SAY $line")
        voice.speak(line)
    }

    /** 카드가 읽거나 서비스가 직접 읽는 동안입니다. */
    private fun diaryReading() = ArrivalCardActivity.diaryReading || ownReading.reading(SystemClock.elapsedRealtime())

    /** 미룬 말은 하나만 둡니다. 새 말이 오면 바꿔서, 일기 끝에 지난 부탁과 인사를 몰아서 하지 않습니다. */
    private fun hold(voice: VoiceProbe, line: String) {
        if (heldLine == null) heldSinceMs = SystemClock.elapsedRealtime()
        heldLine = line
        ProbeState.log("HOLD $line")
        if (holdPoll == null) pollHeld(voice)
    }

    private fun pollHeld(voice: VoiceProbe) {
        holdPoll = Runnable {
            holdPoll = null
            val line = heldLine ?: return@Runnable
            val waited = SystemClock.elapsedRealtime() - heldSinceMs
            when (SpeechHold.next(diaryReading(), PetRepository.state.together.inTrip, waited)) {
                SpeechHold.Next.WAIT -> pollHeld(voice)
                SpeechHold.Next.DROP -> {
                    heldLine = null
                    ProbeState.log("HOLD dropped: trip started")
                }
                SpeechHold.Next.SPEAK -> {
                    heldLine = null
                    speakNow(voice, line)
                }
            }
        }.also { handler.postDelayed(it, SpeechHold.POLL_MS) }
    }

    /** 뒷문 두 개가 함께 닫히면 부탁이 동시에 나옵니다. 음성이 겹치지 않게 한 문장으로 묶습니다. */
    private fun queueAsk(seat: SeatSlot, voice: VoiceProbe) {
        handler.post {
            askSeats += seat
            askFlush?.let(handler::removeCallbacks)
            askFlush = Runnable { flushAsk(voice) }.also { handler.postDelayed(it, ASK_COALESCE_MS) }
        }
    }

    private fun flushAsk(voice: VoiceProbe) {
        askFlush = null
        val names = FamilyRepository.state.companions(askSeats).map { it.preset.displayName }
        askSeats.clear()
        say(voice, if (names.isEmpty()) FamilySpeech.ASK_DEFAULT else FamilySpeech.askBelt(names))
    }

    private fun onSeatsAwake(event: DriveEvent.SeatsAwake, voice: VoiceProbe) {
        FamilyRepository.onSeats(event)
        if (!event.parked || event.newly.isEmpty() || FamilyRepository.state.spirits.isEmpty()) return
        handler.post {
            greetingSeats += event.newly
            greetingFlush?.let(handler::removeCallbacks)
            greetingFlush = Runnable { flushGreeting(voice, event.waiting) }
                .also { handler.postDelayed(it, GREETING_COALESCE_MS) }
        }
    }

    /** 잠깐 사이의 체결을 한 문장으로 말합니다. 같은 자리에서 반복해도 말을 되풀이하지 않습니다. */
    private fun flushGreeting(voice: VoiceProbe, waiting: Boolean) {
        greetingFlush = null
        val now = System.currentTimeMillis()
        // 잠깐 내렸다 다시 탄 자리는 조용히 맞습니다. 오래 떨어졌던 자리만 소리 내어 반깁니다.
        val fresh = greetingSeats.filterTo(LinkedHashSet()) {
            returnedSeats[it] ?: (now - (seatSpokenAt[it] ?: 0L) >= SEAT_VOICE_GAP_MS)
        }
        val welcomed = fresh.filterTo(LinkedHashSet()) { it in returnedSeats }
        greetingSeats.clear()
        returnedSeats.clear()
        if (fresh.isEmpty()) return
        fresh.forEach { seatSpokenAt[it] = now }
        val family = FamilyRepository.state
        val gathered = !gatherAnnounced && !waiting && family.companions(FamilyRepository.awakeSeats).size >= 2
        val names = family.companions(fresh).map { it.preset.displayName }
        val welcomedNames = family.companions(welcomed).map { it.preset.displayName }
        val line = when {
            welcomedNames.isNotEmpty() -> FamilySpeech.welcomeBack(welcomedNames)
            gathered -> FamilySpeech.GATHERED.also { gatherAnnounced = true }
            names.isEmpty() -> FamilySpeech.THANKS_DEFAULT
            else -> FamilySpeech.woke(names)
        }
        say(voice, line)
    }

    private fun cancelGreeting() {
        handler.post {
            greetingFlush?.let(handler::removeCallbacks)
            greetingFlush = null
            greetingSeats.clear()
            returnedSeats.clear()
            askFlush?.let(handler::removeCallbacks)
            askFlush = null
            askSeats.clear()
            holdPoll?.let(handler::removeCallbacks)
            holdPoll = null
            heldLine = null
        }
    }

    /** 주행 제한, 속도, 차종을 따로 감쌉니다. 주행 제한이 실패해도 속도는 받습니다. */
    private fun watchCar(engine: VehicleEngineBridge) {
        val c = runCatching { Car.createCar(this) }
            .onFailure { ProbeState.log("FAIL car ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull() ?: return
        car = c
        runCatching {
            val manager = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            val report = { r: CarUxRestrictions ->
                ProbeState.signal("uxRestricted", "${r.isRequiresDistractionOptimization} active=0x${Integer.toHexString(r.activeRestrictions)}")
                DrivingGate.update(r.isRequiresDistractionOptimization)
            }
            report(manager.currentCarUxRestrictions)
            manager.registerListener { report(it) }
        }.onFailure { ProbeState.log("FAIL uxRestrictions ${it.javaClass.simpleName}: ${it.message}") }
        runCatching { speedProbe = SpeedProbe(c, engine::onSpeed).also { speedRegistered = it.register() } }
            .onFailure { ProbeState.log("FAIL speed ${it.javaClass.simpleName}: ${it.message}") }
        readVehicleKind(c)
    }

    /** 카드와 같은 때입니다. 검증 빌드의 장면 미리 보기(`scene time=night`)도 따릅니다. */
    private fun partNow(): DayPart {
        val now = Calendar.getInstance()
        val part = SceneRules.dayPart(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MONTH) + 1)
        return SkySignals.scene(part, System.currentTimeMillis()).part
    }

    /** 차가 뜨거우면 화면이 움직임을 늦춥니다. 등록하면 지금 상태가 바로 한 번 옵니다. 열 상태 API는 Android 10부터 있습니다. */
    private fun watchThermal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            ProbeState.signal("thermal", status)
            ThermalGate.update(status)
        }
        thermalListener = listener
        getSystemService(PowerManager::class.java).addThermalStatusListener(listener)
    }

    /** 차종은 바뀌지 않아 시작할 때 한 번만 읽습니다. Vehicle SDK에 연료 종류 API가 없어 AAOS 표준 속성을 씁니다. */
    private fun readVehicleKind(c: Car) {
        runCatching {
            val manager = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            val fuelTypes = manager.getProperty<Array<Int>>(VehiclePropertyIds.INFO_FUEL_TYPE, GLOBAL_AREA).value.toList()
            ProbeState.signal("fuelTypes", fuelTypes)
            KeepsakeRepository.onVehicleKind(VehicleKind.of(fuelTypes))
        }.onFailure { ProbeState.log("FAIL fuelType ${it.javaClass.simpleName}: ${it.message}") }
        ProbeState.signal("vehicleKind", KeepsakeRepository.care.kind)
    }

    private fun cancelArrivalFallback(voice: VoiceProbe) {
        arrivalFallback?.let { handler.removeCallbacks(it) }
        arrivalFallback = null
        ownReading.reset()
        voice.stop()
    }

    /**
     * 카드가 못 떠서 서비스가 직접 읽습니다. 다 읽을 때까지 정차 중 말을 미룹니다.
     * 읽은 트립을 적어 두어, 막혔던 카드가 나중에 떠도 같은 일기를 다시 읽지 않게 합니다.
     */
    private fun readDiaryAloud(voice: VoiceProbe, text: String, tripId: Long?) {
        tripId?.let { SpokenDiary.mark(this, it) }
        ownReading.asked(SystemClock.elapsedRealtime())
        voice.speak(text)
    }

    /**
     * 도착 카드를 띄웁니다. 앱이 보이는 상태가 아니면 AAOS 14가 서비스의 액티비티 시작을 막으므로
     * (logcat `BAL_BLOCK`), 잠시 뒤에도 카드가 안 떴으면 일기를 TTS로 읽고 알림 센터에 "도착 일기" 알림을 남깁니다.
     * 알림을 누르면 카드가 열립니다(사용자 조작이라 허용됨).
     * 읽을 때는 [ArrivalAloud]가 정합니다. 뒷자리에 아이가 잠들어 있을 수 있으면 안전벨트가 풀릴 때까지 기다리고, 밤이면 읽지 않습니다.
     */
    private fun showArrivalCard(voice: VoiceProbe, aloud: ArrivalAloud.Start) {
        val quiet = aloud != ArrivalAloud.Start.NOW
        val intent = Intent(this, ArrivalCardActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (quiet) intent.putExtra(ArrivalCardActivity.EXTRA_WAIT_REAR, true)
        val requestedAt = System.currentTimeMillis()
        val quietUntil = SystemClock.elapsedRealtime() + EngineConfig.REAR_QUIET_WAIT_MS
        runCatching { startActivity(intent) }.onFailure { ProbeState.log("FAIL arrival card ${it.javaClass.simpleName}: ${it.message}") }
        arrivalFallback?.let { handler.removeCallbacks(it) }
        arrivalFallback = Runnable {
            arrivalFallback = null
            if (ArrivalCardActivity.shownAtMs < requestedAt) {
                ProbeState.log("arrival card not shown; speaking + notification")
                val state = PetRepository.state
                val trip = state.lastTrip
                val lines = trip?.let { DiaryTemplate.lines(it, state, FamilyRepository.state, KeepsakeRepository.gifts) }
                    ?: listOf(state.arrivalLine())
                val text = lines.joinToString(" ")
                when (aloud) {
                    ArrivalAloud.Start.NOW -> readDiaryAloud(voice, text, trip?.tripId)
                    ArrivalAloud.Start.AFTER_REAR -> speakWhenRearFree(voice, text, quietUntil, trip?.tripId)
                    ArrivalAloud.Start.NEVER -> ProbeState.log("arrival stays quiet: night with rear belted")
                }
                postArrivalNotification(lines, intent, quiet)
            }
        }.also { handler.postDelayed(it, ARRIVAL_CARD_GRACE_MS) }
    }

    /** 뒷자리 안전벨트가 모두 풀리면 그때 읽습니다. 기다림이 지나면 자는 아이로 보고 끝까지 읽지 않습니다. */
    private fun speakWhenRearFree(voice: VoiceProbe, text: String, untilMs: Long, tripId: Long?) {
        arrivalFallback = Runnable {
            arrivalFallback = null
            when {
                // 출발이 대체 동작을 취소하는 순간과 겹쳐도 주행 중에는 읽지 않습니다.
                PetRepository.state.together.inTrip -> Unit
                bridge?.rearBelted() != true -> {
                    ProbeState.log("arrival speaking after rear belts released")
                    readDiaryAloud(voice, text, tripId)
                }
                SystemClock.elapsedRealtime() >= untilMs -> ProbeState.log("arrival stays quiet")
                else -> speakWhenRearFree(voice, text, untilMs, tripId)
            }
        }.also { handler.postDelayed(it, REAR_POLL_MS) }
    }

    /**
     * 정차 중에만 오는, 운전과 직접 관련된 알림입니다. 소리는 내지 않습니다(IMPORTANCE_LOW, 헤드업 없음).
     * 서비스가 일기를 읽는 때는 '띵'이 낭독과 겹치고, 읽지 않는 때는 뒷자리 아이가 자고 있을 수 있습니다.
     */
    private fun postArrivalNotification(lines: List<String>, cardIntent: Intent, quiet: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        // 앱 안의 "오늘의 일기"와 맞춰 "도착 일기"라 부릅니다. 이미 있는 채널은 사용자가 손대지 않았으면 중요도가 낮아집니다.
        val channel = if (quiet) {
            NotificationChannel(CHANNEL_ARRIVAL_QUIET, "도착 일기(조용히)", NotificationManager.IMPORTANCE_LOW)
        } else {
            NotificationChannel(CHANNEL_ARRIVAL, "도착 일기", NotificationManager.IMPORTANCE_LOW)
        }
        nm.createNotificationChannel(channel)
        val fromNotification = Intent(cardIntent).putExtra(ArrivalCardActivity.EXTRA_ALREADY_SPOKEN, true)
        val open = PendingIntent.getActivity(this, 0, fromNotification, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, channel.id)
            .setSmallIcon(R.drawable.ic_gureumi)
            .setContentTitle("도착! 구름이의 일기")
            .setContentText(lines.first())
            .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(ArrivalCardActivity.NOTIFICATION_ID, notification) }
            .onFailure { ProbeState.log("FAIL arrival notification ${it.message}") }
        ProbeState.log("arrival notification posted")
    }

    /** 상주 알림. 누르면 앱 아이콘을 누른 것처럼 홈으로 갑니다. */
    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "함께 타는 구름이", NotificationManager.IMPORTANCE_MIN))
        val home = Intent(this, HomeActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val open = PendingIntent.getActivity(this, REQUEST_HOME, home, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("구름이가 함께 타고 있어요")
            .setSmallIcon(R.drawable.ic_gureumi)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(heartbeat)
        arrivalFallback?.let { handler.removeCallbacks(it) }
        greetingFlush?.let { handler.removeCallbacks(it) }
        askFlush?.let { handler.removeCallbacks(it) }
        holdPoll?.let { handler.removeCallbacks(it) }
        // 초기화가 끝난 뒤에 정리되도록 같은 스레드에 줄 세웁니다.
        val thread = initThread
        if (thread != null) {
            Handler(thread.looper).post {
                // 초기화 도중에 끝났으면 초기화가 박동을 다시 걸었을 수 있습니다. 초기화 뒤인 여기서 한 번 더 지웁니다.
                handler.removeCallbacks(heartbeat)
                activeBridge = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    thermalListener?.let { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) }
                }
                bridge?.release()
                speedProbe?.unregister()
                voiceProbe?.release()
                soundProbe?.release()
                vehicle?.release()
                car?.disconnect()
                ProbeState.log("VehicleProbeService destroyed")
            }
            thread.quitSafely()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** 에뮬레이터에서 값이 안 바뀌는 벨트 신호를 debug 콘솔이 넣을 때 씁니다. */
        @Volatile
        var activeBridge: VehicleEngineBridge? = null
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, VehicleProbeService::class.java))
        }

        private const val CHANNEL_ID = "probe"
        private const val CHANNEL_ARRIVAL = "arrival"
        private const val CHANNEL_ARRIVAL_QUIET = "arrival_quiet"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_HOME = 1
        private const val CAR_WINDOWS = "pleos.car.permission.CAR_WINDOWS"
        private const val HEARTBEAT_MS = 15_000L
        private const val ARRIVAL_CARD_GRACE_MS = 2_000L
        private const val REAR_POLL_MS = 500L
        private const val ARRIVAL_MIN_TRIP_MS = 8_000L
        private const val GREETING_COALESCE_MS = 1_500L
        private const val ASK_COALESCE_MS = 300L
        private const val SEAT_VOICE_GAP_MS = 60_000L
        private const val GLOBAL_AREA = 0
    }
}
