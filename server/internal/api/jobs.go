package api

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"unicode"

	"coog/internal/events"
	"coog/internal/jobs"
	"coog/internal/playback"
	"coog/internal/store"
	"coog/internal/streams"
)

type createJobRequest struct {
	Type           string   `json:"type"`
	URL            string   `json:"url"`
	Title          string   `json:"title"`
	ImdbID         string   `json:"imdbId"`
	InfoHash       string   `json:"infoHash"`
	Kind           string   `json:"kind"`
	Season         int      `json:"season"`
	Episode        int      `json:"episode"`
	Year           int      `json:"year"`
	Quality        string   `json:"quality"`
	SizeBytes      int64    `json:"sizeBytes"`
	SizeLabel      string   `json:"sizeLabel"`
	Pack           string   `json:"pack"`
	Tags           []string `json:"tags"`
	Languages      []string `json:"languages"`
	ReleaseTitle   string   `json:"releaseTitle"`
	Force          bool     `json:"force"` // explicit Sources pick — allow another file beside library
}

func (s *Server) handleJobs(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		list, err := s.store.ListJobs()
		if err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		out := make([]store.Job, 0, len(list))
		for _, job := range list {
			switch job.Status {
			case jobs.StatusCancelled:
				// Legacy cancelled rows: treat like delete.
				jobs.Cleanup(s.cfg.DataPath, job.ID)
				_ = s.store.DeleteJob(job.ID)
				continue
			case jobs.StatusFinished:
				// Finished downloads leave library files; drop the queue row from the list.
				continue
			}
			out = append(out, publicJob(job))
		}
		writeJSON(w, http.StatusOK, map[string]any{"items": out})
	case http.MethodPost:
		s.handleCreateJob(w, r)
	default:
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (s *Server) handleCreateJob(w http.ResponseWriter, r *http.Request) {
	var req createJobRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	req.URL = strings.TrimSpace(req.URL)
	req.ImdbID = strings.TrimSpace(req.ImdbID)
	req.InfoHash = streams.InfoHash(req.InfoHash)
	if req.Type == "" {
		req.Type = jobs.TypeYTDLP
	}
	if req.Type != jobs.TypeYTDLP && req.Type != jobs.TypeHTTP && req.Type != jobs.TypeDebrid && req.Type != jobs.TypeTorrent {
		writeError(w, http.StatusBadRequest, "unsupported job type")
		return
	}
	if req.Type == jobs.TypeDebrid || req.Type == jobs.TypeTorrent {
		if req.ImdbID == "" && strings.HasPrefix(req.URL, "imdb:") {
			req.ImdbID = strings.TrimPrefix(req.URL, "imdb:")
			if i := strings.Index(req.ImdbID, ":"); i > 0 {
				req.ImdbID = req.ImdbID[:i]
			}
		}
		if req.Type == jobs.TypeTorrent {
			if req.InfoHash == "" {
				req.InfoHash = streams.InfoHash(req.URL)
			}
			if req.InfoHash == "" {
				writeError(w, http.StatusBadRequest, "infoHash is required")
				return
			}
		}
		if req.Type == jobs.TypeDebrid && req.ImdbID == "" && req.InfoHash == "" {
			writeError(w, http.StatusBadRequest, "imdbId or infoHash is required")
			return
		}
		if req.InfoHash != "" {
			if existing, err := s.store.FindActiveJobByHash(req.InfoHash); err == nil {
				// Season packs share one infoHash across episodes — only reuse when S/E matches.
				if jobMatchesEpisodeScope(existing, req.Kind, req.Season, req.Episode) {
					writeJSON(w, http.StatusOK, publicJob(existing))
					return
				}
			}
		}
		resource := req.ImdbID
		if req.Kind == "series" || req.Kind == "episode" {
			season, episode := req.Season, req.Episode
			if season <= 0 {
				season = 1
			}
			if episode <= 0 {
				episode = 1
			}
			resource = req.ImdbID + ":" + strconv.Itoa(season) + ":" + strconv.Itoa(episode)
		}
		if resource != "" {
			req.URL = "imdb:" + resource
		}
	}
	if req.URL == "" && req.Type != jobs.TypeDebrid && req.Type != jobs.TypeTorrent {
		writeError(w, http.StatusBadRequest, "url is required")
		return
	}
	// Play / auto-select must not start a second download when the title is already on disk.
	// Explicit Sources picks send force=true to store another file beside the original.
	if !req.Force && req.ImdbID != "" {
		kind := req.Kind
		if kind == "" {
			kind = "movie"
		}
		season, episode := req.Season, req.Episode
		if kind == "series" || kind == "episode" {
			if season <= 0 {
				season = 1
			}
			if episode <= 0 {
				episode = 1
			}
		} else {
			season, episode = 0, 0
		}
		if item, ok := s.findLocalMedia(req.ImdbID, kind, season, episode); ok {
			title := strings.TrimSpace(req.Title)
			if title == "" {
				title = item.Title
			}
			writeJSON(w, http.StatusOK, map[string]any{
				"id":      "library:" + item.ID,
				"type":    "library",
				"title":   title,
				"status":  jobs.StatusFinished,
				"ready":   true,
				"mediaId": item.ID,
				"imdbId":  req.ImdbID,
				"progress": 1.0,
			})
			return
		}
	}
	id, err := randomID()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	job := store.Job{
		ID:           id,
		Type:         req.Type,
		URL:          req.URL,
		Title:        strings.TrimSpace(req.Title),
		Status:       jobs.StatusQueued,
		WorkDir:      jobs.Dir(s.cfg.DataPath, id),
		ImdbID:       req.ImdbID,
		InfoHash:     req.InfoHash,
		Year:         req.Year,
		Quality:      strings.TrimSpace(req.Quality),
		SizeBytes:    req.SizeBytes,
		SizeLabel:    strings.TrimSpace(req.SizeLabel),
		Pack:         strings.TrimSpace(req.Pack),
		Tags:         req.Tags,
		Languages:    req.Languages,
		ReleaseTitle: strings.TrimSpace(req.ReleaseTitle),
	}
	if job.SizeLabel == "" && job.SizeBytes > 0 {
		job.SizeLabel = streams.FormatSizeLabel(job.SizeBytes)
	}
	if err := s.store.InsertJob(job); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	queued := "queued " + job.Type + " download"
	if title := strings.TrimSpace(job.Title); title != "" {
		queued += " for " + title
	}
	s.note("info", "api", "job.queued", queued, job.ID, "")
	writeJSON(w, http.StatusCreated, publicJob(job))
}

func (s *Server) handleJobCancel(w http.ResponseWriter, r *http.Request) {
	job, err := s.store.GetJob(r.PathValue("id"))
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "job not found")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if job.Status == jobs.StatusFinished {
		writeError(w, http.StatusConflict, "job already finished")
		return
	}
	prior := job.Status
	// Mark cancelled first so the worker kills ffmpeg/yt-dlp. Drop the queue
	// row immediately for the UI, but leave the workdir for the worker to close
	// files cleanly — deleting open ffmpeg outputs has wedged the single worker
	// and blocked every later job (including Real-Debrid downloads).
	job.Status = jobs.StatusCancelled
	job.Error = "cancelled"
	job.Ready = false
	if err := s.store.UpdateJob(job); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	_ = s.store.DeleteJob(job.ID)
	if prior == jobs.StatusError || prior == jobs.StatusPaused {
		jobs.Cleanup(s.cfg.DataPath, job.ID)
	}
	s.note("warn", "api", "job.cancelled", "cancelled "+job.Title, job.ID, job.MediaID)
	s.hub.Broadcast(events.Event{Type: "job.cancelled", Job: publicJob(job)})
	writeJSON(w, http.StatusOK, publicJob(job))
}

