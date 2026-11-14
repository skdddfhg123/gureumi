package com.example.pleosapp.ui.game

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.pleosapp.R
import com.example.pleosapp.engine.HomeSpeech
import com.example.pleosapp.engine.Reward
import com.example.pleosapp.engine.RewardKind
import com.example.pleosapp.ui.game.GameStyle.paperText
import com.example.pleosapp.ui.game.GameStyle.sizeTo
import com.example.pleosapp.ui.spirit.HeroArts
import com.example.pleosapp.ui.spirit.HeroPose
import com.example.pleosapp.ui.spirit.SpiritSprites
import com.example.pleosapp.ui.spirit.SpriteSpiritView
import kotlin.math.min

/** 화면 위에 잠깐 펼치는 쪽지. 어디를 눌러도 닫힙니다. 막 펼친 순간의 두 번째 손짓에는 닫히지 않습니다. */
open class PaperNote(context: Context) : FrameLayout(context) {

    protected val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        rotation = -0.6f
    }

    /** 닫힐 때 부릅니다. */
    var onClosed: (() -> Unit)? = null

    /** 펼친 시각(uptime). 병을 빠르게 두 번 누르면 두 번째 손짓이 막 연 쪽지를 닫거나 고르기를 눌렀습니다. */
    private var openedAt = 0L

    init {
        setBackgroundColor(GameStyle.DIM)
        visibility = View.GONE
        isClickable = true
        setOnClickListener { if (!fresh()) close() }
        addView(card, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** 막 펼쳐서 아직 손짓을 받지 않는 동안 참입니다. */
    protected fun fresh(): Boolean = SystemClock.uptimeMillis() - openedAt < OPEN_GUARD_MS

    protected fun open(unit: Float, widthPx: Int) {
        val radius = 2.4f * unit
        card.background = PaperDrawable(context, radius)
        card.elevation = 2f * unit
        elevation = 20f * unit
        card.minimumWidth = widthPx
        card.setPadding((5f * unit).toInt(), (4f * unit).toInt(), (5f * unit).toInt(), (4f * unit).toInt())
        openedAt = SystemClock.uptimeMillis()
        lastOpenedAt = openedAt
        visibility = View.VISIBLE
        card.scaleX = 0.7f
        card.scaleY = 0.7f
        card.alpha = 0f
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(POP_MS).setInterpolator(OvershootInterpolator(2.2f)).start()
    }

    open fun close() {
        if (visibility != View.VISIBLE) return
        visibility = View.GONE
        onClosed?.invoke()
    }

    /** 제목과 줄글만 있는 쪽지. */
    fun show(unit: Float, title: String, lines: List<String>) {
        card.removeAllViews()
        card.gravity = Gravity.START
        card.addView(heading(title, unit).apply { setPadding(0, 0, 0, (1.6f * unit).toInt()) })
        lines.filter { it.isNotBlank() }.forEach { card.addView(body(it, unit)) }
        val room = (parent as? View)?.width ?: width
        open(unit, min((room * 0.78f).toInt(), (120f * unit).toInt()))
    }

    protected fun heading(text: String, unit: Float): TextView = context.paperText(text, bold = true).apply { sizeTo(4.4f * unit, 20f) }

    protected fun body(text: String, unit: Float): TextView = context.paperText(text).apply {
        sizeTo(3.4f * unit, 18f)
        setPadding(0, (0.8f * unit).toInt(), 0, (0.8f * unit).toInt())
    }

    companion object {
        private const val POP_MS = 380L

        /** 막 펼친 쪽지가 손짓을 받지 않는 시간. 펼치는 움직임(0.38초)보다 조금 깁니다. */
        const val OPEN_GUARD_MS = 500L

        /** 어느 쪽지든 마지막으로 펼친 시각(uptime). 화면에서만 씁니다. */
        private var lastOpenedAt = 0L

        /**
         * 쪽지나 카드가 막 펼쳐졌는지. 펼친 직후에는 쪽지가 아직 자리를 잡지 않아 두 번째 손짓이 뒤의 입구에 닿았습니다.
         * 화면은 이때 시작한 손짓을 삼킵니다.
         */
        fun justOpened(): Boolean = SystemClock.uptimeMillis() - lastOpenedAt < OPEN_GUARD_MS
    }
}

/**
 * 앨범에 새로 들어온 순간을 그림책 한 쪽으로 펼칩니다. 그림이 아주 천천히 다가옵니다.
 * 설명 없이 제목과 한 줄만 둡니다. 어디를 눌러도 닫힙니다.
 */
class MomentScene(context: Context) : FrameLayout(context) {

    private val picture = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val title = context.paperText(bold = true).apply { gravity = Gravity.CENTER }
    private val hint = context.paperText(color = GameStyle.INK_SOFT).apply { gravity = Gravity.CENTER }
    private val caption = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        rotation = -0.5f
        addView(title)
        addView(hint)
    }
    private var onClosed: (() -> Unit)? = null

    init {
        setBackgroundColor(GameStyle.INTERIOR)
        visibility = View.GONE
        isClickable = true
        clipChildren = true
        setOnClickListener { close() }
        addView(picture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(caption, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
    }

    fun show(unit: Float, art: Int, titleText: String, hintText: String, closed: () -> Unit) {
        onClosed = closed
        picture.setImageBitmap(SpiritSprites.thumbnail(context, art, 1))
        title.text = titleText
        hint.text = hintText
        title.sizeTo(5f * unit, 22f)
        hint.sizeTo(3.2f * unit, 16f)
        hint.setPadding(0, (0.6f * unit).toInt(), 0, 0)
        caption.background = PaperDrawable(context, 2.4f * unit)
        caption.elevation = 1.6f * unit
        caption.minimumWidth = (70f * unit).toInt()
        caption.setPadding((6f * unit).toInt(), (2.4f * unit).toInt(), (6f * unit).toInt(), (2.6f * unit).toInt())
        (caption.layoutParams as LayoutParams).apply {
            topMargin = (5f * unit).toInt()
            leftMargin = (6f * unit).toInt()
        }
        // 선물 카드(PaperNote 20)보다 낮게 둡니다. 둘이 함께 떠도 순간 그림이 고르기 카드를 덮지 않습니다.
        elevation = 18f * unit
        visibility = View.VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(FADE_MS).start()
        picture.scaleX = 1f
        picture.scaleY = 1f
        picture.animate().scaleX(DRIFT).scaleY(DRIFT).setDuration(DRIFT_MS).setInterpolator(null).start()
    }

    fun close() {
        if (visibility != View.VISIBLE) return
        picture.animate().cancel()
        animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            visibility = View.GONE
            onClosed?.invoke()
            onClosed = null
        }.start()
    }

    private companion object {
        const val FADE_MS = 520L
        const val DRIFT_MS = 9_000L
        const val DRIFT = 1.07f
    }
}

/**
 * 병이 차면 선물을 셋 중 하나 고릅니다. 고르지 않은 것은 다음 병에 다시 나옵니다.
 * 상자를 숨기지 않고 무엇인지 그림과 이름으로 보여 줍니다. 뽑기처럼 보이면 안 됩니다.
 * 바깥을 누르면 고르지 않고 닫히고, 병은 그대로 빛납니다.
 */
class GiftChooser(context: Context, private val plantArt: () -> Int) : PaperNote(context) {

    private var chosen: ((Reward) -> Unit)? = null

    fun show(unit: Float, choices: List<Reward>, onChosen: (Reward) -> Unit) {
        chosen = onChosen
        card.removeAllViews()
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.addView(heading("어떤 선물을 열까?", unit).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (0.6f * unit).toInt())
        })
        card.addView(body("하나를 고르면 나머지는 다음 병에 다시 나와.", unit).apply { gravity = Gravity.CENTER })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (2f * unit).toInt(), 0, 0)
        }
        choices.forEach { reward -> row.addView(option(reward, unit)) }
        card.addView(row)
        open(unit, (120f * unit).toInt())
    }

    private fun option(reward: Reward, unit: Float): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val size = (30f * unit).toInt()
        background = PaperDrawable(context, 1.6f * unit)
        elevation = 1.2f * unit
        setPadding((2f * unit).toInt(), (1.6f * unit).toInt(), (2f * unit).toInt(), (1.6f * unit).toInt())
        addView(ImageView(context).apply {
            scaleType = if (reward.kind == RewardKind.SCENE) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            setImageBitmap(SpiritSprites.reduce(SpiritSprites.bitmap(context, pictureOf(reward)), null, size))
        }, LinearLayout.LayoutParams(size, size))
        addView(context.paperText(reward.title, bold = true).apply {
            gravity = Gravity.CENTER
            sizeTo(3f * unit, 15f)
            setPadding(0, (0.8f * unit).toInt(), 0, 0)
        })
        addView(context.paperText("새 ${reward.kind.title}", color = GameStyle.INK_SOFT).apply {
            gravity = Gravity.CENTER
            sizeTo(2.4f * unit, 13f)
        })
        contentDescription = "${reward.title} 고르기"
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = (1.4f * unit).toInt()
            marginEnd = (1.4f * unit).toInt()
        }
        setOnClickListener {
            // 병을 두 번 누른 두 번째 손짓이 고르기 칸에 닿아도 고르지 않습니다.
            if (fresh()) return@setOnClickListener
            val pick = chosen ?: return@setOnClickListener
            chosen = null
            // 고른 뒤에는 닫힘 알림 없이 바로 보상 화면으로 넘어갑니다.
            this@GiftChooser.visibility = View.GONE
            pick(reward)
        }
    }

    /** 고를 때 보여 줄 그림. 모습은 그 옷을 입은 구름이, 동작은 앉은 구름이, 소품은 소품, 풍경은 창밖입니다. */
    private fun pictureOf(reward: Reward): Int {
        val own = when (reward.kind) {
            RewardKind.FORM -> SpiritSprites.named(context, "hero_${reward.id}_sit")
            RewardKind.MOTION -> 0
            RewardKind.DECOR -> if (reward == Reward.DECOR_FERN) plantArt() else SpiritSprites.named(context, HomeWorld.propArt(reward))
            RewardKind.SCENE -> SpiritSprites.named(context, "window_${reward.id}").takeIf { it != 0 }
                ?: if (reward == Reward.SCENE_WINTER) R.drawable.window_snow else R.drawable.window_day
        }
        return if (own != 0) own else R.drawable.hero_sit
    }

    override fun close() {
        chosen = null
        super.close()
    }
}

