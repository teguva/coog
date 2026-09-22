package api

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"strconv"
	"strings"

	"golang.org/x/sync/singleflight"

	"coog/internal/acquire"
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
	mp4 := ""
	if imdb != "" {
		mp4 = s.meta.TrailerMP4Path(imdb)
		if st, err := os.Stat(mp4); err == nil && st.Size() > 1024 {
			w.Header().Set("Cache-Control", "public, max-age=604800")
			serveTrailerFile(w, r, mp4)
			return
		}
	}
	pageURL, err := s.meta.OfficialTrailer(r.Context(), kind, imdb, 0)
	if err != nil || pageURL == "" {
		writeError(w, http.StatusNotFound, "no trailer")
		return
	}
	if mp4 == "" {
		writeError(w, http.StatusNotFound, "no trailer")
		return
	}
	// HEAD must not wait on yt-dlp. 202 means "fetching" — play once 200/GET has an MP4.
	if r.Method == http.MethodHead {
		w.Header().Set("Content-Type", "video/mp4")
		w.WriteHeader(http.StatusAccepted)
		go s.prefetchTrailer(imdb, pageURL, mp4)
		return
	}
	if err := s.waitTrailerMP4(r.Context(), imdb, pageURL, mp4); err != nil {
		slog.Debug("trailer cache", "id", id, "err", err)
		writeError(w, http.StatusNotFound, "no trailer")
		return
	}
	w.Header().Set("Cache-Control", "public, max-age=604800")
	serveTrailerFile(w, r, mp4)
}

func (s *Server) waitTrailerMP4(ctx context.Context, imdb, pageURL, dest string) error {
	done := make(chan error, 1)
	go func() {
		_, err, _ := trailerFlight.Do(imdb, func() (any, error) {
			if st, err := os.Stat(dest); err == nil && st.Size() > 1024 {
				return dest, nil
			}
			return dest, acquire.DownloadToFile(context.Background(), s.cfg.YTDLP, pageURL, dest)
		})
		done <- err
	}()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case err := <-done:
		if err != nil {
			return err
		}
		st, err := os.Stat(dest)
		if err != nil || st.Size() <= 1024 {
			return errors.New("trailer download empty")
		}
		return nil
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
