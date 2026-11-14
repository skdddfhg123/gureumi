package com.example.pleosapp.engine

/** 작은 정령의 몸. 그림은 몸마다 한 벌이고, 색이 다른 정령은 같은 그림을 씁니다. */
enum class SpiritBody { SAGE, PEACH }

/** 고를 수 있는 가족 정령. 이름을 입력받지 않으려고 고정 목록만 둡니다. 저장에는 [id]만 씁니다. */
enum class SpiritPreset(val id: String, val displayName: String, val body: SpiritBody) {
    SAETEOL("saeteol", "새싹", SpiritBody.SAGE),
    SOM("som", "도토리", SpiritBody.PEACH),
    YANGTTE("yangtte", "개나리", SpiritBody.SAGE),
    ANGAE("angae", "라일락", SpiritBody.PEACH),
    ;

    companion object {
        fun fromId(id: String): SpiritPreset? = entries.firstOrNull { it.id == id }
    }
}

/**
 * [seat]는 지금 앉은 곳입니다. 누가 탈지는 자리가 아니라 탄 사람 수와 부른 순서로 엔진이 정해 앉힙니다.
 * 화면은 [seat]를 읽지 않고 [FamilyRepository.riders], [FamilyState.riderAt], [FamilyState.ridersIn]을 씁니다.
 */
data class FamilySpirit(val preset: SpiritPreset, val seat: SeatSlot)

/** 한 트립에 함께 탄 아기. */
data class Ride(val tripId: Long, val presets: List<SpiritPreset>)

/** 가족이 맡겨 둔 한마디. 녹음이나 자유 입력 없이 [SpiritPhrases]의 번호만 둡니다. */
data class HeldMessage(val preset: SpiritPreset, val phrase: Int)

/** 어느 트립 도착 때 전했는지. 트립으로 묶어 카드, 알림, 홈이 같은 문장을 보여 줍니다. */
data class DeliveredMessage(val tripId: Long, val preset: SpiritPreset, val phrase: Int)

object SpiritPhrases {
    val ALL = listOf(
        "오늘도 조심해서 다녀와.",
        "이따 봐!",
        "오늘도 고마워.",
        "많이 사랑해.",
        "밥 꼭 챙겨 먹어.",
        "힘내!",
    )

    private const val OFFERED = 3

    /** 카드에 보여 줄 문장 번호. 트립마다 조금씩 바뀌어 같은 말만 고르지 않게 합니다. */
    fun offered(seed: Long): List<Int> {
        val start = Math.floorMod(seed, ALL.size.toLong()).toInt()
        return List(OFFERED) { (start + it) % ALL.size }
    }
}

/**
 * 가족 정령 구성과 맡긴 말. 원장과 달리 사용자가 지울 수 있어야 해서 따로 저장합니다.
 * 성장은 구름이 하나에만 쌓으므로 정령별 수치를 두지 않습니다.
 */
