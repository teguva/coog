package tv.coog.app.interactive

import kotlin.math.abs

/**
 * FeelConnect / Keon-style funscript → PowerBlow mapping (mirrors server).
 *
 * Oral (0=out … 100=deep):
 *   - withdrawal / upstroke (pos↓) → suction for segment dt
 *   - insertion  / downstroke (pos↑) → release
 *
 * Callers should [extendSameDirection] so staircase edges become one stroke,
 * then fire at stroke START minus a small BLE lead.
 */
data class PowerBlowStroke(
    val suck: Boolean,
    val level: Double,
    val durationMs: Long,
)

data class PowerBlowInterval(val startMs: Double, val endMs: Double)

data class PowerBlowGate(
    val mode: String = "",
    val intervals: List<PowerBlowInterval> = emptyList(),
    val minStrokeMs: Int = 0,
) {
    fun hasIntervals(): Boolean = intervals.isNotEmpty()

    fun manual(): Boolean = mode.equals("manual", ignoreCase = true) && intervals.isNotEmpty()

    fun activeAt(t: Double): Boolean {
        return intervals.any { iv ->
            val lo = minOf(iv.startMs, iv.endMs)
            val hi = maxOf(iv.startMs, iv.endMs)
            t in lo..hi
        }
    }
}

private const val PB_MIN_SUCK_POS = 8.0
private const val PB_MIN_VENT_POS = 2.5
private const val PB_TICK_MS = 50.0
/** Don't glue strokes across pauses longer than this. */
private const val PB_MERGE_GAP_MS = 800.0

internal const val DEFAULT_POWERBLOW_MIN_STROKE_MS = 0

/** Fire this many ms early to absorb BLE/queue latency. */
internal const val PB_BLE_LEAD_MS = 45.0

internal sealed class PowerBlowAdaptResult {
    data class Fire(val stroke: PowerBlowStroke) : PowerBlowAdaptResult()
    data class Skip(val reason: String) : PowerBlowAdaptResult()
}

/**
 * Extend [start] to the last index of a same-direction run so staircases
 * (90→70→40) become one suck/vent instead of stacked micro-pumps.
 */
internal fun extendSameDirection(actions: List<FunAction>, start: Int): Int {
    if (start < 0 || start >= actions.lastIndex) return start.coerceAtLeast(0)
    var end = start + 1
    val d0 = actions[end].pos - actions[start].pos
    if (abs(d0) < 1.0) return end
    while (end + 1 < actions.size) {
        val gap = actions[end + 1].at - actions[end].at
        if (gap > PB_MERGE_GAP_MS) break
        val d = actions[end + 1].pos - actions[end].pos
        if (d0 * d <= 0.0) break
        if (abs(d) < 1.0) break
        end++
    }
    return end
}

internal fun adaptPowerBlowStroke(
    curAt: Double,
    curPos: Double,
    nxtAt: Double,
    nxtPos: Double,
    invert: Boolean,
    scale: Double,
    intensity: Double,
    minStrokeMs: Double = DEFAULT_POWERBLOW_MIN_STROKE_MS.toDouble(),
    holding: Boolean = false,
    active: Boolean = true,
): PowerBlowStroke? = when (
    val r = adaptPowerBlowStrokeDiag(
        curAt, curPos, nxtAt, nxtPos, invert, scale, intensity, minStrokeMs, holding, active,
    )
) {
    is PowerBlowAdaptResult.Fire -> r.stroke
    is PowerBlowAdaptResult.Skip -> null
}

internal fun adaptPowerBlowStrokeDiag(
    curAt: Double,
    curPos: Double,
    nxtAt: Double,
    nxtPos: Double,
    invert: Boolean,
    scale: Double,
    intensity: Double,
    minStrokeMs: Double = DEFAULT_POWERBLOW_MIN_STROKE_MS.toDouble(),
    holding: Boolean = false,
    active: Boolean = true,
): PowerBlowAdaptResult {
    @Suppress("UNUSED_VARIABLE")
    val unusedMs = minStrokeMs
    if (!active) return PowerBlowAdaptResult.Skip("inactive")

    val dpos = nxtPos - curPos
    val dt = nxtAt - curAt
    if (dt <= 0) return PowerBlowAdaptResult.Skip("flat")

    var withdrawal = dpos < 0
    if (invert) withdrawal = !withdrawal

    // Intensity from full (merged) stroke amplitude — small strokes stay quiet.
    val amp = (abs(dpos) / 100.0).coerceAtMost(1.0)
    val level = (amp * scale * intensity).coerceIn(0.0, 1.0)

    var durMs = dt
    if (durMs < PB_TICK_MS) durMs = PB_TICK_MS
    if (durMs > 255 * PB_TICK_MS) durMs = 255 * PB_TICK_MS

    if (withdrawal) {
        if (holding) return PowerBlowAdaptResult.Skip("already-holding")
        if (abs(dpos) < PB_MIN_SUCK_POS) return PowerBlowAdaptResult.Skip("amp-small")
        return PowerBlowAdaptResult.Fire(
            PowerBlowStroke(suck = true, level = level, durationMs = durMs.toLong()),
        )
    }

    val minVent = if (holding) PB_MIN_VENT_POS else PB_MIN_SUCK_POS
    if (abs(dpos) < minVent) {
        return PowerBlowAdaptResult.Skip(if (holding) "vent-amp-tiny" else "amp-small")
    }
    return PowerBlowAdaptResult.Fire(
        PowerBlowStroke(suck = false, level = level, durationMs = durMs.toLong()),
    )
}
