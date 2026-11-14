package com.example.pleosapp.engine

import android.content.Context
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** 앱 프로세스에 하나뿐인 정령 상태. 시작할 때 원장을 재생해 복원합니다. */
object PetRepository {

    private val lock = Any()

    @Volatile
    private var ledger: EventLedger? = null
    private val listeners = CopyOnWriteArrayList<(DriveEvent) -> Unit>()

    @Volatile
    var state = PetState()
        private set

    /** 모든 화면이 메인 스레드에서 부릅니다. 저장 중인 락을 기다리지 않도록 먼저 봅니다. */
    fun init(context: Context) {
        if (ledger != null) return
        synchronized(lock) {
            if (ledger != null) return
            val opened = EventLedger(File(context.filesDir, "events.jsonl"))
            state = opened.readAll().fold(PetState(), PetState::apply)
            ledger = opened
        }
    }

    /** 원장에 기록하고 상태를 접은 뒤, 화면들에 이벤트를 알립니다(락 밖에서, 엔진 스레드). */
    fun accept(event: DriveEvent) {
        synchronized(lock) {
            ledger?.append(event)
            state = state.apply(event)
        }
        listeners.forEach { it(event) }
    }

    fun addListener(listener: (DriveEvent) -> Unit) = listeners.add(listener)

    fun removeListener(listener: (DriveEvent) -> Unit) = listeners.remove(listener)
}
