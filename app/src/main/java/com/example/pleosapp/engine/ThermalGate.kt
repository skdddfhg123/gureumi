package com.example.pleosapp.engine

/**
 * 차가 뜨거운지 화면에 알립니다. 서비스가 플랫폼의 열 상태를 넣고, 화면은 [slowdown]배 느리게 움직입니다.
 * 값이 정수 하나라 어느 스레드에서 읽어도 됩니다.
 */
object ThermalGate {
    /** `PowerManager.THERMAL_STATUS_SEVERE`. 플랫폼이 앱에 일을 줄이라고 하는 단계입니다. */
    private const val SEVERE = 3
    private const val HOT_SLOWDOWN = 3

    @Volatile
    var slowdown = 1
        private set

    fun update(status: Int) {
        slowdown = if (status >= SEVERE) HOT_SLOWDOWN else 1
    }
}
