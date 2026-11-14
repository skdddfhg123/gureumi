package com.example.pleosapp.ui.spirit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.example.pleosapp.engine.CareLook
import com.example.pleosapp.engine.Mood
import com.example.pleosapp.engine.Reward
import com.example.pleosapp.ui.game.GameStyle
import com.example.pleosapp.ui.game.MotionTicker
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** 정령의 꼬리 자리. 그림 안의 비율입니다. 머리와 귀는 휘지 않아서 자리를 두지 않습니다. */
class Joints(
    val tail: FloatArray,
    val asleep: Boolean = false,
)

/**
 * 자세 하나. 눈 감은 칸은 없을 수 있습니다.
 * [frames]가 있으면 그 자세를 보여 주는 동안 왕복 재생합니다. 그림 크기와 발 자리가 같아야 합니다.
 * [cycleMs]는 한 왕복의 시간입니다. 기쁨은 빠르고 잠은 느립니다.
 */
class ActorArt(val base: Bitmap, val blink: Bitmap?, val joints: Joints, val frames: List<Bitmap> = emptyList(), val cycleMs: Long = 1_000L) {

    /** 프레임이 대기 박자보다 빨리 넘어가는지. 습관은 대부분 느려서 대기 박자로도 프레임을 놓치지 않습니다. */
    val quickFrames: Boolean get() = frames.size > 1 && cycleMs / ((frames.size - 1) * 2) < QUICK_STEP_MS

    /**
     * 발밑 그림자의 가운데와 폭. 그림 폭에 대한 비율이고, 그림 맨 아래 띠에 칠해진 폭으로 잽니다.
     * 비버 그림은 머리 가운데에 맞춰 잘라 꼬리 반대쪽이 비어 있습니다. 그림 폭으로 그리면 그림자가 몸보다 넓어집니다.
     */
    val footprint: FloatArray by lazy { SpiritSprites.footprint(base) }

    private companion object {
        const val QUICK_STEP_MS = 150L
    }
}

/**
 * 살아 움직이는 정령. 컨셉아트에서 오린 그림 위에 격자를 얹어 몸 전체로 숨 쉬고 꼬리만 따로 흔듭니다.
 * 머리와 귀는 따로 휘지 않고, 몸짓도 몸 전체를 기울이고 들고 눌러서 합니다. 그린 머리를 휘면 윤곽이 물렁하게 부풀어 보입니다.
 * 옷과 자세는 그림을 통째로 바꿉니다. 덧씌우지 않아서 붓결이 그대로 남습니다.
 * 뷰의 아래 가운데가 발 자리입니다. 그림은 [unit] 배율로 그려서 자세가 바뀌어도 키가 튀지 않습니다.
 */
