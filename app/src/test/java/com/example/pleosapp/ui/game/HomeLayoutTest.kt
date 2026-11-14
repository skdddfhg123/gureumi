package com.example.pleosapp.ui.game

import com.example.pleosapp.ui.CardLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 홈 장면을 넣기 전에 스스로 보는 점검(docs/design/home-reset-2026-11-04.md 6장).
 * 놓인 것은 받침 면 위에, 정령은 마루에, 크기는 깊이대로, 글자와 누를 곳은 기준 이상입니다.
 * 기준 창은 에뮬레이터의 앱 화면 1680×1209이고, 160dpi라 1dp가 1px입니다.
 */
class HomeLayoutTest {

    private val scale = maxOf(REF_WIDTH / HomeScene.WIDTH, REF_HEIGHT / HomeScene.HEIGHT)

    private val MOBILE_HOOP_HALF = 48f

    private val shelfThings = mapOf(
        "깃털 병" to HomeScene.JAR,
        "화분" to HomeScene.FERN,
        "들꽃 컵" to HomeScene.VASE,
    ) + HomeScene.STONE_ROW.mapIndexed { i, spot -> "돌 줄 $i" to spot }

    private val floorThings = mapOf(
        "앨범" to HomeScene.ALBUM,
        "정비 수첩" to HomeScene.CAREBOOK,
        "귀환품 접시" to HomeScene.TRAY,
        "백설기" to HomeScene.PARTY,
    )

    private val spirits = mapOf("구름이" to HomeScene.HERO) +
        HomeScene.BABIES.mapIndexed { i, spot -> "아기 $i" to spot } +
        HomeScene.ARRIVAL.mapIndexed { i, spot -> "도착 $i" to spot } +
        HomeScene.NARROW.mapIndexed { i, spot -> "좁은 창 $i" to spot } +
        HomeScene.TIGHT.mapIndexed { i, spot -> "아주 좁은 창 $i" to spot }

    /** 들꽃 컵은 돌 줄 끝과 매단 모빌 테 오른쪽에 섭니다. 컵 폭은 키보다 넓지 않고, 모빌 테 반폭은 약 48입니다(그림 261×823, 키 431). */
    @Test
    fun vaseStaysClearOfStonesAndMobile() {
        val vaseLeft = HomeScene.VASE.x - HomeScene.VASE.height / 2f
        val lastStone = HomeScene.STONE_ROW.last()
        assertTrue("들꽃 컵 왼쪽($vaseLeft)이 돌 줄 끝(${lastStone.x})에 붙음", vaseLeft > lastStone.x + lastStone.height)
        assertTrue("들꽃 컵 왼쪽($vaseLeft)이 모빌 테와 겹침", vaseLeft > HomeScene.MOBILE_HOOK_X + MOBILE_HOOP_HALF)
        assertTrue("들꽃 컵이 옆문(1300)에 걸림", HomeScene.VASE.x + HomeScene.VASE.height / 2f < 1300f)
    }

    @Test
    fun shelfThingsStandOnShelves() {
        shelfThings.forEach { (name, spot) ->
            assertTrue("$name 발끝(${spot.x}, ${spot.bottom})이 선반 위에 없음", HomeScene.SHELVES.any { it.holds(spot) })
        }
    }

    @Test
    fun floorThingsAndSpiritsStandOnTheFloor() {
        (floorThings + spirits).forEach { (name, spot) ->
            assertTrue("$name 발끝(${spot.x}, ${spot.bottom})이 마루 위에 없음", HomeScene.FLOORS.any { it.holds(spot) })
        }
    }

    @Test
    fun mobileHangsFromTheHandleAndTouchesTheShelf() {
        // 매단 것은 걸이 선에 닿습니다(홈 다시 세우기 6장). 받침 없이 뜬 모빌은 F-56입니다.
        val mobile = HomeScene.MOBILE
        assertEquals("모빌 윗끝이 손잡이", HomeScene.MOBILE_HOOK_Y, mobile.bottom - mobile.height, 0.5f)
        assertEquals(HomeScene.MOBILE_HOOK_X, mobile.x, 0.5f)
        assertTrue("모빌 아랫끝(${mobile.bottom})이 선반 윗면에 닿지 않음", HomeScene.SHELF.holds(mobile))
    }

