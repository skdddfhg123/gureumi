package com.example.pleosapp.spike

import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 기술 검증(spike)용 공용 상태: 마지막으로 받은 차량 신호 값과 로그를 보관합니다.
 * 검증 빌드의 로그는 logcat 태그 "Spike"로도 출력되어 adb로 검증할 수 있습니다.
 * 제출 빌드는 'FAIL '로 시작하는 줄만 값을 빼고 남깁니다. 신호 값, 자리, 말한 문장이 logcat에 남지 않습니다.
 */
object ProbeState {
    /** 검증 도구는 "Spike" 태그를 읽습니다. 제출 빌드는 앱 이름을 씁니다. */
    val TAG = if (VerifyBuild.ON) "Spike" else "Gureumi"

    private const val FAIL = "FAIL "

    val signals = ConcurrentHashMap<String, String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** 여러 스레드가 함께 씁니다. SimpleDateFormat과 달리 스레드에 안전합니다. */
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)

    fun signal(key: String, value: Any?) {
        signals[key] = value.toString()
        if (VerifyBuild.ON) log("SIG $key=$value")
    }

    fun log(message: String) {
        if (!VerifyBuild.ON) {
            // "FAIL speed SecurityException: 자세한 말"에서 ':' 뒤를 뺍니다.
            if (message.startsWith(FAIL)) Log.w(TAG, message.substringBefore(": "))
            return
        }
        Log.i(TAG, message)
        if (listeners.isEmpty()) return
        val line = "${LocalTime.now().format(timeFormat)} $message"
        listeners.forEach { it(line) }
    }

    fun addListener(listener: (String) -> Unit) = listeners.add(listener)

    fun removeListener(listener: (String) -> Unit) = listeners.remove(listener)
}
