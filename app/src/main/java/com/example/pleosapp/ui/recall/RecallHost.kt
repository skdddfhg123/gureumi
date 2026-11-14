package com.example.pleosapp.ui.recall

import android.app.Activity
import com.example.pleosapp.engine.FamilyRepository
import com.example.pleosapp.engine.KeepsakeRepository
import com.example.pleosapp.engine.PetRepository
import com.example.pleosapp.engine.RecallFacts
import com.example.pleosapp.engine.RecallId
import com.example.pleosapp.engine.TimeTogether

/** 화면이 회상에 말을 거는 창구. 홈과 도착 화면이 같은 규칙으로 초대합니다. */
class RecallHost(private val activity: Activity) {

    private val prefs = RecallPrefs(activity)

    private val now: Long get() = System.currentTimeMillis()

    /** 지금 권할 그림책. 읽기 전의 빈 원장으로 판단하지 않도록 파일을 다 읽은 뒤에만 답합니다. */
    fun waiting(): RecallId? = if (KeepsakeRepository.ready) prefs.waiting(PetRepository.state.together, now) else null

    fun inviteLine(): String? = waiting()?.let { TimeTogether.inviteLine(it, PetRepository.state.together, now) }

    /** 도착 일기에 붙는 한 줄. */
    fun arrivalLine(): String? = TimeTogether.arrivalLine(PetRepository.state.together, prefs.settings, now)

    /** 빠른 두 번 누름이 그림책을 두 번 열지 않게 합니다. 홈 입구는 자기 막이도 있습니다. */
    private val gate = OpenGate()

    /**
     * 긴 원장을 읽는 중이면 열지 않습니다. 그림책이 첫 화면을 붙잡지 않게 합니다.
     * 닫히는 중인 화면(도착 카드가 그림책을 열고 닫힘)과 방금 연 뒤의 두 번째 손짓도 열지 않습니다.
     */
    fun open(id: RecallId, invited: Boolean = false) {
        if (!KeepsakeRepository.ready || activity.isFinishing || !gate.pass()) return
        activity.startActivity(RecallActivity.intent(activity, id, invited))
    }

    /** 초대를 받아 엽니다. 권할 책이 없으면 false입니다. */
    fun openWaiting(): Boolean {
        val id = waiting() ?: return false
        open(id, invited = true)
        return true
    }

    /** 방금 다 읽은 고비의 사진. 홈이 흔들어 보여 줍니다. */
    fun takeHung(): RecallId? = prefs.takeHung()

    /** 오늘 꺼낼 한 줄 회상. 하루 한 번입니다. 초대가 있으면 그날은 쉽니다. */
    fun anniversary(): String? {
        val today = TimeTogether.dayOf(now)
        if (prefs.lastLineDay == today || waiting() != null) return null
        return TimeTogether.anniversaryLine(facts(), prefs.settings)?.also { prefs.lastLineDay = today }
    }

    /** 사다리 뒤의 병에서 나오는 쪽지. */
    fun note(index: Int): String = TimeTogether.note(facts(), prefs.settings, index)

    private fun facts() =
        RecallFacts.of(PetRepository.state, KeepsakeRepository.gifts, KeepsakeRepository.care, FamilyRepository.state, now)
}
