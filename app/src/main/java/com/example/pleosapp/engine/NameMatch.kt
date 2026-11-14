package com.example.pleosapp.engine

/**
 * 들은 문장에서 부른 이름을 찾습니다. 글자가 아니라 소리(자모)로 비교합니다.
 * 이름 뒤에 붙은 부르는 말("아", "야", "이")과 그 뒤의 말은 보지 않습니다.
 * 받침이 뒤로 넘어간 꼴("라일라가")과 사이 받침("돗토리")은 같은 소리로 봅니다.
 * 두 이름이 비슷하게 가까우면 헷갈린 것으로 보고 null을 돌려줍니다.
 */
object NameMatch {

    /** [names] 가운데 [heard]에서 부른 이름 하나. 못 찾거나 헷갈리면 null입니다. */
    fun find(heard: String, names: List<String>): String? {
        val spans = spans(heard)
        if (spans.isEmpty()) return null
        val scored = names.distinct()
            .mapNotNull { name -> score(name, spans)?.let { name to it } }
            .sortedBy { it.second }
        val best = scored.firstOrNull()?.takeIf { it.second <= MAX_SCORE } ?: return null
        val runnerUp = scored.getOrNull(1)
        return if (runnerUp != null && runnerUp.second - best.second < MARGIN) null else best.first
    }

    /** 이름이 가장 잘 맞는 곳의 어긋남을 이름 길이로 나눈 값. 0이면 그대로 들렸습니다. */
    private fun score(name: String, spans: List<List<Char>>): Double? =
        variants(name).minOfOrNull { pattern -> spans.minOf { cost(pattern, it) } / pattern.size }

    /** 받침 뒤의 "이"는 부를 때 빠집니다. 구름이를 "구름아"라고 부릅니다. */
    private fun variants(name: String): List<List<Char>> {
        val syllables = name.filter { it in FIRST..LAST }.toList()
        val stem = syllables.dropLast(1)
            .takeIf { syllables.size >= 2 && syllables.last() == '이' && hasFinal(syllables[syllables.size - 2]) }
        return listOfNotNull(sounds(syllables), stem?.let(::sounds)).filter { it.isNotEmpty() }
    }

    /** 낱말과 붙여 읽은 이웃 두 낱말. 음성 인식이 이름 사이를 띄어 쓰기도 합니다. */
    private fun spans(heard: String): List<List<Char>> {
        val words = heard.split(NOT_HANGUL).filter { it.isNotEmpty() }
        return (words + words.zipWithNext { a, b -> a + b }).map { sounds(it.toList()) }
    }

    /** 음절을 소리 순서대로 이은 자모. 첫소리 ㅇ은 소리가 없어 뺍니다. */
    private fun sounds(syllables: List<Char>): List<Char> {
        val out = ArrayList<Char>()
        syllables.forEachIndexed { index, syllable ->
            val code = syllable - FIRST
            val initial = code / SYLLABLES_PER_INITIAL
            val final = code % FINALS_PER_MEDIAL
            if (initial != SILENT) out += INITIALS[initial]
            out += MEDIALS[code % SYLLABLES_PER_INITIAL / FINALS_PER_MEDIAL]
            if (final == 0) return@forEachIndexed
            val next = syllables.getOrNull(index + 1)?.let { (it - FIRST) / SYLLABLES_PER_INITIAL }
            when {
                // 모음 앞의 받침은 다음 소리가 됩니다. 이어 놓으면 "라일락아"와 "라일라가"가 같습니다.
                next == SILENT -> out += MOVED[final].toList()
                // 자음 앞의 ㄷ 소리 받침은 사이 받침으로 보고 뺍니다. "돗토리"와 "도토리"가 같습니다.
                next != null && NEUTRAL[final] == 'ㄷ' -> Unit
                else -> out += NEUTRAL[final]
            }
        }
        return out
    }

