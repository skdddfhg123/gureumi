package com.example.pleosapp.verify

import com.example.pleosapp.engine.DayPart
import com.example.pleosapp.engine.Sky
import com.example.pleosapp.engine.SkySignals

/**
 * 검증 입력으로 창밖 장면을 고정합니다. 콘솔의 `spike scene`과 시나리오 수신기의 `cmd sky`가 같이 씁니다.
 * 값은 대소문자를 가리지 않습니다: time은 morning|day|evening|night, sky는 clear|rain|snow|fog.
 */
object SceneInput {
    /** 목록 밖 값이 하나라도 있으면 아무것도 바꾸지 않고 거짓입니다. 빠진 값은 실제 장면으로 돌아갑니다. */
    fun apply(time: String?, sky: String?): Boolean {
        val part = time?.let { t -> DayPart.entries.firstOrNull { it.name == t.uppercase() } }
        val weather = sky?.let { s -> Sky.entries.firstOrNull { it.name == s.uppercase() } }
        if ((time != null && part == null) || (sky != null && weather == null)) return false
        SkySignals.setPreview(part, weather)
        return true
    }
}
