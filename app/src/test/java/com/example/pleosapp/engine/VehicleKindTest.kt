package com.example.pleosapp.engine

import android.car.FuelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 전기차 구분과 연료 표현(운전자 중심 보강 명세 6장). */
class VehicleKindTest {

    @Test
    fun `내연기관은 ENGINE이다`() {
        listOf(FuelType.UNLEADED, FuelType.DIESEL_1, FuelType.LPG, FuelType.BIODIESEL).forEach {
            assertEquals(VehicleKind.ENGINE, VehicleKind.of(listOf(it)))
        }
    }

    @Test
    fun `전기차와 수소차는 엔진오일이 없다`() {
        assertEquals(VehicleKind.ELECTRIC, VehicleKind.of(listOf(FuelType.ELECTRIC)))
        assertEquals(VehicleKind.ELECTRIC, VehicleKind.of(listOf(FuelType.HYDROGEN)))
    }

    @Test
    fun `플러그인 하이브리드는 ENGINE이다`() {
        assertEquals(VehicleKind.ENGINE, VehicleKind.of(listOf(FuelType.UNLEADED, FuelType.ELECTRIC)))
    }

    @Test
    fun `값이 없거나 뜻을 모르면 UNKNOWN이다`() {
        assertEquals(VehicleKind.UNKNOWN, VehicleKind.of(emptyList()))
        assertEquals(VehicleKind.UNKNOWN, VehicleKind.of(listOf(FuelType.UNKNOWN)))
        assertEquals(VehicleKind.UNKNOWN, VehicleKind.of(listOf(FuelType.OTHER)))
    }

    @Test
    fun `모르는 값은 아는 값을 가리지 않는다`() {
        assertEquals(VehicleKind.ELECTRIC, VehicleKind.of(listOf(FuelType.UNKNOWN, FuelType.ELECTRIC)))
    }

    @Test
    fun `전기차 수첩에는 엔진오일이 없다`() {
        assertEquals(listOf(CareItem.ROTATION, CareItem.FILTER, CareItem.WASH), CareRules.items(VehicleKind.ELECTRIC))
    }

    @Test
    fun `내연기관과 모름은 전부 보여 준다`() {
        assertEquals(CareItem.entries, CareRules.items(VehicleKind.ENGINE))
        assertEquals(CareItem.entries, CareRules.items(VehicleKind.UNKNOWN))
    }

    private val electric = CareState(firstOdoKm = 0f, firstSeenAtMs = 0L, kind = VehicleKind.ELECTRIC)

    @Test
    fun `전기차는 엔진오일을 알리지 않는다`() {
        // 위치 교환과 주기가 같으므로 함께 때가 되어도 엔진오일은 나오지 않습니다.
        assertEquals(CareItem.ROTATION, electric.nextDue(9_500f))
        assertEquals(CareItem.OIL, electric.copy(kind = VehicleKind.ENGINE).nextDue(9_500f))
        assertEquals(CareItem.OIL, electric.copy(kind = VehicleKind.UNKNOWN).nextDue(9_500f))
    }

    @Test
    fun `전기차는 주기로 털이 푸석해지지 않는다`() {
        assertFalse(CareRules.look(PetState(), electric, 9_500f, 0L).dullFur)
        assertTrue(CareRules.look(PetState(), electric.copy(kind = VehicleKind.ENGINE), 9_500f, 0L).dullFur)
    }

    @Test
    fun `전기차라도 경고등이 켜지면 털이 푸석하다`() {
        assertTrue(CareRules.look(PetState(ailments = setOf("ENGINE_OIL_PRESSURE")), electric, 0f, 0L).dullFur)
    }

    @Test
    fun `전기차는 세차로 부르르 털기를 배운다`() {
        assertEquals(CareItem.WASH, CareRules.teacher(Reward.MOTION_SHAKE, VehicleKind.ELECTRIC))
        assertEquals(CareItem.OIL, CareRules.teacher(Reward.MOTION_SHAKE, VehicleKind.ENGINE))
        assertTrue(Reward.MOTION_SHAKE in CareRules.owned(PetState(), GiftState(), electric.done(CareItem.WASH, 1L, 0f)))
    }

    @Test
    fun `내연기관은 세차로 부르르 털기를 배우지 않는다`() {
        val care = electric.copy(kind = VehicleKind.ENGINE).done(CareItem.WASH, 1L, 0f)
        assertFalse(Reward.MOTION_SHAKE in CareRules.owned(PetState(), GiftState(), care))
    }

    @Test
    fun `가르칠 정비가 없는 동작은 없다고 말한다`() {
        assertNull(CareRules.teacher(Reward.MOTION_SWAY, VehicleKind.ELECTRIC))
    }

    @Test
    fun `전기차 수첩에서도 모든 정비 동작을 배울 수 있다`() {
        val motions = CareItem.entries.mapNotNull { it.reward }
        motions.forEach { assertTrue(it.title, CareRules.teacher(it, VehicleKind.ELECTRIC) in CareRules.items(VehicleKind.ELECTRIC)) }
    }

