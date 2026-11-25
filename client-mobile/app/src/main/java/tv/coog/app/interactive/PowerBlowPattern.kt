package tv.coog.app.interactive

import android.content.Context
import kotlin.math.abs
import kotlin.math.exp
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/** Universal oral-activity mask (mirrors server powerblow_pattern.go). */
internal const val PB_PATTERN_REACTIVE_MS = 100
private const val PB_PATTERN_SAMPLE_MS = 200.0
private const val PB_PATTERN_ENTER_TICKS = 3
private const val PB_PATTERN_EXIT_TICKS = 4

internal data class PowerBlowActivityMask(
    val times: DoubleArray,
    val active: BooleanArray,
) {
    fun activeAt(t: Double): Boolean {
        if (times.isEmpty()) return false
        var i = times.binarySearch(t)
        if (i >= 0) return active[i]
        i = -i - 1
        if (i >= times.size) return active.last()
        if (i == 0) return active[0]
        return if (t - times[i - 1] <= times[i] - t) active[i - 1] else active[i]
    }
}

private data class PbModel(
    val winMs: Double,
    val longWinMs: Double,
    val mu: DoubleArray,
    val sd: DoubleArray,
    val w: DoubleArray,
    val b: Double,
    val threshold: Double,
)

private val cachedModel = AtomicReference<PbModel?>(null)

private fun loadPowerBlowModel(context: Context): PbModel {
    cachedModel.get()?.let { return it }
    val text = context.assets.open("powerblow_pattern_model.json").bufferedReader().use { it.readText() }
    val o = JSONObject(text)
    fun arr(key: String): DoubleArray {
        val a = o.getJSONArray(key)
        return DoubleArray(a.length()) { a.getDouble(it) }
    }
    val m = PbModel(
        winMs = o.optDouble("winMs", 2000.0),
        longWinMs = o.optDouble("longWinMs", 6000.0),
        mu = arr("mu"),
        sd = arr("sd"),
        w = arr("w"),
        b = o.optDouble("b", 0.0),
        threshold = o.optDouble("threshold", 0.62),
    )
    cachedModel.set(m)
    return m
}

internal fun buildPowerBlowActivityMask(actions: List<FunAction>, context: Context): PowerBlowActivityMask {
    if (actions.size < 8) return PowerBlowActivityMask(doubleArrayOf(), booleanArrayOf())
    val model = loadPowerBlowModel(context)
    val dur = actions.last().at
    if (dur < 1000) return PowerBlowActivityMask(doubleArrayOf(), booleanArrayOf())

    val times = ArrayList<Double>()
    val featRows = ArrayList<DoubleArray>()
    var t = 0.0
    while (t <= dur) {
        val f = extractFeatures(actions, t, model.winMs, model.longWinMs)
        if (f != null) {
            times.add(t)
            featRows.add(f)
        }
        t += PB_PATTERN_SAMPLE_MS
    }
    if (featRows.isEmpty()) return PowerBlowActivityMask(doubleArrayOf(), booleanArrayOf())

    val absN = 11
    val ranks = Array(featRows.size) { DoubleArray(absN) }
    for (col in 0 until absN) {
        val order = featRows.indices.sortedBy { featRows[it][col] }
        if (order.size == 1) {
            ranks[order[0]][col] = 0.5
        } else {
            for ((rank, idx) in order.withIndex()) {
                ranks[idx][col] = rank.toDouble() / (order.size - 1)
            }
        }
    }

    val probs = DoubleArray(featRows.size) { i ->
        val full = DoubleArray(absN * 2)
        for (c in 0 until absN) {
            full[c] = featRows[i][c]
            full[absN + c] = ranks[i][c]
        }
        logit(full, model)
    }

    val active = BooleanArray(times.size)
    var on = false
    var enter = 0
    var exit = 0
    for (i in probs.indices) {
        val hit = probs[i] >= model.threshold
        if (!on) {
            enter = if (hit) enter + 1 else 0
            if (enter >= PB_PATTERN_ENTER_TICKS) {
                on = true
                exit = 0
            }
        } else {
            exit = if (!hit) exit + 1 else 0
            if (exit >= PB_PATTERN_EXIT_TICKS) {
                on = false
                enter = 0
            }
        }
        active[i] = on
    }
    return PowerBlowActivityMask(times.toDoubleArray(), active)
}

private fun extractFeatures(actions: List<FunAction>, t: Double, win: Double, longWin: Double): DoubleArray? {
    val s = windowStats(actions, t, win) ?: return null
    val l = windowStats(actions, t, longWin) ?: return null
    return doubleArrayOf(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], l[3], l[0], l[6])
}

/** busy,speed,amp,low,mid,high,energy,flip */
private fun windowStats(actions: List<FunAction>, t: Double, win: Double): DoubleArray? {
    val lo = t - win
    val hi = t + win
    val pts = actions.filter { it.at in lo..hi }
    if (pts.size < 4) return null
    val span = (pts.last().at - pts.first().at).coerceAtLeast(1.0)
    var busyMs = 0.0
    var ampSum = 0.0
    var speedSum = 0.0
    var nSeg = 0
    var lowN = 0
    var midN = 0
    var highN = 0
    var flips = 0
    for (p in pts) {
        when {
            p.pos <= 30 -> lowN++
            p.pos >= 75 -> highN++
            else -> midN++
        }
    }
    for (i in 0 until pts.lastIndex) {
        val dt = (pts[i + 1].at - pts[i].at).coerceAtLeast(1.0)
        val amp = abs(pts[i + 1].pos - pts[i].pos)
        nSeg++
        ampSum += amp
        speedSum += amp / dt * 1000.0
        if (amp >= 8 && dt < 320) busyMs += dt
    }
    for (i in 1 until pts.lastIndex) {
        val d0 = pts[i].pos - pts[i - 1].pos
        val d1 = pts[i + 1].pos - pts[i].pos
        if (d0 * d1 < 0 && abs(d0) + abs(d1) >= 12) flips++
    }
    if (nSeg == 0) return null
    val n = pts.size.toDouble()
    return doubleArrayOf(
        busyMs / span,
        speedSum / nSeg,
        ampSum / nSeg,
        lowN / n,
        midN / n,
        highN / n,
        ampSum / (span / 1000.0),
        flips / (span / 1000.0),
    )
}

private fun logit(feats: DoubleArray, model: PbModel): Double {
    if (feats.size != model.w.size) return 0.0
    var z = model.b
    for (i in feats.indices) {
        val sd = if (model.sd[i] < 1e-6) 1.0 else model.sd[i]
        z += model.w[i] * ((feats[i] - model.mu[i]) / sd)
    }
    return when {
        z > 20 -> 1.0
        z < -20 -> 0.0
        else -> 1.0 / (1.0 + exp(-z))
    }
}
