package com.example.pleosapp.share

import com.example.pleosapp.engine.DayPart
import com.example.pleosapp.engine.Ladder
import com.example.pleosapp.engine.Moment
import com.example.pleosapp.engine.Postcard
import com.example.pleosapp.engine.PostcardCodec
import com.example.pleosapp.engine.PostcardLines
import com.example.pleosapp.engine.Reward
import com.example.pleosapp.engine.RewardKind
import com.example.pleosapp.engine.SpiritPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PostcardTest {

    @Test
    fun `엽서는 그대로 돌아온다`() {
        // 창밖 바다는 11/4에 사다리에서 뺐지만, 이미 보낸 엽서는 그대로 읽혀야 합니다.
        val card = Postcard(
            day = 305, level = 12, stage = 2, form = Reward.FORM_SCARF, scene = Reward.SCENE_SEA, part = DayPart.EVENING,
            aboard = setOf(SpiritPreset.SOM, SpiritPreset.ANGAE), moment = Moment.FIRST_RETURN, line = PostcardLines.RETURN, together = 123,
        )
        assertEquals(card, PostcardCodec.decode(PostcardCodec.encode(card)))
    }

    @Test
    fun `사다리의 모습과 풍경은 모두 엽서에 담긴다`() {
        Ladder.RUNGS.filter { it.kind == RewardKind.FORM || it.kind == RewardKind.SCENE }.forEach { reward ->
            val form = reward.takeIf { it.kind == RewardKind.FORM }
            val scene = reward.takeIf { it.kind == RewardKind.SCENE }
            val back = PostcardCodec.decode(PostcardCodec.encode(Postcard(day = 100, level = 12, stage = 2, form = form, scene = scene)))
            assertEquals(reward.id, form?.let { back?.form?.id } ?: back?.scene?.id)
        }
    }

    @Test
    fun `이미 보낸 엽서의 함께한 날 풍경도 그대로 읽힌다`() {
        // 함께한 날 창밖 풍경은 11/5에 뺐습니다. 그 전에 보낸 엽서는 그대로 읽혀야 합니다.
        listOf(Reward.SCENE_LIGHTHOUSE, Reward.SCENE_FIELD, Reward.SCENE_STARS).forEach { scene ->
            val card = Postcard(day = 100, level = 12, stage = 2, scene = scene)
            assertEquals(scene, PostcardCodec.decode(PostcardCodec.encode(card))?.scene)
        }
    }

    @Test
    fun `비어 있는 값도 그대로 돌아온다`() {
        val card = Postcard(day = 0, level = 1, stage = 0)
        assertEquals(card, PostcardCodec.decode(PostcardCodec.encode(card)))
    }

    @Test
    fun `주소에 짧게 들어간다`() {
        val text = PostcardCodec.encode(Postcard(day = 305, level = 12, stage = 2))
        assertEquals(18, text.length)
        assertTrue(text.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `깨진 값은 읽지 않는다`() {
        assertNull(PostcardCodec.decode("!!!"))
        assertNull(PostcardCodec.decode("AQ"))
        assertNull(PostcardCodec.decode(""))
    }

    @Test
    fun `모르는 형식은 읽지 않는다`() {
        val text = PostcardCodec.encode(Postcard(day = 1, level = 1, stage = 0))
        val later = "B" + text.substring(1)
        assertNull(PostcardCodec.decode(later))
    }

    @Test
    fun `날짜는 한국 자정을 기준으로 2026년부터 센다`() {
        val seoul = TimeZone.getTimeZone("Asia/Seoul")
        val start = Calendar.getInstance(seoul).apply {
            clear()
            set(2026, Calendar.JANUARY, 1, 0, 0, 0)
        }.timeInMillis
        assertEquals(0, PostcardCodec.dayOf(start))
        assertEquals(0, PostcardCodec.dayOf(start + 23 * 3_600_000L))
        assertEquals(1, PostcardCodec.dayOf(start + 24 * 3_600_000L))
    }

    @Test
    fun `있었던 일에서 문장을 고른다`() {
        assertEquals(PostcardLines.RETURN, PostcardLines.pick(null, 2, broughtToday = true))
        assertEquals(PostcardLines.TOGETHER, PostcardLines.pick(null, 2, broughtToday = false))
        assertEquals(PostcardLines.FEATHERS, PostcardLines.pick(null, 0, broughtToday = false))
    }
}
