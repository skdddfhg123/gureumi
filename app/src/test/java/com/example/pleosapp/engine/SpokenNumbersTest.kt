package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** 소리 내어 읽을 글의 숫자와 줄바꿈. 화면 글은 그대로입니다. */
class SpokenNumbersTest {

    @Test
    fun `세는 말 앞의 숫자는 고유어로 읽는다`() {
        assertEquals("부드럽게 멈춘 게 세 번! 깃털을 그만큼 모았어.", SpokenNumbers.forSpeech("부드럽게 멈춘 게 3번! 깃털을 그만큼 모았어."))
        assertEquals("도착! 깃털 열두 개를 모았어.", SpokenNumbers.forSpeech("도착! 깃털 12개를 모았어."))
        assertEquals("오늘은 한 시간 반쯤 같이 달렸어.", SpokenNumbers.forSpeech("오늘은 1시간 반쯤 같이 달렸어."))
        assertEquals("강가를 두 바퀴 돌았어.", SpokenNumbers.forSpeech("강가를 2바퀴 돌았어."))
    }

    @Test
    fun `고유어 수를 바르게 만든다`() {
        assertEquals(listOf("한", "두", "세", "네", "다섯", "열", "열한", "스무", "스물한", "서른", "아흔아홉"),
            listOf(1, 2, 3, 4, 5, 10, 11, 20, 21, 30, 99).map(SpokenNumbers::native))
    }

    @Test
    fun `첫 번째와 스무 번째`() {
        assertEquals("첫 번째 날", SpokenNumbers.forSpeech("1번째 날"))
        assertEquals("스무 번째 날", SpokenNumbers.forSpeech("20번째 날"))
    }

    @Test
    fun `한자어로 읽는 단위와 큰 수는 그대로 둔다`() {
        listOf("25분쯤", "100일을 함께했어", "3개월", "1번호", "깃털 120개", "1.5시간", "1,200개", "0번").forEach {
            assertEquals(it, SpokenNumbers.forSpeech(it))
        }
    }

    @Test
    fun `줄바꿈은 띄어쓰기로 읽는다`() {
        assertEquals("병이 가득 찼어. 선물을 열어 볼래?", SpokenNumbers.forSpeech("병이 가득 찼어.\n선물을 열어 볼래?"))
    }
}
