package com.example.pleosapp.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToLong

/** 그림책 하나. 함께한 날의 고비와 구름이의 자람 두 갈래입니다. 저장에는 [key]만 씁니다. */
sealed interface RecallId {
    val key: String

    data class Days(val days: Int) : RecallId {
        override val key: String get() = "day$days"
    }

    data class Grown(val stage: Growth.Stage) : RecallId {
        override val key: String get() = "stage_${stage.name}"
    }

    companion object {
        fun parse(key: String): RecallId? = when {
            key.startsWith("day") -> key.removePrefix("day").toIntOrNull()?.let(::Days)
            key.startsWith("stage_") -> Growth.Stage.entries.firstOrNull { it.name == key.removePrefix("stage_") }?.let(::Grown)
            else -> null
        }
    }
}

/** 회상을 어디까지 보여 줄지. 가족이 바뀌면 사람이 고를 수 있어야 합니다. */
data class RecallSettings(val off: Boolean = false, val hideDates: Boolean = false)

/**
 * 함께한 시간. 이벤트만으로 계산되는 값이라 원장을 다시 재생하면 복원됩니다.
 * 사람과 자리를 구분하지 않고, 더 달려도 하루는 하루입니다.
 */
data class TogetherState(
    /** 함께 달린 날 수. 달력으로 세고 줄지 않습니다. */
    val days: Int = 0,
    val lastDay: Long = -1,
    val firstTripAt: Long? = null,
    val firstTripM: Float = 0f,
    /** 같이 달린 길. 문장에만 쓰고 고비의 조건이 아닙니다. */
    val distanceM: Double = 0.0,
    val firstBeltAt: Long? = null,
    val firstRestAt: Long? = null,
    val firstStarAt: Long? = null,
    val firstStarCode: String? = null,
    /** 고비에 닿은 시각. 첫날도 들어 있습니다. */
    val reachedAt: Map<Int, Long> = emptyMap(),
    /** 구름이가 그 단계에 오른 시각. */
    val stageAt: Map<Growth.Stage, Long> = emptyMap(),
    /** 트립 중인지. 재생으로 복원되므로 시동이 꺼진 채로 끝난 트립은 다음 도착까지 이어집니다. */
    val inTrip: Boolean = false,
) {
    fun after(event: DriveEvent, before: PetState, next: PetState): TogetherState {
        val grown = if (next.feathers == before.feathers) this else grew(event.atMs, before.stage, next.stage)
        return when (event) {
            is DriveEvent.TripStarted -> grown.copy(inTrip = true)
            is DriveEvent.TripEnded -> grown.arrived(event)
            is DriveEvent.RearBeltBuckled -> if (firstBeltAt == null) grown.copy(firstBeltAt = event.atMs) else grown
            is DriveEvent.RestTaken -> if (firstRestAt == null) grown.copy(firstRestAt = event.atMs) else grown
            is DriveEvent.HealthRecovered ->
                if (firstStarAt == null && next.stars > before.stars) grown.copy(firstStarAt = event.atMs, firstStarCode = event.code) else grown
            else -> grown
        }
    }

    private fun grew(atMs: Long, from: Growth.Stage, to: Growth.Stage): TogetherState {
        if (to.ordinal <= from.ordinal) return this
        val climbed = Growth.Stage.entries.subList(from.ordinal + 1, to.ordinal + 1)
        return copy(stageAt = stageAt + climbed.associateWith { atMs })
    }

    private fun arrived(event: DriveEvent.TripEnded): TogetherState {
        val moved = copy(inTrip = false, distanceM = distanceM + event.summary.distanceM)
        if (event.summary.durationMs < TimeTogether.MIN_TRIP_MS) return moved
        val first = if (firstTripAt == null) moved.copy(firstTripAt = event.atMs, firstTripM = event.summary.distanceM) else moved
        val day = TimeTogether.dayOf(event.atMs)
        if (day == lastDay) return first
        val total = days + 1
        val reached = if (TimeTogether.isMilestone(total)) reachedAt + (total to event.atMs) else reachedAt
        return first.copy(days = total, lastDay = day, reachedAt = reached)
    }
}

enum class Season { SPRING, SUMMER, AUTUMN, WINTER }

/** 창밖에 날리는 계절 조각. */
enum class Drift { PETAL, SEED, FIREFLY, LEAF, FLAKE }

/** 시간의 규칙. 명세는 docs/design/time-spec.md입니다. */
object TimeTogether {
    /** 3일부터 1000일까지의 고비. 그 뒤로는 500일마다입니다. */
    val MILESTONES = listOf(3, 7, 14, 30, 50, 100, 200, 300, 365, 500, 730, 1000)

