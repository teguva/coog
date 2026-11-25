package interactive

import (
	"context"
	"encoding/json"
	"sync"
	"time"

	"github.com/coder/websocket"
)

type Action struct {
	At  float64
	Pos float64
}

type SyncParams struct {
	OffsetMs    int     `json:"offset"`
	Scale       float64 `json:"scale"`
	Speed       float64 `json:"speed"`
	MinInterval int     `json:"minInterval"`
	Invert      bool    `json:"invert"`
	// PowerBlowMinStrokeMs: ignore funscript edges shorter than this (slow-stroke filter).
	// 0 → DefaultPowerBlowMinStrokeMs.
	PowerBlowMinStrokeMs int `json:"powerBlowMinStrokeMs"`
}

func DefaultSyncParams() SyncParams {
	return SyncParams{
		OffsetMs: 350, Scale: 1, Speed: 0.9, MinInterval: 60,
		PowerBlowMinStrokeMs: DefaultPowerBlowMinStrokeMs,
	}
}

type syncClient struct {
	conn *websocket.Conn
}

// SyncRuntime holds playback clock + funscript actions.
type SyncRuntime struct {
	svc *Service

	mu         sync.Mutex
	actions    []Action
	mediaID    string
	loaded     bool
	playing    bool
	anchorMs   float64
	anchorWall time.Time
	rttMs      float64
	params     SyncParams
	clients    map[*websocket.Conn]struct{}
	sessionID  string

	nextVib       map[int]int
	nextLin       map[int]int
	nextCon       map[int]int
	lastVib       map[int]float64
	lastLin       map[int]float64
	lastLinSendAt map[int]time.Time
	pbHolding     bool // PowerBlow vacuum held until release
	pbGate        PowerBlowGate
	pbMask        *PowerBlowActivityMask // universal model, script-relative
	driveEpoch    uint64
	linearCmds    int64
	scalarCmds    int64
}

func newSyncRuntime(svc *Service) *SyncRuntime {
	return &SyncRuntime{
		svc:           svc,
		params:        DefaultSyncParams(),
		clients:       map[*websocket.Conn]struct{}{},
		nextVib:       map[int]int{},
		nextLin:       map[int]int{},
		nextCon:       map[int]int{},
		lastVib:       map[int]float64{},
		lastLin:       map[int]float64{},
		lastLinSendAt: map[int]time.Time{},
	}
}

func (s *SyncRuntime) Load(mediaID string, actions []Action, params SyncParams, resumeMs float64) {
	s.LoadWithGate(mediaID, actions, params, resumeMs, PowerBlowGate{})
}

func (s *SyncRuntime) LoadWithGate(mediaID string, actions []Action, params SyncParams, resumeMs float64, gate PowerBlowGate) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.mediaID = mediaID
	s.actions = actions
	s.loaded = len(actions) > 0
	s.params = params
	if s.params.Scale <= 0 {
		s.params.Scale = 1
	}
	if s.params.Speed <= 0 {
		s.params.Speed = 0.9
	}
	if s.params.MinInterval <= 0 {
		s.params.MinInterval = 60
	}
	if s.params.PowerBlowMinStrokeMs <= 0 {
		s.params.PowerBlowMinStrokeMs = DefaultPowerBlowMinStrokeMs
	}
	// Manual gate may carry reactive minStrokeMs; pattern mode uses pbPatternReactiveMs while active.
	if gate.Manual() {
		if ms := gate.MinStrokeMs(); ms > 0 {
			s.params.PowerBlowMinStrokeMs = ms
		}
	}
	s.pbGate = gate
	// Universal detector: score this script against its own distribution (not a gate file).
	s.pbMask = BuildPowerBlowActivityMask(actions)
	s.anchorMs = resumeMs
	s.anchorWall = time.Now()
	s.playing = false
	s.nextVib = map[int]int{}
	s.nextLin = map[int]int{}
	s.nextCon = map[int]int{}
	s.lastVib = map[int]float64{}
	s.lastLin = map[int]float64{}
	s.lastLinSendAt = map[int]time.Time{}
	s.pbHolding = false
	s.driveEpoch++
	s.linearCmds = 0
	s.scalarCmds = 0
}

// powerBlowActive is retained for manual .powerblow.json overrides only.
// Default / pattern mode never filters — original funscript edges drive the toy.
func (s *SyncRuntime) powerBlowActive(actions []Action, t float64) (active bool, minStrokeMs float64) {
	_ = actions
	s.mu.Lock()
	gate := s.pbGate
	s.mu.Unlock()
	if gate.Manual() {
		return gate.ActiveAt(t), 0
	}
	return true, 0
}

