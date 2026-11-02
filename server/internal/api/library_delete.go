package api

import (
	"errors"
	"log/slog"
	"net/http"
	"os"
	"strconv"
	"strings"

	"coog/internal/events"
	"coog/internal/jobs"
	"coog/internal/library"
	"coog/internal/meta"
	"coog/internal/store"
)

func sameShow(a, b store.MediaItem) bool {
	if a.Kind != "episode" || b.Kind != "episode" {
		return false
	}
	ad, bd := library.ArtDir(a.Path), library.ArtDir(b.Path)
	if ad != "" && ad == bd {
		return true
	}
	at, bt := strings.TrimSpace(a.ShowTitle), strings.TrimSpace(b.ShowTitle)
	return at != "" && strings.EqualFold(at, bt)
}

func (s *Server) forgetLibraryIDs(ids []string) {
	if len(ids) == 0 {
		return
	}
	_ = s.store.DeleteContinueByMediaIDs(ids)
	for _, id := range ids {
		_ = s.store.DeleteMedia(id)
		s.meta.Drop(id)
	}
}

func (s *Server) pruneMissingLibrary() int {
	items, err := s.store.ListMedia()
	if err != nil {
		return 0
	}
	var gone []string
	for _, item := range items {
		if strings.TrimSpace(item.Path) == "" {
			continue
		}
		_, err := os.Stat(item.Path)
		if err == nil || !os.IsNotExist(err) {
			continue
		}
		gone = append(gone, item.ID)
	}
	if len(gone) == 0 {
		return 0
	}
	s.forgetLibraryIDs(gone)
	s.hub.Broadcast(events.Event{Type: "library.changed"})
	return len(gone)
}

func collectSeason(items []store.MediaItem, item store.MediaItem) []store.MediaItem {
	seasonDir := library.SeasonDir(item.Path)
	useDir := seasonDir != ""
	out := make([]store.MediaItem, 0)
	seen := map[string]bool{}
	add := func(it store.MediaItem) {
		if seen[it.ID] {
			return
		}
		seen[it.ID] = true
		out = append(out, it)
	}
	for _, it := range items {
		if it.ID == item.ID {
			add(it)
			continue
		}
		if it.Kind == "episode" && it.Season == item.Season && sameShow(item, it) {
			add(it)
			continue
		}
		if useDir && library.WithinRoot(seasonDir, it.Path) {
			add(it)
		}
	}
	return out
}

func collectSeries(items []store.MediaItem, item store.MediaItem) []store.MediaItem {
	showDir := library.ArtDir(item.Path)
	out := make([]store.MediaItem, 0)
	seen := map[string]bool{}
	add := func(it store.MediaItem) {
		if seen[it.ID] {
			return
		}
		seen[it.ID] = true
		out = append(out, it)
	}
	for _, it := range items {
		if it.ID == item.ID {
			add(it)
			continue
		}
		if showDir != "" && library.WithinRoot(showDir, it.Path) {
			add(it)
			continue
		}
		if sameShow(item, it) {
			add(it)
		}
	}
	return out
}

// collectMovieFolder gathers every library video in the same movie folder so
// "Remove from library" clears remux/quality siblings that uniqueLibraryBase left behind.
func collectMovieFolder(items []store.MediaItem, item store.MediaItem) []store.MediaItem {
	if item.Kind != "movie" {
		return []store.MediaItem{item}
	}
	dir := library.ArtDir(item.Path)
	if dir == "" {
		return []store.MediaItem{item}
	}
	out := make([]store.MediaItem, 0)
	seen := map[string]bool{}
	add := func(it store.MediaItem) {
		if seen[it.ID] {
			return
		}
		seen[it.ID] = true
		out = append(out, it)
	}
	add(item)
	for _, it := range items {
		if it.ID == item.ID || it.Kind != "movie" {
			continue
		}
		if library.ArtDir(it.Path) == dir {
			add(it)
		}
	}
	return out
}

func jobMatchesLibraryTarget(job store.Job, mediaIDs map[string]bool, scopes []jobIMDBScope) bool {
	if job.MediaID != "" && mediaIDs[job.MediaID] {
		return true
	}
	imdb := strings.ToLower(strings.TrimSpace(job.ImdbID))
	if imdb == "" {
		return false
	}
	js, je := store.JobSeasonEpisode(job)
	for _, sc := range scopes {
		if imdb != sc.imdb {
			continue
		}
		if sc.season > 0 || sc.episode > 0 {
			if js == sc.season && je == sc.episode {
				return true
			}
			continue
		}
		if js == 0 && je == 0 {
			return true
		}
	}
	return false
}

type jobIMDBScope struct {
	imdb            string
	season, episode int
}

