package com.example.pleosapp.spike

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.example.pleosapp.engine.SpokenNumbers
import java.util.Locale
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener

/**
 * 구름이 목소리(Pleos TTS SDK, ON_DEVICE).
 * 말하기 직전에 오디오 포커스를 받고, 끝나면 돌려줍니다. 통화나 음성 명령 중이면 포커스가 거절되어 말하지 않습니다.
 * 달리는 중에도 말하지 않습니다. 말하지 못해도 화면과 알림의 글은 그대로입니다.
 */
class VoiceProbe(context: Context) {

    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null

    /** TTS가 말하는 동안 true. 정령의 입 애니메이션용(SDK 스레드에서 호출됨). 말하지 않고 건너뛸 때도 false가 한 번 옵니다. */
    @Volatile
    var onSpeaking: ((Boolean) -> Unit)? = null

    @Volatile
    private var released = false

    /**
     * SDK는 TTS를 USAGE_MEDIA로 냅니다. 포커스도 같은 쓰임으로 받아야 차가 통화·음성 명령 중에 거절합니다(AAOS 포커스 표).
     * 음악은 잠시 작아집니다. 전화벨처럼 다른 소리가 포커스를 가져가면 낭독을 멈춥니다.
     */
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                ProbeState.log("TTS focus lost $change")
                stop()
            }
        }, handler)
        .build()

    /** 끝 알림이 안 와도 포커스를 붙들고 있지 않습니다. 다른 화면의 TTS가 끼어들면 끝 알림이 오지 않습니다. */
    private val giveBack = Runnable { abandon() }

    private val events = object : EventListener {
        override fun onReady() = ProbeState.log("TTS ready")

        override fun onStart() {
            ProbeState.log("TTS start")
            // 초기화와 첫 소리 사이에 출발하거나 전화가 오면 여기서 멈춥니다.
            val reason = blocked()
            if (reason != null) {
                ProbeState.log("TTS cut at start: $reason")
                stop()
                return
            }
            onSpeaking?.invoke(true)
        }

        override fun onUpdatedRms(rms: Double) = Unit

        override fun onDone() {
            ProbeState.log("TTS done")
            finished()
        }

        override fun onStop() {
            ProbeState.log("TTS stop")
            finished()
        }

        override fun onError(errorType: TtsError) {
            ProbeState.log("TTS error $errorType")
            finished()
        }
    }

    /** [line]은 화면 글 그대로 받습니다. 숫자와 줄바꿈은 읽기 좋게 바꿔 읽습니다. */
    fun speak(line: String) {
        if (released) return
        val text = SpokenNumbers.forSpeech(line)
        val existing = tts
        if (existing != null) {
            say(existing, text)
            return
        }
        // 권한이 없으면 SDK가 SecurityException을 던집니다. 그때는 글로만 보입니다.
        if (appContext.checkSelfPermission(TTS_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            ProbeState.log("FAIL tts permission")
            onSpeaking?.invoke(false)
            return
        }
        val engine = runCatching { TextToSpeech(appContext, TtsMode.ON_DEVICE) }
            .onFailure { ProbeState.log("FAIL tts create ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
        if (engine == null) {
            onSpeaking?.invoke(false)
            return
        }
        tts = engine
        runCatching { engine.initialize(initListener(engine, text)) }.onFailure {
            ProbeState.log("FAIL tts init ${it.javaClass.simpleName}: ${it.message}")
            tts = null
            onSpeaking?.invoke(false)
        }
    }

    private fun initListener(engine: TextToSpeech, text: String) = object : TtsInitListener {
        override fun onSuccess() {
            // 초기화가 끝나기 전에 release된 경우(카드가 바로 닫힘) 늦게 낭독을 시작하지 않습니다.
            if (released) {
                runCatching { engine.release() }
                return
            }
            ProbeState.log("TTS init ok")
            val ready = runCatching {
                engine.addEventListener(events)
                engine.setLocale(Locale.KOREA)
            }.onFailure { ProbeState.log("FAIL tts setup ${it.javaClass.simpleName}: ${it.message}") }.isSuccess
            if (ready) say(engine, text) else onSpeaking?.invoke(false)
        }

        override fun onFailure(errorType: TtsError) {
            ProbeState.log("FAIL tts init $errorType")
            tts = null // 실패한 엔진을 들고 있으면 이후 speak가 조용히 무시됩니다.
            onSpeaking?.invoke(false)
        }
    }

    /** 말하기 직전에 다시 봅니다. 초기화에 1초 남짓 걸려 그사이 출발하거나 전화가 올 수 있습니다. */
    private fun say(engine: TextToSpeech, text: String) {
        val reason = blocked()
        if (reason != null) {
            ProbeState.log("TTS skipped: $reason")
            onSpeaking?.invoke(false)
            return
        }
        handler.removeCallbacks(giveBack)
        handler.postDelayed(giveBack, holdFor(text))
        runCatching { engine.speak(text) }.onFailure {
            ProbeState.log("FAIL tts speak ${it.javaClass.simpleName}: ${it.message}")
            finished()
        }
    }

    /** 말하면 안 되는 까닭. 없으면 포커스를 받은 것입니다. 이미 받고 있으면 다시 받아도 그대로입니다. */
    private fun blocked(): String? = when {
        released -> "released"
        VehicleProbeService.activeBridge?.driving() == true -> "driving"
        // 통화 중에는 차가 알림 소리를 함께 낼 수 있어 포커스만으로는 막히지 않을 수 있습니다.
        audio.mode != AudioManager.MODE_NORMAL -> "audio mode ${audio.mode}"
        audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "focus denied"
        else -> null
    }

    private fun finished() {
        abandon()
        onSpeaking?.invoke(false)
    }

    private fun abandon() {
        handler.removeCallbacks(giveBack)
        audio.abandonAudioFocusRequest(focus)
    }

    /** 진행 중인 낭독을 멈춥니다. 출발(D)하면 서비스와 카드가 호출해 주행 중 음성을 끊습니다. */
    fun stop() {
        runCatching { tts?.stop() }.onFailure { ProbeState.log("FAIL tts stop ${it.javaClass.simpleName}: ${it.message}") }
        abandon()
    }

    fun release() {
        released = true
        stop()
        runCatching { tts?.release() }.onFailure { ProbeState.log("FAIL tts release ${it.javaClass.simpleName}: ${it.message}") }
    }

    private companion object {
        const val TTS_PERMISSION = "pleos.car.permission.TTS_SERVICE"

        /** 글자당 0.25초에 3초를 더합니다. 한국어 TTS는 이보다 빨리 읽습니다. 길어도 40초면 돌려줍니다. */
        const val HOLD_BASE_MS = 3_000L
        const val HOLD_PER_CHAR_MS = 250L
        const val HOLD_MAX_MS = 40_000L

        fun holdFor(text: String): Long = (HOLD_BASE_MS + text.length * HOLD_PER_CHAR_MS).coerceAtMost(HOLD_MAX_MS)
    }
}
