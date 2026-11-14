package com.example.pleosapp.ui.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup

/** 칸 전체를 쓰지만 앞뒤는 정해진 바닥선으로 정하는 자식. 바닥 소품 층처럼 여러 물건을 한 번에 그리는 뷰입니다. */
interface Grounded {
    /** 무대 좌표의 바닥선입니다. */
    val feet: Float
}

/**
 * 게임 화면처럼 자식을 좌표로 놓는 무대. 차종과 분할 상태마다 창 크기가 달라서
 * 창 크기가 정해진 순간 [onResize]로 다시 배치합니다. 자리를 받지 않은 자식은 무대 전체를 채웁니다.
 */
class StageLayout(context: Context) : ViewGroup(context) {

    private class Slot(val left: Int, val top: Int, val width: Int, val height: Int, val wrap: Boolean, val hangs: Boolean)

    private val slots = HashMap<View, Slot>()
    private var lastWidth = -1
    private var lastHeight = -1

    // 앞뒤를 발 자리로 정하는 자식들입니다. 매 장면 새 목록을 만들지 않도록 칸을 미리 잡아 둡니다.
    private val byFeet = ArrayList<View>()
    private var order = IntArray(0)
    private var places = IntArray(0)
    private var sorted = arrayOfNulls<View>(0)

    var onResize: ((Int, Int) -> Unit)? = null

    init {
        // 유리병의 불빛처럼 칸 밖으로 번지는 빛이 잘리지 않게 합니다.
        clipChildren = false
    }

    /**
     * 무대는 불빛이 번지도록 자식을 자르지 않습니다. 그래서 스크롤 뷰는 스스로 자기 칸을 잘라야
     * 내용이 칸 밖으로 넘쳐 보이지 않습니다.
     */
    fun clipToSelf(view: View) {
        view.addOnLayoutChangeListener { target, _, _, _, _, _, _, _, _ ->
            target.clipBounds = Rect(0, 0, target.width, target.height)
        }
    }

    /**
     * [wrapHeight]면 내용 높이만 쓰고 칸의 아래쪽에 붙습니다. 말풍선처럼 꼬리 위치가 고정되는 뷰에 씁니다.
     * [hangs]면 내용 높이만 쓰되 칸의 위쪽에 붙습니다. 위에서 늘어나는 쪽지에 씁니다.
     */
    fun place(view: View, left: Int, top: Int, width: Int, height: Int, wrapHeight: Boolean = false, hangs: Boolean = false) {
        slots[view] = Slot(left, top, width, height, wrapHeight || hangs, hangs)
    }

    /**
     * [views]는 넣은 순서 대신 발 자리로 앞뒤를 정합니다. 화면 아래쪽, 곧 앞에 선 쪽이 나중에 그려지고 먼저 눌립니다.
     * 이 자식들이 차지한 순번 안에서만 바꾸므로, 그 뒤에 넣은 글자표와 말풍선은 계속 위에 있습니다.
     */
    fun sortByFeet(views: List<View>) {
        byFeet.clear()
        byFeet.addAll(views)
        places = IntArray(views.size)
        sorted = arrayOfNulls(views.size)
        isChildrenDrawingOrderEnabled = true
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (byFeet.isNotEmpty()) arrange()
        super.dispatchDraw(canvas)
    }

    private fun arrange() {
        if (order.size != childCount) order = IntArray(childCount)
        var found = 0
        for (i in 0 until childCount) {
            order[i] = i
            val child = getChildAt(i)
            if (found < places.size && byFeet.contains(child)) {
                places[found] = i
                sorted[found] = child
                found++
            }
        }
        // 많아야 일곱이라 삽입 정렬로 충분합니다. 움직이는 중이면 옮겨 간 만큼을 더해 봅니다.
        for (a in 1 until found) {
            val view = sorted[a]!!
            val feet = feetOf(view)
            var b = a - 1
            while (b >= 0 && feetOf(sorted[b]!!) > feet) {
                sorted[b + 1] = sorted[b]
                b--
            }
            sorted[b + 1] = view
        }
        for (k in 0 until found) order[places[k]] = indexOfChild(sorted[k])
    }

    private fun feetOf(view: View): Float = (view as? Grounded)?.feet ?: (view.bottom + view.translationY)

    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int =
        if (drawingPosition < order.size) order[drawingPosition] else drawingPosition

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (width != lastWidth || height != lastHeight) {
            lastWidth = width
            lastHeight = height
            onResize?.invoke(width, height)
        }
        setMeasuredDimension(width, height)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val slot = slots[child]
            val childWidth = slot?.width ?: width
            val childHeight = slot?.height ?: height
            child.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, if (slot?.wrap == true) MeasureSpec.AT_MOST else MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val slot = slots[child]
            if (slot == null) {
                child.layout(0, 0, r - l, b - t)
            } else {
                val top = if (slot.wrap && !slot.hangs) slot.top + slot.height - child.measuredHeight else slot.top
                child.layout(slot.left, top, slot.left + slot.width, top + child.measuredHeight)
            }
        }
    }
}