    const val FIRST_DAY = 1

    /**
     * 그날 집에서 실제로 달라지는 화분을 그림책 마지막 쪽 끝에 한 줄로 말합니다.
     * 화분은 100일에 꽃이 피고 365일에 덩굴이 늘어집니다([plantStep]). 화분이 선반에 있을 때만 붙입니다.
     * 예전에는 이 자리에서 창밖 풍경을 열었으나, 창밖이 늘 강가라 11/5에 뺐습니다.
     */
    val PLANT_LINES = mapOf(100 to "화분에 꽃이 피었어.", 365 to "화분 덩굴이 선반 밖까지 내려왔어.")

    /** 날짜 숨기기와 회상 끄기를 고르는 곳. 하는 일 그대로 불러서 설정 앱처럼 들리지 않게 합니다. */
    const val HIDE_TITLE = "가려 두기"

    /** 도착 화면을 띄우는 트립 길이와 같습니다. 주차 중 짧은 움직임은 날로 세지 않습니다. */
    const val MIN_TRIP_MS = 8_000L

    /**
     * 사진 줄에 거는 사진 수. 천장 손잡이 두 개 사이에 곧게, 누를 곳 간격을 지켜 걸 수 있는 만큼입니다.
     * 넘치면 첫날과 최근 사진을 남깁니다. 지난 사진은 앨범의 그림책으로 남습니다.
     */
    const val MAX_PHOTOS = 5

    private const val AFTER_LAST = 500
    private const val INVITE_DAYS = 7L

    /** 하루 경계는 한국 시간 자정입니다. 깃털의 하루 상한과 같습니다. */
    private const val DAY_OFFSET_MS = 9 * 60 * 60_000L
    private const val DAY_MS = 24 * 60 * 60_000L
    private val KST = ZoneOffset.ofHours(9)

    fun dayOf(atMs: Long): Long = Math.floorDiv(atMs + DAY_OFFSET_MS, DAY_MS)

    fun dateOf(atMs: Long): LocalDate = Instant.ofEpochMilli(atMs).atOffset(KST).toLocalDate()

    fun isMilestone(days: Int): Boolean =
        days == FIRST_DAY || days in MILESTONES || (days > MILESTONES.last() && (days - MILESTONES.last()) % AFTER_LAST == 0)

    /** 지금 권할 그림책. 없으면 null입니다. 사람이 누를 때만 열립니다. */
    fun waiting(state: TogetherState, settings: RecallSettings, seen: Set<String>, lastSeenDay: Long, nowMs: Long): RecallId? {
        val today = dayOf(nowMs)
        if (settings.off || state.inTrip || lastSeenDay == today) return null
        fun fresh(id: RecallId, atMs: Long) = id.key !in seen && today - dayOf(atMs) <= INVITE_DAYS
        val day = state.reachedAt.entries
            .filter { it.key != FIRST_DAY && fresh(RecallId.Days(it.key), it.value) }
            .maxByOrNull { it.key }
        if (day != null) return RecallId.Days(day.key)
        return state.stageAt.entries
            .filter { fresh(RecallId.Grown(it.key), it.value) }
            .maxByOrNull { it.key.ordinal }
            ?.let { RecallId.Grown(it.key) }
    }

    /** 초대 문장. 오늘 닿은 고비만 "오늘은"이라고 말합니다. */
    fun inviteLine(id: RecallId, state: TogetherState, nowMs: Long): String = when (id) {
        is RecallId.Grown -> "나 조금 컸어.\n같이 볼래?"
        is RecallId.Days -> {
            val today = state.reachedAt[id.days]?.let { dayOf(it) == dayOf(nowMs) } == true
            val first = when {
                today && id.days == 100 -> "오늘은 백일이야."
                today && id.days == 1000 -> "오늘은 천일이야."
                else -> "같이 달린 날이 ${dayWord(id.days)} 됐어."
            }
            "$first\n보여 주고 싶은 게 있어."
        }
    }

    /** 도착 일기에 붙는 한 줄. 그날 고비에 닿았을 때만 있습니다. */
    fun arrivalLine(state: TogetherState, settings: RecallSettings, nowMs: Long): String? {
        if (settings.off) return null
        val today = dayOf(nowMs)
        val reached = state.reachedAt.entries.filter { dayOf(it.value) == today }.maxOfOrNull { it.key } ?: return null
        return when (reached) {
            FIRST_DAY -> "오늘이 우리 첫날이야."
            100 -> "오늘로 백일이야."
            1000 -> "오늘로 천일이야."
            else -> "오늘로 같이 달린 날이 ${dayWord(reached)} 됐어."
        }
    }

