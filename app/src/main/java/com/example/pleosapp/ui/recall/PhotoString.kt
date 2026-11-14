package com.example.pleosapp.ui.recall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Process
import android.os.SystemClock
import android.view.View
import com.example.pleosapp.R
import com.example.pleosapp.engine.RecallBook
import com.example.pleosapp.engine.RecallId
import com.example.pleosapp.ui.game.GameStyle
import com.example.pleosapp.ui.game.NookView
import com.example.pleosapp.ui.game.TextRules
import com.example.pleosapp.ui.spirit.SpiritSprites
import kotlin.math.exp
import kotlin.math.sin

/**
 * 천장 손잡이 두 개 사이에 맨 사진 줄. 고비마다 사진이 한 장씩 늘고, 사진은 무게대로 곧게 늘어집니다.
 * 줄 왼쪽 끝 집게에 "함께한 날" 꼬리표가 매달립니다. 남은 날은 보여 주지 않습니다.
 * 사진은 누를 곳이 아닙니다. 그림책은 앨범에서 엽니다. 누름은 뒤의 벽으로 넘어갑니다.
 */
class PhotoStringView(context: Context) : View(context) {

    private class Photo(val id: RecallId.Days, val art: Int) {
        var bitmap: Bitmap? = null
    }

    private val tile = SpiritSprites.bitmap(context, R.drawable.paper_tile)
    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val tagPaper = Paint(paper)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SHADE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GameStyle.LINE
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
    }
    private val tagEdge = Paint(edge)
    private val cord = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CORD
        style = Paint.Style.STROKE
        strokeWidth = 2.4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val knot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KNOT }
    private val tape = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TAPE }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GameStyle.INK
        typeface = GameStyle.BODY
        textAlign = Paint.Align.CENTER
    }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GameStyle.INK
        typeface = GameStyle.TITLE
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val rect = RectF()
    private val tag = RectF()
    private val source = Rect()

    private var scene: NookView? = null
    private var photos: List<Photo> = emptyList()
    private var slots = FloatArray(0)
    private var tops = FloatArray(0)
    private var laidScale = 0f
    private var days = 0
    private var clip: Bitmap? = null
    private var swingId: RecallId? = null
    private var swingStart = 0L

    /** 도착 화면은 왼쪽 위에 제목이 있어서 꼬리표를 뺍니다. 꼬리표 자리만큼 사진 자리가 바뀝니다. */
    var showsTag = true
        set(value) {
            field = value
            laidScale = 0f
        }

    private val tagShown: Boolean get() = showsTag && days > 0

    fun attach(scene: NookView) {
        this.scene = scene
        val art = SpiritSprites.named(context, "time_clip")
        if (art != 0) clip = SpiritSprites.bitmap(context, art)
    }

    /** 사진 줄을 맞춥니다. 큰 그림을 줄여 읽으므로 새 사진만 뒤에서 읽습니다. */
    fun show(days: Int, ids: List<RecallId.Days>) {
        this.days = days
        if (ids == photos.map { it.id }) return
        val kept = photos.associateBy { it.id }
        photos = ids.map { id -> kept[id] ?: Photo(id, RecallArt.of(context, RecallBook.photoArt(id))) }
        laidScale = 0f
        invalidate()
        val missing = photos.filter { it.bitmap == null }
        if (missing.isEmpty()) return
        Thread {
            // 첫 화면과 창밖 그림이 먼저입니다. 사진은 그 뒤에 걸려도 됩니다.
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            missing.forEach { photo ->
                val loaded = SpiritSprites.thumbnail(context, photo.art, THUMB_SAMPLE)
                post { photo.bitmap = loaded }
            }
        }.start()
    }

    /**
     * 사진과 집게는 시간대와 날씨의 빛을 입습니다. 집 안 불은 실내등과 깃털 병뿐이라 줄에는 불을 달지 않습니다.
     * 밤에는 줄 한가운데 실내등이 켜져 둘레가 등 빛을 받습니다. 그래서 차가운 밤빛 대신 꼬리표와 같은 밤 종이 빛을 입습니다.
     * 꼬리표는 글자가 있어서 화면의 다른 종이처럼 밤에만 같은 만큼 어두워집니다. 그래야 밤에도 글자가 읽힙니다.
     */
    fun setAmbient(filter: ColorFilter?, night: Boolean) {
        val light = if (night) GameStyle.paperLight(true) else filter
        image.colorFilter = light
        paper.colorFilter = light
        edge.colorFilter = light
        tape.colorFilter = light
        cord.colorFilter = light
        knot.colorFilter = light
        tagPaper.colorFilter = GameStyle.paperLight(night)
        tagEdge.colorFilter = GameStyle.paperLight(night)
    }

    /** 방금 걸린 사진을 크게 흔듭니다. */
    fun swing(id: RecallId) {
        swingId = id
        swingStart = SystemClock.uptimeMillis()
    }

    override fun onDraw(canvas: Canvas) {
        val current = scene ?: return
        if (current.sceneScale <= 0f) return
        if (current.sceneScale != laidScale) lay(current.sceneScale)
        val now = SystemClock.uptimeMillis()
        canvas.save()
        canvas.translate(current.viewX(0f), current.viewY(0f))
        canvas.scale(current.sceneScale, current.sceneScale)
        canvas.drawPath(path, cord)
        canvas.drawCircle(PhotoCord.LEFT, PhotoCord.HANG_Y, KNOT_RADIUS, knot)
        canvas.drawCircle(PhotoCord.RIGHT, PhotoCord.HANG_Y, KNOT_RADIUS, knot)
        if (tagShown) drawTag(canvas)
        photos.forEachIndexed { i, photo -> drawPhoto(canvas, photo, i, now) }
        canvas.restore()
    }

    /** 꼬리표 크기, 사진 자리, 줄 모양을 맞춥니다. 꼬리표 글자는 화면 배율을 따르므로 배율이 바뀌면 다시 맞춥니다. */
    private fun lay(scale: Float) {
        laidScale = scale
        val density = resources.displayMetrics.scaledDensity
        small.textSize = PhotoCord.smallText(scale, density)
        big.textSize = PhotoCord.bigText(scale, density)
        val width = maxOf(small.measureText(TAG_TITLE), big.measureText(TAG_WIDEST)) + TAG_PAD_X * 2f
        val top = PhotoCord.HANG_Y + PhotoCord.TAIL + PhotoCord.CLIP_DROP
        tag.set(PhotoCord.LEFT - width / 2f, top, PhotoCord.LEFT + width / 2f, top + TAG_PAD_TOP + small.textSize + TAG_LINE_GAP + big.textSize + TAG_PAD_Y)
        slots = PhotoCord.slots(photos.size, if (tagShown) tag.right else PhotoCord.LEFT)
        tops = FloatArray(slots.size) { PhotoCord.y(slots[it], slots) }
        path.reset()
        PhotoCord.cordXs(slots).forEachIndexed { i, x ->
            if (i == 0) path.moveTo(x, PhotoCord.y(x, slots)) else path.lineTo(x, PhotoCord.y(x, slots))
        }
    }

    /** 줄 끝 매듭에서 짧게 내려온 줄을 집게가 물고, 꼬리표가 그 밑에 곧게 매달립니다. */
    private fun drawTag(canvas: Canvas) {
        val x = PhotoCord.LEFT
        val clipAt = PhotoCord.HANG_Y + PhotoCord.TAIL
        canvas.drawLine(x, PhotoCord.HANG_Y, x, clipAt, cord)
        canvas.drawRoundRect(tag, 8f, 8f, tagPaper)
        canvas.drawRoundRect(tag, 8f, 8f, tagEdge)
        canvas.drawText(TAG_TITLE, x, tag.top + TAG_PAD_TOP + small.textSize * BASELINE, small)
        canvas.drawText("${days}일", x, tag.top + TAG_PAD_TOP + small.textSize + TAG_LINE_GAP + big.textSize * BASELINE, big)
        drawClip(canvas, x, clipAt)
    }

    private fun drawPhoto(canvas: Canvas, photo: Photo, index: Int, now: Long) {
        val x = slots.getOrNull(index) ?: return
        val top = tops[index]
        val width = PhotoCord.FRAME
        val height = width * PhotoCord.FRAME_RATIO
        val border = width * 0.07f
        canvas.save()
        // 사진은 무게대로 곧게 늘어집니다. 막 걸린 사진만 집게를 축으로 흔들리다 멈춥니다.
        canvas.rotate(swingOf(photo.id, now), x, top)
        rect.set(x - width / 2f + 2f, top + PhotoCord.CLIP_DROP + 3f, x + width / 2f + 2f, top + PhotoCord.CLIP_DROP + height + 3f)
        canvas.drawRect(rect, shade)
        rect.offset(-2f, -3f)
        canvas.drawRect(rect, paper)
        canvas.drawRect(rect, edge)
        photo.bitmap?.let { art ->
            rect.inset(border, border)
            val scale = maxOf(rect.width() / art.width, rect.height() / art.height)
            val w = (rect.width() / scale).toInt()
            val h = (rect.height() / scale).toInt()
            source.set((art.width - w) / 2, (art.height - h) / 2, (art.width + w) / 2, (art.height + h) / 2)
            canvas.drawBitmap(art, source, rect, image)
        }
        drawClip(canvas, x, top)
        canvas.restore()
    }

    private fun drawClip(canvas: Canvas, x: Float, top: Float) {
        val art = clip
        if (art != null) {
            val h = 38f
            val w = h * art.width / art.height
            rect.set(x - w / 2f, top - 13f, x + w / 2f, top - 13f + h)
            canvas.drawBitmap(art, null, rect, image)
        } else {
            rect.set(x - 15f, top - 2f, x + 15f, top + 15f)
            canvas.drawRect(rect, tape)
        }
    }

    private fun swingOf(id: RecallId, now: Long): Float {
        if (id != swingId) return 0f
        val age = (now - swingStart) / 1000f
        if (age >= SWING_SEC) return 0f
        return sin(age * 4.2f) * 11f * exp(-age * 1.1f)
    }

    companion object {
        /** 벽의 아래쪽 경계. 설계 좌표입니다. 이 뷰는 여기까지만 덮습니다. */
        const val WALL_BOTTOM = 270f

        private const val TAG_TITLE = "함께한 날"

        /** 꼬리표 너비를 정하는 가장 긴 날 수. 날이 늘어도 꼬리표 크기와 사진 자리가 그대로입니다. */
        private const val TAG_WIDEST = "0000일"
        private const val TAG_PAD_X = 10f
        private const val TAG_PAD_Y = 8f

        /** 집게 아래턱이 꼬리표 윗변에서 19만큼 내려옵니다. 글자는 그 밑에서 시작합니다. */
        private const val TAG_PAD_TOP = 24f
        private const val TAG_LINE_GAP = 4f

        /** 글자 크기에 대한 한글 글자 밑선 자리. */
        private const val BASELINE = 0.86f
        private const val KNOT_RADIUS = 3.6f
        private const val THUMB_SAMPLE = 8
        private const val SWING_SEC = 4f
        private const val SHADE = 0x333A241C
        private const val CORD = 0xFFB08F76.toInt()
        private const val KNOT = 0xFF9A7A62.toInt()
        private const val TAPE = 0xC8E8B4A0.toInt()
    }
}

