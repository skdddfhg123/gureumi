package com.example.pleosapp.ui.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import com.example.pleosapp.spike.ProbeState
import java.io.File
import java.util.Locale

/**
 * Pleos 온디바이스 음성 인식으로 한 번 듣습니다. 엔진은 처음 누를 때 하나 만들어 화면이 닫힐 때까지 씁니다.
 * 앞 엔진을 지우기 전에 새로 만들면 두 번에 한 번 결과가 없었습니다(docs/reference/pleos-sdk.md 3장).
 * 시험용: 앱 폴더에 [ONCE_FILE]이 있으면 마이크 대신 그 소리(16 kHz, 16비트, 모노)를 한 번 보내고 지웁니다.
 */
class SttEars(context: Context) : NameEars {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var engine: SpeechToText? = null
    private var ready = false
    private var listener: NameEars.Listener? = null

    /** 이번에 연 마이크의 결과만 받습니다. 앞에서 멈춘 인식의 늦은 끝 알림이 새 듣기를 끝내지 않게 합니다. */
    private var live = false

    @Volatile
    private var released = false

    override fun listen(listener: NameEars.Listener) {
        this.listener = listener
        val current = engine ?: return open()
        // 엔진을 여는 중이면 열린 뒤에 시작합니다.
        if (ready) start(current)
    }

    override fun stop() {
        live = false
        listener = null
        if (ready) runCatching { engine?.stop() }.onFailure { ProbeState.log("Ears stop fail ${it.message}") }
    }

    override fun release() {
        released = true
        stop()
        val current = engine ?: return
        engine = null
        // 여는 중이면 열린 뒤에 닫습니다.
        if (!ready) return
        ready = false
        current.removeListener(results)
        current.release()
    }

    private fun open() {
        val made = SpeechToText(appContext, Mode.ON_DEVICE)
        engine = made
        made.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                main.post {
                    if (released) {
                        made.release()
                        return@post
                    }
                    made.addListener(results)
                    made.setLocale(Locale.KOREA)
                    ready = true
                    if (listener != null) start(made)
                }
            }

            override fun onFailure(errorType: ErrorType) {
                ProbeState.log("Ears init fail $errorType")
                main.post {
                    engine = null
                    finish(failed = true)
                }
            }
        })
    }

    private fun start(current: SpeechToText) {
        val once = File(appContext.filesDir, ONCE_FILE)
        if (!once.exists()) {
            runCatching { current.request() }.onFailure {
                ProbeState.log("Ears request fail ${it.message}")
                finish(failed = true)
            }
            return
        }
        val sound = once.readBytes()
        once.delete()
        ProbeState.log("Ears sending ${sound.size} bytes from ${once.name}")
        live = true
        listener?.onReady()
        Thread {
            var at = 0
            while (at < sound.size && !released) {
                val end = minOf(at + BYTES_PER_100_MS, sound.size)
                current.sendAudio(sound.copyOfRange(at, end))
                at = end
                Thread.sleep(SEND_GAP_MS)
            }
            if (!released) current.completeAudioSend()
        }.start()
    }

    private fun finish(failed: Boolean) {
        live = false
        val done = listener ?: return
        listener = null
        done.onDone(failed)
    }

    private val results = object : ResultListener {
        override fun onReady() {
            main.post {
                if (listener == null) return@post
                live = true
                listener?.onReady()
            }
        }

        override fun onStartedRecognition() = Unit

        override fun onUpdated(stt: String, completed: Boolean) {
            ProbeState.log("Ears text(final=$completed): $stt")
            main.post { if (live) listener?.onHeard(stt, completed) }
        }

        override fun onUpdatedEpdData(on: Long, off: Long) = Unit

        override fun onUpdatedRms(rms: Double) = Unit

        override fun onEndedRecognition() {
            main.post { if (live) finish(failed = false) }
        }

        override fun onError(errorType: ErrorType) {
            ProbeState.log("Ears error $errorType")
            main.post { finish(failed = true) }
        }
    }

    private companion object {
        const val ONCE_FILE = "ears_once.pcm"
        const val BYTES_PER_100_MS = 3_200
        const val SEND_GAP_MS = 100L
    }
}