    private fun dayWord(days: Int): String = if (days == 3) "사흘이" else "${days}일이"

    /** 벽에 걸린 사진. 닿은 고비마다 한 장입니다. 날 수만 보므로 원장을 읽기 전에도 그릴 수 있습니다. */
    fun photos(days: Int): List<RecallId.Days> {
        val fixed = (listOf(FIRST_DAY) + MILESTONES).filter { it <= days }
        val later = generateSequence(MILESTONES.last() + AFTER_LAST) { it + AFTER_LAST }.takeWhile { it <= days }.toList()
        val all = (fixed + later).map { RecallId.Days(it) }
        return if (all.size <= MAX_PHOTOS) all else all.take(1) + all.takeLast(MAX_PHOTOS - 1)
    }

    /** 앨범에 꽂힌 그림책. 일어난 순서입니다. */
    fun shelf(state: TogetherState): List<RecallId> {
        val days = state.reachedAt.map { (days, atMs) -> RecallId.Days(days) to atMs }
        val grown = state.stageAt.map { (stage, atMs) -> RecallId.Grown(stage) to atMs }
        return (days + grown).sortedBy { it.second }.map { it.first }
    }

    /** 쿠션 위의 크기. 첫날에도 작게 앉아 있고 어른이 지금 그림의 크기입니다. */
    fun heroScale(stage: Growth.Stage): Float = HERO_SCALE[stage.ordinal]

    /** 고사리는 함께한 날만큼 자랍니다. 벌지 않아도 커집니다. */
    fun fernScale(days: Int): Float = when {
        days < 30 -> 0.7f
        days < 100 -> 0.86f
        days < 365 -> 1f
        else -> 1.14f
    }

    /** 화분 그림의 단계. 1부터 5까지입니다. */
    fun plantStep(days: Int): Int = when {
        days < 7 -> 1
        days < 30 -> 2
        days < 100 -> 3
        days < 365 -> 4
        else -> 5
    }

    /** 창밖에 날리는 조각 수. 오래 함께할수록 조금씩 풍성해집니다. */
    fun driftCount(days: Int): Int = when {
        days < 30 -> 6
        days < 100 -> 8
        days < 365 -> 10
        else -> 12
    }

    /**
     * 지금 창밖에 날릴 조각. 없으면 null입니다. [view]는 지금 창에 든 풍경입니다.
     * 궂은 날은 지금 장면이 맡습니다. 바다에는 잎이 날릴 데가 없고, 밤에는 반딧불만 어울립니다.
     */
    fun drift(season: Season, state: SceneState, view: Reward?): Drift? {
        val dark = state.part == DayPart.EVENING || state.part == DayPart.NIGHT
        return when {
            state.sky != Sky.CLEAR -> null
            view == Reward.SCENE_WINTER -> Drift.FLAKE
            view == Reward.SCENE_SEA || view == Reward.SCENE_LIGHTHOUSE -> null
            season == Season.SUMMER && dark -> Drift.FIREFLY
            state.part == DayPart.NIGHT || view == Reward.SCENE_STARS -> null
            season == Season.SPRING -> Drift.PETAL
            season == Season.SUMMER -> Drift.SEED
            season == Season.AUTUMN -> Drift.LEAF
            else -> Drift.FLAKE
        }
    }

    /** 지금 창에 들 수 있는지. 별 언덕은 밤 그림 한 장이라 맑은 저녁과 밤에만 듭니다. 그 밖에는 시간대 기본 창입니다. */
    fun fitsWindow(scene: Reward, state: SceneState): Boolean =
        scene != Reward.SCENE_STARS || (state.sky == Sky.CLEAR && (state.part == DayPart.EVENING || state.part == DayPart.NIGHT))

    fun season(month: Int): Season = when (month) {
        3, 4, 5 -> Season.SPRING
        6, 7, 8 -> Season.SUMMER
        9, 10, 11 -> Season.AUTUMN
        else -> Season.WINTER
    }

    /** 올해면 월일만, 작년이면 "작년", 그전이면 연도를 붙입니다. */
    fun dateText(atMs: Long, nowMs: Long): String {
        val date = dateOf(atMs)
        val monthDay = "${date.monthValue}월 ${date.dayOfMonth}일"
        return when (dateOf(nowMs).year - date.year) {
            0 -> monthDay
            1 -> "작년 $monthDay"
            else -> "${date.year}년 $monthDay"
        }
    }