/**
 * 사진 줄의 모양. 설계 좌표입니다(장면 규칙 2-1의 3, 6).
 * 천장 그림(home_roof)의 천 고리는 x 40, 405, 770, 1135, 1500에 있고 화면에 다 보이는 것은 가운데 셋입니다.
 * 줄은 양쪽 손잡이(405, 1135)에 묶고 가운데 고리(770)에 걸칩니다. 고리 밖으로는 나가지 않습니다.
 * 사진은 무게대로 곧게 늘어지고, 줄은 사진을 문 집게 자리에서 그 무게만큼 꺾여 내려갑니다.
 */
internal object PhotoCord {

    /** 줄을 묶은 왼쪽 손잡이. 줄 끝 집게에 "함께한 날" 꼬리표가 매달립니다. */
    const val LEFT = 405f

    /** 줄을 걸친 가운데 고리. 바로 밑이 천장 등입니다. */
    const val MIDDLE = 770f

    /** 줄을 묶은 오른쪽 손잡이. */
    const val RIGHT = 1135f

    /** 줄이 고리를 지나는 높이. 고리 아래 둥근 끝의 안쪽입니다. */
    const val HANG_Y = 106f

    /** 고리 너비의 반. */
    const val LOOP_HALF = 19f

    /** 사진 틀의 너비. 장수가 늘어도 같습니다. 넓은 창에서 101×83px이라 한 장씩 무엇인지 읽힙니다. */
    const val FRAME = 86f

