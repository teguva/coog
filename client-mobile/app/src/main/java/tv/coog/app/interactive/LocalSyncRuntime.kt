package tv.coog.app.interactive

import android.os.SystemClock
import kotlin.math.abs

/**
 * Funscript drive loop for on-device Buttplug (mirrors server SyncRuntime.tick).
 *
 * Linear (Solace Pro): sample with [LINEAR_LEAD_MS], emit at most once per
 * [LINEAR_GAP_MS] with fixed [LINEAR_DUR_MS] so we do not fight the 100ms
 * DeviceMessageTimingGap. Idle stop is Oscillate:0 only ([LocalIntiface.parkIdle]).
 */
class LocalSyncRuntime(
    private val bp: ButtplugClient,
    private val prefs: DevicePrefsStore,
) {
    private val lock = Any()
    private var actions: List<FunAction> = emptyList()
    private var params = SyncParams()
    private var loaded = false
    private var playing = false
    private var posMs = 0.0
    private var driveEpoch = 0L

    private val nextCon = mutableMapOf<Int, Int>()
    private val lastVib = mutableMapOf<Int, Double>()
    private val lastLin = mutableMapOf<Int, Double>()
    private val lastLinSendAt = mutableMapOf<Int, Long>()

    fun load(actions: List<FunAction>, params: SyncParams = SyncParams(), resumeMs: Double = 0.0) {
        synchronized(lock) {
            this.actions = actions
            this.params = params.normalized()
            loaded = actions.isNotEmpty()
            playing = false
            posMs = resumeMs
            driveEpoch++
            nextCon.clear()
            lastVib.clear()
            lastLin.clear()
            lastLinSendAt.clear()
        }
    }

    fun clear() {
        synchronized(lock) {
            actions = emptyList()
            loaded = false
            playing = false
            driveEpoch++
            nextCon.clear()
            lastVib.clear()
            lastLin.clear()
            lastLinSendAt.clear()
        }
    }

    fun setClock(posMs: Double, playing: Boolean, seek: Boolean): Boolean {
        synchronized(lock) {
            if (seek) {
                nextCon.clear()
                lastLin.clear()
                lastLinSendAt.clear()
                driveEpoch++
            }
            val was = this.playing
            this.posMs = posMs
            this.playing = playing
            if (was && !playing) {
                driveEpoch++
                nextCon.clear()
                lastVib.clear()
                lastLinSendAt.clear()
                return true
            }
        }
        return false
    }

    /** Stop the drive clock; returns last Linear positions (for diagnostics / legacy). */
    fun haltNow(positionMs: Double = posMsSafe()): List<LinearHold> {
        synchronized(lock) {
            posMs = positionMs
            playing = false
            driveEpoch++
            nextCon.clear()
            lastVib.clear()
            lastLinSendAt.clear()
            val holds = buildLinearHoldsLocked()
            return holds
        }
    }

    fun clearLastLin() {
        synchronized(lock) {
            lastLin.clear()
            lastLinSendAt.clear()
        }
    }

    fun isPlaying(): Boolean = synchronized(lock) { playing }

    private fun posMsSafe(): Double = synchronized(lock) { posMs }

    fun isDriving(): Boolean = synchronized(lock) { loaded && playing && actions.isNotEmpty() }

    fun tick() {
        val snapshot: Snapshot
        val epoch: Long
        synchronized(lock) {
            if (!loaded || !playing || actions.isEmpty()) return
            epoch = driveEpoch
            snapshot = Snapshot(posMs, actions, params)
        }
        for (d in bp.devices()) {
            synchronized(lock) {
                if (!playing || driveEpoch != epoch) return
            }
            val intensity = prefs.intensity(d.deviceId) / 100.0
            val t = snapshot.pos + prefs.offsetMs(d.deviceId)
            when (d.kind) {
                "linear" -> driveLinear(d.index, t, snapshot.actions, snapshot.params, intensity, epoch)
                "constrict" -> driveConstrict(d.index, t, snapshot.actions, snapshot.params, intensity)
                else -> driveScalar(d.index, t, snapshot.actions, snapshot.params, intensity)
            }
        }
    }

    private fun buildLinearHoldsLocked(): List<LinearHold> {
        val out = ArrayList<LinearHold>()
        for (d in bp.devices()) {
            if (d.kind != "linear") continue
            val last = lastLin[d.index]
            if (last != null) {
                out += LinearHold(d.index, last.coerceIn(0.0, 1.0))
                continue
            }
            if (!loaded || actions.isEmpty()) continue
            val intensity = prefs.intensity(d.deviceId) / 100.0
            val t = posMs + prefs.offsetMs(d.deviceId) + LINEAR_LEAD_MS
            var target = mapLinearPos(actionPosAt(actions, t) / 100.0, intensity)
            if (params.invert) target = 1.0 - target
            out += LinearHold(d.index, target.coerceIn(0.0, 1.0))
        }
        return out
    }

    private fun driveScalar(
        idx: Int,
        t: Double,
        actions: List<FunAction>,
        params: SyncParams,
        intensity: Double,
    ) {
        val pos = actionPosAt(actions, t) / 100.0
        val level = vibLevelAt(pos) * params.scale * intensity
        val last = synchronized(lock) { lastVib[idx] ?: -1.0 }
        if (abs(level - last) < 0.008) return
        bp.scalar(idx, level.coerceIn(0.0, 1.0), "Vibrate")
        synchronized(lock) { lastVib[idx] = level }
    }

    private fun driveLinear(
        idx: Int,
        t: Double,
        actions: List<FunAction>,
        params: SyncParams,
        intensity: Double,
        epoch: Long,
    ) {
        if (actions.isEmpty()) return
        // Pipeline lead + extrema clamp/inset (see actionPosLinearTarget).
        var target = mapLinearPos(actionPosLinearTarget(actions, t, LINEAR_LEAD_MS) / 100.0, intensity)
        if (params.invert) target = 1.0 - target
        target = target.coerceIn(0.0, 1.0)

        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (!playing || driveEpoch != epoch) return
            val last = lastLin[idx]
            if (last != null && abs(target - last) < 0.004) return
            val lastSend = lastLinSendAt[idx] ?: 0L
            if (now - lastSend < LINEAR_GAP_MS) return
        }

        synchronized(lock) {
            if (!playing || driveEpoch != epoch) return
        }
        bp.linear(idx, target, LINEAR_DUR_MS)
        synchronized(lock) {
            if (playing && driveEpoch == epoch) {
                lastLin[idx] = target
                lastLinSendAt[idx] = now
            }
        }
    }

    private fun driveConstrict(
        idx: Int,
        t: Double,
        actions: List<FunAction>,
        params: SyncParams,
        intensity: Double,
    ) {
        if (actions.size < 2) return
        var ni = synchronized(lock) { nextCon[idx] ?: 0 }
        val minInterval = params.minInterval.coerceAtLeast(50)
        while (ni < actions.size && actions[ni].at <= t) {
            val cur = actions[ni]
            var tgt = ni + 1
            while (tgt < actions.size && (actions[tgt].at - cur.at) < minInterval) tgt++
            if (tgt >= actions.size) {
                synchronized(lock) { nextCon[idx] = actions.size }
                return
            }
            val nxt = actions[tgt]
            val (level, ok) = constrictStrokeLevel(cur.pos, nxt.pos, params.invert)
            ni = tgt
            if (!ok) continue
            bp.scalar(idx, (level * params.scale * intensity).coerceIn(0.0, 1.0), "Constrict")
        }
        synchronized(lock) { nextCon[idx] = ni }
    }

    private data class Snapshot(val pos: Double, val actions: List<FunAction>, val params: SyncParams)

    data class LinearHold(val index: Int, val pos: Double)

    private fun SyncParams.normalized(): SyncParams = copy(
        scale = if (scale <= 0) 1.0 else scale,
        speed = if (speed <= 0) 0.9 else speed,
        minInterval = if (minInterval <= 0) 60 else minInterval,
    )
}
