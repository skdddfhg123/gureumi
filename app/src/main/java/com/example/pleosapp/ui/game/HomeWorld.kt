package com.example.pleosapp.ui.game

import android.content.Context
import android.view.View
import android.view.Window
import com.example.pleosapp.engine.Badge
import com.example.pleosapp.engine.CareLook
import com.example.pleosapp.engine.DayPart
import com.example.pleosapp.engine.FamilySpirit
import com.example.pleosapp.engine.FamilyState
import com.example.pleosapp.engine.FriendSkill
import com.example.pleosapp.engine.GiftState
import com.example.pleosapp.engine.Mood
import com.example.pleosapp.engine.PetState
import com.example.pleosapp.engine.Reward
import com.example.pleosapp.engine.RewardKind
import com.example.pleosapp.engine.SceneState
import com.example.pleosapp.engine.Season
import com.example.pleosapp.engine.SpiritPreset
import com.example.pleosapp.engine.TimeTogether
import com.example.pleosapp.ui.recall.HomeAge
import com.example.pleosapp.ui.recall.PhotoStringView
import com.example.pleosapp.ui.recall.SeasonDrift
import com.example.pleosapp.ui.spirit.ActorArt
import com.example.pleosapp.ui.spirit.DuoScene
import com.example.pleosapp.ui.spirit.FriendArts
import com.example.pleosapp.ui.spirit.FriendPose
import com.example.pleosapp.ui.spirit.HeroArts
import com.example.pleosapp.ui.spirit.SpiritSprites
import com.example.pleosapp.ui.spirit.HeroPose
import com.example.pleosapp.ui.spirit.SpriteSpiritView
import java.util.Random
import kotlin.math.max
import kotlin.math.min

/**
 * 홈과 도착 화면이 함께 쓰는 정령의 집: 창밖, 소품, 유리병, 구름이, 작은 정령.
 * 화면마다 쪽지와 입구만 다르고 집은 같습니다. 움직임도 여기서 한 박자로 묶습니다.
 */
class HomeWorld(private val context: Context) {

    val scene = NookView(context)
    private val decor = DecorView(context).apply { attach(scene) }

    /** 바닥에 놓인 소품. 정령들과 함께 발 자리로 앞뒤를 정합니다. */
    private val floorDecor = DecorView(context, floor = true).apply { attach(scene) }

    /** 둥지 앞 테두리. 잠든 아기 아래쪽을 덮습니다. 정령들과 함께 발 자리로 앞뒤를 정합니다. */
    private val nestFront = NestFrontView(context).apply { attach(scene) }

    /** 날이 쌓이며 달라지는 것: 사진 줄, 창밖 계절, 구름이 크기, 화분. */
    val age = HomeAge(context, scene)
    val jar = JarView(context).apply { contentDescription = "깃털 유리병" }
    val hero = SpriteSpiritView(context).apply { contentDescription = "구름이" }
    val friends = List(MAX_FRIENDS) { SpriteSpiritView(context).apply { visibility = View.GONE } }

    /** 둘이 함께 나오는 장면을 그리는 자리. 구름이 자리에 겹칩니다. */
    private val duo = SpriteSpiritView(context).apply { visibility = View.GONE }
    val ticker = MotionTicker(BUSY_FPS, IDLE_FPS)

    /** 무대 맨 뒤에 깔리는 겹. 글자와 입구는 이 위에 놓습니다. */
    val layers: List<View> = listOf<View>(scene, decor, age.photos, jar) + friends + hero + duo + nestFront + floorDecor

    /** 지금 무대에 있는 작은 정령. 친구 뷰와 같은 순서입니다. */
    var cast: List<FamilySpirit> = emptyList()
        private set

    private var stage: StageLayout? = null
    private var form: Reward? = null
    private var shown: SceneState? = null
    private var season: Season? = null
    private var winter = false
    private var warmLamp = false
    private var feathered = false
    private var fluttering = false
    private var ambient = 0xFFFFFF
    private val spots = arrayOfNulls<HomeScene.Spot>(MAX_FRIENDS)
    private val random = Random()
    private var babyTurn = 0
    private var scale = 1f
    private var narrow = false
    private var tight = false
    private var arrival = false
    private var owned: Set<Reward> = emptySet()
    private var sneezy = false
    private var hungry = false
    private var calm = true