    /** 사진 틀의 세로 대 가로. */
    const val FRAME_RATIO = 0.82f

    /** 줄 끝 매듭에서 꼬리표 집게까지 내려온 줄. */
    const val TAIL = 10f

    /** 집게가 문 자리에서 사진과 꼬리표 윗변까지. */
    const val CLIP_DROP = 6f

    /** 사진끼리 띄우는 거리. 넓은 창에서 23.6px이라 사진이 한 덩이로 붙어 보이지 않습니다. */
    const val GAP = 20f

    /** 고리와 꼬리표에서 띄우는 거리. */
    private const val EDGE_GAP = 12f

    /** 빈 줄이 고리 사이 가운데에서 처지는 깊이. */
    private const val DROOP = 4f

    /** 사진 한 장이 고리 사이 가운데에 걸렸을 때 줄이 더 처지는 깊이. 가장자리에 걸리면 덜 처집니다. */
    private const val LOAD = 5f

    /** 줄을 그릴 때 x 간격. 집게 자리는 따로 넣어 꺾임이 뭉개지지 않습니다. */
    private const val STEP = 6f

    /** 꼬리표 글자의 설계 크기. 넓은 창에서 26px, 35px입니다. 작은 창에서는 화면 글자 기준까지 키웁니다. */
    private const val SMALL_TEXT = 22f
    private const val BIG_TEXT = 30f

