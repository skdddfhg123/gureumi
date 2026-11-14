package com.example.pleosapp.engine

/**
 * 소리 내어 읽을 글만 고칩니다. 화면 글은 숫자 그대로 둡니다.
 * - 1~99 뒤에 번·개·시간·바퀴·마리·살이 오면 고유어 수로 씁니다. TTS가 "3번"을 "삼 번"으로 읽을 수 있어서입니다.
 *   고유어로 써 두면 엔진이 어떻게 읽든 "세 번"으로 들립니다.
 * - 줄바꿈은 띄어쓰기로 바꿉니다. 그림책 문장의 줄바꿈이 그대로 TTS로 가면 끊어 읽을 수 있습니다.
 * 분·일·년·km처럼 한자어로 읽는 단위와 100 이상은 그대로 둡니다.
 */
object SpokenNumbers {
    /** 앞에 숫자·점·쉼표가 붙은 수(1.5, 1,200)와 개월·번호·번지는 건드리지 않습니다. */
    private val COUNTED = Regex("""(?<![\d.,])(\d{1,2}) ?(번째|번(?![호지])|개(?!월)|시간|바퀴|마리|살)""")

    private val ONES = listOf("", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉")
    private val TENS = listOf("", "열", "스물", "서른", "마흔", "쉰", "예순", "일흔", "여든", "아흔")

    fun forSpeech(text: String): String = COUNTED.replace(text.replace('\n', ' ')) { match ->
        val n = match.groupValues[1].toInt()
        val unit = match.groupValues[2]
        when {
            n == 0 -> match.value
            n == 1 && unit == "번째" -> "첫 번째"
            else -> "${native(n)} $unit"
        }
    }

    /** 1~99의 고유어 수. 단위 앞이라 스물은 스무가 됩니다. */
    fun native(n: Int): String {
        require(n in 1..99) { "1~99만 고유어로 읽습니다: $n" }
        val tens = n / 10
        val ones = n % 10
        return if (ones == 0 && tens == 2) "스무" else TENS[tens] + ONES[ones]
    }
}
