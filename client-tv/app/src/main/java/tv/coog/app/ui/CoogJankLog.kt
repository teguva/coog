package tv.coog.app.ui

import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import tv.coog.app.BuildConfig

/**
 * Debug-only hop / frame logger for live TV scroll profiling via logcat:
 *   adb logcat -s CoogJank:D
 */
internal object CoogJankLog {
    private const val TAG = "CoogJank"
    private var lastHopAt = 0L
    private var lastHopLabel = ""
    private var frameCallback: Choreographer.FrameCallback? = null
    private var pendingSlowFrames = 0
    private var watching = false

    fun hop(surface: String, detail: String) {
        if (!BuildConfig.DEBUG) return
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
        if (!BuildConfig.DEBUG) return
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
        Log.d(TAG, "mark surface=$surface detail=$detail heapMb=$usedMb lastHop='$lastHopLabel'")
        armFrameWatch()
    }

    private fun armFrameWatch() {
        if (!BuildConfig.DEBUG || watching) return
        watching = true
        pendingSlowFrames = 0
        val start = SystemClock.elapsedRealtime()
        val cb = object : Choreographer.FrameCallback {
            private var frames = 0
            private var lastFrameAt = start
            override fun doFrame(frameTimeNanos: Long) {
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
