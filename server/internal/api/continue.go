package api

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"

	"coog/internal/meta"
	"coog/internal/store"
)

type playbackProgressRequest struct {
	ImdbID     string `json:"imdbId"`
	TmdbID     int    `json:"tmdbId"`
	Kind       string `json:"kind"`
	Title      string `json:"title"`
	Year       int    `json:"year"`
	Season     int    `json:"season"`
	Episode    int    `json:"episode"`
	PositionMs int64  `json:"positionMs"`
	DurationMs int64  `json:"durationMs"`
	MediaID    string `json:"mediaId"`
}

func continueKey(kind, imdb string, tmdbID int, mediaID string) string {
	kind = strings.ToLower(strings.TrimSpace(kind))
	if kind == "episode" {
		kind = "series"
	}
	if kind == "" {
		kind = "movie"
	}
	imdb = strings.ToLower(strings.TrimSpace(imdb))
	if strings.HasPrefix(imdb, "tt") {
		return kind + ":" + imdb
	}
	if tmdbID > 0 {
		return kind + ":tmdb:" + strconv.Itoa(tmdbID)
	}
	mediaID = strings.TrimSpace(mediaID)
	if mediaID != "" {
		return "media:" + mediaID
	}
	return ""
}

func (s *Server) handlePlaybackProgress(w http.ResponseWriter, r *http.Request) {
	var body playbackProgressRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	key := continueKey(body.Kind, body.ImdbID, body.TmdbID, body.MediaID)
	if key == "" {
		writeError(w, http.StatusBadRequest, "imdb, tmdb, or media id required")
		return
	}
	kind := strings.ToLower(strings.TrimSpace(body.Kind))
	if kind == "episode" {
		kind = "series"
	}
	if kind == "" {
		kind = "movie"
	}
	if body.PositionMs < 15_000 {
		_ = s.store.DeleteContinue(key)
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "removed": true})
		return
	}
	if body.DurationMs > 0 && body.PositionMs*100 >= body.DurationMs*90 {
		_ = s.store.DeleteContinue(key)
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "removed": true})
		return
	}
	entry := store.ContinueEntry{
		Key:        key,
		ImdbID:     strings.TrimSpace(body.ImdbID),
		TmdbID:     body.TmdbID,
		Kind:       kind,
		Title:      strings.TrimSpace(body.Title),
		Year:       body.Year,
		Season:     body.Season,
		Episode:    body.Episode,
		PositionMs: body.PositionMs,
		DurationMs: body.DurationMs,
		MediaID:    strings.TrimSpace(body.MediaID),
	}
	if err := s.store.UpsertContinue(entry); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) handlePlaybackProgressClear(w http.ResponseWriter, r *http.Request) {
	var body playbackProgressRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	key := continueKey(body.Kind, body.ImdbID, body.TmdbID, body.MediaID)
	if key == "" {
		writeError(w, http.StatusBadRequest, "imdb, tmdb, or media id required")
		return
	}
	if err := s.store.DeleteContinue(key); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "removed": true})
}

func (s *Server) handleCatalogContinue(w http.ResponseWriter, r *http.Request) {
	entries, err := s.store.ListContinue()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	localMovies, localSeries := s.imdbIndex()
	out := make([]map[string]any, 0, len(entries))
	origin := strings.TrimRight(publicURL(r, "/"), "/")
	for _, entry := range entries {
		if s.continueEntryIsMaize(entry) {
			continue
		}
		item := s.continueAsCatalog(r, entry)
		local := localMovies
		if item.Kind == "series" || item.Kind == "episode" {
			local = localSeries
		}
		item = attachLibrary([]meta.CatalogItem{item}, local)[0]
		item = s.rewriteItemArt(origin, item, meta.ArtSizeThumb)
		out = append(out, s.mediaJSON(item))
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": out})
}

// continueEntryIsMaize reports whether a continue row points at Maize library media.
// Public catalog continue must never surface adult titles.
func (s *Server) continueEntryIsMaize(entry store.ContinueEntry) bool {
	id := strings.TrimSpace(entry.MediaID)
	if id == "" || strings.HasPrefix(id, "catalog:") || strings.HasPrefix(id, "continue:") {
		return false
	}
	media, err := s.store.GetMedia(id)
	if err != nil {
		return false
	}
	return s.isMaizeItem(media.Path, media.RelativePath)
}

func (s *Server) continueAsCatalog(r *http.Request, entry store.ContinueEntry) meta.CatalogItem {
	kind := entry.Kind
	if kind == "episode" {
		kind = "series"
	}
	if kind == "" {
		kind = "movie"
	}
	mediaID := strings.TrimSpace(entry.MediaID)
	if strings.HasPrefix(mediaID, "catalog:") || strings.HasPrefix(mediaID, "continue:") {
		mediaID = ""
	}
	item := meta.CatalogItem{
		ID:         "continue:" + entry.Key,
		Kind:       kind,
		Title:      entry.Title,
		Year:       entry.Year,
		ImdbID:     entry.ImdbID,
		TMDBID:     entry.TmdbID,
		MediaID:    mediaID,
		InLibrary:  mediaID != "",
		Season:     entry.Season,
		Episode:    entry.Episode,
		PositionMs: entry.PositionMs,
		DurationMs: entry.DurationMs,
	}
	if strings.HasPrefix(strings.ToLower(entry.ImdbID), "tt") {
		if remote, err := s.meta.CatalogTitle(r.Context(), kind, entry.ImdbID); err == nil {
			remote.PositionMs = entry.PositionMs
			remote.DurationMs = entry.DurationMs
			remote.MediaID = mediaID
			if mediaID != "" {
				remote.InLibrary = true
			}
			if kind == "series" {
				remote.Season = entry.Season
				remote.Episode = entry.Episode
			}
			if remote.Title == "" {
				remote.Title = entry.Title
			}
			return remote
		}
	}
	if mediaID != "" {
		if media, err := s.store.GetMedia(mediaID); err == nil {
			info, _ := s.meta.Peek(media.ID)
			local := localAsCatalog(media, info, kind)
			local.ID = item.ID
			local.PositionMs = entry.PositionMs
			local.DurationMs = entry.DurationMs
			if local.Title == "" {
				local.Title = entry.Title
			}
			return local
		}
	}
	return item
}
