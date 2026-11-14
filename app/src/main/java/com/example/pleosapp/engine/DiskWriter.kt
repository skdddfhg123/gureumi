package com.example.pleosapp.engine

import android.util.AtomicFile
import java.io.IOException
import java.util.concurrent.Executors

/**
 * 소품과 가족 파일을 저장하는 줄 하나. 화면이 누를 때도 저장하므로 플래시가 느리면 메인 스레드가 멈춥니다.
 * 메모리의 값은 바로 바뀌고 파일만 순서대로 뒤따릅니다.
 */
object DiskWriter {
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "disk-writer") }

    /** 임시 파일을 쓴 뒤 교체합니다. 중간에 전원이 꺼져도 파일이 깨지지 않습니다. */
    fun save(file: AtomicFile?, text: String) {
        val target = file ?: return
        writer.execute {
            val out = try {
                target.startWrite()
            } catch (_: IOException) {
                return@execute
            }
            try {
                out.write(text.toByteArray())
                SyncHook.before?.invoke()
                target.finishWrite(out)
            } catch (_: IOException) {
                target.failWrite(out)
            }
        }
    }
}