    /** 구름이가 닮은 운전 습관의 동작입니다. 없으면 null입니다. */
    private var likeness: Reward? = null
    private var skill = FriendSkill.BASIC
    private var nextHabitAt = 0f

    val night: Boolean get() = shown?.part == DayPart.NIGHT

    init {
        ticker.add(jar)
        ticker.addSmooth(hero, duo, *friends.toTypedArray())
        // 둥지 테두리는 실내판에서 잘라 낸 그림이라 실내판이 바뀔 때만 다시 그립니다.
        ticker.addEvery({ scene.redrawMs() }, scene, nestFront)
        // 바닥 소품은 움직이지 않습니다. 창 전체 크기라 주기로 다시 그리면 화면 전체를 새로 칠합니다.
        ticker.addEvery({ PROP_MS }, decor, age.photos)
        ticker.busy = { hero.quick || jar.busy || duo.quick || friends.any { it.quick } }
        // 매 화면 주기마다 묻습니다. 목록을 새로 만들지 않습니다.
        ticker.lively = { hero.lively || (duo.visibility == View.VISIBLE && duo.lively) || friends.any { it.visibility == View.VISIBLE && it.lively } }
        ticker.onTick(::routine)
    }

    /** 창밖 그림을 읽는 동안 무대를 감춰 두었다가 한 번에 보여 줍니다. */
    fun revealWhenReady(stageView: View, window: Window, onReady: () -> Unit = {}) {
        stageView.alpha = 0f
        scene.onFirstFrame = {
            stageView.animate().alpha(1f).setDuration(REVEAL_MS).withEndAction { ScenePreview.clear(window) }.start()
            onReady()
        }
    }

    /** 창을 가득 채우는 가로 배치. */
    fun placeWide(stage: StageLayout, w: Int, h: Int) = place(stage, w, h, h, HomeScene.WIDTH / 2f, narrow = false)

    /** 세로로 좁은 창: 위쪽 [sceneHeight]에 구름이와 친구가 보이게 자릅니다. */
    fun placeNarrow(stage: StageLayout, w: Int, h: Int, sceneHeight: Int) {
        // 구름이의 왼쪽 끝부터 보이게 맞춥니다. 창이 좁아도 주인공이 잘리지 않습니다.
        val shown = w / max(w / HomeScene.WIDTH, sceneHeight / HomeScene.HEIGHT)
        tight = shown < TIGHT_SHOWN
        place(stage, w, h, sceneHeight, NARROW_LEFT + shown / 2f, narrow = true)
    }

    private fun place(stage: StageLayout, w: Int, h: Int, sceneHeight: Int, focusX: Float, narrow: Boolean) {
        this.stage = stage
        this.narrow = narrow
        scene.fit(w, sceneHeight, focusX)
        scale = scene.sceneScale
        stage.place(scene, 0, 0, w, sceneHeight)
        stage.place(decor, 0, 0, w, sceneHeight)
        stage.place(floorDecor, 0, 0, w, sceneHeight)
        stage.place(nestFront, 0, 0, w, sceneHeight)
        // 크기가 같아도 장면 배율이 바뀌었을 수 있습니다. 주기로 다시 그리지 않는 층이라 여기서 새로 그립니다.
        floorDecor.invalidate()
        nestFront.invalidate()
        stage.place(age.photos, 0, 0, w, min(sceneHeight, scene.viewY(PhotoStringView.WALL_BOTTOM).toInt()))
        placeJar(stage, w, sceneHeight, focusX)
        placeActor(stage, hero, HomeScene.HERO, scale * age.heroScale)
        placeActor(stage, duo, HomeScene.HERO, scale * age.heroScale)
        // 담요 앞줄에 앉은 친구가 구름이나 뒷줄 친구 뒤로 숨지 않게 발 자리로 앞뒤를 정합니다. 바닥 소품과 둥지 테두리도 함께 정합니다.
        stage.sortByFeet(friends + hero + duo + nestFront + floorDecor)
        spots.fill(null)
        cast.forEachIndexed { index, spirit -> moveFriend(index, spirit) }
    }