func (s *Server) handleJobPause(w http.ResponseWriter, r *http.Request) {
	job, err := s.store.GetJob(r.PathValue("id"))
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "job not found")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	switch job.Status {
	case jobs.StatusFinished, jobs.StatusCancelled, jobs.StatusError, jobs.StatusPaused:
		writeError(w, http.StatusConflict, "job cannot be paused")
		return
	}
	job.Status = jobs.StatusPaused
	job.Error = ""
	if err := s.store.UpdateJob(job); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	s.note("info", "api", "job.paused", "paused "+job.Title, job.ID, job.MediaID)
	writeJSON(w, http.StatusOK, publicJob(job))
}

func (s *Server) handleJobRetry(w http.ResponseWriter, r *http.Request) {
	job, err := s.store.GetJob(r.PathValue("id"))
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "job not found")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if job.Status != jobs.StatusError && job.Status != jobs.StatusPaused {
		writeError(w, http.StatusConflict, "job is not retryable")
		return
	}
	job.Status = jobs.StatusQueued
	job.Error = ""
	job.Ready = false
	job.Progress = 0
	job.BufferedMs = 0
	if err := s.store.UpdateJob(job); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	s.note("info", "api", "job.retry", "re-queued "+job.Title, job.ID, job.MediaID)
	writeJSON(w, http.StatusOK, publicJob(job))
}