func (s *SyncRuntime) SetParams(p SyncParams) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if p.Scale > 0 {
		s.params.Scale = p.Scale
	}
	if p.Speed > 0 {
		s.params.Speed = p.Speed
	}
	if p.MinInterval > 0 {
		s.params.MinInterval = p.MinInterval
	}
	if p.PowerBlowMinStrokeMs > 0 {
		s.params.PowerBlowMinStrokeMs = p.PowerBlowMinStrokeMs
	}
	s.params.OffsetMs = p.OffsetMs
	s.params.Invert = p.Invert
}

func (s *SyncRuntime) Status() map[string]any {
	s.mu.Lock()
	defer s.mu.Unlock()
	pos := s.currentPosLocked()
	return map[string]any{
		"loaded":           s.loaded,
		"playing":          s.playing,
		"posMs":            pos,
		"offset":           s.params.OffsetMs,
		"scale":            s.params.Scale,
		"speed":            s.params.Speed,
		"invert":           s.params.Invert,
		"minInterval":             s.params.MinInterval,
		"powerBlowMinStrokeMs":    s.params.PowerBlowMinStrokeMs,
		"mediaId":                 s.mediaID,
		"actionCount":      len(s.actions),
		"syncRttMs":        s.rttMs,
		"syncWsConnected":  len(s.clients) > 0,
		"intiface":         s.svc != nil && s.svc.bp != nil && s.svc.bp.Connected(),
		"linearCmds":       s.linearCmds,
		"scalarCmds":       s.scalarCmds,
	}
}

func (s *SyncRuntime) currentPosLocked() float64 {
	if !s.playing {
		return s.anchorMs
	}
	elapsed := time.Since(s.anchorWall).Seconds() * 1000
	return s.anchorMs + elapsed
}

func (s *SyncRuntime) HandleMessage(conn *websocket.Conn, raw []byte) []byte {
	var msg map[string]any
	if json.Unmarshal(raw, &msg) != nil {
		return nil
	}
	typ, _ := msg["type"].(string)
	var out []byte
	needHalt := false
	s.mu.Lock()
	switch typ {
	case "ping":
		ts := msg["client_ts"]
		out, _ = json.Marshal(map[string]any{"type": "pong", "client_ts": ts})
		if rtt, ok := msg["rtt_ms"].(float64); ok {
			s.rttMs = rtt
		}
	case "play":
		s.playing = true
		s.anchorWall = time.Now()
	case "pause":
		was := s.playing
		s.anchorMs = s.currentPosLocked()
		s.playing = false
		s.anchorWall = time.Now()
		if was {
			s.driveEpoch++
			needHalt = true
		}
	case "pos":
		pos, _ := msg["pos_ms"].(float64)
		playing, _ := msg["playing"].(bool)
		seek, _ := msg["seek"].(bool)
		if rtt, ok := msg["rtt_ms"].(float64); ok && rtt > 0 {
			s.rttMs = rtt
			pos -= rtt / 2
		}
		if seek || abs(pos-s.currentPosLocked()) > 500 {
			s.nextVib = map[int]int{}
			s.nextLin = map[int]int{}
			s.nextCon = map[int]int{}
			s.lastLin = map[int]float64{}
			s.lastLinSendAt = map[int]time.Time{}
			s.pbHolding = false
			s.driveEpoch++
		}
		was := s.playing
		s.anchorMs = pos
		s.anchorWall = time.Now()
		s.playing = playing
		// Only halt on a true→false edge. ExoPlayer often flickers isPlaying while
		// buffering; halting every 50ms left linear toys idle during playback.
		if was && !playing {
			s.driveEpoch++
			needHalt = true
		}
	}
	s.mu.Unlock()
	if needHalt {
		s.hardStop()
	}
	_ = conn
	return out
}

func (s *SyncRuntime) AddClient(conn *websocket.Conn) {
	s.mu.Lock()
	s.clients[conn] = struct{}{}
	s.mu.Unlock()
}

func (s *SyncRuntime) RemoveClient(conn *websocket.Conn) {
	s.mu.Lock()
	delete(s.clients, conn)
	empty := len(s.clients) == 0
	if empty {
		s.playing = false
		s.loaded = false
		s.actions = nil
		s.driveEpoch++
		s.nextLin = map[int]int{}
		s.nextCon = map[int]int{}
		s.lastLin = map[int]float64{}
		s.lastLinSendAt = map[int]time.Time{}
		s.lastVib = map[int]float64{}
		s.pbHolding = false
		s.pbMask = nil
	}
	s.mu.Unlock()
	if empty {
		s.hardStop()
	}
}

