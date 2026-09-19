package api

import (
	"encoding/json"
	"net/http"
	"path/filepath"
	"strconv"
	"strings"

	"coog/internal/interactive"
)

func (s *Server) requireAdultOrAdmin(w http.ResponseWriter, r *http.Request) bool {
	// Open LAN install (no auth token): allow admin Interactive page without adult PIN.
	if s.cfg.AuthToken == "" {
		return true
	}
	authz := r.Header.Get("Authorization")
	if strings.HasPrefix(authz, "Bearer ") && strings.TrimSpace(strings.TrimPrefix(authz, "Bearer ")) == s.cfg.AuthToken {
		return true
	}
	if tok := strings.TrimSpace(r.URL.Query().Get("token")); tok != "" && tok == s.cfg.AuthToken {
		return true
	}
	return s.requireAdult(w, r)
}

func (s *Server) handleInteractiveStatus(w http.ResponseWriter, r *http.Request) {
	if s.interactive == nil {
		writeJSON(w, http.StatusOK, map[string]any{"supported": false, "enabled": false})
		return
	}
	writeJSON(w, http.StatusOK, s.interactive.Status())
}

func (s *Server) handleInteractiveEngine(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdultOrAdmin(w, r) {
		return
	}
	if s.interactive == nil || !s.interactive.Enabled() {
		writeError(w, http.StatusServiceUnavailable, "interactive disabled")
		return
	}
	writeJSON(w, http.StatusOK, s.interactive.EngineState())
}

func (s *Server) handleInteractiveEngineAction(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdultOrAdmin(w, r) {
		return
	}
	if s.interactive == nil || !s.interactive.Enabled() {
		writeError(w, http.StatusServiceUnavailable, "interactive disabled")
		return
	}
	action := r.PathValue("action")
	var err error
	switch action {
	case "reconnect":
		err = s.interactive.Reconnect()
	case "restart":
		err = s.interactive.RestartEngine()
	case "stop_all":
		err = s.interactive.StopAll()
	default:
		writeError(w, http.StatusNotFound, "unknown action")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "engine": s.interactive.EngineState()})
}

func (s *Server) handleInteractiveScan(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdultOrAdmin(w, r) {
		return
	}
	if s.interactive == nil || !s.interactive.Enabled() {
		writeError(w, http.StatusServiceUnavailable, "interactive disabled")
		return
	}
	action := r.PathValue("action")
	var err error
	switch action {
	case "start":
		err = s.interactive.ScanStart()
	case "pair":
		err = s.interactive.ScanPair()
	case "stop":
		err = s.interactive.ScanStop()
	default:
		writeError(w, http.StatusNotFound, "unknown action")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "engine": s.interactive.EngineState()})
}

func (s *Server) handleInteractiveBatteryRefresh(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdultOrAdmin(w, r) {
		return
	}
	if s.interactive == nil {
		writeError(w, http.StatusServiceUnavailable, "interactive disabled")
		return
	}
	s.interactive.RefreshBattery()
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "engine": s.interactive.EngineState()})
}

func (s *Server) handleInteractiveDeviceTest(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdultOrAdmin(w, r) {
		return
	}
	if s.interactive == nil {
		writeError(w, http.StatusServiceUnavailable, "interactive disabled")
		return
	}
	idx, _ := strconv.Atoi(r.PathValue("idx"))
	if err := s.interactive.TestDevice(idx); err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) handleInteractiveDevicePatch(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdultOrAdmin(w, r) {
		return
	}
	if s.interactive == nil {
		writeError(w, http.StatusServiceUnavailable, "interactive disabled")
		return
	}
	idx, _ := strconv.Atoi(r.PathValue("idx"))
	var body map[string]any
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid body")
		return
	}
	dev, err := s.interactive.PatchDevice(idx, body)
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "device": dev})
}