    @Test
    fun `이미 챙긴 엔진오일 기록은 전기차로 알게 되어도 지우지 않는다`() {
        val care = CareState(firstOdoKm = 0f).done(CareItem.OIL, 1L, 0f).observe(VehicleKind.ELECTRIC)
        assertTrue(Reward.MOTION_SHAKE in CareRules.owned(PetState(), GiftState(), care))
    }

    @Test
    fun `한 번 알게 된 차종은 모름으로 돌아가지 않는다`() {
        assertEquals(VehicleKind.ELECTRIC, electric.observe(VehicleKind.UNKNOWN).kind)
        assertEquals(VehicleKind.ENGINE, CareState().observe(VehicleKind.ENGINE).kind)
    }

    @Test
    fun `차종을 파일에 쓰고 읽는다`() {
        assertEquals(electric, KeepsakeCodec.decodeCare(KeepsakeCodec.encodeCare(electric)))
    }

    @Test
    fun `차종이 없는 옛 파일은 모름으로 읽는다`() {
        val old = """{"firstOdoKm":12.5,"firstSeenAtMs":7,"records":[]}"""
        assertEquals(CareState(firstOdoKm = 12.5f, firstSeenAtMs = 7L), KeepsakeCodec.decodeCare(old))
    }

    @Test
    fun `모르는 차종 값은 모름으로 읽는다`() {
        assertEquals(VehicleKind.UNKNOWN, KeepsakeCodec.decodeCare("""{"kind":"PLASMA","records":[]}""").kind)
    }

    @Test
    fun `수첩의 연료 충전 워셔액 문장은 일반 경고가 아니다`() {
        assertEquals("배가 고파. 주유할 때가 됐어.", PetState.healthLine("LOW_FUEL"))
        assertEquals("배가 고파. 충전할 때가 됐어.", PetState.healthLine("LOW_EV_BATTERY"))
        assertEquals("앞유리가 조금 뿌예. 워셔액을 채워 주면 좋겠어.", PetState.healthLine(PetState.WASHER_CODE))
    }

    @Test
    fun `모르는 경고는 일반 문장이다`() {
        assertEquals("차 어딘가가 불편해. 경고등을 확인해 줘.", PetState.healthLine("NEW_CODE"))
    }

    private val tenMinutes = 10 * 60_000L

    private fun refuelled(code: String, afterMs: Long) = PetState()
        .apply(DriveEvent.HealthAlert(0, code))
        .apply(DriveEvent.HealthRecovered(afterMs, code))

    @Test
    fun `주유 뒤 고맙다고 말한다`() {
        assertEquals(HomeSpeech.FED, HomeSpeech.line(refuelled("LOW_FUEL", tenMinutes).copy(trips = 5), FamilyState(), emptySet()))
        assertEquals(HomeSpeech.FED, HomeSpeech.line(refuelled("LOW_EV_BATTERY", tenMinutes).copy(trips = 5), FamilyState(), emptySet()))
    }

    @Test
    fun `주유는 별을 주지 않는다`() {
        assertEquals(0, refuelled("LOW_FUEL", tenMinutes).stars)
    }

    @Test
    fun `깜빡인 연료 경고에는 고맙다고 말하지 않는다`() {
        assertFalse(refuelled("LOW_FUEL", tenMinutes - 1).fed)
    }

    @Test
    fun `다른 경고가 꺼져도 고맙다는 말은 수첩 쪽이다`() {
        assertFalse(refuelled("TPMS", tenMinutes).fed)
        assertFalse(refuelled(PetState.WASHER_CODE, tenMinutes).fed)
    }

    private val trip = TripSummary(1, 600_000, 1_000f, 0, 0, 0, 0)

    @Test
    fun `다음 도착까지만 고맙다고 말한다`() {
        val fed = refuelled("LOW_FUEL", tenMinutes)
        val blip = trip.copy(durationMs = 5_000)
        assertTrue(fed.apply(DriveEvent.TripStarted(1, 1)).apply(DriveEvent.TripEnded(2, blip)).fed)
        assertFalse(fed.apply(DriveEvent.TripStarted(1, 1)).apply(DriveEvent.TripEnded(2, trip)).fed)
    }

    @Test
    fun `고맙다는 말보다 다시 배고픈 말이 먼저다`() {
        val hungry = refuelled("LOW_FUEL", tenMinutes).apply(DriveEvent.HealthAlert(tenMinutes + 1, "LOW_FUEL"))
        assertEquals(HomeSpeech.FUEL, HomeSpeech.line(hungry.copy(trips = 5), FamilyState(), emptySet()))
    }

    @Test
    fun `원장을 다시 재생해도 같은 값이다`() {
        val events = listOf(DriveEvent.HealthAlert(0, "LOW_FUEL"), DriveEvent.HealthRecovered(tenMinutes, "LOW_FUEL"))
        assertTrue(events.fold(PetState(), PetState::apply).fed)
    }
}
