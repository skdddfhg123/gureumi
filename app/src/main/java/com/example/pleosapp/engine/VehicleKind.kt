package com.example.pleosapp.engine

import android.car.FuelType

/** 엔진오일을 챙겨야 하는 차인지. 하이브리드는 엔진이 있어 [ENGINE]이고 수소차는 엔진오일이 없어 [ELECTRIC]입니다. */
enum class VehicleKind {
    ENGINE, ELECTRIC, UNKNOWN;

    companion object {
        private val NO_ENGINE = setOf(FuelType.ELECTRIC, FuelType.HYDROGEN)
        private val NOT_KNOWN = setOf(FuelType.UNKNOWN, FuelType.OTHER)

        /** 차가 알려 준 연료 목록(`INFO_FUEL_TYPE`)으로 정합니다. 플러그인 하이브리드는 둘 다 알려 줍니다. */
        fun of(fuelTypes: List<Int>): VehicleKind {
            val known = fuelTypes - NOT_KNOWN
            return when {
                known.isEmpty() -> UNKNOWN
                known.all { it in NO_ENGINE } -> ELECTRIC
                else -> ENGINE
            }
        }

        fun fromStored(name: String): VehicleKind = entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}
