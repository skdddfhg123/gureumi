package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 도착 일기를 읽는 동안 정차 중 말을 미루는 규칙. 서비스는 이 판정대로 500ms마다 묻습니다. */
class SpeechHoldTest {

    @Test
    fun `일기를 읽지 않으면 바로 말한다`() {
        assertEquals(SpeechHold.Next.SPEAK, SpeechHold.next(reading = false, inTrip = false, waitedMs = 0))
    }

    @Test
    fun `일기를 읽는 동안에는 기다린다`() {
        assertEquals(SpeechHold.Next.WAIT, SpeechHold.next(reading = true, inTrip = false, waitedMs = 0))
        assertEquals(SpeechHold.Next.WAIT, SpeechHold.next(reading = true, inTrip = false, waitedMs = SpeechHold.MAX_WAIT_MS - 1))
    }

    @Test
    fun `40초를 기다리면 읽는 표시가 남아 있어도 말한다`() {
        assertEquals(SpeechHold.Next.SPEAK, SpeechHold.next(reading = true, inTrip = false, waitedMs = SpeechHold.MAX_WAIT_MS))
        // 카드가 처음 펼친 30초보다 길어야 일기를 끝까지 읽습니다.
        assertTrue(SpeechHold.MAX_WAIT_MS > 30_000L)
    }

    @Test
    fun `기다리는 사이 출발하면 버린다`() {
        assertEquals(SpeechHold.Next.DROP, SpeechHold.next(reading = true, inTrip = true, waitedMs = 1_000))
        assertEquals(SpeechHold.Next.DROP, SpeechHold.next(reading = false, inTrip = true, waitedMs = 1_000))
        assertEquals(SpeechHold.Next.DROP, SpeechHold.next(reading = true, inTrip = true, waitedMs = SpeechHold.MAX_WAIT_MS))
    }

    @Test
    fun `서비스가 직접 읽는 일기는 TTS가 시작한 뒤 끝나면 풀린다`() {
        val own = SpeechHold.OwnReading()
        assertFalse(own.reading(0))
        own.asked(1_000)
        assertTrue("TTS가 시작하기 전에도 읽는 중으로 봄", own.reading(1_500))
        own.onSpeaking(true)
        assertTrue(own.reading(5_000))
        own.onSpeaking(false)
        assertFalse(own.reading(5_100))
    }

    @Test
    fun `앞 말의 끝 알림에는 풀리지 않는다`() {
        val own = SpeechHold.OwnReading()
        own.asked(1_000)
        own.onSpeaking(false) // 일기 낭독이 끊은 앞 말의 끝
        assertTrue(own.reading(1_200))
        own.onSpeaking(true)
        own.onSpeaking(false)
        assertFalse(own.reading(9_000))
    }

    @Test
    fun `다른 말이 시작해도 일기로 보지 않는다`() {
        val own = SpeechHold.OwnReading()
        own.onSpeaking(true) // 맡긴 일기 없이 시작한 인사
        assertFalse(own.reading(1_000))
    }

    @Test
    fun `TTS 알림이 없으면 40초 뒤에 풀린다`() {
        val own = SpeechHold.OwnReading()
        own.asked(1_000)
        assertTrue(own.reading(1_000 + SpeechHold.MAX_WAIT_MS - 1))
        assertFalse(own.reading(1_000 + SpeechHold.MAX_WAIT_MS))
    }

    @Test
    fun `출발하면 직접 읽던 일기를 기다리지 않는다`() {
        val own = SpeechHold.OwnReading()
        own.asked(1_000)
        own.onSpeaking(true)
        own.reset()
        assertFalse(own.reading(2_000))
    }
}
