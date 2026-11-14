package com.example.pleosapp.spike

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.example.pleosapp.R
import com.example.pleosapp.engine.TripSound

/**
 * 달리는 동안 내는 짧은 소리. 재생 전에 오디오 포커스를 받고 끝나면 돌려줍니다.
 * 음성 명령, 통화 벨, 긴급 소리 중에는 포커스가 거절되므로 그때는 소리를 건너뜁니다. 통화 중에도 건너뜁니다. 낼지는 `TripSounds`가 정합니다.
 * 트립에 몇 번뿐이라 미리 읽어 두지 않고 그때마다 만듭니다.
 */
class SoundProbe(context: Context) {

    private class Playback(val sound: TripSound, val player: MediaPlayer, val focus: AudioFocusRequest)

    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private var playback: Playback? = null
    private val finishNow = Runnable(::finish)

    /** 소리를 시작했으면 true입니다. */
    @Synchronized
    fun play(sound: TripSound): Boolean {
        finish()
        // 차는 통화 중에도 알림 소리 포커스를 줍니다(AAOS 포커스 표). 통화·벨 중에는 포커스와 상관없이 건너뜁니다.
        if (audio.mode != AudioManager.MODE_NORMAL) {
            ProbeState.log("SOUND skipped $sound: audio mode ${audio.mode}")
            return false
        }
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) finish()
            }
            .build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        val resource = if (sound == TripSound.CHIME) R.raw.sfx_chime else R.raw.sfx_feather
        val player = MediaPlayer.create(appContext, resource, attributes, audio.generateAudioSessionId())
        if (player == null) {
            audio.abandonAudioFocusRequest(focus)
            return false
        }
        player.setVolume(VOLUME, VOLUME)
        player.setOnCompletionListener { finish() }
        player.setOnErrorListener { _, _, _ ->
            finish()
            true
        }
        player.start()
        playback = Playback(sound, player, focus)
        // 완료 알림이 오지 않아도 포커스를 붙들고 있으면 안 됩니다. 내비 음성이 계속 작아집니다.
        handler.postDelayed(finishNow, player.duration + FOCUS_GRACE_MS)
        return true
    }

    @Synchronized
    fun release() = finish()

    @Synchronized
    private fun finish() {
        handler.removeCallbacks(finishNow)
        val current = playback ?: return
        playback = null
        current.player.release()
        audio.abandonAudioFocusRequest(current.focus)
        ProbeState.log("SOUND done ${current.sound}")
    }

    private companion object {
        const val VOLUME = 0.5f
        const val FOCUS_GRACE_MS = 1_000L
    }
}
