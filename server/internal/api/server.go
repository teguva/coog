package api

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"coog/internal/adminui"
	"coog/internal/auth"
	"coog/internal/config"
	"coog/internal/events"
	"coog/internal/interactive"
	"coog/internal/jobs"
	"coog/internal/library"
	"coog/internal/maize"
	"coog/internal/meta"
	"coog/internal/playback"
	"coog/internal/probe"
	"coog/internal/settings"
	"coog/internal/store"
	"coog/internal/streams"
	"coog/internal/taste"
)

type Server struct {
	cfg           config.Config
	store         *store.Store
	scanner       *library.Scanner
	prober        *probe.Prober
	meta          *meta.Enricher
	hub           *events.Hub
	http          *http.Server
	catalogMu     sync.Mutex
	catalogErr    string
	catalogAt     int64
	rdMu          sync.Mutex
	rdAt          time.Time
	rdStatus      map[string]any
	adminSrc      string
	tasteMu       sync.Mutex
	tasteProf     *taste.Profile
	tasteFP       string
	remuxMu       sync.Mutex
	remuxing      map[string]*remuxProc
	maizeSessions *maize.Sessions
	interactive   *interactive.Service
}

func New(cfg config.Config, st *store.Store, scanner *library.Scanner, prober *probe.Prober) *Server {
	enricher := meta.New(cfg.DataPath, cfg.TMDBKey)
	enricher.SetMetaTTL(time.Duration(cfg.MetaTTLDays) * 24 * time.Hour)
	enricher.SetBackdropDisplayMax(settings.BackdropDisplayMaxEdge(settings.Load(cfg.DataPath).PreferredBackdropMax))
	s := &Server{
		cfg:           cfg,
		store:         st,
		scanner:       scanner,
		prober:        prober,
		meta:          enricher,
		hub:           events.NewHub(),
		maizeSessions: maize.NewSessions(maize.NoopMount{}),
		interactive:   interactive.NewService(cfg, st),
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", s.handleHealth)
	mux.HandleFunc("GET /api/v1/library", s.handleLibraryList)
	mux.HandleFunc("GET /api/v1/library/{id}", s.handleLibraryGet)
	mux.HandleFunc("DELETE /api/v1/library/{id}", s.handleLibraryDelete)
	mux.HandleFunc("POST /api/v1/library/rescan", s.handleLibraryRescan)
	mux.HandleFunc("POST /api/v1/library/{id}/ignore", s.handleLibraryIgnore)
	mux.HandleFunc("POST /api/v1/library/{id}/rematch", s.handleLibraryRematch)
	mux.HandleFunc("GET /api/v1/media/{id}/stream", s.handleStream)
	mux.HandleFunc("GET /api/v1/media/{id}/remux/{file}", s.handleRemux)
	mux.HandleFunc("GET /api/v1/media/{id}/artwork", s.handleArtwork)
	mux.HandleFunc("GET /api/v1/media/{id}/poster", s.handlePoster)
	mux.HandleFunc("GET /api/v1/media/{id}/backdrop", s.handleBackdrop)
	mux.HandleFunc("GET /api/v1/media/{id}/logo", s.handleLogo)
	mux.HandleFunc("GET /api/v1/media/{id}/trailer", s.handleTrailer)
	mux.HandleFunc("GET /api/v1/catalog/art/{key}/{kind}", s.handleCatalogArt)
	mux.HandleFunc("GET /api/v1/catalog/cache/stats", s.handleCatalogCacheStats)
	mux.HandleFunc("POST /api/v1/catalog/cache/refresh", s.handleCatalogCacheRefresh)
	mux.HandleFunc("POST /api/v1/catalog/cache/clear", s.handleCatalogCacheClear)
	mux.HandleFunc("POST /api/v1/playback/sessions", s.handlePlaybackSession)
	mux.HandleFunc("POST /api/v1/playback/prefetch-next", s.handlePrefetchNext)
	mux.HandleFunc("GET /api/v1/catalog/home", s.handleCatalogHome)
	mux.HandleFunc("GET /api/v1/catalog/browse", s.handleCatalogBrowse)
	mux.HandleFunc("GET /api/v1/catalog/genres", s.handleCatalogGenres)
	mux.HandleFunc("GET /api/v1/catalog/moods", s.handleCatalogMoods)
	mux.HandleFunc("GET /api/v1/catalog/continue", s.handleCatalogContinue)
	mux.HandleFunc("POST /api/v1/playback/progress", s.handlePlaybackProgress)
	mux.HandleFunc("POST /api/v1/playback/progress/clear", s.handlePlaybackProgressClear)
	mux.HandleFunc("GET /api/v1/catalog/series/{imdb}", s.handleCatalogShow)
	mux.HandleFunc("GET /api/v1/catalog/streams", s.handleCatalogStreams)
	mux.HandleFunc("GET /api/v1/catalog/search", s.handleCatalogSearch)
	mux.HandleFunc("GET /api/v1/catalog/title/{imdb}/similar", s.handleCatalogSimilar)
	mux.HandleFunc("GET /api/v1/catalog/title/{imdb}", s.handleCatalogTitle)
	mux.HandleFunc("GET /api/v1/catalog/tmdb/{kind}/{id}", s.handleCatalogTMDB)
	mux.HandleFunc("GET /api/v1/catalog/person/{id}", s.handleCatalogPerson)
	mux.HandleFunc("GET /api/v1/settings/streaming", s.handleStreamingSettings)
	mux.HandleFunc("PUT /api/v1/settings/streaming", s.handleStreamingSettings)
	mux.HandleFunc("POST /api/v1/settings/streaming", s.handleStreamingSettings)
	mux.HandleFunc("GET /api/v1/settings/subtitles", s.handleSubtitleSettings)
	mux.HandleFunc("PUT /api/v1/settings/subtitles", s.handleSubtitleSettings)
	mux.HandleFunc("POST /api/v1/settings/subtitles", s.handleSubtitleSettings)
	mux.HandleFunc("GET /api/v1/subtitles", s.handleSubtitlesList)
	mux.HandleFunc("GET /api/v1/subtitles/file", s.handleSubtitleFile)
	mux.HandleFunc("GET /api/v1/jobs", s.handleJobs)
	mux.HandleFunc("POST /api/v1/jobs", s.handleJobs)
	mux.HandleFunc("GET /api/v1/jobs/{id}", s.handleJobGet)
	mux.HandleFunc("POST /api/v1/jobs/{id}/cancel", s.handleJobCancel)
	mux.HandleFunc("POST /api/v1/jobs/{id}/pause", s.handleJobPause)
	mux.HandleFunc("POST /api/v1/jobs/{id}/retry", s.handleJobRetry)
	mux.HandleFunc("GET /api/v1/jobs/{id}/progressive/{file}", s.handleProgressive)
	mux.HandleFunc("GET /api/v1/server/stats", s.handleStats)
	mux.HandleFunc("GET /api/v1/server/activity", s.handleActivity)
	mux.HandleFunc("GET /api/v1/taste", s.handleTaste)
	mux.HandleFunc("PUT /api/v1/taste", s.handleTasteUpdate)
	mux.HandleFunc("POST /api/v1/taste/rebuild", s.handleTasteRebuild)
	mux.HandleFunc("GET /api/v1/maize/status", s.handleMaizeStatus)
	mux.HandleFunc("POST /api/v1/maize/unlock", s.handleMaizeUnlock)
	mux.HandleFunc("POST /api/v1/maize/lock", s.handleMaizeLock)
	mux.HandleFunc("GET /api/v1/maize/home", s.handleMaizeHome)
	mux.HandleFunc("GET /api/v1/maize/library", s.handleMaizeLibrary)
	mux.HandleFunc("GET /api/v1/maize/actors", s.handleMaizeActors)
	mux.HandleFunc("GET /api/v1/maize/actors/{slug}", s.handleMaizeActorGet)
	mux.HandleFunc("PUT /api/v1/maize/actors/{slug}", s.handleMaizeActorPut)
	mux.HandleFunc("POST /api/v1/maize/actors/{slug}/enrich", s.handleMaizeActorEnrich)
	mux.HandleFunc("POST /api/v1/maize/actors/{slug}/headshot", s.handleMaizeActorHeadshotUpload)
	mux.HandleFunc("GET /api/v1/maize/actors/{slug}/headshot", s.handleMaizeActorHeadshot)
	mux.HandleFunc("GET /api/v1/maize/actors/{slug}/gallery/{index}", s.handleMaizeActorGallery)
	mux.HandleFunc("GET /api/v1/maize/media/{id}/funscript", s.handleMaizeFunscript)
	mux.HandleFunc("POST /api/v1/maize/media/{id}/art/upload", s.handleMaizeArtUpload)
	mux.HandleFunc("POST /api/v1/maize/media/{id}/art/frame", s.handleMaizeArtFrame)
	mux.HandleFunc("PUT /api/v1/maize/media/{id}/meta", s.handleMaizeMediaMeta)
	mux.HandleFunc("POST /api/v1/maize/media/{id}/enrich", s.handleMaizeMediaEnrich)
	mux.HandleFunc("GET /api/v1/maize/media/{id}", s.handleMaizeMediaGet)
	mux.HandleFunc("GET /api/v1/maize/sync/status", s.handleMaizeSyncStatus)
	mux.HandleFunc("GET /api/v1/settings/maize", s.handleMaizeSettings)
	mux.HandleFunc("PUT /api/v1/settings/maize", s.handleMaizeSettings)
	mux.HandleFunc("POST /api/v1/settings/maize", s.handleMaizeSettings)
	mux.HandleFunc("GET /api/v1/interactive/status", s.handleInteractiveStatus)
	mux.HandleFunc("GET /api/v1/interactive/engine", s.handleInteractiveEngine)
	mux.HandleFunc("POST /api/v1/interactive/engine/{action}", s.handleInteractiveEngineAction)
	mux.HandleFunc("POST /api/v1/interactive/engine/scan/{action}", s.handleInteractiveScan)
	mux.HandleFunc("POST /api/v1/interactive/engine/battery/refresh", s.handleInteractiveBatteryRefresh)
	mux.HandleFunc("POST /api/v1/interactive/devices/{idx}/test", s.handleInteractiveDeviceTest)
	mux.HandleFunc("PATCH /api/v1/interactive/devices/{idx}", s.handleInteractiveDevicePatch)
	mux.HandleFunc("POST /api/v1/interactive/devices/id/{id}/{action}", s.handleInteractiveDeviceID)
	mux.HandleFunc("DELETE /api/v1/interactive/devices/id/{id}", s.handleInteractiveDeviceID)
	mux.HandleFunc("POST /api/v1/interactive/devices/forget-offline", s.handleInteractiveForgetOffline)
	mux.HandleFunc("POST /api/v1/interactive/load", s.handleInteractiveLoad)
	mux.HandleFunc("POST /api/v1/interactive/params", s.handleInteractiveParams)
	mux.HandleFunc("GET /api/v1/interactive/playback", s.handleInteractivePlayback)
	mux.HandleFunc("GET /api/v1/interactive/device-icons/resolve", s.handleDeviceIcon)
	mux.HandleFunc("GET /ws/v1/sync", s.handleInteractiveSyncWS)
	mux.HandleFunc("GET /ws/v1/engine", s.handleInteractiveEngineWS)
	mux.HandleFunc("POST /api/v1/client/events", s.handleClientEvents)
	mux.HandleFunc("GET /ws", s.hub.ServeHTTP)

	adminFS, adminSrc := adminui.Resolve(cfg.AdminDir)
	mux.Handle("/", adminui.Handler(adminFS))
	s.adminSrc = adminSrc

	handler := withCORS(auth.Bearer(cfg.AuthToken)(mux))
	s.http = &http.Server{
		Addr:              cfg.Listen,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
	}
	return s
}

func (s *Server) Run(ctx context.Context) error {
	go s.watchJobs(ctx)
	go s.metaCacheSweep(ctx)
	if s.interactive != nil {
		s.interactive.Start(ctx)
		defer s.interactive.Stop()
		go s.interactiveMaizeGate(ctx)
	}
	errCh := make(chan error, 1)
	go func() {
		ln, err := net.Listen("tcp", s.cfg.Listen)
		if err != nil {
			errCh <- err
			return
		}
		slog.Info("coog-api listening",
			"addr", ln.Addr().String(),
			"library", s.cfg.LibraryPath,
			"data", s.cfg.DataPath,
			"auth", s.cfg.AuthToken != "",
			"admin", s.adminSrc,
			"version", config.Version,
		)
		errCh <- s.http.Serve(ln)
	}()
	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = s.http.Shutdown(shutdownCtx)
		return nil
	case err := <-errCh:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	}
}

