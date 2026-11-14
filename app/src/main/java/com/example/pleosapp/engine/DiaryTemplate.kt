package com.example.pleosapp.engine

import kotlin.math.roundToInt

/**
 * 트립 요약과 정령 상태를 두 줄 여정 일기로 바꿉니다(목소리와 소리 문서).
 * 1줄 여정 · 2줄 한 가지만(건강 > 맡긴 말 > 고마운 일 > 좋았던 운전 > 한 가지 부탁 > 마무리).
 * 작은 정령이 새 일을 배운 도착에만 3줄이 붙습니다.
 * 말투: 반말, 1인칭, 문장당 숫자 1개 이하, 느낌표 1개 이하, 이모지 없음(TTS가 읽음). 1차는 템플릿, 2차는 LLM.
 */
object DiaryTemplate {

    fun lines(trip: TripSummary, state: PetState, family: FamilyState = FamilyState(), gifts: GiftState = GiftState()): List<String> =
        listOfNotNull(journeyLine(trip), secondLine(trip, state, family), learnedLine(trip, family, gifts))

    /** TTS용 한 문단. */
    fun speech(trip: TripSummary, state: PetState, family: FamilyState = FamilyState(), gifts: GiftState = GiftState()): String =
        lines(trip, state, family, gifts).joinToString(" ")

    /** 단계를 올린 트립의 첫 정령이 말합니다. 혼자 온 트립이면 지난 단계를 다시 말하지 않습니다. */
    private fun learnedLine(trip: TripSummary, family: FamilyState, gifts: GiftState): String? {
        val skill = gifts.justLearned ?: return null
        val first = family.companions(trip.seats + trip.tappedSeats).firstOrNull() ?: return null
        return FamilySpeech.learned(first.preset.displayName, skill)
    }

    private fun journeyLine(trip: TripSummary): String {
        val minutes = (trip.durationMs / 60_000.0).roundToInt().coerceAtLeast(1)
        return "오늘은 ${spokenTime(minutes)}쯤 같이 달렸어."
    }

    /** 보고문처럼 읽히지 않게 한 시간이 넘으면 30분 단위로 말합니다. */
    private fun spokenTime(minutes: Int): String {
        if (minutes < 60) return "${minutes}분"
        val halfHours = (minutes / 30.0).roundToInt()
        return "${halfHours / 2}시간" + if (halfHours % 2 == 1) " 반" else ""
    }

    private fun secondLine(trip: TripSummary, state: PetState, family: FamilyState): String =
        state.ailmentLine()
            ?: family.deliveredLine(trip.tripId)
            ?: when {
                // 고마움은 안전벨트를 채운 자리만 받습니다. 화면에서 깨운 자리는 표시만 합니다.
                family.companions(trip.seats).isNotEmpty() ->
                    FamilySpeech.thanks(family.companions(trip.seats).map { it.preset.displayName })
                trip.rearBelts > 0 -> "친구가 벨트를 먼저 챙겨 줬어, 고마워."
                family.rodeBeside(trip) -> FamilySpeech.BESIDE
                trip.restedBefore -> "쉬어 가 준 덕분에 바깥 공기를 마셨어, 고마워."
                trip.smoothStops == 0 && trip.signalsUsed == 0 -> plainLine(trip, state)
                trip.smoothStops >= trip.signalsUsed -> praise(trip.smoothStops, "부드럽게 멈춘 게 ${trip.smoothStops}번!", "부드럽게 멈춰 줘서")
                else -> praise(trip.signalsUsed, "깜빡이를 ${trip.signalsUsed}번이나 미리 켜 줬어!", "깜빡이를 미리 켜 줘서")
            }

    /** 한두 번은 숫자 없이 말합니다. "1번이나"는 뜻이 어긋납니다. 셋부터 숫자를 셉니다. */
    private fun praise(count: Int, counted: String, few: String): String =
        if (count >= COUNT_FROM) "$counted 깃털을 그만큼 모았어." else "$few 깃털을 모았어."

    private const val COUNT_FROM = 3

    /** 칭찬할 운전이 없는 트립. 부탁은 여기서만 합니다. 사다리를 다 채운 뒤에는 자란다고 말하지 않습니다. */
    private fun plainLine(trip: TripSummary, state: PetState): String = when {
        trip.hardBrakes > 0 -> "아까 조금 출렁였어, 다음엔 천천히 멈춰 줘."
        trip.signalsMissed > 0 -> "깜빡이를 먼저 켜 주면 깃털을 더 모을 수 있어."
        state.jar.opened >= Ladder.RUNGS.size -> "벌써 ${state.together.days.coerceAtLeast(1)}일을 함께했어, 고마워."
        state.lastGain > 0 -> "오늘처럼만 달리면 금방 자랄 것 같아."
        else -> "깃털은 못 모았지만, 같이 달린 것만으로도 좋았어."
    }
}
