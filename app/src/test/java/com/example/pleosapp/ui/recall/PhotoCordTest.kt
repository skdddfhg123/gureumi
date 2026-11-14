package com.example.pleosapp.ui.recall

import com.example.pleosapp.engine.TimeTogether
import com.example.pleosapp.ui.game.GameStyle
import com.example.pleosapp.ui.game.HomeScene
import com.example.pleosapp.ui.game.TextRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoCordTest {

    private val counts = 1..TimeTogether.MAX_PHOTOS

    /** 넓은 창(1680×1209)의 화면 배율. */
    private val wide = 1.1797f

    /** 이 배율까지 작은 창에서도 사진이 겹치지 않아야 합니다. 좁은 배치는 0.6쯤입니다. */
    private val scales = listOf(0.5f, 0.6f, 0.7f, 0.85f, 1f, wide, 1.4f)

    /** 꼬리표 오른쪽 끝. 글자 너비는 기기에서 재므로 여기서는 넉넉히 어림합니다(한글 1em, 숫자 0.6em). */
    private fun tagRight(scale: Float): Float {
        val width = maxOf(4.4f * PhotoCord.smallText(scale, 1f), 3.4f * PhotoCord.bigText(scale, 1f)) + 20f
        return PhotoCord.LEFT + width / 2f
    }

    /** 꼬리표가 있는 줄과 없는 줄(도착 화면). */
    private val afters = scales.map(::tagRight) + PhotoCord.LEFT

    /** 천장 그림에서 잰 고리 자리. */
    private val loops = listOf(PhotoCord.LEFT, PhotoCord.MIDDLE, PhotoCord.RIGHT)

    /** 천장 등 테두리. 위가 둥글어 아래로 갈수록 넓습니다. (y, 왼쪽, 오른쪽) */
    private val lamp = listOf(
        Triple(197f, 737f, 800f), Triple(200f, 723f, 813f), Triple(204f, 714f, 823f),
        Triple(208f, 709f, 827f), Triple(214f, 705f, 831f), Triple(269f, 702f, 834f),
    )

    /** 햇빛 가리개. 받침 위쪽 끝으로 넉넉히 잡습니다. (왼쪽, 오른쪽, 위) */
    private val visors = listOf(Triple(285f, 497f, 224f), Triple(1043f, 1252f, 213f))

    private fun inLamp(x: Float, y: Float): Boolean {
        if (y < lamp.first().first) return false
        val row = lamp.firstOrNull { it.first >= y } ?: return false
        return x in row.second..row.third
    }

    private fun onVisor(x: Float, y: Float): Boolean = visors.any { (left, right, top) -> x in left..right && y >= top }

    /** 사진 틀 아래 두 모서리. 곧게 늘어지므로 집게 자리에서 바로 내려옵니다. 그림자 3까지 봅니다. */
    private fun bottomCorners(x: Float, slots: FloatArray): List<Pair<Float, Float>> {
        val bottom = PhotoCord.y(x, slots) + PhotoCord.CLIP_DROP + PhotoCord.FRAME * PhotoCord.FRAME_RATIO + 3f
        return listOf(x - PhotoCord.FRAME / 2f to bottom, x + PhotoCord.FRAME / 2f + 2f to bottom)
    }

    @Test
    fun `줄은 손잡이 두 개 사이에만 있고 고리를 지나는 높이가 같다`() {
        for (count in counts) {
            val slots = PhotoCord.slots(count, tagRight(wide))
            val xs = PhotoCord.cordXs(slots)
            assertEquals(PhotoCord.LEFT, xs.first())
            assertEquals(PhotoCord.RIGHT, xs.last())
            loops.forEach { loop -> assertEquals("$count 장: $loop", PhotoCord.HANG_Y, PhotoCord.y(loop, slots), 0.001f) }
            slots.forEach { slot -> assertTrue("$count 장: 집게 자리 $slot 를 줄 그림이 지나지 않음", xs.any { it == slot }) }
        }
    }

    @Test
    fun `사진은 고리와 꼬리표를 비켜 손잡이 사이에 걸린다`() {
        for (after in afters) for (count in counts) {
            val half = PhotoCord.FRAME / 2f
            PhotoCord.slots(count, after).forEach { x ->
                assertTrue("$count 장: $x 가 꼬리표에 닿음($after)", x - half > after)
                assertTrue("$count 장: $x 가 손잡이 밖", x + half < PhotoCord.RIGHT - PhotoCord.LOOP_HALF)
                loops.forEach { loop ->
                    assertTrue("$count 장: $x 가 $loop 고리에 닿음", x + half < loop - PhotoCord.LOOP_HALF || x - half > loop + PhotoCord.LOOP_HALF)
                }
            }
        }
    }

    @Test
    fun `사진은 오른쪽 손잡이에 매단 모빌 끈을 비킨다`() {
        // 모빌 끈은 손잡이에서 곧게 내려오고, 사진 띠 높이에서 폭이 14 안쪽입니다(prop_mobile_hang에서 잼).
        val string = HomeScene.MOBILE_HOOK_X - 7f
        for (after in afters) for (count in counts) {
            PhotoCord.slots(count, after).forEach { x ->
                assertTrue("$count 장: $x 가 모빌 끈에 닿음", x + PhotoCord.FRAME / 2f + 2f < string)
            }
        }
    }

    @Test
    fun `사진 아랫모서리가 천장 등과 햇빛 가리개에 닿지 않는다`() {
        for (after in afters) for (count in counts) {
            val slots = PhotoCord.slots(count, after)
            slots.forEach { x ->
                bottomCorners(x, slots).forEach { (cx, cy) ->
                    assertTrue("$count 장: $x 의 모서리 ($cx, $cy)가 등에 닿음", !inLamp(cx, cy))
                    assertTrue("$count 장: $x 의 모서리 ($cx, $cy)가 가리개에 닿음", !onVisor(cx, cy))
                }
            }
        }
    }

    @Test
    fun `사진은 날 순서대로 왼쪽부터 걸린다`() {
        for (count in counts) {
            val slots = PhotoCord.slots(count, tagRight(wide))
            assertEquals(count, slots.size)
            slots.toList().zipWithNext().forEach { (a, b) -> assertTrue("$count 장: $a, $b", a < b) }
        }
    }

    @Test
    fun `사진은 한 장씩 읽히는 크기와 간격을 지킨다`() {
        // 사진은 누를 곳이 아니지만 누를 곳만큼 크고 떨어져야 한 장씩 읽힙니다. 넓은 창 기준 76px, 23px입니다.
        assertTrue(PhotoCord.FRAME * PhotoCord.FRAME_RATIO * wide >= 76f)
        assertTrue(PhotoCord.GAP * wide >= 23f)
        for (after in afters) for (count in counts) {
            PhotoCord.slots(count, after).toList().zipWithNext().forEach { (a, b) ->
                assertTrue("$count 장: $a, $b 사이가 좁음($after)", b - a - PhotoCord.FRAME >= PhotoCord.GAP - 0.01f)
            }
        }
    }

    @Test
    fun `사진은 곧게 늘어지고 줄은 집게 자리에서 꺾인다`() {
        val slots = PhotoCord.slots(TimeTogether.MAX_PHOTOS, tagRight(wide))
        val d = 0.5f
        slots.forEach { x ->
            val before = (PhotoCord.y(x, slots) - PhotoCord.y(x - d, slots)) / d
            val after = (PhotoCord.y(x + d, slots) - PhotoCord.y(x, slots)) / d
            assertTrue("$x 에서 무게만큼 꺾이지 않음: $before, $after", before - after > 0.03f)
        }
        // 집게 사이는 거의 곧습니다. 줄 무게로만 조금 처집니다.
        slots.toList().zipWithNext().filter { (a, b) -> a < PhotoCord.MIDDLE == b < PhotoCord.MIDDLE }.forEach { (a, b) ->
            val chord = (PhotoCord.y(a, slots) + PhotoCord.y(b, slots)) / 2f
            assertEquals("$a~$b", chord, PhotoCord.y((a + b) / 2f, slots), 1f)
        }
    }

    @Test
    fun `줄은 사진 무게로 조금만 처진다`() {
        for (count in counts) {
            val slots = PhotoCord.slots(count, tagRight(wide))
            PhotoCord.cordXs(slots).forEach { x ->
                val drop = PhotoCord.y(x, slots) - PhotoCord.HANG_Y
                assertTrue("$count 장: $x 에서 $drop", drop in 0f..18f)
            }
        }
    }

    @Test
    fun `꼬리표 글자는 어느 창에서도 화면 글자 기준을 넘는다`() {
        for (density in listOf(1f, 1.5f, 2f)) for (scale in listOf(0.4f, 0.6f, 0.8f, 1f, wide, 1.6f)) {
            assertTrue("$scale, $density", PhotoCord.smallText(scale, density) * scale >= TextRules.SMALL * density - 0.01f)
            assertTrue("$scale, $density", PhotoCord.bigText(scale, density) * scale >= TextRules.MAIN * density - 0.01f)
        }
    }

    @Test
    fun `꼬리표와 회상 글자는 낮과 밤 모두 대비 4_5를 넘는다`() {
        val paper = GameStyle.PAPER and 0xFFFFFF
        val night = TextRules.lit(paper, TextRules.PAPER_DIM_LIGHT)
        val ink = GameStyle.INK and 0xFFFFFF
        assertTrue(TextRules.contrast(ink, paper) >= TextRules.CONTRAST)
        assertTrue(TextRules.contrast(ink, night) >= TextRules.CONTRAST)
        assertTrue(TextRules.contrast(GameStyle.INK_SOFT and 0xFFFFFF, paper) >= TextRules.CONTRAST)
        // 회상 설정의 켜짐 단추는 산호빛 종이에 진한 글자입니다.
        assertTrue(TextRules.contrast(ink, TextRules.lit(paper, RecallSettingsNote.ON_PAPER)) >= TextRules.CONTRAST)
    }
}
