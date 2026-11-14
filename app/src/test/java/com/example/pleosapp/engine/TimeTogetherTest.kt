package com.example.pleosapp.engine

import com.example.pleosapp.ui.game.NookLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.ZoneOffset

class TimeTogetherTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** 예전에 고비에 열던 창밖 풍경. 11/5에 보상을 뺐지만 옛 파일에는 남아 있을 수 있습니다. */
    private val oldScenes = listOf(Reward.SCENE_LIGHTHOUSE, Reward.SCENE_FIELD, Reward.SCENE_STARS)

    private val hour = 60 * 60_000L
    private val day = 24 * hour

    /** 2026-03-02 08:00 한국 시간. */
    private val start = at(2026, 3, 2, 8)

    private fun at(year: Int, month: Int, date: Int, hourOfDay: Int = 8): Long =
        LocalDate.of(year, month, date).atTime(hourOfDay, 0).toInstant(ZoneOffset.ofHours(9)).toEpochMilli()

    private fun trip(endAt: Long, durationMs: Long = 20 * 60_000L, distanceM: Float = 12_000f): List<DriveEvent> {
        val startedAt = endAt - durationMs
        return listOf(
            DriveEvent.TripStarted(startedAt, startedAt),
            DriveEvent.TripEnded(endAt, TripSummary(startedAt, durationMs, distanceM, 0, 0, 0, 0)),
        )
    }

    private fun PetState.play(events: List<DriveEvent>): PetState = events.fold(this, PetState::apply)

    private fun drive(days: Int, tripsPerDay: Int = 1, gapDays: Int = 1): PetState =
        (0 until days).fold(PetState()) { pet, i ->
            (0 until tripsPerDay).fold(pet) { state, n -> state.play(trip(start + i * gapDays * day + n * 3 * hour)) }
        }

    @Test
    fun `같은 날 세 번 달려도 하루다`() {
        assertEquals(1, drive(1, tripsPerDay = 3).together.days)
    }

    @Test
    fun `하루 한 번과 세 번은 날 수와 고비가 같다`() {
        val once = drive(40).together
        val thrice = drive(40, tripsPerDay = 3).together
        assertEquals(once.days, thrice.days)
        assertEquals(once.reachedAt.keys, thrice.reachedAt.keys)
        assertEquals(TimeTogether.photos(once.days), TimeTogether.photos(thrice.days))
    }

    @Test
    fun `빠진 날이 있어도 줄지 않고 이어서 센다`() {
        val weekly = drive(10, gapDays = 7).together
        assertEquals(10, weekly.days)
        assertTrue(7 in weekly.reachedAt)
    }

    @Test
    fun `한국 자정을 넘기면 다른 날이다`() {
        val beforeMidnight = LocalDate.of(2026, 3, 2).atTime(23, 50).toInstant(ZoneOffset.ofHours(9)).toEpochMilli()
        val pet = PetState().play(trip(beforeMidnight)).play(trip(beforeMidnight + 20 * 60_000L))
        assertEquals(2, pet.together.days)
    }

    @Test
    fun `짧은 움직임은 날로 세지 않는다`() {
        val pet = PetState().play(trip(start, durationMs = 7_000L))
        assertEquals(0, pet.together.days)
        assertNull(pet.together.firstTripAt)
    }

    @Test
    fun `다시 재생하면 같은 값이다`() {
        val ledger = EventLedger(folder.newFile("events.jsonl"))
        val events = (0 until 120).flatMap { trip(start + it * day) } +
            DriveEvent.RearBeltBuckled(start + 2 * day, SeatSlot.REAR_LEFT, parked = true) +
            DriveEvent.RestTaken(start + 3 * day, 20 * 60_000L)
        events.forEach(ledger::append)
        val live = PetState().play(events)
        val replayed = ledger.readAll().fold(PetState(), PetState::apply)
        assertEquals(live.together, replayed.together)
        assertEquals(120, replayed.together.days)
    }

    @Test
    fun `원장에 없던 새 필드 없이도 옛 원장을 읽는다`() {
        val file = folder.newFile("old.jsonl")
        file.writeText("""{"at":$start,"type":"Drowsy"}""" + "\n" + """{"at":$start,"type":"TripStarted","tripId":$start}""" + "\n")
        val pet = EventLedger(file).readAll().fold(PetState(), PetState::apply)
        assertTrue(pet.together.inTrip)
        assertEquals(0, pet.together.days)
    }

    @Test
    fun `고비에 닿은 시각을 남기고 첫날도 들어 있다`() {
        val pet = drive(8)
        assertEquals(setOf(1, 3, 7), pet.together.reachedAt.keys)
        assertEquals(start + 2 * day, pet.together.reachedAt[3])
        assertEquals(start, pet.together.firstTripAt)
    }

    @Test
    fun `천일 뒤에는 오백일마다 고비다`() {
        assertTrue(TimeTogether.isMilestone(1000))
        assertTrue(TimeTogether.isMilestone(1500))
        assertTrue(TimeTogether.isMilestone(2000))
        assertFalse(TimeTogether.isMilestone(1250))
        assertFalse(TimeTogether.isMilestone(2))
    }

    @Test
    fun `첫날은 권하지 않는다`() {
        val state = drive(1).together
        assertNull(TimeTogether.waiting(state, RecallSettings(), emptySet(), -1, start + hour))
    }

    @Test
    fun `여러 고비가 밀리면 가장 큰 고비 하나만 권한다`() {
        val state = drive(31).together
        val now = start + 30 * day + hour
        assertEquals(RecallId.Days(30), TimeTogether.waiting(state, RecallSettings(), emptySet(), -1, now))
    }

    @Test
    fun `본 그림책은 다시 권하지 않고 이레가 지나면 앨범에만 남는다`() {
        val state = drive(30).together
        val reached = start + 29 * day
        assertNull(TimeTogether.waiting(state, RecallSettings(), setOf("day30"), -1, reached + hour))
        assertEquals(RecallId.Days(30), TimeTogether.waiting(state, RecallSettings(), emptySet(), -1, reached + 7 * day))
        assertNull(TimeTogether.waiting(state, RecallSettings(), emptySet(), -1, reached + 8 * day))
        assertTrue(RecallId.Days(30) in TimeTogether.shelf(state))
    }

    @Test
    fun `같은 날에는 한 권만 권한다`() {
        val state = drive(30).together
        val now = start + 29 * day + hour
        assertNull(TimeTogether.waiting(state, RecallSettings(), emptySet(), TimeTogether.dayOf(now), now))
    }

    @Test
    fun `끄면 권하지 않고 일기 줄도 없다`() {
        val state = drive(30).together
        val now = start + 29 * day + hour
        assertNull(TimeTogether.waiting(state, RecallSettings(off = true), emptySet(), -1, now))
        assertNull(TimeTogether.arrivalLine(state, RecallSettings(off = true), now))
    }

    @Test
    fun `운전 중에는 권하지 않는다`() {
        val pet = drive(30)
        val now = start + 29 * day + hour
        val driving = pet.apply(DriveEvent.TripStarted(now, now))
        assertNull(TimeTogether.waiting(driving.together, RecallSettings(), emptySet(), -1, now))
    }

    @Test
    fun `구름이가 자라면 자람 그림책을 권한다`() {
        val pet = PetState(feathers = 149).apply(DriveEvent.SmoothStop(start, 1.8f))
        assertEquals(setOf(Growth.Stage.LITTLE), pet.together.stageAt.keys)
        assertEquals(RecallId.Grown(Growth.Stage.LITTLE), TimeTogether.waiting(pet.together, RecallSettings(), emptySet(), -1, start + hour))
    }

    @Test
    fun `고비가 자람보다 먼저다`() {
        val pet = PetState(feathers = 149).apply(DriveEvent.SmoothStop(start, 1.8f))
            .play((0 until 3).flatMap { trip(start + it * day + hour) })
        assertEquals(RecallId.Days(3), TimeTogether.waiting(pet.together, RecallSettings(), emptySet(), -1, start + 2 * day + 2 * hour))
    }

    @Test
    fun `일기 줄은 고비에 닿은 날에만 붙는다`() {
        val state = drive(3).together
        assertEquals("오늘로 같이 달린 날이 사흘이 됐어.", TimeTogether.arrivalLine(state, RecallSettings(), start + 2 * day + hour))
        assertNull(TimeTogether.arrivalLine(state, RecallSettings(), start + 3 * day + hour))
        assertEquals("오늘이 우리 첫날이야.", TimeTogether.arrivalLine(drive(1).together, RecallSettings(), start + hour))
        assertEquals("오늘로 백일이야.", TimeTogether.arrivalLine(drive(100).together, RecallSettings(), start + 99 * day + hour))
    }

    @Test
    fun `초대 문장은 오늘 닿은 고비에만 오늘이라고 한다`() {
        val state = drive(100).together
        val reached = start + 99 * day
        assertTrue(TimeTogether.inviteLine(RecallId.Days(100), state, reached + hour).startsWith("오늘은 백일이야."))
        assertTrue(TimeTogether.inviteLine(RecallId.Days(100), state, reached + 2 * day).startsWith("같이 달린 날이 100일이 됐어."))
    }

    @Test
    fun `사진은 닿은 고비만큼이고 넘치면 첫날과 최근을 남긴다`() {
        assertEquals(listOf(1, 3, 7, 14, 30), TimeTogether.photos(30).map { it.days })
        assertEquals(listOf(1, 14, 30, 50, 100), TimeTogether.photos(100).map { it.days })
        assertEquals(drive(30).together.reachedAt.keys.sorted(), TimeTogether.photos(30).map { it.days })
        assertEquals(1, TimeTogether.photos(1).size)
        assertTrue(TimeTogether.photos(0).isEmpty())
        val photos = TimeTogether.photos(2600).map { it.days }
        assertEquals(TimeTogether.MAX_PHOTOS, photos.size)
        assertEquals(1, photos.first())
        assertEquals(2500, photos.last())
    }

    @Test
    fun `크기는 단계마다 커지고 어른이 지금 그림 크기다`() {
        val scales = Growth.Stage.entries.map(TimeTogether::heroScale)
        assertEquals(scales.sorted(), scales)
        assertEquals(scales.size, scales.toSet().size)
        assertEquals(1f, scales.last(), 0f)
    }

    @Test
    fun `집은 날이 갈수록 달라지고 되돌아가지 않는다`() {
        val days = listOf(1, 7, 30, 100, 365, 1000)
        assertEquals(days.map(TimeTogether::fernScale).sorted(), days.map(TimeTogether::fernScale))
        assertEquals(days.map(TimeTogether::plantStep).sorted(), days.map(TimeTogether::plantStep))
        assertEquals(days.map(TimeTogether::driftCount).sorted(), days.map(TimeTogether::driftCount))
    }

    @Test
    fun `계절은 달로 정한다`() {
        assertEquals(Season.SPRING, TimeTogether.season(3))
        assertEquals(Season.SUMMER, TimeTogether.season(8))
        assertEquals(Season.AUTUMN, TimeTogether.season(11))
        assertEquals(Season.WINTER, TimeTogether.season(12))
        assertEquals(Season.WINTER, TimeTogether.season(2))
    }

    @Test
    fun `날짜는 올해와 작년과 그전을 나눠 쓴다`() {
        val now = at(2027, 5, 1)
        assertEquals("3월 2일", TimeTogether.dateText(at(2027, 3, 2), now))
        assertEquals("작년 3월 2일", TimeTogether.dateText(at(2026, 3, 2), now))
        assertEquals("2025년 3월 2일", TimeTogether.dateText(at(2025, 3, 2), now))
    }

    @Test
    fun `거리는 짧으면 소수로 길면 쉼표로 쓴다`() {
        assertEquals("3.2km", TimeTogether.kmText(3.2))
        assertEquals("12km", TimeTogether.kmText(12.3))
        assertEquals("1,240km", TimeTogether.kmText(1240.0))
    }

    @Test
    fun `일 년 전 오늘을 꺼낸다`() {
        val facts = RecallFacts(nowMs = at(2027, 3, 2), firstTripAt = at(2026, 3, 2))
        assertEquals("1년 전 오늘, 처음 같이 달렸어.", TimeTogether.anniversaryLine(facts, RecallSettings()))
    }

    @Test
    fun `달력이 맞지 않는 날에는 꺼내지 않는다`() {
        val sameYear = RecallFacts(nowMs = at(2026, 3, 2, 20), firstTripAt = at(2026, 3, 2))
        val otherDay = RecallFacts(nowMs = at(2027, 3, 3), firstTripAt = at(2026, 3, 2))
        assertNull(TimeTogether.anniversaryLine(sameYear, RecallSettings()))
        assertNull(TimeTogether.anniversaryLine(otherDay, RecallSettings()))
    }

    @Test
    fun `날짜를 숨기면 일 년 전 오늘도 없다`() {
        val facts = RecallFacts(nowMs = at(2027, 3, 2), firstTripAt = at(2026, 3, 2))
        assertNull(TimeTogether.anniversaryLine(facts, RecallSettings(hideDates = true)))
        assertNull(TimeTogether.anniversaryLine(facts, RecallSettings(off = true)))
    }

    @Test
    fun `무엇인지 모르는 일은 일 년 전 오늘에 꺼내지 않는다`() {
        val yearAgo = at(2026, 3, 2)
        val facts = RecallFacts(nowMs = at(2027, 3, 2), hasFamily = true, careAt = yearAgo, returnAt = yearAgo)
        assertNull(TimeTogether.anniversaryLine(facts, RecallSettings()))
    }

    @Test
    fun `윤일의 일은 평년 이월 스무여드레에 꺼낸다`() {
        val facts = RecallFacts(nowMs = at(2029, 2, 28), firstTripAt = at(2028, 2, 29))
        assertEquals("1년 전 오늘, 처음 같이 달렸어.", TimeTogether.anniversaryLine(facts, RecallSettings()))
    }

    @Test
    fun `작은 친구를 다 지우면 첫 찰칵을 꺼내지 않는다`() {
        val click = at(2026, 3, 2)
        val gone = RecallFacts(nowMs = at(2027, 3, 2), firstClickAt = click, hasFamily = false)
        val here = gone.copy(hasFamily = true)
        assertNull(TimeTogether.anniversaryLine(gone, RecallSettings()))
        assertNotNull(TimeTogether.anniversaryLine(here, RecallSettings()))
    }

    @Test
    fun `앨범에 든 시각을 남기고 파일로 옮겨도 그대로다`() {
        val family = FamilyState().adopt(SpiritPreset.SOM, SeatSlot.REAR_LEFT)
        val gifts = Memories.onEvent(GiftState(), DriveEvent.RestTaken(start, 1L), family)
            .let { Memories.onEvent(it, DriveEvent.RestTaken(start + day, 1L), family) }
        assertEquals(start, gifts.momentAt[Moment.REST_DAY])
        assertEquals(gifts, KeepsakeCodec.decodeGifts(KeepsakeCodec.encodeGifts(gifts)))
    }

    @Test
    fun `날짜를 모르는 옛 선물 파일도 읽는다`() {
        val gifts = KeepsakeCodec.decodeGifts("""{"claims":1,"album":["rest"]}""")
        assertEquals(setOf(Moment.REST_DAY), gifts.album)
        assertTrue(gifts.momentAt.isEmpty())
    }

    /** 파일까지 읽는 시간입니다. 차량에서는 더 걸리므로 첫 화면은 이 재생을 기다리지 않습니다. */
    @Test
    fun `십 년치 원장 파일을 읽어 접는다`() {
        val file = folder.newFile("ten-years.jsonl")
        file.bufferedWriter().use { out ->
            for (date in 0 until 3650) {
                for (n in 0 until 3) {
                    val begin = start + date * day + n * 3 * hour
                    out.write("""{"at":$begin,"type":"TripStarted","tripId":$begin}""" + "\n")
                    repeat(18) { out.write("""{"at":${begin + it * 60_000L},"type":"SmoothStop","peakDecel":1.8}""" + "\n") }
                    out.write(
                        """{"at":${begin + 1_800_000L},"type":"TripEnded","tripId":$begin,"durationMs":1800000,"distanceM":12000.0,""" +
                            """"smoothStops":18,"hardBrakes":0,"signalsUsed":0,"signalsMissed":0}""" + "\n",
                    )
                }
            }
        }
        val began = System.nanoTime()
        val pet = EventLedger(file).readAll().fold(PetState(), PetState::apply)
        val elapsedMs = (System.nanoTime() - began) / 1_000_000
        println("10년치 원장 ${file.length() / 1024 / 1024}MB를 ${elapsedMs}ms에 읽어 접음")
        assertEquals(3650, pet.together.days)
    }

    /** 10년, 하루 세 번, 트립마다 이벤트 스무 개입니다. 첫 화면은 이 재생을 기다리지 않습니다. */
    @Test
    fun `십 년치 원장도 금방 접는다`() {
        val events = ArrayList<DriveEvent>()
        for (date in 0 until 3650) {
            for (n in 0 until 3) {
                val begin = start + date * day + n * 3 * hour
                events += DriveEvent.TripStarted(begin, begin)
                repeat(10) { events += DriveEvent.SmoothStop(begin + it * 60_000L, 1.8f) }
                repeat(8) { events += DriveEvent.SignalUsed(begin + it * 30_000L, TurnSide.LEFT, 2_000L) }
                events += DriveEvent.TripEnded(begin + 30 * 60_000L, TripSummary(begin, 30 * 60_000L, 12_000f, 10, 0, 8, 0))
            }
        }
        val began = System.nanoTime()
        val pet = events.fold(PetState(), PetState::apply)
        val elapsedMs = (System.nanoTime() - began) / 1_000_000
        println("10년치 이벤트 ${events.size}개를 ${elapsedMs}ms에 접음")
        assertEquals(3650, pet.together.days)
        assertTrue(pet.together.reachedAt.size <= 20)
        assertTrue("접기가 너무 느림: ${elapsedMs}ms", elapsedMs < 2_000)
    }

    @Test
    fun `고비 풍경은 더 주지 않고 사다리 칸 수는 그대로다`() {
        // 창밖이 늘 강가라 고비 풍경 보상을 뺐습니다.
        val owned = CareRules.owned(drive(365, tripsPerDay = 3), GiftState(), CareState())
        assertTrue(oldScenes.none { it in owned })
        assertEquals(17, Ladder.RUNGS.size)
    }

    @Test
    fun `화분 한 줄은 화분이 실제로 바뀌는 고비에만 붙는다`() {
        assertTrue(TimeTogether.MILESTONES.containsAll(TimeTogether.PLANT_LINES.keys))
        TimeTogether.PLANT_LINES.keys.forEach { day ->
            assertTrue("$day 일", TimeTogether.plantStep(day) > TimeTogether.plantStep(day - 1))
        }
        assertEquals(4, TimeTogether.plantStep(100))
        assertEquals(5, TimeTogether.plantStep(365))
    }

    @Test
    fun `별 언덕은 맑은 저녁과 밤에만 창에 든다`() {
        val every = DayPart.entries.flatMap { part -> Sky.entries.map { sky -> SceneState(part, sky) } }
        assertEquals(
            listOf(SceneState(DayPart.EVENING, Sky.CLEAR), SceneState(DayPart.NIGHT, Sky.CLEAR)),
            every.filter { TimeTogether.fitsWindow(Reward.SCENE_STARS, it) },
        )
    }

    @Test
    fun `낮 그림 풍경은 시간과 날씨를 가리지 않고 창에 든다`() {
        val every = DayPart.entries.flatMap { part -> Sky.entries.map { sky -> SceneState(part, sky) } }
        val daylight = listOf(Reward.SCENE_SEA, Reward.SCENE_FOREST, Reward.SCENE_LIGHTHOUSE, Reward.SCENE_FIELD)
        assertTrue(daylight.all { scene -> every.all { TimeTogether.fitsWindow(scene, it) } })
    }

    @Test
    fun `별 언덕에는 시간대 색을 입히지 않고 낮 그림 풍경에는 입힌다`() {
        val night = SceneState(DayPart.NIGHT, Sky.CLEAR)
        val evening = SceneState(DayPart.EVENING, Sky.CLEAR)
        assertNull(NookLight.windowTint(night, Reward.SCENE_STARS))
        assertNull(NookLight.windowTint(evening, Reward.SCENE_STARS))
        assertEquals(0x4E4E86, NookLight.windowTint(night, Reward.SCENE_LIGHTHOUSE))
        assertEquals(0xFFC8A0, NookLight.windowTint(evening, Reward.SCENE_FIELD))
    }

    @Test
    fun `풍경을 안 고른 창의 색은 그대로다`() {
        assertNull(NookLight.windowTint(SceneState(DayPart.NIGHT, Sky.CLEAR), null))
        assertEquals(0x4E4E86, NookLight.windowTint(SceneState(DayPart.NIGHT, Sky.RAIN), null))
    }

    private val clearSkies = DayPart.entries.map { SceneState(it, Sky.CLEAR) }

    @Test
    fun `낮에는 계절마다 다른 조각이 날린다`() {
        val day = SceneState(DayPart.DAY, Sky.CLEAR)
        assertEquals(
            listOf(Drift.PETAL, Drift.SEED, Drift.LEAF, Drift.FLAKE),
            Season.entries.map { TimeTogether.drift(it, day, null) },
        )
    }

    @Test
    fun `밤에는 여름 반딧불만 남는다`() {
        val night = SceneState(DayPart.NIGHT, Sky.CLEAR)
        assertEquals(listOf(null, Drift.FIREFLY, null, null), Season.entries.map { TimeTogether.drift(it, night, null) })
    }

    @Test
    fun `바다와 등대 창에는 아무것도 날리지 않는다`() {
        listOf(Reward.SCENE_SEA, Reward.SCENE_LIGHTHOUSE).forEach { scene ->
            Season.entries.forEach { season -> clearSkies.forEach { assertNull(TimeTogether.drift(season, it, scene)) } }
        }
    }

    @Test
    fun `별 언덕에는 여름 반딧불만 날린다`() {
        val dark = clearSkies.filter { TimeTogether.fitsWindow(Reward.SCENE_STARS, it) }
        dark.forEach { state ->
            assertEquals(Drift.FIREFLY, TimeTogether.drift(Season.SUMMER, state, Reward.SCENE_STARS))
            assertNull(TimeTogether.drift(Season.AUTUMN, state, Reward.SCENE_STARS))
        }
    }

    @Test
    fun `겨울 둥지 창에는 달과 상관없이 눈송이가 날린다`() {
        Season.entries.forEach { season ->
            clearSkies.forEach { assertEquals(Drift.FLAKE, TimeTogether.drift(season, it, Reward.SCENE_WINTER)) }
        }
    }

    @Test
    fun `궂은 날에는 조각을 날리지 않는다`() {
        val rough = Sky.entries.filter { it != Sky.CLEAR }
        Season.entries.forEach { season ->
            rough.forEach { assertNull(TimeTogether.drift(season, SceneState(DayPart.DAY, it), null)) }
        }
    }

    @Test
    fun `예전에 고른 풍경도 파일에 남는다`() {
        oldScenes.forEach { scene ->
            val saved = KeepsakeCodec.decodeGifts(KeepsakeCodec.encodeGifts(GiftState().show(scene)))
            assertEquals(scene, saved.scene)
        }
    }
}
