package com.example.pleosapp.engine

/**
 * 도착 일기를 읽는 동안 정차 중 말(안전벨트 인사, 부탁, 고마워)을 미루는 규칙.
 * 말이 일기를 끊으면 카드 TTS에 끝 알림이 오지 않습니다. 그러면 카드가 "일기를 읽고 있어"인 채 남습니다.
 * 서비스가 [POLL_MS]마다 묻고, 일기가 끝나면 말합니다. 그사이 출발하면 버립니다.
 */
object SpeechHold {
    const val POLL_MS = 500L

    /**
     * 카드가 처음 펼친 30초보다 길게 둡니다. 일기는 그 안에 끝납니다. 카드는 새 카드를 펼칠 때마다 30초를 새로 세므로
     * 카드가 닫히기를 기다리지 않습니다. 읽는 표시가 남아 있어도 이만큼 기다린 뒤에는 말합니다.
     */
    const val MAX_WAIT_MS = 40_000L

    enum class Next { SPEAK, WAIT, DROP }

    fun next(reading: Boolean, inTrip: Boolean, waitedMs: Long): Next = when {
        inTrip -> Next.DROP
        !reading || waitedMs >= MAX_WAIT_MS -> Next.SPEAK
        else -> Next.WAIT
    }

    /**
     * 카드가 못 떠서 서비스가 직접 읽는 일기. 읽기를 맡긴 뒤 TTS 시작을 보고, 그 뒤의 끝 알림에서 풉니다.
     * 앞 말의 끝 알림에는 풀리지 않습니다. TTS가 아무 알림도 주지 않으면 [MAX_WAIT_MS] 뒤에 풉니다.
     * [onSpeaking]은 TTS 스레드에서, 나머지는 서비스 메인 스레드에서 부릅니다.
     */
    class OwnReading {
        private enum class Phase { IDLE, ASKED, SPEAKING }

        @Volatile
        private var phase = Phase.IDLE

        @Volatile
        private var askedAtMs = 0L

        fun asked(nowMs: Long) {
            askedAtMs = nowMs
            phase = Phase.ASKED
        }

        fun onSpeaking(speaking: Boolean) {
            phase = when {
                speaking && phase == Phase.ASKED -> Phase.SPEAKING
                !speaking && phase == Phase.SPEAKING -> Phase.IDLE
                else -> phase
            }
        }

        /** 출발하면 낭독을 끊으므로 끝 알림을 기다리지 않습니다. */
        fun reset() {
            phase = Phase.IDLE
        }

        fun reading(nowMs: Long): Boolean = phase != Phase.IDLE && nowMs - askedAtMs < MAX_WAIT_MS
    }
}
