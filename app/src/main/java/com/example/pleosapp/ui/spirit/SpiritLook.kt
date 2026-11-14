package com.example.pleosapp.ui.spirit

import com.example.pleosapp.engine.Mood

enum class EyeStyle { DOT, SWIRL, CLOSED, HALF, ARC_UP }

enum class MouthStyle { SMILE, WAVY, FROWN, BIG_SMILE }

/**
 * 구름이의 생김새 한 벌. 수치는 docs/concept/character-sheet.md 3장(캐릭터 시트)과
 * docs/assets/gureumi/ 아래 SVG 초안의 240×200 좌표계를 그대로 씁니다.
 * 색·기울기·소품 투명도는 [lerp]로 보간하고, 눈·입 모양은 뷰에서 크로스페이드합니다.
 */
data class SpiritLook(
    val body: Int,
    val shade: Int,
    val shadow: Int,
    val shadowAlpha: Float,
    val shadowY: Float,
    val shadowRy: Float,
    val eyes: EyeStyle,
    val mouth: MouthStyle,
    val cheek: Int,
    val cheekAlpha: Float,
    val cheekRadius: Float,
    /** 몸 기울기(도). 음수가 왼쪽으로 기울어짐. */
    val tiltDeg: Float,
    /** 좌우 흔들림 진폭(도). 0이면 흔들리지 않음. */
    val wobbleDeg: Float,
    /** idle bob 진폭 배율. */
    val bobAmp: Float,
    val blinks: Boolean,
    // 소품 투명도 0~1. 보간되면서 자연스럽게 나타나고 사라집니다.
    val sweat: Float = 0f,
    val motionLines: Float = 0f,
    val rain: Float = 0f,
    val brows: Float = 0f,
    val glow: Float = 0f,
    val stars: Float = 0f,
    val confetti: Float = 0f,
) {
    companion object {
        val HAPPY = SpiritLook(
            body = 0xFFFFFFFF.toInt(), shade = 0xFFD6E8FC.toInt(),
            shadow = 0xFFCFE2FA.toInt(), shadowAlpha = 0.6f, shadowY = 176f, shadowRy = 8f,
            eyes = EyeStyle.DOT, mouth = MouthStyle.SMILE,
            cheek = 0xFFFFC2CF.toInt(), cheekAlpha = 0.9f, cheekRadius = 7f,
            tiltDeg = 0f, wobbleDeg = 0f, bobAmp = 1f, blinks = true,
        )

        val DIZZY = HAPPY.copy(
            body = 0xFFF1FAEE.toInt(), shade = 0xFFCFE8D8.toInt(),
            eyes = EyeStyle.SWIRL, mouth = MouthStyle.WAVY, cheekAlpha = 0.6f,
            tiltDeg = -7f, wobbleDeg = 6f, sweat = 1f, motionLines = 1f,
        )

        val SICK = HAPPY.copy(
            body = 0xFFE3E7EE.toInt(), shade = 0xFFC9D0DA.toInt(),
            shadowAlpha = 0.5f, shadowY = 188f, shadowRy = 6f,
            eyes = EyeStyle.HALF, mouth = MouthStyle.FROWN, cheekAlpha = 0.45f,
            bobAmp = 0.5f, blinks = false, rain = 1f, brows = 1f,
        )

        /** 기분이 아니라 도착 카드 연출입니다. */
        val CELEBRATE = HAPPY.copy(
            shade = 0xFFFBE7C2.toInt(), shadow = 0xFFF6E3B4.toInt(), shadowAlpha = 0.7f,
            eyes = EyeStyle.ARC_UP, mouth = MouthStyle.BIG_SMILE,
            cheek = 0xFFFFB3C2.toInt(), cheekAlpha = 0.95f, cheekRadius = 8f,
            blinks = false, glow = 1f, stars = 1f, confetti = 1f,
        )

        fun of(mood: Mood): SpiritLook = when (mood) {
            Mood.HAPPY -> HAPPY
            Mood.DIZZY -> DIZZY
            Mood.SICK -> SICK
        }

        /** 연속 값은 보간, 눈·입 모양은 절반을 넘으면 [b]의 것. */
        fun lerp(a: SpiritLook, b: SpiritLook, t: Float): SpiritLook {
            val k = t.coerceIn(0f, 1f)
            if (k <= 0f) return a
            if (k >= 1f) return b
            fun f(x: Float, y: Float) = x + (y - x) * k
            return SpiritLook(
                body = blendColor(a.body, b.body, k),
                shade = blendColor(a.shade, b.shade, k),
                shadow = blendColor(a.shadow, b.shadow, k),
                shadowAlpha = f(a.shadowAlpha, b.shadowAlpha),
                shadowY = f(a.shadowY, b.shadowY),
                shadowRy = f(a.shadowRy, b.shadowRy),
                eyes = if (k < 0.5f) a.eyes else b.eyes,
                mouth = if (k < 0.5f) a.mouth else b.mouth,
                cheek = blendColor(a.cheek, b.cheek, k),
                cheekAlpha = f(a.cheekAlpha, b.cheekAlpha),
                cheekRadius = f(a.cheekRadius, b.cheekRadius),
                tiltDeg = f(a.tiltDeg, b.tiltDeg),
                wobbleDeg = f(a.wobbleDeg, b.wobbleDeg),
                bobAmp = f(a.bobAmp, b.bobAmp),
                blinks = if (k < 0.5f) a.blinks else b.blinks,
                sweat = f(a.sweat, b.sweat),
                motionLines = f(a.motionLines, b.motionLines),
                rain = f(a.rain, b.rain),
                brows = f(a.brows, b.brows),
                glow = f(a.glow, b.glow),
                stars = f(a.stars, b.stars),
                confetti = f(a.confetti, b.confetti),
            )
        }

        fun blendColor(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int): Int {
                val x = (a ushr shift) and 0xFF
                val y = (b ushr shift) and 0xFF
                return (x + ((y - x) * t)).toInt().coerceIn(0, 255)
            }
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