    fun kmText(km: Double): String = if (km < 10.0) "%.1fkm".format(km) else "%,dkm".format(km.roundToLong())

    /**
     * "1년 전 오늘" 한 줄. 달력이 맞는 날에만 있어서 드뭅니다. 첫 일만 보므로 날마다 나오지 않습니다.
     * 날짜를 숨겼으면 나오지 않습니다. 달력이 곧 내용이기 때문입니다.
     */
    fun anniversaryLine(facts: RecallFacts, settings: RecallSettings): String? {
        if (settings.off || settings.hideDates) return null
        val today = dateOf(facts.nowMs)
        val events = buildList {
            facts.firstTripAt?.let { add(it to "처음 같이 달렸어.") }
            if (facts.hasFamily) facts.firstClickAt?.let { add(it to "작은 친구가 처음 깨어났어.") }
            // 무엇인지 모르면 꺼내지 않습니다. 빈 이름에 조사만 붙은 문장이 되지 않게 합니다.
            facts.careTitle?.let { title -> facts.careAt?.let { add(it to "${Josa.objective(title)} 챙겨 줬어.") } }
            if (facts.hasFamily) facts.returnTitle?.let { title -> facts.returnAt?.let { add(it to "${Josa.objective(title)} 주워 왔어.") } }
            facts.restAt?.let { add(it to "긴 길에서 같이 쉬어 갔어.") }
        }
        for ((atMs, line) in events) {
            val date = dateOf(atMs)
            val years = today.year - date.year
            if (years >= 1 && date.plusYears(years.toLong()) == today) return "${years}년 전 오늘, $line"
        }
        return null
    }

    /**
     * 사다리를 다 올라간 뒤의 병에서 나오는 쪽지. 구름이의 혼잣말과 함께한 사실을 돌아가며 꺼냅니다.
     * 날이 쌓일수록 사실이 바뀌어서 같은 쪽지를 받아도 값이 다릅니다.
     */
    fun note(facts: RecallFacts, settings: RecallSettings, index: Int): String {
        val lines = NOTES.toMutableList()
        if (!settings.off) {
            lines.add(3, "같이 달린 날이 ${facts.days}일이 됐어.")
            if (facts.totalKm >= 0.1) lines.add(7, "같이 달린 길이 ${kmText(facts.totalKm)}야.")
            lines.add(11, "깃털이 ${facts.feathers}개 모였어.")
            if (facts.firstTripAt != null && !settings.hideDates) lines.add("${dateText(facts.firstTripAt, facts.nowMs)}, 처음 같이 달렸지.")
        }
        return lines[Math.floorMod(index, lines.size)]
    }

    private val HERO_SCALE = floatArrayOf(0.66f, 0.74f, 0.82f, 0.91f, 1f)

    private val NOTES = listOf(
        "오늘도 같이 달려 줘서 고마워.",
        "창밖이 좋아서 한참 봤어.",
        "구름 한 조각이 천천히 따라왔어.",
        "우리 차가 제일 포근해.",
        "문 닫히는 소리가 좋아.",
        "같은 길도 같이 가면 조금 달라 보여.",
        "돌아오는 소리를 기억해.",
        "밤에는 병 속 깃털이 은은해.",
        "늘 가던 길이 제일 좋아.",
        "기어가 P에 놓이면, 내 마음도 놓여.",
        "오늘도 잘 다녀왔어.",
        "돌아오면 여기 있을게.",
    )
}

