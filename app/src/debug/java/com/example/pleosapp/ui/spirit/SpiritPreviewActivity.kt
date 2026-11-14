package com.example.pleosapp.ui.spirit

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.pleosapp.engine.Mood

/**
 * 구름이 미리보기(디버그 메뉴). 버튼 또는 adb 인텐트로 기분·레벨을 바꿔 스크린샷을 찍습니다.
 * `am start -n com.example.pleosapp/.ui.spirit.SpiritPreviewActivity --es mood dizzy --ei level 3`
 * mood: happy | dizzy | sick | celebrate
 */
class SpiritPreviewActivity : Activity() {

    private lateinit var spirit: SpiritView
    private lateinit var status: TextView
    private var talking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        apply(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        apply(intent)
    }

    private fun apply(intent: Intent?) {
        intent?.getIntExtra("level", -1)?.takeIf { it > 0 }?.let { spirit.setLevel(it) }
        when (intent?.getStringExtra("mood")?.lowercase()) {
            "happy" -> spirit.setMood(Mood.HAPPY)
            "dizzy" -> spirit.setMood(Mood.DIZZY)
            "sick" -> spirit.setMood(Mood.SICK)
            "celebrate" -> spirit.celebrate()
        }
        refresh()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            setPadding(32, 32, 32, 32)
        }
        status = TextView(this).apply {
            textSize = 24f
            setTextColor(0xFFE6EEF8.toInt())
        }
        spirit = SpiritView(this)
        val stage = FrameLayout(this).apply {
            addView(spirit, FrameLayout.LayoutParams(HERO_WIDTH, HERO_HEIGHT, Gravity.CENTER))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf<Pair<String, () -> Unit>>(
            "행복" to { spirit.setMood(Mood.HAPPY) },
            "멀미" to { spirit.setMood(Mood.DIZZY) },
            "아픔" to { spirit.setMood(Mood.SICK) },
            "축하" to { spirit.celebrate() },
            "레벨−" to { spirit.setLevel(spirit.currentLevel - 1) },
            "레벨+" to { spirit.setLevel(spirit.currentLevel + 1) },
            "말하기" to { talking = !talking; spirit.setTalking(talking) },
        ).forEach { (label, action) ->
            row.addView(Button(this).apply {
                text = label
                textSize = 20f
                setOnClickListener { action(); refresh() }
            })
        }
        root.addView(status)
        root.addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(row)
        return root
    }

    private fun refresh() {
        status.text = "구름이 · 기분 ${spirit.currentMood} · Lv.${spirit.currentLevel}" + if (talking) " · 말하는 중" else ""
    }

    private companion object {
        const val BACKGROUND = 0xFF1B2A41.toInt()
        const val HERO_WIDTH = 560
        const val HERO_HEIGHT = 470
    }
}
