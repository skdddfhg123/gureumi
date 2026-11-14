package com.example.pleosapp.engine

/**
 * 도착 일기를 언제 소리 내어 읽을지. 서비스와 도착 카드가 같은 규칙을 씁니다.
 * 뒷자리 안전벨트를 맨 채 도착하면 아이가 자고 있을 수 있어 벨트가 풀리기를 기다립니다.
 * 밤에는 부모가 자는 아이를 안아 내리며 벨트를 풉니다. 그래서 풀려도 읽지 않고 카드와 조용한 알림만 남깁니다.
 * 밤 차임과 인사를 끌지는 소리 다듬기에서 개발자가 정합니다.
 */
object ArrivalAloud {
    enum class Start {
        /** 바로 읽습니다. */
        NOW,

        /** 뒷자리 안전벨트가 모두 풀리면 읽습니다. 기다림(`EngineConfig.REAR_QUIET_WAIT_MS`)이 지나면 읽지 않습니다. */
        AFTER_REAR,

        /** 읽지 않습니다. 효과음도 내지 않습니다. */
        NEVER,
    }

    /** [part]는 화면과 같은 때입니다. 검증 빌드의 장면 미리 보기도 따릅니다(`SkySignals.scene`). */
    fun start(rearStillBelted: Boolean, part: DayPart): Start = when {
        !rearStillBelted -> Start.NOW
        part == DayPart.NIGHT -> Start.NEVER
        else -> Start.AFTER_REAR
    }
}