/** 회상 문장에 쓰는 사실. 모르는 값은 null이고, 그 쪽은 다른 문장으로 바뀝니다. */
data class RecallFacts(
    val nowMs: Long,
    val days: Int = 0,
    val firstTripAt: Long? = null,
    val firstTripKm: Double = 0.0,
    val totalKm: Double = 0.0,
    val feathers: Int = 0,
    val stage: Growth.Stage = Growth.Stage.FLUFF,
    val stageAt: Map<Growth.Stage, Long> = emptyMap(),
    val jarsOpened: Int = 0,
    val stars: Int = 0,
    /** 지금 작은 정령이 있는지. 다 지웠으면 가족 쪽을 꺼내지 않습니다. */
    val hasFamily: Boolean = false,
    val firstClickAt: Long? = null,
    /** 아기가 처음 주워 온 날이고 [returnTitle]은 그 물건입니다. 혼자 탄 날 구름이가 주운 것은 넣지 않습니다. */
    val returnAt: Long? = null,
    val returnTitle: String? = null,
    val shelfCount: Int = 0,
    val careAt: Long? = null,
    val careTitle: String? = null,
    val restAt: Long? = null,
    val latestRung: Reward? = null,
    /** 앨범에 든 순간. 일어난 순서입니다. */
    val moments: List<Moment> = emptyList(),
    /** 화분이 선반에 있는지. 홈과 같이 사다리에서 받은 칸을 봅니다. */
    val plant: Boolean = false,
) {
    companion object {
        fun of(pet: PetState, gifts: GiftState, care: CareState, family: FamilyState, nowMs: Long): RecallFacts {
            val time = pet.together
            val record = care.records.firstOrNull()
            val starFirst = time.firstStarAt != null && (record == null || time.firstStarAt < record.atMs)
            // 첫 보물은 아기가 처음 주워 온 것입니다. 혼자 탄 날 구름이가 주운 것을 아기가 가져왔다고 말하지 않습니다.
            val firstBrought = gifts.shelf.firstOrNull { it.bringer != null }
            return RecallFacts(
                nowMs = nowMs,
                days = time.days,
                firstTripAt = time.firstTripAt,
                firstTripKm = time.firstTripM / 1000.0,
                totalKm = time.distanceM / 1000.0,
                feathers = pet.feathers,
                stage = pet.stage,
                stageAt = time.stageAt,
                jarsOpened = pet.jar.opened,
                stars = pet.stars + care.stars,
                hasFamily = family.spirits.isNotEmpty(),
                firstClickAt = gifts.momentAt[Moment.FIRST_CLICK] ?: time.firstBeltAt,
                returnAt = gifts.momentAt[Moment.FIRST_RETURN]?.takeIf { firstBrought != null },
                returnTitle = firstBrought?.kind?.title,
                shelfCount = gifts.shelf.size,
                careAt = if (starFirst) time.firstStarAt else record?.atMs,
                careTitle = if (starFirst) time.firstStarCode?.let(CareTerms::title) else record?.item?.title,
                restAt = gifts.momentAt[Moment.REST_DAY] ?: time.firstRestAt,
                latestRung = Ladder.RUNGS.getOrNull(gifts.claims - 1),
                moments = gifts.album.toList(),
                plant = Reward.DECOR_FERN in gifts.owned,
            )
        }
    }
}

/**
 * 그림책의 한 쪽. [art]는 그림 이름이고 앞의 것부터 찾습니다. 뒤로 갈수록 이미 있는 그림입니다.
 * [ruler]면 지금까지의 크기를 나란히 세운 키 재기 쪽입니다.
 */
data class RecallPage(val line: String, val art: List<String>, val ruler: Boolean = false)

data class RecallStory(val id: RecallId, val title: String, val pages: List<RecallPage>)

/**
 * 고비마다의 쪽과 문장. 기준은 낮은 혼잣말입니다. 일을 시키지 않고 다음을 약속하게 하지 않습니다.
 * 문장에는 원장의 사실만 넣고, 자리와 이름과 사람별 횟수는 넣지 않습니다. 없는 사람을 부르지 않기 위해서입니다.
 */
object RecallBook {
    private val ROAD = listOf("recall_road", "page_home")
    private val WINDOW = listOf("recall_window", "album_sendoff", "page_home")
    private val NIGHT = listOf("recall_night", "page_letter")
    private val JAR = listOf("recall_jar", "page_home")
    private val HOME = listOf("recall_home", "page_home")
    private val ALBUM = listOf("recall_album", "page_album")
    private val BELT = listOf("recall_belt", "album_sendoff", "page_home")
    private val SHELF = listOf("recall_shelf", "album_welcome", "page_home")
    private val RETURN = listOf("recall_return", "album_return", "page_album")
    private val REST = listOf("recall_rest", "album_rest", "page_home")
    private val CARE = listOf("recall_care", "page_home")
    private val LONG_ROAD = listOf("recall_longroad", "window_forest", "page_home")
    private val HUNDRED = listOf("recall_hundred", "page_album")
    private val THOUSAND = listOf("recall_thousand", "page_album")
    private val SPRING = listOf("recall_spring", "page_picnic")
    private val SUMMER = listOf("recall_summer", "window_day", "page_picnic")
    private val AUTUMN = listOf("recall_autumn", "window_evening", "page_home")
    private val WINTER = listOf("recall_winter", "window_snow", "page_home")
    private val RULER = listOf("book_spread")

    /** 천일 뒤의 사진. 줄이 같은 그림으로 채워지지 않게 돌아가며 씁니다. */
    private val LATER = listOf(NIGHT, ROAD, SUMMER, WINDOW, WINTER, HOME)

