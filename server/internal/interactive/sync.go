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
}

func DefaultSyncParams() SyncParams {
	return SyncParams{OffsetMs: 350, Scale: 1, Speed: 0.9, MinInterval: 60}
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
	s.anchorMs = resumeMs
	s.anchorWall = time.Now()
	s.playing = false
	s.nextVib = map[int]int{}
	s.nextLin = map[int]int{}
	s.nextCon = map[int]int{}
	s.lastVib = map[int]float64{}
	s.lastLin = map[int]float64{}
	s.lastLinSendAt = map[int]time.Time{}
	s.driveEpoch++
	s.linearCmds = 0
	s.scalarCmds = 0
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
		"minInterval":      s.params.MinInterval,
		"mediaId":          s.mediaID,
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
	}
	s.mu.Unlock()
	if empty {
		s.hardStop()
	}
}

// hardStop: Oscillate:0 / scalar 0 only — never Linear. A Linear "park" retargets
// Solace toward lastLin and continues the stroke instead of stopping.
func (s *SyncRuntime) hardStop() {
	if s.svc == nil || !s.svc.bp.Connected() {
		return
	}
	s.mu.Lock()
	s.nextLin = map[int]int{}
	s.nextCon = map[int]int{}
	s.lastLin = map[int]float64{}
	s.lastLinSendAt = map[int]time.Time{}
	s.lastVib = map[int]float64{}
	s.mu.Unlock()

	ctx := context.Background()
	devices := s.svc.LiveDevicesWithPrefs()
	for i := 0; i < 3; i++ {
		for _, d := range devices {
			switch d.Kind {
			case "linear":
				_ = s.svc.bp.Scalar(ctx, d.Index, 0, "Oscillate")
			case "constrict":
				_ = s.svc.bp.Scalar(ctx, d.Index, 0, "Constrict")
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
	ticker := time.NewTicker(50 * time.Millisecond)
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
			s.driveLinear(ctx, d.Index, t, actions, params, intensity, epoch)
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

// driveLinear: Solace-tuned continuous tracking. Sample with LinearLeadMs on top
// of device offset (already in t), emit at most once per LinearGapMs with fixed
// LinearDurMs so we do not fight DeviceMessageTimingGap / FSetSite seeker steps.
func (s *SyncRuntime) driveLinear(ctx context.Context, idx int, t float64, actions []Action, params SyncParams, intensity float64, epoch uint64) {
	if len(actions) == 0 {
		return
	}
	target := mapLinearPos(actionPosLinearTarget(actions, t, LinearLeadMs)/100.0, intensity)
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
	if lastSend, ok := s.lastLinSendAt[idx]; ok && now.Sub(lastSend) < time.Duration(LinearGapMs)*time.Millisecond {
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
	_ = s.svc.bp.Linear(ctx, idx, target, LinearDurMs)
	s.mu.Lock()
	if s.playing && s.driveEpoch == epoch {
		s.lastLin[idx] = target
		s.lastLinSendAt[idx] = now
		s.linearCmds++
	}
	s.mu.Unlock()
}

func (s *SyncRuntime) driveConstrict(ctx context.Context, idx int, t float64, actions []Action, params SyncParams, intensity float64) {
	// Funplay constrict: same stroke clock as linear (fire at cur when cur.at <= t).
	if len(actions) < 2 {
		return
	}
	s.mu.Lock()
	ni := s.nextCon[idx]
	s.mu.Unlock()
	if ni < 0 {
		ni = 0
	}
	minInterval := params.MinInterval
	if minInterval < 50 {
		minInterval = 50
	}
	for ni < len(actions) && actions[ni].At <= t {
		cur := actions[ni]
		tgt := ni + 1
		for tgt < len(actions) && (actions[tgt].At-cur.At) < float64(minInterval) {
			tgt++
		}
		if tgt >= len(actions) {
			s.mu.Lock()
			s.nextCon[idx] = len(actions)
			s.mu.Unlock()
			return
		}
		nxt := actions[tgt]
		level, ok := constrictStrokeLevel(cur.Pos, nxt.Pos, params.Invert)
		ni = tgt
		if !ok {
			continue
		}
		_ = s.svc.bp.Scalar(ctx, idx, clamp01(level*params.Scale*intensity), "Constrict")
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
