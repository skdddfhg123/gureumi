package com.example.pleosapp.engine

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** 모으는 것은 네 갈래입니다. 전부 화면이 달라지는 것이고, 깃털 보너스 같은 칸은 없습니다. */
enum class RewardKind(val title: String, val teaser: String, val shelf: String) {
    FORM("모습", "다음은 새 모습이야.", "모습"),
    MOTION("동작", "다음은 새 동작이야.", "배운 동작"),
    DECOR("소품", "다음은 새 소품이야.", "집 안의 소품"),
    SCENE("풍경", "다음은 새 풍경이야.", "창밖 풍경"),
}

enum class Reward(val id: String, val kind: RewardKind, val title: String) {
    FORM_READ("read", RewardKind.FORM, "앨범 읽는 구름이"),
    DECOR_FERN("fern", RewardKind.DECOR, "작은 화분"),
    MOTION_FLAP("flap", RewardKind.MOTION, "귀 쫑긋"),
    /** 창밖은 늘 지금 장소입니다. 강가 풍경은 강가와 창밖에 함께 보입니다. */
    SCENE_BUTTERFLY("butterfly", RewardKind.SCENE, "강가 나비"),
    /** 새 물건이 아니라 빛입니다. 저녁과 밤에 천장 실내등이 노을빛으로 켜집니다. */
    DECOR_LAMP("lamp", RewardKind.DECOR, "노을빛 실내등"),
    MOTION_WAG("wag", RewardKind.MOTION, "꼬리 톡톡"),
    FORM_SCARF("scarf", RewardKind.FORM, "라벤더 목도리"),
    DECOR_MOBILE("mobile", RewardKind.DECOR, "구름이가 엮은 모빌"),
    SCENE_BOAT("boat", RewardKind.SCENE, "구름이가 띄운 돛배"),
    DECOR_TRAY("tray", RewardKind.DECOR, "보물 접시"),
    /** 귀환품 들꽃을 꽂은 컵입니다. */
    DECOR_VASE("vase", RewardKind.DECOR, "들꽃 컵"),
    FORM_CAPE("cape", RewardKind.FORM, "세이지 망토와 가방"),
    /** 아기 둥지에 병의 깃털을 깝니다. 새 물건이 아니라 둥지가 바뀝니다. */
    DECOR_FEATHERBED("featherbed", RewardKind.DECOR, "깃털 둥지"),
    MOTION_HUM("hum", RewardKind.MOTION, "콧노래"),
    FORM_APRON("apron", RewardKind.FORM, "살구 앞치마"),
    FORM_RAINCOAT("raincoat", RewardKind.FORM, "노란 비옷"),
    /** 모습 칸의 순서가 엽서 번호라서 새 모습은 맨 뒤에 둡니다. */
    FORM_OVERALLS("overalls", RewardKind.FORM, "이끼색 멜빵바지"),

    // 습관과 정비로 배우는 동작. 사다리 밖에 있습니다.
    MOTION_SWAY("sway", RewardKind.MOTION, "살랑살랑 춤"),
    MOTION_TILT("tilt", RewardKind.MOTION, "갸우뚱"),
    MOTION_NOD("nod", RewardKind.MOTION, "꾸벅 인사"),
    MOTION_SHAKE("shake", RewardKind.MOTION, "부르르 털기"),
    MOTION_HOP("hop", RewardKind.MOTION, "콩콩 뛰기"),
    MOTION_STRETCH("stretch", RewardKind.MOTION, "기지개"),

    // 함께한 날이 여는 풍경. 사다리 밖에 있습니다.
    SCENE_LIGHTHOUSE("lighthouse", RewardKind.SCENE, "창밖 등대"),
    SCENE_FIELD("field", RewardKind.SCENE, "창밖 논길"),
    SCENE_STARS("stars", RewardKind.SCENE, "별 언덕"),

    // 11/4에 사다리에서 뺀 칸. 창밖은 늘 지금 장소이고(안 A), 책장은 앨범과 합쳤습니다.
    // 옛 기록을 읽고, 아직 이 이름을 쓰는 화면이 있어 남겨 둡니다. 새로 얻지 않습니다.
    SCENE_SEA("sea", RewardKind.SCENE, "창밖 바다"),
    SCENE_WINTER("winter", RewardKind.SCENE, "겨울 둥지"),
    DECOR_BOOKS("books", RewardKind.DECOR, "작은 책장"),
    SCENE_FOREST("forest", RewardKind.SCENE, "창밖 숲길"),
    ;