// interactiveMaizeGate keeps intiface running only while at least one Maize adult session is valid.
func (s *Server) interactiveMaizeGate(ctx context.Context) {
	t := time.NewTicker(2 * time.Second)
	defer t.Stop()
	s.syncInteractiveDesired()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			s.syncInteractiveDesired()
		}
	}
}

func (s *Server) syncInteractiveDesired() {
	if s.interactive == nil || !s.interactive.Enabled() {
		return
	}
	s.interactive.SetDesired(s.maizeSessions.ActiveCount() > 0)
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	out := map[string]any{
		"status":  "ok",
		"version": config.Version,
		"ffmpeg":  s.prober.Version(r.Context()),
	}
	if s.interactive != nil {
		st := s.interactive.Status()
		out["interactive"] = map[string]any{
			"enabled":       st["enabled"],
			"engineRunning": st["engineRunning"],
			"connected":     st["connected"],
		}
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleLibraryList(w http.ResponseWriter, r *http.Request) {
	items, err := s.store.ListMedia()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	s.meta.Warm(context.Background(), items)
	origin := strings.TrimRight(publicURL(r, "/"), "/")
	views := make([]any, 0, len(items))
	for _, item := range items {
		if s.isMaizeItem(item.Path, item.RelativePath) {
			continue
		}
		info, _ := s.meta.Peek(item.ID)
		views = append(views, viewItem(item, info, origin))
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": views})
}

func (s *Server) handleLibraryGet(w http.ResponseWriter, r *http.Request) {
	item, err := s.store.GetMedia(r.PathValue("id"))
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "media not found")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if !s.gateMaizeMedia(w, r, item.Path, item.RelativePath) {
		return
	}
	info := s.meta.Ensure(r.Context(), item)
	view := viewItem(item, info, strings.TrimRight(publicURL(r, "/"), "/"))
	if vids := videoCandidates(s.mediaSiblings(item)); len(vids) > 0 {
		view["videos"] = vids
	}
	writeJSON(w, http.StatusOK, view)
}

func (s *Server) handleLibraryRescan(w http.ResponseWriter, r *http.Request) {
	result, err := s.scanner.Scan(r.Context(), s.maizeCfg().Bucket)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if items, err := s.store.ListMedia(); err == nil {
		s.meta.Warm(context.Background(), items)
	}
	writeJSON(w, http.StatusOK, result)
}

func (s *Server) handleStream(w http.ResponseWriter, r *http.Request) {
	item, err := s.store.GetMedia(r.PathValue("id"))
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "media not found")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if !s.gateMaizeMedia(w, r, item.Path, item.RelativePath) {
		return
	}
	f, err := os.Open(item.Path)
	if err != nil {
		writeError(w, http.StatusNotFound, "file missing on disk")
		return
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	ctype := item.ContentType
	if ctype == "" {
		ctype = library.ContentType(item.Path)
	}
	w.Header().Set("Content-Type", ctype)
	w.Header().Set("Accept-Ranges", "bytes")
	asked, clamped := clampRangeForExoPlayer(r, info.Size())
	slog.Info("media stream", "id", item.ID, "range", r.Header.Get("Range"), "asked", asked, "size", info.Size(), "clamped", clamped)
	if asked >= info.Size()+2 {
		s.note("error", "api", "stream.range", fmt.Sprintf(
			"%s: player asked byte %d of a %d byte file. The file looks truncated (MKV cues past EOF).",
			item.Title, asked, info.Size(),
		), "", item.ID)
	}
	http.ServeContent(w, r, info.Name(), info.ModTime(), f)
}

// ExoPlayer probes EOF with Range start == file size (or size+1). Go ServeContent
// maps those to 416, which Media3 treats as a hard source error, so clamp those
// probes to the last byte. Ranges far past EOF (truncated MKV cues) are left
// alone so the player gets HTTP 416 instead of one junk byte.
func clampRangeForExoPlayer(r *http.Request, size int64) (asked int64, clamped bool) {
	if size <= 0 {
		return 0, false
	}
	spec, ok := strings.CutPrefix(r.Header.Get("Range"), "bytes=")
	if !ok || spec == "" || strings.Contains(spec, ",") {
		return 0, false
	}
	startStr, _, found := strings.Cut(spec, "-")
	if !found || startStr == "" {
		return 0, false
	}
	start, err := strconv.ParseInt(startStr, 10, 64)
	if err != nil {
		return 0, false
	}
	asked = start
	if start >= size && start <= size+2 {
		last := size - 1
		r.Header.Set("Range", "bytes="+strconv.FormatInt(last, 10)+"-"+strconv.FormatInt(last, 10))
		return asked, true
	}
	return asked, false
}

type sessionRequest struct {
	MediaID            string                 `json:"mediaId"`
	JobID              string                 `json:"jobId"`
	ImdbID             string                 `json:"imdbId"`
	Kind               string                 `json:"kind"`
	Title              string                 `json:"title"`
	Year               int                    `json:"year"`
	Season             int                    `json:"season"`
	Episode            int                    `json:"episode"`
	ClientCapabilities *playback.Capabilities `json:"clientCapabilities"`
}

func (s *Server) handlePlaybackSession(w http.ResponseWriter, r *http.Request) {
	var req sessionRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	if req.JobID != "" {
		s.handleJobPlayback(w, r, req.JobID)
		return
	}
	if req.MediaID != "" && !strings.HasPrefix(req.MediaID, "catalog:") {
		item, err := s.store.GetMedia(req.MediaID)
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "media not found")
			return
		}
		if err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		s.writeDirectSession(w, r, item, req.ClientCapabilities)
		return
	}
	applyCatalogMediaID(&req)
	if req.ImdbID != "" {
		s.handleCatalogPlayback(w, r, req)
		return
	}
	writeError(w, http.StatusBadRequest, "mediaId or imdbId is required")
}

