package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrowthTest {

    private val minute = 60_000L
    private fun trip(minutes: Long) = TripSummary(1, minutes * minute, 1000f, 0, 0, 0, 0)
    private val noon = 1_793_500_000_000L

    private fun PetState.arrive(minutes: Long, atMs: Long = noon) = apply(DriveEvent.TripEnded(atMs, trip(minutes)))

    @Test
    fun `병은 갈수록 커지고 60개에서 멈춘다`() {
        assertEquals(Growth.Jar(0, 0, 10), Growth.jar(0))
        assertEquals(Growth.Jar(1, 0, 20), Growth.jar(10))
        assertEquals(Growth.Jar(2, 5, 30), Growth.jar(35))
        assertEquals(Growth.Jar(6, 0, 60), Growth.jar(210))
        assertEquals(Growth.Jar(7, 0, 60), Growth.jar(270))
    }

    @Test
    fun `병을 채울 때마다 레벨이 오른다`() {
        assertEquals(1, PetState(feathers = 9).level)
        assertEquals(2, PetState(feathers = 10).level)
        assertEquals(4, PetState(feathers = 60).level)
    }

    @Test
    fun `병 용량은 10부터 60까지 늘고 그 뒤로 60이다`() {
        var feathers = 0
        val sizes = (1..9).map {
            val capacity = Growth.jar(feathers).capacity
            feathers += capacity
            capacity
        }
        assertEquals(listOf(10, 20, 30, 40, 50, 60, 60, 60, 60), sizes)
    }

    @Test
    fun `깃털은 줄지 않고 레벨은 내려가지 않는다`() {
        val before = PetState(feathers = 100)
        val after = listOf(
            DriveEvent.HardBrake(1, 7f, 20f),
            DriveEvent.HardBrake(2, 7f, 20f),
            DriveEvent.SignalMissed(3, TurnSide.LEFT),
            DriveEvent.BeltsMissing(4),
            DriveEvent.HealthAlert(5, "ENGINE"),
        ).fold(before, PetState::apply)
        assertEquals(100, after.feathers)
        assertEquals(before.level, after.level)
    }

    @Test
    fun `여정의 행동을 스티커 조건으로 센다`() {
        val summary = trip(70).copy(smoothStops = 4, signalsUsed = 3, rearBelts = 1, seats = setOf(SeatSlot.REAR_LEFT))
        val counts = PetState().apply(DriveEvent.TripEnded(noon, summary)).counts
        assertEquals(TripCounts(softStops = 4, signals = 3, rearBelts = 1), counts)
    }

    @Test
    fun `밤과 아침과 장거리는 세지 않는다`() {
        val midnightKst = 1_793_458_800_000L
        val night = trip(150).copy(smoothStops = 1)
        val state = PetState().apply(DriveEvent.TripEnded(midnightKst + 23 * 60 * minute, night))
        assertEquals(TripCounts(softStops = 1), state.counts)
    }

    @Test
    fun `성장 단계는 누적 깃털로 정해진다`() {
        assertEquals(Growth.Stage.FLUFF, Growth.stage(149))
        assertEquals(Growth.Stage.LITTLE, Growth.stage(150))
        assertEquals(Growth.Stage.GROWN, Growth.stage(5000))
    }

    @Test
    fun `셋째 단계 이름은 친구가 아니라 씩씩이다`() {
        // 구름이는 아기와 강가 손님을 "친구"라고 부릅니다. 단계 이름이 겹치면 그림책 키 재기에서 헷갈립니다.
        assertEquals(listOf("솜털", "꼬마", "씩씩이", "듬직이", "어른"), Growth.Stage.entries.map { it.title })
    }

    @Test
    fun `달린 시간 5분마다 기본 깃털을 준다`() {
        assertEquals(0, Growth.baseFeathers(4 * minute))
        assertEquals(6, Growth.baseFeathers(30 * minute))
    }

    @Test
    fun `여정 하나의 기본 깃털에는 상한이 있다`() {
        assertEquals(Growth.BASE_TRIP_CAP, Growth.baseFeathers(180 * minute))
    }

    @Test
    fun `도착하면 기본 깃털이 쌓인다`() {
        assertEquals(6, PetState().arrive(30).feathers)
    }

    @Test
    fun `하루 상한을 넘게 주지 않는다`() {
        val state = PetState().arrive(120).arrive(120, noon + 1).arrive(120, noon + 2)
        assertEquals(Growth.BASE_DAY_CAP, state.feathers)
    }

    @Test
    fun `날이 바뀌면 기본 깃털을 다시 준다`() {
        val state = PetState().arrive(120).arrive(120, noon + 1).arrive(120, noon + 24 * 60 * minute)
        assertEquals(Growth.BASE_DAY_CAP + Growth.BASE_TRIP_CAP, state.feathers)
    }

    @Test
    fun `지난 여정에서 늘어난 깃털을 기억한다`() {
        val state = PetState().arrive(30)
            .apply(DriveEvent.TripStarted(noon + 1, noon + 1))
            .apply(DriveEvent.SmoothStop(noon + 2, 1f))
            .arrive(10, noon + 3)
        assertEquals(3, state.lastGain)
        assertEquals(9, state.feathers)
    }

    @Test
    fun `정비로 경고등이 꺼지면 별을 준다`() {
        val state = PetState()
            .apply(DriveEvent.HealthAlert(noon, "TPMS"))
            .apply(DriveEvent.HealthRecovered(noon + 30 * minute, "TPMS"))
        assertEquals(1, state.stars)
        assertEquals(noon + 30 * minute, state.lastStarAtMs)
    }

    @Test
    fun `금방 꺼진 경고등은 별을 주지 않는다`() {
        val state = PetState()
            .apply(DriveEvent.HealthAlert(noon, "TPMS"))
            .apply(DriveEvent.HealthRecovered(noon + minute, "TPMS"))
        assertEquals(0, state.stars)
    }

    @Test
    fun `워셔액 부족은 아픈 것이 아니다`() {
        val state = PetState().apply(DriveEvent.HealthAlert(noon, PetState.WASHER_CODE))
        assertEquals(Mood.HAPPY, state.mood)
        assertTrue(state.softNeed != null)
    }

    @Test
    fun `진짜 경고등은 아픈 상태가 된다`() {
        val state = PetState().apply(DriveEvent.HealthAlert(noon, "ABS"))
        assertEquals(Mood.SICK, state.mood)
        assertFalse(state.softNeed != null)
    }
}
