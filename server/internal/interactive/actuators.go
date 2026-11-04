package interactive

import "math"

// Solace Pro / BLE Linear: match DeviceMessageTimingGap + FSetSite seeker step.
const (
	LinearLeadMs         = 100.0
	LinearGapMs          = 100
	LinearDurMs          = 100
	LinearStrokeInset    = 0.94
	LinearEnvelopeHalfMs = 500.0
)

func mapLinearPos(pos, intensityFactor float64) float64 {
	// Funplay: expand/shrink stroke around mid (0.5) by intensity factor (may be >1).
	p := 0.5 + (pos-0.5)*intensityFactor
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

// actionPosLinearTarget: lead sample that aims at stroke extrema crossed in the
// lead window (not past them), then clamps + soft-insets into the local envelope
// so Solace coast does not fly past scripted stroke length.
func actionPosLinearTarget(actions []Action, t, leadMs float64) float64 {
	if len(actions) == 0 {
		return 0
	}
	t1 := t + leadMs
	p0 := actionPosAt(actions, t)
	p1 := actionPosAt(actions, t1)

	var extremum float64
	hasExt := false
	prevP := p0
	rising := 0 // 0 unset, 1 up, -1 down

	step := func(pos float64) {
		up := pos > prevP+1e-9
		down := pos < prevP-1e-9
		switch {
		case rising == 0 && up:
			rising = 1
		case rising == 0 && down:
			rising = -1
		case rising == 1 && down:
			extremum = prevP
			hasExt = true
			rising = -1
		case rising == -1 && up:
			extremum = prevP
			hasExt = true
			rising = 1
		}
		prevP = pos
	}

	i := 0
	for i < len(actions) && actions[i].At <= t {
		i++
	}
	for i < len(actions) && actions[i].At <= t1 {
		step(actions[i].Pos)
		i++
	}
	step(p1)

	raw := p1
	if hasExt {
		raw = extremum
	}

	lo := math.Min(math.Min(p0, p1), raw)
	hi := math.Max(math.Max(p0, p1), raw)
	tLo := t - LinearEnvelopeHalfMs
	tHi := t + LinearEnvelopeHalfMs
	j := 0
	for j < len(actions) && actions[j].At < tLo {
		j++
	}
	for j < len(actions) && actions[j].At <= tHi {
		lo = math.Min(lo, actions[j].Pos)
		hi = math.Max(hi, actions[j].Pos)
		j++
	}
	if raw < lo {
		raw = lo
	}
	if raw > hi {
		raw = hi
	}
	if hi-lo >= 2.0 {
		mid := (lo + hi) / 2.0
		raw = mid + (raw-mid)*LinearStrokeInset
	}
	return raw
}
