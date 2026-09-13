package interactive

import "math"

func mapLinearPos(pos, intensityFactor float64) float64 {
	// Expand/shrink stroke around mid (0.5) by intensity factor.
	mid := 0.5
	span := 0.5 * intensityFactor
	if span > 0.5 {
		span = 0.5
	}
	p := mid + (pos-0.5)*(span/0.5)
	if p < 0 {
		return 0
	}
	if p > 1 {
		return 1
	}
	return p
}

func vibLevelAt(pos01 float64) float64 {
	// Funplay: vibrate level = 1 - pos/100
	v := 1.0 - pos01
	if v < 0 {
		return 0
	}
	if v > 1 {
		return 1
	}
	return v
}

func constrictStrokeLevel(curPos, nxtPos float64, invert bool) (float64, bool) {
	dpos := nxtPos - curPos
	if math.Abs(dpos) < 2.0 {
		return 0, false
	}
	goingIn := dpos > 0
	if invert {
		goingIn = !goingIn
	}
	if goingIn {
		return 1.0, true
	}
	return 0.0, true
}

func clamp01(v float64) float64 {
	if v < 0 {
		return 0
	}
	if v > 1 {
		return 1
	}
	return v
}

func actionPosAt(actions []Action, t float64) float64 {
	if len(actions) == 0 {
		return 0
	}
	if t <= actions[0].At {
		return actions[0].Pos
	}
	if t >= actions[len(actions)-1].At {
		return actions[len(actions)-1].Pos
	}
	lo, hi := 0, len(actions)-1
	for lo+1 < hi {
		mid := (lo + hi) / 2
		if actions[mid].At <= t {
			lo = mid
		} else {
			hi = mid
		}
	}
	a0, a1 := actions[lo], actions[lo+1]
	dt := a1.At - a0.At
	if dt <= 0 {
		return a0.Pos
	}
	frac := (t - a0.At) / dt
	return a0.Pos + frac*(a1.Pos-a0.Pos)
}

func nextActionIndex(actions []Action, t float64, from int) int {
	i := from
	if i < 0 {
		i = 0
	}
	for i < len(actions) && actions[i].At < t {
		i++
	}
	return i
}
