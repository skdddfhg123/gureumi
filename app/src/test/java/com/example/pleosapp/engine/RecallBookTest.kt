package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** 회상 문장은 사람이 읽고 듣는 글이라 경우를 곱해서 전부 읽어 봅니다. */
class RecallBookTest {

    private val now = at(2027, 6, 1)

    private fun at(year: Int, month: Int, date: Int): Long =
        LocalDate.of(year, month, date).atTime(9, 0).toInstant(ZoneOffset.ofHours(9)).toEpochMilli()

    /** 가족과 정비가 다 있는 차. */
    private val rich = RecallFacts(
        nowMs = now,
        days = 400,
        firstTripAt = at(2026, 3, 2),
        firstTripKm = 12.3,
        totalKm = 8_240.0,
        feathers = 2_400,
        stage = Growth.Stage.GROWN,
        stageAt = mapOf(Growth.Stage.LITTLE to at(2026, 3, 12), Growth.Stage.GROWN to at(2026, 6, 1)),
        jarsOpened = 40,
        stars = 5,
        hasFamily = true,
        firstClickAt = at(2026, 3, 4),
        returnAt = at(2026, 3, 9),
        returnTitle = ReturnKind.ACORN.title,
        shelfCount = 12,
        careAt = at(2026, 5, 20),
        careTitle = CareItem.OIL.title,
        restAt = at(2026, 4, 2),
        latestRung = Reward.FORM_SCARF,
        moments = listOf(Moment.FIRST_CLICK, Moment.GATHERED, Moment.FIRST_RETURN, Moment.REST_DAY),
        plant = true,
    )

    /** 혼자 타고 아무 일도 없던 차. */
    private val bare = RecallFacts(nowMs = now, days = 400, firstTripAt = at(2026, 3, 2))

    /** 작은 정령을 모두 지운 차. 앨범에는 옛 순간이 남아 있습니다. */
    private val goneFamily = rich.copy(hasFamily = false)

    /** 주행거리 신호가 없는 차. */
    private val noDistance = rich.copy(firstTripKm = 0.0, totalKm = 0.0)

    /** 서른 번째 날 선물 쪽은 가장 최근 사다리 칸에 따라 문장이 달라서 칸을 하나씩 다 넣어 봅니다. */
    private val everyRung = Ladder.RUNGS.map { rich.copy(latestRung = it) }

    private val everyFacts = listOf(rich, bare, goneFamily, noDistance, rich.copy(stage = Growth.Stage.FLUFF)) + everyRung
    private val everySetting = listOf(RecallSettings(), RecallSettings(hideDates = true))
    private val everyId: List<RecallId> =
        (listOf(1) + TimeTogether.MILESTONES + listOf(1500, 2000)).map { RecallId.Days(it) } +
            Growth.Stage.entries.drop(1).map { RecallId.Grown(it) }

    private val stories = everyId.flatMap { id ->
        everyFacts.flatMap { facts -> everySetting.map { setting -> Triple(facts, setting, RecallBook.story(id, facts, setting)) } }
    }
    private val lines = stories.flatMap { (_, _, story) -> story.pages.map { it.line } }
    private val notes = (0 until 40).flatMap { i -> everyFacts.map { TimeTogether.note(it, RecallSettings(), i) } }

    /** 구름이가 읽어 주는 글 전부. 그림책 쪽과 제목, 쪽지, 초대, 화분 한 줄, 가려 두기 제목입니다. */
    private val spoken = (
        lines + stories.map { it.third.title } + notes + everyId.map { TimeTogether.inviteLine(it, TogetherState(), now) } +
            TimeTogether.PLANT_LINES.values + TimeTogether.HIDE_TITLE
        ).toSet()

    @Test
    fun `쪽 수는 첫날 한 쪽, 자람 두 쪽, 고비 세 쪽에서 다섯 쪽이다`() {
        stories.forEach { (_, _, story) ->
            val range = when (val id = story.id) {
                is RecallId.Grown -> 2..2
                is RecallId.Days -> if (id.days == 1) 1..1 else 3..5
            }
            assertTrue("${story.id} ${story.pages.size}쪽", story.pages.size in range)
        }
    }