class SpriteSpiritView(context: Context) : View(context), SpiritRenderer {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, SHADOW, 0, Shader.TileMode.CLAMP)
    }
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GameStyle.INK_SOFT
        typeface = GameStyle.TITLE
        textAlign = Paint.Align.CENTER
    }

    private var rest: ActorArt? = null
    private var pose: ActorArt? = null
    private var poseEnd = 0L
    private var poseStart = 0L
    private var mood = Mood.HAPPY
    private var talking = false
    private var look = CareLook()
    private var ambient: ColorMatrix? = null
    private var motions: List<Reward> = emptyList()
    private var motionIndex = 0
    private var active: Reward? = null
    private var motionStart = 0L
    private var motionEnd = 0L
    private var nextIdleAt = 0L
    private var meshedFor: ActorArt? = null
    private var heartStart = 0L
    private val hearts by lazy { pieces("fx_heart_1", "fx_heart_2") }
    private val notes by lazy { pieces("fx_note_1", "fx_note_2") }
    private val float = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val floatRect = RectF()

    /**
     * 이 정령만의 시계 어긋남(초). 숨, 깜빡임, 꼬리, 쉬는 자세의 그림 넘김이 정령마다 다른 박자로 갑니다.
     * 모두 같은 시계를 쓰면 아기 넷이 함께 똑딱입니다.
     */
    private val clockShift = Random.nextFloat() * CLOCK_SPREAD_SEC

    /** 설계 픽셀 하나가 화면 몇 픽셀인지. */
    var unit = 1f

    /** 발 밑에 그림자를 그릴지. 작은 카드 안에서는 끕니다. */
    var grounded = true

    private val columns = MESH_W + 1
    private val rows = MESH_H + 1
    private val count = columns * rows
    private val verts = FloatArray(count * 2)
    private val tailWeight = FloatArray(count)

    /** 평소 자세를 정합니다. */
    fun setArt(next: ActorArt) {
        if (rest != null && rest !== next) poseStart = SystemClock.uptimeMillis()
        rest = next
        invalidate()
    }

    /** [durationMs] 동안 다른 자세를 보여 주고 돌아옵니다. */
    fun pose(art: ActorArt, durationMs: Long) {
        pose = art
        poseStart = SystemClock.uptimeMillis()
        poseEnd = poseStart + durationMs
    }

    /**
     * 뛰기, 갸웃, 하트, 자세 바꿈처럼 한 장 사이에 크게 움직이는 동안. 이때만 화면을 자주 다시 그립니다.
     * 말할 때 들썩임과 그림 넘김은 작거나 그림 장수로 정해져 있어 넣지 않습니다.
     */
    val lively: Boolean
        get() {
            val now = SystemClock.uptimeMillis()
            return (active != null && now < motionEnd) || now - poseStart < SWAP_MS || now - heartStart < HEART_MS
        }

    /** 숨 쉬기보다 큰 몸짓을 하는 중인지. 습관이 겹치지 않게 봅니다. */
    val busy: Boolean get() = quick || pose?.frames?.isNotEmpty() == true

    /** 대기 박자로는 끊겨 보이는 움직임인지. 그동안은 박자를 올립니다. */
    val quick: Boolean
        get() {
            val now = SystemClock.uptimeMillis()
            return active != null || talking || pose?.quickFrames == true || now - poseStart < SWAP_MS || now - heartStart < HEART_MS
        }

    private fun weigh(art: ActorArt) {
        if (meshedFor === art) return
        meshedFor = art
        val tail = art.joints.tail
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val i = row * columns + column
                val u = column / MESH_W.toFloat()
                val v = row / MESH_H.toFloat()
                tailWeight[i] = 1f - smooth(0.05f, 0.17f, hypot(u - tail[0], v - tail[1]))
            }
        }
    }

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    override fun setMood(mood: Mood) {
        this.mood = mood
        applyColor()
    }

    /** 시간대의 빛을 입습니다. 0xRRGGBB, null이면 그대로입니다. */
    fun setAmbient(color: Int?) {
        ambient = color?.let {
            ColorMatrix().apply { setScale((it shr 16 and 0xFF) / 255f, (it shr 8 and 0xFF) / 255f, (it and 0xFF) / 255f, 1f) }
        }
        applyColor()
    }

    private fun applyColor() {
        val saturation = when {
            mood == Mood.SICK -> SICK_SATURATION
            look.dullFur -> DULL_SATURATION
            else -> 1f
        }
        val matrix = ColorMatrix().apply { setSaturation(saturation) }
        if (look.dullFur) matrix.postConcat(ColorMatrix().apply { setScale(DULL_BRIGHTNESS, DULL_BRIGHTNESS, DULL_BRIGHTNESS, 1f) })
        ambient?.let { matrix.postConcat(it) }
        paint.colorFilter = ColorMatrixColorFilter(matrix)
    }

    /** 몸의 크기는 바뀌지 않습니다. 성장은 모습과 장면으로 드러납니다. */
    override fun setLevel(level: Int) = Unit

    /** 배운 동작. 화면을 열면 금방 하나를 보여 주고, 가만히 있을 때 가끔 스스로 합니다. */
    fun setMotions(owned: List<Reward>) {
        if (owned == motions) return
        motions = owned
        nextIdleAt = SystemClock.uptimeMillis() + FIRST_IDLE_MS
    }

    /** 차 상태를 몸에 드러냅니다. */
    fun setCareLook(look: CareLook) {
        if (this.look == look) return
        this.look = look
        applyColor()
    }

    /** 도착했을 때 크게 뛰며 기뻐합니다. */
    override fun celebrate() = play(Reward.MOTION_HOP)

    /** 눌렀을 때 배운 동작을 돌아가며 보여 줍니다. 아직 없으면 고개만 갸웃합니다. */
    fun poke() {
        play(if (motions.isEmpty()) Reward.MOTION_TILT else motions[motionIndex++ % motions.size])
        heartStart = SystemClock.uptimeMillis()
    }

    /** 쓰다듬을 때처럼 하트만 떠오릅니다. */
    fun heart() {
        heartStart = SystemClock.uptimeMillis()
    }

    /** 찰칵 순간처럼 뿌듯할 때. 콩 뛰고 하트가 떠오릅니다. */
    fun cheer() {
        play(Reward.MOTION_HOP)
        heartStart = SystemClock.uptimeMillis()
    }

    fun play(motion: Reward) {
        active = motion
        motionStart = SystemClock.uptimeMillis()
        motionEnd = motionStart + (MOTION_MS[motion] ?: DEFAULT_MOTION_MS)
    }

    override fun setTalking(talking: Boolean) {
        this.talking = talking
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (pose != null && now >= poseEnd) {
            pose = null
            poseStart = now
        }
        val current = pose ?: rest ?: return
        weigh(current)
        val seconds = MotionTicker.seconds(now) + clockShift
        val w = current.base.width.toFloat()
        val h = current.base.height.toFloat()

        if (now >= motionEnd) {
            active = null
            if (nextIdleAt in 1..now && motions.isNotEmpty()) {
                play(motions[motionIndex++ % motions.size])
                nextIdleAt = now + IDLE_GAP_MS + (seconds * 977f).toLong() % IDLE_GAP_MS
            }
        }
        val progress = if (active != null) ((now - motionStart).toFloat() / (motionEnd - motionStart)).coerceIn(0f, 1f) else 0f
        val move = Move.of(active, progress)
        val sneeze = sneezeProgress(seconds)
        val tilt = move.angle + (if (look.soreFoot) -1.6f + sin(seconds * 1.7f) * 0.5f else 0f) +
            (if (sneeze >= 0f) sin(sneeze * PI.toFloat()) * 3f else 0f)
        var lift = move.lift * h
        // 말하는 동안 1초에 한 번 남짓 살짝 들썩입니다. 더 빠르면 그리는 박자 사이로 끊겨 보입니다.
        if (talking) lift += (1f - cos(seconds * TWO_PI * TALK_HZ)) * h * TALK_LIFT

        val feetX = width / 2f
        val feetY = height.toFloat() - FOOT_PAD * unit
        if (grounded) {
            val shrink = 1f - lift / h
            val foot = current.footprint
            val reach = w * unit * foot[1] * SHADOW_REACH * shrink
            canvas.save()
            canvas.translate(feetX + (foot[0] - 0.5f) * w * unit, feetY)
            canvas.scale(reach, reach * SHADOW_FLAT)
            canvas.drawCircle(0f, 0f, 1f, shadow)
            canvas.restore()
        }

        canvas.save()
        canvas.translate(feetX + w * unit * move.shift, feetY - lift * unit)
        canvas.scale(unit, unit)
        canvas.rotate(tilt)
        // 자세가 바뀌는 순간 살짝 눌렸다 펴집니다. 그림이 툭 바뀌어 보이지 않게 합니다.
        val swap = ((now - poseStart).toFloat() / SWAP_MS.toFloat()).takeIf { it in 0f..1f }?.let { sin(it * PI.toFloat()) * SWAP_SQUASH } ?: 0f
        canvas.scale(move.scaleX * (1f + swap), move.scaleY * (1f - swap))
        canvas.translate(-w / 2f, -h)
        deform(current, seconds, move)
        drawBody(canvas, frameOf(current, seconds, sneeze))
        canvas.restore()

        if (current.joints.asleep) drawSleep(canvas, feetX, feetY, h * unit, seconds)
        if (active == Reward.MOTION_HUM) drawHum(canvas, feetX, feetY, h * unit, progress)
        if (sneeze >= 0f) drawSneeze(canvas, feetX, feetY, h * unit, sneeze)
        if (now - heartStart < HEART_MS) drawHearts(canvas, feetX, feetY, h * unit, (now - heartStart).toFloat() / HEART_MS)
    }

    private fun drawBody(canvas: Canvas, frame: Bitmap) {
        canvas.drawBitmapMesh(frame, MESH_W, MESH_H, verts, 0, null, 0, paint)
    }

    private fun sneezeProgress(seconds: Float): Float {
        if (!look.sneezy) return -1f
        val cycle = seconds % SNEEZE_SEC
        return if (cycle < SNEEZE_MOVE_SEC) cycle / SNEEZE_MOVE_SEC else -1f
    }

    private fun drawSneeze(canvas: Canvas, x: Float, feet: Float, body: Float, progress: Float) {
        letter.textSize = body * 0.09f
        letter.alpha = (sin(progress * PI.toFloat()) * 255).toInt()
        canvas.drawText("에취", x + body * 0.5f, feet - body * 0.86f, letter)
        letter.alpha = 255
    }

    private fun drawSleep(canvas: Canvas, x: Float, feet: Float, body: Float, seconds: Float) {
        letter.textSize = body * 0.14f
        // 천천히 짧게 떠오릅니다. 1초에 9장으로 그려도 한 장에 몇 픽셀만 움직입니다.
        for (i in 0 until 3) {
            val phase = (seconds * Z_RISE_HZ + i * 0.33f) % 1f
            letter.alpha = (sin(phase * PI.toFloat()) * 200).toInt()
            canvas.drawText("z", x + body * (0.4f + phase * 0.2f), feet - body * (0.95f + phase * Z_RISE), letter)
        }
        letter.alpha = 255
    }

    private fun drawHum(canvas: Canvas, x: Float, feet: Float, body: Float, progress: Float) {
        for (i in 0 until 3) {
            val phase = ((progress * 2f + i * 0.33f) % 1f)
            drawPiece(canvas, notes, i, x + body * (0.44f + i * 0.06f + sin(phase * 6f) * 0.03f), feet - body * (0.8f + phase * 0.3f), body * 0.11f, phase)
        }
    }

    /** 눌렀을 때 머리 위로 하트가 몽글몽글 떠오릅니다. */
    private fun drawHearts(canvas: Canvas, x: Float, feet: Float, body: Float, progress: Float) {
        for (i in 0 until HEARTS) {
            val phase = progress * 1.4f - i * 0.2f
            if (phase !in 0f..1f) continue
            val side = (i - 1) * 0.2f + sin(phase * 5f + i) * 0.04f
            drawPiece(canvas, hearts, i, x + body * side, feet - body * (0.9f + phase * 0.34f), body * 0.1f, phase)
        }
    }

    private fun drawPiece(canvas: Canvas, set: List<Bitmap>, index: Int, x: Float, y: Float, size: Float, phase: Float) {
        val piece = set.getOrNull(index % set.size.coerceAtLeast(1)) ?: return
        float.alpha = (sin(phase * PI.toFloat()) * 255).toInt()
        val w = size * piece.width / piece.height
        floatRect.set(x - w / 2f, y - size / 2f, x + w / 2f, y + size / 2f)
        canvas.drawBitmap(piece, null, floatRect, float)
    }

    private fun pieces(vararg names: String): List<Bitmap> = names.mapNotNull { name ->
        SpiritSprites.named(context, name).takeIf { it != 0 }?.let { SpiritSprites.bitmap(context, it) }
    }

    private fun frameOf(current: ActorArt, seconds: Float, sneeze: Float): Bitmap {
        if (current.frames.isNotEmpty()) return flipbook(current)
        val closed = current.blink ?: return current.base
        val cycle = seconds % BLINK_SEC
        val blinking = cycle < BLINK_MOVE_SEC || seconds % (BLINK_SEC * 3f) in BLINK_TWICE..BLINK_TWICE + BLINK_MOVE_SEC
        return if (sneeze >= 0f || blinking || (mood == Mood.SICK && seconds % 2.4f < 0.5f)) closed else current.base
    }

    /**
     * 앞으로 갔다가 되돌아옵니다. 손 흔들기처럼 끝이 처음과 이어지는 동작에 맞습니다.
     * 잠드는 그림은 한 번만 앞으로 넘기고 마지막 장에 머뭅니다. 깼다 잤다를 되풀이하지 않습니다.
     * 쉬는 자세는 정령마다 시계가 어긋나 있어 함께 넘어가지 않습니다. 잠깐 하는 몸짓은 늘 첫 장부터입니다.
     */
    private fun flipbook(art: ActorArt): Bitmap {
        val frames = art.frames
        val last = frames.size - 1
        if (last == 0) return frames[0]
        val stepMs = art.cycleMs / (last * 2)
        val elapsed = SystemClock.uptimeMillis() - poseStart
        if (art.joints.asleep) return frames[(elapsed / stepMs).toInt().coerceAtMost(last)]
        val shift = if (art === pose) 0L else (clockShift * 1000f).toLong()
        val step = ((elapsed + shift) / stepMs).toInt() % (last * 2)
        return frames[if (step <= last) step else last * 2 - step]
    }

    /**
     * 그림이 칠해진 곳만 누를 곳입니다. 뷰 칸은 몸짓이 들어갈 여백까지 커서 이웃 정령과 겹칩니다.
     * 칸으로 받으면 앞에 앉은 정령의 빈 여백이 뒤에 앉은 정령을 가립니다.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !onBody(event.x, event.y)) return false
        return super.dispatchTouchEvent(event)
    }

    private fun onBody(x: Float, y: Float): Boolean {
        val art = (pose ?: rest)?.base ?: return false
        val area = SpiritSprites.painted(art)
        val left = width / 2f + (area.left - art.width / 2f) * unit
        val bottom = height - FOOT_PAD * unit - (art.height - area.bottom) * unit
        return x in left..left + area.width() * unit && y in bottom - area.height() * unit..bottom
    }

    /** 몸 전체가 발부터 위로 고르게 늘었다 줄며 숨 쉬고, 꼬리만 따로 흔듭니다. 고르게 늘어서 머리 윤곽은 그대로입니다. */
    private fun deform(current: ActorArt, seconds: Float, move: Move) {
        val w = current.base.width.toFloat()
        val h = current.base.height.toFloat()
        val joints = current.joints
        val calm = when {
            joints.asleep -> 0.7f
            mood == Mood.SICK -> 0.5f
            else -> 1f
        }
        val breath = sin(seconds * TWO_PI / (if (joints.asleep) SLEEP_BREATH_SEC else BREATH_SEC)) * calm
        // 평소 꼬리는 작고 느리게 흔듭니다. 1초에 9장으로 그려도 한 장에 2픽셀 안쪽으로 움직여 끊겨 보이지 않습니다.
        // 꼬리 흔들기 몸짓은 몸짓이 시작된 때부터 잰 시각으로 빠르게 흔듭니다. 큰 시각에 빠르기를 곱하면 위상이 튑니다.
        val wagSeconds = (SystemClock.uptimeMillis() - motionStart) / 1000f
        val wag = if (move.wag > 0f) sin(wagSeconds * WAG_SPEED) * WAG_SWING * move.wag else 0f
        val tailAngle = (sin(seconds * TAIL_SPEED) * TAIL_SWING + wag) * calm
        val tailX = joints.tail[0] * w
        val tailY = joints.tail[1] * h
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val i = row * columns + column
                val x = column * w / MESH_W
                val y = row * h / MESH_H
                val v = row / MESH_H.toFloat()
                var dx = 0f
                var dy = -(1f - v) * h * BREATH * breath
                val tail = tailWeight[i]
                if (tail > 0f) {
                    dx += tail * (-(y - tailY) * tailAngle)
                    dy += tail * (x - tailX) * tailAngle
                }
                verts[i * 2] = x + dx
                verts[i * 2 + 1] = y + dy
            }
        }
    }

    /** 한 순간의 몸짓. 값은 그림 크기에 대한 비율입니다. 몸 전체를 기울이고 들고 눌러서 하고, 머리만 따로 움직이지 않습니다. */
    private class Move(
        val lift: Float = 0f,
        val angle: Float = 0f,
        val shift: Float = 0f,
        val scaleX: Float = 1f,
        val scaleY: Float = 1f,
        val wag: Float = 0f,
    ) {
        companion object {
            private val STILL = Move()

            fun of(motion: Reward?, p: Float): Move {
                val pi = PI.toFloat()
                val swell = sin(p * pi)
                return when (motion) {
                    null -> STILL
                    Reward.MOTION_HOP -> {
                        val arc = sin(p * pi * 3f)
                        val squash = (1f - abs(arc)) * 0.035f * (1f - p)
                        Move(lift = abs(arc) * 0.085f * (1f - p * 0.5f), scaleX = 1f + squash, scaleY = 1f - squash)
                    }
                    Reward.MOTION_TILT -> Move(angle = swell * 6f)
                    // 귀 쫑긋: 귀를 휘지 않고 몸을 살짝 세웠다 내립니다.
                    Reward.MOTION_FLAP -> Move(lift = swell * 0.012f, scaleX = 1f - swell * 0.015f, scaleY = 1f + swell * 0.03f)
                    Reward.MOTION_WAG -> Move(wag = swell, angle = sin(p * pi * 6f) * 1.2f)
                    // 꾸벅 인사: 머리를 숙이는 대신 몸 전체를 한 번 눌렀다 폅니다.
                    Reward.MOTION_NOD -> {
                        val bow = sin(p * pi * 2f).coerceAtLeast(0f)
                        Move(scaleX = 1f + bow * 0.02f, scaleY = 1f - bow * 0.045f)
                    }
                    Reward.MOTION_SHAKE -> Move(shift = sin(p * pi * 18f) * 0.012f * (1f - p), angle = sin(p * pi * 18f) * 1.4f * (1f - p))
                    Reward.MOTION_SWAY -> Move(angle = sin(p * pi * 4f) * 4f, lift = abs(sin(p * pi * 4f)) * 0.012f)
                    Reward.MOTION_STRETCH -> Move(scaleX = 1f - swell * 0.03f, scaleY = 1f + swell * 0.055f)
                    Reward.MOTION_HUM -> Move(angle = sin(p * pi * 8f) * 2.4f)
                    else -> STILL
                }
            }
        }
    }

    private companion object {
        const val MESH_W = 20
        const val MESH_H = 18
        const val FOOT_PAD = 10f
        const val SWAP_MS = 220L
        const val HEART_MS = 1_500L
        const val HEARTS = 3
        const val SWAP_SQUASH = 0.07f
        const val BREATH = 0.0065f // 숨 쉴 때 머리 꼭대기가 오르내리는 폭. 키에 대한 비율입니다.
        const val BREATH_SEC = 3.2f
        const val TAIL_SPEED = 2f // 평소 꼬리 흔들기(라디안/초). 한 번 오가는 데 약 3초로 숨과 비슷합니다.
        const val TAIL_SWING = 0.07f // 평소 꼬리 흔들기 폭(라디안)
        const val WAG_SPEED = 8.1f // 꼬리 흔들기 몸짓의 빠르기
        const val WAG_SWING = 0.26f // 꼬리 흔들기 몸짓에서 더하는 폭
        const val Z_RISE_HZ = 0.12f // 잠든 정령 위 z가 떠오르는 빠르기(번/초)
        const val Z_RISE = 0.35f // z가 떠오르는 높이. 키에 대한 비율입니다.
        const val SLEEP_BREATH_SEC = 4.6f
        const val BLINK_SEC = 3.7f
        const val BLINK_MOVE_SEC = 0.14f
        const val BLINK_TWICE = 1.05f
        const val TWO_PI = (2 * PI).toFloat()
        const val FIRST_IDLE_MS = 2_500L
        const val IDLE_GAP_MS = 13_000L
        const val DEFAULT_MOTION_MS = 1_500L
        const val SNEEZE_SEC = 17f
        const val SNEEZE_MOVE_SEC = 0.9f
        const val CLOCK_SPREAD_SEC = 9f // 정령마다 시계를 이만큼 안에서 어긋나게 둡니다. 숨과 깜빡임 주기보다 깁니다.
        const val TALK_HZ = 1.25f
        const val TALK_LIFT = 0.003f
        const val SICK_SATURATION = 0.8f
        const val DULL_SATURATION = 0.86f
        const val DULL_BRIGHTNESS = 0.97f
        const val SHADOW = 0x6E3A241C
        const val SHADOW_REACH = 0.62f // 그림자 반지름. 발이 닿는 폭보다 조금 넓습니다.
        const val SHADOW_FLAT = 0.163f // 그림자 높이 대 폭. 바닥에 납작하게 깔립니다.
        val MOTION_MS = mapOf(
            Reward.MOTION_HOP to 1_500L,
            Reward.MOTION_SWAY to 2_400L,
            Reward.MOTION_STRETCH to 2_200L,
            Reward.MOTION_NOD to 1_400L,
            Reward.MOTION_SHAKE to 1_100L,
            Reward.MOTION_WAG to 1_800L,
            Reward.MOTION_HUM to 2_800L,
        )
    }
}