    private fun placeJar(stage: StageLayout, w: Int, sceneHeight: Int, focusX: Float) {
        val spot = HomeScene.JAR
        val width = (spot.height * scale * JAR_ASPECT).toInt()
        val height = (spot.height * scale * 1.5f).toInt()
        if (narrow) {
            // 좁은 창에서는 선반이 잘리므로 창턱 오른쪽에 둡니다. 담요 위 친구들과 겹치지 않습니다.
            stage.place(jar, w - width - (w * 0.02f).toInt(), (scene.viewY(NARROW_SILL) - height).toInt(), width, height)
        } else {
            stage.place(jar, (scene.viewX(spot.x) - width / 2f).toInt(), (scene.viewY(spot.bottom) - height).toInt(), width, height)
        }
    }

    private fun placeActor(stage: StageLayout, actor: SpriteSpiritView, spot: HomeScene.Spot, unit: Float) {
        val width = (spot.height * scale * ACTOR_ROOM).toInt()
        val height = (spot.height * scale * ACTOR_ROOM).toInt()
        actor.unit = unit
        stage.place(actor, (scene.viewX(spot.x) - width / 2f).toInt(), (scene.viewY(spot.bottom) - height).toInt(), width, height)
    }

    /**
     * 장면이 바뀌었을 때만 true입니다. 소품과 정령도 그 빛을 입습니다.
     * 창밖은 늘 지금 장소인 강가입니다. 고른 창밖 풍경은 쓰지 않고, 겨울 둥지만 방을 바꿉니다.
     */
    fun applyScene(state: SceneState, keep: GiftState): Boolean {
        val pickedWinter = keep.scene == Reward.SCENE_WINTER && TimeTogether.fitsWindow(Reward.SCENE_WINTER, state)
        val warm = Reward.DECOR_LAMP in keep.owned
        val month = SeasonDrift.season()
        val feathers = Reward.DECOR_FEATHERBED in keep.owned
        val butterflies = Reward.SCENE_BUTTERFLY in keep.owned
        if (state == shown && pickedWinter == winter && warm == warmLamp && month == season && feathers == feathered && butterflies == fluttering) return false
        shown = state
        winter = pickedWinter
        warmLamp = warm
        season = month
        feathered = feathers
        fluttering = butterflies
        scene.show(NookLook(state, pickedWinter, warm, month, feathers, butterflies))
        ambient = NookLight.ambient(state)
        val light = if (ambient == 0xFFFFFF) null else ambient
        hero.setAmbient(light)
        friends.forEach { it.setAmbient(light) }
        duo.setAmbient(light)
        val filter = ambientFilter(ambient)
        decor.setAmbient(filter)
        floorDecor.setAmbient(filter)
        jar.setAmbient(filter)
        age.light(state, filter, null)
        return true
    }

    /** 구름이를 지금 상태로 입힙니다. 유리병 내용은 화면마다 따로 다룹니다. */
    fun dress(pet: PetState, keep: GiftState, look: CareLook, motions: List<Reward>) {
        form = keep.form?.takeIf { it.kind == RewardKind.FORM }
        owned = keep.owned
        sneezy = look.sneezy
        hungry = look.hungry
        likeness = Badge.likeness(pet)?.reward
        skill = keep.friendSkill
        calm = pet.mood == Mood.HAPPY
        val rest = when {
            pet.mood == Mood.SICK -> HeroPose.POINT
            dozing -> HeroPose.SLEEP
            else -> HeroPose.SIT
        }
        hero.setArt(HeroArts.of(context, rest, form))
        hero.setMood(pet.mood)
        hero.setCareLook(look)
        hero.setMotions(motions)
        age.show(pet)
        hero.unit = scale * age.heroScale
        duo.unit = hero.unit
        decor.setFern(age.fernArt, age.fernSpot)
        scene.setDust(look.dust)
        jar.setLit(night && !look.dimLight)
        jar.setDim(look.dimLight)
    }

    /** 집 안 소품을 지금 상태로 그립니다. 선반 층과 바닥 층이 같은 상태를 봅니다. */
    fun showDecor(gifts: GiftState, party: Boolean = false) {
        decor.show(gifts, party)
        floorDecor.show(gifts, party)
    }

    /** 밤에 가만히 두면 구름이도 잠듭니다. 건드리면 깨어납니다. 다음 [dress]부터 적용됩니다. */
    var dozing = false

