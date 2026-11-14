package com.example.pleosapp.ui.spirit

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.example.pleosapp.R
import com.example.pleosapp.engine.Reward
import com.example.pleosapp.engine.Guest
import com.example.pleosapp.engine.SpiritPreset
import com.example.pleosapp.ui.recall.SeasonDrift
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlin.math.max

/**
 * 한 자리에 그릴 줄인 그림을 들고 있습니다([SpiritSprites.reduce]).
 * 그림이나 크기가 바뀔 때만 다시 만들어서 그릴 때마다 할당하지 않습니다.
 */
class ReducedSlot {
    private var source: Bitmap? = null
    private var height = -1
    private var result: Bitmap? = null

    fun of(art: Bitmap, area: Rect?, height: Int): Bitmap {
        val kept = result
        if (kept != null && art === source && height == this.height) return kept
        return SpiritSprites.reduce(art, area, height).also {
            source = art
            this.height = height
            result = it
        }
    }
}

/** 정령과 소품 그림. 그림 파일은 scripts/cut_home.py가 만들고, 작은 그림이라 한 번 읽어 둡니다. */
object SpiritSprites {
    /** 오래 안 쓴 그림부터 놓습니다. 습관 프레임까지 다 쥐고 있으면 메모리가 계속 늡니다. */
    private val cache = LinkedHashMap<Int, Bitmap>(64, 0.75f, true)
    private var held = 0L
    private const val HOLD_BYTES = 72L * 1024 * 1024