func (s *Server) handleInteractiveDeviceID(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdultOrAdmin(w, r) {
		return
	}
	if s.interactive == nil {
		writeError(w, http.StatusServiceUnavailable, "interactive disabled")
		return
	}
	id := r.PathValue("id")
	action := r.PathValue("action")
	switch {
	case r.Method == http.MethodDelete:
		if err := s.interactive.ForgetDevice(id); err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	case action == "connect":
		if err := s.interactive.ConnectDevice(id); err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "engine": s.interactive.EngineState()})
	case action == "disconnect":
		if err := s.interactive.DisconnectDevice(id); err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "engine": s.interactive.EngineState()})
	default:
		writeError(w, http.StatusNotFound, "unknown action")
	}
}

func (s *Server) handleInteractiveForgetOffline(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdultOrAdmin(w, r) {
		return
	}
	if s.interactive == nil {
		writeError(w, http.StatusServiceUnavailable, "interactive disabled")
		return
	}
	n, err := s.interactive.ForgetOffline()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "removed": n, "engine": s.interactive.EngineState()})
}

func (s *Server) handleInteractiveLoad(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdult(w, r) {
		return
	}
	if s.interactive == nil || !s.interactive.Enabled() {
		writeError(w, http.StatusServiceUnavailable, "interactive disabled")
		return
	}
	var body struct {
		MediaID     string  `json:"mediaId"`
		Script      string  `json:"script"`
		ResumeMs    float64 `json:"resumeMs"`
		Offset      int     `json:"offset"`
		Scale       float64 `json:"scale"`
		Speed       float64 `json:"speed"`
		MinInterval int     `json:"minInterval"`
		Invert      bool    `json:"invert"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || strings.TrimSpace(body.MediaID) == "" {
		writeError(w, http.StatusBadRequest, "mediaId required")
		return
	}
	item, err := s.store.GetMedia(body.MediaID)
	if err != nil {
		writeError(w, http.StatusNotFound, "media not found")
		return
	}
	if !s.isMaizeItem(item.Path, item.RelativePath) {
		writeError(w, http.StatusForbidden, "not a maize item")
		return
	}
	params := interactive.DefaultSyncParams()
	if body.Offset != 0 {
		params.OffsetMs = body.Offset
	}
	if body.Scale > 0 {
		params.Scale = body.Scale
	}
	if body.Speed > 0 {
		params.Speed = body.Speed
	}
	if body.MinInterval > 0 {
		params.MinInterval = body.MinInterval
	}
	params.Invert = body.Invert
	if err := s.interactive.LoadScript(body.MediaID, item.Path, body.Script, params, body.ResumeMs); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "playback": s.interactive.PlaybackStatus()})
}

func (s *Server) handleInteractiveParams(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdultOrAdmin(w, r) {
		return
	}
	if s.interactive == nil {
		writeError(w, http.StatusServiceUnavailable, "interactive disabled")
		return
	}
	var body interactive.SyncParams
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid body")
		return
	}
	s.interactive.SetParams(body)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "playback": s.interactive.PlaybackStatus()})
}

func (s *Server) handleInteractivePlayback(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdultOrAdmin(w, r) {
		return
	}
	if s.interactive == nil {
		writeError(w, http.StatusServiceUnavailable, "interactive disabled")
		return
	}
	writeJSON(w, http.StatusOK, s.interactive.PlaybackStatus())
}

func (s *Server) handleInteractiveSyncWS(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdult(w, r) {
		return
	}
	if s.interactive == nil || !s.interactive.Enabled() {
		writeError(w, http.StatusServiceUnavailable, "interactive disabled")
		return
	}
	s.interactive.ServeSyncWS(w, r)
}

func (s *Server) handleInteractiveEngineWS(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdultOrAdmin(w, r) {
		return
	}
	if s.interactive == nil || !s.interactive.Enabled() {
		writeError(w, http.StatusServiceUnavailable, "interactive disabled")
		return
	}
	s.interactive.ServeEngineWS(w, r)
}

func (s *Server) handleDeviceIcon(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdultOrAdmin(w, r) {
		return
	}
	interactive.ResolveDeviceIcon(w, r, r.URL.Query().Get("name"), r.URL.Query().Get("device_id"), filepath.Join(s.cfg.DataPath, "device-icons"))
}