    @Test
    fun sleepingBabiesLieInsideTheNest() {
        HomeScene.NEST_BEDS.forEachIndexed { i, bed ->
            assertTrue("둥지 자리 $i (${bed.x}, ${bed.bottom})가 둥지 밖", HomeScene.NEST.holds(bed))
        }
    }

    @Test
    fun awakeSpiritsStayOutOfTheNest() {
        // 둥지는 배경에 그려져 있어, 그 앞에 앉은 깬 정령은 둥지 위에 떠 보입니다. 둥지 앞(발이 둥지 아래끝보다 앞)은 괜찮습니다.
        val (left, _, right, bottom) = HomeScene.NEST_BOX.toList()
        spirits.forEach { (name, spot) ->
            val half = spot.height * PAINTED_HALF
            val beside = spot.x + half <= left || spot.x - half >= right
            assertTrue("$name 이(가) 둥지 자리에 앉음", beside || spot.bottom > bottom)
        }
    }

    @Test
    fun spiritsNeverStandOnShelves() {
        spirits.forEach { (name, spot) ->
            assertTrue("$name 이(가) 선반 위에 있음", HomeScene.SHELVES.none { it.holds(spot) })
        }
    }

    @Test
    fun nothingStandsOnTheWindowSill() {
        // 창턱(창 유리 바로 밑)에 선 정령은 창밖에 떠 보입니다.
        spirits.forEach { (name, spot) ->
            assertTrue("$name 발끝이 창턱 높이(${spot.bottom})", spot.bottom > HomeScene.WINDOW_BOTTOM + 60f)
        }
    }

    @Test
    fun depthFollowsTheRule() {
        assertEquals(0.85f, HomeScene.depth(HomeScene.DEPTH_FAR), 1e-4f)
        assertEquals(1f, HomeScene.depth(HomeScene.DEPTH_MID), 1e-4f)
        assertEquals(1.1f, HomeScene.depth(HomeScene.DEPTH_NEAR), 1e-4f)
        var before = 0f
        for (y in 500..1024 step 4) {
            val depth = HomeScene.depth(y.toFloat())
            assertTrue("깊이 배율이 범위 밖: $y → $depth", depth in 0.85f..1.1f)
            assertTrue("깊이 배율이 앞으로 갈수록 작아짐: $y", depth >= before)
            before = depth
        }
    }

    @Test
    fun babiesAreAboutHalfTheHeroAtTheSameDepth() {
        val hero = HomeScene.HERO.height
        assertTrue("1.0배 깊이의 아기 키는 구름이의 45~50%", HomeScene.BABY_HEIGHT in hero * 0.45f..hero * 0.5f)
        (HomeScene.BABIES + HomeScene.ARRIVAL + HomeScene.NARROW + HomeScene.TIGHT + HomeScene.NEST_BEDS).forEach { spot ->
            assertEquals("발끝 ${spot.bottom}의 아기 키", HomeScene.BABY_HEIGHT * HomeScene.depth(spot.bottom), spot.height, 0.01f)
        }
    }

    @Test
    fun shelfThingsAreSmallerThanHalfTheHero() {
        shelfThings.forEach { (name, spot) ->
            assertTrue("$name 키 ${spot.height}가 구름이 키의 절반을 넘음", spot.height <= HomeScene.HERO.height / 2f)
        }
    }

    @Test
    fun babiesDoNotStandOnEachOther() {
        // 앞줄과 뒷줄이 위아래로 겹치지 않거나, 옆으로 떨어져 있습니다.
        // 같은 줄(발 차이 20 안)은 나란히 붙어 앉아도 됩니다. 꼬리와 손이 조금 겹치되 몸 가운데는 떨어집니다.
        val babies = HomeScene.BABIES
        for (a in babies.indices) for (b in a + 1 until babies.size) {
            val one = babies[a]
            val two = babies[b]
            val gap = kotlin.math.abs(one.x - two.x)
            val sameRow = kotlin.math.abs(one.bottom - two.bottom) < SAME_ROW
            val apart = gap > (one.height + two.height) * 0.45f || (sameRow && gap > (one.height + two.height) / 2f * SIDE_BY_SIDE)
            val front = maxOf(one, two, compareBy { it.bottom })
            val back = minOf(one, two, compareBy { it.bottom })
            val stacked = front.bottom - front.height >= back.bottom - STACK_SLACK
            assertTrue("아기 $a, $b 가 겹쳐 앉음", apart || stacked)
        }
    }

