package com.example.pleosapp.engine

import kotlin.math.roundToInt

enum class Mood { HAPPY, DIZZY, SICK }

/** 스티커 조건을 세는 누적 횟수. 원장을 다시 재생하면 그대로 나옵니다. */
data class TripCounts(
    val softStops: Int = 0,
    val signals: Int = 0,
    val rearBelts: Int = 0,
    val rests: Int = 0,
)

/** 정령의 상태. 이벤트만으로 계산되는 값이라 원장을 다시 재생하면 언제든 복원됩니다. */
data class PetState(
    val feathers: Int = 0,
    val sickness: Int = 0,
    val ailments: Set<String> = emptySet(),
    val trips: Int = 0,
    val lastTrip: TripSummary? = null,
    /** 질환별 시작 시각. 회복 보상은 질환이 충분히 지속된 뒤에만 줍니다(경고등 깜빡임으로 깃털을 모으는 것을 막음). */
    val ailmentSince: Map<String, Long> = emptyMap(),
    /** 코드별 마지막 회복 보상 시각. 같은 코드는 하루 한 번만 보상합니다. */
    val recoveryRewardedAt: Map<String, Long> = emptyMap(),
    /** 재시작 때 엔진에 넘길 휴식 시계. */
    val restClock: RestClock = RestClock(),
    /** 이번 여정을 안전벨트를 매고 시작했는지. 홈의 도장에 씁니다. */
    val tripBelted: Boolean = false,
    /** 정비로 경고등을 끈 횟수. 룸미러의 별이 됩니다. */
    val stars: Int = 0,
    val lastStarAtMs: Long? = null,
    /** 기본 깃털의 하루 상한을 세는 날과 그날 준 개수. */
    val baseDay: Long = -1,
    val baseToday: Int = 0,
    /** 지난 도착 때의 깃털과 그 여정에서 늘어난 개수. 도착 화면이 병에 부어 넣습니다. */
    val arrivalFeathers: Int = 0,
    val lastGain: Int = 0,
    val counts: TripCounts = TripCounts(),
    /** 함께한 날과 첫 일들. 회상과 집의 변화가 씁니다. */
    val together: TogetherState = TogetherState(),
    /** 주유나 충전을 마쳤고 아직 다음 도착 전입니다. 그동안 고맙다고 말합니다. */
    val fed: Boolean = false,
) {
    val stage: Growth.Stage get() = Growth.stage(feathers)

    val jar: Growth.Jar get() = Growth.jar(feathers)

    /** 병을 하나 채울 때마다 레벨이 오릅니다. */
    val level: Int get() = jar.opened + 1

    /** 구름이 그림의 크기 단계. 1부터 시작합니다. */
    val growthStep: Int get() = stage.ordinal + 1

    /** 아픈 것은 아니지만 챙겨 주면 좋은 것. 워셔액, 연료, 충전입니다. */
    val softNeed: String? get() = ailments.firstOrNull { it in SOFT_CODES }

    val mood: Mood
        get() = when {
            ailments.any { it !in SOFT_CODES } -> Mood.SICK
            sickness >= DIZZY_SICKNESS -> Mood.DIZZY
            else -> Mood.HAPPY
        }

    fun apply(event: DriveEvent): PetState = step(event).let { it.copy(together = together.after(event, this, it)) }

    private fun step(event: DriveEvent): PetState = when (event) {
        is DriveEvent.TripStarted -> {
            val parkedAt = restClock.parkedSinceMs
            val rested = parkedAt != null && event.atMs - parkedAt >= EngineConfig.DEFAULT_REST_MS
            copy(sickness = 0, restClock = RestClock(if (rested) 0 else restClock.driveMs, null), tripBelted = false)
        }
        is DriveEvent.TripEnded -> {
            val day = (event.atMs + DAY_OFFSET_MS) / DAY_MS
            val usedToday = if (day == baseDay) baseToday else 0
            val base = minOf(Growth.baseFeathers(event.summary.durationMs), Growth.BASE_DAY_CAP - usedToday)
            val total = feathers + base
            val ended = copy(
                trips = trips + 1,
                restClock = RestClock(restClock.driveMs + event.summary.durationMs.coerceAtLeast(0), event.atMs),
                feathers = total,
                baseDay = day,
                baseToday = usedToday + base,
                counts = counted(event),
            )
            // 주차 중 짧은 움직임은 도착이 아닙니다. 지난 일기와 도장은 그대로 두고, 주운 깃털은 다음 도착에 함께 붓습니다.
            if (event.summary.durationMs < TimeTogether.MIN_TRIP_MS) {
                ended
            } else {
                ended.copy(lastTrip = event.summary, arrivalFeathers = total, lastGain = total - arrivalFeathers, fed = false)
            }
        }
        is DriveEvent.SmoothStop -> copy(feathers = feathers + 1)
        is DriveEvent.SignalUsed -> copy(feathers = feathers + 1)
        is DriveEvent.BeltsReady -> copy(feathers = feathers + BELT_FEATHERS, tripBelted = true)
        is DriveEvent.HardBrake -> copy(sickness = (sickness + 1).coerceAtMost(MAX_SICKNESS))
        is DriveEvent.RestTaken -> copy(
            feathers = feathers + REST_FEATHERS,
            restClock = restClock.copy(driveMs = 0),
            counts = counts.copy(rests = counts.rests + 1),
        )
        is DriveEvent.RearBeltBuckled -> copy(feathers = feathers + BELT_FEATHERS)
        is DriveEvent.HealthAlert -> copy(
            ailments = ailments + event.code,
            ailmentSince = ailmentSince + (event.code to (ailmentSince[event.code] ?: event.atMs)),
        )
        is DriveEvent.HealthRecovered -> {
            val since = ailmentSince[event.code]
            val lastRewarded = recoveryRewardedAt[event.code]
            val food = event.code in FOOD_CODES
            val lasted = since != null && event.atMs - since >= RECOVERY_MIN_ACTIVE_MS
            val rewardable = !food && lasted && (lastRewarded == null || event.atMs - lastRewarded >= RECOVERY_REWARD_INTERVAL_MS)
            copy(
                ailments = ailments - event.code,
                ailmentSince = ailmentSince - event.code,
                feathers = if (rewardable) feathers + RECOVERY_FEATHERS else feathers,
                stars = if (rewardable) stars + 1 else stars,
                lastStarAtMs = if (rewardable) event.atMs else lastStarAtMs,
                recoveryRewardedAt = if (rewardable) recoveryRewardedAt + (event.code to event.atMs) else recoveryRewardedAt,
                fed = fed || (food && lasted),
            )
        }
        is DriveEvent.SignalMissed, is DriveEvent.BeltsMissing, is DriveEvent.RearBeltRequested,
        is DriveEvent.SeatsAwake, is DriveEvent.SeatLeft, is DriveEvent.SeatReturned -> this
    }

    private fun counted(event: DriveEvent.TripEnded): TripCounts {
        val trip = event.summary
        return counts.copy(
            softStops = counts.softStops + trip.smoothStops,
            signals = counts.signals + trip.signalsUsed,
            rearBelts = counts.rearBelts + trip.rearBelts,
        )
    }

    /** 도착 후 정령이 건네는 한마디. 벌점 대신 칭찬과 '놓친 깃털'로 말합니다. */
    fun arrivalLine(): String {
        val trip = lastTrip ?: return "오늘도 잘 부탁해!"
        val earned = trip.smoothStops + trip.signalsUsed
        val parts = mutableListOf("도착! 깃털 ${earned}개를 모았어.")
        if (trip.hardBrakes > 0) parts += "급제동 ${trip.hardBrakes}번에 살짝 멀미했어."
        if (trip.signalsMissed > 0) parts += "깜빡이를 놓친 깃털도 ${trip.signalsMissed}개 있어."
        ailmentLine()?.let { parts += it }
        return parts.joinToString(" ")
    }

    fun ailmentLine(): String? = ailments.filter { it !in SOFT_CODES }.sorted().firstOrNull()?.let(::healthLine)

    fun statusLine(): String {
        val trip = lastTrip?.let {
            " · 지난 트립 %.1fkm %d분".format(it.distanceM / 1000f, (it.durationMs / 60_000f).roundToInt())
        }.orEmpty()
        return "Lv.$level 깃털 $feathers · 기분 $mood · 멀미 $sickness$trip"
    }

    companion object {
        fun healthLine(code: String): String = HEALTH_LINES[code] ?: "차 어딘가가 불편해. 경고등을 확인해 줘."

        /** 워셔액 부족은 경고등이 아니라 SDK의 경고 값으로 옵니다. */
        const val WASHER_CODE = "LOW_WASHER_FLUID"

        /** 연료와 충전은 고장이 아니라 밥입니다. 채워도 별을 주지 않습니다. */
        val FOOD_CODES = setOf("LOW_FUEL", "LOW_EV_BATTERY")
        private val SOFT_CODES = FOOD_CODES + WASHER_CODE

        /** 하루 경계는 한국 시간 자정입니다. 원장을 다시 재생해도 같은 값이 나와야 합니다. */
        private const val DAY_OFFSET_MS = 9 * 60 * 60_000L
        private const val DAY_MS = 24 * 60 * 60_000L
        private const val MAX_SICKNESS = 5
        private const val DIZZY_SICKNESS = 2
        private const val BELT_FEATHERS = 2
        private const val REST_FEATHERS = 3
        private const val RECOVERY_FEATHERS = 5
        /** 이보다 짧게 켜졌다 꺼진 경고등은 정비가 아니라 깜빡임으로 봅니다. */
        private const val RECOVERY_MIN_ACTIVE_MS = 10 * 60_000L
        private const val RECOVERY_REWARD_INTERVAL_MS = 24 * 60 * 60_000L

        private val HEALTH_LINES = mapOf(
            "TPMS" to "발이 아파. 타이어 공기압을 봐 줘.",
            "ENGINE_OIL_PRESSURE" to "속이 안 좋아. 엔진오일을 확인해 줘.",
            "ENGINE_TEMPERATURE" to "열이 나. 엔진 온도를 확인해 줘.",
            "LOW_VOLT_BATTERY" to "기운이 없어. 배터리를 확인해 줘.",
            "ABS" to "다리에 힘이 없어. 브레이크 점검이 필요해.",
            "AIRBAG" to "가슴이 답답해. 에어백 경고등을 확인해 줘.",
            "EV_SYSTEM" to "몸이 이상해. 전기 시스템을 점검해 줘.",
            "ENGINE" to "속이 안 좋아. 엔진 점검이 필요해.",
            "MASTER_WARNING" to "뭔가 이상해. 계기판 경고를 확인해 줘.",
            "LOW_FUEL" to HomeSpeech.FUEL.replace("\n", " "),
            "LOW_EV_BATTERY" to HomeSpeech.CHARGE.replace("\n", " "),
            WASHER_CODE to HomeSpeech.WASHER.replace("\n", " "),
        )
    }
}
