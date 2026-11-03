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
