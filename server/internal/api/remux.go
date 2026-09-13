package api

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"coog/internal/jobs"
	"coog/internal/playback"
	"coog/internal/store"
)

type remuxProc struct {
	cancel context.CancelFunc
	cmd    *exec.Cmd
}

func (s *Server) remuxProcs() map[string]*remuxProc {
	if s.remuxing == nil {
		s.remuxing = map[string]*remuxProc{}
	}
	return s.remuxing
}

// ensureRemux starts (or reuses) an awkward-audio HLS remux and waits briefly for the first segment.
func (s *Server) ensureRemux(item store.MediaItem) (ready bool, err error) {
	dir := playback.RemuxDir(s.cfg.DataPath, item.ID)
	playlist := playback.RemuxPlaylist(s.cfg.DataPath, item.ID)
	if playback.RemuxCacheFresh(s.cfg.DataPath, item.ID, item.Path) && playback.PlaylistHasMedia(playlist) {
		return true, nil
	}

	s.remuxMu.Lock()
	procs := s.remuxProcs()
	if _, running := procs[item.ID]; !running {
		ctx, cancel := context.WithCancel(context.Background())
		cmd, startErr := playback.StartAwkwardAudioHLS(ctx, s.prober.FFmpeg(), item.Path, dir)
		if startErr != nil {
			cancel()
			s.remuxMu.Unlock()
			return false, startErr
		}
		procs[item.ID] = &remuxProc{cancel: cancel, cmd: cmd}
		go func(id string, c *exec.Cmd, cancel context.CancelFunc) {
			_ = c.Wait()
			_ = jobs.AppendEndList(playback.RemuxPlaylist(s.cfg.DataPath, id))
			s.remuxMu.Lock()
			delete(s.remuxProcs(), id)
			s.remuxMu.Unlock()
			cancel()
		}(item.ID, cmd, cancel)
		slog.Info("remux started", "media_id", item.ID, "codec_audio", item.CodecAudio)
	}
	s.remuxMu.Unlock()

	ready = playback.WaitForPlaylist(playlist, 20*time.Second)
	return ready, nil
}

func (s *Server) writeRemuxSession(w http.ResponseWriter, r *http.Request, item store.MediaItem, result playback.Result) {
	ready, err := s.ensureRemux(item)
	if err != nil {
		slog.Info("playback session rejected", "playback_method", playback.MethodRemux, "media_id", item.ID, "err", err)
		s.note("error", "api", "session.error", err.Error(), "", item.ID)
		writeJSON(w, http.StatusConflict, map[string]any{
			"error":   "remux failed to start: " + err.Error(),
			"method":  playback.MethodRemux,
			"mediaId": item.ID,
			"reason":  result.Reason,
		})
		return
	}
	if !ready {
		writeJSON(w, http.StatusConflict, map[string]any{
			"error":   "Server is remuxing awkward audio for this title. Retry in a moment.",
			"method":  playback.MethodRemux,
			"mediaId": item.ID,
			"ready":   false,
			"reason":  result.Reason,
		})
		return
	}
	sid, err := randomID()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	streamURL := s.playbackMediaURL(r, item, "/api/v1/media/"+item.ID+"/remux/"+jobs.PlaylistName)
	buffered, _ := jobs.PlaylistBufferedMs(playback.RemuxPlaylist(s.cfg.DataPath, item.ID))
	slog.Info("playback session", "playback_method", playback.MethodRemux, "media_id", item.ID, "session_id", sid)
	writeJSON(w, http.StatusOK, map[string]any{
		"id":                 sid,
		"method":             playback.MethodRemux,
		"reason":             result.Reason,
		"url":                streamURL,
		"mediaId":            item.ID,
		"jobId":              "",
		"expectedDurationMs": item.DurationMs,
		"bufferedMs":         buffered,
	})
}

func (s *Server) handleRemux(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	file := r.PathValue("file")
	if id == "" || !safeHLSFile(file) {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	item, err := s.store.GetMedia(id)
	if err != nil {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	if !s.gateMaizeMedia(w, r, item.Path, item.RelativePath) {
		return
	}
	path := filepath.Join(playback.RemuxDir(s.cfg.DataPath, id), file)
	f, err := os.Open(path)
	if err != nil {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if strings.HasSuffix(strings.ToLower(file), ".m3u8") {
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
		w.Header().Set("Cache-Control", "no-cache, no-store")
		w.WriteHeader(http.StatusOK)
		_, _ = io.Copy(w, f)
		return
	}
	w.Header().Set("Content-Type", "video/mp2t")
	w.Header().Set("Cache-Control", "public, max-age=60")
	http.ServeContent(w, r, file, st.ModTime(), f)
}
