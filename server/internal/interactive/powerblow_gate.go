package interactive

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
)

// PowerBlowGate is an optional companion <funscript>.powerblow.json.
//
// Default playback uses the universal oral-activity PATTERN (see powerblow_pattern.go).
// Set "mode":"manual" to force suck only inside exported learner intervals instead.
type PowerBlowGate struct {
	Mode      string              `json:"mode"` // ""|"auto" → pattern; "manual" → intervals
	Intervals []PowerBlowInterval `json:"intervals"`
	Params    PowerBlowGateParams `json:"params"`
}

type PowerBlowInterval struct {
	StartMs float64 `json:"startMs"`
	EndMs   float64 `json:"endMs"`
}

type PowerBlowGateParams struct {
	MinStrokeMs  int     `json:"minStrokeMs"`
	MinStrokePos float64 `json:"minStrokePos"`
	Outside      string  `json:"outside"`
}

func (g PowerBlowGate) Manual() bool {
	return strings.EqualFold(strings.TrimSpace(g.Mode), "manual") && len(g.Intervals) > 0
}

func (g PowerBlowGate) ActiveAt(t float64) bool {
	for _, iv := range g.Intervals {
		lo, hi := iv.StartMs, iv.EndMs
		if hi < lo {
			lo, hi = hi, lo
		}
		if t >= lo && t <= hi {
			return true
		}
	}
	return false
}

func (g PowerBlowGate) HasIntervals() bool {
	return len(g.Intervals) > 0
}

func (g PowerBlowGate) MinStrokeMs() int {
	if g.Params.MinStrokeMs > 0 {
		return g.Params.MinStrokeMs
	}
	return 0
}

// LoadPowerBlowGateBesideFunscript reads <path>.powerblow.json or
// <path without .funscript>.powerblow.json.
func LoadPowerBlowGateBesideFunscript(funscriptPath string) (PowerBlowGate, error) {
	candidates := []string{
		funscriptPath + ".powerblow.json",
		strings.TrimSuffix(funscriptPath, filepath.Ext(funscriptPath)) + ".powerblow.json",
	}
	var lastErr error
	for _, p := range candidates {
		b, err := os.ReadFile(p)
		if err != nil {
			lastErr = err
			continue
		}
		var g PowerBlowGate
		if err := json.Unmarshal(b, &g); err != nil {
			return PowerBlowGate{}, err
		}
		return g, nil
	}
	if lastErr != nil && !os.IsNotExist(lastErr) {
		return PowerBlowGate{}, lastErr
	}
	return PowerBlowGate{}, nil
}