// hardStop: Oscillate:0 / scalar 0 / PowerBlow release — never Linear.
// A Linear "park" retargets Solace toward lastLin and continues the stroke.
func (s *SyncRuntime) hardStop() {
	if s.svc == nil {
		return
	}
	s.mu.Lock()
	s.nextLin = map[int]int{}
	s.nextCon = map[int]int{}
	s.lastLin = map[int]float64{}
	s.lastLinSendAt = map[int]time.Time{}
	s.lastVib = map[int]float64{}
	s.pbHolding = false
	s.mu.Unlock()

	// Native PowerBlow holds vacuum until release — always vent, even if Intiface is down.
	s.svc.HaltPowerBlow()

	if !s.svc.bp.Connected() {
		return
	}
	ctx := context.Background()
	devices := s.svc.LiveDevicesWithPrefs()
	for i := 0; i < 3; i++ {
		for _, d := range devices {
			if d.Index == powerblowIndexBase {
				continue
			}
			switch d.Kind {
			case "linear":
				_ = s.svc.bp.Scalar(ctx, d.Index, 0, "Oscillate")
			case "constrict":
				_ = s.svc.Constrict(ctx, d.Index, 0)
			default:
				_ = s.svc.bp.Scalar(ctx, d.Index, 0, "Vibrate")
			}
		}
		if i+1 < 3 {
			time.Sleep(120 * time.Millisecond)
		}
	}
}

func (s *SyncRuntime) DriveLoop(ctx context.Context) {
	ticker := time.NewTicker(25 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			s.tick()
		}
	}
}

func (s *SyncRuntime) tick() {
	s.mu.Lock()
	if !s.loaded || !s.playing || len(s.actions) == 0 {
		s.mu.Unlock()
		return
	}
	epoch := s.driveEpoch
	pos := s.currentPosLocked()
	actions := s.actions
	params := s.params
	s.mu.Unlock()

	// Hold BLE scan while driving — continuous discovery starves Solace writes.
	s.svc.holdScanForSync(3 * time.Second)

	devices := s.svc.LiveDevicesWithPrefs()
	ctx := context.Background()
	for _, d := range devices {
		s.mu.Lock()
		alive := s.playing && s.driveEpoch == epoch
		s.mu.Unlock()
		if !alive {
			return
		}
		t := pos + float64(d.OffsetMs)
		intensity := float64(d.Intensity) / 100.0
		switch d.Kind {
		case "linear":
			s.driveLinear(ctx, d.Index, d.Name, t, actions, params, intensity, epoch)
		case "constrict":
			s.driveConstrict(ctx, d.Index, t, actions, params, intensity)
		default:
			s.driveScalar(ctx, d.Index, t, actions, params, intensity)
		}
	}
}

func (s *SyncRuntime) driveScalar(ctx context.Context, idx int, t float64, actions []Action, params SyncParams, intensity float64) {
	pos := actionPosAt(actions, t) / 100.0
	level := vibLevelAt(pos) * params.Scale * intensity
	s.mu.Lock()
	last := s.lastVib[idx]
	s.mu.Unlock()
	if abs(level-last) < 0.008 {
		return
	}
	_ = s.svc.bp.Scalar(ctx, idx, clamp01(level), "Vibrate")
	s.mu.Lock()
	s.lastVib[idx] = level
	s.scalarCmds++
	s.mu.Unlock()
}

// driveLinear: Solace uses continuous FSetSite tracking. Handy seeks one scripted
// half-stroke tip at a time (duration = tip Δt) so a single stroke is not chopped
// into many short Hdsp moves.
func (s *SyncRuntime) driveLinear(ctx context.Context, idx int, name string, t float64, actions []Action, params SyncParams, intensity float64, epoch uint64) {
	if len(actions) == 0 {
		return
	}
	if IsHandyName(name) {
		s.driveLinearHandy(ctx, idx, t, actions, params, intensity, epoch)
		return
	}
	prof := LinearProfileFor(name)
	target := mapLinearPos(actionPosLinearTarget(actions, t, prof.LeadMs, prof.Inset)/100.0, intensity)
	if params.Invert {
		target = 1 - target
	}
	target = clamp01(target)

	now := time.Now()
	s.mu.Lock()
	alive := s.playing && s.driveEpoch == epoch
	if !alive {
		s.mu.Unlock()
		return
	}
	if last, has := s.lastLin[idx]; has && abs(target-last) < 0.004 {
		s.mu.Unlock()
		return
	}
	if lastSend, ok := s.lastLinSendAt[idx]; ok && now.Sub(lastSend) < time.Duration(prof.GapMs)*time.Millisecond {
		s.mu.Unlock()
		return
	}
	s.mu.Unlock()

	s.mu.Lock()
	alive = s.playing && s.driveEpoch == epoch
	s.mu.Unlock()
	if !alive {
		return
	}
	_ = s.svc.bp.Linear(ctx, idx, target, prof.DurMs)
	s.mu.Lock()
	if s.playing && s.driveEpoch == epoch {
		s.lastLin[idx] = target
		s.lastLinSendAt[idx] = now
		s.linearCmds++
	}
	s.mu.Unlock()
}

