package com.example.pleosapp.ui.share

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.pleosapp.R
import com.example.pleosapp.engine.CareRules
import com.example.pleosapp.engine.DayPart
import com.example.pleosapp.engine.DriveEvent
import com.example.pleosapp.engine.FamilyRepository
import com.example.pleosapp.engine.KeepsakeRepository
import com.example.pleosapp.engine.Moment
import com.example.pleosapp.engine.PetRepository
import com.example.pleosapp.engine.Postcard
import com.example.pleosapp.engine.PostcardCodec
import com.example.pleosapp.engine.PostcardLines
import com.example.pleosapp.engine.RewardKind
import com.example.pleosapp.engine.SceneRules
import com.example.pleosapp.engine.SceneState
import com.example.pleosapp.engine.SkySignals
import com.example.pleosapp.ui.game.GameStyle
import com.example.pleosapp.ui.game.GameStyle.paperText
import com.example.pleosapp.ui.game.GameStyle.sizeTo
import com.example.pleosapp.ui.game.HomeWorld
import com.example.pleosapp.ui.game.PaperDrawable
import com.example.pleosapp.ui.game.SoftSounds
import com.example.pleosapp.ui.game.StageLayout
import com.example.pleosapp.ui.spirit.SpiritSprites
import java.util.Calendar
import kotlin.math.min

/**
 * 그날의 장면을 엽서로 만들어 폰으로 넘깁니다. 차는 QR을 그리기만 하고 통신하지 않습니다.
 * 앨범 순간을 눌러 들어오면 그 장면을 크게 보여 주고, 아니면 지금의 집을 그대로 뒤에 둡니다.
 * 주소를 정하기 전에는 그림만 보여 줍니다. 출발하면 닫힙니다.
 */
class PostcardActivity : Activity() {

    private lateinit var stage: StageLayout
    private lateinit var panel: LinearLayout
    private lateinit var heading: TextView
    private lateinit var body: TextView
    private lateinit var hint: TextView
    private lateinit var closeTag: TextView
    private var world: HomeWorld? = null
    private var picture: ImageView? = null
    private var qr: QrView? = null
    private lateinit var sounds: SoftSounds
    private var moment: Moment? = null

    private val onEvent: (DriveEvent) -> Unit = { event ->
        if (event is DriveEvent.TripStarted) runOnUiThread { finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(GameStyle.INTERIOR))
        PetRepository.init(this)
        FamilyRepository.init(this)
        KeepsakeRepository.init(this)
        moment = Moment.fromId(intent.getStringExtra(EXTRA_MOMENT))
        sounds = SoftSounds(this)
        val scene = currentScene()
        val card = Postcards.make(moment, scene.part, intent.getIntExtra(EXTRA_LINE, NO_LINE))
        val url = ShareLink.url(this, card)
        setContentView(buildStage(card, url))
        PetRepository.addListener(onEvent)
        if (world != null) dressWorld(scene)
        sounds.pop()
    }

    override fun onStart() {
        super.onStart()
        world?.ticker?.start()
    }

    override fun onStop() {
        world?.ticker?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        PetRepository.removeListener(onEvent)
        sounds.release()
        super.onDestroy()
    }

