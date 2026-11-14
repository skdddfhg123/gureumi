package com.example.pleosapp.ui.recall

import android.app.Activity
import android.os.Bundle
import com.example.pleosapp.engine.DriveEvent
import com.example.pleosapp.engine.PetRepository
import com.example.pleosapp.engine.RecallId

/**
 * 시간 층 시험 입력(debug 빌드 전용). 화면 없이 명령만 처리하고 닫힙니다.
 * `--es recall day100`: 그림책을 바로 엽니다. `--ez invited true`면 초대로 연 것과 같습니다.
 * `--ei month 4`: 창밖 계절을 그 달로 봅니다. 0이면 실제 달입니다.
 * `--ez off true`, `--ez hide true`: 회상 끄기와 날짜 숨기기. `--ez reset true`: 설정과 본 것을 지웁니다.
 * `--ez depart true`: 출발을 넣습니다. 그림책이 바로 닫혀야 합니다. 원장에 남으므로 시험 상태에서만 씁니다.
 */
class TimeConsoleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PetRepository.init(this)
        val prefs = RecallPrefs(this)
        val extras = intent.extras
        if (extras?.getBoolean("reset") == true) prefs.clear()
        if (extras?.containsKey("off") == true) prefs.off = extras.getBoolean("off")
        if (extras?.containsKey("hide") == true) prefs.hideDates = extras.getBoolean("hide")
        if (extras?.containsKey("month") == true) {
            prefs.previewMonth = extras.getInt("month")
            SeasonDrift.previewMonth = extras.getInt("month")
        }
        if (extras?.getBoolean("depart") == true) {
            val now = System.currentTimeMillis()
            PetRepository.accept(DriveEvent.TripStarted(now, now))
        }
        intent.getStringExtra("recall")?.let(RecallId::parse)?.let { id ->
            startActivity(RecallActivity.intent(this, id, invited = extras?.getBoolean("invited") == true))
        }
        finish()
    }
}
