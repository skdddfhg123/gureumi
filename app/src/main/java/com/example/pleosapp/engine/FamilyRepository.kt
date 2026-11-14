package com.example.pleosapp.engine

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** 앱 프로세스에 하나뿐인 가족 정령 상태. 깨어 있는 자리는 저장하지 않고 메모리에만 둡니다. */
object FamilyRepository {

    private val lock = Any()

    @Volatile
    private var file: AtomicFile? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var state = FamilyState()
        private set

    @Volatile
    var awakeSeats: Set<SeatSlot> = emptySet()
        private set

    /** 지금 탄 아기. 안전벨트를 채운 자리와 화면에서 깨운 자리에 앉은 아기입니다. */
    val aboard: List<FamilySpirit> get() = state.companions(awakeSeats)

    /**
     * 지금 함께 탄 아기. 프리셋 순서입니다. 화면은 자리 대신 이것으로 깸을 정합니다.
     * 탄 사람 수만큼, 부른 아기부터, 그다음 오래 기다린 아기가 탑니다. 안전벨트 신호가 없는 차는 화면에서 누른 아기입니다.
     */
    val riders: List<SpiritPreset> get() = state.ridersIn(awakeSeats)

    /** 앞서 안전벨트로 깨어난 자리. 다음 변화에서 계속 탄 사람을 가립니다. 메모리에만 둡니다. */
    @Volatile
    private var belted: Set<SeatSlot> = emptySet()

    /** 모든 화면이 메인 스레드에서 부릅니다. 저장 중인 락을 기다리지 않도록 먼저 봅니다. */
    fun init(context: Context) {
        if (file != null) return
        synchronized(lock) {
            if (file != null) return
            val opened = AtomicFile(File(context.filesDir, "family.json"))
            state = runCatching { FamilyCodec.decode(String(opened.readFully())) }.getOrDefault(FamilyState())
            file = opened
        }
    }

    /** 바뀐 상태를 저장한 뒤 화면들에 알립니다(락 밖에서). */
    fun update(transform: (FamilyState) -> FamilyState) {
        val changed = synchronized(lock) {
            val next = transform(state)
            if (next == state) {
                false
            } else {
                state = next
                save(next)
                true
            }
        }
        if (changed) listeners.forEach { it() }
    }

    fun setAwake(seats: Set<SeatSlot>) {
        awakeSeats = seats
        listeners.forEach { it() }
    }

    /**
     * 자리가 바뀌면 서비스가 부릅니다. 탈 아기를 안전벨트를 채운 자리에 앉힌 뒤 깨어 있는 자리를 알립니다.
     * 정차 중에는 자리를 지키는 아기만 두고 다시 고릅니다. 달리는 중에는 앉은 아기를 옮기지 않습니다.
     */
    fun onSeats(event: DriveEvent.SeatsAwake) {
        val held = belted
        belted = event.belted
        update { it.seated(event.belted, held, keep = if (event.parked) it.away else event.belted + it.away) }
        setAwake(event.seats)
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun save(next: FamilyState) {
        DiskWriter.save(file, FamilyCodec.encode(next))
    }
}