    /**
     * 아기는 함께 탄 사람이 있으면 깨어 자기 자리에 앉고, 아니면 둥지에서 잡니다.
     * 맡은 쪽지가 있는 아기는 깨어서 쪽지를 들고 있습니다. 하트 우편함 대신입니다.
     * 깬 자리와 잠자리는 아기마다 정해져 있습니다. 갑자기 다른 데서 나타나지 않습니다(장면 규칙 2-9).
     */
    fun dressFamily(family: FamilyState, awake: Set<SpiritPreset>) {
        cast = family.spirits.take(MAX_FRIENDS)
        aboard = awake
        noteHolder = family.pending?.preset
        friends.forEachIndexed { index, actor ->
            val spirit = cast.getOrNull(index)
            actor.visibility = if (spirit == null) View.GONE else View.VISIBLE
            if (spirit != null) {
                actor.setArt(FriendArts.of(context, spirit.preset, restingPose(spirit)))
                moveFriend(index, spirit)
            }
        }
    }

    /** 쪽지를 들고 있는 아기. 없으면 null입니다. */
    var noteHolder: SpiritPreset? = null
        private set

    /** 그 아기가 지금 깨어 있는지. 깨어 있는 아기를 누르면 가족 화면이 열립니다. */
    fun awake(spirit: FamilySpirit): Boolean = spirit.preset in aboard || spirit.preset == noteHolder

    /**
     * 도착 화면. 함께 탄 아기는 깨어 일기장에 가리지 않게 구름이 곁에 모이고, 나머지는 홈처럼 둥지에서 잡니다.
     * 홈에서 자던 아기가 도착 화면에서 사라지면 장면 규칙 2-9(갑자기 나타나거나 사라지지 않음)에 어긋납니다.
     * 함께 탄 아기를 앞에 세워 도착 자리 순서가 그대로입니다.
     */
    fun dressArrival(family: FamilyState, companions: List<FamilySpirit>) {
        arrival = true
        age.photos.showsTag = false
        val riders = companions.map { it.preset }.toSet()
        cast = (companions + family.spirits.filter { it.preset !in riders }).take(MAX_FRIENDS)
        aboard = riders
        noteHolder = null
        friends.forEachIndexed { index, actor ->
            val spirit = cast.getOrNull(index)
            actor.visibility = if (spirit == null) View.GONE else View.VISIBLE
            if (spirit != null) {
                actor.setArt(FriendArts.of(context, spirit.preset, if (spirit.preset in riders) FriendPose.SIT else FriendPose.SLEEP))
                moveFriend(index, spirit)
            }
        }
    }

    /** 쉬는 자세. 쪽지를 맡은 아기는 쪽지를 내밀고, 함께 탄 아기는 앉고, 나머지는 잡니다. */
    private fun restingPose(spirit: FamilySpirit): FriendPose = when {
        spirit.preset == noteHolder -> letterPose(spirit.preset)
        spirit.preset in aboard -> FriendPose.SIT
        else -> FriendPose.SLEEP
    }

    /** 쪽지를 든 자세. 도토리는 편지를 안은 그림이 있고, 나머지는 건네는 몸짓입니다. */
    private fun letterPose(preset: SpiritPreset): FriendPose = if (preset == SpiritPreset.SOM) FriendPose.HABIT else FriendPose.OFFER

    /** 함께 탄 만큼 할 줄 아는 일이 늘어납니다. 몸은 자라지 않습니다. */
    private val chores: List<FriendPose>
        get() = when (skill) {
            FriendSkill.BASIC -> emptyList()
            FriendSkill.HABIT -> listOf(FriendPose.HABIT)
            FriendSkill.WORK, FriendSkill.DUO -> CHORES
        }

    /** 함께 탄 날이 많이 쌓이면 구름이와 한 장면에 나옵니다. */
    val friendsJoinIn: Boolean get() = skill == FriendSkill.DUO

    /** 깨어 있는 아기 하나가 자기 자리에서 자기 일을 한 번 합니다. 할 일이 없거나 모두 자면 false입니다. */
    private fun babyChore(): Boolean {
        if (arrival || narrow || stage == null) return false
        val awake = cast.indices.filter { cast[it].preset in aboard && cast[it].preset != noteHolder }
        val chore = chores.randomOrNull() ?: return false
        if (awake.isEmpty()) return false
        val index = awake[babyTurn++ % awake.size]
        friends[index].pose(FriendArts.of(context, cast[index].preset, chore), CHORE_MS)
        return true
    }