    private fun currentScene(): SceneState {
        val now = Calendar.getInstance()
        val part = SceneRules.dayPart(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MONTH) + 1)
        return SkySignals.scene(part, System.currentTimeMillis())
    }

    private fun dressWorld(scene: SceneState) {
        val pet = PetRepository.state
        val keep = KeepsakeRepository.gifts
        val care = KeepsakeRepository.care
        val family = FamilyRepository.state
        val current = world ?: return
        val motions = CareRules.owned(pet, keep, care).filter { it.kind == RewardKind.MOTION }.sortedBy { it.ordinal }
        current.dress(pet, keep, CareRules.look(pet, care, KeepsakeRepository.odoKm, System.currentTimeMillis()), motions)
        current.dressFamily(family, emptySet())
        current.showDecor(keep)
        current.applyScene(scene, keep)
    }

    private fun buildStage(card: Postcard, url: String?): StageLayout {
        val shown = moment
        stage = StageLayout(this).apply { setBackgroundColor(GameStyle.INTERIOR) }
        if (shown != null) {
            picture = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(SpiritSprites.thumbnail(context, Postcards.art(context, shown), 1))
                contentDescription = shown.title
            }
            stage.addView(picture)
        } else {
            val made = HomeWorld(this)
            world = made
            made.layers.forEach(stage::addView)
        }
        heading = paperText(shown?.title ?: "가족에게 보내기", bold = true).apply { gravity = Gravity.CENTER }
        body = paperText(shown?.hint ?: PostcardLines.ALL[card.line]).apply { gravity = Gravity.CENTER }
        hint = paperText("폰 카메라로 찍으면 엽서가 열려. 설치는 필요 없어.", color = GameStyle.INK_SOFT).apply { gravity = Gravity.CENTER }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            rotation = 0.5f
            addView(heading)
            addView(body)
        }
        if (url != null) {
            qr = QrView(this, QrEncoder.encode(url)).apply { contentDescription = "엽서를 여는 QR 코드" }
            panel.addView(qr)
            panel.addView(hint)
        }
        closeTag = paperText("닫기", bold = true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { finish() }
        }
        stage.addView(panel)
        stage.addView(closeTag)
        stage.onResize = ::arrange
        return stage
    }

    private fun arrange(w: Int, h: Int) {
        val wide = w >= h * WIDE_RATIO
        val u = if (wide) min(w / 170f, h / 121f) else min(w / 96f, h / 150f)
        val gap = (2.4f * u).toInt()
        val current = world
        if (current != null) {
            if (wide) current.placeWide(stage, w, h) else current.placeNarrow(stage, w, h, h / 2)
        }
        picture?.let { stage.place(it, 0, 0, w, h) }

        panel.background = PaperDrawable(this, 2.4f * u)
        panel.elevation = 2f * u
        panel.setPadding((3.4f * u).toInt(), (2.6f * u).toInt(), (3.4f * u).toInt(), (2.6f * u).toInt())
        heading.sizeTo(4.4f * u, 20f)
        body.sizeTo(3.1f * u, 16f)
        body.setPadding(0, (0.6f * u).toInt(), 0, (1.4f * u).toInt())
        hint.sizeTo(2.7f * u, 14f)
        hint.setPadding(0, (1.2f * u).toInt(), 0, 0)
        val panelWidth = if (wide) (w * PANEL_WIDTH).toInt() else w - gap * 2
        val room = if (wide) h - gap * 2 else h / 2 - gap * 2
        val qrSize = min(panelWidth - (6.8f * u).toInt(), room - (29f * u).toInt())
        qr?.let { panel.updateChild(it, qrSize) }
        if (wide) {
            stage.place(panel, w - panelWidth - gap, gap, panelWidth, h - gap * 2, hangs = true)
        } else {
            stage.place(panel, gap, h / 2 + gap, panelWidth, room, hangs = true)
        }

        closeTag.background = PaperDrawable(this, 2f * u)
        closeTag.elevation = 2.2f * u
        closeTag.sizeTo(3.2f * u, 16f)
        stage.place(closeTag, gap, gap, (15f * u).toInt(), (7.4f * u).toInt())
        world?.forgetScene()
        if (current != null) stage.post { current.applyScene(currentScene(), KeepsakeRepository.gifts) }
    }

    private fun LinearLayout.updateChild(view: View, size: Int) {
        view.layoutParams = LinearLayout.LayoutParams(size, size)
    }

    companion object {
        private const val EXTRA_MOMENT = "moment"
        private const val EXTRA_LINE = "line"
        private const val NO_LINE = -1
        private const val WIDE_RATIO = 1.15f
        private const val PANEL_WIDTH = 0.42f

        /** [line]을 주면 그 문장을 씁니다. 회상을 덮은 뒤의 엽서처럼 문장이 정해진 때에 씁니다. */
        fun intent(context: Context, moment: Moment? = null, line: Int = NO_LINE): Intent =
            Intent(context, PostcardActivity::class.java).putExtra(EXTRA_MOMENT, moment?.id).putExtra(EXTRA_LINE, line)
    }
}

/** 지금 상태에서 엽서를 만듭니다. */
object Postcards {
    fun make(moment: Moment?, part: DayPart, line: Int = -1): Postcard {
        val pet = PetRepository.state
        val keep = KeepsakeRepository.gifts
        val trip = pet.lastTrip
        val companions = trip?.let { FamilyRepository.state.rideOf(it.tripId) }.orEmpty()
        return Postcard(
            day = PostcardCodec.dayOf(System.currentTimeMillis()),
            level = pet.level,
            stage = pet.stage.ordinal,
            form = keep.form,
            scene = keep.scene,
            part = part,
            aboard = companions.map { it.preset }.toSet(),
            moment = moment,
            line = line.takeIf { it >= 0 } ?: PostcardLines.pick(trip, companions.size, broughtToday = companions.isNotEmpty() && keep.justBrought != null),
            together = pet.together.days,
        )
    }

    /** 순간의 그림. 자기 그림이 아직 없으면 가까운 장면을 씁니다. */
    fun art(context: Context, moment: Moment): Int {
        val own = SpiritSprites.named(context, "album_${moment.id}")
        if (own != 0) return own
        return when (moment) {
            Moment.FIRST_CLICK -> R.drawable.page_album
            Moment.GATHERED -> R.drawable.page_picnic
            Moment.LETTER -> R.drawable.page_letter
            else -> R.drawable.page_home
        }
    }
}

/** 엽서 주소. 주소를 정하기 전이면 null이고, 그때는 QR을 그리지 않습니다. */
object ShareLink {
    fun url(context: Context, card: Postcard): String? {
        val base = context.getString(R.string.share_base_url)
        return if (base.isBlank()) null else "$base#${PostcardCodec.encode(card)}"
    }
}

/** QR 그림. 칸을 정수 픽셀로 맞춰야 또렷하게 찍힙니다. 밤에도 흰 바탕을 지킵니다. */
private class QrView(context: Context, private val matrix: QrEncoder.Matrix) : View(context) {

    private val ink = Paint().apply { color = Color.BLACK }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val cells = matrix.size + QUIET * 2
        val module = min(width, height) / cells
        if (module == 0) return
        val left = (width - module * matrix.size) / 2
        val top = (height - module * matrix.size) / 2
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (!matrix.dark(x, y)) continue
                val cx = (left + x * module).toFloat()
                val cy = (top + y * module).toFloat()
                canvas.drawRect(cx, cy, cx + module, cy + module, ink)
            }
        }
    }

    private companion object {
        const val QUIET = 4
    }
}