    @Test
    fun `쪽마다 문장이 있고 그림 이름이 있다`() {
        stories.forEach { (_, _, story) ->
            assertTrue(story.title.isNotBlank())
            story.pages.forEach {
                assertTrue(it.line.isNotBlank())
                assertTrue(it.art.isNotEmpty())
            }
        }
    }

    @Test
    fun `한 쪽 문장은 마흔다섯 자를 넘지 않는다`() {
        lines.toSet().forEach { assertTrue("$it (${it.length}자)", it.length <= 45) }
    }

    @Test
    fun `슬픔과 죄책감과 재촉과 빈자리 판단이 없다`() {
        val banned = listOf(
            "슬퍼", "슬프", "외로", "미안", "보고 싶", "그리워", "서운", "아쉬", "버리", "버렸", "떠나", "잊", "죽", "마지막",
            "왜 안", "혼자", "못 왔", "못 탔", "안 탔", "빠졌", "요즘", "오랜만", "기다렸", "기다려", "얼른", "빨리", "어서", "내일도", "해야", "해 줘",
        )
        spoken.forEach { line -> banned.forEach { word -> assertFalse("$word 금지: $line", word in line) } }
    }

    @Test
    fun `읽는 글에 주인공 이름 구름이가 나오지 않는다`() {
        // 말하는 쪽이 구름이입니다. 하늘 구름을 "구름이"로 쓰면 이름으로 읽혀서 "구름 한 조각이"처럼 씁니다.
        spoken.forEach { assertFalse(it, "구름이" in it) }
    }

    @Test
    fun `서른 번째 날 선물 쪽은 칸에 어울리는 말을 쓴다`() {
        // 셋째 쪽이 선물 쪽입니다. 흉내말 동작은 하는 일을 배웠다고 말합니다.
        fun line(reward: Reward) = RecallBook.story(RecallId.Days(30), rich.copy(latestRung = reward), RecallSettings()).pages[2].line
        assertEquals("귀를 쫑긋 세우는 법도 배웠어.", line(Reward.MOTION_FLAP))
        assertEquals("꼬리로 톡톡 치는 법도 배웠어.", line(Reward.MOTION_WAG))
        assertEquals("콧노래도 배웠어.", line(Reward.MOTION_HUM))
        assertEquals("라벤더 목도리도 둘러 봤어.", line(Reward.FORM_SCARF))
        assertEquals("세이지 망토와 가방도 걸쳐 봤어.", line(Reward.FORM_CAPE))
        assertEquals("나뭇가지 모빌도 엮었어.", line(Reward.DECOR_MOBILE))
        assertEquals("작은 돛배도 띄웠어.", line(Reward.SCENE_BOAT))
        assertEquals("실내등도 노을빛이 됐어.", line(Reward.DECOR_LAMP))
        assertEquals("둥지에 깃털도 깔았어.", line(Reward.DECOR_FEATHERBED))
        assertEquals("작은 화분도 생겼어.", line(Reward.DECOR_FERN))
    }

    @Test
    fun `자리와 이름과 사람을 말하지 않는다`() {
        val banned = SeatSlot.entries.map { it.displayName } + SpiritPreset.entries.map { it.displayName } +
            listOf("아빠", "엄마", "아이", "운전자", "너는", "네가", "뒷자리", "조수석", "번 탔", "명이")
        lines.toSet().forEach { line -> banned.forEach { word -> assertFalse("$word 금지: $line", word in line) } }
    }

    @Test
    fun `날짜를 숨기면 어디에도 날짜가 없다`() {
        val date = Regex("""\d+월|\d+년|작년""")
        stories.filter { it.second.hideDates }.forEach { (_, _, story) ->
            story.pages.forEach { assertNull(it.line, date.find(it.line)) }
        }
    }

    @Test
    fun `날짜를 숨기지 않으면 첫날 날짜가 나온다`() {
        val line = RecallBook.story(RecallId.Days(3), rich, RecallSettings()).pages[1].line
        assertEquals("작년 3월 2일, 처음 같이 달렸지. 12km였어.", line)
    }

    @Test
    fun `작은 정령이 없으면 가족 쪽을 꺼내지 않는다`() {
        stories.filter { !it.first.hasFamily }.forEach { (_, _, story) ->
            story.pages.forEach { page ->
                assertFalse(page.line, "작은 친구" in page.line)
                assertFalse(page.line, "선반에 모인" in page.line)
                assertFalse(page.line, "첫 찰칵" in page.line)
            }
        }
    }