    private const val AT_PARK = "기어가 P에 놓이면, 내 마음도 놓여."
    private const val STAY = "돌아오면 여기 있을게."
    private const val MILESTONE_GAP = 500
    private const val KM_TO_BUSAN = 400.0
    private const val KM_AROUND_EARTH = 40_000.0

    /**
     * 칸 이름을 그대로 넣으면 어색한 사다리 칸입니다. 나머지 칸은 갈래별 꼴에 이름을 넣습니다.
     * 흉내말 동작은 하는 일을 배웠다고 말합니다. 목도리는 두르고, 망토와 가방은 걸칩니다.
     * 구름이가 읽는 글이라 "구름이가 엮은 모빌"처럼 제 이름이 든 칸은 한 일로 씁니다.
     * 실내등과 깃털 둥지는 새 물건이 아니라 바뀐 것이라 무엇이 달라졌는지 말합니다.
     */
    private val RUNG_LINES = mapOf(
        Reward.FORM_READ to "앨범을 읽는 법도 배웠어.",
        Reward.MOTION_FLAP to "귀를 쫑긋 세우는 법도 배웠어.",
        Reward.DECOR_LAMP to "실내등도 노을빛이 됐어.",
        Reward.MOTION_WAG to "꼬리로 톡톡 치는 법도 배웠어.",
        Reward.FORM_SCARF to "라벤더 목도리도 둘러 봤어.",
        Reward.DECOR_MOBILE to "나뭇가지 모빌도 엮었어.",
        Reward.SCENE_BOAT to "작은 돛배도 띄웠어.",
        Reward.FORM_CAPE to "세이지 망토와 가방도 걸쳐 봤어.",
        Reward.DECOR_FEATHERBED to "둥지에 깃털도 깔았어.",
    )

    fun title(id: RecallId): String = when (id) {
        is RecallId.Grown -> "키 재기"
        is RecallId.Days -> when (id.days) {
            1 -> "첫날"
            3 -> "사흘째"
            7 -> "일곱 번째 날"
            14 -> "열네 번째 날"
            30 -> "서른 번째 날"
            50 -> "쉰 번째 날"
            100 -> "백일"
            200 -> "이백 번째 날"
            300 -> "삼백 번째 날"
            500 -> "오백 번째 날"
            1000 -> "천일"
            else -> "${id.days}번째 날"
        }
    }

    /** 벽에 걸릴 사진. 줄이 단조롭지 않게 고비마다 다른 그림을 고릅니다. */
    fun photoArt(id: RecallId): List<String> = when (id) {
        is RecallId.Grown -> RULER
        is RecallId.Days -> when (id.days) {
            1 -> ROAD
            3 -> WINDOW
            7 -> JAR
            14 -> BELT
            30 -> HOME
            50 -> SHELF
            100 -> HUNDRED
            200 -> CARE
            300 -> LONG_ROAD
            365 -> AUTUMN
            500 -> ALBUM
            730 -> SPRING
            1000 -> THOUSAND
            else -> LATER[(id.days / MILESTONE_GAP) % LATER.size]
        }
    }

    fun story(id: RecallId, facts: RecallFacts, settings: RecallSettings): RecallStory {
        val pen = Pen(facts, settings.hideDates)
        val pages = when (id) {
            is RecallId.Days -> pen.days(id.days)
            is RecallId.Grown -> pen.grown(id.stage)
        }
        return RecallStory(id, title(id), pages)
    }

    private class Pen(private val facts: RecallFacts, private val hideDates: Boolean) {

        private val grownUp = facts.stage != Growth.Stage.FLUFF
        private val family = facts.hasFamily

        private fun page(art: List<String>, line: String) = RecallPage(line, art)

        private fun ruler(line: String) = RecallPage(line, RULER, ruler = true)

        /** 날짜를 숨겼거나 모르면 [plain]을 씁니다. */
        private fun dated(atMs: Long?, plain: String, line: (String) -> String): String =
            if (atMs == null || hideDates) plain else line(TimeTogether.dateText(atMs, facts.nowMs))

        private fun firstRide(): String {
            if (facts.firstTripKm < 0.1) return dated(facts.firstTripAt, "처음 같이 달린 날을 기억해.") { "$it, 처음 같이 달렸지." }
            val km = TimeTogether.kmText(facts.firstTripKm)
            return dated(facts.firstTripAt, "처음 같이 달린 길은 ${km}였어.") { "$it, 처음 같이 달렸지. ${km}였어." }
        }

