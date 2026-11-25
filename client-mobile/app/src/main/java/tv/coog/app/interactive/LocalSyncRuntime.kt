package tv.coog.app.interactive

import android.content.Context
import android.os.SystemClock
import kotlin.math.abs

/**
 * Funscript drive loop for on-device Buttplug (mirrors server SyncRuntime.tick).
 *
 * Linear: Solace keeps continuous FSetSite tracking; Handy 2 seeks one scripted
 * half-stroke tip at a time ([driveLinearHandy]). Idle stop is Oscillate:0 only
 * ([LocalIntiface.parkIdle]).
 */
class LocalSyncRuntime(
    private val appContext: Context,
    private val bp: ButtplugClient,
    private val prefs: DevicePrefsStore,
    private val extraDevices: () -> List<LiveDevice> = { emptyList() },
    private val constrict: (index: Int, level: Double) -> Unit = { idx, level ->
        bp.scalar(idx, level, "Constrict")
    },
    private val powerBlowStroke: (PowerBlowStroke) -> Unit = {},
    private val powerBlowVacuumOn: () -> Boolean = { false },
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
    private val nextLinTip = mutableMapOf<Int, Int>()
    private var pbHolding = false
    private var pbGate = PowerBlowGate()
    private var pbMask: PowerBlowActivityMask? = null

    fun load(
        actions: List<FunAction>,
        params: SyncParams = SyncParams(),
        resumeMs: Double = 0.0,
        gate: PowerBlowGate = PowerBlowGate(),
    ) {
        val mask = if (!gate.manual()) {
            runCatching { buildPowerBlowActivityMask(actions, appContext) }.getOrNull()
        } else {
            null
        }
        synchronized(lock) {
            this.actions = actions
            var p = params.normalized()
            if (gate.manual() && gate.minStrokeMs > 0) {
                p = p.copy(powerBlowMinStrokeMs = gate.minStrokeMs)
            }
            this.params = p
            this.pbGate = gate
            this.pbMask = mask
            loaded = actions.isNotEmpty()
            playing = false
            posMs = resumeMs
            driveEpoch++
            nextCon.clear()
            lastVib.clear()
            lastLin.clear()
            nextLinTip.clear()
            lastLinSendAt.clear()
            pbHolding = false
            if (actions.isNotEmpty() && resumeMs > 0) {
                nextCon[PowerBlowHub.INDEX] = seedActionIndex(actions, resumeMs)
            }
        }
        // Start open — never inherit a phantom hold across loads.
        powerBlowStroke(PowerBlowStroke(suck = false, level = 0.55, durationMs = 250))
        PowerBlowTrace.reset(
            "load actions=${actions.size} gate=${gate.mode.ifBlank { "pattern" }} resume=${resumeMs.toLong()}",
        )
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
            nextLinTip.clear()
            lastLinSendAt.clear()
            pbHolding = false
            pbGate = PowerBlowGate()
            pbMask = null
        }
        PowerBlowTrace.reset("clear")
    }

    /** Clear hold flag after parkIdle / Halt vents the device. */
    fun clearPowerBlowHold() {
        synchronized(lock) { pbHolding = false }
    }

    private fun powerBlowActive(
        t: Double,
        gate: PowerBlowGate,
        params: SyncParams,
        mask: PowerBlowActivityMask?,
    ): Pair<Boolean, Double> {
        @Suppress("UNUSED_PARAMETER")
        val unused = params to mask
        // Manual gate only; otherwise use the original funscript unfiltered.
        if (gate.manual()) {
            return gate.activeAt(t) to 0.0
        }
        return true to 0.0
    }

    fun setPowerBlowMinStrokeMs(ms: Int) {
        synchronized(lock) {
            val v = if (ms <= 0) DEFAULT_POWERBLOW_MIN_STROKE_MS else ms.coerceIn(60, 2000)
            params = params.copy(powerBlowMinStrokeMs = v)
        }
    }

    fun setClock(posMs: Double, playing: Boolean, seek: Boolean): Boolean {
        var didSeek = false
        val enteredPause: Boolean
        synchronized(lock) {
            if (seek) {
                nextCon.clear()
                lastLin.clear()
            nextLinTip.clear()
                lastLinSendAt.clear()
                pbHolding = false
                driveEpoch++
                if (actions.isNotEmpty()) {
                    nextCon[PowerBlowHub.INDEX] = seedActionIndex(actions, posMs)
                    PowerBlowTrace.reset("seek pos=${posMs.toLong()} idx=${nextCon[PowerBlowHub.INDEX]}")
                }
                didSeek = true
            }
            val was = this.playing
            this.posMs = posMs
            this.playing = playing
            enteredPause = was && !playing
            if (enteredPause) {
                driveEpoch++
                nextCon.clear()
                lastVib.clear()
                lastLinSendAt.clear()
            }
        }
        if (didSeek) {
            powerBlowStroke(PowerBlowStroke(suck = false, level = 0.55, durationMs = 250))
        }
        return enteredPause
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
            nextLinTip.clear()
            lastLinSendAt.clear()
        }
    }

    fun isPlaying(): Boolean = synchronized(lock) { playing }

    private fun posMsSafe(): Double = synchronized(lock) { posMs }

    fun isDriving(): Boolean = synchronized(lock) { loaded && playing && actions.isNotEmpty() }

    fun tick() {
        val snapshot: Snapshot
        val epoch: Long
        val holdingSnap: Boolean
        synchronized(lock) {
            if (!loaded || !playing || actions.isEmpty()) return
            epoch = driveEpoch
            holdingSnap = pbHolding
            snapshot = Snapshot(posMs, actions, params)
        }
        PowerBlowTrace.clock(snapshot.pos, playing = true, holding = holdingSnap, vacuum = powerBlowVacuumOn())
        for (d in liveDevices()) {
            synchronized(lock) {
                if (!playing || driveEpoch != epoch) return
            }
            val intensity = prefs.intensity(d.deviceId) / 100.0
            val t = snapshot.pos + prefs.offsetMs(d.deviceId, d.name)
            when (d.kind) {
                "linear" -> driveLinear(d, t, snapshot.actions, snapshot.params, intensity, epoch)
                "constrict" -> driveConstrict(d.index, t, snapshot.actions, snapshot.params, intensity)
                else -> driveScalar(d.index, t, snapshot.actions, snapshot.params, intensity)
            }
        }
    }

    private fun liveDevices(): List<LiveDevice> = bp.devices() + extraDevices()

    private fun buildLinearHoldsLocked(): List<LinearHold> {
        val out = ArrayList<LinearHold>()
        for (d in liveDevices()) {
            if (d.kind != "linear") continue
            val last = lastLin[d.index]
            if (last != null) {
                out += LinearHold(d.index, last.coerceIn(0.0, 1.0))
                continue
            }
            if (!loaded || actions.isEmpty()) continue
            val intensity = prefs.intensity(d.deviceId) / 100.0
            val prof = linearProfileFor(d.name)
            val t = posMs + prefs.offsetMs(d.deviceId, d.name) + prof.leadMs
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
        device: LiveDevice,
        t: Double,
        actions: List<FunAction>,
        params: SyncParams,
        intensity: Double,
        epoch: Long,
    ) {
        if (actions.isEmpty()) return
        if (isHandyName(device.name)) {
            driveLinearHandy(device.index, t, actions, params, intensity, epoch)
            return
        }
        val idx = device.index
        val prof = linearProfileFor(device.name)
        // Pipeline lead + extrema clamp/inset (see actionPosLinearTarget).
        var target = mapLinearPos(
            actionPosLinearTarget(actions, t, prof.leadMs, prof.inset) / 100.0,
            intensity,
        )
        if (params.invert) target = 1.0 - target
        target = target.coerceIn(0.0, 1.0)

        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (!playing || driveEpoch != epoch) return
            val last = lastLin[idx]
            if (last != null && abs(target - last) < 0.004) return
            val lastSend = lastLinSendAt[idx] ?: 0L
            if (now - lastSend < prof.gapMs) return
        }

        synchronized(lock) {
            if (!playing || driveEpoch != epoch) return
        }
        bp.linear(idx, target, prof.durMs)
        synchronized(lock) {
            if (playing && driveEpoch == epoch) {
                lastLin[idx] = target
                lastLinSendAt[idx] = now
            }
        }
    }

    private fun driveLinearHandy(
        idx: Int,
        t: Double,
        actions: List<FunAction>,
        params: SyncParams,
        intensity: Double,
        epoch: Long,
    ) {
        val prof = linearProfileFor("handy")
        synchronized(lock) {
            if (!playing || driveEpoch != epoch) return
            val tipIdx = nextLinTip[idx]
            // Hold until playhead reaches the tip — early release + wall gap skipped strokes.
            if (tipIdx != null && tipIdx < actions.size && actions[tipIdx].at > t) {
                return
            }
        }
        val tip = nextLinearStrokeTip(actions, t, HANDY_MIN_STROKE_DELTA, prof.inset) ?: return
        var target = mapLinearPos(tip.pos / 100.0, intensity)
        if (params.invert) target = 1.0 - target
        target = target.coerceIn(0.0, 1.0)

        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (!playing || driveEpoch != epoch) return
            if (nextLinTip[idx] == tip.idx) return
            val last = lastLin[idx]
            if (last != null && abs(target - last) < 0.005 && nextLinTip[idx] == tip.idx) return
        }
        synchronized(lock) {
            if (!playing || driveEpoch != epoch) return
        }
        bp.linear(idx, target, tip.durMs)
        synchronized(lock) {
            if (playing && driveEpoch == epoch) {
                lastLin[idx] = target
                lastLinSendAt[idx] = now
                nextLinTip[idx] = tip.idx
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
        val pb = idx == PowerBlowHub.INDEX
        var ni: Int
        var holding: Boolean
        val gate: PowerBlowGate
        val mask: PowerBlowActivityMask?
        synchronized(lock) {
            ni = nextCon[idx] ?: if (pb) seedActionIndex(actions, t) else 0
            holding = pbHolding
            gate = pbGate
            mask = pbMask
        }
        var minInterval = params.minInterval.coerceAtLeast(50)
        if (pb) minInterval = 0
        // PowerBlow: one live BLE command per tick; merge staircases first.
        while (ni < actions.size) {
            val cur = actions[ni]
            var tgt = if (pb) {
                extendSameDirection(actions, ni)
            } else {
                var t2 = ni + 1
                while (t2 < actions.size && (actions[t2].at - cur.at) < minInterval) t2++
                t2
            }
            if (tgt >= actions.size) {
                synchronized(lock) {
                    nextCon[idx] = actions.size
                    if (pb) pbHolding = holding
                }
                return
            }
            val nxt = actions[tgt]
            if (pb) {
                // Fire slightly early so BLE lands near stroke START.
                if (cur.at - PB_BLE_LEAD_MS > t) {
                    synchronized(lock) {
                        nextCon[idx] = ni
                        pbHolding = holding
                    }
                    return
                }
                ni = tgt
                val (active, minMs) = powerBlowActive(cur.at, gate, params, mask)
                if (!active) {
                    PowerBlowTrace.skip(
                        t, cur.at, nxt.at, cur.pos, nxt.pos,
                        "manual-gate", minMs, holding,
                    )
                    continue
                }
                when (
                    val adapted = adaptPowerBlowStrokeDiag(
                        cur.at, cur.pos, nxt.at, nxt.pos,
                        params.invert, params.scale, intensity,
                        minMs,
                        holding,
                        active,
                    )
                ) {
                    is PowerBlowAdaptResult.Skip -> {
                        PowerBlowTrace.skip(
                            t, cur.at, nxt.at, cur.pos, nxt.pos,
                            adapted.reason, minMs, holding,
                        )
                        continue
                    }
                    is PowerBlowAdaptResult.Fire -> {
                        val st = adapted.stroke
                        val lag = t - cur.at
                        if (lag > PB_STALE_LAG_MS) {
                            PowerBlowTrace.stale(t, cur.at, st.suck, lag)
                            holding = st.suck
                            continue
                        }
                        PowerBlowTrace.fire(t, cur.at, st, holding, minMs)
                        powerBlowStroke(st)
                        holding = st.suck
                        synchronized(lock) {
                            nextCon[idx] = ni
                            pbHolding = holding
                        }
                        return
                    }
                }
            } else {
                if (cur.at > t) {
                    synchronized(lock) { nextCon[idx] = ni }
                    return
                }
                ni = tgt
                val (level, ok) = constrictStrokeLevel(cur.pos, nxt.pos, params.invert)
                if (!ok) continue
                constrict(idx, (level * params.scale * intensity).coerceIn(0.0, 1.0))
            }
        }
        synchronized(lock) {
            nextCon[idx] = ni
            if (pb) pbHolding = holding
        }
    }

    private data class Snapshot(val pos: Double, val actions: List<FunAction>, val params: SyncParams)

    data class LinearHold(val index: Int, val pos: Double)

    private fun SyncParams.normalized(): SyncParams = copy(
        scale = if (scale <= 0) 1.0 else scale,
        speed = if (speed <= 0) 0.9 else speed,
        minInterval = if (minInterval <= 0) 60 else minInterval,
        powerBlowMinStrokeMs = if (powerBlowMinStrokeMs <= 0) {
            DEFAULT_POWERBLOW_MIN_STROKE_MS
        } else {
            powerBlowMinStrokeMs
        },
    )
}

/** Drop BLE for edges this far behind the playhead (ms); keep virtual hold state. */
private const val PB_STALE_LAG_MS = 180.0

/** Index of last action at/before [t], stepped back one so the next edge arrives soon. */
private fun seedActionIndex(actions: List<FunAction>, t: Double): Int {
    if (actions.isEmpty()) return 0
    var lo = 0
    var hi = actions.lastIndex
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (actions[mid].at <= t) lo = mid else hi = mid - 1
    }
    return (lo - 1).coerceAtLeast(0)
}
