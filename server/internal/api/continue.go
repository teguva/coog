package api

import (
	"encoding/json"
	"fmt"
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
	kind = normalizeContinueKind(kind)
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

func normalizeContinueKind(kind string) string {
	kind = strings.ToLower(strings.TrimSpace(kind))
	if kind == "episode" {
		return "series"
	}
	if kind == "" {
		return "movie"
	}
	return kind
}

func (s *Server) enrichProgressIdentity(body *playbackProgressRequest) {
	body.ImdbID = strings.TrimSpace(body.ImdbID)
	body.MediaID = strings.TrimSpace(body.MediaID)
	body.Kind = normalizeContinueKind(body.Kind)
	if body.ImdbID != "" || body.MediaID == "" || s.meta == nil {
		return
	}
	if info, ok := s.meta.Peek(body.MediaID); ok {
		if imdb := strings.TrimSpace(info.ImdbID); strings.HasPrefix(strings.ToLower(imdb), "tt") {
			body.ImdbID = imdb
		}
		if body.TmdbID == 0 && info.TMDBID > 0 {
			body.TmdbID = info.TMDBID
		}
	}
}

// pruneContinueAliases drops sibling keys so the same title isn't listed twice
// (e.g. media:<id> plus movie:tt… after library vs catalog playback).
func (s *Server) pruneContinueAliases(keepKey string, body playbackProgressRequest) {
	seen := map[string]bool{keepKey: true}
	drop := func(key string) {
		key = strings.TrimSpace(key)
		if key == "" || seen[key] {
			return
		}
		seen[key] = true
		_ = s.store.DeleteContinue(key)
	}
	if body.MediaID != "" {
		drop("media:" + body.MediaID)
	}
	imdb := strings.ToLower(strings.TrimSpace(body.ImdbID))
	if strings.HasPrefix(imdb, "tt") {
		drop(body.Kind + ":" + imdb)
	}
	if body.TmdbID > 0 {
		drop(fmt.Sprintf("%s:tmdb:%d", body.Kind, body.TmdbID))
	}
}

func (s *Server) handlePlaybackProgress(w http.ResponseWriter, r *http.Request) {
	var body playbackProgressRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	s.enrichProgressIdentity(&body)
	key := continueKey(body.Kind, body.ImdbID, body.TmdbID, body.MediaID)
	if key == "" {
		writeError(w, http.StatusBadRequest, "imdb, tmdb, or media id required")
		return
	}
	if body.PositionMs < 15_000 {
		s.pruneContinueAliases(key, body)
		_ = s.store.DeleteContinue(key)
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "removed": true})
		return
	}
	if body.DurationMs > 0 && body.PositionMs*100 >= body.DurationMs*90 {
		s.pruneContinueAliases(key, body)
		_ = s.store.DeleteContinue(key)
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "removed": true})
		return
	}
	entry := store.ContinueEntry{
		Key:        key,
		ImdbID:     strings.TrimSpace(body.ImdbID),
		TmdbID:     body.TmdbID,
		Kind:       body.Kind,
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
	s.pruneContinueAliases(key, body)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) handlePlaybackProgressClear(w http.ResponseWriter, r *http.Request) {
	var body playbackProgressRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	s.enrichProgressIdentity(&body)
	key := continueKey(body.Kind, body.ImdbID, body.TmdbID, body.MediaID)
	if key == "" {
		writeError(w, http.StatusBadRequest, "imdb, tmdb, or media id required")
		return
	}
	s.pruneContinueAliases(key, body)
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
	entries = s.dedupeContinueEntries(entries)
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

func (s *Server) continueIdentity(entry store.ContinueEntry) string {
	kind := normalizeContinueKind(entry.Kind)
	imdb := strings.ToLower(strings.TrimSpace(entry.ImdbID))
	if !strings.HasPrefix(imdb, "tt") && entry.MediaID != "" && s.meta != nil {
		if info, ok := s.meta.Peek(entry.MediaID); ok {
			imdb = strings.ToLower(strings.TrimSpace(info.ImdbID))
		}
	}
	if strings.HasPrefix(imdb, "tt") {
		if kind == "series" {
			return fmt.Sprintf("%s:%s:%d:%d", kind, imdb, entry.Season, entry.Episode)
		}
		return kind + ":" + imdb
	}
	if mid := strings.TrimSpace(entry.MediaID); mid != "" {
		return "media:" + mid
	}
	return entry.Key
}

func (s *Server) dedupeContinueEntries(entries []store.ContinueEntry) []store.ContinueEntry {
	if len(entries) < 2 {
		return entries
	}
	seen := map[string]bool{}
	out := make([]store.ContinueEntry, 0, len(entries))
	for _, entry := range entries {
		id := s.continueIdentity(entry)
		if id == "" || seen[id] {
			if id != "" && entry.Key != "" && s.store != nil {
				_ = s.store.DeleteContinue(entry.Key)
			}
			continue
		}
		seen[id] = true
		out = append(out, entry)
	}
	return out
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
	kind := normalizeContinueKind(entry.Kind)
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