    @Test
    fun textMeetsTheSizeRule() {
        assertTrue(TextRules.MAIN >= 32f)
        assertTrue(TextRules.SMALL >= 24f)
        assertTrue(TextRules.BUBBLE >= 36f)
    }

    @Test
    fun inkKeepsContrastDayAndNight() {
        val paper = GameStyle.PAPER and 0xFFFFFF
        val nightPaper = TextRules.lit(paper, TextRules.PAPER_DIM_LIGHT)
        val pairs = mapOf(
            "글자 / 종이" to (GameStyle.INK to paper),
            "글자 / 밤 종이" to (GameStyle.INK to nightPaper),
            "글자 / 밤 말풍선" to (GameStyle.INK to (GameStyle.PAPER_NIGHT and 0xFFFFFF)),
            "보조 글자 / 종이" to (GameStyle.INK_SOFT to paper),
            "카드 보조 글자 / 카드" to (rgb(CardLayout.MUTED) to rgb(CardLayout.BACKGROUND)),
            "카드 글자 / 카드" to (rgb(CardLayout.TEXT) to rgb(CardLayout.BACKGROUND)),
        )
        pairs.forEach { (name, colors) ->
            val ratio = TextRules.contrast(colors.first and 0xFFFFFF, colors.second)
            assertTrue("$name 대비 %.2f".format(ratio), ratio >= TextRules.CONTRAST)
        }
    }

    @Test
    fun objectsAreBigEnoughToPress() {
        val minimum = TextRules.MIN_TOUCH
        TAPS.forEach { (name, tap) ->
            val rect = rectOf(tap.first, tap.second)
            assertTrue("$name 높이 ${rect[3] - rect[1]}px", rect[3] - rect[1] >= minimum)
            assertTrue("$name 폭 ${rect[2] - rect[0]}px", rect[2] - rect[0] >= minimum)
        }
        HomeScene.BABIES.forEach { assertTrue("아기 키 ${it.height * scale}px", it.height * scale >= minimum) }
    }

    @Test
    fun objectsAreApartFromEachOther() {
        val rects = TAPS.mapValues { (_, tap) -> rectOf(tap.first, tap.second) }
        val names = rects.keys.toList()
        for (a in names.indices) for (b in a + 1 until names.size) {
            val one = rects.getValue(names[a])
            val two = rects.getValue(names[b])
            val gapX = maxOf(two[0] - one[2], one[0] - two[2])
            val gapY = maxOf(two[1] - one[3], one[1] - two[3])
            assertTrue("${names[a]}와 ${names[b]} 사이 ${maxOf(gapX, gapY)}px", maxOf(gapX, gapY) >= TextRules.TOUCH_GAP)
        }
    }

    /** 물건 전체가 누를 곳입니다. 작으면 [TextRules.MIN_TOUCH]까지 넓힙니다. HomeActivity.placeTagWide와 같은 셈입니다. */
    private fun rectOf(spot: HomeScene.Spot, aspect: Float): FloatArray {
        val tall = maxOf(spot.height * scale, TextRules.MIN_TOUCH)
        val wide = maxOf(tall * aspect, TextRules.MIN_TOUCH)
        val x = spot.x * scale
        val bottom = spot.bottom * scale
        return floatArrayOf(x - wide / 2f, bottom - tall, x + wide / 2f, bottom)
    }

    private fun rgb(hex: String): Int = Integer.parseInt(hex.removePrefix("#"), 16)

    private companion object {
        const val REF_WIDTH = 1680f
        const val REF_HEIGHT = 1209f
        const val STACK_SLACK = 30f
        const val SAME_ROW = 20f
        const val SIDE_BY_SIDE = 0.75f // 같은 줄 두 아기 가운데 사이가 키의 이만큼은 됩니다.

        /** 아기 그림에서 칠해진 몸의 반폭 대 키. 앉은 그림 넷의 칠한 폭은 키의 0.85~1.17배이고 꼬리를 뺀 몸은 0.75배쯤입니다. */
        const val PAINTED_HALF = 0.38f

        /** 누르는 물건과 그림의 가로세로 비. */
        val TAPS = mapOf(
            "깃털 병" to (HomeScene.JAR to 341f / 414f),
            "앨범" to (HomeScene.ALBUM to 531f / 300f),
            "정비 수첩" to (HomeScene.CAREBOOK to 488f / 300f),
        )
    }
}