data class FamilyState(
    val spirits: List<FamilySpirit> = emptyList(),
    val pending: HeldMessage? = null,
    val delivered: DeliveredMessage? = null,
    /** 내려서 비어 있는 자리. 정령이 자리를 지키는 중입니다. 언제 내렸는지는 남기지 않습니다. */
    val away: Set<SeatSlot> = emptySet(),
    /** 정차 중 이름을 부르거나 누른 순서. 이 순서로 먼저 탑니다. 트립이 끝나면 비웁니다. */
    val called: List<SpiritPreset> = emptyList(),
    /** 아기별 마지막으로 탄 시각. 부르지 않은 자리를 누구에게 줄지만 정합니다. */
    val lastRodeAt: Map<SpiritPreset, Long> = emptyMap(),
    /** 마지막 트립에 탄 아기. 도착 화면과 엽서가 씁니다. */
    val lastRide: Ride? = null,
) {
    val canAdopt: Boolean get() = spirits.size < MAX_SPIRITS && availablePresets.isNotEmpty()

    val availablePresets: List<SpiritPreset>
        get() = SpiritPreset.entries.filter { preset -> spirits.none { it.preset == preset } }

    fun spiritAt(seat: SeatSlot): FamilySpirit? = spirits.firstOrNull { it.seat == seat }

    fun companions(seats: Set<SeatSlot>): List<FamilySpirit> = spirits.filter { it.seat in seats }

    /**
     * 그 자리들에 엔진이 앉힌 아기. 프리셋 순서입니다. 이벤트의 자리(`SeatsAwake.newly`, `TripSummary.seats`)를 아기로 바꿀 때 씁니다.
     * 자리마다 정해진 아기는 없습니다. 같은 자리라도 트립마다 다른 아기가 앉습니다.
     */
    fun ridersIn(seats: Set<SeatSlot>): List<SpiritPreset> = companions(seats).map { it.preset }.sortedBy { it.ordinal }

    /** 그 자리에 엔진이 앉힌 아기. `SeatLeft`, `SeatReturned`, `RearBeltBuckled`의 자리를 아기로 바꿀 때 씁니다. */
    fun riderAt(seat: SeatSlot): SpiritPreset? = spiritAt(seat)?.preset

    /** 사람이 내려 자리를 지키는 아기. 프리셋 순서입니다. [away] 자리 대신 씁니다. */
    val keeping: List<SpiritPreset> get() = ridersIn(away)

    /** 아기 없이 사람이 함께 탄 트립. 가져올 아기는 없어도 고마운 날입니다. */
    fun rodeBeside(trip: TripSummary): Boolean =
        trip.durationMs >= TimeTogether.MIN_TRIP_MS && trip.seats.isNotEmpty() && companions(trip.seats).isEmpty()

    /** 자리 하나에 정령 하나입니다. 조건에 맞지 않으면 그대로 돌려줍니다. */
    fun adopt(preset: SpiritPreset, seat: SeatSlot): FamilyState {
        if (!canAdopt || spiritAt(seat) != null || spirits.any { it.preset == preset }) return this
        return copy(spirits = spirits + FamilySpirit(preset, seat))
    }

    /** 자리를 고르지 않고 들입니다. 비어 있는 자리를 줍니다. 누가 탈지는 자리와 상관없습니다. */
    fun adopt(preset: SpiritPreset): FamilyState {
        val seat = SeatSlot.entries.firstOrNull { spiritAt(it) == null } ?: return this
        return adopt(preset, seat)
    }

    /** 탈 차례. 부른 순서가 먼저이고, 나머지는 가장 오래 안 탄 아기부터입니다. 한 번도 안 탄 아기가 먼저이고 같으면 들인 순서입니다. */
    fun order(): List<SpiritPreset> {
        val adopted = spirits.map { it.preset }
        val first = called.filter { it in adopted }
        return first + adopted.filter { it !in first }.sortedBy { lastRodeAt[it] ?: Long.MIN_VALUE }
    }

    fun call(preset: SpiritPreset): FamilyState =
        if (spirits.none { it.preset == preset } || preset in called) this else copy(called = called + preset)

    fun uncall(preset: SpiritPreset): FamilyState = if (preset in called) copy(called = called - preset) else this

    /**
     * 채운 안전벨트 자리에 탈 아기를 앉힙니다. 사람이 아기보다 많으면 아기가 모두 탑니다.
     * 먼저 부른 아기, 앞서 채운 자리[held]에 계속 앉은 아기, 오래 안 탄 아기 순입니다.
     * [keep] 자리의 아기는 옮기지 않습니다. 자리를 지키는 중이거나 달리는 중입니다.
     * 탈 아기가 들어간 자리의 아기는 탈 아기가 있던 자리로 갑니다.
     */
    fun seated(belted: Set<SeatSlot>, held: Set<SeatSlot> = emptySet(), keep: Set<SeatSlot> = emptySet()): FamilyState {
        val locked = spirits.filter { it.seat in keep }.map { it.preset }.toSet()
        val stay = spirits.filter { it.seat in keep && it.seat in belted }.map { it.preset }
        val continuing = spirits.filter { it.seat in belted && it.seat in held }.map { it.preset }
        val riders = (stay + called + continuing + order())
            .filter { (it !in locked || it in stay) && spirits.any { spirit -> spirit.preset == it } }
            .distinct()
            .take(minOf(belted.size, spirits.size))
            .toSet()
        val seatOf = LinkedHashMap(spirits.associate { it.preset to it.seat })
        val free = ArrayDeque(SeatSlot.entries.filter { seat -> seat in belted && seatOf.none { it.value == seat && it.key in riders } })
        riders.filter { seatOf.getValue(it) !in belted }.forEach { rider ->
            val target = free.removeFirst()
            val from = seatOf.getValue(rider)
            seatOf.entries.firstOrNull { it.value == target }?.setValue(from)
            seatOf[rider] = target
        }
        val next = spirits.map { it.copy(seat = seatOf.getValue(it.preset)) }
        return if (next == spirits) this else copy(spirits = next)
    }

    /** 트립이 끝나면 탄 아기의 시각을 남기고 부른 순서를 비웁니다. 탄 아기는 그때 앉아 있던 자리로 셉니다. */
    fun arrived(trip: TripSummary, atMs: Long): FamilyState {
        val riders = companions(trip.seats + trip.tappedSeats).map { it.preset }
        return copy(called = emptyList(), lastRodeAt = lastRodeAt + riders.associateWith { atMs }, lastRide = Ride(trip.tripId, riders))
    }

    /** 그 트립에 탄 아기. 마지막 트립만 압니다. */
    fun rideOf(tripId: Long): List<FamilySpirit> {
        val ride = lastRide?.takeIf { it.tripId == tripId } ?: return emptyList()
        return ride.presets.mapNotNull { preset -> spirits.firstOrNull { it.preset == preset } }
    }

    fun moveTo(preset: SpiritPreset, seat: SeatSlot): FamilyState {
        if (spiritAt(seat) != null) return this
        return copy(
            spirits = spirits.map { if (it.preset == preset) it.copy(seat = seat) else it },
            away = away - spirits.filter { it.preset == preset }.map { it.seat }.toSet(),
        )
    }

    /** 정령을 지우면 그 정령과 연결된 말도 함께 사라집니다. */
    fun remove(preset: SpiritPreset): FamilyState = copy(
        spirits = spirits.filterNot { it.preset == preset },
        pending = pending?.takeUnless { it.preset == preset },
        delivered = delivered?.takeUnless { it.preset == preset },
        away = away - spirits.filter { it.preset == preset }.map { it.seat }.toSet(),
        called = called - preset,
        lastRodeAt = lastRodeAt - preset,
    )

    /** 정령이 있는 자리만 지킵니다. */
    fun seatLeft(seat: SeatSlot): FamilyState = if (spiritAt(seat) == null) this else copy(away = away + seat)

    fun seatReturned(seat: SeatSlot): FamilyState = copy(away = away - seat)

    /** 맡긴 말은 1건만 둡니다. 새로 맡기면 바꿉니다. */
    fun hold(preset: SpiritPreset, phrase: Int): FamilyState {
        if (phrase !in SpiritPhrases.ALL.indices || spirits.none { it.preset == preset }) return this
        return copy(pending = HeldMessage(preset, phrase))
    }

    /**
     * 도착 때 맡긴 말을 전할지 정합니다. 함께한 정령이 없고 5분 넘게 달린 트립에만 전합니다.
     * 발레 기사가 차를 옮기거나 주차 자리를 고치는 짧은 트립에는 읽지 않습니다.
     * 차량 건강 안내가 있으면 안내가 먼저라서 다음 혼자 탄 날로 미룹니다.
     */
    fun settleArrival(trip: TripSummary, ailing: Boolean): FamilyState {
        val held = pending ?: return this
        if (trip.durationMs <= CARRY_MIN_TRIP_MS) return this
        if (ailing || companions(trip.seats + trip.tappedSeats).isNotEmpty()) return this
        return copy(pending = null, delivered = DeliveredMessage(trip.tripId, held.preset, held.phrase))
    }

    fun deliveredLine(tripId: Long): String? = delivered
        ?.takeIf { it.tripId == tripId }
        ?.let { FamilySpeech.carried(it.preset.displayName, SpiritPhrases.ALL[it.phrase]) }

    companion object {
        const val MAX_SPIRITS = 4

        /** 맡긴 말을 전하는 트립은 이보다 길어야 합니다. */
        const val CARRY_MIN_TRIP_MS = 5 * 60_000L
    }
}