        private fun distance(): String =
            if (facts.totalKm < 0.1) "늘 가던 길이 제일 많았어." else "같이 달린 길이 ${TimeTogether.kmText(facts.totalKm)}야."

        private fun feathers(): String =
            if (facts.feathers < 1) "같이 달린 날이 쌓이고 있어." else "깃털이 ${facts.feathers}개 모였어."

        private fun jars(): String =
            if (facts.jarsOpened < 1) "깃털이 조금씩 모이고 있어." else "병을 ${facts.jarsOpened}번 열었어."

        private fun rung(): RecallPage {
            val reward = facts.latestRung ?: return page(WINDOW, "창밖을 같이 본 날이 쌓였어.")
            val line = RUNG_LINES[reward] ?: when (reward.kind) {
                RewardKind.FORM -> "${reward.title}도 입어 봤어."
                RewardKind.MOTION -> "${reward.title}도 배웠어."
                RewardKind.SCENE -> "${reward.title}도 보게 됐어."
                RewardKind.DECOR -> "${reward.title}도 생겼어."
            }
            return page(HOME, line)
        }

        private fun measure(): RecallPage = when {
            facts.totalKm >= KM_AROUND_EARTH -> page(WINDOW, "지구를 ${(facts.totalKm / KM_AROUND_EARTH).toInt()}바퀴 돈 만큼이야.")
            facts.totalKm >= KM_TO_BUSAN -> page(WINDOW, "서울에서 부산까지 ${(facts.totalKm / KM_TO_BUSAN).toInt()}번 갈 만큼이야.")
            else -> page(WINDOW, "창밖이 계절마다 달랐어.")
        }

        /** 그날 집에서 달라진 화분을 마지막 쪽에 한 줄로 잇습니다. 쪽을 늘리지 않습니다. 화분이 없으면 잇지 않습니다. */
        fun days(days: Int): List<RecallPage> {
            val pages = written(days)
            val grew = TimeTogether.PLANT_LINES[days]?.takeIf { facts.plant } ?: return pages
            return pages.dropLast(1) + pages.last().let { it.copy(line = "${it.line}\n$grew") }
        }

        private fun written(days: Int): List<RecallPage> = when (days) {
            1 -> listOf(page(ROAD, dated(facts.firstTripAt, "우리 첫날.") { "$it, 우리 첫날." }))
            3 -> listOf(
                page(ROAD, "같이 달린 날이 사흘이 됐어."),
                page(WINDOW, firstRide()),
                page(NIGHT, AT_PARK),
            )
            7 -> listOf(
                page(JAR, "일곱 번째 날이야."),
                page(JAR, if (facts.feathers < 1) feathers() else "깃털을 ${facts.feathers}개 모았어."),
                page(ROAD, "같은 길도 같이 가면 조금 달라 보여."),
            )
            14 -> listOf(
                page(WINDOW, "열네 번째 날이야."),
                if (family && facts.firstClickAt != null) {
                    page(BELT, dated(facts.firstClickAt, "작은 친구가 처음 깨어난 날을 기억해.") { "$it, 작은 친구가 처음 깨어났어." })
                } else {
                    page(WINDOW, "창밖이 매일 조금씩 달라.")
                },
                page(ROAD, "찰칵 소리가 나면, 오늘도 같이 가는구나 해."),
            )
            30 -> listOf(
                page(HOME, "서른 번째 날이야."),
                if (grownUp) ruler("처음엔 이만했어.") else page(HOME, "병을 열 때마다 조금씩 달라져."),
                rung(),
                page(NIGHT, STAY),
            )
            50 -> fiftieth()
            100 -> listOf(
                page(HUNDRED, "오늘은 백일이야."),
                page(ROAD, dated(facts.firstTripAt, "백 날을 같이 달렸어.") { "${it}부터 백 날을 같이 달렸어." }),
                page(LONG_ROAD, distance()),
                if (grownUp) ruler("처음엔 이만했는데.") else page(JAR, feathers()),
                page(NIGHT, "백 날 동안 나갔다가, 늘 잘 돌아왔어. 고마워."),
            )
            200 -> twoHundredth()
            300 -> listOf(
                page(LONG_ROAD, "삼백 번째 날이야."),
                page(LONG_ROAD, distance()),
                measure(),
                page(ROAD, "그래도 제일 좋은 건 늘 가던 길이야."),
            )
            365 -> listOf(
                page(SPRING, "봄에는 꽃잎이 앞유리에 앉았어."),
                page(SUMMER, "여름에는 초록이 창을 다 채웠어."),
                page(AUTUMN, "가을에는 낙엽이 길을 덮었어."),
                page(WINTER, "겨울에는 창에 입김이 맺혔어."),
                page(HOME, "사계절을 같이 지났어. 앞으로도 여기 있을게."),
            )
            500 -> listOf(
                page(ROAD, "오백 번째 날이야."),
                page(WINDOW, dated(facts.firstTripAt, "첫날이 아직 또렷해.") { "${it}의 첫날이 아직 또렷해." }),
                albumPage(),
                page(NIGHT, "이제 이 차의 소리는 다 알아."),
            )
            730 -> listOf(
                page(WINDOW, "같이 달린 날이 730일이 됐어."),
                if (grownUp) ruler("이만큼 자랐어.") else page(JAR, feathers()),
                page(HOME, "집도 많이 채워졌어."),
                page(NIGHT, STAY),
            )
            1000 -> listOf(
                page(THOUSAND, "오늘은 천일이야."),
                page(ROAD, dated(facts.firstTripAt, "첫날에서 여기까지 왔어.") { "${it}에서 여기까지 왔어." }),
                page(LONG_ROAD, distance()),
                if (grownUp) ruler("처음엔 이만했는데.") else page(JAR, feathers()),
                page(NIGHT, "천 날 동안 나갔다가, 늘 잘 돌아왔어. 앞으로도 여기 있을게."),
            )
            else -> listOf(
                page(HOME, "같이 달린 날이 ${days}일이 됐어."),
                page(ROAD, dated(facts.firstTripAt, "첫날부터 쭉 같이 왔어.") { "${it}부터 쭉 같이 왔어." }),
                page(NIGHT, "앞으로도 여기 있을게."),
            )
        }