    @Test
    fun `다 모인 날은 문장에 꺼내지 않는다`() {
        assertFalse(lines.any { "다 모인 날" in it })
    }

    @Test
    fun `한 문장에 숫자는 하나까지다`() {
        val date = Regex("""(\d+년 )?\d+월 \d+일|작년 \d+월 \d+일""")
        val number = Regex("""\d[\d,.]*""")
        lines.toSet().forEach { line ->
            line.split(". ", ", ").forEach { part ->
                val rest = date.replace(part, "")
                assertTrue(line, number.findAll(rest).count() <= 1)
            }
        }
    }

    @Test
    fun `느낌표와 이모지를 쓰지 않는다`() {
        lines.toSet().forEach { line -> assertTrue(line, line.none { it == '!' || it.code > 0xD7A3 }) }
    }

    @Test
    fun `같은 책에서 같은 문장을 되풀이하지 않는다`() {
        stories.forEach { (_, _, story) ->
            val pageLines = story.pages.map { it.line }
            assertEquals(story.id.toString(), pageLines.size, pageLines.toSet().size)
        }
    }

    @Test
    fun `쪽지는 사실을 돌아가며 꺼내고 끄면 사실을 빼고 날짜를 숨기면 날짜를 뺀다`() {
        val all = (0 until 40).map { TimeTogether.note(rich, RecallSettings(), it) }.toSet()
        assertTrue(all.any { "8,240km" in it })
        assertTrue(all.any { "400일" in it })
        assertTrue(all.size >= 14)
        val off = (0 until 40).map { TimeTogether.note(rich, RecallSettings(off = true), it) }.toSet()
        assertFalse(off.any { any -> any.any { it.isDigit() } })
        val hidden = (0 until 40).map { TimeTogether.note(rich, RecallSettings(hideDates = true), it) }.toSet()
        assertFalse(hidden.any { "월" in it })
    }

    @Test
    fun `솜털이면 키 재기 쪽이 없다`() {
        val small = rich.copy(stage = Growth.Stage.FLUFF)
        TimeTogether.MILESTONES.forEach { days ->
            assertFalse(RecallBook.story(RecallId.Days(days), small, RecallSettings()).pages.any { it.ruler })
        }
    }

    @Test
    fun `백일과 천일에는 키 재기가 있다`() {
        assertTrue(RecallBook.story(RecallId.Days(100), rich, RecallSettings()).pages.any { it.ruler })
        assertTrue(RecallBook.story(RecallId.Days(1000), rich, RecallSettings()).pages.any { it.ruler })
    }

    @Test
    fun `거리가 길면 비유가 커진다`() {
        val busan = RecallBook.story(RecallId.Days(300), rich, RecallSettings()).pages[2].line
        val earth = RecallBook.story(RecallId.Days(300), rich.copy(totalKm = 80_000.0), RecallSettings()).pages[2].line
        assertEquals("서울에서 부산까지 20번 갈 만큼이야.", busan)
        assertEquals("지구를 2바퀴 돈 만큼이야.", earth)
    }

    @Test
    fun `백일과 천일의 끝 문장은 날 수를 두 번 말하지 않는다`() {
        listOf(100 to "백 날", 1000 to "천 날").forEach { (days, word) ->
            val last = RecallBook.story(RecallId.Days(days), rich, RecallSettings()).pages.last().line
            assertEquals(last, 1, last.windowed(word.length).count { it == word })
        }
    }

    @Test
    fun `화분이 있으면 100일과 365일 책 끝에 그날 달라진 화분을 한 줄로 잇는다`() {
        stories.forEach { (facts, _, story) ->
            val grew = (story.id as? RecallId.Days)?.let { TimeTogether.PLANT_LINES[it.days] }?.takeIf { facts.plant }
            val last = story.pages.last().line
            if (grew == null) assertFalse(last, "화분" in last) else assertTrue(last, last.endsWith("\n$grew"))
            story.pages.dropLast(1).forEach { assertFalse(it.line, "\n" in it.line) }
        }
    }

