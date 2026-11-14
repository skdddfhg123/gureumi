package com.example.pleosapp.engine

import java.util.Base64

/**
 * 폰으로 보내는 엽서 한 장. 이름, 위치, 차대번호, 사진은 담지 않습니다.
 * 엽서는 번호만 담고, 그림과 문장은 폰 페이지가 가지고 있습니다. share/index.html의 표와 순서가 같아야 합니다.
 */
data class Postcard(
    /** 달력 날짜. 2026년 1월 1일에서 지난 날 수입니다. */
    val day: Int,
    val level: Int,
    val stage: Int,
    val form: Reward? = null,
    val scene: Reward? = null,
    val part: DayPart = DayPart.DAY,
    val aboard: Set<SpiritPreset> = emptySet(),
    val moment: Moment? = null,
    val line: Int = 0,
    /** 함께 달린 날 수. 시간이 쌓인 흔적입니다. */
    val together: Int = 0,
)

/** 엽서에 적는 구름이의 한마디. 폰 페이지도 같은 순서로 가지고 있습니다. */
object PostcardLines {
    val ALL = listOf(
        "오늘도 같이 와 줘서 고마워.",
        "다 같이 타서 참 좋았어.",
        "창밖이 예뻐서 한참 봤어.",
        "살살 달려 줘서 포근했어.",
        "친구가 주워 온 걸 선반에 올려 뒀어.",
        "오늘도 깃털을 주워 왔어.",
        "차를 챙겨 줘서 몸이 가벼워.",
        "우리가 함께한 날이 또 늘었어.",
    )

    const val THANKS = 0
    const val TOGETHER = 1
    const val WINDOW = 2
    const val GENTLE = 3
    const val RETURN = 4
    const val FEATHERS = 5
    const val CARE = 6
    const val DAYS = 7

    /** 오늘 있었던 일에서 하나를 고릅니다. 없던 일을 쓰지 않습니다. */
    fun pick(trip: TripSummary?, companions: Int, broughtToday: Boolean): Int = when {
        broughtToday -> RETURN
        companions >= 2 -> TOGETHER
        trip == null -> FEATHERS
        trip.smoothStops >= GENTLE_STOPS -> GENTLE
        companions == 1 -> THANKS
        else -> FEATHERS
    }

    private const val GENTLE_STOPS = 5
}

/** 짧은 주소에 담는 형식. 번호가 바뀌면 옛 엽서가 달라지므로 표의 뒤에만 더합니다. */
object PostcardCodec {
    private const val VERSION = 1
    private const val SIZE = 13
    // 번호가 엽서 주소에 들고 웹 엽서(share/index.html)도 같은 표를 씁니다.
    // 사다리에서 뽑으면 칸을 뺄 때 번호가 당겨집니다. 고정해 두고 뒤에만 더합니다.
    private val FORMS = listOf(Reward.FORM_READ, Reward.FORM_SCARF, Reward.FORM_CAPE, Reward.FORM_APRON, Reward.FORM_RAINCOAT, Reward.FORM_OVERALLS)
    private val SCENES = listOf(
        Reward.SCENE_SEA, Reward.SCENE_WINTER, Reward.SCENE_FOREST, Reward.SCENE_LIGHTHOUSE, Reward.SCENE_FIELD, Reward.SCENE_STARS,
        Reward.SCENE_BUTTERFLY, Reward.SCENE_BOAT,
    )
    private val PRESETS = SpiritPreset.entries
    private val MOMENTS = Moment.entries

    fun encode(card: Postcard): String {
        val bytes = ByteArray(SIZE)
        bytes[0] = VERSION.toByte()
        bytes[1] = (card.day shr 8).toByte()
        bytes[2] = card.day.toByte()
        bytes[3] = card.level.coerceIn(0, 255).toByte()
        bytes[4] = card.stage.coerceIn(0, 255).toByte()
        bytes[5] = (FORMS.indexOf(card.form) + 1).toByte()
        bytes[6] = (SCENES.indexOf(card.scene) + 1).toByte()
        bytes[7] = card.part.ordinal.toByte()
        bytes[8] = PRESETS.foldIndexed(0) { index, bits, preset -> if (preset in card.aboard) bits or (1 shl index) else bits }.toByte()
        bytes[9] = (MOMENTS.indexOf(card.moment) + 1).toByte()
        bytes[10] = card.line.toByte()
        val days = card.together.coerceIn(0, 0xFFFF)
        bytes[11] = (days shr 8).toByte()
        bytes[12] = days.toByte()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** 깨졌거나 모르는 형식이면 null입니다. */
    fun decode(text: String): Postcard? {
        val bytes = try {
            Base64.getUrlDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.size != SIZE || bytes[0].toInt() != VERSION) return null
        fun byte(i: Int) = bytes[i].toInt() and 0xFF
        return Postcard(
            day = (byte(1) shl 8) or byte(2),
            level = byte(3),
            stage = byte(4),
            form = FORMS.getOrNull(byte(5) - 1),
            scene = SCENES.getOrNull(byte(6) - 1),
            part = DayPart.entries.getOrNull(byte(7)) ?: return null,
            aboard = PRESETS.filterIndexedTo(LinkedHashSet()) { index, _ -> byte(8) and (1 shl index) != 0 },
            moment = MOMENTS.getOrNull(byte(9) - 1),
            line = byte(10),
            together = (byte(11) shl 8) or byte(12),
        )
    }

    /** 2026년 1월 1일에서 지난 날 수. 한국 시간 자정이 경계입니다. */
    fun dayOf(atMs: Long): Int = ((atMs + DAY_OFFSET_MS) / DAY_MS - EPOCH_DAY).toInt().coerceIn(0, 0xFFFF)

    private const val DAY_OFFSET_MS = 9 * 60 * 60_000L
    private const val DAY_MS = 24 * 60 * 60_000L

    /** 1970년 1월 1일에서 2026년 1월 1일까지의 날 수. */
    private const val EPOCH_DAY = 20454L
}
