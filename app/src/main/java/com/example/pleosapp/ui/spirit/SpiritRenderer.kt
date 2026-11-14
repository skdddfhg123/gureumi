package com.example.pleosapp.ui.spirit

import com.example.pleosapp.engine.Mood

/**
 * 구름이를 그리는 쪽이 지켜야 할 계약(docs/design/brief.md, docs/concept/production.md).
 * 1차는 [SpiritView]가 코드로 그리고, 2차에 Rive·Lottie·스프라이트 구현체로 바꿔 끼워도 화면 코드는 그대로입니다.
 */
interface SpiritRenderer {
    /** 기분 전환. 400ms 안에 색·눈·입이 보간되어야 합니다. */
    fun setMood(mood: Mood)

    /** 레벨 1~5. 뭉게 개수와 크기로 표현합니다. */
    fun setLevel(level: Int)

    /** 도착 카드 연출(1.2초 원샷). 끝나면 현재 기분으로 돌아옵니다. */
    fun celebrate()

    /** TTS 재생 중 입을 움직입니다. */
    fun setTalking(talking: Boolean)
}
