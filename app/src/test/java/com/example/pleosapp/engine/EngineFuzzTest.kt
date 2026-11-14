package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 실제 차의 신호 순서는 예상과 다를 수 있습니다. 어떤 순서로 와도 깨지면 안 되는 약속만 봅니다. */
class EngineFuzzTest {

    private val doors = listOf(SeatSlot.REAR_LEFT, SeatSlot.REAR_RIGHT, SeatSlot.FRONT_RIGHT)

    private class Model {
        val belts = HashMap<SeatSlot, Boolean>()
        val gone = HashSet<SeatSlot>()
        val lastBuckleAt = HashMap<SeatSlot, Long>()
        var inTrip = false
    }

    private fun check(model: Model, event: DriveEvent) {
        when (event) {
            is DriveEvent.TripStarted -> {
                assertFalse("트립이 겹침", model.inTrip)
                model.inTrip = true
            }
            is DriveEvent.TripEnded -> {
                assertTrue("시작 없는 도착", model.inTrip)
                model.inTrip = false
                assertTrue(event.summary.durationMs >= 0)
                assertTrue((event.summary.seats intersect event.summary.tappedSeats).isEmpty())
            }
            is DriveEvent.RearBeltRequested -> {
                assertFalse("주행 중 부탁", model.inTrip)
                assertTrue("조수석 부탁", event.seat.isRear)
                assertEquals("맨 자리에 부탁", false, model.belts[event.seat])
            }
            is DriveEvent.RearBeltBuckled -> {
                assertTrue("조수석 깃털", event.seat.isRear)
                val last = model.lastBuckleAt[event.seat]
                assertTrue("10분 안에 다시 보상", last == null || event.atMs - last >= 10 * EngineConfig.MINUTE_MS)
                model.lastBuckleAt[event.seat] = event.atMs
            }
            is DriveEvent.SeatLeft -> {
                assertEquals("맨 자리를 배웅", false, model.belts[event.seat])
                assertTrue("두 번 배웅", model.gone.add(event.seat))
            }
            is DriveEvent.SeatReturned -> {
                assertEquals("풀린 자리를 반김", true, model.belts[event.seat])
                assertTrue("배웅 없는 반김", model.gone.remove(event.seat))
                assertTrue(event.absentMs >= 0)
            }
            is DriveEvent.SeatsAwake -> assertTrue("깨지 않은 자리에 인사", event.seats.containsAll(event.newly))
            else -> Unit
        }
    }

    private fun play(seed: Int) {
        val random = Random(seed)
        val model = Model()
        val engine = DriveEventEngine { check(model, it) }
        var now = 1_000L
        repeat(STEPS) {
            now += random.nextLong(100, 90_000)
            val seat = SeatSlot.entries.random(random)
            when (random.nextInt(8)) {
                0 -> engine.onGear(listOf(Gear.PARK, Gear.DRIVE, Gear.REVERSE, Gear.NEUTRAL).random(random), now)
                1, 2 -> {
                    val buckled = random.nextBoolean()
                    model.belts[seat] = buckled
                    engine.onSeatBelt(seat, buckled, now)
                }
                3, 4 -> engine.onRearDoor(doors.random(random), random.nextBoolean(), now)
                5 -> engine.onSeatTap(seat, now)
                6 -> engine.onSpeed(random.nextFloat() * 30f, now)
                else -> engine.tick(now)
            }
        }
    }

    @Test
    fun `어떤 순서로 신호가 와도 약속이 깨지지 않는다`() {
        (0 until SEEDS).forEach(::play)
    }

    private companion object {
        const val SEEDS = 300
        const val STEPS = 500
    }
}