/** 받침에 따라 조사를 고릅니다. 이름 목록이 바뀌어도 문장이 어색해지지 않게 합니다. */
object Josa {
    private fun hasFinalConsonant(word: String): Boolean {
        val last = word.lastOrNull() ?: return false
        return last in '가'..'힣' && (last - '가') % 28 != 0
    }

    fun subject(word: String): String = word + if (hasFinalConsonant(word)) "이" else "가"

    fun objective(word: String): String = word + if (hasFinalConsonant(word)) "을" else "를"

    /** 대사에서 이름을 잇습니다. 반말 말풍선이라 글말 "과/와" 대신 "이랑/랑"을 씁니다. */
    fun and(word: String): String = word + if (hasFinalConsonant(word)) "이랑" else "랑"
}

/**
 * 가족 정령이 나오는 대사. TTS가 읽으므로 이모지를 쓰지 않습니다.
 * 아이에게 소리 내어 하는 짧은 말이라 "벨트 찰칵"처럼 "벨트"라고 합니다. 일기의 고마움 줄도 같습니다.
 * 설명 글과 말풍선 안내는 "안전벨트"라고 씁니다.
 */
object FamilySpeech {
    const val THANKS_DEFAULT = "찰칵! 고마워. 오늘도 같이 가자."
    const val ASK_DEFAULT = "친구야, 벨트 찰칵해 줄래?"
    const val GATHERED = "다 모였다! 오늘도 같이 가자."
    const val BESIDE = "오늘은 같이 타서 든든했어."

