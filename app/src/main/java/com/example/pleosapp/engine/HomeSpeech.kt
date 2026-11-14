package com.example.pleosapp.engine

/**
 * 홈에서 구름이가 건네는 한마디. 설명문 대신 이 말풍선이 지금 무슨 일이 일어나는지 알려 줍니다.
 * 낮고 차분한 혼잣말에 가깝게 씁니다. 일을 시키지 않고, 초대만 합니다.
 */
object HomeSpeech {
    const val HELLO = "안녕, 나는 이 차에 사는 구름이야.\n같이 달리면 깃털을 주워 올게."

    /** 처음 켠 날의 둘째 마디. 깃털과 병이 무엇에 쓰이는지 말합니다. */
    const val FIRST_JAR = "깃털이 저 병에 가득 차면\n선물을 하나 골라 줘."

    /** 처음 켠 날의 셋째 마디. 처음 누를 곳을 말합니다. 세 마디를 본 뒤 아기를 들이거나 처음 달리기 전까지 이 말을 둡니다. */
    const val FIRST_ADOPT = "아기 데려오기를 눌러 봐.\n같이 탈 친구가 와."

    /** 처음 켠 날 차례로 하는 세 마디. 넘김과 멈춤은 화면이 정합니다(docs/guide/first-run.md 3장). */
    val FIRST_RUN = listOf(HELLO, FIRST_JAR, FIRST_ADOPT)

    const val INVITE = "오늘은 누구랑 같이 갈까?"
    const val WAITING = "친구들은 아직 자고 있어.\n안전벨트를 매면 깨어날 거야."

    /**
     * 낮의 말. 11/5부터 안 탄 아기는 낮에도 둥지에서 잡니다(배경 v2). 논다고 말하면 그림과 어긋납니다.
     * 이름 PLAYING은 낮에 놀던 옛 그림 때 붙였습니다.
     */
    const val PLAYING = "친구들은 둥지에서 자고 있어.\n안전벨트를 매면 깨어날 거야."
    const val MORNING = "좋은 아침이야.\n오늘은 어디로 가?"
    const val DAY = "왔구나! 반가워."
    const val EVENING = "오늘도 수고했어."
    const val NIGHT = "늦었네.\n조심히 들어가자."
    const val RAIN = "비가 오네.\n오늘은 천천히 가자."
    const val SNOW = "길이 얼었나 봐.\n조심히 가자."
    const val FOG = "안개가 짙어.\n앞차와 거리를 두고 가자."
    const val DIZZY = "아까 조금 어지러웠어.\n출발하면 괜찮아질 거야."
    const val GIFT = "병이 가득 찼어.\n선물을 열어 볼래?"
    const val GIFT_NIGHT = "선물은 내일 열어도 돼."
    const val WASHER = "앞유리가 조금 뿌예.\n워셔액을 채워 주면 좋겠어."
    const val FUEL = "배가 고파.\n주유할 때가 됐어."
    const val CHARGE = "배가 고파.\n충전할 때가 됐어."
    const val FED = "배불러.\n채워 줘서 고마워."

    const val PET = "좋아.\n조금만 더."

    /** 찌를 때 돌아가며 하는 말. 찔러도 깃털은 늘지 않아 찾았다고 하지 않습니다. */
    val POKES = listOf("간지러워.", "같이 있으니까 좋아.", "깃털 냄새가 나.")

    /** 맑은 아침과 낮에만 섞는 말입니다. 비 오는 밤에 하늘이 좋다고 하지 않습니다. */
    const val SKY_POKE = "오늘 하늘이 참 좋다."

    /** 지금 장면에서 찌를 때 할 말. 맑은 아침·낮에는 하늘 말이 둘째 자리에 들어갑니다. */
    fun pokes(scene: SceneState): List<String> {
        val clearDay = scene.sky == Sky.CLEAR && (scene.part == DayPart.MORNING || scene.part == DayPart.DAY)
        return if (clearDay) POKES.take(1) + SKY_POKE + POKES.drop(1) else POKES
    }

    /** 지난 여정에서 찍힌 도장. 잘한 일만 찍고, 못한 일을 따로 표시하지 않습니다. */
    data class Stamps(val belt: Boolean, val softStop: Boolean, val blinker: Boolean, val rest: Boolean)

    fun careLine(item: CareItem): String = "${item.title}할 때가 됐어.\n수첩에 적어 뒀어."

