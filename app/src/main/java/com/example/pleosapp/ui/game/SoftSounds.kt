package com.example.pleosapp.ui.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.example.pleosapp.R
import kotlin.math.min

/**
 * 도착과 선물, 정비의 순간에 나는 작은 소리. 주차 중에만 나고 음성을 가리지 않게 낮게 냅니다.
 * 화면을 닫을 때 [release]를 부릅니다.
 */
class SoftSounds(context: Context) {

    private val pool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val stampSound = pool.load(context, R.raw.sfx_stamp, 1)
    private val featherSound = pool.load(context, R.raw.sfx_feather, 1)
    private val chimeSound = pool.load(context, R.raw.sfx_chime, 1)
    private val sparkleSound = pool.load(context, R.raw.sfx_sparkle, 1)
    private val popSound = pool.load(context, R.raw.sfx_pop, 1)

    /** 뒷자리에 아직 누가 있는 도착처럼 조용해야 할 때 켭니다. 재우는 아이를 깨우지 않습니다. */
    var muted = false

    fun stamp() = play(stampSound, VOLUME)

    /** 병이 찰수록 음이 조금씩 올라갑니다. */
    fun feather(index: Int) = play(featherSound, VOLUME * 0.7f, 1f + min(index, FEATHER_STEPS) * 0.035f)

    fun chime() = play(chimeSound, VOLUME)

    fun sparkle() = play(sparkleSound, VOLUME)

    fun pop() = play(popSound, VOLUME)

    private fun play(sound: Int, volume: Float, rate: Float = 1f) {
        if (!muted) pool.play(sound, volume, volume, 1, 0, rate)
    }

    fun release() = pool.release()

    private companion object {
        const val MAX_STREAMS = 4
        const val VOLUME = 0.5f
        const val FEATHER_STEPS = 20
    }
}
