package com.example.pleosapp.ui.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LightingColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import com.example.pleosapp.R
import com.example.pleosapp.engine.DayPart
import com.example.pleosapp.engine.Reward
import com.example.pleosapp.engine.SceneRules
import com.example.pleosapp.engine.SceneState
import com.example.pleosapp.engine.Season
import com.example.pleosapp.engine.Sky
import com.example.pleosapp.engine.SkySignals
import com.example.pleosapp.ui.river.RiverScene
import com.example.pleosapp.ui.spirit.SceneArt
import com.example.pleosapp.ui.spirit.SpiritSprites
import java.util.Calendar
import java.util.Random
import kotlin.math.max
import kotlin.math.sin

/**
 * 정령의 집의 설계 좌표. 그림은 1536×1024이고 앱은 창에 맞춰 잘라 보여 줍니다.
 * [Spot]은 물건의 가운데, 바닥, 높이입니다. 자리마다 받침 면이 있고, 그 면은 [Surface]로 둡니다.
 * 장면 규칙은 docs/review/2026-11-04-concept.md 2장, 칸 나누기는 5장입니다.
 */
object HomeScene {
    const val WIDTH = 1536f
    const val HEIGHT = 1024f

    /** 창밖 그림을 그리는 띠. 위는 옆문 유리 위쪽, 아래는 앞 창 유리 아래쪽입니다. */
    const val WINDOW_TOP = 190f
    const val WINDOW_BOTTOM = 490f

    class Spot(val x: Float, val bottom: Float, val height: Float)

    /**
     * 받침 면. 물건과 정령이 발을 딛는 윗면입니다. [xs]마다 윗면 뒤쪽 선 [back]과 앞쪽 선 [front]의 y이고, 사이는 고르게 잇습니다.
     * 발끝이 두 선 사이에 오면 놓인 것입니다. 배경 그림(home_*)에서 쟀습니다.
     */
    class Surface(val name: String, private val xs: FloatArray, private val back: FloatArray, private val front: FloatArray) {
        val left: Float get() = xs.first()
        val right: Float get() = xs.last()

        fun backAt(x: Float): Float = along(back, x)

        fun frontAt(x: Float): Float = along(front, x)

        fun holds(spot: Spot): Boolean =
            spot.x in left..right && spot.bottom in backAt(spot.x) - SLACK..frontAt(spot.x) + SLACK

        private fun along(ys: FloatArray, x: Float): Float {
            val i = xs.indexOfLast { it <= x }.coerceIn(0, xs.size - 2)
            val t = ((x - xs[i]) / (xs[i + 1] - xs[i])).coerceIn(0f, 1f)
            return ys[i] + (ys[i + 1] - ys[i]) * t
        }
    }

    /** 받침 면에서 이만큼 벗어나도 놓인 것으로 봅니다. */
    const val SLACK = 6f

    /** 창 바로 아래 긴 선반 하나(배경 v2). 오른쪽 끝은 옆문 문틀입니다. 앞 모서리는 오른쪽으로 갈수록 조금 내려옵니다. */
    val SHELF = Surface(
        "선반",
        floatArrayOf(20f, 150f, 300f, 450f, 600f, 750f, 900f, 1050f, 1200f, 1280f),
        floatArrayOf(507f, 503f, 505f, 505f, 505f, 505f, 505f, 506f, 510f, 514f),
        floatArrayOf(547f, 544f, 541f, 540f, 540f, 542f, 546f, 551f, 560f, 562f),
    )

    /** 11/4까지의 왼쪽 선반. 시간 성장 쪽 코드가 옮겨 갈 때까지 [SHELF]를 가리킵니다. */
    val LEFT_SHELF get() = SHELF

    /** 구름이 방석 윗면. */
    val CUSHION = Surface("방석", floatArrayOf(330f, 505f, 680f), floatArrayOf(690f, 680f, 700f), floatArrayOf(770f, 775f, 770f))

    /** 접어 둔 담요의 윗면. 왼쪽 아래에서 둥지 앞까지 비스듬히 놓였습니다. */
    val QUILT = Surface("담요", floatArrayOf(660f, 800f, 950f, 1100f), floatArrayOf(800f, 760f, 720f, 830f), floatArrayOf(915f, 915f, 908f, 895f))

    /** 마루 전체에 깐 러그. 뒤쪽은 벽판 아래, 오른쪽은 옆문 앞까지입니다. 둥지 자리는 [NEST_BOX]로 따로 막습니다. */
    val RUG = Surface(
        "러그",
        floatArrayOf(100f, 250f, 400f, 650f, 800f, 950f, 1100f, 1300f, 1450f),
        floatArrayOf(720f, 660f, 640f, 625f, 620f, 640f, 700f, 790f, 830f),
        floatArrayOf(940f, 945f, 930f, 920f, 915f, 910f, 905f, 900f, 900f),
    )

    /** 아기 둥지 안쪽. 잠든 아기만 눕습니다. */
    val NEST = Surface("둥지", floatArrayOf(965f, 1120f, 1275f), floatArrayOf(700f, 690f, 700f), floatArrayOf(810f, 815f, 810f))

