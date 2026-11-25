package interactive

import (
	"math"
	"strings"
)

// Solace Pro / BLE Linear: match DeviceMessageTimingGap + FSetSite seeker step.
const (
	LinearLeadMs         = 100.0
	LinearGapMs          = 100
	LinearDurMs          = 100
	LinearStrokeInset    = 0.94
	LinearEnvelopeHalfMs = 500.0

	// Handy (FW4+ / Handy 2): one Hdsp seek per scripted half-stroke (not continuous
	// sampling). Duration = tip Δt; short fixed seeks cause repeated moves.
	HandyLinearLeadMs      = 70.0
	HandyLinearGapMs       = 0 // tip driver is paced by script tips, not wall clock
	HandyLinearDurMs       = 160 // fallback only; stroke driver uses tip Δt
	HandyLinearStrokeInset = 0.96
	HandyDefaultOffsetMs   = 150
	HandyMinStrokeDelta    = 1.0 // script pos units 0-100; keep small moves
	HandyMinStrokeDurMs    = 50  // match LinearCmd floor
	HandyMaxStrokeDurMs    = 2500
	SolaceDefaultOffsetMs  = 350
)

// LinearDriveProfile is per-brand continuous linear tracking.
type LinearDriveProfile struct {
	LeadMs    float64
	GapMs     int64
	DurMs     int
	Inset     float64
	OffsetMs  int
}

func IsHandyName(name string) bool {
	return strings.Contains(strings.ToLower(strings.TrimSpace(name)), "handy")
}

func LinearProfileFor(name string) LinearDriveProfile {
	if IsHandyName(name) {
		return LinearDriveProfile{
			LeadMs:   HandyLinearLeadMs,
			GapMs:    HandyLinearGapMs,
			DurMs:    HandyLinearDurMs,
			Inset:    HandyLinearStrokeInset,
			OffsetMs: HandyDefaultOffsetMs,
		}
	}
	return LinearDriveProfile{
		LeadMs:   LinearLeadMs,
		GapMs:    LinearGapMs,
		DurMs:    LinearDurMs,
		Inset:    LinearStrokeInset,
		OffsetMs: SolaceDefaultOffsetMs,
	}
}

func DefaultOffsetMsFor(name, kind string) int {
	if kind == "linear" || IsHandyName(name) {
		return LinearProfileFor(name).OffsetMs
	}
	return SolaceDefaultOffsetMs
}

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
// so Solace coast does not fly past scripted stroke length. Handy uses inset 1.0.
func actionPosLinearTarget(actions []Action, t, leadMs, inset float64) float64 {
	if len(actions) == 0 {
		return 0
	}
	if inset <= 0 {
		inset = LinearStrokeInset
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
	if hi-lo >= 2.0 && inset < 0.999 {
		mid := (lo + hi) / 2.0
		raw = mid + (raw-mid)*inset
	}
	return raw
}

// LinearStrokeTip is the next half-stroke extremum after playhead t.
type LinearStrokeTip struct {
	Pos    float64 // script 0-100 after inset
	At     float64
	Idx    int
	DurMs  int
}

// nextLinearStrokeTip finds the next local max/min after t (min |Δpos|).
// Handy should seek this tip once with DurMs = tip.At - t, not sample mid-stroke.
func nextLinearStrokeTip(actions []Action, t, minDelta, inset float64) (LinearStrokeTip, bool) {
	var none LinearStrokeTip
	if len(actions) < 2 {
		return none, false
	}
	if minDelta <= 0 {
		minDelta = HandyMinStrokeDelta
	}
	if inset <= 0 {
		inset = HandyLinearStrokeInset
	}
	cur := actionPosAt(actions, t)
	i := nextActionIndex(actions, t, 0)
	if i < len(actions) && actions[i].At <= t {
		i++
	}
	if i >= len(actions) {
		return none, false
	}

	// Prefer a true direction reverse (peak/valley), including plateau ends.
	tip := -1
	for j := max(i, 1); j < len(actions)-1; j++ {
		p0 := actions[j-1].Pos
		p1 := actions[j].Pos
		p2 := actions[j+1].Pos
		d0 := p1 - p0
		d1 := p2 - p1
		rose := d0 >= minDelta
		fell := d0 <= -minDelta
		if !rose && !fell && math.Abs(d0) < minDelta {
			for k := j - 1; k >= 0; k-- {
				dd := p1 - actions[k].Pos
				if dd >= minDelta {
					rose = true
					break
				}
				if dd <= -minDelta {
					fell = true
					break
				}
			}
		}
		peak := rose && d1 <= -minDelta
		valley := fell && d1 >= minDelta
		if peak || valley {
			tip = j
			break
		}
	}

	// No reverse ahead — drive to the next point with a real position change, else last.
	if tip < 0 {
		tip = len(actions) - 1
		for j := i; j < len(actions); j++ {
			if math.Abs(actions[j].Pos-cur) >= minDelta {
				tip = j
				break
			}
		}
	}

	tipPos := actions[tip].Pos
	tipAt := actions[tip].At
	if tipAt <= t {
		return none, false
	}
	// Soft-inset tip toward mid of this half-stroke.
	lo := math.Min(cur, tipPos)
	hi := math.Max(cur, tipPos)
	if hi-lo >= 2.0 && inset < 0.999 {
		mid := (lo + hi) / 2.0
		tipPos = mid + (tipPos-mid)*inset
	}
	dur := int(tipAt - t + 0.5)
	if dur < HandyMinStrokeDurMs {
		dur = HandyMinStrokeDurMs
	}
	if dur > HandyMaxStrokeDurMs {
		dur = HandyMaxStrokeDurMs
	}
	return LinearStrokeTip{Pos: tipPos, At: tipAt, Idx: tip, DurMs: dur}, true
}
