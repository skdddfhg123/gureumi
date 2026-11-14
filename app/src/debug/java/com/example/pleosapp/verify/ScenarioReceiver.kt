package com.example.pleosapp.verify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.example.pleosapp.engine.CareItem
import com.example.pleosapp.engine.DriveEvent
import com.example.pleosapp.engine.FamilyRepository
import com.example.pleosapp.engine.KeepsakeRepository
import com.example.pleosapp.engine.PetRepository
import com.example.pleosapp.engine.SyncHook
import com.example.pleosapp.spike.VehicleProbeService
import com.example.pleosapp.ui.game.MotionTicker
import com.example.pleosapp.ui.spirit.SpiritSprites
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 자동차 시나리오가 앱 상태를 읽고 시험 입력을 넣는 입구입니다. 릴리스 APK에는 없습니다.
 * scripts/verify/scenarios.py가 부릅니다. 명시적 방송이라 프로세스가 죽어 있어도 깨웁니다.
 * `am broadcast --user 10 -n com.example.pleosapp/.verify.ScenarioReceiver --es cmd state --es id 1`
 *
 * 명령: state(`--es id`)는 logcat 태그 `Scenario`로 상태 한 줄을 남깁니다.
 * burst(`--ei from --ei count --el gapMs`)는 원장에 이벤트를 쓰고, fsync가 끝날 때마다 `ACK 번호`를 남깁니다.
 * slow_sync(`--el ms`)는 fsync 앞에 지연을 넣습니다. 0이면 뺍니다.
 * write_main은 수첩을 누른 것처럼 메인 스레드에서 저장합니다.
 * sky(`--es time day --es sky clear`)는 창밖 장면을 고정하고 `SKY time=… sky=…`를 남깁니다. 둘 다 빼면 실제 장면으로 돌아갑니다.
 * 고정은 프로세스 안에만 있어, 앱이 다시 뜨면 다시 보내야 합니다.
 */
class ScenarioReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra("cmd")) {
            "state" -> {
                val pending = goAsync()
                Thread({
                    try {
                        logState(context, intent.getStringExtra("id").orEmpty())
                    } finally {
                        pending.finish()
                    }
                }, "scenario-state").start()
            }
            // 방송을 붙잡지 않습니다. 시나리오는 쓰는 도중에 다음 일을 일으켜야 합니다.
            "burst" -> Thread({
                ready(context)
                burst(intent.getIntExtra("from", 0), intent.getIntExtra("count", 1), intent.getLongExtra("gapMs", 0L))
            }, "scenario-burst").start()
            "slow_sync" -> slowSync(intent.getLongExtra("ms", 0L))
            // 측정 중 창밖 장면을 고정합니다. 콘솔을 앞에 띄우지 않고 앱 프로세스 안에서 합니다.
            "sky" -> {
                val time = intent.getStringExtra("time")
                val sky = intent.getStringExtra("sky")
                if (SceneInput.apply(time, sky)) Log.i(TAG, "SKY time=$time sky=$sky") else Log.w(TAG, "SKY unknown time=$time sky=$sky")
            }
            "write_main" -> {
                ready(context)
                KeepsakeRepository.markDone(CareItem.WASH, System.currentTimeMillis())
            }
        }
    }

    private fun ready(context: Context) {
        PetRepository.init(context)
        FamilyRepository.init(context)
        KeepsakeRepository.init(context)
    }

    private fun logState(context: Context, id: String) {
        ready(context)
        val pet = PetRepository.state
        val power = context.getSystemService(PowerManager::class.java)
        val json = JSONObject()
            .put("id", id)
            .put("pid", Process.myPid())
            .put("feathers", pet.feathers)
            .put("trips", pet.trips)
            .put("days", pet.together.days)
            .put("inTrip", pet.together.inTrip)
            .put("lastTripMs", pet.lastTrip?.durationMs ?: JSONObject.NULL)
            .put("familyTrips", KeepsakeRepository.gifts.familyTrips)
            .put("spirits", FamilyRepository.state.spirits.size)
            .put("ledgerBytes", File(context.filesDir, "events.jsonl").length())
            .put("engine", VehicleProbeService.activeBridge != null)
            .put("thermal", power.currentThermalStatus)
            .put("residentBytes", SpiritSprites.residentBytes())
            .put("ticks", MotionTicker.ticks)
            .put("decodes", SpiritSprites.decodes)
            .put("syncOnMain", syncOnMain.get())
            .put("mainBlocked", mainBlocked.get())
        Log.i(TAG, json.toString())
    }

    /** 번호를 `peakDecel`에 실어 원장의 어느 줄이 어느 쓰기인지 가립니다. fsync가 끝난 뒤에만 ACK를 남깁니다. */
    private fun burst(from: Int, count: Int, gapMs: Long) {
        repeat(count) { index ->
            val seq = from + index
            PetRepository.accept(DriveEvent.SmoothStop(System.currentTimeMillis(), seq.toFloat()))
            Log.i(TAG, "ACK $seq")
            if (gapMs > 0) SystemClock.sleep(gapMs)
        }
        Log.i(TAG, "BURST done ${from + count - 1}")
    }

    private fun slowSync(ms: Long) {
        syncOnMain.set(0)
        mainBlocked.set(0)
        if (ms <= 0) {
            SyncHook.before = null
            return
        }
        SyncHook.before = {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                syncOnMain.incrementAndGet()
                Log.w(TAG, "SYNC on main\n" + Log.getStackTraceString(Throwable()))
            }
            val main = Looper.getMainLooper().thread
            val until = SystemClock.uptimeMillis() + ms
            while (SystemClock.uptimeMillis() < until) {
                if (main.state == Thread.State.BLOCKED && mainBlocked.incrementAndGet() == 1L) {
                    Log.w(TAG, "MAIN blocked\n" + main.stackTrace.take(STACK_LINES).joinToString("\n"))
                }
                SystemClock.sleep(minOf(SAMPLE_MS, ms))
            }
        }
    }

    private companion object {
        const val TAG = "Scenario"
        const val SAMPLE_MS = 5L
        const val STACK_LINES = 8
        val syncOnMain = AtomicLong()

        /** 지연 중 메인 스레드가 락을 기다린 표본 수. 저장이 메인을 멈추면 0이 아닙니다. */
        val mainBlocked = AtomicLong()
    }
}