    /** 지금 함께 탄 아기. 자리가 아니라 아기로 셉니다. */
    private var aboard: Set<SpiritPreset> = emptySet()

    /**
     * 아기 자리. 좁은 창과 도착 화면은 모여 앉고, 홈에서는 깨어 있으면 아기마다 정해진 자리, 자면 둥지입니다.
     * 이미 누가 앉은 자리는 비운 자리로 옮깁니다.
     */
    private fun moveFriend(index: Int, spirit: FamilySpirit) {
        val current = stage ?: return
        val spot = when {
            narrow -> (if (tight) HomeScene.TIGHT else HomeScene.NARROW)[index]
            !awake(spirit) -> bedOf(index)
            arrival -> HomeScene.ARRIVAL[index]
            else -> homeOf(index, spirit.preset)
        }
        if (spots[index] === spot) return
        spots[index] = spot
        placeActor(current, friends[index], spot, scale * spot.height / FRIEND_HEIGHT)
        current.requestLayout()
    }

    /** 잠든 아기의 둥지 자리. 들인 순서로 앞 가운데부터 눕고, 뒤에 눕는 아기가 위로 겹칩니다. */
    private fun bedOf(index: Int): HomeScene.Spot {
        val before = cast.take(index).count { !awake(it) }
        return HomeScene.NEST_BEDS[before.coerceAtMost(HomeScene.NEST_BEDS.lastIndex)]
    }

    private fun homeOf(index: Int, preset: SpiritPreset): HomeScene.Spot {
        val own = when (preset) {
            SpiritPreset.SAETEOL -> HomeScene.SPROUT
            SpiritPreset.ANGAE -> HomeScene.LILAC
            SpiritPreset.SOM -> HomeScene.ACORN
            SpiritPreset.YANGTTE -> HomeScene.FORSYTHIA
        }
        val taken = spots.filterIndexed { other, spot -> other != index && spot != null }
        return if (own !in taken) own else HomeScene.BABIES.first { it !in taken }
    }

    /** 그 아기의 무대 위 정령을 찾습니다. 자리는 트립마다 바뀌어 아기로 찾습니다. */
    fun friendOf(preset: SpiritPreset): Pair<SpriteSpiritView, FamilySpirit>? {
        val index = cast.indexOfFirst { it.preset == preset }
        return if (index < 0) null else friends[index] to cast[index]
    }

    fun friendPose(preset: SpiritPreset, pose: FriendPose, durationMs: Long) {
        val (actor, spirit) = friendOf(preset) ?: return
        actor.pose(FriendArts.of(context, spirit.preset, pose), durationMs)
    }

    fun heroPose(pose: HeroPose, durationMs: Long) {
        // 옷 그림이 없는 갸웃은 옷을 입은 채 몸 전체만 기울입니다.
        if (pose == HeroPose.TILT && !HeroArts.dressed(context, pose, form)) {
            hero.play(Reward.MOTION_TILT)
            return
        }
        hero.pose(HeroArts.of(context, pose, form), durationMs)
    }

    /** 그림은 있는데 입은 옷의 그림이 없어 몸짓을 건너뛰는지. 그때는 하트처럼 옷을 벗지 않는 반응으로 대신합니다. */
    fun outfitHolds(pose: HeroPose): Boolean = HeroArts.has(context, pose) && !HeroArts.dressed(context, pose, form)

    /**
     * 습관을 한 번 보여 줍니다. 그림이 없거나, 입은 옷의 그림이 없으면 하지 않고 false입니다.
     * 프레임이 여러 장이라 뒤에서 읽고 시작합니다.
     */
    fun heroHabit(pose: HeroPose): Boolean {
        if (!HeroArts.has(context, pose) || !HeroArts.dressed(context, pose, form)) return false
        val outfit = form
        Thread {
            val art = HeroArts.of(context, pose, outfit)
            hero.post { hero.pose(art, pose.cycleMs * HABIT_LOOPS) }
        }.start()
        return true
    }

