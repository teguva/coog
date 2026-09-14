package api

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"os"
	"strconv"
	"strings"

	"golang.org/x/sync/singleflight"

	"coog/internal/acquire"
	"coog/internal/events"
	"coog/internal/library"
	"coog/internal/meta"
	"coog/internal/probe"
	"coog/internal/store"
)

func parseCatalogRef(id string) (imdb, kind string, season, episode int) {
	if !strings.HasPrefix(id, "catalog:") {
		return "", "", 0, 0
	}
	rest := strings.TrimPrefix(id, "catalog:")
	parts := strings.Split(rest, ":")
	if len(parts) == 0 {
		return "", "", 0, 0
	}
	imdb = strings.TrimSpace(parts[0])
	if !strings.HasPrefix(imdb, "tt") {
		return "", "", 0, 0
	}
	if len(parts) >= 3 {
		season, _ = strconv.Atoi(parts[1])
		episode, _ = strconv.Atoi(parts[2])
		return imdb, "series", season, episode
	}
	return imdb, "movie", 0, 0
}

func catalogTrailerRef(id string) (imdb, kind string) {
	imdb, kind, _, _ = parseCatalogRef(id)
	return imdb, kind
}

func applyCatalogMediaID(req *sessionRequest) {
	imdb, kind, season, episode := parseCatalogRef(req.MediaID)
	if imdb == "" {
		return
	}
	if req.ImdbID == "" {
		req.ImdbID = imdb
	}
	if req.Kind == "" {
		if episode > 0 {
			req.Kind = "episode"
		} else {
			req.Kind = kind
		}
	}
	if req.Season == 0 {
		req.Season = season
	}
	if req.Episode == 0 {
		req.Episode = episode
	}
}

func (s *Server) resolveTrailerMedia(ctx context.Context, id string) (item store.MediaItem, imdb, kind string, hasFile bool) {
	item, err := s.store.GetMedia(id)
	if err == nil {
		info, ok := s.meta.Peek(item.ID)
		if !ok {
			info = s.meta.Ensure(ctx, item)
		}
		imdb = strings.TrimSpace(info.ImdbID)
		if imdb == "" {
			imdb = meta.FindIMDB(item.Path, item.Title, item.Year)
		}
		return item, imdb, item.Kind, true
	}
	imdb, kind = catalogTrailerRef(id)
	if imdb == "" {
		return store.MediaItem{}, "", "", false
	}
	if local, ok := s.findLocalByIMDB(imdb, kind); ok {
		return local, imdb, local.Kind, true
	}
	if kind == "movie" {
		if local, ok := s.findLocalByIMDB(imdb, "series"); ok {
			return local, imdb, local.Kind, true
		}
	}
	return store.MediaItem{}, imdb, kind, false
}

