package interactive

import (
	"math"
	"time"
)

// FeelConnect / Keon-style funscript → PowerBlow mapping.
//
// Oral convention (funscript 0=out … 100=deep):
//   - withdrawal / upstroke (pos falling) → suction 0x1401 for stroke dt
//   - insertion  / downstroke (pos rising) → release 0x1402
//
// Callers should ExtendSameDirection so staircases become one stroke, then
// fire at stroke START minus PbBleLeadMs.

const (
	powerblowMinSuckPos = 8.0
	powerblowMinVentPos = 2.5
	powerblowTickMs     = 50.0
	powerblowMergeGapMs = 800.0
	// PbBleLeadMs: fire early to absorb BLE/queue latency.
	PbBleLeadMs = 45.0
	// Kept for SyncParams / prefs compatibility.
	DefaultPowerBlowMinStrokeMs = 0
)

// PowerBlowStroke is one FeelConnect-style BLE event derived from a funscript edge.
type PowerBlowStroke struct {
	Suck     bool
	Level    float64
	Duration time.Duration
}

// ExtendSameDirection returns the last index of a same-direction run starting at start.
// Staircases (90→70→40) become one suck/vent instead of stacked micro-pumps.
func ExtendSameDirection(actions []Action, start int) int {
	if start < 0 || start >= len(actions)-1 {
		if start < 0 {
			return 0
		}
		return start
	}
	end := start + 1
	d0 := actions[end].Pos - actions[start].Pos
	if math.Abs(d0) < 1 {
		return end
	}
	for end+1 < len(actions) {
		gap := actions[end+1].At - actions[end].At
		if gap > powerblowMergeGapMs {
			break
		}
		d := actions[end+1].Pos - actions[end].Pos
		if d0*d <= 0 {
			break
		}
		if math.Abs(d) < 1 {
			break
		}
		end++
	}
	return end
}

// AdaptPowerBlowStroke maps one (possibly merged) funscript segment to suck/release.
// holding: skip suck if vacuum already held. active: manual gate.
func AdaptPowerBlowStroke(curAt, curPos, nxtAt, nxtPos float64, invert bool, scale, intensity float64, minStrokeMs float64, holding, active bool) (PowerBlowStroke, bool) {
	_ = minStrokeMs
	if !active {
		return PowerBlowStroke{}, false
	}

	dpos := nxtPos - curPos
	dt := nxtAt - curAt
	if dt <= 0 {
		return PowerBlowStroke{}, false
	}

	withdrawal := dpos < 0
	if invert {
		withdrawal = !withdrawal
	}

	amp := math.Abs(dpos) / 100.0
	if amp > 1 {
		amp = 1
	}
	level := clamp01(amp * scale * intensity)

	durMs := dt
	if durMs < powerblowTickMs {
		durMs = powerblowTickMs
	}
	if durMs > 255*powerblowTickMs {
		durMs = 255 * powerblowTickMs
	}

	if withdrawal {
		if holding {
			return PowerBlowStroke{}, false
		}
		if math.Abs(dpos) < powerblowMinSuckPos {
			return PowerBlowStroke{}, false
		}
		return PowerBlowStroke{
			Suck:     true,
			Level:    level,
			Duration: time.Duration(durMs) * time.Millisecond,
		}, true
	}

	minVent := powerblowMinSuckPos
	if holding {
		minVent = powerblowMinVentPos
	}
	if math.Abs(dpos) < minVent {
		return PowerBlowStroke{}, false
	}
	return PowerBlowStroke{
		Suck:     false,
		Level:    level,
		Duration: time.Duration(durMs) * time.Millisecond,
	}, true
}
