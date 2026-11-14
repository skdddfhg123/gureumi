package com.example.pleosapp.engine

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 지금 화면을 멈춰야 하는지. 서비스가 차의 주행 제한 신호를 넣고 화면이 읽습니다.
 * 듣는 함수는 서비스 스레드에서 불리므로 화면은 메인 스레드로 넘겨야 합니다.
 */
object DrivingGate {
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Volatile
    var restricted = false
        private set

    fun update(next: Boolean) {
        if (next == restricted) return
        restricted = next
        listeners.forEach { it(next) }
    }

    fun addListener(listener: (Boolean) -> Unit) = listeners.add(listener)

    fun removeListener(listener: (Boolean) -> Unit) = listeners.remove(listener)
}
