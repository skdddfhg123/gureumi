package com.example.pleosapp.ui.voice

import android.content.Context
import java.io.File

/**
 * 검증 빌드(debug, verify)의 귀입니다. Pleos 온디바이스 음성 인식을 씁니다.
 * 촬영 표시(files/film_mode)가 있으면 귀를 만들지 않아 "이름 부르기" 단추가 숨습니다.
 * 제출 APK에 없는 단추가 다른 컷에 나오지 않게 합니다. 켜고 끄기는 `FILM=1 scripts/demo.sh`입니다.
 */
object NameEarsMaker {
    fun make(context: Context): NameEars? = if (File(context.filesDir, FILM_MODE).exists()) null else SttEars(context)

    private const val FILM_MODE = "film_mode"
}
