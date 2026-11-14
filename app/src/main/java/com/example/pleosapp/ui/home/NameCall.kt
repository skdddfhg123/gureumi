package com.example.pleosapp.ui.home

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.example.pleosapp.R
import com.example.pleosapp.engine.NameMatch
import com.example.pleosapp.ui.game.GameStyle
import com.example.pleosapp.ui.game.GameStyle.paperText
import com.example.pleosapp.ui.game.GameStyle.sizeTo
import com.example.pleosapp.ui.game.PaperDrawable
import com.example.pleosapp.ui.game.TextRules
import com.example.pleosapp.ui.voice.NameEars
import com.example.pleosapp.ui.voice.NameEarsMaker

/**
 * 홈의 이름 부르기 단추입니다(강가 장소 명세 7장). 누르면 한 번 듣고, 부른 이름을 [answered]로 넘깁니다.
 * 들리는 대로 이름을 맞춰 보고, 이름이 들리면 바로 마이크를 닫습니다. 최종 글자를 기다리지 않아 대답이 빠릅니다.
 * 듣는 동안 단추가 숨 쉬듯 커졌다 작아집니다. 다시 누르면 그만 듣습니다.
 */
class NameCall(
    private val activity: Activity,
    private val names: () -> List<String>,
    private val started: () -> Unit,
    private val answered: (String) -> Unit,
    private val missed: (Miss) -> Unit,
) {

    enum class Miss { UNCLEAR, BROKEN, DENIED }

    private val ears: NameEars? = NameEarsMaker.make(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val icon: Drawable = requireNotNull(activity.getDrawable(R.drawable.ic_mic)).mutate()
    private var listening = false
    private var opened = false
    private var breath: ValueAnimator? = null

    /** 이 빌드에 귀가 있는지. 제출 빌드에는 없어 단추를 숨깁니다. */
    val available: Boolean get() = ears != null

    val button: TextView = activity.paperText(IDLE, bold = true).apply {
        gravity = Gravity.CENTER
        rotation = 1f
        contentDescription = "이름 불러 보기"
        visibility = View.GONE
        setOnClickListener { press() }
    }

    /** 이름이 끝내 들리지 않으면 마이크를 닫고 갸웃합니다. */
    private val giveUp = Runnable {
        ears?.stop()
        end()
        missed(Miss.UNCLEAR)
    }

    private val listener = object : NameEars.Listener {
        override fun onReady() {
            opened = true
        }

        override fun onHeard(text: String, final: Boolean) {
            if (!listening) return
            val name = NameMatch.find(text, names())
            if (name == null && !final) return
            ears?.stop()
            end()
            if (name != null) answered(name) else missed(Miss.UNCLEAR)
        }

        override fun onDone(failed: Boolean) {
            if (!listening) return
            end()
            missed(if (failed && !opened) Miss.BROKEN else Miss.UNCLEAR)
        }
    }

    fun style(u: Float, wide: Boolean) {
        button.background = PaperDrawable(activity, 1.4f * u)
        button.sizeTo((if (wide) 3.3f else 3f) * u, TextRules.MAIN)
        val side = (button.textSize * ICON_SHARE).toInt()
        icon.setBounds(0, 0, side, side)
        button.setCompoundDrawables(icon, null, null, null)
        button.compoundDrawablePadding = (0.5f * u).toInt()
        val pad = (1.4f * u).toInt()
        button.setPadding(pad, 0, pad, 0)
    }

    /** 긴 쪽 말에 맞춘 폭입니다. 듣는 동안 말이 바뀌어도 단추 크기는 그대로입니다. */
    fun width(): Int {
        val text = maxOf(button.paint.measureText(IDLE), button.paint.measureText(LISTENING))
        return (button.paddingLeft + icon.bounds.width() + button.compoundDrawablePadding + text + button.paddingRight).toInt() + 1
    }

    /** 마이크를 허락받으면 바로 듣습니다. */
    fun onPermission(granted: Boolean) {
        if (granted) listen() else missed(Miss.DENIED)
    }

    /** 화면이 가려지거나 달리기 시작하면 그만 듣습니다. */
    fun cancel() {
        if (!listening) return
        ears?.stop()
        end()
    }

    fun release() {
        cancel()
        ears?.release()
    }

    private fun press() {
        if (listening) {
            cancel()
            return
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST)
            return
        }
        listen()
    }

    private fun listen() {
        val ears = ears ?: return
        listening = true
        opened = false
        button.text = LISTENING
        breathe(true)
        started()
        ears.listen(listener)
        handler.postDelayed(giveUp, LISTEN_MS)
    }

    private fun end() {
        listening = false
        handler.removeCallbacks(giveUp)
        button.text = IDLE
        breathe(false)
    }

    private fun breathe(on: Boolean) {
        breath?.cancel()
        breath = null
        icon.setTint(if (on) GameStyle.CORAL else GameStyle.INK)
        if (!on) {
            button.scaleX = 1f
            button.scaleY = 1f
            return
        }
        breath = ValueAnimator.ofFloat(1f, BREATH_SCALE).apply {
            duration = BREATH_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val scale = it.animatedValue as Float
                button.scaleX = scale
                button.scaleY = scale
            }
            start()
        }
    }

    companion object {
        const val PERMISSION_REQUEST = 2

        /** 말을 마친 뒤 최종 글자까지 3초쯤 걸립니다. 부르는 데 3초를 더 줍니다. */
        const val LISTEN_MS = 6_000L
        private const val IDLE = "이름 부르기"
        private const val LISTENING = "듣고 있어"
        private const val ICON_SHARE = 1.15f
        private const val BREATH_SCALE = 1.06f
        private const val BREATH_MS = 520L
    }
}
