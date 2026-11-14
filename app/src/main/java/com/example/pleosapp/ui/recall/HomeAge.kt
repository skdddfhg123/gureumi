package com.example.pleosapp.ui.recall

import android.content.Context
import android.graphics.ColorFilter
import android.view.View
import com.example.pleosapp.R
import com.example.pleosapp.engine.DayPart
import com.example.pleosapp.engine.Growth
import com.example.pleosapp.engine.PetState
import com.example.pleosapp.engine.Reward
import com.example.pleosapp.engine.SceneState
import com.example.pleosapp.engine.TimeTogether
import com.example.pleosapp.ui.game.HomeScene
import com.example.pleosapp.ui.game.NookView
import com.example.pleosapp.ui.spirit.SpiritSprites

/**
 * 집이 나이 드는 값을 한곳에서 정합니다. 입구에서 벌지 않아도 날이 쌓이면 달라집니다.
 * 홈과 도착 화면이 같은 집을 쓰므로 둘 다 이 값을 봅니다.
 */
class HomeAge(private val context: Context, scene: NookView) {

    val photos = PhotoStringView(context).apply { attach(scene) }
    private val drift = SeasonDrift(context)
    private val prefs = RecallPrefs(context)

    private var days = 0
    private var stage = Growth.Stage.FLUFF
    private var shown: SceneState? = null
    private var view: Reward? = null
    private var filter: ColorFilter? = null

    /** 쿠션 위 크기. 어른이 1입니다. */
    val heroScale: Float get() = TimeTogether.heroScale(stage)

    /** 화분 그림이 오면 단계별 그림을 쓰고, 없으면 고사리가 커집니다. */
    val fernArt: Int get() = plantArt().takeIf { it != 0 } ?: R.drawable.prop_fern

    /** 화분이 자라도 바닥은 그대로 두고 위로만 커집니다. 바닥은 왼쪽 선반 윗면 위입니다. */
    val fernSpot: HomeScene.Spot
        get() {
            if (plantArt() != 0) return PlantPot.SPOT
            return HomeScene.Spot(HomeScene.FERN.x, HomeScene.FERN.bottom, HomeScene.FERN.height * TimeTogether.fernScale(days))
        }

    init {
        SeasonDrift.previewMonth = prefs.previewMonth
        scene.outside = drift::draw
        scene.outsideMs = { drift.redrawMs }
    }

    private fun plantArt(): Int = SpiritSprites.named(context, "plant_${TimeTogether.plantStep(days)}")

    /** 원장을 읽는 동안에는 마지막으로 본 값으로 그립니다. 구름이가 작아졌다 커지지 않습니다. */
    fun show(pet: PetState) {
        prefs.rememberAge(pet)
        days = maxOf(pet.together.days, prefs.lastDays)
        stage = maxOf(pet.stage, prefs.lastStage)
        photos.visibility = if (prefs.off) View.GONE else View.VISIBLE
        photos.show(days, TimeTogether.photos(days))
        shown?.let { drift.show(it, view, days, filter) }
    }

    /** 시간과 날씨, 창에 든 풍경이 바뀌면 사진 줄과 창밖도 따라갑니다. */
    fun light(state: SceneState, ambient: ColorFilter?, window: Reward?) {
        shown = state
        view = window
        filter = ambient
        photos.setAmbient(ambient, state.part == DayPart.NIGHT)
        drift.show(state, window, days, ambient)
    }
}

/**
 * 화분 그림(plant_1~5)의 자리. 다섯 장은 캔버스(235×274)가 같고, 화분 바닥은 위에서 218입니다.
 * 그 아래는 5단계 덩굴이 선반 앞면(y 545~575)으로 내려올 자리라, 그림 바닥과 화분 바닥이 다릅니다.
 * 새 배경 v2의 창 아래 긴 선반에 고사리([HomeScene.FERN])와 같은 자리로 섭니다.
 */
internal object PlantPot {
    val SPOT = HomeScene.Spot(295f, 576f, 191f)
    private const val CANVAS = 274f
    private const val BASE = 218f

    /** 화분 바닥이 닿는 높이. 선반 윗면 위여야 합니다. 지금 537로 고사리 바닥과 같습니다. */
    val FOOT: Float = SPOT.bottom - (CANVAS - BASE) / CANVAS * SPOT.height
}