    /** "함께한 날" 글자 크기(설계 좌표). 화면에서 [TextRules.SMALL]보다 작지 않습니다. */
    fun smallText(scale: Float, density: Float): Float = maxOf(SMALL_TEXT, TextRules.SMALL * density / scale)

    /** 날 수 글자 크기(설계 좌표). 화면에서 [TextRules.MAIN]보다 작지 않습니다. */
    fun bigText(scale: Float, density: Float): Float = maxOf(BIG_TEXT, TextRules.MAIN * density / scale)

    /**
     * 줄의 높이. 고리 사이마다 줄 무게로 조금 처지고, 사진을 문 집게마다 그 무게만큼 더 처집니다.
     * 무게 하나가 만드는 처짐은 양 끝을 받친 줄과 같아서, 집게 자리에서 꺾이고 집게 사이는 곧습니다.
     */
    fun y(x: Float, slots: FloatArray): Float {
        val (start, end) = if (x <= MIDDLE) LEFT to MIDDLE else MIDDLE to RIGHT
        val span = end - start
        val u = ((x - start) / span).coerceIn(0f, 1f)
        var drop = DROOP * 4f * u * (1f - u)
        slots.forEach { slot ->
            if (slot <= start || slot >= end) return@forEach
            val v = (slot - start) / span
            drop += LOAD * 4f * if (u <= v) u * (1f - v) else v * (1f - u)
        }
        return HANG_Y + drop
    }

    /** 줄을 그릴 x. 손잡이에서 손잡이까지이고, 꺾이는 집게 자리와 가운데 고리를 꼭 넣습니다. */
    fun cordXs(slots: FloatArray): FloatArray {
        val xs = sortedSetOf(LEFT, MIDDLE, RIGHT)
        var x = LEFT
        while (x < RIGHT) {
            xs += x
            x += STEP
        }
        slots.forEach { xs += it }
        return xs.toFloatArray()
    }

    /**
     * 사진 [count]장의 가운데 x. [after]는 꼬리표 오른쪽 끝입니다. 꼬리표가 없으면 [LEFT]를 넘깁니다.
     * 가운데 고리 왼쪽 칸부터 채우고, 사진 사이는 [GAP] 넘게 띄웁니다.
     * 두 칸이 다 차면 가장 넉넉한 칸에 한 장씩 더 겁니다. 그 칸의 사진은 조금 겹칩니다.
     */
    fun slots(count: Int, after: Float): FloatArray {
        val zones = listOf(
            maxOf(after, LEFT + LOOP_HALF) + EDGE_GAP to MIDDLE - LOOP_HALF - EDGE_GAP,
            MIDDLE + LOOP_HALF + EDGE_GAP to RIGHT - LOOP_HALF - EDGE_GAP,
        )
        val counts = IntArray(zones.size)
        var left = count
        zones.forEachIndexed { i, (start, end) ->
            counts[i] = minOf(((end - start + GAP) / (FRAME + GAP)).toInt().coerceAtLeast(0), left)
            left -= counts[i]
        }
        repeat(left) {
            val roomiest = zones.indices.maxBy { (zones[it].second - zones[it].first) / (counts[it] + 1) }
            counts[roomiest]++
        }
        return zones.indices.flatMap { spread(zones[it], counts[it]) }.toFloatArray()
    }

    /** 한 칸에 [count]장을 겁니다. 자리가 넉넉하면 고르게 띄우고, 빠듯하면 [GAP]씩 띄워 가운데에 모읍니다. */
    private fun spread(zone: Pair<Float, Float>, count: Int): List<Float> {
        val (start, end) = zone
        if (count == 1) return listOf((start + end) / 2f)
        val free = end - start - count * FRAME
        val even = free / (count + 1)
        val inner = if (even >= GAP) even else minOf(GAP, free / (count - 1))
        val outer = (free - inner * (count - 1)) / 2f
        return List(count) { start + outer + FRAME / 2f + it * (FRAME + inner) }
    }
}