    /**
     * 운전 습관이 구름이의 버릇이 됐다는 한마디. 흉내말과 춤은 "~을 하다"가 어색해 배지마다 씁니다.
     * 뒷자리 안전벨트는 운전자가 아니라 뒤에 탄 가족이 채우므로 가족을 닮습니다.
     */
    fun likenessLine(badge: Badge): String = when (badge) {
        Badge.SIGNAL -> "너를 닮아서 고개를 자주 갸우뚱해."
        Badge.SOFT -> "너를 닮아서 살랑살랑 춤을 자주 춰."
        Badge.BELT -> "가족을 닮아서 ${Josa.objective(badge.reward.title)} 자주 해."
    }

    /**
     * 지금 홈 말풍선.
     * [introSeen]은 처음 켠 날 세 마디를 끝냈거나 멈췄는지입니다(화면 기억 `Seen`). 기본값은 거짓이라 첫인사를 합니다.
     */
    fun line(
        pet: PetState,
        family: FamilyState,
        awake: Set<SeatSlot>,
        scene: SceneState = SceneState(DayPart.DAY, Sky.CLEAR),
        giftsWaiting: Int = 0,
        careDue: CareItem? = null,
        introSeen: Boolean = false,
    ): String {
        val awakeNames = family.spirits.filter { it.seat in awake }.map { it.preset.displayName }
        return when {
            pet.mood == Mood.SICK -> pet.ailmentLine().orEmpty().replaceFirst(". ", ".\n")
            pet.mood == Mood.DIZZY -> DIZZY
            awakeNames.isNotEmpty() && awakeNames.size == family.spirits.size && awakeNames.size >= 2 -> FamilySpeech.GATHERED
            awakeNames.isNotEmpty() -> FamilySpeech.woke(awakeNames)
            pet.trips == 0 && family.spirits.isEmpty() -> if (introSeen) FIRST_ADOPT else HELLO
            pet.softNeed != null -> softLine(pet.softNeed)
            pet.fed -> FED
            careDue != null -> careLine(careDue)
            scene.sky != Sky.CLEAR -> skyLine(scene.sky)
            giftsWaiting > 0 -> giftLine(scene.part)
            pet.trips >= INVITE_TRIPS -> greeting(scene.part, pet)
            family.spirits.isNotEmpty() -> if (scene.part == DayPart.NIGHT) WAITING else PLAYING
            else -> INVITE
        }
    }

    /** 밤에는 선물을 권하지 않습니다. 쉬러 가는 사람을 붙잡지 않습니다. */
    fun giftLine(part: DayPart): String = if (part == DayPart.NIGHT) GIFT_NIGHT else GIFT

    /** 수첩을 펼쳤을 때 구름이의 말. */
    fun careBookLine(stars: Int, needsCare: Boolean): String = when {
        needsCare -> "챙겨 줄 게 있어.\n같이 볼래?"
        stars == 0 -> "돌봐 줄 때마다\n여기에 별이 늘어나."
        stars == 1 -> "첫 별이 생겼어.\n고마워."
        else -> "별이 ${stars}개나 모였어.\n늘 고마워."
    }

    /** 수첩에서 구름이가 말하는 지금 몸. 정비가 왜 필요한지 몸으로 풀어 줍니다. */
    fun careFeeling(item: CareItem, status: CareStatus): String {
        val fine = status == CareStatus.OK
        return when (item) {
            CareItem.OIL -> if (fine) "털이 보드라워." else "털이 조금 푸석해."
            CareItem.ROTATION -> if (fine) "발이 가벼워." else "발이 조금 무거워."
            CareItem.FILTER -> if (fine) "숨쉬기가 편해." else "코가 간질간질해."
            CareItem.WASH -> if (fine) "창밖이 잘 보여." else "창이 조금 뿌예."
        }
    }

    /** 차를 챙겨 준 직후의 한마디. 소품이 생긴 것을 말해 줍니다. */
    fun careThanks(item: CareItem?): String = when (item) {
        CareItem.OIL -> "털이 보드라워졌어.\n부르르 털어 볼래."
        CareItem.ROTATION -> "발이 가벼워졌어.\n콩콩 뛰어 볼래."
        CareItem.FILTER -> "숨쉬기가 편해졌어.\n기지개 켜야지."
        CareItem.WASH -> "앞이 환해졌어.\n고마워!"
        null -> "몸이 가벼워졌어.\n고마워!"
    }