func (s *Server) handleJobGet(w http.ResponseWriter, r *http.Request) {
	job, err := s.store.GetJob(r.PathValue("id"))
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "job not found")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, publicJob(job))
}

// jobMatchesEpisodeScope reports whether an existing active job can satisfy this enqueue.
// Season packs share one infoHash; different episodes must not reuse each other's jobs.
func jobMatchesEpisodeScope(existing store.Job, kind string, season, episode int) bool {
	es, ee := store.JobSeasonEpisode(existing)
	series := strings.EqualFold(kind, "series") || strings.EqualFold(kind, "episode") || season > 0 || episode > 0
	if series {
		if season <= 0 {
			season = 1
		}
		if episode <= 0 {
			episode = 1
		}
		return es == season && ee == episode
	}
	return es == 0 && ee == 0
}

func (s *Server) handleProgressive(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	file := r.PathValue("file")
	if id == "" || !safeHLSFile(file) {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	if _, err := s.store.GetJob(id); err != nil {
		writeError(w, http.StatusNotFound, "job not found")
		return
	}
	path := filepath.Join(jobs.HLSDir(s.cfg.DataPath, id), file)
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

func (s *Server) handleJobPlayback(w http.ResponseWriter, r *http.Request, jobID string) {
	job, err := s.store.GetJob(jobID)
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "job not found")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if job.Status == jobs.StatusError {
		writeError(w, http.StatusConflict, job.Error)
		return
	}
	if job.Status == jobs.StatusFinished && job.MediaID != "" {
		item, err := s.store.GetMedia(job.MediaID)
		if err == nil {
			s.writeDirectSession(w, r, item, nil)
			return
		}
	}
	if !job.Ready && job.Status != jobs.StatusReady && job.Status != jobs.StatusFinished {
		writeJSON(w, http.StatusConflict, map[string]any{
			"error":   "job is not ready for playback yet",
			"method":  playback.MethodProgressive,
			"jobId":   job.ID,
			"ready":   false,
			"status":  job.Status,
			"mediaId": job.MediaID,
		})
		return
	}
	sid, err := randomID()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	url := publicURL(r, "/api/v1/jobs/"+job.ID+"/progressive/"+jobs.PlaylistName)
	slog.Info("playback session", "playback_method", playback.MethodProgressive, "job_id", job.ID, "session_id", sid)
	writeJSON(w, http.StatusOK, map[string]any{
		"id":                 sid,
		"method":             playback.MethodProgressive,
		"reason":             "growing HLS while download continues",
		"url":                url,
		"mediaId":            job.MediaID,
		"jobId":              job.ID,
		"expectedDurationMs": job.ExpectedDurationMs,
		"bufferedMs":         job.BufferedMs,
	})
}

func safeHLSFile(name string) bool {
	if name == "" || strings.Contains(name, "/") || strings.Contains(name, "\\") || strings.Contains(name, "..") {
		return false
	}
	if name == jobs.PlaylistName {
		return true
	}
	if !strings.HasPrefix(name, "seg_") || !strings.HasSuffix(name, ".ts") {
		return false
	}
	for _, c := range strings.TrimSuffix(strings.TrimPrefix(name, "seg_"), ".ts") {
		if !unicode.IsDigit(c) {
			return false
		}
	}
	return true
}