// purgeJobsForLibraryDelete cancels active downloads and drops finished job rows
// tied to the removed media so Play/Sources cannot resume a leftover workdir.
func (s *Server) purgeJobsForLibraryDelete(targets []store.MediaItem) {
	if s.store == nil || len(targets) == 0 {
		return
	}
	mediaIDs := make(map[string]bool, len(targets))
	scopes := make([]jobIMDBScope, 0, len(targets))
	seenScope := map[string]bool{}
	for _, t := range targets {
		mediaIDs[t.ID] = true
		imdb := ""
		if info, ok := s.meta.Peek(t.ID); ok {
			imdb = strings.TrimSpace(info.ImdbID)
		}
		if imdb == "" {
			imdb = meta.FindIMDB(t.Path, t.Title, t.Year)
		}
		imdb = strings.ToLower(strings.TrimSpace(imdb))
		if imdb == "" {
			continue
		}
		sc := jobIMDBScope{imdb: imdb}
		if t.Kind == "episode" {
			sc.season, sc.episode = t.Season, t.Episode
		}
		sk := imdb
		if t.Kind == "episode" {
			sk = imdb + ":" + strconv.Itoa(sc.season) + ":" + strconv.Itoa(sc.episode)
		}
		if seenScope[sk] {
			continue
		}
		seenScope[sk] = true
		scopes = append(scopes, sc)
	}

	list, err := s.store.ListJobs()
	if err != nil {
		slog.Warn("library delete: list jobs", "err", err)
		return
	}
	for _, job := range list {
		if !jobMatchesLibraryTarget(job, mediaIDs, scopes) {
			continue
		}
		prior := job.Status
		switch prior {
		case jobs.StatusFinished, jobs.StatusCancelled, jobs.StatusError, jobs.StatusPaused:
			_ = s.store.DeleteJob(job.ID)
			jobs.Cleanup(s.cfg.DataPath, job.ID)
		default:
			// Mark cancelled so the worker stops, then drop the queue row.
			// Workdir cleanup happens when the worker notices GetJob fails / cancelled
			// (same pattern as handleJobCancel) — avoid deleting open ffmpeg outputs.
			job.Status = jobs.StatusCancelled
			job.Error = "cancelled"
			job.Ready = false
			_ = s.store.UpdateJob(job)
			_ = s.store.DeleteJob(job.ID)
			s.hub.Broadcast(events.Event{Type: "job.cancelled", Job: publicJob(job)})
		}
		slog.Info("library delete purged job", "job", job.ID, "status", prior, "mediaId", job.MediaID, "imdb", job.ImdbID)
	}
}

func (s *Server) handleLibraryDelete(w http.ResponseWriter, r *http.Request) {
	item, err := s.store.GetMedia(r.PathValue("id"))
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "media not found")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if !library.WithinRoot(s.cfg.LibraryPath, item.Path) {
		writeError(w, http.StatusForbidden, "path outside library")
		return
	}
	scope := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("scope")))
	if scope == "" {
		scope = "file"
	}
	items, err := s.store.ListMedia()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	var targets []store.MediaItem
	switch scope {
	case "season":
		if item.Kind != "episode" {
			writeError(w, http.StatusBadRequest, "season delete is only for episodes")
			return
		}
		targets = collectSeason(items, item)
		seasonDir := library.SeasonDir(item.Path)
		if seasonDir != "" && library.WithinRoot(s.cfg.LibraryPath, seasonDir) {
			if err := library.RemoveTree(s.cfg.LibraryPath, seasonDir); err != nil {
				writeError(w, http.StatusInternalServerError, err.Error())
				return
			}
			for _, it := range targets {
				if !library.WithinRoot(seasonDir, it.Path) {
					if err := library.RemoveVideo(s.cfg.LibraryPath, it.Path, it.Kind); err != nil {
						writeError(w, http.StatusInternalServerError, err.Error())
						return
					}
				}
			}
		} else {
			for _, it := range targets {
				if err := library.RemoveVideo(s.cfg.LibraryPath, it.Path, it.Kind); err != nil {
					writeError(w, http.StatusInternalServerError, err.Error())
					return
				}
			}
		}
	case "series":
		if item.Kind != "episode" && item.Kind != "series" {
			writeError(w, http.StatusBadRequest, "series delete is only for shows")
			return
		}
		targets = collectSeries(items, item)
		showDir := library.ArtDir(item.Path)
		if library.WithinRoot(s.cfg.LibraryPath, showDir) {
			if err := library.RemoveTree(s.cfg.LibraryPath, showDir); err != nil {
				writeError(w, http.StatusInternalServerError, err.Error())
				return
			}
			for _, it := range targets {
				if !library.WithinRoot(showDir, it.Path) {
					if err := library.RemoveVideo(s.cfg.LibraryPath, it.Path, it.Kind); err != nil {
						writeError(w, http.StatusInternalServerError, err.Error())
						return
					}
				}
			}
		} else {
			for _, it := range targets {
				if err := library.RemoveVideo(s.cfg.LibraryPath, it.Path, it.Kind); err != nil {
					writeError(w, http.StatusInternalServerError, err.Error())
					return
				}
			}
		}
	default:
		// Movies: wipe every file in the title folder (quality siblings).
		if item.Kind == "movie" {
			targets = collectMovieFolder(items, item)
		} else {
			targets = []store.MediaItem{item}
		}
		for _, it := range targets {
			if err := library.RemoveVideo(s.cfg.LibraryPath, it.Path, it.Kind); err != nil {
				writeError(w, http.StatusInternalServerError, err.Error())
				return
			}
		}
	}

	removed := make([]string, 0, len(targets))
	for _, it := range targets {
		removed = append(removed, it.ID)
	}
	// Cancel matching downloads / drop finished job rows before forgetting IDs
	// so Peek can still resolve IMDB for job matching.
	s.purgeJobsForLibraryDelete(targets)
	s.forgetLibraryIDs(removed)
	s.hub.Broadcast(events.Event{Type: "library.changed"})
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "removed": removed, "scope": scope})
}
