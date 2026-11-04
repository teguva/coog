package tv.coog.app.interactive

import kotlin.math.abs

data class FunAction(val at: Double, val pos: Double)

data class SyncParams(
    val offsetMs: Int = 350,
    val scale: Double = 1.0,
    val speed: Double = 0.9,
    val minInterval: Int = 60,
    val invert: Boolean = false,
)

/** Solace Pro / BLE Linear: match DeviceMessageTimingGap + FSetSite seeker step. */
internal const val LINEAR_LEAD_MS = 100.0
internal const val LINEAR_GAP_MS = 100L
internal const val LINEAR_DUR_MS = 100
/** Keep commands inside authored stroke so Solace coast lands on scripted extrema. */
internal const val LINEAR_STROKE_INSET = 0.94
internal const val LINEAR_ENVELOPE_HALF_MS = 500.0

internal fun mapLinearPos(pos: Double, intensityFactor: Double): Double {
    val p = 0.5 + (pos - 0.5) * intensityFactor
    return p.coerceIn(0.0, 1.0)
}

internal fun vibLevelAt(pos01: Double): Double = (1.0 - pos01).coerceIn(0.0, 1.0)

internal fun constrictStrokeLevel(curPos: Double, nxtPos: Double, invert: Boolean): Pair<Double, Boolean> {
    val dpos = nxtPos - curPos
    if (abs(dpos) < 2.0) return 0.0 to false
    var goingIn = dpos > 0
    if (invert) goingIn = !goingIn
    return if (goingIn) 1.0 to true else 0.0 to true
}

internal fun actionPosAt(actions: List<FunAction>, t: Double): Double {
    if (actions.isEmpty()) return 0.0
    if (t <= actions.first().at) return actions.first().pos
    if (t >= actions.last().at) return actions.last().pos
    var lo = 0
    var hi = actions.lastIndex
    while (lo + 1 < hi) {
        val mid = (lo + hi) / 2
        if (actions[mid].at <= t) lo = mid else hi = mid
    }
    val a0 = actions[lo]
    val a1 = actions[lo + 1]
    val dt = a1.at - a0.at
    if (dt <= 0) return a0.pos
    val frac = (t - a0.at) / dt
    return a0.pos + frac * (a1.pos - a0.pos)
}

/**
 * Linear aim with lead: if the lead window crosses a stroke top/bottom, target that
 * extremum instead of a past-it sample; clamp + soft-inset into the local envelope
 * so physical coast does not fly past scripted stroke length.
 */
internal fun actionPosLinearTarget(actions: List<FunAction>, t: Double, leadMs: Double): Double {
    if (actions.isEmpty()) return 0.0
    val t1 = t + leadMs
    val p0 = actionPosAt(actions, t)
    val p1 = actionPosAt(actions, t1)

    var extremum: Double? = null
    var prevP = p0
    var rising: Boolean? = null

    fun step(pos: Double) {
        val up = pos > prevP + 1e-9
        val down = pos < prevP - 1e-9
        when {
            rising == null && up -> rising = true
            rising == null && down -> rising = false
            rising == true && down -> {
                extremum = prevP
                rising = false
            }
            rising == false && up -> {
                extremum = prevP
                rising = true
            }
        }
        prevP = pos
    }

    var i = 0
    while (i < actions.size && actions[i].at <= t) i++
    while (i < actions.size && actions[i].at <= t1) {
        step(actions[i].pos)
        i++
    }
    step(p1)

    var raw = extremum ?: p1

    var lo = minOf(p0, p1, raw)
    var hi = maxOf(p0, p1, raw)
    var j = 0
    val tLo = t - LINEAR_ENVELOPE_HALF_MS
    val tHi = t + LINEAR_ENVELOPE_HALF_MS
    while (j < actions.size && actions[j].at < tLo) j++
    while (j < actions.size && actions[j].at <= tHi) {
        lo = minOf(lo, actions[j].pos)
        hi = maxOf(hi, actions[j].pos)
        j++
    }
    raw = raw.coerceIn(lo, hi)
    if (hi - lo >= 2.0) {
        val mid = (lo + hi) / 2.0
        raw = mid + (raw - mid) * LINEAR_STROKE_INSET
    }
    return raw
}
