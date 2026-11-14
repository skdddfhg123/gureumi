package com.example.pleosapp.verify

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.Window
import com.example.pleosapp.engine.RecallId
import com.example.pleosapp.ui.book.BookActivity
import com.example.pleosapp.ui.family.FamilyActivity
import com.example.pleosapp.ui.game.MotionTicker
import com.example.pleosapp.ui.recall.RecallActivity
import com.example.pleosapp.ui.spirit.SpiritSprites
import org.json.JSONArray
import org.json.JSONObject

/**
 * 검증 도구가 창 단위로 읽는 계측입니다. 릴리스 APK에는 없습니다.
 * scripts/verify/collect.py가 `com.example.pleosapp.VERIFY`로 시작과 끝을 알리고, 끝에서 logcat 태그 `Verify`로 한 줄을 받습니다.
 * `--es cmd open --es screen care`로 화면을 바로 엽니다. 이름표를 좌표로 누르면 앱이 막 떴거나 부하가 클 때 빗나갑니다.
 */
object VerifyProbe {
    private const val ACTION = "com.example.pleosapp.VERIFY"
    private const val TAG = "Verify"
    private const val FRAME_LOG_LIMIT = 200
    private const val NANOS_PER_MS = 1_000_000L

    private val frames = HandlerThread("verify-frames").apply { start() }
    private val framesHandler = Handler(frames.looper)

    private class Baseline(val atMs: Long, val ticks: Long, val decodes: Long, val decodeBytes: Long, val alloc: Long, val gc: Long)

    @Volatile
    private var open: Baseline? = null

    @Volatile
    private var framesDrawn = 0L

    @Volatile
    private var touchDownMs = 0L
    private val touchToFrame = ArrayList<Long>()

    private var mainStartMs = 0L
    private var mainLongestMs = 0L
    private var mainOver16 = 0L
    private var mainOver39 = 0L

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = watch(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getStringExtra("cmd")) {
                    "start" -> start()
                    "stop" -> stop()
                    "open" -> open(context, intent.getStringExtra("screen").orEmpty())
                }
            }
        }
        application.registerReceiver(receiver, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
    }

    /** [screen]은 care, growth, album, family 또는 그림책 이름(day7, stage_BABY)입니다. */
    private fun open(context: Context, screen: String) {
        val target = when (screen) {
            "care" -> BookActivity.intent(context, BookActivity.Page.CARE)
            "growth" -> BookActivity.intent(context, BookActivity.Page.GROWTH)
            "album" -> BookActivity.intent(context, BookActivity.Page.ALBUM)
            "family" -> FamilyActivity.intent(context, adding = false)
            else -> RecallId.parse(screen)?.let { RecallActivity.intent(context, it) }
        }
        if (target != null) context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun watch(activity: Activity) {
        val window = activity.window
        if (window.callback is TouchWatch) return
        window.callback = TouchWatch(window.callback)
        window.addOnFrameMetricsAvailableListener({ _, metrics, _ -> onFrame(metrics) }, framesHandler)
    }

    private fun onFrame(metrics: FrameMetrics) {
        if (open == null) return
        framesDrawn++
        val touched = touchDownMs
        if (touched == 0L) return
        val shownMs = (metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP) + metrics.getMetric(FrameMetrics.TOTAL_DURATION)) / NANOS_PER_MS
        if (shownMs < touched) return
        touchDownMs = 0L
        synchronized(touchToFrame) { if (touchToFrame.size < FRAME_LOG_LIMIT) touchToFrame += shownMs - touched }
    }

    private class TouchWatch(private val base: Window.Callback) : Window.Callback by base {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touchDownMs = event.eventTime
            return base.dispatchTouchEvent(event)
        }
    }

    private fun start() {
        framesDrawn = 0
        touchDownMs = 0
        synchronized(touchToFrame) { touchToFrame.clear() }
        mainStartMs = 0
        mainLongestMs = 0
        mainOver16 = 0
        mainOver39 = 0
        Looper.getMainLooper().setMessageLogging { line ->
            val now = SystemClock.uptimeMillis()
            if (line.startsWith(">")) {
                mainStartMs = now
            } else if (mainStartMs != 0L) {
                val took = now - mainStartMs
                if (took > mainLongestMs) mainLongestMs = took
                if (took > 16) mainOver16++
                if (took > 39) mainOver39++
            }
        }
        open = Baseline(SystemClock.elapsedRealtime(), MotionTicker.ticks, SpiritSprites.decodes, SpiritSprites.decodeBytes, allocated(), gcCount())
    }

    private fun stop() {
        val from = open ?: return
        open = null
        Looper.getMainLooper().setMessageLogging(null)
        val touches = synchronized(touchToFrame) { touchToFrame.toList() }
        val json = JSONObject()
            .put("elapsedMs", SystemClock.elapsedRealtime() - from.atMs)
            .put("ticks", MotionTicker.ticks - from.ticks)
            .put("framesDrawn", framesDrawn)
            .put("bitmapHeldBytes", SpiritSprites.residentBytes())
            .put("decodes", SpiritSprites.decodes - from.decodes)
            .put("decodeBytes", SpiritSprites.decodeBytes - from.decodeBytes)
            .put("allocBytes", allocated() - from.alloc)
            .put("gcCount", gcCount() - from.gc)
            .put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
            .put("touchToFrameMs", JSONArray(touches))
            .put("mainLongestMs", mainLongestMs)
            .put("mainOver16", mainOver16)
            .put("mainOver39", mainOver39)
        Log.i(TAG, json.toString())
    }

    private fun allocated(): Long = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: 0L

    private fun gcCount(): Long = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0L
}

/** 프로세스가 뜰 때 계측을 붙입니다. */
class VerifyProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.let(VerifyProbe::install)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