    /**
     * 새로 얻었을 때의 한마디. 소품과 풍경은 놓이는 곳이 저마다 달라 따로 말합니다.
     * 공개 카드 제목이 이름을 보여 주므로 모습은 이름을 되풀이하지 않습니다.
     */
    fun rewardLine(reward: Reward): String = REWARD_LINES[reward] ?: when (reward.kind) {
        RewardKind.FORM -> "새로 차려입었어.\n어때?"
        RewardKind.MOTION -> "${Josa.objective(reward.title)} 배웠어.\n눌러 봐."
        RewardKind.DECOR -> "${Josa.subject(reward.title)} 생겼어.\n선반에 놓아 둘게."
        RewardKind.SCENE -> "오늘은 ${Josa.objective(reward.title)} 보고 있어."
    }

    /**
     * 구름이가 하는 말이라 "구름이가 엮은"처럼 제 이름을 넣지 않습니다. 받침을 말하지 않는 것은 자리가 바뀔 수 있어서입니다.
     * 흉내말 이름(귀 쫑긋, 꼬리 톡톡)은 "~을 배웠어"가 어색해 하는 일을 말합니다. 콧노래는 기본 문장 그대로입니다.
     */
    private val REWARD_LINES = mapOf(
        Reward.FORM_READ to "앨범을 펼쳐 봤어.\n어때?",
        Reward.MOTION_FLAP to "귀를 쫑긋 세울 수 있어.\n눌러 봐.",
        Reward.MOTION_WAG to "꼬리로 톡톡 칠 수 있어.\n눌러 봐.",
        Reward.DECOR_LAMP to "실내등이 노을빛이 됐어.\n저녁이면 따뜻해.",
        // "가지"만 쓰면 채소로도 읽힙니다.
        Reward.DECOR_MOBILE to "나뭇가지 모빌을 엮었어.\n깃털이 바람에 살랑일 거야.",
        Reward.DECOR_TRAY to "보물 접시가 생겼어.\n주워 온 걸 담아 둘게.",
        Reward.DECOR_VASE to "들꽃을 컵에 꽂았어.\n향이 좋아.",
        Reward.DECOR_FEATHERBED to "둥지에 깃털을 깔았어.\n아기들이 푹 자겠다.",
        // 나비는 맑은 날 낮에 집 창밖에만 나옵니다. 강가에는 돛배만 있습니다.
        Reward.SCENE_BUTTERFLY to "창밖에 나비가 놀러 왔어.\n맑은 날 낮에 보여.",
        Reward.SCENE_BOAT to "작은 돛배를 띄웠어.\n강가에 떠 있어.",
    )

    private fun softLine(code: String?): String = when (code) {
        "LOW_FUEL" -> FUEL
        "LOW_EV_BATTERY" -> CHARGE
        else -> WASHER
    }

    private fun skyLine(sky: Sky): String = when (sky) {
        Sky.RAIN -> RAIN
        Sky.SNOW -> SNOW
        Sky.FOG -> FOG
        Sky.CLEAR -> DAY
    }

    /** 함께한 날도 가끔 말합니다. 사흘에 한 번이면 세는 말로 들리지 않습니다. 닮은 습관이 있으면 그 차례를 번갈아 씁니다. */
    private fun greeting(part: DayPart, pet: PetState): String {
        val days = pet.together.days
        val turn = days >= TOGETHER_FROM && days % TOGETHER_EVERY == 0
        val likeness = Badge.likeness(pet)?.takeIf { turn && days % (TOGETHER_EVERY * 2) == 0 }
        return when {
            part == DayPart.NIGHT -> NIGHT
            likeness != null -> likenessLine(likeness)
            turn -> "벌써 ${days}일째 같이 달렸어."
            part == DayPart.MORNING -> MORNING
            part == DayPart.EVENING -> EVENING
            else -> DAY
        }
    }

    fun stamps(pet: PetState): Stamps {
        val trip = pet.lastTrip ?: return Stamps(belt = false, softStop = false, blinker = false, rest = false)
        return Stamps(
            belt = trip.driverBelted || pet.tripBelted || trip.rearBelts > 0,
            softStop = trip.smoothStops > 0,
            blinker = trip.signalsUsed > 0,
            rest = trip.restedBefore,
        )
    }

    /** 혼자 타는 운전자에게 계속 조르지 않도록 처음 몇 번만 권합니다. */
    private const val INVITE_TRIPS = 5
    private const val TOGETHER_FROM = 7
    private const val TOGETHER_EVERY = 3
}