/**
 * 병을 열었을 때의 보상 화면. 얻은 것, 살아 움직이는 모습, 다음 칸의 갈래만 보여 줍니다.
 * 다음에 무엇이 나오는지는 말하지 않습니다. 갈래만 알려야 기다릴 재미가 남습니다.
 * [plantArt]는 지금 선반의 화분입니다. 카드와 선반의 그림이 같아야 합니다.
 */
class GiftReveal(context: Context, ticker: MotionTicker, private val plantArt: () -> Int) : PaperNote(context) {

    private val actor = SpriteSpiritView(context).apply { grounded = false }
    private val picture = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val handler = Handler(Looper.getMainLooper())
    private var looped: Reward? = null
    private val loop = object : Runnable {
        override fun run() {
            looped?.let { actor.play(it) }
            handler.postDelayed(this, MOTION_LOOP_MS)
        }
    }

    init {
        ticker.add(actor)
        setBackgroundColor(Color.TRANSPARENT)
    }

    /** 카드를 구름이 곁으로 비켜 둡니다. 선물을 받는 순간의 얼굴이 보여야 합니다. */
    private fun asideOfHero(unit: Float) {
        val room = parent as? View
        val wide = room == null || room.width >= room.height
        val params = card.layoutParams as LayoutParams
        params.width = (if (wide) ASIDE_WIDTH * unit else LayoutParams.WRAP_CONTENT.toFloat()).toInt()
        params.gravity = if (wide) Gravity.CENTER_VERTICAL or Gravity.END else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.marginEnd = if (wide) (6f * unit).toInt() else 0
        params.bottomMargin = if (wide) 0 else (6f * unit).toInt()
        card.layoutParams = params
    }

