package interactive

import (
	_ "embed"
	"encoding/json"
	"math"
	"sort"
)

//go:embed powerblow_pattern_model.json
var powerblowPatternModelJSON []byte

// Universal oral-activity model trained from labeled vs paused funscript sections.
// Uses absolute features + script-relative ranks (so each title is scored against
// its own stroke distribution — not a per-file gate list).

const (
	pbPatternReactiveMs = 100
	pbPatternSampleMs   = 200.0
	pbPatternEnterTicks = 3
	pbPatternExitTicks  = 4
)

type pbModelFile struct {
	WinMs      float64   `json:"winMs"`
	LongWinMs  float64   `json:"longWinMs"`
	Features   []string  `json:"features"`
	Mu         []float64 `json:"mu"`
	Sd         []float64 `json:"sd"`
	W          []float64 `json:"w"`
	B          float64   `json:"b"`
	Threshold  float64   `json:"threshold"`
}

var pbModel = loadPBModel()

func loadPBModel() pbModelFile {
	var m pbModelFile
	if err := json.Unmarshal(powerblowPatternModelJSON, &m); err != nil {
		// Safe fallback — never panic on embed parse failure.
		return pbModelFile{WinMs: 2000, LongWinMs: 6000, Threshold: 0.62}
	}
	if m.WinMs <= 0 {
		m.WinMs = 2000
	}
	if m.LongWinMs <= 0 {
		m.LongWinMs = 6000
	}
	if m.Threshold <= 0 {
		m.Threshold = 0.62
	}
	return m
}

// PowerBlowActivityMask is a precomputed oral-activity timeline for one funscript.
type PowerBlowActivityMask struct {
	times  []float64
	active []bool
}

func (m *PowerBlowActivityMask) ActiveAt(t float64) bool {
	if m == nil || len(m.times) == 0 {
		return false
	}
	i := sort.Search(len(m.times), func(i int) bool { return m.times[i] >= t })
	if i >= len(m.times) {
		return m.active[len(m.active)-1]
	}
	if i == 0 {
		return m.active[0]
	}
	if t-m.times[i-1] <= m.times[i]-t {
		return m.active[i-1]
	}
	return m.active[i]
}

// BuildPowerBlowActivityMask scores the whole script with the universal model
// (script-relative ranks) and applies hysteresis.
func BuildPowerBlowActivityMask(actions []Action) *PowerBlowActivityMask {
	if len(actions) < 8 {
		return &PowerBlowActivityMask{}
	}
	dur := actions[len(actions)-1].At
	if dur < 1000 {
		return &PowerBlowActivityMask{}
	}
	step := pbPatternSampleMs
	n := int(dur/step) + 1
	raw := make([]float64, 0, n) // logistic probability
	times := make([]float64, 0, n)
	featRows := make([][]float64, 0, n)

	for t := 0.0; t <= dur; t += step {
		f, ok := extractPBFeatures(actions, t, pbModel.WinMs, pbModel.LongWinMs)
		if !ok {
			continue
		}
		times = append(times, t)
		featRows = append(featRows, f)
	}
	if len(featRows) == 0 {
		return &PowerBlowActivityMask{}
	}

	// Script-relative ranks for each absolute feature column.
	absN := 11 // busy..energyL
	ranks := make([][]float64, len(featRows))
	for i := range ranks {
		ranks[i] = make([]float64, absN)
	}
	for col := 0; col < absN; col++ {
		type pair struct {
			i int
			v float64
		}
		ps := make([]pair, len(featRows))
		for i, row := range featRows {
			ps[i] = pair{i, row[col]}
		}
		sort.Slice(ps, func(a, b int) bool { return ps[a].v < ps[b].v })
		if len(ps) == 1 {
			ranks[ps[0].i][col] = 0.5
			continue
		}
		for rank, p := range ps {
			ranks[p.i][col] = float64(rank) / float64(len(ps)-1)
		}
	}

	for i, row := range featRows {
		full := make([]float64, 0, absN*2)
		full = append(full, row...)
		full = append(full, ranks[i]...)
		raw = append(raw, pbLogit(full))
	}

	// Hysteresis on probability ≥ threshold.
	active := make([]bool, len(times))
	on := false
	enter, exit := 0, 0
	for i, p := range raw {
		hit := p >= pbModel.Threshold
		if !on {
			if hit {
				enter++
			} else {
				enter = 0
			}
			if enter >= pbPatternEnterTicks {
				on = true
				exit = 0
			}
		} else {
			if !hit {
				exit++
			} else {
				exit = 0
			}
			if exit >= pbPatternExitTicks {
				on = false
				enter = 0
			}
		}
		active[i] = on
	}
	return &PowerBlowActivityMask{times: times, active: active}
}

