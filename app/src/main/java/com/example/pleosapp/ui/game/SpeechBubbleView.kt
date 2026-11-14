package com.example.pleosapp.ui.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.widget.TextView

/** 캐릭터가 하는 말. 종이를 오려 붙인 모양이고, 꼬리는 왼쪽 아래의 캐릭터를 가리킵니다. */
class SpeechBubbleView(context: Context) : TextView(context) {

    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GameStyle.PAPER
        setShadowLayer(16f, 0f, 6f, SHADOW)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GameStyle.LINE
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()
    private var tail = 0f

    init {
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        typeface = GameStyle.TITLE
        setTextColor(GameStyle.INK)
        setLineSpacing(0f, 1.28f)
    }

    fun setPaper(color: Int) {
        paper.color = color
        invalidate()
    }

    /** 글자 크기를 정한 뒤 호출합니다. 꼬리 높이만큼 아래 여백을 더 둡니다. */
    fun setPaddingFor(unit: Float) {
        tail = unit * 3.4f
        line.strokeWidth = (unit * 0.24f).coerceAtLeast(1.5f)
        val side = (unit * 3.6f).toInt()
        val vertical = (unit * 2.6f).toInt()
        setPadding(side, vertical, side, vertical + tail.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val bottom = height - tail
        val radius = minOf(tail * 1.5f, bottom / 2.2f)
        rect.set(ROOM, ROOM, w - ROOM, bottom)
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.drawPath(path, paper)
        canvas.drawPath(path, line)

        // 꼬리는 말풍선 폭과 상관없이 같은 크기입니다. 길어지면 종이비행기처럼 보입니다.
        val start = tail * 2.2f
        val base = tail * 1.3f
        path.reset()
        path.moveTo(start, bottom - 2f)
        path.quadTo(start - base * 0.3f, bottom + tail * 0.5f, start - base * 0.7f, height - ROOM)
        path.quadTo(start + base * 0.3f, bottom + tail * 0.7f, start + base, bottom - 2f)
        canvas.drawPath(path, paper.apply { clearShadowLayer() })
        canvas.drawPath(path, line)
        paper.setShadowLayer(16f, 0f, 6f, SHADOW)

        // 꼬리와 몸이 만나는 곳의 선을 지워 한 장으로 이어 줍니다.
        val joint = line.strokeWidth * 1.4f
        rect.set(start + joint, bottom - joint, start + base - joint, bottom + joint)
        canvas.drawRect(rect, paper.apply { clearShadowLayer() })
        paper.setShadowLayer(16f, 0f, 6f, SHADOW)
        super.onDraw(canvas)
    }

    private companion object {
        const val SHADOW = 0x443A241C
        const val ROOM = 8f
    }
}
