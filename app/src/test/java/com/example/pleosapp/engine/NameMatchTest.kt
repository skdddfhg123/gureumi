package com.example.pleosapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 이름 부르기(강가 장소 명세 7장). 글자가 아니라 소리로 이름을 찾습니다. */
class NameMatchTest {

    private val names = listOf(FamilySpeech.HERO_NAME) + SpiritPreset.entries.map { it.displayName }

    /**
     * 11/3 합성 음성 시험이 실제로 들은 문장(`C:\Android\tmp\pleos-name-stt\results-20261103-152930.csv`).
     * 30줄 가운데 겹친 문장을 빼면 19개입니다. 글자 비교로는 도토리 2/6, 라일락 0/6이 맞았습니다.
     */
    private val synthetic = listOf(
        "구름아" to "구름이", "구름아 안녕" to "구름이", "구름아 오늘 기분 어때?" to "구름이",
        "새싹아?" to "새싹", "새싹아 안녕" to "새싹", "새싹아 오늘 기분 어때?" to "새싹",
        "도토리아" to "도토리", "돗토리아" to "도토리", "돗토리아 안녕" to "도토리", "돗토리의 안녕" to "도토리",
        "돗토리야 오늘 기분 어때" to "도토리", "도토리의 오늘 기분 어때?" to "도토리",
        "개나리야" to "개나리", "개나리야 안녕" to "개나리", "개나리야 오늘 기분 어때?" to "개나리",
        "라일라가" to "라일락", "프라일러가" to "라일락", "라일라가 안녕" to "라일락", "라일라가 오늘 기분 어때?" to "라일락",
    )

    @Test
    fun `합성 음성이 들은 문장 19개가 모두 그 이름이 된다`() {
        synthetic.forEach { (heard, name) -> assertEquals(heard, name, NameMatch.find(heard, names)) }
    }

    @Test
    fun `이름 목록의 순서와 상관없다`() {
        synthetic.forEach { (heard, name) -> assertEquals(heard, name, NameMatch.find(heard, names.reversed())) }
    }

    @Test
    fun `부르는 말 없이 이름만 말해도 찾는다`() {
        listOf("도토리" to "도토리", "라일락" to "라일락", "구름이" to "구름이", "새싹이야" to "새싹", "구름이야" to "구름이")
            .forEach { (heard, name) -> assertEquals(heard, name, NameMatch.find(heard, names)) }
    }

    @Test
    fun `다른 말 뒤에 부르거나 이름을 띄어 써도 찾는다`() {
        assertEquals("개나리", NameMatch.find("안녕 개나리야", names))
        assertEquals("개나리", NameMatch.find("개 나리야", names))
    }

    @Test
    fun `조금 다르게 들려도 찾는다`() {
        assertEquals("라일락", NameMatch.find("라이락아", names))
        assertEquals("새싹", NameMatch.find("새삭아", names))
        assertEquals("도토리", NameMatch.find("도또리야", names))
    }

    @Test
    fun `이름이 없으면 null이다`() {
        listOf("", "   ", "오늘 기분 어때?", "안녕", "hello", "그럼 안녕", "나리야").forEach { assertNull(it, NameMatch.find(it, names)) }
        assertNull(NameMatch.find("도토리야", emptyList()))
    }

    @Test
    fun `다른 이름과 헷갈리면 null이다`() {
        assertNull(NameMatch.find("라일라가", listOf("라일락", "라일라")))
        assertEquals("라일락", NameMatch.find("라일라가", listOf("라일락", "개나리")))
    }
}
