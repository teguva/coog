package api

import (
	"errors"
	"net/http"
	"os"
	"strings"

	"coog/internal/events"
	"coog/internal/library"
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
		targets = []store.MediaItem{item}
		if err := library.RemoveVideo(s.cfg.LibraryPath, item.Path, item.Kind); err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
	}

	removed := make([]string, 0, len(targets))
	for _, it := range targets {
		removed = append(removed, it.ID)
	}
	s.forgetLibraryIDs(removed)
	s.hub.Broadcast(events.Event{Type: "library.changed"})
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "removed": removed, "scope": scope})
}