func (s *Server) handleCatalogPlayback(w http.ResponseWriter, r *http.Request, req sessionRequest) {
	kind := req.Kind
	if kind == "" {
		kind = "movie"
	}
	if item, ok := s.findLocalMedia(req.ImdbID, kind, req.Season, req.Episode); ok {
		if kind == "movie" || (item.Season == req.Season && item.Episode == req.Episode) || (req.Season == 0 && req.Episode == 0) {
			s.writeDirectSession(w, r, item, req.ClientCapabilities)
			return
		}
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
	if job, err := s.store.FindActiveJobByIMDB(req.ImdbID, season, episode); err == nil {
		s.handleJobPlayback(w, r, job.ID)
		return
	}
	id, err := randomID()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	resource := req.ImdbID
	if kind == "series" || kind == "episode" {
		resource = req.ImdbID + ":" + strconv.Itoa(season) + ":" + strconv.Itoa(episode)
	}
	job := store.Job{
		ID:      id,
		Type:    jobs.TypeDebrid,
		URL:     "imdb:" + resource,
		Title:   strings.TrimSpace(req.Title),
		Status:  jobs.StatusQueued,
		ImdbID:  req.ImdbID,
		Year:    req.Year,
		WorkDir: jobs.Dir(s.cfg.DataPath, id),
	}
	if job.Title == "" {
		job.Title = req.ImdbID
	}
	if err := s.store.InsertJob(job); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	s.note("info", "api", "job.queued", "queued Real-Debrid download for "+job.Title, job.ID, "")
	writeJSON(w, http.StatusConflict, map[string]any{
		"error":  "Server is buffering this title. Playback starts when enough of the stream is ready.",
		"method": playback.MethodProgressive,
		"jobId":  job.ID,
		"ready":  false,
		"status": job.Status,
		"imdbId": req.ImdbID,
	})
}

func (s *Server) writeDirectSession(w http.ResponseWriter, r *http.Request, item store.MediaItem, caps *playback.Capabilities) {
	if !s.gateMaizeMedia(w, r, item.Path, item.RelativePath) {
		return
	}
	result, err := playback.Negotiate(item, caps)
	if err != nil {
		slog.Info("playback session rejected", "playback_method", playback.MethodTranscode, "media_id", item.ID, "err", err)
		s.note("error", "api", "session.error", err.Error(), "", item.ID)
		writeJSON(w, http.StatusConflict, map[string]any{
			"error":   err.Error(),
			"method":  playback.MethodTranscode,
			"mediaId": item.ID,
		})
		return
	}
	if result.Method == playback.MethodRemux {
		s.writeRemuxSession(w, r, item, result)
		return
	}
	sid, err := randomID()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	streamURL := s.playbackMediaURL(r, item, "/api/v1/media/"+item.ID+"/stream")
	slog.Info("playback session", "playback_method", result.Method, "media_id", item.ID, "session_id", sid)
	writeJSON(w, http.StatusOK, map[string]any{
		"id":                 sid,
		"method":             result.Method,
		"reason":             result.Reason,
		"url":                streamURL,
		"mediaId":            item.ID,
		"jobId":              "",
		"expectedDurationMs": item.DurationMs,
		"bufferedMs":         item.DurationMs,
	})
}

// playbackMediaURL builds a public media URL and, for Maize items, attaches the adult
// session query so players that only send Authorization still pass the adult gate.
func (s *Server) playbackMediaURL(r *http.Request, item store.MediaItem, path string) string {
	u := publicURL(r, path)
	if !s.isMaizeItem(item.Path, item.RelativePath) {
		return u
	}
	tok := adultToken(r)
	if tok == "" {
		return u
	}
	sep := "?"
	if strings.Contains(u, "?") {
		sep = "&"
	}
	return u + sep + "adult=" + url.QueryEscape(tok)
}

func (s *Server) watchJobs(ctx context.Context) {
	last := map[string]int64{}
	sawReady := map[string]bool{}
	sawStatus := map[string]string{}
	t := time.NewTicker(500 * time.Millisecond)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			list, err := s.store.ListJobs()
			if err != nil {
				continue
			}
			for _, job := range list {
				if last[job.ID] == job.UpdatedAt {
					continue
				}
				last[job.ID] = job.UpdatedAt
				s.hub.Broadcast(events.Event{Type: "job.progress", Job: publicJob(job)})
				if job.Ready && !sawReady[job.ID] {
					sawReady[job.ID] = true
					s.hub.Broadcast(events.Event{Type: "job.ready", Job: publicJob(job)})
					s.hub.Record(events.Activity{
						Level:   "info",
						Source:  "worker",
						Type:    "job.ready",
						Message: job.Title + " is ready to play",
						JobID:   job.ID,
						MediaID: job.MediaID,
					})
				}
				if job.Status == jobs.StatusFinished && sawStatus[job.ID] != jobs.StatusFinished {
					s.hub.Broadcast(events.Event{Type: "job.finished", Job: publicJob(job)})
					s.hub.Broadcast(events.Event{Type: "library.changed"})
					s.hub.Record(events.Activity{
						Level:   "info",
						Source:  "worker",
						Type:    "job.finished",
						Message: job.Title + " finished",
						JobID:   job.ID,
						MediaID: job.MediaID,
					})
					// Chain binge: when an episode lands in the library, queue the next
					// if missing (same prefs / autoDownloadNextEpisode gate).
					if se, ep := store.JobSeasonEpisode(job); job.ImdbID != "" && (se > 0 || ep > 0) {
						go s.queueNextEpisode(context.Background(), job.ImdbID, se, ep, job.Title, job.Year)
					}
					// Drop from the downloads queue once the library (or ephemeral
					// workdir) holds the file. Temp workdir goes away only when we
					// already copied into the library.
					if job.MediaID != "" {
						jobs.Cleanup(s.cfg.DataPath, job.ID)
						_ = s.store.DeleteJob(job.ID)
						delete(last, job.ID)
						delete(sawReady, job.ID)
						sawStatus[job.ID] = jobs.StatusFinished
						continue
					}
				}
				if job.Status == jobs.StatusError && sawStatus[job.ID] != jobs.StatusError {
					s.hub.Record(events.Activity{
						Level:   "error",
						Source:  "worker",
						Type:    "job.error",
						Message: job.Error,
						JobID:   job.ID,
						MediaID: job.MediaID,
					})
				}
				sawStatus[job.ID] = job.Status
			}
		}
	}
}