    /** [next]는 다음 칸입니다. 없으면 사다리를 다 올라온 것입니다. */
    fun show(unit: Float, reward: Reward, next: Reward?) {
        card.removeAllViews()
        card.gravity = Gravity.CENTER_HORIZONTAL
        val artSize = (46f * unit).toInt()
        when (reward.kind) {
            RewardKind.FORM, RewardKind.MOTION -> showActor(reward, unit, artSize)
            RewardKind.DECOR -> showPicture(decorArt(reward), ImageView.ScaleType.FIT_CENTER, artSize)
            RewardKind.SCENE -> showScene(reward, artSize)
        }
        card.addView(heading(reward.title, unit).apply {
            gravity = Gravity.CENTER
            sizeTo(5.4f * unit, 24f)
            setPadding(0, (2.2f * unit).toInt(), 0, (0.4f * unit).toInt())
        })
        card.addView(body(HomeSpeech.rewardLine(reward).replace("\n", " "), unit).apply { gravity = Gravity.CENTER })
        if (next != null) {
            card.addView(context.paperText(next.kind.teaser, color = GameStyle.INK_SOFT).apply {
                gravity = Gravity.CENTER
                sizeTo(3f * unit, 16f)
            })
        }
        asideOfHero(unit)
        open(unit, (78f * unit).toInt())
    }

