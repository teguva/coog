package tv.coog.app.interactive

import kotlin.math.abs

data class FunAction(val at: Double, val pos: Double)

data class SyncParams(
    val offsetMs: Int = 350,
    val scale: Double = 1.0,
    val speed: Double = 0.9,
    val minInterval: Int = 60,
    val invert: Boolean = false,
    /** Ignore PowerBlow edges shorter than this (slow-stroke filter). 0 → default. */
    val powerBlowMinStrokeMs: Int = DEFAULT_POWERBLOW_MIN_STROKE_MS,
)

/** Solace Pro / BLE Linear: match DeviceMessageTimingGap + FSetSite seeker step. */
internal const val LINEAR_LEAD_MS = 100.0
internal const val LINEAR_GAP_MS = 100L
internal const val LINEAR_DUR_MS = 100
/** Keep commands inside authored stroke so Solace coast lands on scripted extrema. */
internal const val LINEAR_STROKE_INSET = 0.94
internal const val LINEAR_ENVELOPE_HALF_MS = 500.0

/**
 * Handy 2: one Hdsp seek per scripted half-stroke (duration = tip Δt).
 * Continuous short seeks chop a single stroke into repeated moves.
 */
internal const val HANDY_LINEAR_LEAD_MS = 70.0
internal const val HANDY_LINEAR_GAP_MS = 80L
internal const val HANDY_LINEAR_DUR_MS = 160 // fallback only
internal const val HANDY_LINEAR_STROKE_INSET = 0.96
internal const val HANDY_DEFAULT_OFFSET_MS = 150
internal const val HANDY_MIN_STROKE_DELTA = 5.0
internal const val HANDY_MIN_STROKE_DUR_MS = 90
internal const val HANDY_MAX_STROKE_DUR_MS = 2500
internal const val SOLACE_DEFAULT_OFFSET_MS = 350

internal data class LinearDriveProfile(
    val leadMs: Double,
    val gapMs: Long,
    val durMs: Int,
    val inset: Double,
    val offsetMs: Int,
)

internal fun isHandyName(name: String): Boolean =
    name.contains("handy", ignoreCase = true)

internal fun linearProfileFor(name: String): LinearDriveProfile =
    if (isHandyName(name)) {
        LinearDriveProfile(
            leadMs = HANDY_LINEAR_LEAD_MS,
            gapMs = HANDY_LINEAR_GAP_MS,
            durMs = HANDY_LINEAR_DUR_MS,
            inset = HANDY_LINEAR_STROKE_INSET,
            offsetMs = HANDY_DEFAULT_OFFSET_MS,
        )
    } else {
        LinearDriveProfile(
            leadMs = LINEAR_LEAD_MS,
            gapMs = LINEAR_GAP_MS,
            durMs = LINEAR_DUR_MS,
            inset = LINEAR_STROKE_INSET,
            offsetMs = SOLACE_DEFAULT_OFFSET_MS,
        )
    }

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


internal data class LinearStrokeTip(
    val pos: Double, // script 0-100 after inset
    val at: Double,
    val idx: Int,
    val durMs: Int,
)

/** Next local max/min after [t]; Handy seeks this tip once per half-stroke. */
internal fun nextLinearStrokeTip(
    actions: List<FunAction>,
    t: Double,
    minDelta: Double = HANDY_MIN_STROKE_DELTA,
    inset: Double = HANDY_LINEAR_STROKE_INSET,
): LinearStrokeTip? {
    if (actions.size < 2) return null
    val cur = actionPosAt(actions, t)
    var i = 0
    while (i < actions.size && actions[i].at < t) i++
    if (i >= actions.size) return null
    var rising = 0 // 0 unset, 1 up, -1 down
    var prev = cur
    var tip = i
    var j = i
    while (j < actions.size) {
        val p = actions[j].pos
        val up = p > prev + minDelta
        val down = p < prev - minDelta
        when {
            rising == 0 -> {
                if (up) rising = 1
                else if (down) rising = -1
                tip = j
                prev = p
            }
            rising == 1 && down -> break
            rising == -1 && up -> break
            else -> {
                tip = j
                prev = p
            }
        }
        j++
    }
    val tipAt = actions[tip].at
    if (tipAt <= t) return null
    var tipPos = actions[tip].pos
    val lo = minOf(cur, tipPos)
    val hi = maxOf(cur, tipPos)
    if (hi - lo >= 2.0 && inset < 0.999) {
        val mid = (lo + hi) / 2.0
        tipPos = mid + (tipPos - mid) * inset
    }
    var dur = (tipAt - t + 0.5).toInt()
    if (dur < HANDY_MIN_STROKE_DUR_MS) dur = HANDY_MIN_STROKE_DUR_MS
    if (dur > HANDY_MAX_STROKE_DUR_MS) dur = HANDY_MAX_STROKE_DUR_MS
    return LinearStrokeTip(pos = tipPos, at = tipAt, idx = tip, durMs = dur)
}

/**
 * Linear aim with lead: if the lead window crosses a stroke top/bottom, target that
 * extremum instead of a past-it sample; clamp + soft-inset into the local envelope
 * so physical coast does not fly past scripted stroke length. Handy uses inset 1.0.
 */
internal fun actionPosLinearTarget(
    actions: List<FunAction>,
    t: Double,
    leadMs: Double,
    inset: Double = LINEAR_STROKE_INSET,
): Double {
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
    if (hi - lo >= 2.0 && inset < 0.999) {
        val mid = (lo + hi) / 2.0
        raw = mid + (raw - mid) * inset
    }
    return raw
}