func (s *Server) handleStats(w http.ResponseWriter, r *http.Request) {
	count, _ := s.store.Stats()
	var disk any
	if usage, err := diskUsage(s.cfg.LibraryPath); err == nil {
		disk = usage
	}
	counts, _ := s.store.JobCounts()
	hb, _ := s.store.WorkerHeartbeat()
	stale := hb.UpdatedAt == 0 || time.Now().Unix()-hb.UpdatedAt > 15
	s.catalogMu.Lock()
	catalogErr, catalogAt := s.catalogErr, s.catalogAt
	s.catalogMu.Unlock()
	writeJSON(w, http.StatusOK, map[string]any{
		"version":                  config.Version,
		"ffmpeg":                   s.prober.Version(r.Context()),
		"libraryPath":              s.cfg.LibraryPath,
		"dataPath":                 s.cfg.DataPath,
		"mediaCount":               count,
		"disk":                     disk,
		"concurrentTranscodeLimit": playback.ConcurrentTranscodeLimit,
		"jobs": map[string]int{
			"queued":      counts.Queued,
			"downloading": counts.Downloading,
			"ready":       counts.Ready,
			"finished":    counts.Finished,
			"error":       counts.Error,
			"cancelled":   counts.Cancelled,
			"active":      counts.Active(),
		},
		"worker": map[string]any{
			"updatedAt": hb.UpdatedAt,
			"pid":       hb.PID,
			"stale":     stale,
			"seenAgoS": func() int64 {
				if hb.UpdatedAt == 0 {
					return -1
				}
				return time.Now().Unix() - hb.UpdatedAt
			}(),
		},
		"realDebrid":     s.realDebridStatus(r.Context()),
		"catalogError":   catalogErr,
		"catalogErrorAt": catalogAt,
	})
}

