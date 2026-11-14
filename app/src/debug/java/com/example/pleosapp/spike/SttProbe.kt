package com.example.pleosapp.spike

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener

/** 검증용 STT(ON_DEVICE)와 외부 서버 접근 확인. 제품 빌드에는 들어가지 않습니다. */
class SttProbe(context: Context) {

    private val appContext = context.applicationContext
    private var stt: SpeechToText? = null

    /** micMode=true면 차량 마이크로 인식, false면 pcmFile(16kHz/16bit/mono)을 sendAudio로 주입. */
    fun recognize(micMode: Boolean, pcmFile: File?) {
        stt?.let { it.removeListener(resultListener); it.release() }
        val engine = SpeechToText(appContext, Mode.ON_DEVICE)
        stt = engine
        engine.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                ProbeState.log("STT init ok (mic=$micMode)")
                engine.addListener(resultListener)
                engine.setLocale(Locale.KOREA)
                if (micMode) {
                    engine.request()
                } else {
                    Thread { streamPcm(engine, pcmFile) }.start()
                }
            }

            override fun onFailure(errorType: ErrorType) = ProbeState.log("STT init fail $errorType")
        })
    }

    private fun streamPcm(engine: SpeechToText, file: File?) {
        if (file == null || !file.exists()) {
            ProbeState.log("STT pcm file missing: $file")
            return
        }
        val bytes = file.readBytes()
        ProbeState.log("STT sending ${bytes.size} bytes from ${file.name}")
        bytes.asList().chunked(BYTES_PER_100_MS).forEach {
            engine.sendAudio(it.toByteArray())
            Thread.sleep(100L)
        }
        engine.completeAudioSend()
        ProbeState.log("STT audio send complete")
    }

    private val resultListener = object : ResultListener {
        override fun onReady() = ProbeState.log("STT ready")
        override fun onStartedRecognition() = ProbeState.log("STT started")
        override fun onUpdated(stt: String, completed: Boolean) = ProbeState.log("STT text(final=$completed): $stt")
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() = ProbeState.log("STT ended")
        override fun onError(errorType: ErrorType) = ProbeState.log("STT error $errorType")
    }

    /** 외부 LLM API까지 HTTPS가 나가는지만 확인(키 없이 호출하므로 401/405 응답이면 도달 성공). */
    fun checkNetwork() {
        Thread {
            for (url in listOf("https://api.anthropic.com/v1/messages", "https://www.google.com/generate_204")) {
                runCatching {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 5_000
                    val code = conn.responseCode
                    conn.disconnect()
                    ProbeState.log("NET $url -> HTTP $code")
                }.onFailure { ProbeState.log("NET $url -> FAIL ${it.javaClass.simpleName}: ${it.message}") }
            }
        }.start()
    }

    fun release() {
        stt?.let { it.removeListener(resultListener); it.release() }
    }

    private companion object {
        const val BYTES_PER_100_MS = 3_200
    }
}
