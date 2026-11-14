package com.example.pleosapp.spike

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DrivingTimeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TirePressureListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener

/** 드라이브펫 이벤트 엔진에 필요한 차량 신호를 모두 구독해 ProbeState로 흘려보냅니다. */
class VehicleProbe(private val vehicle: Vehicle) {

    private val unregisters = mutableListOf<() -> Unit>()

    fun registerAll() {
        guarded("gear") {
            val l = object : GearSelectionListener {
                override fun onSelected(gear: VehicleGear?) = ProbeState.signal("gear", gear)
                override fun onFailed(e: Exception) = failed("gear", e)
            }
            vehicle.getGear().registerGearSelection(l)
            unregisters += { vehicle.getGear().unregisterGearSelection(l) }
        }
        guarded("brake") {
            val l = object : BrakePedalStatusListener {
                override fun onBrakePedalStatusUpdated(status: Boolean?) = ProbeState.signal("brake", status)
                override fun onFailed(e: Exception) = failed("brake", e)
            }
            vehicle.getBrake().registerBrakePedalStatus(l)
            unregisters += { vehicle.getBrake().unregisterBrakePedalStatus(l) }
        }
        guarded("turnSignal") {
            val l = object : TurnSignalStateListener {
                override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = ProbeState.signal("turnSignal", state)
                override fun onFailed(e: Exception) = failed("turnSignal", e)
            }
            vehicle.getTurnSignal().registerTurnSignalState(l)
            unregisters += { vehicle.getTurnSignal().unregisterTurnSignalState(l) }
        }
        guarded("steering") {
            val l = object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(angle: Float?) = ProbeState.signal("steering", angle)
                override fun onFailed(e: Exception) = failed("steering", e)
            }
            vehicle.getSteeringWheel().registerSteeringWheelAngle(l)
            unregisters += { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(l) }
        }
        guarded("distance") {
            val l = object : DistanceDrivenListener {
                override fun onDistanceDrivenUpdated(value: Float?) = ProbeState.signal("distance", value)
                override fun onFailed(e: Exception) = failed("distance", e)
            }
            vehicle.getOdometer().registerDistanceDriven(l)
            unregisters += { vehicle.getOdometer().unregisterDistanceDriven(l) }
        }
        guarded("tripTime") {
            val l = object : DrivingTimeListener {
                override fun onDrivingTimeUpdated(value: Int?) = ProbeState.signal("tripTime", value)
                override fun onFailed(e: Exception) = failed("tripTime", e)
            }
            vehicle.getOdometer().registerTripCurrentDrivingTime(l)
            unregisters += { vehicle.getOdometer().unregisterTripCurrentDrivingTime(l) }
        }
        guarded("telltales") {
            val l = object : TelltaleInfoListener {
                override fun onTelltaleInfoUpdated(telltales: Telltales?) {
                    val active = telltales?.telltales.orEmpty()
                        .filter { it.state != TelltaleState.OFF }
                        .joinToString { "${it.type}:${it.state}" }
                    ProbeState.signal("telltales", active.ifEmpty { "none" })
                }
                override fun onFailed(e: Exception) = failed("telltales", e)
            }
            vehicle.getSafety().registerTelltales(l)
            unregisters += { vehicle.getSafety().unregisterTelltales(l) }
        }
        for (wheel in WheelValue.entries.filter { it != WheelValue.UNKNOWN }) {
            val key = "tire.$wheel"
            guarded(key) {
                val area = WheelArea(wheel)
                val l = object : TirePressureListener {
                    override fun onTirePressureUpdated(pressure: Float?) = ProbeState.signal(key, pressure)
                    override fun onFailed(e: Exception) = failed(key, e)
                }
                vehicle.getTire().registerTirePressure(area, l)
                unregisters += { vehicle.getTire().unregisterTirePressure(area, l) }
            }
        }
        for (seat in listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)) {
            val area = SeatArea(seat)
            guarded("occupied.$seat") {
                val l = object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(occupancy: Boolean?) = ProbeState.signal("occupied.$seat", occupancy)
                    override fun onFailed(e: Exception) = failed("occupied.$seat", e)
                }
                vehicle.getSeat().registerSeatOccupancy(area, l)
                unregisters += { vehicle.getSeat().unregisterSeatOccupancy(area, l) }
            }
            guarded("belt.$seat") {
                val l = object : SeatBeltBuckledStateListener {
                    override fun onSeatBeltBuckledStateUpdated(state: Boolean?) = ProbeState.signal("belt.$seat", state)
                    override fun onFailed(e: Exception) = failed("belt.$seat", e)
                }
                vehicle.getSeat().registerSeatBeltBuckledState(area, l)
                unregisters += { vehicle.getSeat().unregisterSeatBeltBuckledState(area, l) }
            }
        }
        ProbeState.log("VehicleProbe registered ${unregisters.size} listeners")
    }

    fun unregisterAll() {
        unregisters.forEach { runCatching(it) }
        unregisters.clear()
    }

    private fun guarded(key: String, block: () -> Unit) {
        runCatching(block).onFailure { failed(key, it) }
    }

    private fun failed(key: String, e: Throwable) {
        ProbeState.log("FAIL $key ${e.javaClass.simpleName}: ${e.message}")
    }
}
