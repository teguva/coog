package tv.coog.app.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import tv.coog.app.BuildConfig

/**
 * Debug hop / frame logger for live TV scroll profiling. Off by default even in
 * DEBUG builds — Choreographer watches inflate Slow UI on device. Enable with:
 *   adb shell setprop debug.coog.jank 1
 *   adb logcat -s CoogJank:D
 *
 * Safe from any thread: Log is fine off-main; Choreographer is posted to main
 * (hold scroll marks run on Dispatchers.Default).
 */
internal object CoogJankLog {
    private const val TAG = "CoogJank"
    private const val PROP = "debug.coog.jank"
    private var lastHopAt = 0L
    private var lastHopLabel = ""
    private var frameCallback: Choreographer.FrameCallback? = null
    private var pendingSlowFrames = 0
    private var watching = false
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private fun enabled(): Boolean {
        if (!BuildConfig.DEBUG) return false
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java, String::class.java)
            val raw = get.invoke(null, PROP, "0") as String
            raw == "1" || raw.equals("true", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    fun hop(surface: String, detail: String) {
        if (!enabled()) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastHopAt == 0L) 0L else now - lastHopAt
        lastHopAt = now
        lastHopLabel = "$surface $detail"
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
        val maxMb = rt.maxMemory() / (1024L * 1024L)
        Log.d(
            TAG,
            "hop surface=$surface detail=$detail sinceLastHopMs=$dt heapMb=$usedMb/$maxMb",
        )
        armFrameWatch()
    }

    /** One-shot event (page commit, soft-end wait) — does not reset hop timing. */
    fun mark(surface: String, detail: String) {
        if (!enabled()) return
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
        Log.d(TAG, "mark surface=$surface detail=$detail heapMb=$usedMb lastHop='$lastHopLabel'")
        armFrameWatch()
    }

    private fun armFrameWatch() {
        if (!enabled() || watching) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { armFrameWatch() }
            return
        }
        if (watching) return
        watching = true
        pendingSlowFrames = 0
        val start = SystemClock.elapsedRealtime()
        val cb = object : Choreographer.FrameCallback {
            private var frames = 0
            private var lastFrameAt = start
            override fun doFrame(frameTimeNanos: Long) {
                if (!enabled()) {
                    watching = false
                    frameCallback = null
                    return
                }
                val now = SystemClock.elapsedRealtime()
                val gap = now - lastFrameAt
                lastFrameAt = now
                frames++
                if (gap > 32) {
                    pendingSlowFrames++
                    Log.w(TAG, "slowFrame gapMs=$gap afterHop='$lastHopLabel' frame=$frames")
                }
                // Hold scrolls last many seconds — keep rolling 45-frame windows.
                if (frames < 45 && now - start < 1500) {
                    Choreographer.getInstance().postFrameCallback(this)
                } else {
                    Log.d(
                        TAG,
                        "hopWindow done frames=$frames slowFrames=$pendingSlowFrames " +
                            "windowMs=${now - start} lastHop='$lastHopLabel'",
                    )
                    watching = false
                    frameCallback = null
                }
            }
        }
        frameCallback = cb
        Choreographer.getInstance().postFrameCallback(cb)
    }
}