    /** 이름이 낱말 앞쪽에서 시작하면 맞춥니다. 앞의 군소리는 조금만 건너뛰고, 이름 뒤의 말은 셈하지 않습니다. */
    private fun cost(pattern: List<Char>, text: List<Char>): Double =
        (0..minOf(MAX_LEAD, text.size)).minOf { start -> start * LEAD_COST + edit(pattern, text.subList(start, text.size)) }

    /** 편집 거리. [text] 끝에 남은 소리는 셈하지 않습니다. */
    private fun edit(pattern: List<Char>, text: List<Char>): Double {
        var previous = DoubleArray(text.size + 1) { it.toDouble() }
        pattern.forEachIndexed { i, p ->
            val current = DoubleArray(text.size + 1)
            current[0] = i + 1.0
            text.forEachIndexed { j, t ->
                current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1, previous[j] + substitution(p, t))
            }
            previous = current
        }
        return previous.min()
    }

    /** 사람 귀에도 비슷한 소리는 반만 틀린 것으로 봅니다. ㅐ와 ㅔ는 같은 소리입니다. */
    private fun substitution(a: Char, b: Char): Double = when {
        a == b -> 0.0
        SAME.any { a in it && b in it } -> 0.0
        CLOSE.any { a in it && b in it } -> HALF
        else -> 1.0
    }

    private fun hasFinal(syllable: Char): Boolean = (syllable - FIRST) % FINALS_PER_MEDIAL != 0

    private const val FIRST = '가'
    private const val LAST = '힣'
    private const val SYLLABLES_PER_INITIAL = 588
    private const val FINALS_PER_MEDIAL = 28
    private const val SILENT = 11

    private const val INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private const val MEDIALS = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"

    /** 자음 앞이나 끝에서 나는 받침 소리. 0번은 받침 없음입니다. */
    private const val NEUTRAL = " ㄱㄱㄱㄴㄴㄴㄷㄹㄱㅁㄹㄹㄹㅂㄹㅁㅂㅂㄷㄷㅇㄷㄷㄱㄷㅂㄷ"

    /** 모음 앞에서 다음 소리로 넘어가는 받침. 겹받침은 뒤의 것만 넘어가고 ㅎ은 소리가 없어집니다. */
    private val MOVED = listOf(
        "", "ㄱ", "ㄲ", "ㄱㅅ", "ㄴ", "ㄴㅈ", "ㄴ", "ㄷ", "ㄹ", "ㄹㄱ", "ㄹㅁ", "ㄹㅂ", "ㄹㅅ", "ㄹㅌ",
        "ㄹㅍ", "ㄹ", "ㅁ", "ㅂ", "ㅂㅅ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "",
    )

    private val SAME = listOf("ㅐㅔ", "ㅒㅖ", "ㅙㅚㅞ")
    private val CLOSE = listOf(
        "ㄱㄲㅋ", "ㄷㄸㅌ", "ㅂㅃㅍ", "ㅅㅆ", "ㅈㅉㅊ", "ㄴㄹ",
        "ㅏㅓ", "ㅓㅗ", "ㅗㅜ", "ㅜㅡ", "ㅡㅢ", "ㅣㅢ", "ㅏㅑ", "ㅓㅕ", "ㅗㅛ", "ㅜㅠ",
        "ㅏㅘ", "ㅓㅝ", "ㅣㅟ", "ㅐㅒ", "ㅔㅖ", "ㅐㅙ", "ㅔㅞ",
    )
    private val NOT_HANGUL = Regex("[^가-힣]+")

    private const val HALF = 0.5
    /** 앞의 군소리("프라일러가"의 "프")는 소리 셋까지 건너뜁니다. 하나에 이만큼 틀린 것으로 봅니다. */
    private const val LEAD_COST = 0.25
    private const val MAX_LEAD = 3
    /** 이름 길이에 대해 이만큼까지 어긋나도 그 이름입니다. "그럼"을 "구름"으로 듣지 않을 만큼입니다. */
    private const val MAX_SCORE = 0.25
    /** 둘째로 가까운 이름이 이만큼 안쪽이면 헷갈린 것입니다. */
    private const val MARGIN = 0.1
}
