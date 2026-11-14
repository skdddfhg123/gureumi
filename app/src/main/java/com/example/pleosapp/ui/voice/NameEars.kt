package com.example.pleosapp.ui.voice

/**
 * 단추를 누른 뒤 한 번만 듣는 귀입니다(강가 장소 명세 7장). 앱은 늘 듣지 않습니다.
 * 음성 인식은 검증 빌드에만 들어 있습니다. 제출 빌드에서는 [NameEarsMaker.make]가 null이라 단추가 없습니다.
 * 콜백은 모두 메인 스레드로 옵니다.
 */
interface NameEars {

    /** 한 번 듣기 시작합니다. 듣는 중에 다시 부르면 듣는 사람만 바뀝니다. */
    fun listen(listener: Listener)

    /** 그만 듣습니다. 이후 콜백은 오지 않습니다. */
    fun stop()

    fun release()

    interface Listener {
        /** 마이크가 열렸습니다. */
        fun onReady()

        /** 들리는 대로 고쳐 가는 글자입니다. 마지막 글자면 [final]이 참입니다. */
        fun onHeard(text: String, final: Boolean)

        /** 듣기가 끝났습니다. 시작하지 못했거나 도중에 고장 나면 [failed]가 참입니다. */
        fun onDone(failed: Boolean)
    }
}
