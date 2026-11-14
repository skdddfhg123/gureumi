package com.example.pleosapp.ui.recall

import com.example.pleosapp.engine.TimeTogether
import com.example.pleosapp.ui.game.HomeScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlantPotTest {

    @Test
    fun `화분 그림은 단계가 올라도 바닥이 선반 윗면 위에 선다`() {
        val foot = HomeScene.Spot(PlantPot.SPOT.x, PlantPot.FOOT, 1f)
        assertTrue("화분 바닥 ${PlantPot.FOOT}", HomeScene.SHELF.holds(foot))
    }

    @Test
    fun `화분 그림과 고사리는 같은 자리에 선다`() {
        // 화분 그림이 오기 전후로 화분이 옆으로 옮겨 가거나 뜨지 않습니다.
        assertEquals(HomeScene.FERN.x, PlantPot.SPOT.x, 0.5f)
        assertEquals(HomeScene.FERN.bottom, PlantPot.FOOT, 1f)
    }

    @Test
    fun `화분 그림이 없을 때의 고사리도 바닥은 그대로 두고 위로만 자란다`() {
        listOf(0, 29, 30, 99, 100, 364, 365, 1000).forEach { days ->
            val fern = HomeScene.Spot(HomeScene.FERN.x, HomeScene.FERN.bottom, HomeScene.FERN.height * TimeTogether.fernScale(days))
            assertTrue("$days 일", HomeScene.SHELF.holds(fern))
        }
    }
}