    /**
     * 시스템이 메모리를 달라고 하면 쥔 그림을 놓습니다. 화면에 있는 그림은 뷰가 쥐고 있어 지워지지 않습니다.
     * 화면이 가려지기만 했을 때는 놓지 않습니다. Android 14는 가릴 때마다 이 단계를 보내서, 놓으면 돌아올 때마다 메인 스레드가 그림을 다시 읽습니다.
     */
    private val trim = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) release()
        }

        override fun onLowMemory() = release()

        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }
    private var watching = false

    /** 지금까지 읽은 장수와 바이트. 검증 도구가 읽습니다. */
    var decodes = 0L
        private set
    var decodeBytes = 0L
        private set

    fun bitmap(context: Context, resource: Int): Bitmap = synchronized(cache) {
        if (!watching) {
            watching = true
            context.applicationContext.registerComponentCallbacks(trim)
        }
        cache[resource] ?: BitmapFactory.decodeResource(context.applicationContext.resources, resource).also {
            cache[resource] = it
            held += it.allocationByteCount
            decodes++
            decodeBytes += it.allocationByteCount
            val oldest = cache.entries.iterator()
            while (held > HOLD_BYTES && oldest.hasNext()) {
                held -= oldest.next().value.allocationByteCount
                oldest.remove()
            }
        }
    }

    private fun release() = synchronized(cache) {
        cache.clear()
        held = 0
        HeroArts.forget()
        FriendArts.forget()
        GuestArts.forget()
    }

    /** 캐시와 자세 묶음이 쥔 그림의 합. 같은 그림은 한 번만 셉니다. 검증 도구가 읽습니다. */
    fun residentBytes(): Long = synchronized(cache) {
        val all = Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())
        all += cache.values
        (HeroArts.kept + FriendArts.kept).forEach { art ->
            all += art.base
            art.blink?.let(all::add)
            all += art.frames
        }
        all.sumOf { it.allocationByteCount.toLong() }
    }

    private val paintedAreas = WeakHashMap<Bitmap, Rect>()

    /**
     * 그림에서 칠해진 테두리. 비버 그림은 머리 가운데에 맞춰 잘라 꼬리 반대쪽이 비어 있습니다.
     * 작은 칸에 넣을 때는 이 테두리만 맞춰 넣어야 정령이 작아 보이지 않습니다. 그림마다 한 번만 잽니다.
     */
    fun painted(art: Bitmap): Rect = synchronized(paintedAreas) {
        paintedAreas.getOrPut(art) {
            val row = IntArray(art.width)
            var left = art.width
            var right = -1
            var top = -1
            var bottom = -1
            for (y in 0 until art.height) {
                art.getPixels(row, 0, art.width, 0, y, art.width, 1)
                val first = row.indexOfFirst { it ushr 24 > PAINTED_ALPHA }
                if (first < 0) continue
                if (top < 0) top = y
                bottom = y
                left = minOf(left, first)
                right = maxOf(right, row.indexOfLast { it ushr 24 > PAINTED_ALPHA })
            }
            if (top < 0) Rect(0, 0, art.width, art.height) else Rect(left, top, right + 1, bottom + 1)
        }
    }

    private const val PAINTED_ALPHA = 32

    private val footprints = WeakHashMap<Bitmap, FloatArray>()

    /**
     * 바닥에 닿는 폭. 그림 맨 아래 띠에 칠해진 곳의 가운데와 폭을 그림 폭에 대한 비율로 돌려줍니다.
     * 그림 폭으로 그림자를 그리면 빈 여백까지 그늘이 져서 떠 보입니다. 그림마다 한 번만 잽니다.
     */
    fun footprint(art: Bitmap): FloatArray = synchronized(footprints) {
        footprints.getOrPut(art) {
            val top = (art.height * (1f - FOOT_BAND)).toInt()
            val rows = art.height - top
            val pixels = IntArray(art.width * rows)
            art.getPixels(pixels, 0, art.width, 0, top, art.width, rows)
            var left = art.width
            var right = -1
            pixels.forEachIndexed { i, pixel ->
                if (pixel ushr 24 > 128) {
                    val x = i % art.width
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
            if (right < left) floatArrayOf(0.5f, 0.74f) else floatArrayOf((left + right + 1) / 2f / art.width, (right + 1 - left).toFloat() / art.width)
        }
    }

    private const val FOOT_BAND = 0.06f

    /**
     * [height] 픽셀 높이로 그릴 그림을 두 배 안쪽까지 미리 줄입니다. [area]가 있으면 그 부분만 자릅니다.
     * 두 배 넘게 줄여 그리면 쌍선형 필터가 픽셀을 건너뛰어 가는 윤곽선이 점선처럼 끊깁니다.
     * 밉맵 힌트는 이 기기에서 효과가 없어서 반씩 줄여 평균을 냅니다.
     * 마지막 두 배 안쪽은 그릴 때 줄입니다. 여기서 크기까지 맞추면 두 번 거르게 되어 흐려집니다.
     */
    fun reduce(art: Bitmap, area: Rect?, height: Int): Bitmap {
        var current = if (area == null) art else Bitmap.createBitmap(art, area.left, area.top, area.width(), area.height())
        while (height > 0 && current.height >= height * 2) {
            current = Bitmap.createScaledBitmap(current, max(1, current.width / 2), current.height / 2, true)
        }
        return current
    }

    /** 앨범 칸처럼 작게 보일 큰 그림. 줄여서 읽고 보관하지 않습니다. */
    fun thumbnail(context: Context, resource: Int, sample: Int): Bitmap {
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeResource(context.resources, resource, options)
    }

    /** 이름으로 찾습니다. 아직 받지 못한 그림은 0입니다. */
    fun named(context: Context, name: String): Int =
        context.resources.getIdentifier(name, "drawable", context.packageName)

    /** 프레임 그림. `이름_1`, `이름_2`처럼 이어지는 그림을 찾습니다. 없으면 빈 목록입니다. */
    fun frames(context: Context, name: String): List<Bitmap> =
        generateSequence(1) { it + 1 }
            .map { named(context, "${name}_$it") }
            .takeWhile { it != 0 }
            .map { bitmap(context, it) }
            .toList()

    fun family(preset: SpiritPreset, awake: Boolean): Int = when (preset) {
        SpiritPreset.SOM -> if (awake) R.drawable.family_som_sit else R.drawable.family_som_sleep
        SpiritPreset.SAETEOL -> if (awake) R.drawable.family_saeteol_sit else R.drawable.family_saeteol_sleep
        SpiritPreset.YANGTTE -> if (awake) R.drawable.family_yangtte_sit else R.drawable.family_yangtte_sleep
        SpiritPreset.ANGAE -> if (awake) R.drawable.family_angae_sit else R.drawable.family_angae_sleep
    }
}

/** 구름이의 자세. 평소에는 앉아 있고, 순간마다 다른 그림을 잠깐 씁니다. */
enum class HeroPose(val file: String, val cycleMs: Long = 1_000L, val habit: Boolean = false) {
    SIT("sit"),
    STAND("stand"),
    CHEER("cheer", 700L),
    THANKS("thanks", 1_600L),
    TILT("tilt", 1_600L),
    POINT("point", 2_000L),
    STRETCH("stretch", 3_200L),
    SLEEP("sleep", 4_000L),
    WAVE("wave", 1_000L),
    LETTER("letter", 2_000L),
    HOLDJAR("holdjar", 2_600L),

    // 혼자 있을 때의 습관, 터치, 소품을 쓰는 동작입니다. 그림이 있을 때만 나옵니다.
    DOZE("doze", 3_400L, true),
    GAZE("gaze", 4_000L, true),
    SNEEZE("sneeze", 1_800L, true),
    CHASE("chase", 2_000L, true),
    BLANKET("blanket", 3_000L, true),
    ROLL("roll", 2_000L, true),
    DANGLE("dangle", 1_600L, true),
    TEA("tea", 3_000L, true),
    HUM("hum", 1_400L, true),
    PET("pet", 2_400L, true),
    PUFF("puff", 1_200L, true),
    HIDE("hide", 1_600L, true),
    WATER("water", 2_600L, true),
    LAMPREAD("lampread", 3_200L, true),
    MOBILE("mobile", 3_600L, true),
    TRAY("tray", 2_600L, true),
    SMELL("smell", 2_600L, true),
    BOOK("book", 2_600L, true),
    LOW("low", 2_600L, true),
}

/** 구름이와 작은 정령이 한 장에 함께 그려진 장면입니다. 구름이 자리에서 잠깐 재생합니다. */
enum class DuoScene(val file: String, val preset: SpiritPreset, val cycleMs: Long) {
    TUCK("duo_tuck", SpiritPreset.SAETEOL, 4_400L),
    LEAN("duo_lean", SpiritPreset.SOM, 4_400L),
    HUG("duo_hug", SpiritPreset.SAETEOL, 2_400L),
    GIFT("duo_gift", SpiritPreset.SOM, 3_200L),
}

object HeroArts {
    /** 관절 무게를 다시 계산하지 않도록 같은 그림은 같은 묶음을 씁니다. */
    private val made = HashMap<String, ActorArt>()
    val kept: Collection<ActorArt> get() = made.values

    fun forget() = made.clear()

    // 비버 그림(R-034 앉기, R-035 서기와 잠)을 자른 결과에서 잰 꼬리 자리입니다. 머리 가운데에 맞춰 잘라 꼬리는 왼쪽 아래입니다.
    private val SIT = Joints(floatArrayOf(0.11f, 0.72f))
    private val STAND = Joints(floatArrayOf(0.11f, 0.7f))
    private val ASLEEP = Joints(floatArrayOf(0.11f, 0.68f), asleep = true)

    // 나머지 자세와 둘이 함께 장면은 그림마다 꼬리 자리가 다릅니다. 엉뚱한 곳이 휘지 않게 꼬리를 그림 밖에 두고 숨만 쉽니다.
    private val STILL = Joints(floatArrayOf(0.5f, 2f))

    /** 입은 옷. 옷마다 앉은 그림이 있을 때만 옷으로 봅니다. 책 읽는 모습(hero_read)은 옷이 아닙니다. */
    private fun outfitOf(context: Context, form: Reward?): String? =
        form?.id?.takeIf { SpiritSprites.named(context, "hero_${it}_sit") != 0 }

    /** 홈에서 입혀 볼 수 있는 모습인지. 앉은 그림이 있어야 합니다. 그림이 아직 없는 옷은 성장 기록에도 두지 않습니다. */
    fun wearable(context: Context, form: Reward): Boolean =
        if (form == Reward.FORM_READ) SpiritSprites.named(context, "hero_read") != 0 else outfitOf(context, form) != null

    /**
     * 입은 옷을 벗지 않고 그 자세를 보여 줄 수 있는지. 옷을 안 입었으면 늘 true입니다.
     * 옷 그림이 없는 습관 몸짓을 하면 목도리가 사라졌다 나타나므로 건너뜁니다.
     */
    fun dressed(context: Context, pose: HeroPose, form: Reward?): Boolean {
        val outfit = outfitOf(context, form) ?: return true
        return SpiritSprites.named(context, "hero_${outfit}_${pose.file}") != 0
    }

    /** 둘이 함께 나오는 장면을 옷을 입은 채로 보여 줄 수 있는지. 지금 장면 그림은 모두 옷이 없습니다. */
    fun duoDressed(context: Context, scene: DuoScene, form: Reward?): Boolean {
        val outfit = outfitOf(context, form) ?: return true
        return SpiritSprites.named(context, "${scene.file}_$outfit") != 0
    }

    /**
     * 옷을 입었으면 그 옷의 같은 자세를 씁니다. 없으면 옷 입은 그림으로 대신해 옷이 사라지지 않게 합니다.
     * 옷을 받지 않았으면 가장 가까운 그림을 씁니다.
     */
    fun of(context: Context, pose: HeroPose, form: Reward?): ActorArt {
        val outfit = outfitOf(context, form)
        val names = buildList {
            if (form == Reward.FORM_READ && pose == HeroPose.SIT) add("hero_read")
            if (outfit != null) {
                add("hero_${outfit}_${pose.file}")
                if (pose == HeroPose.SIT) add("hero_$outfit")
                standIn(outfit, pose)?.let(::add)
            }
            add("hero_${pose.file}")
            add("hero_sit")
        }
        val name = names.first { SpiritSprites.named(context, it) != 0 }
        // 습관은 가끔 나오고 프레임이 많습니다. 쥐고 있지 않습니다.
        if (pose.habit) return build(context, name, pose)
        return made.getOrPut("$name/${pose.name}") { build(context, name, pose) }
    }

    /**
     * 옷 그림이 없는 자세를 대신할 옷 입은 그림. 반기기, 고마움, 기지개는 만세, 잠은 눈 감고 앉은 그림, 나머지는 앉은 그림입니다.
     * 편지와 유리병을 든 자세는 든 것이 그 순간의 뜻이라 대신하지 않습니다. 습관 몸짓은 대신하지 않고 건너뜁니다([dressed]).
     */
    private fun standIn(outfit: String, pose: HeroPose): String? = when {
        pose == HeroPose.LETTER || pose == HeroPose.HOLDJAR || pose.habit -> null
        pose == HeroPose.WAVE || pose == HeroPose.THANKS || pose == HeroPose.STRETCH -> "hero_${outfit}_cheer"
        pose == HeroPose.SLEEP -> "hero_${outfit}_sit_blink"
        else -> "hero_${outfit}_sit"
    }

    private fun build(context: Context, name: String, pose: HeroPose): ActorArt {
        val blink = SpiritSprites.named(context, "${name}_blink")
        val joints = when {
            pose == HeroPose.SLEEP -> ASLEEP
            name == "hero_read" -> STILL
            pose == HeroPose.SIT -> SIT
            pose == HeroPose.STAND -> STAND
            else -> STILL
        }
        return ActorArt(
            SpiritSprites.bitmap(context, SpiritSprites.named(context, name)),
            if (blink != 0) SpiritSprites.bitmap(context, blink) else null,
            joints,
            SpiritSprites.frames(context, name),
            pose.cycleMs,
        )
    }

    /** 습관 그림이 들어와 있는지. 없으면 앱이 그 습관을 건너뜁니다. */
    fun has(context: Context, pose: HeroPose): Boolean = SpiritSprites.named(context, "hero_${pose.file}") != 0

    fun hasDuo(context: Context, scene: DuoScene): Boolean = SpiritSprites.named(context, scene.file) != 0

    /** 둘이 함께 나오는 장면. 그림이 없으면 null입니다. */
    fun duo(context: Context, scene: DuoScene): ActorArt? {
        val art = SpiritSprites.named(context, scene.file)
        if (art == 0) return null
        return ActorArt(SpiritSprites.bitmap(context, art), null, STILL, SpiritSprites.frames(context, scene.file), scene.cycleMs)
    }
}

/** 작은 정령의 자세. 구름이처럼 키우지 않고, 순간마다 몸짓으로 말합니다. */
enum class FriendPose(val file: String, val cycleMs: Long = 1_000L) {
    SIT("sit"),
    BELT("belt", 1_600L),
    WAVE("wave", 1_000L),
    WELCOME("welcome", 1_200L),
    OFFER("offer", 1_600L),
    WORK("work", 2_000L),
    SLEEP("sleep", 4_000L),
    HABIT("habit", 2_400L),
}

object FriendArts {
    private val made = HashMap<String, ActorArt>()
    val kept: Collection<ActorArt> get() = made.values

    fun forget() = made.clear()

    // 아기 넷의 앉은 그림(R-037, R-049, R-047, R-048)을 자른 결과에서 잰 값입니다. 카피바라는 꼬리가 없어 그림 밖에 둡니다.
    private val BEAVER = Joints(floatArrayOf(0.08f, 0.76f))
    private val OTTER = Joints(floatArrayOf(0.17f, 0.79f))
    private val CAPYBARA = Joints(floatArrayOf(0.5f, 1.3f))
    private val REDPANDA = Joints(floatArrayOf(0.13f, 0.7f))
    private val ASLEEP = Joints(floatArrayOf(0.5f, 1.3f), asleep = true)

    fun of(context: Context, preset: SpiritPreset, pose: FriendPose): ActorArt = made.getOrPut("${preset.id}/${pose.file}") {
        val wanted = SpiritSprites.named(context, "family_${preset.id}_${pose.file}")
        val fallback = when (pose) {
            FriendPose.WELCOME, FriendPose.OFFER -> FriendPose.WAVE
            FriendPose.WORK, FriendPose.HABIT, FriendPose.BELT -> FriendPose.SIT
            else -> pose
        }
        val resource = if (wanted != 0) wanted else SpiritSprites.named(context, "family_${preset.id}_${fallback.file}")
        val joints = if (pose == FriendPose.SLEEP) ASLEEP else when (preset) {
            SpiritPreset.SAETEOL -> BEAVER
            SpiritPreset.SOM -> OTTER
            SpiritPreset.YANGTTE -> CAPYBARA
            SpiritPreset.ANGAE -> REDPANDA
        }
        ActorArt(SpiritSprites.bitmap(context, resource), null, joints, SpiritSprites.frames(context, "family_${preset.id}_${pose.file}"), pose.cycleMs)
    }
}

/**
 * 강가 손님 그림(`guest_{id}_{sit,visit,happy}`). 숨만 쉬고 귀와 꼬리는 굽히지 않습니다.
 * 자세 그림이 없으면 앉은 그림을 쓰고, 그것도 없으면 null입니다. 그림이 앱에 들어오기 전에는 손님을 그리지 않습니다.
 */
object GuestArts {
    private val made = HashMap<String, ActorArt>()
    private val STILL = Joints(floatArrayOf(0.5f, 2f))

    fun forget() = made.clear()

    fun of(context: Context, guest: Guest, pose: String): ActorArt? {
        val key = "${guest.id}/$pose"
        made[key]?.let { return it }
        val wanted = SpiritSprites.named(context, "guest_${guest.id}_$pose")
        val file = if (wanted != 0) pose else "sit"
        val resource = if (wanted != 0) wanted else SpiritSprites.named(context, "guest_${guest.id}_sit")
        if (resource == 0) return null
        // 눈 감은 그림이 있는 자세는 깜빡입니다. 지금은 앉은 자세만 있습니다(R-094).
        val blink = SpiritSprites.named(context, "guest_${guest.id}_${file}_blink")
        return ActorArt(
            SpiritSprites.bitmap(context, resource),
            if (blink != 0) SpiritSprites.bitmap(context, blink) else null,
            STILL,
            SpiritSprites.frames(context, "guest_${guest.id}_$file"),
        ).also { made[key] = it }
    }
}

/** 장면에 쓸 큰 그림을 고릅니다. */
object SceneArt {
    fun interior(context: Context, winter: Boolean): Int {
        val art = if (winter) SpiritSprites.named(context, "home_winter") else SeasonDrift.art(context, "home")
        return if (art != 0) art else R.drawable.home_interior
    }
}
