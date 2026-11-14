package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyCodecTest {

    @Test
    fun `저장한 그대로 읽는다`() {
        val state = FamilyState(
            spirits = listOf(FamilySpirit(SpiritPreset.SOM, SeatSlot.REAR_LEFT), FamilySpirit(SpiritPreset.ANGAE, SeatSlot.FRONT_RIGHT)),
            pending = HeldMessage(SpiritPreset.SOM, 2),
            delivered = DeliveredMessage(1_793_500_000_000, SpiritPreset.ANGAE, 5),
            away = setOf(SeatSlot.REAR_LEFT),
        )
        assertEquals(state, FamilyCodec.decode(FamilyCodec.encode(state)))
    }

    @Test
    fun `이름 문자열을 저장하지 않는다`() {
        val text = FamilyCodec.encode(FamilyState().adopt(SpiritPreset.SOM, SeatSlot.REAR_LEFT))
        assertFalse(text.contains(SpiritPreset.SOM.displayName))
    }

    @Test
    fun `없어진 정령이 적힌 낡은 파일도 나머지를 읽는다`() {
        val state = FamilyCodec.decode(
            """{"spirits":[{"preset":"byeol","seat":"REAR_LEFT"},{"preset":"noeul","seat":"FRONT_RIGHT"},{"preset":"som","seat":"REAR_RIGHT"}],"pending":{"preset":"byeol","phrase":1},"delivered":{"tripId":1,"preset":"noeul","phrase":1}}""",
        )
        assertEquals(listOf(FamilySpirit(SpiritPreset.SOM, SeatSlot.REAR_RIGHT)), state.spirits)
        assertNull(state.pending)
        assertNull(state.delivered)
        assertTrue(state.away.isEmpty())
    }

    @Test
    fun `깨진 파일이면 비어 있는 상태로 시작한다`() {
        assertEquals(FamilyState(), FamilyCodec.decode("{\"spirits\":[{\"preset\""))
        assertEquals(FamilyState(), FamilyCodec.decode(""))
    }

    @Test
    fun `자리 값이 없거나 모르거나 겹쳐도 아기를 잃지 않는다`() {
        // 11/5부터 자리는 엔진이 앉힌 곳일 뿐입니다. 자리 값 하나 때문에 가족을 비우지 않습니다.
        val state = FamilyCodec.decode(
            """{"spirits":[{"preset":"som","seat":"ROOF"},{"preset":"angae"},{"preset":"saeteol","seat":"LEFT"},{"preset":"yangtte","seat":"REAR_LEFT"},{"preset":"som","seat":"REAR_RIGHT"}]}""",
        )
        assertEquals(listOf(SpiritPreset.SOM, SpiritPreset.ANGAE, SpiritPreset.SAETEOL, SpiritPreset.YANGTTE), state.spirits.map { it.preset })
        assertEquals(state.spirits.size, state.spirits.map { it.seat }.toSet().size)
        // 10/31 이전 이름(LEFT)도 읽고, 앞서 적힌 자리는 그대로 둡니다.
        assertEquals(SeatSlot.REAR_LEFT, state.spirits.first { it.preset == SpiritPreset.SAETEOL }.seat)
    }

    @Test
    fun `지키는 자리는 아기가 앉은 자리만 읽는다`() {
        val state = FamilyCodec.decode("""{"spirits":[{"preset":"som","seat":"REAR_LEFT"}],"away":["REAR_LEFT","REAR_RIGHT"]}""")
        assertEquals(setOf(SeatSlot.REAR_LEFT), state.away)
    }

    @Test
    fun `탄 시각과 지난 트립이 깨져도 가족은 그대로 읽는다`() {
        val state = FamilyCodec.decode(
            """{"spirits":[{"preset":"som","seat":"REAR_LEFT"}],"lastRodeAt":{"som":"어제","angae":5},"lastRide":{"presets":["som"]}}""",
        )
        assertEquals(listOf(SpiritPreset.SOM), state.spirits.map { it.preset })
        assertEquals(mapOf(SpiritPreset.ANGAE to 5L), state.lastRodeAt)
        assertNull(state.lastRide)
    }

    @Test
    fun `모르는 정령은 건너뛰고 나머지는 읽는다`() {
        val state = FamilyCodec.decode(
            """{"spirits":[{"preset":"gone","seat":"REAR_LEFT"},{"preset":"som","seat":"REAR_RIGHT"}]}""",
        )
        assertEquals(listOf(FamilySpirit(SpiritPreset.SOM, SeatSlot.REAR_RIGHT)), state.spirits)
    }

    @Test
    fun `범위를 벗어난 문장 번호는 버린다`() {
        val state = FamilyCodec.decode("""{"spirits":[],"pending":{"preset":"som","phrase":99}}""")
        assertNull(state.pending)
        assertTrue(state.spirits.isEmpty())
    }
}