func (s *Server) realDebridStatus(ctx context.Context) map[string]any {
	cfg := settings.Load(s.cfg.DataPath)
	token := strings.TrimSpace(cfg.RealDebridToken)
	if token == "" {
		return map[string]any{"configured": false}
	}
	s.rdMu.Lock()
	if s.rdStatus != nil && time.Since(s.rdAt) < 30*time.Second {
		cached := s.rdStatus
		s.rdMu.Unlock()
		return cached
	}
	s.rdMu.Unlock()
	user, err := streams.User(ctx, token)
	out := map[string]any{"configured": true}
	if err != nil {
		out["error"] = err.Error()
	} else {
		out["username"] = user.Username
		out["type"] = user.Type
		out["premium"] = user.Premium > 0 || strings.EqualFold(user.Type, "premium")
		out["premiumSeconds"] = user.Premium
		out["expiration"] = user.Expiration
	}
	s.rdMu.Lock()
	s.rdAt = time.Now()
	s.rdStatus = out
	s.rdMu.Unlock()
	return out
}

func publicURL(r *http.Request, path string) string {
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	if proto := r.Header.Get("X-Forwarded-Proto"); proto != "" {
		scheme = proto
	}
	host := r.Host
	if host == "" {
		host = "127.0.0.1:8090"
	}
	return scheme + "://" + host + path
}

func withCORS(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

func randomID() (string, error) {
	var b [8]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(b[:]), nil
}