    /** 사다리 끝의 병에서는 구름이의 쪽지가 나옵니다. */
    fun showNote(unit: Float, note: String) {
        card.removeAllViews()
        card.gravity = Gravity.CENTER_HORIZONTAL
        val size = (40f * unit).toInt()
        actor.setArt(HeroArts.of(context, HeroPose.LETTER, null))
        actor.unit = size / NOTE_ACTOR_HEIGHT
        card.addView(actor, LinearLayout.LayoutParams(size, size))
        card.addView(body(note, unit).apply { gravity = Gravity.CENTER })
        asideOfHero(unit)
        open(unit, (78f * unit).toInt())
    }

    private fun showActor(reward: Reward, unit: Float, size: Int) {
        actor.setArt(HeroArts.of(context, HeroPose.SIT, reward.takeIf { it.kind == RewardKind.FORM }))
        actor.unit = size / ACTOR_HEIGHT
        card.addView(actor, LinearLayout.LayoutParams(size, size))
        if (reward.kind == RewardKind.MOTION) {
            looped = reward
            handler.removeCallbacks(loop)
            handler.postDelayed(loop, FIRST_MOTION_MS)
        } else {
            actor.celebrate()
        }
    }

    private fun decorArt(reward: Reward) =
        if (reward == Reward.DECOR_FERN) plantArt() else SpiritSprites.named(context, HomeWorld.propArt(reward))

    private fun showPicture(resource: Int, scale: ImageView.ScaleType, size: Int) {
        picture.setImageBitmap(SpiritSprites.bitmap(context, resource))
        picture.scaleType = scale
        card.addView(picture, LinearLayout.LayoutParams(size, size))
    }

    /** 창밖 그림은 크니까 줄여서 읽습니다. 창틀을 지나 보이는 풍경처럼 가로로 넓게 보여 줍니다. */
    private fun showScene(reward: Reward, size: Int) {
        val own = SpiritSprites.named(context, "window_${reward.id}")
        val source = if (own != 0) own else if (reward == Reward.SCENE_WINTER) R.drawable.window_snow else R.drawable.window_day
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        picture.setImageBitmap(BitmapFactory.decodeResource(resources, source, options))
        picture.scaleType = ImageView.ScaleType.CENTER_CROP
        card.addView(picture, LinearLayout.LayoutParams(size * 3 / 2, size))
    }

    override fun close() {
        looped = null
        handler.removeCallbacks(loop)
        super.close()
    }

    /**
     * 카드를 연 채 화면이 닫히면 close가 불리지 않습니다. 화면은 이 Handler를 못 지웁니다.
     * 반복이 남아 카드와 화면을 붙잡고 2.9초마다 메인을 깨웠습니다.
     */
    override fun onDetachedFromWindow() {
        looped = null
        handler.removeCallbacks(loop)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MOTION_LOOP_MS = 2_900L
        const val FIRST_MOTION_MS = 500L
        const val ACTOR_HEIGHT = 560f
        const val NOTE_ACTOR_HEIGHT = 560f
        const val ASIDE_WIDTH = 88f
    }
}