    companion object {
        fun fromId(id: String?): Reward? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 병이 찰 때마다 여는 고정 사다리. 레벨은 열린 병 수에 1을 더한 값이라 첫 칸이 레벨 2입니다.
 * 작은 칸 둘 뒤에 큰 칸 하나를 둡니다. 순서를 고정해야 큰 칸이 앞으로 몰리지 않습니다.
 */
object Ladder {
    val RUNGS = listOf(
        Reward.FORM_READ, Reward.DECOR_FERN, Reward.MOTION_FLAP, Reward.SCENE_BUTTERFLY,
        Reward.DECOR_LAMP, Reward.MOTION_WAG, Reward.FORM_SCARF, Reward.DECOR_MOBILE,
        Reward.SCENE_BOAT, Reward.DECOR_TRAY, Reward.DECOR_VASE, Reward.FORM_CAPE,
        Reward.DECOR_FEATHERBED, Reward.MOTION_HUM, Reward.FORM_APRON, Reward.FORM_RAINCOAT,
        Reward.FORM_OVERALLS,
    )

    /** 11/4에 뺀 칸과 그 칸을 이어받은 칸. 뺀 칸을 고른 옛 기록은 이어받은 칸을 가집니다. 숲길 칸만 맨 뒤로 갔습니다. */
    val REPLACED = mapOf(
        Reward.SCENE_SEA to Reward.SCENE_BUTTERFLY,
        Reward.SCENE_WINTER to Reward.SCENE_BOAT,
        Reward.DECOR_BOOKS to Reward.DECOR_FEATHERBED,
        Reward.SCENE_FOREST to Reward.FORM_OVERALLS,
    )

    /** 그 칸이 열리는 레벨. */
    fun level(reward: Reward): Int = RUNGS.indexOf(reward) + 2
}

/** 함께한 순간. 예고 없이 앨범에 들어갑니다. 놓쳐서 못 채우는 칸은 없습니다. */
enum class Moment(val id: String, val title: String, val hint: String) {
    FIRST_CLICK("click", "첫 찰칵", "작은 친구가 처음 깨어난 날"),
    GATHERED("gathered", "다 모인 날", "다 같이 탄 날"),
    /** 화면은 "쪽지 남기기"라 부릅니다. 앨범도 같은 말을 씁니다. id는 그대로입니다. */
    LETTER("letter", "건넨 쪽지", "쪽지를 처음 전한 날"),
    THANKS("thanks", "고마운 날", "함께 도착한 날"),
    /**
     * 아기가 처음 주워 온 날입니다. 혼자 탄 날 구름이가 주운 것은 세지 않습니다.
     * 주워 온 것은 보물 접시에 담깁니다. "귀환"은 아이가 모르는 말이라 쓰지 않습니다. id는 그대로입니다.
     */
    FIRST_RETURN("return", "첫 보물", "처음 주워 온 날"),
    SEND_OFF("sendoff", "첫 배웅", "처음 손 흔들어 준 날"),
    WELCOME("welcome", "첫 반김", "다시 만난 날"),
    REST_DAY("rest", "쉬어 간 날", "긴 길에서 쉬어 간 날"),
    ;

    companion object {
        fun fromId(id: String?): Moment? = entries.firstOrNull { it.id == id }
    }
}

/** 작은 정령이 다녀오며 주워 온 것. 실제로 들른 곳을 뜻하지 않습니다. */
enum class ReturnKind(val id: String, val title: String) {
    STONE("stone", "매끈한 돌"),
    MAPLE("maple", "단풍잎"),
    ACORN("acorn", "동그란 열매"),
    PINECONE("pinecone", "솔방울"),
    SHELL("shell", "작은 조개"),
    CLOVER("clover", "네잎클로버"),
    BERRY("berry", "빨간 열매"),
    FLOWER("flower", "들꽃"),
    TWIG("twig", "나뭇가지"),
    GLASS("glass", "바다 유리"),
    SEED("seed", "민들레 씨앗"),
    CAP("cap", "작은 깍정이"),
    ;

    companion object {
        fun fromId(id: String?): ReturnKind? = entries.firstOrNull { it.id == id }
    }
}

/** 다녀오며 주워 온 것. [bringer]가 null이면 혼자 탄 날 구름이가 주워 왔습니다. [tripId]가 0이면 옛 기록입니다. */
data class ReturnGift(val kind: ReturnKind, val bringer: SpiritPreset?, val tripId: Long = 0L) {
    /** 옛 이름입니다. 구름이가 주워 온 것은 첫 아기로 보입니다. 화면이 [bringer]로 옮기면 지웁니다. */
    val preset: SpiritPreset get() = bringer ?: SpiritPreset.entries.first()
}

/** 함께 탄 횟수로 작은 정령이 할 줄 알게 되는 일. 몸은 자라지 않고, 가족 전체로 세며 사람별로 세지 않습니다. */
enum class FriendSkill(val trips: Int) {
    BASIC(0),
    HABIT(3),
    WORK(10),
    DUO(30),
}

data class GiftState(
    /** 사다리에서 고른 칸. 고른 순서입니다. 무엇을 가졌는지는 이 목록만 봅니다. */
    val chosen: List<Reward> = emptyList(),
    val notes: Int = 0,
    /** 이미 축하한 습관. 새로 얻은 것만 도착 화면에서 보여 줍니다. */
    val badgesSeen: Set<Badge> = emptySet(),
    /** 지금 입은 모습과 지금 쓰는 풍경. 없으면 기본입니다. */
    val form: Reward? = null,
    val scene: Reward? = null,
    val album: Set<Moment> = emptySet(),
    /** 주워 온 물건. 오래된 것부터입니다. */
    val shelf: List<ReturnGift> = emptyList(),
    /** 아기가 함께 탄 트립 수. 아기가 할 줄 아는 일을 정합니다. 사람별로 세지 않습니다. */
    val familyTrips: Int = 0,
    /** 앨범에 처음 든 시각. 회상 문장의 날짜입니다. 이 값이 생기기 전의 순간은 날짜가 없습니다. */
    val momentAt: Map<Moment, Long> = emptyMap(),
    /** 다음 물건까지 모은 점수. 혼자 탄 트립은 1, 함께 탄 트립은 2를 더하고 3이면 하나가 생깁니다. */
    val itemPoints: Int = 0,
    /** 마지막으로 센 트립. 방금 생긴 물건을 가립니다. */
    val lastTripId: Long = 0L,
    /** 열린 장소, 칸에 놓은 물건, 다녀간 손님. */
    val places: PlaceState = PlaceState(),
    /** 아기가 한 명이라도 함께 탄 날 수. 한국 시간 자정이 경계이고 하루에 한 번만 셉니다. 홈의 돌 줄이 이만큼 늡니다. 사람별로 세지 않습니다. */
    val familyDays: Int = 0,
    /** 마지막으로 아기와 함께 탄 날의 번호([TimeTogether.dayOf]). 같은 날을 두 번 세지 않게 합니다. */
    val lastFamilyDay: Long? = null,
) {
    /** 고른 칸 수. 사다리 순서대로 열던 때의 이름입니다. */
    val claims: Int get() = chosen.size

    val opened: Int get() = chosen.size + notes

    val owned: Set<Reward> get() = chosen.toSet()

    /** 병이 찼을 때 고를 후보. 아직 안 가진 앞쪽 칸이고 사다리 순서입니다. 다 가졌으면 비어 있습니다. */
    fun choices(): List<Reward> = Ladder.RUNGS.filter { it !in chosen }.take(CHOICES)

    /** 첫 후보. 다음 선물을 예고하는 문장에 씁니다. 다 가졌으면 null입니다. */
    val next: Reward? get() = Ladder.RUNGS.firstOrNull { it !in chosen }

    /** 아직 열지 않은 선물 수. 안 열어도 사라지지 않습니다. */
    fun pending(jarsOpened: Int): Int = (jarsOpened - opened).coerceAtLeast(0)

    /** 후보 하나를 고릅니다. 기다리는 병이 없거나 후보가 아니면 그대로입니다. 고르지 않은 칸은 다음 병에 다시 나옵니다. */
    fun choose(reward: Reward, jarsOpened: Int): GiftState =
        if (pending(jarsOpened) > 0 && reward in choices()) claim(reward) else this

    /** 첫 후보를 엽니다. 다 가졌으면 쪽지입니다. 고르는 화면이 생기기 전의 열기입니다. */
    fun open(): GiftState = next?.let(::claim) ?: readNote()

    /** 기다리는 선물이 있을 때만 엽니다. 두 번 눌러도 칸이 병보다 앞서가지 않습니다. */
    fun open(jarsOpened: Int): GiftState = if (pending(jarsOpened) > 0) open() else this

    /** 모습과 풍경은 바로 입혀 줍니다. 벗는 것은 성장 쪽에서 합니다. */
    private fun claim(reward: Reward): GiftState {
        val after = copy(chosen = chosen + reward)
        return when (reward.kind) {
            RewardKind.FORM -> after.copy(form = reward)
            RewardKind.SCENE -> after.copy(scene = reward)
            RewardKind.MOTION, RewardKind.DECOR -> after
        }
    }

    fun wear(reward: Reward?): GiftState = copy(form = reward)

    fun show(reward: Reward?): GiftState = copy(scene = reward)

    /** 다 모은 뒤의 병에서는 구름이의 쪽지가 나옵니다. */
    fun readNote(): GiftState = copy(notes = notes + 1)

    fun nextNote(): String = NOTES[notes % NOTES.size]

    fun remember(moment: Moment): GiftState = if (moment in album) this else copy(album = album + moment)

    /** [before] 뒤에 앨범에 든 순간에 시각을 남깁니다. */
    fun stamped(before: Set<Moment>, atMs: Long): GiftState {
        val fresh = album - before
        return if (fresh.isEmpty()) this else copy(momentAt = momentAt + fresh.associateWith { atMs })
    }

    /** 가진 물건 종류. 처음 얻은 순서이고 겹치지 않습니다. 칸에 놓을 수 있는 것입니다. */
    val kinds: List<ReturnKind> get() = shelf.map { it.kind }.distinct()

    /** 마지막으로 센 트립에 생긴 물건. 그 트립에 생기지 않았으면 null입니다. 혼자 탄 트립도 셉니다. */
    val justBrought: ReturnGift?
        get() = shelf.lastOrNull()?.takeIf { it.tripId != 0L && it.tripId == lastTripId }

    val friendSkill: FriendSkill get() = FriendSkill.entries.last { familyTrips >= it.trips }

    /** 마지막으로 함께 탄 트립에서 오른 단계. 그 트립에 오르지 않았으면 null입니다. */
    val justLearned: FriendSkill? get() = friendSkill.takeIf { it != FriendSkill.BASIC && it.trips == familyTrips }

    /**
     * 도착한 트립을 셉니다. 모든 트립이 물건 점수를 쌓고, 첫 물건이 생기면 강가가 열립니다.
     * 아기가 함께 탔으면 탄 아기가 돌아가며 가져오고, 혼자면 구름이가 주워 옵니다.
     * 짧은 트립은 세지 않습니다. 시동만 켰다 끄며 모으지 못하게 합니다. 트립이 길다고 더 주지 않습니다.
     */
    fun afterTrip(trip: TripSummary, riders: List<FamilySpirit>, atMs: Long): GiftState {
        if (trip.durationMs < TimeTogether.MIN_TRIP_MS) return this
        val counted = if (riders.isEmpty()) copy(lastTripId = trip.tripId)
        else copy(lastTripId = trip.tripId, familyTrips = familyTrips + 1).countDay(TimeTogether.dayOf(atMs)).remember(Moment.THANKS)
        val points = itemPoints + if (riders.isEmpty()) SOLO_POINTS else FAMILY_POINTS
        val brought = when {
            shelf.isEmpty() -> counted.bring(trip.tripId, riders).copy(itemPoints = 0)
            points >= ITEM_POINTS -> counted.bring(trip.tripId, riders).copy(itemPoints = points - ITEM_POINTS)
            else -> counted.copy(itemPoints = points)
        }
        return brought.copy(places = brought.places.arrive(trip.tripId, atMs))
    }

    /** 새 날만 셉니다. 늦게 온 지난 날의 도착은 다시 세지 않습니다. */
    private fun countDay(day: Long): GiftState =
        if (lastFamilyDay != null && day <= lastFamilyDay) this else copy(familyDays = familyDays + 1, lastFamilyDay = day)

    /**
     * 첫 보물은 함께한 순간이라 아기가 가져왔을 때만 앨범에 남깁니다. 앨범 그림에 아기가 있습니다.
     * 혼자 탄 날 구름이가 주운 물건은 선반과 강가에만 놓입니다.
     * 11/6 전 기록에는 혼자 주운 날의 첫 보물이 남아 있을 수 있습니다. 출시 전이라 불러올 때 고치지 않습니다.
     */
    private fun bring(tripId: Long, riders: List<FamilySpirit>): GiftState {
        val kinds = ReturnKind.entries
        val bringer = if (riders.isEmpty()) null else riders[shelf.size % riders.size].preset
        val brought = copy(shelf = shelf + ReturnGift(kinds[shelf.size % kinds.size], bringer, tripId), places = places.open(Place.RIVER, tripId))
        return if (bringer == null) brought else brought.remember(Moment.FIRST_RETURN)
    }

    /** 가진 물건만 칸에 놓습니다. */
    fun put(place: Place, index: Int, kind: ReturnKind): GiftState =
        if (kind in kinds) copy(places = places.put(place, index, kind)) else this

    fun take(place: Place, index: Int): GiftState = copy(places = places.take(place, index))

    fun collect(guest: Guest): GiftState = copy(places = places.collect(guest))

    private companion object {
        const val CHOICES = 3
        const val SOLO_POINTS = 1
        const val FAMILY_POINTS = 2
        const val ITEM_POINTS = 3
        val NOTES = listOf(
            "오늘도 같이 달려 줘서 고마워.",
            "창밖이 좋아서 한참 봤어.",
            "네가 돌아오는 소리를 기억해.",
            "우리 차가 제일 포근해.",
        )
    }
}

/** 차와 가족의 일이 앨범과 선반에 어떻게 남는지. 사람별 횟수는 남기지 않습니다. */
object Memories {
    private const val GATHERED_SPIRITS = 2

    fun onEvent(gifts: GiftState, event: DriveEvent, family: FamilyState): GiftState =
        remembered(gifts, event, family).stamped(gifts.album, event.atMs)

    private fun remembered(gifts: GiftState, event: DriveEvent, family: FamilyState): GiftState = when (event) {
        // 벨트 신호가 없는 차에서도 채울 수 있도록 화면에서 깨운 자리도 셉니다.
        is DriveEvent.SeatsAwake -> if (family.companions(event.seats).isNotEmpty()) gifts.remember(Moment.FIRST_CLICK) else gifts
        is DriveEvent.SeatLeft -> if (family.spiritAt(event.seat) != null) gifts.remember(Moment.SEND_OFF) else gifts
        is DriveEvent.SeatReturned -> if (family.spiritAt(event.seat) != null) gifts.remember(Moment.WELCOME) else gifts
        is DriveEvent.RestTaken -> gifts.remember(Moment.REST_DAY)
        is DriveEvent.TripEnded -> {
            // 탈 아기는 안전벨트를 채울 때 엔진이 그 자리에 앉혀 둡니다. 그래서 채운 자리의 아기가 탄 아기입니다.
            val companions = family.companions(event.summary.seats + event.summary.tappedSeats)
            // 한 사람이 빠져도 나머지가 손해 보지 않도록 정령 전부가 아니라 둘 이상이면 모인 날입니다.
            gifts.afterTrip(event.summary, companions, event.atMs)
                .let { if (companions.size >= GATHERED_SPIRITS) it.remember(Moment.GATHERED) else it }
                .let { if (family.deliveredLine(event.summary.tripId) != null) it.remember(Moment.LETTER) else it }
                .let { if (family.rodeBeside(event.summary)) it.remember(Moment.THANKS) else it }
        }
        else -> gifts
    }
}

/**
 * 좋은 습관을 쌓으면 배우는 동작. 못 얻은 칸은 힌트로 남아 다음 목표가 됩니다.
 * 잃지 않고 기한도 없습니다. 밤, 궂은 날, 장거리처럼 위험한 운전을 부르는 조건은 넣지 않습니다.
 */
enum class Badge(val id: String, val title: String, val hint: String, val target: Int, val reward: Reward) {
    SOFT("soft", "구름 손길", "부드럽게 멈추기", 30, Reward.MOTION_SWAY),
    SIGNAL("signal", "반짝 신호", "깜빡이 먼저 켜기", 50, Reward.MOTION_TILT),
    BELT("belt", "찰칵 친구", "뒷자리 안전벨트", 20, Reward.MOTION_NOD),
    ;

    fun progress(pet: PetState): Int = when (this) {
        SOFT -> pet.counts.softStops
        SIGNAL -> pet.counts.signals
        BELT -> pet.counts.rearBelts
    }

    fun earned(pet: PetState): Boolean = progress(pet) >= target

    companion object {
        fun earned(pet: PetState): List<Badge> = entries.filter { it.earned(pet) }

        /** 구름이가 닮은 습관. 받은 배지 중 목표의 몇 배를 쌓았는지로 고르고, 같으면 앞선 배지입니다. 벌은 없습니다. */
        fun likeness(pet: PetState): Badge? = earned(pet).maxByOrNull { it.progress(pet).toDouble() / it.target }

        fun fromId(id: String): Badge? = entries.firstOrNull { it.id == id }
    }
}

/** 주행거리나 기간으로 챙기는 정비. 주기는 일반적인 기본값이고 차종마다 다릅니다. */
enum class CareItem(val id: String, val title: String, val bodyPart: String, val intervalKm: Int?, val intervalDays: Int?, val reward: Reward?) {
    OIL("oil", "엔진오일 교환", "털 윤기", 10_000, null, Reward.MOTION_SHAKE),
    ROTATION("rotation", "타이어 위치 교환", "발", 10_000, null, Reward.MOTION_HOP),
    FILTER("filter", "에어컨 필터 교체", "숨", 15_000, null, Reward.MOTION_STRETCH),
    WASH("wash", "세차", "맑은 창", null, 14, null),
    ;

    companion object {
        fun fromId(id: String): CareItem? = entries.firstOrNull { it.id == id }
    }
}

enum class CareStatus { OK, SOON, DUE }

data class CareRecord(val item: CareItem, val atMs: Long, val odoKm: Float, val starred: Boolean)

/** 정비 수첩. 앱을 처음 쓴 날의 주행거리를 시작점으로 삼습니다. */
data class CareState(
    val firstOdoKm: Float? = null,
    val records: List<CareRecord> = emptyList(),
    val firstSeenAtMs: Long? = null,
    /** 차가 알려 준 차종. 시작할 때 읽기 전에도 수첩이 맞도록 저장해 둡니다. */
    val kind: VehicleKind = VehicleKind.UNKNOWN,
) {
    val stars: Int get() = records.count { it.starred }
    val lastStarAtMs: Long? get() = records.lastOrNull { it.starred }?.atMs

    fun observe(odoKm: Float): CareState = if (firstOdoKm == null) copy(firstOdoKm = odoKm) else this

    /** 한 번 알게 된 차종은 잠깐 못 읽었다고 잊지 않습니다. */
    fun observe(next: VehicleKind): CareState = if (next == VehicleKind.UNKNOWN) this else copy(kind = next)

    /** 기간으로 세는 항목의 시작점입니다. */
    fun started(atMs: Long): CareState = if (firstSeenAtMs == null) copy(firstSeenAtMs = atMs) else this

    fun has(item: CareItem): Boolean = records.any { it.item == item }

    private fun startOf(item: CareItem): Float? = records.lastOrNull { it.item == item }?.odoKm ?: firstOdoKm

    fun leftKm(item: CareItem, odoKm: Float): Float? {
        val interval = item.intervalKm ?: return null
        return startOf(item)?.let { interval - (odoKm - it) }
    }

    /** 마지막으로 챙긴 뒤 지난 날수. 기간 항목에 씁니다. */
    fun daysSince(item: CareItem, nowMs: Long): Float? {
        val from = records.lastOrNull { it.item == item }?.atMs ?: firstSeenAtMs ?: return null
        return (nowMs - from) / DAY_MS
    }

    /** 주기를 얼마나 썼는지. 1이면 때가 됐습니다. 아직 모르면 null입니다. */
    fun usedRatio(item: CareItem, odoKm: Float, nowMs: Long): Float? {
        item.intervalKm?.let { interval -> return leftKm(item, odoKm)?.let { 1f - it / interval } }
        val interval = item.intervalDays ?: return null
        return daysSince(item, nowMs)?.let { it / interval }
    }

    fun status(item: CareItem, odoKm: Float, nowMs: Long = 0L): CareStatus {
        val used = usedRatio(item, odoKm, nowMs) ?: return CareStatus.OK
        return when {
            used >= 1f -> CareStatus.DUE
            used >= SOON_RATIO -> CareStatus.SOON
            else -> CareStatus.OK
        }
    }

    /** 때가 된 항목만 별을 줍니다. 미리 눌러도 기록은 남고 주기는 다시 시작합니다. */
    fun done(item: CareItem, atMs: Long, odoKm: Float): CareState =
        copy(records = records + CareRecord(item, atMs, odoKm, starred = status(item, odoKm, atMs) != CareStatus.OK))

    /** 수첩에 알릴 항목. 주기를 가장 많이 쓴 것이 먼저입니다. */
    fun nextDue(odoKm: Float, nowMs: Long = 0L): CareItem? =
        CareRules.items(kind)
            .filter { status(it, odoKm, nowMs) != CareStatus.OK }
            .maxByOrNull { usedRatio(it, odoKm, nowMs) ?: 0f }

    private companion object {
        const val SOON_RATIO = 0.9f
        const val DAY_MS = 24 * 60 * 60_000f
    }
}

/**
 * 차 상태가 구름이와 차 안에서 어떻게 보이는지. 아프게 그리지 않고 돌봐 줄 곳만 드러냅니다.
 * 방치해도 더 나빠지지 않고, 해 주면 새 동작을 배웁니다.
 */
data class CareLook(
    /** 앞유리에 쌓인 먼지. 0이면 맑고 1이면 가장 뿌옇습니다. */
    val dust: Float = 0f,
    val dullFur: Boolean = false,
    val soreFoot: Boolean = false,
    val sneezy: Boolean = false,
    val dimLight: Boolean = false,
    val hungry: Boolean = false,
    /** 정비한 뒤 일주일 동안 털이 반짝입니다. */
    val gleaming: Boolean = false,
)

object CareRules {
    /** 경고등을 끈 정비도 수첩의 정비와 같은 동작을 가르쳐 줍니다. */
    private val CODE_REWARDS = mapOf("TPMS" to Reward.MOTION_HOP, "ENGINE_OIL_PRESSURE" to Reward.MOTION_SHAKE)

    /** 이 차에서 챙길 항목. 전기차에는 엔진오일이 없습니다. 차종을 모르면 전부 보여 줍니다. */
    fun items(kind: VehicleKind): List<CareItem> =
        if (kind == VehicleKind.ELECTRIC) CareItem.entries - CareItem.OIL else CareItem.entries

    /** 그 동작을 가르치는 정비. 전기차는 세차가 엔진오일 대신 가르쳐서 영영 잠긴 칸이 남지 않습니다. */
    fun teacher(reward: Reward, kind: VehicleKind): CareItem? =
        if (kind == VehicleKind.ELECTRIC && reward == CareItem.OIL.reward) CareItem.WASH
        else items(kind).firstOrNull { it.reward == reward }

    /** 지금 가진 것 전부. 사다리, 습관, 정비를 합칩니다. 함께한 날의 창밖 풍경은 11/5에 뺐습니다. */
    fun owned(pet: PetState, gifts: GiftState, care: CareState): Set<Reward> {
        val fromBadges = Badge.earned(pet).map { it.reward }
        val fromCare = CareItem.entries.filter { care.has(it) }.mapNotNull { it.reward }
        val fromWash = listOfNotNull(CareItem.OIL.reward.takeIf { care.kind == VehicleKind.ELECTRIC && care.has(CareItem.WASH) })
        val fromCodes = CODE_REWARDS.filterKeys { it in pet.recoveryRewardedAt }.values
        return gifts.owned + fromBadges + fromCare + fromWash + fromCodes
    }

    private const val GLEAM_MS = 7 * 24 * 60 * 60_000L
    private const val DUST_START_DAYS = 7f
    private const val DUST_FULL_DAYS = 21f
    private const val WASHER_DUST = 0.85f

    fun look(pet: PetState, care: CareState, odoKm: Float?, nowMs: Long): CareLook {
        val odo = odoKm ?: care.firstOdoKm ?: 0f
        fun needs(item: CareItem) = item in items(care.kind) && care.status(item, odo, nowMs) != CareStatus.OK
        val days = care.daysSince(CareItem.WASH, nowMs) ?: 0f
        val grime = ((days - DUST_START_DAYS) / (DUST_FULL_DAYS - DUST_START_DAYS)).coerceIn(0f, 0.7f)
        val lastStar = listOfNotNull(care.lastStarAtMs, pet.lastStarAtMs).maxOrNull()
        return CareLook(
            dust = if (PetState.WASHER_CODE in pet.ailments) maxOf(grime, WASHER_DUST) else grime,
            dullFur = "ENGINE_OIL_PRESSURE" in pet.ailments || needs(CareItem.OIL),
            soreFoot = "TPMS" in pet.ailments || care.status(CareItem.ROTATION, odo, nowMs) == CareStatus.DUE,
            sneezy = needs(CareItem.FILTER),
            dimLight = "LOW_VOLT_BATTERY" in pet.ailments,
            hungry = pet.ailments.any { it in PetState.FOOD_CODES },
            gleaming = lastStar != null && nowMs - lastStar < GLEAM_MS,
        )
    }
}

/** 경고등을 수첩에서 부르는 실제 이름. 귀여운 비유 옆에 또렷한 안내를 둡니다. */
object CareTerms {
    private val TITLES = mapOf(
        "TPMS" to "타이어 공기압",
        "ENGINE_OIL_PRESSURE" to "엔진오일 압력",
        "ENGINE_TEMPERATURE" to "엔진 온도",
        "LOW_VOLT_BATTERY" to "12V 배터리",
        "ABS" to "브레이크(ABS)",
        "AIRBAG" to "에어백",
        "EV_SYSTEM" to "전기 시스템",
        "ENGINE" to "엔진",
        "MASTER_WARNING" to "계기판 경고",
        PetState.WASHER_CODE to "워셔액",
        "LOW_FUEL" to "연료",
        "LOW_EV_BATTERY" to "충전",
    )

    fun title(code: String): String = TITLES[code] ?: "차량 점검"
}

/** 선물과 정비 수첩의 파일 형식. 모르는 값은 건너뜁니다. */
object KeepsakeCodec {
    fun encodeGifts(state: GiftState): String {
        val seen = JSONArray()
        state.badgesSeen.forEach { seen.put(it.id) }
        val album = JSONArray()
        state.album.forEach { album.put(it.id) }
        val shelf = JSONArray()
        state.shelf.forEach { gift ->
            val row = JSONObject().put("kind", gift.kind.id)
            gift.bringer?.let { row.put("preset", it.id) }
            if (gift.tripId != 0L) row.put("tripId", gift.tripId)
            shelf.put(row)
        }
        // `claims`는 읽지 않지만 고른 수를 함께 씁니다. 성능 도구가 이 값으로 상태를 적습니다.
        val json = JSONObject().put("chosen", JSONArray(state.chosen.map { it.id })).put("claims", state.claims).put("notes", state.notes)
            .put("badgesSeen", seen).put("album", album).put("shelf", shelf).put("familyTrips", state.familyTrips)
            .put("itemPoints", state.itemPoints).put("lastTripId", state.lastTripId).put("places", PlaceCodec.encode(state.places))
            .put("familyDays", state.familyDays)
        state.lastFamilyDay?.let { json.put("lastFamilyDay", it) }
        val momentAt = JSONObject()
        state.momentAt.forEach { (moment, atMs) -> momentAt.put(moment.id, atMs) }
        json.put("momentAt", momentAt)
        state.form?.let { json.put("form", it.id) }
        state.scene?.let { json.put("scene", it.id) }
        return json.toString()
    }

    /**
     * 11/3 이전 파일은 사다리를 연 칸 수(`claims`)와 함께 탄 트립 수만 있습니다.
     * 연 칸 수는 앞 칸부터 고른 것으로, 함께 탄 트립 수는 다음 물건까지의 점수로 옮깁니다. 물건이 있으면 강가가 열려 있습니다.
     * 11/4에 뺀 칸은 그 자리를 이어받은 칸으로 읽습니다. 뺀 창밖 풍경을 쓰고 있었으면 이어받은 풍경을 쓰고, 풍경이 아니면 기본 창입니다.
     */
    fun decodeGifts(text: String): GiftState {
        val json = JSONObject(text)
        val seen = json.optJSONArray("badgesSeen") ?: JSONArray()
        val album = json.optJSONArray("album") ?: JSONArray()
        val shelfRows = json.optJSONArray("shelf") ?: JSONArray()
        val momentAt = json.optJSONObject("momentAt") ?: JSONObject()
        val familyTrips = json.optInt("familyTrips", 0).coerceAtLeast(0)
        val shelf = (0 until shelfRows.length()).mapNotNull { index ->
            val row = shelfRows.optJSONObject(index) ?: return@mapNotNull null
            val kind = ReturnKind.fromId(row.optString("kind")) ?: return@mapNotNull null
            ReturnGift(kind, SpiritPreset.fromId(row.optString("preset")), row.optLong("tripId", 0L))
        }
        val chosen = json.optJSONArray("chosen")
            ?.let { rows -> (0 until rows.length()).mapNotNull { rung(rows.optString(it)) }.filter { it in Ladder.RUNGS }.distinct() }
            ?: Ladder.RUNGS.take(json.optInt("claims", 0).coerceIn(0, Ladder.RUNGS.size))
        val itemPoints = if (json.has("itemPoints")) json.optInt("itemPoints").coerceIn(0, MAX_POINTS)
        else if (shelf.isEmpty()) 0 else ((familyTrips - 1).coerceAtLeast(0) % OLD_RETURN_EVERY * OLD_FAMILY_POINTS).coerceAtMost(MAX_POINTS)
        val places = json.optJSONObject("places")?.let(PlaceCodec::decode)
            ?: PlaceState().let { if (shelf.isEmpty()) it else it.open(Place.RIVER, 0L) }
        return GiftState(
            chosen = chosen,
            notes = json.optInt("notes", 0).coerceAtLeast(0),
            badgesSeen = (0 until seen.length()).mapNotNullTo(HashSet()) { Badge.fromId(seen.optString(it)) },
            form = Reward.fromId(json.optString("form")),
            scene = rung(json.optString("scene"))?.takeIf { it.kind == RewardKind.SCENE },
            // 앨범은 일어난 순서로 펼칩니다.
            album = (0 until album.length()).mapNotNullTo(LinkedHashSet()) { Moment.fromId(album.optString(it)) },
            shelf = shelf,
            familyTrips = familyTrips,
            momentAt = Moment.entries.filter { momentAt.has(it.id) }.associateWith { momentAt.getLong(it.id) },
            itemPoints = itemPoints,
            lastTripId = json.optLong("lastTripId", 0L),
            places = places,
            // 11/5 전 파일에는 날 수가 없어 함께 탄 트립 수로 어림합니다. 하루에 여러 번 탔으면 실제 날 수보다 큽니다.
            familyDays = if (json.has("familyDays")) json.optInt("familyDays").coerceAtLeast(0) else familyTrips,
            lastFamilyDay = (json.opt("lastFamilyDay") as? Number)?.toLong(),
        )
    }

    private fun rung(id: String): Reward? = Reward.fromId(id)?.let { Ladder.REPLACED[it] ?: it }

    /** 다음 물건까지의 점수는 3이 되면 물건이 되므로 2까지입니다. */
    private const val MAX_POINTS = 2
    private const val OLD_RETURN_EVERY = 3
    private const val OLD_FAMILY_POINTS = 2

    fun encodeCare(state: CareState): String {
        val json = JSONObject().put("kind", state.kind.name)
        state.firstOdoKm?.let { json.put("firstOdoKm", it.toDouble()) }
        state.firstSeenAtMs?.let { json.put("firstSeenAtMs", it) }
        val records = JSONArray()
        state.records.forEach {
            records.put(
                JSONObject().put("item", it.item.id).put("atMs", it.atMs)
                    .put("odoKm", it.odoKm.toDouble()).put("starred", it.starred),
            )
        }
        return json.put("records", records).toString()
    }

    fun decodeCare(text: String): CareState {
        val json = JSONObject(text)
        val records = json.optJSONArray("records") ?: JSONArray()
        return CareState(
            firstOdoKm = if (json.has("firstOdoKm")) json.getDouble("firstOdoKm").toFloat() else null,
            firstSeenAtMs = if (json.has("firstSeenAtMs")) json.getLong("firstSeenAtMs") else null,
            records = (0 until records.length()).mapNotNull { index ->
                val row = records.optJSONObject(index) ?: return@mapNotNull null
                val item = CareItem.fromId(row.optString("item")) ?: return@mapNotNull null
                CareRecord(item, row.optLong("atMs"), row.optDouble("odoKm").toFloat(), row.optBoolean("starred"))
            },
            kind = VehicleKind.fromStored(json.optString("kind")),
        )
    }
}

/** 앱 프로세스에 하나뿐인 소품과 정비 수첩. 원장과 분리해 둡니다. */
object KeepsakeRepository {
    private val lock = Any()

    @Volatile
    private var giftFile: AtomicFile? = null
    private var careFile: AtomicFile? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var gifts = GiftState()
        private set

    @Volatile
    var care = CareState()
        private set

    /** 파일을 다 읽었는지. 읽기 전의 빈 값으로 판단하지 않게 합니다. */
    val ready: Boolean get() = giftFile != null

    /** 차가 알려 준 누적 주행거리. 값이 없으면 null입니다. */
    @Volatile
    var odoKm: Float? = null
        private set

    /** 모든 화면이 메인 스레드에서 부릅니다. 저장 중인 락을 기다리지 않도록 먼저 봅니다. */
    fun init(context: Context) {
        if (giftFile != null) return
        synchronized(lock) {
            if (giftFile != null) return
            val giftsOpened = AtomicFile(File(context.filesDir, "gifts.json"))
            val careOpened = AtomicFile(File(context.filesDir, "care.json"))
            gifts = runCatching { KeepsakeCodec.decodeGifts(String(giftsOpened.readFully())) }.getOrDefault(GiftState())
            care = runCatching { KeepsakeCodec.decodeCare(String(careOpened.readFully())) }.getOrDefault(CareState())
            careFile = careOpened
            if (care.firstSeenAtMs == null) {
                care = care.started(System.currentTimeMillis())
                save(careFile, KeepsakeCodec.encodeCare(care))
            }
            giftFile = giftsOpened
        }
    }

    fun updateGifts(transform: (GiftState) -> GiftState) {
        val changed = synchronized(lock) {
            val next = transform(gifts)
            (next != gifts).also {
                if (it) {
                    gifts = next
                    save(giftFile, KeepsakeCodec.encodeGifts(next))
                }
            }
        }
        if (changed) listeners.forEach { it() }
    }

    fun onOdometer(km: Float) {
        val changed = synchronized(lock) {
            val moved = odoKm != km
            odoKm = km
            val next = care.observe(km)
            if (next != care) {
                care = next
                save(careFile, KeepsakeCodec.encodeCare(next))
            }
            moved
        }
        if (changed) listeners.forEach { it() }
    }

    fun onVehicleKind(kind: VehicleKind) {
        val changed = synchronized(lock) {
            val next = care.observe(kind)
            (next != care).also {
                if (it) {
                    care = next
                    save(careFile, KeepsakeCodec.encodeCare(next))
                }
            }
        }
        if (changed) listeners.forEach { it() }
    }

    fun markDone(item: CareItem, atMs: Long) {
        synchronized(lock) {
            val odo = odoKm ?: care.firstOdoKm ?: 0f
            care = care.done(item, atMs, odo)
            save(careFile, KeepsakeCodec.encodeCare(care))
        }
        listeners.forEach { it() }
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun save(file: AtomicFile?, text: String) = DiskWriter.save(file, text)
}
