package com.example.pleosapp.spike

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context

/** NaviHelper 검증: 목적지 설정, 휴게소 경유지 추가, 트립 정보(ETA·거리), 도착 이벤트. */
class NaviProbe(context: Context) {

    private val navi = NaviHelper(context.applicationContext)
    private var connected = false

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = ProbeState.log("NAVI routeStarted $routeStartInfo")
        override fun onRoutingCompleted() = ProbeState.log("NAVI routingCompleted")
        override fun onRouteCancelled() = ProbeState.log("NAVI routeCancelled")
        override fun onRouteEnded() = ProbeState.log("NAVI routeEnded")
        override fun onDestinationChanged() = ProbeState.log("NAVI destinationChanged")
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) = ProbeState.log("NAVI waypointChanged $waypointChangedInfo")
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = ProbeState.log("NAVI ARRIVED $destinationArrivedInfo")
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = ProbeState.log("NAVI destinationInfo $destinationInfo")
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) = ProbeState.log("NAVI waypointInfo $waypointInfoList")
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = ProbeState.log("NAVI routeState $routeStateInfo")
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = ProbeState.log("NAVI location $currentLocationInfo")
        override fun onTripInfo(tripInfo: TripInfo) = ProbeState.log("NAVI trip distance=${tripInfo.distance} eta=${tripInfo.eta} soc@dest=${tripInfo.estimatedSocAtDest}")
        override fun onDrivingInfo(drivingInfo: DrivingInfo) = ProbeState.log("NAVI drivingInfo $drivingInfo")
        override fun onNavigationStatus(status: NavigationStatus) = ProbeState.log("NAVI status $status")
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) = ProbeState.log("NAVI speedLimit $speedLimit")
        override fun onError(errorCode: NaviErrorCode) = ProbeState.log("NAVI error $errorCode")
    }

    fun connect(then: () -> Unit = {}) {
        if (connected) return then()
        navi.initialize(
            onConnected = {
                connected = true
                navi.addListener(listener)
                ProbeState.log("NAVI connected")
                then()
            },
            onFailed = { ProbeState.log("NAVI connect failed ${it.message}") },
        )
    }

    /** 문서 예제 목적지(42dot 사옥)로 경로 요청. */
    fun requestRoute() = connect {
        navi.requestRoute(
            RouteInfo(
                latitude = 37.48544722,
                longitude = 127.03636666,
                poiName = "42dot",
                poiId = "36khp37hh8gqcm5nqhe5",
                address = "서울시 강남구 남부순환로 2621",
                poiSubId = "0",
                routeOption = RouteOption.RECOMMENDED,
            ),
        )
        ProbeState.log("NAVI requestRoute sent")
    }

    /** 펫이 졸릴 때 쓸 '휴게소 경유지 추가' 흐름 검증 (좌표만 지정, POI ID 없음). */
    fun addRestStop() = connect {
        navi.addWaypoint(
            RequestWaypointInfo(
                latitude = 37.4636,
                longitude = 127.0430,
                poiName = "양재 쉼터",
                waypointIndex = WaypointIndex.FIRST,
                poiId = "",
                address = "서울시 서초구 양재동",
                poiSubId = "",
            ),
        )
        ProbeState.log("NAVI addWaypoint sent")
    }

    fun queryInfo() = connect {
        navi.getRouteStateInfo()
        navi.getDestinationInfo()
        navi.getWaypointInfo()
        navi.getCurrentLocationInfo()
    }

    fun cancel() = connect { navi.cancelRoute() }

    fun release() {
        if (connected) navi.removeListener(listener)
        navi.release()
    }
}
