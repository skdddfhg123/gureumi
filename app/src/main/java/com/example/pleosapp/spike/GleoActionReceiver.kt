package com.example.pleosapp.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.pleosapp.engine.Mood
import com.example.pleosapp.engine.PetRepository
import com.example.pleosapp.engine.PetState
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gleo Interface: Gleo AI가 schema.json의 function(component="service")을 broadcast로 호출하면
 * 정령 상태로 답을 만들어 APP_ACTION_RESPONSE broadcast로 돌려줍니다.
 * 답은 Gleo가 그대로 읽을 수 있게 한국어 한 문장입니다. 'pet'이나 레벨 같은 숫자는 넣지 않습니다.
 */
class GleoActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        ProbeState.log("GLEO action=$action param=${intent.getStringExtra("ACTION_PARAM")}")
        PetRepository.init(context)
        val state = PetRepository.state
        val (message, data) = when (action) {
            "GET_PET_STATUS" -> moodAnswer(state.mood) to JSONObject().put("mood", state.mood.name.lowercase())
            "GET_VEHICLE_HEALTH" ->
                healthAnswer(state) to JSONObject().put("telltales", state.ailments.sorted().joinToString().ifEmpty { "none" })
            else -> return
        }
        val response = JSONObject()
            .put("functionName", action)
            .put("status", "success")
            .put("message", message)
            .put("data", JSONArray().put(data).toString())
        context.sendBroadcast(
            Intent(RESPONSE_ACTION)
                .putExtra("packageName", context.packageName)
                .putExtra("response", response.toString()),
        )
        ProbeState.log("GLEO response=$response")
    }

    companion object {
        private const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

        fun moodAnswer(mood: Mood): String = when (mood) {
            Mood.HAPPY -> "구름이는 지금 기분이 좋아."
            Mood.DIZZY -> "구름이는 아까 조금 어지러웠어."
            Mood.SICK -> "구름이는 차 경고등 때문에 조금 아파."
        }

        /** 구름이가 하는 말("발이 아파. 타이어 공기압을 봐 줘.")에서 할 일만 이어 한 문장으로 만듭니다. */
        fun healthAnswer(state: PetState): String {
            val line = state.ailmentLine() ?: state.ailments.minOrNull()?.let { PetState.healthLine(it) }
                ?: return "지금 켜진 경고등은 없어."
            return "켜진 경고등이 있어, ${line.substringAfter(". ")}"
        }
    }
}