    /**
     * 혼자 두면 정령들도 자기 일을 합니다. 큰 몸짓은 한 번에 하나만, 20~40초에 한 번입니다(장면 규칙 2-9).
     * 깨어 있는 아기가 있으면 구름이와 차례를 나눕니다. 구름이는 시간대와 집에 있는 소품에 맞는 것만 고릅니다.
     */
    private fun routine(seconds: Float) {
        if (nextHabitAt == 0f) nextHabitAt = seconds + HABIT_MIN_SEC
        if (seconds < nextHabitAt && nextHabitAt - seconds < HABIT_MAX_SEC) return
        nextHabitAt = seconds + HABIT_MIN_SEC + random.nextFloat() * (HABIT_MAX_SEC - HABIT_MIN_SEC)
        val state = shown ?: return
        if (arrival || stage == null || hero.busy || duo.visibility == View.VISIBLE || friends.any { it.busy }) return
        if (random.nextBoolean() && babyChore()) return
        if (dozing || !calm) return
        // 습관 차례의 일부는 운전자를 닮은 버릇이 가져갑니다.
        if (state.part != DayPart.NIGHT && random.nextFloat() < LIKENESS_SHARE) {
            likeness?.let {
                hero.play(it)
                return
            }
        }
        val props = PROP_HABITS.filterKeys { it in owned }.values.filter { state.part != DayPart.NIGHT || it in NIGHT_PROPS }
        val body = listOfNotNull(HeroPose.SNEEZE.takeIf { sneezy }, HeroPose.LOW.takeIf { hungry })
        val choices = DAY_HABITS.getValue(state.part) + props + body
        choices.filter { HeroArts.has(context, it) && HeroArts.dressed(context, it, form) }.randomOrNull()?.let(::heroHabit)
    }

    /** 구름이와 작은 정령을 잠깐 한 장면으로 모읍니다. 그 친구가 없거나 그림이 없으면 false입니다. 큰 그림이라 뒤에서 읽습니다. */
    fun duoScene(scene: DuoScene, durationMs: Long = scene.cycleMs * HABIT_LOOPS): Boolean {
        val friend = friends.getOrNull(cast.indexOfFirst { it.preset == scene.preset }) ?: return false
        // 장면 그림에는 옷이 없습니다. 옷을 입었으면 구름이 옷이 사라져 보이므로 건너뜁니다.
        if (!HeroArts.hasDuo(context, scene) || !HeroArts.duoDressed(context, scene, form) || duo.visibility == View.VISIBLE) return false
        Thread {
            HeroArts.duo(context, scene)?.let { art -> duo.post { playDuo(art, friend, durationMs) } }
        }.start()
        return true
    }

    private fun playDuo(art: ActorArt, friend: SpriteSpiritView, durationMs: Long) {
        if (duo.visibility == View.VISIBLE) return
        duo.setArt(art)
        duo.setAmbient(if (ambient == 0xFFFFFF) null else ambient)
        duo.alpha = 0f
        duo.visibility = View.VISIBLE
        duo.animate().alpha(1f).setDuration(DUO_FADE_MS).start()
        hero.animate().alpha(0f).setDuration(DUO_FADE_MS).start()
        friend.animate().alpha(0f).setDuration(DUO_FADE_MS).start()
        duo.postDelayed({
            duo.animate().alpha(0f).setDuration(DUO_FADE_MS).withEndAction { duo.visibility = View.GONE }.start()
            hero.animate().alpha(1f).setDuration(DUO_FADE_MS).start()
            friend.animate().alpha(1f).setDuration(DUO_FADE_MS).start()
        }, durationMs)
    }

    fun paperFilter() = if (night) GameStyle.PAPER_DIM else null

    /** 창 크기가 바뀌면 다음 장면을 새로 입힙니다. */
    fun forgetScene() {
        shown = null
    }

    fun jarWidthFor(heightPx: Int): Int = min(heightPx, (heightPx * JAR_ASPECT).toInt())

