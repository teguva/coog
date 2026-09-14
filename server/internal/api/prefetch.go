package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"

	"coog/internal/jobs"
	"coog/internal/meta"
	"coog/internal/settings"
	"coog/internal/store"
)

// handlePrefetchNext queues the next episode when autoDownloadNextEpisode is on.
// TV can call this near end-of-playback; no background scheduler required.
func (s *Server) handlePrefetchNext(w http.ResponseWriter, r *http.Request) {
	var body struct {
		ImdbID  string `json:"imdbId"`
		Season  int    `json:"season"`
		Episode int    `json:"episode"`
		Title   string `json:"title"`
		Year    int    `json:"year"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	imdb := strings.ToLower(strings.TrimSpace(body.ImdbID))
	if !strings.HasPrefix(imdb, "tt") {
		writeError(w, http.StatusBadRequest, "imdb id required")
		return
	}
	cfg := settings.Load(s.cfg.DataPath)
	if !cfg.AutoDownloadNext {
		writeJSON(w, http.StatusOK, map[string]any{
			"ok":      true,
			"skipped": true,
			"reason":  "autoDownloadNextEpisode is off",
		})
		return
	}
	res := s.queueNextEpisode(r.Context(), imdb, body.Season, body.Episode, body.Title, body.Year)
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) queueNextEpisode(ctx context.Context, imdb string, season, episode int, showTitle string, year int) map[string]any {
	imdb = strings.ToLower(strings.TrimSpace(imdb))
	cfg := settings.Load(s.cfg.DataPath)
	if !cfg.AutoDownloadNext {
		return map[string]any{"ok": true, "skipped": true, "reason": "autoDownloadNextEpisode is off"}
	}
	next, ok := s.resolveNextEpisodeCtx(ctx, imdb, season, episode)
	if !ok {
		return map[string]any{"ok": true, "skipped": true, "reason": "no next episode"}
	}
	if next.InLibrary && next.MediaID != "" {
		return map[string]any{"ok": true, "skipped": true, "reason": "next episode already in library", "item": s.mediaJSON(next)}
	}
	resource := imdb + ":" + strconv.Itoa(next.Season) + ":" + strconv.Itoa(next.Episode)
	if job, err := s.store.FindActiveJobByIMDB(imdb, next.Season, next.Episode); err == nil {
		return map[string]any{"ok": true, "skipped": true, "reason": "next episode already queued", "jobId": job.ID, "item": s.mediaJSON(next)}
	}
	id, err := randomID()
	if err != nil {
		return map[string]any{"ok": false, "reason": err.Error()}
	}
	title := strings.TrimSpace(showTitle)
	if title == "" {
		title = next.ShowTitle
	}
	if title == "" {
		title = next.Title
	}
	if next.Season > 0 && next.Episode > 0 {
		title = fmt.Sprintf("%s S%02dE%02d", strings.TrimSpace(title), next.Season, next.Episode)
	}
	job := store.Job{
		ID:      id,
		Type:    jobs.TypeDebrid,
		URL:     "imdb:" + resource,
		Title:   title,
		Status:  jobs.StatusQueued,
		ImdbID:  imdb,
		Year:    year,
		WorkDir: jobs.Dir(s.cfg.DataPath, id),
	}
	if job.Year == 0 {
		job.Year = next.Year
	}
	if err := s.store.InsertJob(job); err != nil {
		return map[string]any{"ok": false, "reason": err.Error()}
	}
	s.note("info", "api", "job.queued", "next episode queued "+job.Title, job.ID, "")
	return map[string]any{"ok": true, "jobId": job.ID, "item": s.mediaJSON(next)}
}

func (s *Server) resolveNextEpisode(r *http.Request, imdb string, season, episode int) (meta.CatalogItem, bool) {
	return s.resolveNextEpisodeCtx(r.Context(), imdb, season, episode)
}

func (s *Server) resolveNextEpisodeCtx(ctx context.Context, imdb string, season, episode int) (meta.CatalogItem, bool) {
	if season <= 0 {
		season = 1
	}
	if episode <= 0 {
		episode = 1
	}
	_, eps, err := s.meta.CatalogShow(ctx, imdb)
	if err != nil {
		return meta.CatalogItem{}, false
	}
	eps = s.attachEpisodeLibrary(eps, imdb)
	var next meta.CatalogItem
	found := false
	for _, ep := range eps {
		if ep.Season < season {
			continue
		}
		if ep.Season == season && ep.Episode <= episode {
			continue
		}
		if !found || ep.Season < next.Season || (ep.Season == next.Season && ep.Episode < next.Episode) {
			next = ep
			found = true
		}
	}
	return next, found
}