    /** 둥지가 차지하는 바닥. 깨어 있는 정령은 이 안에 앉지 않습니다. 배경에 그려진 둥지라 정령이 앞에 오면 겹쳐 보입니다. */
    val NEST_BOX = floatArrayOf(935f, 615f, 1305f, 845f)

    val SURFACES = listOf(SHELF, CUSHION, QUILT, RUG, NEST)

    /** 선반은 작은 물건만 둡니다. 정령은 마루(방석, 담요, 러그)에만 삽니다(2-1). 잠든 아기만 둥지에 눕습니다. */
    val SHELVES = listOf(SHELF)
    val FLOORS = listOf(CUSHION, QUILT, RUG)

    // 선반 위: 깃털 병, 화분, 돌 줄. 걸이: 모빌. 마루 위: 구름이, 아기, 수첩, 앨범, 귀환품 접시. 둥지: 잠든 아기.
    val JAR = Spot(165f, 538f, 190f)
    val HERO = Spot(505f, 762f, 400f)
    val FERN = Spot(295f, 537f, 165f)

    /** 모빌을 매다는 오른쪽 천장 손잡이. 끈이 길어 맨 아래 돌이 선반 윗면에 닿습니다(R-118). */
    const val MOBILE_HOOK_X = 1135f
    const val MOBILE_HOOK_Y = 106f
    val MOBILE = Spot(MOBILE_HOOK_X, 537f, 537f - MOBILE_HOOK_Y)

    /**
     * 돌 줄. 새싹이 주워 온 조약돌을 크기순으로 세웁니다(컨셉 점검 3장). 왼쪽이 가장 큰 돌이고 오른쪽으로 자랍니다.
     * 구름이 머리 오른쪽부터 모빌 앞까지입니다. 키는 돌 그림 키 × 0.44입니다(scripts/cut_home.py STONE_DESIGN).
     */
    val STONE_ROW = listOf(
        Spot(720f, 538f, 35.6f), Spot(776f, 538f, 31.7f), Spot(827f, 538f, 29.9f), Spot(873f, 538f, 27.3f),
        Spot(916f, 538f, 25.5f), Spot(956f, 538f, 23.8f), Spot(993f, 538f, 22f), Spot(1028f, 538f, 20.2f),
    )

    /**
     * 들꽃 컵(R-121, 사다리 12칸). 선반 오른쪽 끝, 매단 모빌 테와 옆문 사이입니다. 강가 들꽃을 꽂아 강가와 집을 잇습니다.
     * 구름이 머리와 돌 줄, 모빌 테를 가리지 않습니다.
     */
    val VASE = Spot(1240f, 538f, 70f)

    /** 눕힌 앨범. 방석 앞 러그 위입니다. 둥지와 도토리 꼬리를 비킵니다. */
    val ALBUM = Spot(545f, 925f, 66f)

    /** 눕힌 정비 수첩. 구름이 곁 러그 위입니다. */
    val CAREBOOK = Spot(205f, 940f, 66f)

    /** 귀환품 접시. 도토리 곁 러그 앞입니다. */
    val TRAY = Spot(935f, 902f, 60f)
    val PARTY = Spot(335f, 922f, 105f)

    /** 바닥 소품 층의 바닥선. 담요 위 정령은 바닥 소품 뒤에, 러그 앞줄 정령은 앞에 옵니다. */
    const val FLOOR_FEET = 895f

    /**
     * 깊이 배율. 발끝이 화면 아래로 올수록 큽니다(2-3). 기준 창(1680×1209)의 화면 66%, 86%, 98%가 0.85, 1.0, 1.1배입니다.
     * 86%는 구름이 방석이 마루에 닿는 선입니다.
     */
    fun depth(feet: Float): Float = when {
        feet <= DEPTH_FAR -> 0.85f
        feet <= DEPTH_MID -> 0.85f + (feet - DEPTH_FAR) / (DEPTH_MID - DEPTH_FAR) * 0.15f
        else -> (1f + (feet - DEPTH_MID) / (DEPTH_NEAR - DEPTH_MID) * 0.1f).coerceAtMost(1.1f)
    }

    const val DEPTH_FAR = 676f
    const val DEPTH_MID = 881f
    const val DEPTH_NEAR = 1004f

    /** 1.0배 깊이의 아기 키. 구름이 앉은키 400의 47.5%입니다(2-3). */
    const val BABY_HEIGHT = 190f

    /** 아기가 앉는 자리. 키는 깊이로 정합니다. */
    fun baby(x: Float, feet: Float) = Spot(x, feet, BABY_HEIGHT * depth(feet))

    /**
     * 아기마다 정해진 깬 자리(5-3). 깨어 있으면 앉아서 자기 일을 합니다. 자면 둥지([NEST_BEDS])에 눕습니다.
     * 뒷줄 둘은 구름이와 둥지 사이, 앞줄 둘은 담요 앞과 둥지 앞입니다. 둥지 자리에는 앉지 않습니다.
     */
    val SPROUT = baby(850f, 700f) // 새싹: 마루 뒤, 돌 줄 바로 아래. 돌을 고르는 자리
    val LILAC = baby(720f, 706f) // 라일락: 마루 뒤, 구름이 곁. 꼬리는 구름이 무릎 뒤로 숨습니다
    val ACORN = baby(795f, 905f) // 도토리: 담요 앞. 귀환품 접시 곁
    val FORSYTHIA = baby(1135f, 897f) // 개나리: 둥지 앞