    /** 혼자 탄 날 물건을 주워 오는 메인 정령의 이름. 바꿀 수 없습니다. */
    const val HERO_NAME = "구름이"

    private fun subjectOf(names: List<String>): String = when (names.size) {
        1 -> Josa.subject(names[0])
        2 -> "${Josa.and(names[0])} ${Josa.subject(names[1])}"
        else -> "모두가"
    }

    fun woke(names: List<String>): String =
        "${if (names.size == 1) "찰칵!" else "찰칵찰칵!"} ${subjectOf(names)} 일어났어."

    /** "찰칵하다"는 한 낱말이라 붙여 씁니다. */
    fun askBelt(names: List<String>): String = "${subjectOf(names)} 깨어나게 벨트 찰칵해 줄래?"

    fun thanks(names: List<String>): String = "${subjectOf(names)} 벨트를 먼저 챙겨 줬어, 고마워."

    fun carried(name: String, phrase: String): String = "${Josa.subject(name)} 전해 달래. $phrase"

    /** 자리 이름은 말하지 않습니다. 아기는 자리에 묶이지 않습니다. */
    fun welcome(name: String): String = "반가워! ${Josa.subject(name)} 우리 차에 왔어."

    /** 옛 이름입니다. 자리는 쓰지 않습니다. */
    fun welcome(name: String, @Suppress("UNUSED_PARAMETER") seat: SeatSlot): String = welcome(name)

    /** 구름이가 하는 말이라 아기의 약속("있을게")으로 맺지 않습니다. */
    fun sendOff(name: String): String = "잘 다녀와. ${Josa.subject(name)} 자리를 지키고 있을 거야."

    fun welcomeBack(names: List<String>): String = "왔구나! ${subjectOf(names)} 반가워해."

    fun welcomeBack(name: String): String = welcomeBack(listOf(name))

    fun brought(name: String, item: String): String = "${Josa.subject(name)} ${Josa.objective(item)} 주워 왔어."

    /** 혼자 탄 날은 구름이가 주워 옵니다. */
    fun brought(gift: ReturnGift): String = brought(gift.bringer?.displayName ?: HERO_NAME, gift.kind.title)

    /** 처음부터 하던 일은 새로 말할 것이 없습니다. 일기는 구름이가 쓰므로 제 이름 대신 "나"라고 합니다. */
    fun learned(name: String, skill: FriendSkill): String? = when (skill) {
        FriendSkill.BASIC -> null
        FriendSkill.HABIT -> "${Josa.subject(name)} 자기 몸짓을 보여 주기 시작했어."
        FriendSkill.WORK -> "${Josa.subject(name)} 내 일을 거들기 시작했어."
        FriendSkill.DUO -> "${Josa.subject(name)} 나랑 같이 놀기 시작했어."
    }
}