func (s *Server) handleTrailer(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	item, imdb, kind, hasFile := s.resolveTrailerMedia(r.Context(), id)
	if hasFile && !s.gateMaizeMedia(w, r, item.Path, item.RelativePath) {
		return
	}
	path := ""
	if hasFile {
		path = probe.SidecarTrailer(item.Path)
		if path == "" && imdb != "" {
			path = probe.LibraryTrailer(s.cfg.LibraryPath, imdb)
		}
	} else if imdb != "" {
		path = probe.LibraryTrailer(s.cfg.LibraryPath, imdb)
	}
	if path != "" {
		serveTrailerFile(w, r, path)
		return
	}
	if imdb != "" {
		cached := s.meta.TrailerPath(imdb)
		if st, err := os.Stat(cached); err == nil && st.Size() > 1024 {
			w.Header().Set("Cache-Control", "public, max-age=604800")
			serveTrailerFile(w, r, cached)
			return
		}
	}
	pageURL, err := s.meta.OfficialTrailer(r.Context(), kind, imdb, 0)
	if err != nil || pageURL == "" {
		writeError(w, http.StatusNotFound, "no trailer")
		return
	}
	if imdb == "" {
		if r.Method == http.MethodHead {
			w.Header().Set("Content-Type", "video/mp4")
			w.WriteHeader(http.StatusOK)
			return
		}
		stdout, wait, err := acquire.Pipe(r.Context(), s.cfg.YTDLP, pageURL)
		if err != nil {
			slog.Debug("trailer ytdlp", "id", id, "err", err)
			writeError(w, http.StatusNotFound, "no trailer")
			return
		}
		defer stdout.Close()
		w.Header().Set("Content-Type", "video/mp2t")
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(http.StatusOK)
		_, _ = io.Copy(w, stdout)
		if err := wait(); err != nil {
			slog.Debug("trailer ytdlp wait", "id", id, "err", err)
		}
		return
	}
	dest := s.meta.TrailerPath(imdb)
	mp4Dest := s.meta.TrailerMP4Path(imdb)
	tsDest := s.meta.TrailerTSPath(imdb)
	// HEAD must not wait on yt-dlp — clients probe existence before play.
	if r.Method == http.MethodHead {
		w.Header().Set("Content-Type", "video/mp4")
		w.WriteHeader(http.StatusOK)
		go s.prefetchTrailer(imdb, pageURL, mp4Dest)
		return
	}
	// Cold path: stream bytes immediately while filling the disk cache.
	// Warm path (above) already returned ServeContent for instant replay.
	if st, err := os.Stat(dest); err == nil && st.Size() > 1024 {
		w.Header().Set("Cache-Control", "public, max-age=604800")
		serveTrailerFile(w, r, dest)
		return
	}
	stdout, wait, err := acquire.StreamAndCache(r.Context(), s.cfg.YTDLP, pageURL, tsDest)
	if err != nil {
		slog.Debug("trailer stream", "id", id, "err", err)
		// Fall back to background cache + blocking download only if stream start fails.
		go s.prefetchTrailer(imdb, pageURL, mp4Dest)
		writeError(w, http.StatusNotFound, "no trailer")
		return
	}
	defer stdout.Close()
	go s.prefetchTrailer(imdb, pageURL, mp4Dest)
	w.Header().Set("Content-Type", "video/mp2t")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_, _ = io.Copy(w, stdout)
	if err := wait(); err != nil {
		slog.Debug("trailer stream wait", "id", id, "err", err)
	}
}

func (s *Server) prefetchTrailer(imdb, pageURL, dest string) {
	_, _, _ = trailerFlight.Do(imdb, func() (any, error) {
		if st, err := os.Stat(dest); err == nil && st.Size() > 1024 {
			return dest, nil
		}
		return dest, acquire.DownloadToFile(context.Background(), s.cfg.YTDLP, pageURL, dest)
	})
}

var trailerFlight singleflight.Group


func serveTrailerFile(w http.ResponseWriter, r *http.Request, path string) {
	f, err := os.Open(path)
	if err != nil {
		writeError(w, http.StatusNotFound, "trailer missing")
		return
	}
	defer f.Close()
	stat, err := f.Stat()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	w.Header().Set("Content-Type", library.ContentType(path))
	w.Header().Set("Accept-Ranges", "bytes")
	clampRangeForExoPlayer(r, stat.Size())
	http.ServeContent(w, r, stat.Name(), stat.ModTime(), f)
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
	removed := []string{item.ID}
	if item.Kind == "episode" && scope == "series" {
		showDir := library.ArtDir(item.Path)
		if !library.WithinRoot(s.cfg.LibraryPath, showDir) {
			writeError(w, http.StatusForbidden, "path outside library")
			return
		}
		items, err := s.store.ListMedia()
		if err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		removed = nil
		for _, it := range items {
			if it.ID == item.ID || library.WithinRoot(showDir, it.Path) {
				removed = append(removed, it.ID)
			}
		}
		if err := library.RemoveTree(s.cfg.LibraryPath, showDir); err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
	} else {
		if err := library.RemoveVideo(s.cfg.LibraryPath, item.Path, item.Kind); err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
	}
	for _, id := range removed {
		_ = s.store.DeleteMedia(id)
		s.meta.Drop(id)
	}
	s.hub.Broadcast(events.Event{Type: "library.changed"})
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "removed": removed})
}
