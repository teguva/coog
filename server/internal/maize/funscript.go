package maize

import (
	"encoding/json"
	"math"
	"os"
	"sort"
)

type funAction struct {
	At  float64 `json:"at"`
	Pos float64 `json:"pos"`
}

// FunAction is a single funscript keyframe (ms, position 0–100).
type FunAction struct {
	At  float64 `json:"at"`
	Pos float64 `json:"pos"`
}

// LoadFunscriptActions reads the full action list from a .funscript file.
func LoadFunscriptActions(path string) ([]FunAction, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var wrap struct {
		Actions []funAction `json:"actions"`
	}
	if err := json.Unmarshal(b, &wrap); err != nil {
		return nil, err
	}
	actions := make([]FunAction, 0, len(wrap.Actions))
	for _, a := range wrap.Actions {
		actions = append(actions, FunAction{At: a.At, Pos: a.Pos})
	}
	sort.Slice(actions, func(i, j int) bool { return actions[i].At < actions[j].At })
	return actions, nil
}

// FunscriptPreview is a downsampled heatmap for UI bars (Funplay-compatible).
type FunscriptPreview struct {
	DurationMs  int              `json:"durationMs"`
	ActionCount int              `json:"actionCount"`
	Intensity   float64          `json:"intensity,omitempty"`
	Points      []FunscriptPoint `json:"points"`
}

type FunscriptPoint struct {
	T        int     `json:"t"`
	Pos      float64 `json:"pos"`
	PosMin   float64 `json:"posMin"`
	PosMax   float64 `json:"posMax"`
	Speed    float64 `json:"speed"`
	Strength float64 `json:"strength"`
}

// LoadFunscriptPreview reads a .funscript and returns a downsampled heatmap preview.
// buckets <= 0 selects a constant ~1s/bar resolution (clamped to [48, 320]).
func LoadFunscriptPreview(path string, buckets int) (FunscriptPreview, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return FunscriptPreview{}, err
	}
	var wrap struct {
		Actions []funAction `json:"actions"`
	}
	if err := json.Unmarshal(b, &wrap); err != nil {
		return FunscriptPreview{}, err
	}
	actions := wrap.Actions
	sort.Slice(actions, func(i, j int) bool { return actions[i].At < actions[j].At })
	intensity := funscriptIntensity(actions)
	if len(actions) == 0 {
		return FunscriptPreview{Points: []FunscriptPoint{}, Intensity: intensity}, nil
	}
	duration := int(actions[len(actions)-1].At)
	if duration <= 0 {
		return FunscriptPreview{ActionCount: len(actions), Points: []FunscriptPoint{}, Intensity: intensity}, nil
	}
	buckets = resolvePreviewBuckets(duration, buckets)
	bucketMs := float64(duration) / float64(buckets)
	type raw struct {
		t, pos, posMin, posMax, speed float64
	}
	rawPts := make([]raw, 0, buckets)
	maxSpeed := 0.0
	for i := 0; i < buckets; i++ {
		t0 := float64(i) * bucketMs
		t1 := float64(i+1) * bucketMs
		tMid := (t0 + t1) / 2
		pos := posAt(actions, tMid)
		posMin, posMax := pos, pos
		for _, t := range []float64{t0, t1, tMid} {
			p := posAt(actions, t)
			if p < posMin {
				posMin = p
			}
			if p > posMax {
				posMax = p
			}
		}
		for _, a := range actions {
			if a.At < t0 {
				continue
			}
			if a.At > t1 {
				break
			}
			if a.Pos < posMin {
				posMin = a.Pos
			}
			if a.Pos > posMax {
				posMax = a.Pos
			}
		}
		speed := bucketStrength(actions, t0, t1)
		if speed > maxSpeed {
			maxSpeed = speed
		}
		rawPts = append(rawPts, raw{tMid, pos, posMin, posMax, speed})
	}
	if maxSpeed <= 0 {
		maxSpeed = 1
	}
	points := make([]FunscriptPoint, 0, len(rawPts))
	for _, r := range rawPts {
		points = append(points, FunscriptPoint{
			T:        int(math.Round(r.t)),
			Pos:      clamp100(r.pos),
			PosMin:   clamp100(r.posMin),
			PosMax:   clamp100(r.posMax),
			Speed:    math.Round(r.speed*10) / 10,
			Strength: clamp100((r.speed / maxSpeed) * 100),
		})
	}
	return FunscriptPreview{
		DurationMs:  duration,
		ActionCount: len(actions),
		Intensity:   intensity,
		Points:      points,
	}, nil
}

const (
	previewBucketMs = 1000
	previewMinBars  = 48
	previewMaxBars  = 320
)

// resolvePreviewBuckets picks bar count. buckets <= 0 → ~1s/bar; otherwise clamp explicit request.
func resolvePreviewBuckets(durationMs, buckets int) int {
	if buckets <= 0 {
		n := durationMs / previewBucketMs
		if n < previewMinBars {
			n = previewMinBars
		}
		if n > previewMaxBars {
			n = previewMaxBars
		}
		return n
	}
	if buckets < 64 {
		return 64
	}
	if buckets > 960 {
		return 960
	}
	return buckets
}

// ScriptIntensity returns a 0–100 sort score for a funscript file (0 if missing).
func ScriptIntensity(mediaPath string) float64 {
	p := FunscriptPath(mediaPath)
	if p == "" {
		return 0
	}
	b, err := os.ReadFile(p)
	if err != nil {
		return 0
	}
	var wrap struct {
		Actions []funAction `json:"actions"`
	}
	if json.Unmarshal(b, &wrap) != nil {
		return 0
	}
	sort.Slice(wrap.Actions, func(i, j int) bool { return wrap.Actions[i].At < wrap.Actions[j].At })
	return funscriptIntensity(wrap.Actions)
}

func funscriptIntensity(actions []funAction) float64 {
	if len(actions) < 2 {
		return 0
	}
	t0, t1 := actions[0].At, actions[len(actions)-1].At
	durationMs := t1 - t0
	if durationMs < 1000 {
		return 0
	}
	travel := 0.0
	peakRate := 0.0
	for i := 0; i < len(actions)-1; i++ {
		dt := actions[i+1].At - actions[i].At
		dpos := math.Abs(actions[i+1].Pos - actions[i].Pos)
		travel += dpos
		if dt > 0 {
			rate := dpos / dt * 1000
			if rate > peakRate {
				peakRate = rate
			}
		}
	}
	durationS := durationMs / 1000
	travelN := math.Min(1, (travel/durationS)/90)
	densityN := math.Min(1, (float64(len(actions))/durationS)/10)
	peakN := math.Min(1, peakRate/400)
	return math.Round((100*(0.55*travelN+0.30*densityN+0.15*peakN))*10) / 10
}

func posAt(actions []funAction, t float64) float64 {
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

func bucketStrength(actions []funAction, t0, t1 float64) float64 {
	peak := 0.0
	for i := 0; i < len(actions)-1; i++ {
		at0, at1 := actions[i].At, actions[i+1].At
		if at1 <= t0 {
			continue
		}
		if at0 >= t1 {
			break
		}
		dt := at1 - at0
		if dt <= 0 {
			continue
		}
		rate := math.Abs(actions[i+1].Pos-actions[i].Pos) / dt * 1000
		if rate > peak {
			peak = rate
		}
	}
	return peak
}

func clamp100(v float64) float64 {
	if v < 0 {
		return 0
	}
	if v > 100 {
		return 100
	}
	return math.Round(v*10) / 10
}
