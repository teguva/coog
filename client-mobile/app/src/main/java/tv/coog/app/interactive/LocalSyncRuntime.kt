package tv.coog.app.interactive

import kotlin.math.abs

/**
 * Funscript drive loop for on-device Buttplug (mirrors server SyncRuntime.tick).
 * Clock comes from ExoPlayer via [setClock].
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

    private val nextLin = mutableMapOf<Int, Int>()
    private val nextCon = mutableMapOf<Int, Int>()
    private val lastVib = mutableMapOf<Int, Double>()

    fun load(actions: List<FunAction>, params: SyncParams = SyncParams(), resumeMs: Double = 0.0) {
        synchronized(lock) {
            this.actions = actions
            this.params = params.normalized()
            loaded = actions.isNotEmpty()
            playing = false
            posMs = resumeMs
            nextLin.clear()
            nextCon.clear()
            lastVib.clear()
        }
    }

    fun clear() {
        synchronized(lock) {
            actions = emptyList()
            loaded = false
            playing = false
            nextLin.clear()
            nextCon.clear()
            lastVib.clear()
        }
        bp.stopAll()
    }

    fun setClock(posMs: Double, playing: Boolean, seek: Boolean) {
        val halt: Boolean
        synchronized(lock) {
            if (seek) {
                nextLin.clear()
                nextCon.clear()
            }
            val was = this.playing
            this.posMs = posMs
            this.playing = playing
            halt = was && !playing
        }
        if (halt) bp.stopAll()
    }

    fun tick() {
        val snapshot: Snapshot
        synchronized(lock) {
            if (!loaded || !playing || actions.isEmpty()) return
            snapshot = Snapshot(posMs, actions, params)
        }
        for (d in bp.devices()) {
            val intensity = prefs.intensity(d.deviceId) / 100.0
            val t = snapshot.pos + prefs.offsetMs(d.deviceId)
            when (d.kind) {
                "linear" -> driveLinear(d.index, t, snapshot.actions, snapshot.params, intensity)
                "constrict" -> driveConstrict(d.index, t, snapshot.actions, snapshot.params, intensity)
                else -> driveScalar(d.index, t, snapshot.actions, snapshot.params, intensity)
            }
        }
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
    ) {
        if (actions.size < 2) return
        var ni = synchronized(lock) { nextLin[idx] ?: 0 }
        val minInterval = params.minInterval.coerceAtLeast(50)
        val speed = if (params.speed <= 0) 0.9 else params.speed
        val refVel = 0.3
        while (ni < actions.size && actions[ni].at <= t) {
            val cur = actions[ni]
            var tgt = ni + 1
            while (tgt < actions.size && (actions[tgt].at - cur.at) < minInterval) tgt++
            if (tgt >= actions.size) {
                var target = mapLinearPos(cur.pos / 100.0, intensity)
                if (params.invert) target = 1.0 - target
                bp.linear(idx, target.coerceIn(0.0, 1.0), 1000)
                synchronized(lock) { nextLin[idx] = actions.size }
                return
            }
            val nxt = actions[tgt]
            val interval = nxt.at - cur.at
            val delta = abs(nxt.pos - cur.pos)
            var fastness = (delta / maxOf(1.0, interval)) / refVel
            if (fastness > 1) fastness = 1.0
            val mult = 1.0 - (1.0 - speed) * fastness
            var dur = (interval * mult).toInt()
            if (dur < 50) dur = 50
            var target = mapLinearPos(nxt.pos / 100.0, intensity)
            if (params.invert) target = 1.0 - target
            bp.linear(idx, target.coerceIn(0.0, 1.0), dur)
            ni = tgt
        }
        synchronized(lock) { nextLin[idx] = ni }
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

    private fun SyncParams.normalized(): SyncParams = copy(
        scale = if (scale <= 0) 1.0 else scale,
        speed = if (speed <= 0) 0.9 else speed,
        minInterval = if (minInterval <= 0) 60 else minInterval,
    )
}