// extractPBFeatures returns the 11 absolute features matching the trained model.
func extractPBFeatures(actions []Action, t, win, longWin float64) ([]float64, bool) {
	short, ok := windowStats(actions, t, win)
	if !ok {
		return nil, false
	}
	long, ok := windowStats(actions, t, longWin)
	if !ok {
		return nil, false
	}
	return []float64{
		short.busy, short.speed, short.amp, short.low, short.mid, short.high,
		short.energy, short.flip, long.low, long.busy, long.energy,
	}, true
}

type pbWinStats struct {
	busy, speed, amp, low, mid, high, energy, flip float64
}

func windowStats(actions []Action, t, win float64) (pbWinStats, bool) {
	lo, hi := t-win, t+win
	i0 := sort.Search(len(actions), func(i int) bool { return actions[i].At >= lo })
	pts := make([]Action, 0, 48)
	for i := i0; i < len(actions); i++ {
		if actions[i].At > hi {
			break
		}
		pts = append(pts, actions[i])
	}
	if len(pts) < 4 {
		return pbWinStats{}, false
	}
	span := pts[len(pts)-1].At - pts[0].At
	if span < 1 {
		span = 1
	}
	var busyMs, ampSum, speedSum float64
	var lowN, midN, highN, flips, nSeg int
	for _, p := range pts {
		switch {
		case p.Pos <= 30:
			lowN++
		case p.Pos >= 75:
			highN++
		default:
			midN++
		}
	}
	for i := 0; i < len(pts)-1; i++ {
		dt := pts[i+1].At - pts[i].At
		if dt < 1 {
			dt = 1
		}
		amp := math.Abs(pts[i+1].Pos - pts[i].Pos)
		nSeg++
		ampSum += amp
		speedSum += amp / dt * 1000
		if amp >= 8 && dt < 320 {
			busyMs += dt
		}
	}
	for i := 1; i < len(pts)-1; i++ {
		d0 := pts[i].Pos - pts[i-1].Pos
		d1 := pts[i+1].Pos - pts[i].Pos
		if d0*d1 < 0 && math.Abs(d0)+math.Abs(d1) >= 12 {
			flips++
		}
	}
	if nSeg == 0 {
		return pbWinStats{}, false
	}
	n := float64(len(pts))
	return pbWinStats{
		busy:   busyMs / span,
		speed:  speedSum / float64(nSeg),
		amp:    ampSum / float64(nSeg),
		low:    float64(lowN) / n,
		mid:    float64(midN) / n,
		high:   float64(highN) / n,
		energy: ampSum / (span / 1000),
		flip:   float64(flips) / (span / 1000),
	}, true
}

func pbLogit(feats []float64) float64 {
	if len(pbModel.W) == 0 || len(feats) != len(pbModel.W) {
		// Fallback heuristic if model mismatch.
		if len(feats) >= 4 {
			busy, speed, low, mid := feats[0], feats[1], feats[3], feats[4]
			if busy >= 0.5 && speed >= 120 && low >= 0.18 && mid <= 0.35 {
				return 0.8
			}
		}
		return 0
	}
	z := pbModel.B
	for i, x := range feats {
		sd := pbModel.Sd[i]
		if sd < 1e-6 {
			sd = 1
		}
		z += pbModel.W[i] * ((x - pbModel.Mu[i]) / sd)
	}
	// sigmoid
	if z > 20 {
		return 1
	}
	if z < -20 {
		return 0
	}
	return 1 / (1 + math.Exp(-z))
}

// PowerBlowPatternHysteresis kept for mobile parity / tests.
type PowerBlowPatternHysteresis struct {
	Active bool
	enter  int
	exit   int
}

func (h *PowerBlowPatternHysteresis) Reset() {
	h.Active = false
	h.enter = 0
	h.exit = 0
}

func (h *PowerBlowPatternHysteresis) Observe(raw bool) bool {
	if !h.Active {
		if raw {
			h.enter++
		} else {
			h.enter = 0
		}
		if h.enter >= pbPatternEnterTicks {
			h.Active = true
			h.exit = 0
		}
	} else {
		if !raw {
			h.exit++
		} else {
			h.exit = 0
		}
		if h.exit >= pbPatternExitTicks {
			h.Active = false
			h.enter = 0
		}
	}
	return h.Active
}
