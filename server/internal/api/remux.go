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

// ensureJobRemux remuxes a progressive job's growing original to multi-track AAC
// Matroska so clients that cannot decode E-AC3/DTS/TrueHD still get selectable audio.
func (s *Server) ensureJobRemux(jobID, sourcePath string, expectedTotal int64) (ready bool, err error) {
	key := "job:" + jobID
	outPath := playback.JobRemuxPath(s.cfg.DataPath, jobID)
	outDir := jobs.HLSDir(s.cfg.DataPath, jobID)

	s.remuxMu.Lock()
	procs := s.remuxProcs()
	if _, running := procs[key]; running {
		s.remuxMu.Unlock()
		return playback.WaitForRemuxFile(outPath, 8*time.Second), nil
	}
	// Reuse a finished remux when the original is fully on disk.
	if st, statErr := os.Stat(sourcePath); statErr == nil && expectedTotal > 0 && st.Size() >= expectedTotal &&
		playback.RemuxFileReady(outPath) {
		s.remuxMu.Unlock()
		return true, nil
	}
	ctx, cancel := context.WithCancel(context.Background())
	cmd, startErr := playback.StartGrowingAwkwardAudioRemux(ctx, s.prober.FFmpeg(), sourcePath, outDir, expectedTotal)
	if startErr != nil {
		cancel()
		s.remuxMu.Unlock()
		return false, startErr
	}
	procs[key] = &remuxProc{cancel: cancel, cmd: cmd}
	go func(id string, c *exec.Cmd, cancel context.CancelFunc) {
		_ = c.Wait()
		s.remuxMu.Lock()
		delete(s.remuxProcs(), "job:"+id)
		s.remuxMu.Unlock()
		cancel()
	}(jobID, cmd, cancel)
	slog.Info("job remux started", "job_id", jobID, "source", sourcePath, "out", outPath)
	s.remuxMu.Unlock()

	ready = playback.WaitForRemuxFile(outPath, 8*time.Second)
	return ready, nil
}

// ensureRemux starts (or reuses) an awkward-audio remux and waits briefly for playable bytes.
func (s *Server) ensureRemux(item store.MediaItem) (ready bool, err error) {
	dir := playback.RemuxDir(s.cfg.DataPath, item.ID)
	outPath := playback.RemuxFile(s.cfg.DataPath, item.ID)
	if playback.RemuxCacheFresh(s.cfg.DataPath, item.ID, item.Path) && playback.RemuxFileReady(outPath) {
		return true, nil
	}

	s.remuxMu.Lock()
	procs := s.remuxProcs()
	if _, running := procs[item.ID]; !running {
		ctx, cancel := context.WithCancel(context.Background())
		cmd, startErr := playback.StartAwkwardAudioRemux(ctx, s.prober.FFmpeg(), item.Path, dir)
		if startErr != nil {
			cancel()
			s.remuxMu.Unlock()
			return false, startErr
		}
		procs[item.ID] = &remuxProc{cancel: cancel, cmd: cmd}
		go func(id string, c *exec.Cmd, cancel context.CancelFunc) {
			_ = c.Wait()
			s.remuxMu.Lock()
			delete(s.remuxProcs(), id)
			s.remuxMu.Unlock()
			cancel()
		}(item.ID, cmd, cancel)
		slog.Info("remux started", "media_id", item.ID, "codec_audio", item.CodecAudio)
	}
	s.remuxMu.Unlock()

	// Short wait — session HTTP must stay snappy; client retries while ffmpeg warms.
	ready = playback.WaitForRemuxFile(outPath, 4*time.Second)
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
	// Jellyfin Direct Stream: original video URL + small AAC-only audio sidecar.
	videoURL := s.playbackMediaURL(r, item, "/api/v1/media/"+item.ID+"/stream")
	audioURL := s.playbackMediaURL(r, item, "/api/v1/media/"+item.ID+"/remux/"+playback.AwkwardAudioName)
	ctype := strings.TrimSpace(item.ContentType)
	if ctype == "" {
		ctype = "video/x-matroska"
	}
	slog.Info("playback session", "playback_method", playback.MethodRemux, "media_id", item.ID, "session_id", sid, "audio_only", true)
	writeJSON(w, http.StatusOK, map[string]any{
		"id":                 sid,
		"method":             playback.MethodRemux,
		"reason":             result.Reason,
		"url":                videoURL,
		"audioUrl":           audioURL,
		"mediaId":            item.ID,
		"jobId":              "",
		"contentType":        ctype,
		"audioContentType":   "audio/x-matroska",
		"expectedDurationMs": item.DurationMs,
		"bufferedMs":         item.DurationMs,
		"codecAudio":         "aac",
	})
}

func (s *Server) handleRemux(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	file := r.PathValue("file")
	if id == "" || !safeRemuxFile(file) {
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
	lower := strings.ToLower(file)
	switch {
	case strings.HasSuffix(lower, ".mka"), strings.HasSuffix(lower, ".mkv"):
		if strings.HasSuffix(lower, ".mka") {
			w.Header().Set("Content-Type", "audio/x-matroska")
		} else {
			w.Header().Set("Content-Type", "video/x-matroska")
		}
	case strings.HasSuffix(lower, ".m3u8"):
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
		w.Header().Set("Cache-Control", "no-cache, no-store")
		w.WriteHeader(http.StatusOK)
		_, _ = io.Copy(w, f)
		return
	case strings.HasSuffix(lower, ".m4s"), lower == "init.mp4":
		w.Header().Set("Content-Type", "video/mp4")
	default:
		w.Header().Set("Content-Type", "video/mp2t")
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Accept-Ranges", "bytes")
	http.ServeContent(w, r, file, st.ModTime(), f)
}

func safeRemuxFile(name string) bool {
	if name == playback.AwkwardAudioName {
		return true
	}
	return safeHLSFile(name)
}