func (s *SyncRuntime) driveLinearHandy(ctx context.Context, idx int, t float64, actions []Action, params SyncParams, intensity float64, epoch uint64) {
	prof := LinearProfileFor("handy")
	s.mu.Lock()
	alive := s.playing && s.driveEpoch == epoch
	tipIdx, committed := s.nextLin[idx]
	s.mu.Unlock()
	if !alive {
		return
	}
	// Still en route to a committed tip — do not retarget mid-stroke.
	if committed && tipIdx >= 0 && tipIdx < len(actions) && actions[tipIdx].At > t+40 {
		return
	}

	tip, ok := nextLinearStrokeTip(actions, t, HandyMinStrokeDelta, prof.Inset)
	if !ok {
		return
	}
	target := mapLinearPos(tip.Pos/100.0, intensity)
	if params.Invert {
		target = 1 - target
	}
	target = clamp01(target)

	now := time.Now()
	s.mu.Lock()
	if last, has := s.lastLin[idx]; has && abs(target-last) < 0.01 && s.nextLin[idx] == tip.Idx {
		s.mu.Unlock()
		return
	}
	if lastSend, ok := s.lastLinSendAt[idx]; ok && now.Sub(lastSend) < time.Duration(prof.GapMs)*time.Millisecond {
		s.mu.Unlock()
		return
	}
	alive = s.playing && s.driveEpoch == epoch
	s.mu.Unlock()
	if !alive {
		return
	}

	_ = s.svc.bp.Linear(ctx, idx, target, tip.DurMs)
	s.mu.Lock()
	if s.playing && s.driveEpoch == epoch {
		s.lastLin[idx] = target
		s.lastLinSendAt[idx] = now
		s.nextLin[idx] = tip.Idx
		s.linearCmds++
	}
	s.mu.Unlock()
}

func (s *SyncRuntime) driveConstrict(ctx context.Context, idx int, t float64, actions []Action, params SyncParams, intensity float64) {
	// Vortex / other constrict: fire at stroke start.
	// PowerBlow: oral — suck on withdrawal START, vent on insertion START.
	if len(actions) < 2 {
		return
	}
	pb := idx == powerblowIndexBase
	s.mu.Lock()
	ni := s.nextCon[idx]
	holding := s.pbHolding
	s.mu.Unlock()
	if ni < 0 {
		ni = 0
	}
	minInterval := params.MinInterval
	if minInterval < 50 {
		minInterval = 50
	}
	if pb {
		minInterval = 0
	}
	for ni < len(actions) {
		cur := actions[ni]
		var tgt int
		if pb {
			tgt = ExtendSameDirection(actions, ni)
		} else {
			tgt = ni + 1
			for tgt < len(actions) && (actions[tgt].At-cur.At) < float64(minInterval) {
				tgt++
			}
		}
		if tgt >= len(actions) {
			s.mu.Lock()
			s.nextCon[idx] = len(actions)
			s.mu.Unlock()
			return
		}
		nxt := actions[tgt]
		if pb {
			// Fire slightly early so BLE lands near stroke START.
			if cur.At-PbBleLeadMs > t {
				s.mu.Lock()
				s.nextCon[idx] = ni
				s.mu.Unlock()
				return
			}
			active, minMs := s.powerBlowActive(actions, cur.At)
			ni = tgt
			if !active {
				continue
			}
			st, ok := AdaptPowerBlowStroke(cur.At, cur.Pos, nxt.At, nxt.Pos, params.Invert, params.Scale, intensity, minMs, holding, active)
			if !ok {
				continue
			}
			_ = s.svc.PowerBlowStroke(st)
			holding = st.Suck
			s.mu.Lock()
			s.pbHolding = holding
			s.nextCon[idx] = ni
			s.scalarCmds++
			s.mu.Unlock()
			return
		}
		if cur.At > t {
			s.mu.Lock()
			s.nextCon[idx] = ni
			s.mu.Unlock()
			return
		}
		ni = tgt
		level, ok := constrictStrokeLevel(cur.Pos, nxt.Pos, params.Invert)
		if !ok {
			continue
		}
		_ = s.svc.Constrict(ctx, idx, clamp01(level*params.Scale*intensity))
		s.mu.Lock()
		s.scalarCmds++
		s.mu.Unlock()
	}
	s.mu.Lock()
	s.nextCon[idx] = ni
	s.mu.Unlock()
}

func abs(v float64) float64 {
	if v < 0 {
		return -v
	}
	return v
}
