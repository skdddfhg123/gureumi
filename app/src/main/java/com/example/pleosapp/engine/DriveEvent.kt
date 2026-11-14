package com.example.pleosapp.engine

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

enum class TurnSide { OFF, LEFT, RIGHT }

/** 가족 정령이 앉을 수 있는 자리. 운전석은 구름이의 자리라 넣지 않습니다. */
enum class SeatSlot(val displayName: String) {
    FRONT_RIGHT("조수석"),
    REAR_LEFT("왼쪽 뒷자리"),
    REAR_CENTER("가운데 뒷자리"),
    REAR_RIGHT("오른쪽 뒷자리");

    val isRear: Boolean get() = this != FRONT_RIGHT

    companion object {
        /** 10/31 이전 원장에는 뒷자리만 LEFT, RIGHT로 적혀 있습니다. */
        fun fromStored(name: String): SeatSlot = when (name) {
            "LEFT" -> REAR_LEFT
            "RIGHT" -> REAR_RIGHT
            else -> valueOf(name)
        }
    }
}

/** 차량 신호에서 판정한 운전 이벤트. 모든 펫 변화는 이 이벤트에서만 일어납니다. */
sealed interface DriveEvent {
    val atMs: Long

    /** false면 화면과 음성에만 쓰고 원장에 남기지 않습니다. */
    val persisted: Boolean get() = true

    data class TripStarted(override val atMs: Long, val tripId: Long) : DriveEvent
    data class TripEnded(override val atMs: Long, val summary: TripSummary) : DriveEvent

    data class SmoothStop(override val atMs: Long, val peakDecel: Float) : DriveEvent
    data class HardBrake(override val atMs: Long, val decel: Float, val fromSpeed: Float) : DriveEvent

    data class SignalUsed(override val atMs: Long, val side: TurnSide, val leadMs: Long) : DriveEvent
    data class SignalMissed(override val atMs: Long, val side: TurnSide) : DriveEvent

    data class BeltsReady(override val atMs: Long) : DriveEvent
    data class BeltsMissing(override val atMs: Long) : DriveEvent

    /** 뒷문이 닫혔는데 벨트가 안 채워져 있어 구름이가 부탁한 순간. 정차 중에만 발생합니다. */
    data class RearBeltRequested(override val atMs: Long, val seat: SeatSlot) : DriveEvent

    /** 뒷자리 탑승자가 벨트를 채운 순간. [parked]가 true일 때만 음성으로 인사합니다. */
    data class RearBeltBuckled(override val atMs: Long, val seat: SeatSlot, val parked: Boolean) : DriveEvent

    /**
     * 깨어 있는 자리가 바뀐 순간. 누가 언제 탔는지 쌓이지 않도록 원장에 남기지 않습니다.
     * [newly]는 방금 안전벨트로 깨어나 인사할 자리, [waiting]은 탑승했지만 아직 안전벨트를 안 채운 자리가 있는지입니다.
     * [belted]는 안전벨트로 깨어난 자리입니다. 화면에서 깨운 자리는 빠집니다. 탈 아기 수가 이 자리 수입니다.
     */
    data class SeatsAwake(
        override val atMs: Long,
        val seats: Set<SeatSlot>,
        val newly: Set<SeatSlot>,
        val waiting: Boolean,
        val parked: Boolean,
        val belted: Set<SeatSlot> = emptySet(),
    ) : DriveEvent {
        override val persisted: Boolean get() = false
    }

    /**
     * 벨트를 풀고 그 자리 문이 닫힌 순간. 그 자리 사람이 내린 것으로 봅니다. 누가 언제 내렸는지는 남기지 않습니다.
     * P가 아닐 때 내렸으면 장면을 보여 줄 수 없어 다음 도착 때 냅니다.
     */
    data class SeatLeft(override val atMs: Long, val seat: SeatSlot) : DriveEvent {
        override val persisted: Boolean get() = false
    }

    /** 내렸던 자리의 벨트가 다시 채워진 순간. [absentMs]는 비어 있던 시간입니다. */
    data class SeatReturned(override val atMs: Long, val seat: SeatSlot, val absentMs: Long, val parked: Boolean) : DriveEvent {
        override val persisted: Boolean get() = false

        /** 매번 인사하면 금방 질립니다. 주차 중이고 오래 떨어져 있었을 때만 소리를 냅니다. */
        val greetsAloud: Boolean get() = parked && absentMs >= EngineConfig.LONG_ABSENCE_MS
    }

    /** 긴 주행 중간에 쉬고 다시 출발한 순간. */
    data class RestTaken(override val atMs: Long, val restMs: Long) : DriveEvent

    data class HealthAlert(override val atMs: Long, val code: String) : DriveEvent
    data class HealthRecovered(override val atMs: Long, val code: String) : DriveEvent
}

data class TripSummary(
    val tripId: Long,
    val durationMs: Long,
    val distanceM: Float,
    val smoothStops: Int,
    val hardBrakes: Int,
    val signalsUsed: Int,
    val signalsMissed: Int,
    /** 이 트립에서 잠금(래치) 동안 관측한 최대 감속도. `HardBrake.decel`은 임계값을 처음 넘은 순간 값입니다. */
    val peakBrakeDecel: Float = 0f,
    /** 출발 전이나 트립 중 뒷자리 벨트를 채운 횟수. */
    val rearBelts: Int = 0,
    /** 이 트립 직전에 쉬었는지. */
    val restedBefore: Boolean = false,
    /** 이 트립에서 안전벨트가 채워져 있던 자리. */
    val seats: Set<SeatSlot> = emptySet(),
    /** 안전벨트 신호 없이 화면에서 깨운 자리. 함께한 정령으로 표시하지만 고마움 문장과 깃털은 없습니다. */
    val tappedSeats: Set<SeatSlot> = emptySet(),
    /** 도착했는데 뒷자리 벨트가 그대로입니다. 잠든 아이일 수 있어 소리를 내지 않습니다. */
    val rearStillBelted: Boolean = false,
    /** 출발할 때 안전벨트 경고가 없었는지. 깃털은 10분에 한 번이어도 도장은 트립마다 찍습니다. */
    val driverBelted: Boolean = false,
)

/** 재시작 때 원장에서 복원하는 휴식 시계. 시동을 끄고 쉰 시간도 휴식으로 인정하기 위해 필요합니다. */
data class RestClock(val driveMs: Long = 0, val parkedSinceMs: Long? = null)
