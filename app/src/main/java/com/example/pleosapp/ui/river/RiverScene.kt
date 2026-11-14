package com.example.pleosapp.ui.river

import com.example.pleosapp.engine.DayPart
import com.example.pleosapp.engine.SceneState
import com.example.pleosapp.engine.Season
import com.example.pleosapp.engine.Sky
import com.example.pleosapp.ui.game.HomeScene

/**
 * 강가 장면의 자리. 그림(`place_river`)과 같은 1536×1024 좌표입니다.
 * 왼쪽 앞은 구름이와 아기 자리이고, 오른쪽 강둑에 물건 칸 넷이 완만한 호로 놓입니다(docs/design/river-spec.md 9장).
 */
object RiverScene {
    const val WIDTH = 1536f
    const val HEIGHT = 1024f

    /** 밖이라 집 안보다 조금 작게 섭니다. 칸 쪽 강둑이 가려지지 않습니다. */
    val HERO = HomeScene.Spot(400f, 990f, 360f)

    /** 함께 탄 아기 자리. 강가에는 둘까지 나옵니다. 넷이 다 오면 칸 앞을 가립니다. */
    val FRIENDS = listOf(HomeScene.Spot(675f, 1005f, 140f), HomeScene.Spot(150f, 1000f, 140f))

    /**
     * 물건 칸. 그림 속 납작한 돌, 이끼, 그루터기, 연잎의 윗면에 물건 바닥을 맞췄습니다.
     * 높이는 놓인 물건의 키입니다.
     */
    val SLOTS = listOf(
        HomeScene.Spot(735f, 860f, 74f), HomeScene.Spot(910f, 836f, 70f),
        HomeScene.Spot(1107f, 830f, 70f), HomeScene.Spot(1310f, 862f, 72f),
    )

    /** 손님은 좋아하는 물건 곁 풀밭에 섭니다. 연잎 칸은 화면 오른쪽 끝이 잘려서 왼쪽에 섭니다. */
    fun guestBeside(slot: Int): HomeScene.Spot {
        val spot = SLOTS[slot]
        val side = if (slot == SLOTS.lastIndex) -GUEST_SIDE else GUEST_SIDE
        return HomeScene.Spot(spot.x + side, spot.bottom + GUEST_FRONT, GUEST_HEIGHT)
    }

    /**
     * 구름이가 띄운 돛배(R-124, 사다리 10칸). 선체 가운데가 물 위 x 720에 뜨고 물에 닿는 선은 y 690입니다.
     * 선체 길이는 강가 아기 키(140)의 1.2배인 약 170입니다. 그림 폭 454 가운데 선체가 약 340이라 그림 폭을 227로 그립니다.
     * 댐, 징검돌, 물건 칸, 연잎을 가리지 않습니다.
     */
    const val BOAT_X = 720f
    const val BOAT_WATER = 690f
    const val BOAT_LENGTH = 227f

    /** 손님이 떨어뜨린 흔적 자리. 칸 앞 풀밭입니다. 오래된 흔적부터 왼쪽에 놓입니다. */
    val TRACES = listOf(
        HomeScene.Spot(800f, 960f, 46f), HomeScene.Spot(985f, 945f, 46f),
        HomeScene.Spot(1170f, 955f, 46f), HomeScene.Spot(1330f, 975f, 46f),
    )

    /** 강가 그림과 그 위에 씌울 빛. [tint]가 null이면 그림 그대로입니다. */
    data class Look(val art: String, val tint: Int?)

    /**
     * 지금 시간과 계절에 맞는 강가 그림(R-096). 계절이 먼저입니다. 빙판 경고가 켜진 날은 계절과 상관없이 겨울입니다.
     * 저녁과 밤 그림은 봄여름 것뿐이라 가을과 겨울에는 계절 그림에 빛을 씌웁니다. 밤에 여름 강가가 나오면 안 됩니다(처음 보는 눈 점검 49).
     * 비 오는 날도 같은 강가에 흐린 빛을 씌웁니다. 다섯 장 모두 칸 자리가 같아 좌표 표는 하나입니다.
     * 집의 창밖도 이 그림을 씁니다.
     */
    fun look(state: SceneState, season: Season): Look {
        val seasonal = when {
            state.sky == Sky.SNOW || season == Season.WINTER -> "place_river_winter"
            season == Season.AUTUMN -> "place_river_autumn"
            else -> null
        }
        return when {
            seasonal == null && state.part == DayPart.NIGHT -> Look("place_river_night", null)
            seasonal == null && state.part == DayPart.EVENING -> Look("place_river_evening", null)
            else -> Look(seasonal ?: "place_river", lightOver(state))
        }
    }

    /** 낮 그림에 씌울 시간과 날씨의 빛. */
    private fun lightOver(state: SceneState): Int? = when {
        state.part == DayPart.NIGHT -> NIGHT_LIGHT
        state.part == DayPart.EVENING -> EVENING_LIGHT
        state.sky == Sky.RAIN -> RAIN_LIGHT
        state.part == DayPart.MORNING -> MORNING_LIGHT
        else -> null
    }

    const val NIGHT_LIGHT = 0x4E4E86
    const val EVENING_LIGHT = 0xFFC8A0
    const val RAIN_LIGHT = 0xB8BCC8
    const val MORNING_LIGHT = 0xFFEBD2

    // 칸 사이가 약 175라 옆으로 70만 비켜 앞쪽 풀밭에 섭니다. 더 비키면 이웃 칸 물건을 가립니다.
    private const val GUEST_SIDE = 70f
    private const val GUEST_FRONT = 55f
    const val GUEST_HEIGHT = 120f
}