    val BABIES = listOf(SPROUT, LILAC, ACORN, FORSYTHIA)

    /**
     * 둥지 잠자리. 잠든 아기는 앱 크기 그대로 서로 겹쳐 눕습니다. 먼저 잠든 아기가 앞 가운데입니다.
     * 둥지 앞 테두리 층([NEST_FRONT_LEFT], [NEST_FRONT_TOP])이 아기 아래쪽을 덮어 둥지 안에 누운 모습이 됩니다.
     */
    val NEST_BEDS = listOf(baby(1120f, 800f), baby(1035f, 772f), baby(1205f, 776f), baby(1118f, 738f))

    /** 둥지 앞 테두리 층. 배경에서 잘라 낸 400×160 조각의 자리와 앞뒤를 정하는 발 자리입니다(scripts/cut_home.py NEST_FRONT_BOX). */
    const val NEST_FRONT_LEFT = 920f
    const val NEST_FRONT_TOP = 700f
    const val NEST_FRONT_FEET = 845f

    /** 도착 화면에서 함께 탄 아기가 모이는 자리. 오른쪽은 일기장이 덮어서 구름이 곁에 모입니다. */
    val ARRIVAL = listOf(baby(700f, 698f), baby(865f, 704f), baby(760f, 905f), baby(950f, 900f))

    /** 아주 좁은 창에서는 구름이 곁까지만 보여서 더 붙어 앉습니다. */
    val TIGHT = listOf(baby(710f, 840f), baby(770f, 900f), baby(640f, 905f), baby(700f, 912f))

    /** 세로로 좁은 창에서는 양옆이 잘리므로 모두 담요 위에 모입니다. */
    val NARROW = listOf(baby(780f, 800f), baby(860f, 790f), baby(700f, 890f), baby(830f, 900f))

    /** 천장 실내등의 가운데. 저녁부터 켜집니다. */
    const val ROOF_LAMP_X = 768f
    const val ROOF_LAMP_Y = 228f
}

/** 장면에 쓸 그림. [warmLamp]면 저녁부터 실내등이 노을빛으로 켜집니다(사다리 보상). */
/**
 * 장면을 다시 칠할지 정하는 값. [feathers]는 깃털 둥지(R-122), [butterflies]는 창밖 강가 나비(R-123) 보상을 받았는지입니다.
 */
data class NookLook(
    val state: SceneState,
    val winter: Boolean,
    val warmLamp: Boolean,
    val season: Season,
    val feathers: Boolean = false,
    val butterflies: Boolean = false,
)

/** 시간과 날씨가 집 안에 드는 빛. 정령과 소품도 같은 빛을 입습니다. */
object NookLight {
    /** 지금 시계와 차 신호로 본 장면. 집과 강가가 같은 장면을 씁니다. */
    fun now(): SceneState {
        val now = Calendar.getInstance()
        val part = SceneRules.dayPart(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MONTH) + 1)
        return SkySignals.scene(part, System.currentTimeMillis())
    }

    fun ambient(state: SceneState): Int = when {
        state.part == DayPart.NIGHT -> 0x8682B0
        state.sky == Sky.RAIN -> 0xC6CAD6
        state.sky == Sky.SNOW -> 0xDCE2EC
        state.sky == Sky.FOG -> 0xDAD9D4
        state.part == DayPart.MORNING -> 0xFFF0DA
        state.part == DayPart.EVENING -> 0xFFDFC2
        else -> 0xFFFFFF
    }

    /** 창밖에 낀 옅은 공기. 창 그림이 방보다 쨍하면 따로 노는 그림으로 보입니다. */
    fun haze(state: SceneState): Int = when {
        state.part == DayPart.NIGHT -> 0x1E2A2A50
        state.sky != Sky.CLEAR -> 0x30E4E4EA
        state.part == DayPart.MORNING -> 0x2EFFE9C8
        state.part == DayPart.EVENING -> 0x24FFD7A8
        else -> 0x28FBF1E1
    }

    /** 저녁과 밤에는 천장 실내등이 켜집니다. 켜진 불은 둘레를 밝힙니다(2-2). */
    fun roofLampOn(state: SceneState): Boolean = state.part == DayPart.EVENING || state.part == DayPart.NIGHT

    /**
     * 고른 풍경은 낮 그림 한 장뿐이라 시간대 색을 입힙니다. 별 언덕은 밤 그림이라 그대로 둡니다.
     * 11/4부터 창밖은 늘 강가라 홈은 쓰지 않습니다. 함께한 날 풍경을 장소로 옮길 때까지 시간 성장 테스트가 씁니다.
     */
    fun windowTint(state: SceneState, chosen: Reward?): Int? = when {
        chosen == Reward.SCENE_STARS -> null
        state.part == DayPart.NIGHT && (chosen != null || state.sky != Sky.CLEAR) -> 0x4E4E86
        chosen == null -> null
        state.sky == Sky.RAIN -> 0xB8BCC8
        state.sky == Sky.SNOW -> 0xD0D8E4
        state.part == DayPart.MORNING -> 0xFFEBD2
        state.part == DayPart.EVENING -> 0xFFC8A0
        else -> null
    }
}

