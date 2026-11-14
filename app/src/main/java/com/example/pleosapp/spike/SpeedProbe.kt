package com.example.pleosapp.spike

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager

/**
 * Vehicle SDK에 속도 API가 없어서 AAOS 표준 CarPropertyManager로 PERF_VEHICLE_SPEED를 받습니다.
 * [onSpeed]는 속도(m/s)와 신호 시각(elapsedRealtime 나노초)을 받습니다.
 */
class SpeedProbe(car: Car, private val onSpeed: (Float, Long) -> Unit) {

    private val manager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
    private var lastLoggedSpeed = -1f

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val speed = value.value as? Float ?: return
            onSpeed(speed, value.timestamp)
            if (kotlin.math.abs(speed - lastLoggedSpeed) >= 1f) {
                lastLoggedSpeed = speed
                ProbeState.signal("speed", "%.1f m/s".format(speed))
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) = ProbeState.log("FAIL speed onErrorEvent")
    }

    /** 받기 시작했는지 돌려줍니다. 속도 권한이 없으면 SecurityException이 나서 거짓입니다. */
    fun register(): Boolean = runCatching {
        manager.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FAST)
            .also { ProbeState.log("SpeedProbe registered=$it") }
    }.onFailure { ProbeState.log("FAIL speed ${it.javaClass.simpleName}: ${it.message}") }.getOrDefault(false)

    fun unregister() = runCatching { manager.unregisterCallback(callback) }
}