        private fun fiftieth(): List<RecallPage> {
            val keep = "다녀올 때마다 조금씩 쌓여."
            val item = facts.returnTitle
            if (!family || item == null) {
                return listOf(page(JAR, "쉰 번째 날이야."), page(JAR, jars()), page(HOME, "집이 조금씩 채워지고 있어."), page(ROAD, keep))
            }
            val brought = Josa.objective(item)
            return listOf(
                page(SHELF, "쉰 번째 날이야."),
                page(RETURN, dated(facts.returnAt, "작은 친구가 $brought 주워 온 날을 기억해.") { "$it, 작은 친구가 $brought 주워 왔어." }),
                page(SHELF, "선반에 모인 게 ${facts.shelfCount}개야."),
                page(ROAD, keep),
            )
        }

        private fun twoHundredth(): List<RecallPage> {
            val title = facts.careTitle
            if (facts.careAt == null || title == null) {
                val rest = if (facts.restAt != null) page(REST, "긴 길에서 같이 쉬어 간 날을 기억해.") else page(WINDOW, "창밖이 몇 번이나 바뀌었어.")
                return listOf(page(WINDOW, "이백 번째 날이야."), rest, page(HOME, AT_PARK), page(ROAD, "오늘도 같이 와서 좋아."))
            }
            val cared = Josa.objective(title)
            return listOf(
                page(CARE, "이백 번째 날이야."),
                page(CARE, dated(facts.careAt, "$cared 챙겨 준 날을 기억해.") { "$it, $cared 챙겨 줬어." }),
                if (facts.stars > 0) page(CARE, "별이 ${facts.stars}개 모였어.") else page(HOME, AT_PARK),
                page(ROAD, "챙겨 준 곳은 오래 기억해."),
            )
        }

        private fun albumPage(): RecallPage {
            val titles = facts.moments
                .filter { it != Moment.GATHERED && (family || it == Moment.REST_DAY) }
                .map { it.title }
            return if (titles.size >= 2) page(ALBUM, "${titles[0]}, ${titles[1]}. 앨범이 제법 찼어.") else page(HOME, "집이 제법 찼어.")
        }

        fun grown(stage: Growth.Stage): List<RecallPage> = when (stage) {
            Growth.Stage.FLUFF, Growth.Stage.LITTLE -> listOf(ruler("처음엔 이만했어."), ruler("이제 쿠션이 조금 좁아."))
            Growth.Stage.FRIEND -> listOf(ruler("또 조금 컸어."), ruler("창밖이 더 잘 보여."))
            Growth.Stage.STURDY -> listOf(ruler("이제 제법 듬직하지?"), ruler("기대도 끄떡없어."))
            Growth.Stage.GROWN -> listOf(ruler("이만큼 자랐어."), ruler("그래도 여기가 제일 좋아."))
        }
    }
}