    @Test
    fun `창밖은 늘 강가라 그림책이 창밖 풍경을 약속하지 않는다`() {
        stories.forEach { (_, _, story) ->
            story.pages.forEach { assertFalse(it.line, "창밖에" in it.line && "보여" in it.line) }
        }
    }

    @Test
    fun `화분 문장은 남은 날이나 숫자를 말하지 않는다`() {
        TimeTogether.PLANT_LINES.values.forEach { line -> assertTrue(line, line.none { it.isDigit() } && "남은" !in line && "더 달" !in line) }
    }

    @Test
    fun `화분이 선반에 있는지는 홈과 같이 사다리에서 받은 칸으로 본다`() {
        val pet = PetState()
        assertTrue(RecallFacts.of(pet, GiftState(chosen = listOf(Reward.DECOR_FERN)), CareState(), FamilyState(), now).plant)
        assertFalse(RecallFacts.of(pet, GiftState(), CareState(), FamilyState(), now).plant)
    }

    @Test
    fun `저장 이름으로 그림책을 되찾는다`() {
        everyId.forEach { assertEquals(it, RecallId.parse(it.key)) }
        assertNull(RecallId.parse("이상한 값"))
        assertNull(RecallId.parse("stage_없음"))
    }

    @Test
    fun `앨범 표지 그림이 모든 그림책에 있다`() {
        everyId.forEach { assertTrue(RecallBook.photoArt(it).isNotEmpty()) }
    }

    @Test
    fun `facts는 앨범의 날짜를 우선하고 첫 정비는 더 이른 쪽을 쓴다`() {
        val click = at(2026, 3, 4)
        val pet = PetState(together = TogetherState(firstBeltAt = at(2026, 3, 9), firstStarAt = at(2026, 4, 1), firstStarCode = "TPMS"))
        val gifts = GiftState(momentAt = mapOf(Moment.FIRST_CLICK to click))
        val care = CareState(records = listOf(CareRecord(CareItem.OIL, at(2026, 5, 1), 0f, starred = true)))
        val facts = RecallFacts.of(pet, gifts, care, FamilyState(), now)
        assertEquals(click, facts.firstClickAt)
        assertEquals(at(2026, 4, 1), facts.careAt)
        assertEquals("타이어 공기압", facts.careTitle)
    }

    @Test
    fun `첫 보물은 아기가 처음 주워 온 것이다`() {
        val back = at(2026, 3, 9)
        val solo = ReturnGift(ReturnKind.STONE, bringer = null, tripId = at(2026, 3, 5))
        val brought = ReturnGift(ReturnKind.ACORN, SpiritPreset.SOM, tripId = back)
        val gifts = GiftState(shelf = listOf(solo, brought), momentAt = mapOf(Moment.FIRST_RETURN to back))
        val facts = RecallFacts.of(PetState(), gifts, CareState(), FamilyState(), now)
        assertEquals(ReturnKind.ACORN.title, facts.returnTitle)
        assertEquals(back, facts.returnAt)
        assertEquals(2, facts.shelfCount)
    }

    @Test
    fun `혼자 탄 날 주운 것만 있으면 작은 친구가 주워 왔다고 하지 않는다`() {
        // 11/6 전 기록은 혼자 주운 날에도 첫 보물을 남겼습니다.
        val soloDay = at(2026, 3, 5)
        val gifts = GiftState(shelf = listOf(ReturnGift(ReturnKind.STONE, null, soloDay)), momentAt = mapOf(Moment.FIRST_RETURN to soloDay))
        val facts = RecallFacts.of(PetState(), gifts, CareState(), FamilyState(), now).copy(hasFamily = true)
        assertNull(facts.returnTitle)
        assertNull(facts.returnAt)
        assertFalse(RecallBook.story(RecallId.Days(50), facts, RecallSettings()).pages.any { "주워" in it.line })
    }

    /** 사람이 읽어 볼 수 있게 전체 문장을 빌드 기록에 남깁니다. */
    @Test
    fun `문장 전체를 찍어 본다`() {
        everyId.forEach { id ->
            val story = RecallBook.story(id, rich, RecallSettings())
            println("[${story.title}] " + story.pages.joinToString(" / ") { it.line })
        }
    }
}
