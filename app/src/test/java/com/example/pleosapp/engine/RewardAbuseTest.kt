package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** 보상 남용 방지와 재시작 뒤 휴식 복원. */
class RewardAbuseTest {

    private class Rig(restored: RestClock = RestClock()) {
        val events = mutableListOf<DriveEvent>()
        val engine = DriveEventEngine(EngineConfig(arrivalHoldMs = 0), restored = restored) { events += it }
        var now = 100 * EngineConfig.HOUR_MS

        inline fun <reified T : DriveEvent> count() = events.count { it is T }
        fun gear(g: Gear) = engine.onGear(g, now)
        fun idle(ms: Long) { now += ms; engine.tick(now) }
    }

    @Test
    fun `시동을 끄고 쉰 시간도 재시작 뒤 휴식으로 인정한다`() {
        val rig = Rig()
        val restarted = Rig(RestClock(driveMs = 2 * EngineConfig.HOUR_MS, parkedSinceMs = rig.now - 20 * EngineConfig.MINUTE_MS))
        restarted.gear(Gear.PARK)
        restarted.gear(Gear.DRIVE)
        assertEquals(1, restarted.count<DriveEvent.RestTaken>())
    }

    @Test
    fun `재시작 뒤 쉰 시간이 모자라면 휴식이 아니다`() {
        val rig = Rig()
        val restarted = Rig(RestClock(driveMs = 2 * EngineConfig.HOUR_MS, parkedSinceMs = rig.now - 5 * EngineConfig.MINUTE_MS))
        restarted.gear(Gear.PARK)
        restarted.gear(Gear.DRIVE)
        assertEquals(0, restarted.count<DriveEvent.RestTaken>())
    }

    @Test
    fun `원장을 접으면 휴식 시계가 복원된다`() {
        val pet = listOf(
            DriveEvent.TripStarted(1_000, 1_000),
            DriveEvent.TripEnded(3_601_000, TripSummary(1_000, 3_600_000, 0f, 0, 0, 0, 0)),
        ).fold(PetState(), PetState::apply)
        assertEquals(RestClock(3_600_000, 3_601_000), pet.restClock)

        val rested = pet.apply(DriveEvent.TripStarted(3_601_000 + 15 * EngineConfig.MINUTE_MS, 2))
        assertEquals(RestClock(0, null), rested.restClock)
        val shortStop = pet.apply(DriveEvent.TripStarted(3_601_000 + 5 * EngineConfig.MINUTE_MS, 2))
        assertEquals(RestClock(3_600_000, null), shortStop.restClock)
    }

    @Test
    fun `P와 D를 반복해도 벨트 보너스는 10분에 한 번이다`() {
        val rig = Rig()
        rig.engine.onTelltales(emptySet(), rig.now) // 벨트 경고 없음 = 모두 체결
        repeat(5) {
            rig.gear(Gear.DRIVE)
            rig.idle(5_000)
            rig.gear(Gear.PARK)
            rig.idle(5_000)
        }
        assertEquals(1, rig.count<DriveEvent.BeltsReady>())
        rig.idle(10 * 60_000L)
        rig.gear(Gear.DRIVE)
        assertEquals(2, rig.count<DriveEvent.BeltsReady>())
    }

    @Test
    fun `깃털을 기다리는 동안에도 안전벨트 도장은 트립마다 찍는다`() {
        val rig = Rig()
        rig.engine.onTelltales(emptySet(), rig.now)
        repeat(2) {
            rig.gear(Gear.DRIVE)
            rig.idle(20_000)
            rig.gear(Gear.PARK)
            rig.idle(5_000)
        }
        val trips = rig.events.filterIsInstance<DriveEvent.TripEnded>()
        assertEquals(1, rig.count<DriveEvent.BeltsReady>())
        assertEquals(listOf(true, true), trips.map { it.summary.driverBelted })
    }

    @Test
    fun `안전벨트 경고가 켜진 트립은 도장을 찍지 않는다`() {
        val rig = Rig()
        rig.engine.onTelltales(setOf(EngineConfig().beltCode), rig.now)
        rig.gear(Gear.DRIVE)
        rig.idle(20_000)
        rig.gear(Gear.PARK)
        rig.idle(5_000)
        assertEquals(false, rig.events.filterIsInstance<DriveEvent.TripEnded>().single().summary.driverBelted)
    }
}
