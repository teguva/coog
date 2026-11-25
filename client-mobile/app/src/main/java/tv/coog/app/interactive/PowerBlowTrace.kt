package tv.coog.app.interactive

import android.util.Log

/** Realtime PowerBlow playback diagnostics (logcat: `adb logcat -s CoogPB`). */
internal object PowerBlowTrace {
    const val TAG = "CoogPB"

    @Volatile private var lastClockLogAt = 0L
    @Volatile private var lastClockMs = -1.0
    @Volatile private var suckCount = 0
    @Volatile private var releaseCount = 0
    @Volatile private var skipCount = 0
    @Volatile private var staleCount = 0
    @Volatile private var bleCount = 0

    fun reset(reason: String) {
        suckCount = 0
        releaseCount = 0
        skipCount = 0
        staleCount = 0
        bleCount = 0
        lastClockMs = -1.0
        lastClockLogAt = 0L
        Log.i(TAG, "reset [$reason]")
    }

    fun clock(posMs: Double, playing: Boolean, holding: Boolean, vacuum: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastClockLogAt < 1000L && kotlin.math.abs(posMs - lastClockMs) < 900) return
        lastClockLogAt = now
        lastClockMs = posMs
        val mm = (posMs / 60000).toInt()
        val ss = ((posMs / 1000) % 60).toInt()
        Log.i(
            TAG,
            "clock %02d:%02d.%03d play=%s hold=%s vac=%s suck=%d vent=%d skip=%d stale=%d ble=%d".format(
                mm, ss, (posMs % 1000).toInt(),
                playing, holding, vacuum,
                suckCount, releaseCount, skipCount, staleCount, bleCount,
            ),
        )
    }

    fun skip(t: Double, curAt: Double, nxtAt: Double, curPos: Double, nxtPos: Double, reason: String, minMs: Double, holding: Boolean) {
        skipCount++
        if (skipCount <= 40 || skipCount % 25 == 0) {
            Log.i(
                TAG,
                "skip t=%.0f edge=%.0f→%.0f pos=%.0f→%.0f dt=%.0f minMs=%.0f hold=%s :: %s".format(
                    t, curAt, nxtAt, curPos, nxtPos, nxtAt - curAt, minMs, holding, reason,
                ),
            )
        }
    }

    fun stale(t: Double, nxtAt: Double, suck: Boolean, lagMs: Double) {
        staleCount++
        if (staleCount <= 40 || staleCount % 25 == 0) {
            Log.i(TAG, "stale lag=%.0f t=%.0f at=%.0f virtual=%s".format(lagMs, t, nxtAt, if (suck) "SUCK" else "VENT"))
        }
    }

    fun fire(t: Double, nxtAt: Double, st: PowerBlowStroke, holdingBefore: Boolean, minMs: Double) {
        if (st.suck) suckCount++ else releaseCount++
        val lag = t - nxtAt
        Log.i(
            TAG,
            "FIRE %s t=%.0f at=%.0f lag=%.0f lvl=%.2f dur=%dms holdWas=%s minMs=%.0f".format(
                if (st.suck) "SUCK" else "VENT",
                t, nxtAt, lag, st.level, st.durationMs, holdingBefore, minMs,
            ),
        )
    }

    fun ble(op: String, ok: Boolean, blocked: String = "", xx: Int = -1, yy: Int = -1, waitedMs: Long = 0) {
        bleCount++
        Log.i(
            TAG,
            "ble %s ok=%s wait=%dms xx=%02X yy=%02X %s".format(
                op, ok, waitedMs, xx.coerceAtLeast(0), yy.coerceAtLeast(0), blocked,
            ).trim(),
        )
    }
}
