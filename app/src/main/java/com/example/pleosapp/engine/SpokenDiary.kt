package com.example.pleosapp.engine

import android.content.Context

/**
 * 도착 일기를 소리 내어 읽은 트립.
 * 다른 앱이 앞이면 카드가 막혀 서비스가 대신 읽습니다. 막힌 카드가 남았다가 앱으로 돌아올 때 뜨면 같은 일기를 또 읽었습니다.
 * 알림으로 연 카드는 인텐트로 알았지만(EXTRA_ALREADY_SPOKEN) 이 길은 몰랐습니다. 카드는 이 기록을 보고 다시 읽지 않습니다.
 * 마지막 트립 하나만 둡니다. 프로세스가 다시 떠도 남도록 SharedPreferences에 적습니다.
 */
object SpokenDiary {
    private const val STORE = "spoken_diary"
    private const val KEY_TRIP = "trip"
    private const val NONE = Long.MIN_VALUE

    /** 그 트립의 일기를 읽기 시작했습니다. 서비스는 대신 읽을 때, 카드는 직접 읽을 때 부릅니다. */
    fun mark(context: Context, tripId: Long) {
        prefs(context).edit().putLong(KEY_TRIP, tripId).apply()
    }

    fun isSpoken(context: Context, tripId: Long): Boolean = prefs(context).getLong(KEY_TRIP, NONE) == tripId

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
}