/**
 * 살아 있는 창가. 창밖은 차를 대어 둔 강가입니다. 강가 그림을 흐리게 잘라 쓰고 그 위로 비와 눈이 내립니다.
 * 그 위에 창이 뚫린 실내판을 덮습니다. 실내판은 장면이 바뀔 때 한 번만 색을 입혀 굳히므로 매 프레임 비용이 작습니다.
 */
class NookView(context: Context) : View(context) {

    private class Layers(val look: NookLook, val window: Bitmap, val plate: Bitmap, val windowTint: Int?, val life: WindowLife?, val nestRim: Bitmap?)

    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fadePaint = Paint(image)
    private val windowPaint = Paint(image)
    private val dustPaint = Paint(image)
    private val sudsPaint = Paint(image)
    private val fogPaint = Paint(image).apply { alpha = 205 }
    private val mote = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MOTE }
    private val hazePaint = Paint()
    private val ray = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(0, RAY, 0), null, Shader.TileMode.CLAMP)
    }
    private val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4f
    }
    private val rain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = RAIN_LINE
        strokeWidth = 2.4f
        strokeCap = Paint.Cap.ROUND
    }
    private val drip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DRIP }
    private val flake = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SNOW
        strokeCap = Paint.Cap.ROUND
    }
    private val frost = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 545f, 0f, 400f, FROST, 0, Shader.TileMode.CLAMP)
    }
    private val trail = Path()
    private val rect = RectF()
    private val windowArea = RectF(0f, HomeScene.WINDOW_TOP, HomeScene.WIDTH, HomeScene.WINDOW_BOTTOM)
    private val windowBand = Rect()
    private val transform = Matrix()

    private var look: NookLook? = null
    private var layers: Layers? = null
    private var previous: Layers? = null
    private var changedAt = 0L
    private var fog: Bitmap? = null
    private var dustFilm: Bitmap? = null
    private var suds: Bitmap? = null
    private var dust = 0f
    private var wipeFrom = 0f
    private var wipeStart = 0L
    private var viewScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val random = Random(5)
    private val moteSeeds = FloatArray(MOTES * 4) { random.nextFloat() }
    private val rainSeeds = FloatArray(RAIN_LINES * 3) { random.nextFloat() }
    private val rainPoints = FloatArray(RAIN_LINES * 4)
    private val dripSeeds = FloatArray(DRIPS * 3) { random.nextFloat() }
    private val snowSeeds = FloatArray(FLAKES * 4) { random.nextFloat() }
    private val snowPoints = FloatArray(FLAKES * 2)
    private val glintSeeds = FloatArray(GLINTS * 3) { random.nextFloat() }

    /** 창밖에 날리는 것이 있을 때 필요한 다시 그림 간격. 없으면 아주 큰 값입니다. */
    var outsideMs: () -> Long = { Long.MAX_VALUE }

    /** 첫 그림이 준비되면 한 번 부릅니다. 그 전에는 화면을 보여 주지 않습니다. */
    var onFirstFrame: (() -> Unit)? = null

    /** 창밖과 실내판 사이에 그릴 것. 창틀 뒤로 지나가는 계절 조각입니다. 설계 좌표와 초 단위 시각을 받습니다. */
    var outside: ((Canvas, Float) -> Unit)? = null

    /** 창을 꽉 채웁니다. [focusX]는 창 가운데에 둘 설계 좌표입니다. 그림 밖으로는 밀리지 않습니다. */
    fun fit(w: Int, h: Int, focusX: Float = HomeScene.WIDTH / 2f) {
        viewScale = max(w / HomeScene.WIDTH, h / HomeScene.HEIGHT)
        offsetX = (w / 2f - focusX * viewScale).coerceIn(w - HomeScene.WIDTH * viewScale, 0f)
        offsetY = (h - HomeScene.HEIGHT * viewScale) / 2f
        transform.setScale(viewScale, viewScale)
        transform.postTranslate(offsetX, offsetY)
    }

    fun viewX(designX: Float): Float = offsetX + designX * viewScale

    fun viewY(designY: Float): Float = offsetY + designY * viewScale

    val sceneScale: Float get() = viewScale

    /** 앞유리의 먼지. 줄어들면 유리를 닦아 내는 장면을 한 번 보여 줍니다. */
    fun setDust(next: Float) {
        if (next == dust) return
        if (next + WIPE_MIN_DROP < dust) {
            wipeFrom = dust
            wipeStart = SystemClock.uptimeMillis()
        }
        dust = next
        if (dustFilm == null && (dust > 0f || wipeStart > 0L)) {
            Thread {
                val loaded = BitmapFactory.decodeResource(resources, R.drawable.overlay_dust)
                val bubble = BitmapFactory.decodeResource(resources, R.drawable.fx_bubble)
                post {
                    dustFilm = loaded
                    suds = bubble
                }
            }.start()
        }
    }

    /** 큰 그림이라 화면을 막지 않도록 뒤에서 읽고 색을 입힙니다. */
    fun show(next: NookLook) {
        if (next == look) return
        look = next
        if (next.state.sky == Sky.FOG && fog == null) {
            Thread {
                val loaded = BitmapFactory.decodeResource(resources, R.drawable.fog_band)
                post { fog = loaded }
            }.start()
        }
        Thread {
            val river = RiverScene.look(next.state, if (next.winter) Season.WINTER else next.season)
            val interior = roofed(SceneArt.interior(context, next.winter))
            if (next.feathers) featherbed(interior, next)
            val plate = grade(interior, next)
            interior.recycle()
            val loaded = Layers(next, riverWindow(river.art), plate, river.tint, WindowLife.of(context, next), nestRim(plate, next))
            post { if (next == look) use(loaded) }
        }.start()
    }

    /**
     * 깃털 둥지(R-122). 둥지 안 담요와 이끼 위에 깔린 깃털을 실내판에 겹칩니다. 빛을 입히기 전이라 방과 같은 빛을 받습니다.
     * 그림은 계절마다 `home_{계절}_featherbed`이고 둥지 상자 왼쪽 위에 맞춰 잘라 둡니다(370×230).
     */
    private fun featherbed(interior: Bitmap, target: NookLook) {
        val season = if (target.winter) Season.WINTER else target.season
        // 계절 그림이 없으면 가을 그림을 씁니다. 네 계절 방에 겹쳐 봐도 이음매가 없습니다.
        val art = SpiritSprites.named(context, "home_${season.name.lowercase()}_featherbed").takeIf { it != 0 }
            ?: SpiritSprites.named(context, "home_autumn_featherbed").takeIf { it != 0 } ?: return
        val feathers = BitmapFactory.decodeResource(resources, art)
        Canvas(interior).drawBitmap(feathers, HomeScene.NEST_BOX[0], HomeScene.NEST_BOX[1], null)
        feathers.recycle()
    }

    /** 지금 장면의 둥지 앞 테두리. 그림이 없으면 null입니다. */
    val nestRim: Bitmap? get() = layers?.nestRim

    /**
     * 둥지 앞 테두리. 빛을 입힌 실내판에서 그 자리를 테두리 모양대로 잘라 둡니다. 방과 빛이 같아 이음매가 보이지 않습니다.
     * 잠든 아기 위에 덮어 아기가 둥지 안에 누운 모습이 됩니다. 모양은 계절마다 잘라 둔 `home_{계절}_nest_front`의 투명도입니다.
     */
    private fun nestRim(plate: Bitmap, target: NookLook): Bitmap? {
        val season = if (target.winter) Season.WINTER else target.season
        val art = SpiritSprites.named(context, "home_${season.name.lowercase()}_nest_front").takeIf { it != 0 } ?: return null
        val shape = BitmapFactory.decodeResource(resources, art)
        val rim = Bitmap.createBitmap(shape.width, shape.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(rim)
        canvas.drawBitmap(shape, 0f, 0f, null)
        val inside = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }
        canvas.drawBitmap(plate, -HomeScene.NEST_FRONT_LEFT, -HomeScene.NEST_FRONT_TOP, inside)
        shape.recycle()
        return rim
    }

    /**
     * 창밖에 보일 강가. 문 밖 강가와 같은 그림을 4분의 1로 읽어 창 띠([RIVER_BAND])만 크게 그립니다.
     * 크게 늘이면 멀리 있는 듯 흐려집니다. 비 오는 날도 같은 강가에 비가 내립니다.
     */
    private fun riverWindow(art: String): Bitmap {
        val resource = SpiritSprites.named(context, art).takeIf { it != 0 } ?: R.drawable.place_river
        val options = BitmapFactory.Options().apply {
            inSampleSize = WINDOW_SAMPLE
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeResource(resources, resource, options)
    }

    /** 실내판 위쪽을 차 천장으로 덮습니다. 한 장으로 굳혀 시간대 색도 같이 입습니다. */
    private fun roofed(interior: Int): Bitmap {
        val plate = BitmapFactory.decodeResource(resources, interior, BitmapFactory.Options().apply { inMutable = true })
        val roof = BitmapFactory.decodeResource(resources, R.drawable.home_roof)
        Canvas(plate).drawBitmap(roof, 0f, 0f, null)
        roof.recycle()
        return plate
    }

    /** 지금 장면이 원하는 다시 그림 간격. 구름은 느려서 자주 그리지 않아도 됩니다. */
    fun redrawMs(): Long {
        val now = SystemClock.uptimeMillis()
        val state = look?.state ?: return MOVING_MS
        if (now - changedAt < FADE_MS || now - wipeStart < WIPE_MS) return MOVING_MS
        val sky = when (state.sky) {
            Sky.RAIN -> MOVING_MS
            Sky.SNOW -> SNOW_MS
            Sky.FOG -> FOG_MS
            Sky.CLEAR -> if (state.part == DayPart.NIGHT || state.part == DayPart.EVENING) NIGHT_MS else CLEAR_MS
        }
        return minOf(sky, outsideMs(), layers?.life?.redrawMs ?: Long.MAX_VALUE)
    }

    private fun use(loaded: Layers) {
        previous = layers
        changedAt = SystemClock.uptimeMillis()
        layers = loaded
        invalidate()
        post {
            onFirstFrame?.invoke()
            onFirstFrame = null
        }
    }

    /** 실내에 시간대의 빛을 입힙니다. 실내등이 켜지면 그 둘레를 따뜻하게 다시 그립니다. */
    private fun grade(interior: Bitmap, target: NookLook): Bitmap {
        val out = Bitmap.createBitmap(interior.width, interior.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val ambient = NookLight.ambient(target.state)
        paint.colorFilter = if (ambient == 0xFFFFFF) null else LightingColorFilter(ambient, 0)
        canvas.drawBitmap(interior, 0f, 0f, paint)
        if (NookLight.roofLampOn(target.state)) {
            lightAround(canvas, interior, paint, target)
            lightRoofLamp(canvas, target.warmLamp)
        }
        return out
    }

    /**
     * 실내등 둘레를 등 빛으로 다시 칠합니다. 불만 밝고 둘레가 어두우면 따로 노는 불로 보입니다(2-2의 5).
     * 노을빛 보상이 있으면 빛이 더 붉고 멀리 닿아 구름이와 아기들까지 덮습니다.
     */
    private fun lightAround(canvas: Canvas, interior: Bitmap, paint: Paint, target: NookLook) {
        val night = target.state.part == DayPart.NIGHT
        val reach = if (target.warmLamp) WARM_REACH else LAMP_REACH
        val stops = if (night) NIGHT_LAMP_STOPS else EVENING_LAMP_STOPS
        val layer = canvas.saveLayer(null, null)
        paint.colorFilter = LightingColorFilter(if (target.warmLamp) WARM_LIGHT else LAMP_LIGHT, 0)
        canvas.drawBitmap(interior, 0f, 0f, paint)
        val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(HomeScene.ROOF_LAMP_X, HomeScene.ROOF_LAMP_Y, reach, stops, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawRect(0f, 0f, HomeScene.WIDTH, HomeScene.HEIGHT, mask)
        canvas.restoreToCount(layer)
    }

    /** 켜진 실내등. 등은 시간대 색을 입히지 않습니다. */
    private fun lightRoofLamp(canvas: Canvas, warm: Boolean) {
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(HomeScene.ROOF_LAMP_X, HomeScene.ROOF_LAMP_Y, ROOF_GLOW, if (warm) WARM_GLOW_COLOR else ROOF_GLOW_COLOR, 0, Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(HomeScene.ROOF_LAMP_X, HomeScene.ROOF_LAMP_Y, ROOF_GLOW, glow)
        val lamp = BitmapFactory.decodeResource(resources, R.drawable.home_roof_lamp)
        canvas.drawBitmap(lamp, ROOF_LAMP_LEFT, ROOF_LAMP_TOP, null)
        lamp.recycle()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (viewScale == 1f) fit(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val current = layers ?: return
        val now = SystemClock.uptimeMillis()
        val t = MotionTicker.seconds(now)
        canvas.save()
        canvas.concat(transform)
        val old = previous
        val fade = (now - changedAt).toFloat() / FADE_MS
        if (old != null && fade < 1f) {
            drawWorld(canvas, old, t, now, 255)
            drawWorld(canvas, current, t, now, (fade * 255).toInt())
        } else {
            previous = null
            drawWorld(canvas, current, t, now, 255)
        }
        canvas.restore()
    }

    private fun drawWorld(canvas: Canvas, world: Layers, t: Float, now: Long, alpha: Int) {
        val state = world.look.state
        windowPaint.alpha = alpha
        windowPaint.colorFilter = world.windowTint?.let { LightingColorFilter(it, 0) }
        canvas.drawBitmap(world.window, bandOf(world.window), windowArea, windowPaint)
        if (alpha == 255) {
            when (state.sky) {
                Sky.RAIN -> drawRain(canvas, t)
                Sky.SNOW -> drawSnow(canvas, t)
                Sky.FOG -> fog?.let { drawFog(canvas, it, t) }
                Sky.CLEAR -> drawWindowLight(canvas, world.look, t)
            }
            world.life?.draw(canvas, t, windowPaint)
            hazePaint.color = NookLight.haze(state)
            canvas.drawRect(HAZE_LEFT, HomeScene.WINDOW_TOP, HAZE_RIGHT, HAZE_BOTTOM, hazePaint)
            outside?.invoke(canvas, t)
            drawDirt(canvas, now)
        }
        fadePaint.alpha = alpha
        canvas.drawBitmap(world.plate, 0f, 0f, fadePaint)
        if (alpha == 255) {
            drawRoomLight(canvas, world.look, t)
            if (state.sky != Sky.RAIN) drawMotes(canvas, state.part, t)
        }
    }

    /** 4분의 1로 읽은 강가 그림에서 창 띠에 해당하는 칸. */
    private fun bandOf(window: Bitmap): Rect {
        val scale = window.width / HomeScene.WIDTH
        windowBand.set(0, (RIVER_BAND * scale).toInt(), window.width, ((RIVER_BAND + HomeScene.WINDOW_BOTTOM - HomeScene.WINDOW_TOP) * scale).toInt())
        return windowBand
    }

    /** 맑은 날 창밖의 빛: 노을과 달빛이 강물에 비쳐 반짝입니다. 강물은 창 오른쪽 아래입니다. */
    private fun drawWindowLight(canvas: Canvas, target: NookLook, t: Float) {
        when (target.state.part) {
            DayPart.NIGHT -> drawGlints(canvas, t, 900f, 1280f, 455f, 22f, 0xFFFFD79A.toInt())
            DayPart.EVENING -> drawGlints(canvas, t, 900f, 1280f, 453f, 24f, 0xFFFFE2B0.toInt())
            else -> Unit
        }
    }

    /** 방 안의 빛: 맑은 아침에 창으로 드는 사선. */
    private fun drawRoomLight(canvas: Canvas, target: NookLook, t: Float) {
        val state = target.state
        if (state.part == DayPart.MORNING && state.sky == Sky.CLEAR) drawRays(canvas, t)
    }

    private fun drawRays(canvas: Canvas, t: Float) {
        canvas.save()
        canvas.rotate(20f, 600f, 300f)
        RAYS.forEachIndexed { i, spec ->
            canvas.save()
            canvas.translate(spec[0], -200f)
            canvas.scale(spec[1], 1f)
            ray.alpha = (110 + 60 * sin(t * 0.4f + i * 1.7f)).toInt()
            canvas.drawRect(0f, 0f, 1f, 1200f, ray)
            canvas.restore()
        }
        canvas.restore()
    }

    private fun drawGlints(canvas: Canvas, t: Float, left: Float, right: Float, top: Float, height: Float, color: Int) {
        glint.color = color
        for (i in 0 until GLINTS) {
            val x = left + glintSeeds[i * 3] * (right - left)
            val y = top + glintSeeds[i * 3 + 1] * height
            val light = (sin(t * (1.1f + glintSeeds[i * 3 + 2] * 1.8f) + i * 2.1f) + 1f) / 2f
            glint.alpha = (light * light * 200).toInt()
            val half = 8f + glintSeeds[i * 3 + 2] * 16f
            canvas.drawLine(x - half, y, x + half, y, glint)
        }
    }

    private fun drawRain(canvas: Canvas, t: Float) {
        for (i in 0 until RAIN_LINES) {
            val a = rainSeeds[i * 3]
            val b = rainSeeds[i * 3 + 1]
            val c = rainSeeds[i * 3 + 2]
            val y = (b * RAIN_SPAN + t * (480f + c * 340f)) % RAIN_SPAN + 200f
            val x = 120f + a * 1320f
            rainPoints[i * 4] = x
            rainPoints[i * 4 + 1] = y
            rainPoints[i * 4 + 2] = x - 3f
            rainPoints[i * 4 + 3] = y + 22f + c * 26f
        }
        canvas.drawLines(rainPoints, rain)
        for (i in 0 until DRIPS) {
            val x = 160f + dripSeeds[i * 3] * 1260f
            val speed = 16f + dripSeeds[i * 3 + 2] * 34f
            val y = (dripSeeds[i * 3 + 1] * RAIN_SPAN + t * speed) % RAIN_SPAN + 200f
            trail.reset()
            trail.moveTo(x - 3f, y)
            trail.lineTo(x + 3f, y)
            trail.lineTo(x + 1f, y - 54f)
            trail.lineTo(x - 1f, y - 54f)
            trail.close()
            drip.alpha = 70
            canvas.drawPath(trail, drip)
            drip.alpha = 170
            canvas.drawCircle(x, y, 5f, drip)
        }
    }

    private fun drawSnow(canvas: Canvas, t: Float) {
        canvas.drawRect(100f, 400f, 1460f, 560f, frost)
        for (group in 0 until 3) {
            var used = 0
            for (i in 0 until FLAKES) {
                if (i % 3 != group) continue
                val a = snowSeeds[i * 4]
                val b = snowSeeds[i * 4 + 1]
                val phase = snowSeeds[i * 4 + 2] * 6.28f
                val speed = SNOW_SPEED[group] * (0.8f + snowSeeds[i * 4 + 3] * 0.4f)
                snowPoints[used++] = 120f + a * 1320f + sin(t * 0.7f + phase) * 30f
                snowPoints[used++] = (b * RAIN_SPAN + t * speed) % RAIN_SPAN + 200f
            }
            flake.strokeWidth = SNOW_SIZE[group]
            flake.alpha = SNOW_ALPHA[group]
            canvas.drawPoints(snowPoints, 0, used / 2, flake)
        }
    }

    private fun drawFog(canvas: Canvas, band: Bitmap, t: Float) {
        val x = -((t * 14f) % HomeScene.WIDTH)
        rect.set(x, 230f, x + HomeScene.WIDTH, 560f)
        canvas.drawBitmap(band, null, rect, fogPaint)
        rect.offset(HomeScene.WIDTH, 0f)
        canvas.drawBitmap(band, null, rect, fogPaint)
    }

    /** 먼지는 유리에 붙어 있습니다. 닦을 때는 지나간 자리부터 맑아집니다. */
    private fun drawDirt(canvas: Canvas, now: Long) {
        val film = dustFilm ?: return
        val elapsed = now - wipeStart
        val wiping = wipeStart > 0L && elapsed < WIPE_MS
        val level = if (wiping) wipeFrom else dust
        if (level <= 0f) return
        dustPaint.alpha = (level * 255).toInt()
        canvas.save()
        if (wiping) {
            val edge = elapsed / WIPE_MS.toFloat() * (HomeScene.WIDTH + WIPE_BAND) - WIPE_BAND
            canvas.clipRect(edge + WIPE_BAND, 0f, HomeScene.WIDTH, HomeScene.HEIGHT)
        }
        rect.set(0f, 0f, HomeScene.WIDTH, HomeScene.HEIGHT)
        canvas.drawBitmap(film, null, rect, dustPaint)
        canvas.restore()
        if (wiping) drawSuds(canvas, elapsed)
    }

    /** 닦이는 자리를 따라 비눗방울이 떠오릅니다. 세차가 집에 보이는 순간입니다. */
    private fun drawSuds(canvas: Canvas, elapsed: Long) {
        val art = suds ?: return
        val progress = elapsed / WIPE_MS.toFloat()
        val edge = progress * (HomeScene.WIDTH + WIPE_BAND) - WIPE_BAND
        for (i in 0 until SUDS) {
            val rise = (progress * SUDS_LAPS + moteSeeds[i * 4 + 1]) % 1f
            val size = 36f + moteSeeds[i * 4 + 2] * 58f
            val x = edge + moteSeeds[i * 4] * WIPE_BAND * 1.4f + sin(rise * 6.28f) * 14f
            val y = HomeScene.WINDOW_BOTTOM - rise * 300f
            sudsPaint.alpha = ((1f - rise) * 235f).toInt()
            rect.set(x - size / 2f, y - size / 2f, x + size / 2f, y + size / 2f)
            canvas.drawBitmap(art, null, rect, sudsPaint)
        }
    }

    private fun drawMotes(canvas: Canvas, part: DayPart, t: Float) {
        val strength = MOTE_ALPHA[part.ordinal]
        for (i in 0 until MOTES) {
            val a = moteSeeds[i * 4]
            val b = moteSeeds[i * 4 + 1]
            val c = moteSeeds[i * 4 + 2]
            val x = (a * HomeScene.WIDTH + t * (5f + c * 8f) + sin(t * 0.3f + i) * 30f) % HomeScene.WIDTH
            val y = 800f - (b * 620f + t * (8f + c * 12f)) % 620f
            mote.alpha = (strength * ((sin(t * (0.5f + c) + i) + 1f) / 2f)).toInt()
            canvas.drawCircle(x, y, 2f + moteSeeds[i * 4 + 3] * 3f, mote)
        }
        mote.alpha = 255
    }

    private companion object {
        const val FADE_MS = 900L
        const val HAZE_LEFT = 100f
        const val HAZE_RIGHT = 1536f
        const val HAZE_BOTTOM = 500f
        const val MOVING_MS = 66L
        const val SNOW_MS = 110L
        const val FOG_MS = 300L
        const val NIGHT_MS = 190L
        const val CLEAR_MS = 800L
        const val WIPE_MS = 1_400L
        const val WIPE_BAND = 240f
        const val WIPE_MIN_DROP = 0.05f
        const val ROOF_LAMP_LEFT = 700f
        const val ROOF_LAMP_TOP = 196f
        const val ROOF_GLOW = 190f
        const val ROOF_GLOW_COLOR = 0x70FFDDA8
        const val WARM_GLOW_COLOR = 0x88FFB878.toInt()
        const val SUDS = 12
        const val SUDS_LAPS = 1.6f
        const val MOTES = 18
        const val GLINTS = 14
        const val RAIN_LINES = 60
        const val RAIN_SPAN = 360f
        const val DRIPS = 7
        const val FLAKES = 120

        /** 창밖 강가는 4분의 1로 읽습니다. 크게 늘여 그리면 멀리 있는 듯 흐려집니다. */
        const val WINDOW_SAMPLE = 4

        /** 창 띠의 위쪽에 오는 강가 그림의 y. 창 유리에 나무, 강둑, 물이 차례로 보입니다. */
        const val RIVER_BAND = 220f

        // 실내등 빛. 노을빛 보상은 더 붉고 멀리 닿아 마루의 정령들까지 덮습니다.
        const val LAMP_LIGHT = 0xFFD8A0
        const val WARM_LIGHT = 0xFFB884
        const val LAMP_REACH = 620f
        const val WARM_REACH = 860f
        val NIGHT_LAMP_STOPS = intArrayOf(0xCC000000.toInt(), 0x55000000, 0)
        val EVENING_LAMP_STOPS = intArrayOf(0x80000000.toInt(), 0x30000000, 0)
        const val MOTE = 0xFFFFF0CC.toInt()
        const val RAY = 0x40FFE6B0
        const val RAIN_LINE = 0x55FFFFFF
        const val DRIP = 0xFFEAF0F6.toInt()
        const val SNOW = 0xFFFDFDFB.toInt()
        const val FROST = 0xB8F4F7F8.toInt()

        val RAYS = arrayOf(floatArrayOf(420f, 150f), floatArrayOf(690f, 230f), floatArrayOf(1040f, 120f))
        val MOTE_ALPHA = intArrayOf(120, 60, 170, 70)
        val SNOW_SPEED = floatArrayOf(36f, 58f, 88f)
        val SNOW_SIZE = floatArrayOf(5f, 8f, 13f)
        val SNOW_ALPHA = intArrayOf(190, 230, 170)
    }
}
