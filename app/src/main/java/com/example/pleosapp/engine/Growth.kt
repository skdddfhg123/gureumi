package com.example.pleosapp.engine

import kotlin.math.min

/**
 * 누적 깃털이 유리병과 성장 단계로 보이는 규칙. 누적값만 보므로 되돌아가지 않습니다.
 * 수치의 근거는 docs/concept/growth.md와 scripts/simulate_growth.py에 있습니다.
 */
object Growth {
    /** 달린 시간 몇 분마다 기본 깃털 하나를 주는지. 속도와 무관합니다. */
    const val BASE_MINUTES = 5

    /** 공회전과 불필요한 운행을 보상하지 않도록 여정과 하루에 상한을 둡니다. */
    const val BASE_TRIP_CAP = 8
    const val BASE_DAY_CAP = 16

    private val JAR_SIZES = intArrayOf(10, 20, 30, 40, 50, 60)

    enum class Stage(val title: String, val feathers: Int) {
        FLUFF("솜털", 0),
        LITTLE("꼬마", 150),
        /** 구름이는 아기와 강가 손님을 "친구"라고 불러서 단계 이름은 "씩씩이"입니다. */
        FRIEND("씩씩이", 400),
        STURDY("듬직이", 800),
        GROWN("어른", 1300),
    }

    /** [opened]는 지금까지 가득 찬 병의 수입니다. */
    data class Jar(val opened: Int, val fill: Int, val capacity: Int) {
        val left: Int get() = capacity - fill
    }

    fun jar(feathers: Int): Jar {
        var rest = feathers
        var opened = 0
        while (true) {
            val size = JAR_SIZES[min(opened, JAR_SIZES.lastIndex)]
            if (rest < size) return Jar(opened, rest, size)
            rest -= size
            opened++
        }
    }

    /** 재생 중에 불리므로 어떤 값이 와도 죽지 않습니다. 여기서 죽으면 앱을 켤 때마다 다시 죽습니다. */
    fun stage(feathers: Int): Stage = Stage.entries.lastOrNull { feathers >= it.feathers } ?: Stage.FLUFF

    /** 원장에는 시계가 뒤로 가서 길이가 음수로 적힌 트립이 있을 수 있습니다. */
    fun baseFeathers(durationMs: Long): Int =
        (durationMs / (BASE_MINUTES * 60_000L)).toInt().coerceIn(0, BASE_TRIP_CAP)
}