    companion object {
        /** 홈이 그리는 소품 보상. 스탠드는 밤의 따뜻한 빛으로 보입니다. 새 소품을 홈에 놓으면 여기에 더합니다. */
        val SHOWN_DECOR = setOf(Reward.DECOR_FERN, Reward.DECOR_LAMP, Reward.DECOR_MOBILE, Reward.DECOR_TRAY, Reward.DECOR_VASE)

        /** 소품 보상의 그림 이름. 성장 기록과 선물 열기도 홈에 놓인 것과 같은 그림을 씁니다. 모빌은 천장에 매단 그림입니다(R-118). */
        fun propArt(reward: Reward): String = when (reward) {
            Reward.DECOR_MOBILE -> "prop_mobile_hang_1"
            // 깃털 둥지는 따로 놓는 물건이 아니라 둥지에 깐 깃털입니다. 둥지 자리를 자른 그림을 씁니다.
            Reward.DECOR_FEATHERBED -> "home_autumn_featherbed"
            else -> "prop_${reward.id}"
        }

        /**
         * 성장 기록에 둘 보상인지. 지금 홈에서 볼 수 있는 것만 둡니다.
         * 그림은 통과했지만 아직 놓지 않은 들꽃 컵, 깃털 둥지, 멜빵바지와 창밖 풍경은 홈에 놓으면 다시 보입니다.
         */
        fun showsAtHome(context: Context, reward: Reward): Boolean = when (reward.kind) {
            RewardKind.MOTION -> true
            RewardKind.FORM -> HeroArts.wearable(context, reward)
            RewardKind.DECOR -> reward in SHOWN_DECOR ||
                (reward == Reward.DECOR_FEATHERBED && SpiritSprites.named(context, "home_autumn_featherbed") != 0)
            // 창밖은 늘 강가입니다. 고르는 창밖 풍경은 없고, 강가에 늘 있는 나비와 돛배만 둡니다.
            RewardKind.SCENE -> when (reward) {
                Reward.SCENE_BUTTERFLY -> SpiritSprites.named(context, "fly_butterfly_yellow_1") != 0
                Reward.SCENE_BOAT -> SpiritSprites.named(context, "river_boat_1") != 0
                else -> false
            }
        }

        const val MAX_FRIENDS = 4
        const val POSE_MS = 3_200L

        private const val HABIT_MIN_SEC = 20f
        private const val HABIT_MAX_SEC = 40f
        private const val HABIT_LOOPS = 2
        private const val CHORE_MS = 4_800L
        private const val LIKENESS_SHARE = 0.3f
        private const val DUO_FADE_MS = 320L

        /** 밤에는 실내등 아래서 수첩을 폅니다(컨셉 점검 4-1의 46). */
        private val DAY_HABITS = mapOf(
            DayPart.MORNING to listOf(HeroPose.TEA, HeroPose.HUM, HeroPose.DANGLE, HeroPose.CHASE),
            DayPart.DAY to listOf(HeroPose.GAZE, HeroPose.HUM, HeroPose.DANGLE, HeroPose.ROLL, HeroPose.CHASE),
            DayPart.EVENING to listOf(HeroPose.TEA, HeroPose.GAZE, HeroPose.HUM, HeroPose.DANGLE),
            DayPart.NIGHT to listOf(HeroPose.DOZE, HeroPose.BLANKET, HeroPose.LAMPREAD),
        )

        /**
         * 집에 있는 소품을 쓰는 몸짓. 뺀 소품(책장, 꽃병)의 몸짓은 나오지 않습니다.
         * 모빌 몸짓은 머리 위에 모빌이 따로 그려진 그림이라 뺐습니다. 모빌은 11/5부터 오른쪽 손잡이에 매달려 둘로 보입니다.
         */
        private val PROP_HABITS = mapOf(
            Reward.DECOR_FERN to HeroPose.WATER,
            Reward.DECOR_TRAY to HeroPose.TRAY,
        )
        private val NIGHT_PROPS = emptyList<HeroPose>()

        /** 아기의 일. 새싹은 돌, 도토리는 편지, 개나리는 잎, 라일락은 사진을 듭니다. */
        private val CHORES = listOf(FriendPose.WORK, FriendPose.HABIT)

        /**
         * 몸짓이 있을 때와 숨만 쉴 때의 박자. 몸짓까지 더한 홈 평균을 초당 10장 안에 둡니다. 예산은 docs/verify/verification-system.md에 있습니다.
         * 60 Hz 화면의 9는 8.6장입니다. 눈 깜빡임 0.14초가 프레임 사이로 빠지지 않는 하한은 7.5장입니다.
         */
        private const val BUSY_FPS = 15
        private const val IDLE_FPS = 9
        private const val PROP_MS = 160L
        private const val REVEAL_MS = 280L
        private const val JAR_ASPECT = 341f / 414f
        private const val ACTOR_ROOM = 1.5f
        private const val FRIEND_HEIGHT = 260f
        private const val NARROW_LEFT = 290f
        private const val TIGHT_SHOWN = 640f
        private const val NARROW_SILL = 590f
    }
}
