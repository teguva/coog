package api

import (
	"errors"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"coog/internal/meta"
	"coog/internal/probe"
	"coog/internal/store"
)

var artworkGate = make(chan struct{}, 2)

func (s *Server) handleArtwork(w http.ResponseWriter, r *http.Request) {
	s.serveArt(w, r, "backdrop")
}

func (s *Server) handlePoster(w http.ResponseWriter, r *http.Request) {
	s.serveArt(w, r, "poster")
}

func (s *Server) handleBackdrop(w http.ResponseWriter, r *http.Request) {
	s.serveArt(w, r, "backdrop")
}

func (s *Server) handleLogo(w http.ResponseWriter, r *http.Request) {
	s.serveArt(w, r, "logo")
}

func (s *Server) serveArt(w http.ResponseWriter, r *http.Request, kind string) {
	item, err := s.store.GetMedia(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusNotFound, "media not found")
		return
	}
	if !s.gateMaizeMedia(w, r, item.Path, item.RelativePath) {
		return
	}
	size := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("size")))
	if size == "" {
		size = "display"
	}
	if kind == "logo" {
		if img := probe.SidecarLogo(item.Path); img != "" {
			serveImage(w, r, img)
			return
		}
	}
	dest := filepath.Join(s.cfg.DataPath, "artwork", item.ID+"-"+kind+".jpg")
	if kind == "logo" {
		dest = filepath.Join(s.cfg.DataPath, "artwork", item.ID+"-logo.png")
	}
	if err := s.ensureArt(r, item, dest, kind); err != nil {
		slog.Debug("artwork", "id", item.ID, "kind", kind, "err", err)
		writeError(w, http.StatusNotFound, "no artwork")
		return
	}
	servePath := dest
	if kind != "logo" && (size == "thumb" || size == "display") {
		tier := filepath.Join(s.cfg.DataPath, "artwork", item.ID+"-"+kind+"-"+size+filepath.Ext(dest))
		if !fresh(tier, 0) {
			max := 780
			if kind == "backdrop" {
				max = 1920
			}
			if size == "thumb" {
				max = 300
				if kind == "backdrop" {
					max = 480
				}
			}
			_ = meta.DeriveArtFile(dest, tier, max)
		}
		if fresh(tier, 0) {
			servePath = tier
		}
	}
	serveImage(w, r, servePath)
}

func (s *Server) handleCatalogArt(w http.ResponseWriter, r *http.Request) {
	key := strings.TrimSpace(r.PathValue("key"))
	kind := strings.TrimSpace(r.PathValue("kind"))
	size := strings.TrimSpace(r.URL.Query().Get("size"))
	if key == "" || kind == "" {
		writeError(w, http.StatusBadRequest, "key and kind required")
		return
	}
	path, err := s.meta.ResolveCatalogArtPath(key, kind, size)
	if err != nil || path == "" {
		writeError(w, http.StatusNotFound, "no artwork")
		return
	}
	serveImage(w, r, path)
}

func serveImage(w http.ResponseWriter, r *http.Request, path string) {
	w.Header().Set("Content-Type", imageContentType(path))
	w.Header().Set("Cache-Control", "public, max-age=86400")
	http.ServeFile(w, r, path)
}

func imageContentType(path string) string {
	switch strings.ToLower(filepath.Ext(path)) {
	case ".png":
		return "image/png"
	case ".webp":
		return "image/webp"
	case ".gif":
		return "image/gif"
	default:
		return "image/jpeg"
	}
}

func sidecarArt(item store.MediaItem, kind string) string {
	switch kind {
	case "poster":
		return probe.SidecarPoster(item.Path)
	case "logo":
		return probe.SidecarLogo(item.Path)
	default:
		return probe.SidecarBackdrop(item.Path)
	}
}

func (s *Server) ensureArt(r *http.Request, item store.MediaItem, dest, kind string) error {
	if img := sidecarArt(item, kind); img != "" {
		if kind == "logo" {
			return nil
		}
		return s.prober.MaterializeImage(r.Context(), img, dest)
	}
	select {
	case artworkGate <- struct{}{}:
		defer func() { <-artworkGate }()
	case <-r.Context().Done():
		return r.Context().Err()
	}
	if img := sidecarArt(item, kind); img != "" {
		if kind == "logo" {
			return nil
		}
		return s.prober.MaterializeImage(r.Context(), img, dest)
	}

	if fresh(dest, item.MtimeUnix) {
		return nil
	}

	info := s.meta.Ensure(r.Context(), item)
	if !meta.IdentityConfirmed(item.Path, info) {
		if kind == "logo" {
			return errors.New("no artwork")
		}
		if kind == "poster" {
			if err := s.fallbackPoster(dest, item); err == nil {
				return nil
			}
		}
		return s.prober.ExtractStill(r.Context(), item.Path, dest, item.DurationMs)
	}

	if fresh(dest, item.MtimeUnix) {
		return nil
	}
	remote := info.PosterURL
	switch kind {
	case "logo":
		remote = info.LogoURL
	case "poster":
		remote = info.PosterURL
	default:
		remote = info.BackdropURL
	}
	if remote != "" {
		if err := s.meta.FetchFile(r.Context(), remote, dest); err == nil && fresh(dest, 0) {
			return nil
		}
	}
	if kind == "logo" {
		return errors.New("no artwork")
	}
	if kind == "poster" {
		if err := s.fallbackPoster(dest, item); err == nil {
			return nil
		}
	}
	return s.prober.ExtractStill(r.Context(), item.Path, dest, item.DurationMs)
}

func (s *Server) fallbackPoster(dest string, item store.MediaItem) error {
	artDir := filepath.Join(s.cfg.DataPath, "artwork")
	for _, src := range []string{
		filepath.Join(artDir, item.ID+"-backdrop.jpg"),
		filepath.Join(artDir, item.ID+".jpg"),
	} {
		if !fresh(src, 0) {
			continue
		}
		if err := copyArtFile(src, dest); err == nil && fresh(dest, 0) {
			return nil
		}
	}
	return errors.New("no poster fallback")
}

func copyArtFile(src, dest string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return err
	}
	tmp := dest + ".tmp"
	out, err := os.Create(tmp)
	if err != nil {
		return err
	}
	_, copyErr := io.Copy(out, in)
	closeErr := out.Close()
	if copyErr != nil {
		_ = os.Remove(tmp)
		return copyErr
	}
	if closeErr != nil {
		_ = os.Remove(tmp)
		return closeErr
	}
	return os.Rename(tmp, dest)
}

func fresh(path string, itemMtime int64) bool {
	st, err := os.Stat(path)
	if err != nil || st.Size() <= 32 {
		return false
	}
	if itemMtime > 0 && st.ModTime().Unix() < itemMtime {
		return false
	}
	return true
}

func viewItem(item store.MediaItem, info meta.Info, origin string) map[string]any {
	confirmed := meta.IdentityConfirmed(item.Path, info)
	year := item.Year
	imdb, tagline, plot := "", "", ""
	rating := 0.0
	genres := []string{}
	matchStatus := strings.ToLower(strings.TrimSpace(info.MatchStatus))
	if matchStatus == "ignored" || matchStatus == "suggested" {
		// keep status, no catalog copy
	} else if confirmed {
		matchStatus = "matched"
		if year == 0 && info.Year > 0 {
			year = info.Year
		}
		imdb = info.ImdbID
		tagline = info.Tagline
		plot = info.Plot
		rating = info.Rating
		if info.Genres != nil {
			genres = info.Genres
		}
	} else {
		matchStatus = "unmatched"
		imdb = info.ImdbID
	}
	out := map[string]any{
		"id":           item.ID,
		"kind":         item.Kind,
		"title":        item.Title,
		"year":         year,
		"season":       item.Season,
		"episode":      item.Episode,
		"showTitle":    item.ShowTitle,
		"path":         item.Path,
		"relativePath": item.RelativePath,
		"sizeBytes":    item.SizeBytes,
		"mtimeUnix":    item.MtimeUnix,
		"durationMs":   item.DurationMs,
		"codecVideo":   item.CodecVideo,
		"codecAudio":   item.CodecAudio,
		"width":        item.Width,
		"height":       item.Height,
		"hdr":          item.HDR,
		"contentType":  item.ContentType,
		"imdbId":       imdb,
		"matchStatus":  matchStatus,
		"tagline":      tagline,
		"plot":         plot,
		"genres":       genres,
		"rating":       rating,
		"posterUrl":    origin + "/api/v1/media/" + item.ID + "/poster",
		"backdropUrl":  origin + "/api/v1/media/" + item.ID + "/backdrop",
	}
	if confirmed {
		out["logoUrl"] = origin + "/api/v1/media/" + item.ID + "/logo"
	}
	if matchStatus != "ignored" && matchStatus != "suggested" {
		if info.RuntimeMinutes > 0 {
			out["runtimeMinutes"] = info.RuntimeMinutes
		}
		if info.Certification != "" {
			out["certification"] = info.Certification
		}
		if info.Country != "" {
			out["country"] = info.Country
		}
		if info.TMDBID != 0 {
			out["tmdbId"] = info.TMDBID
		}
		if len(info.Cast) > 0 {
			out["cast"] = info.Cast
		}
		if info.Director != nil {
			out["director"] = info.Director
		}
		if info.EpisodeCount > 0 {
			out["episodeCount"] = info.EpisodeCount
		}
	}
	out["streamUrl"] = origin + "/api/v1/media/" + item.ID + "/stream"
	probeError := ""
	if item.CodecVideo == "" {
		if item.DurationMs == 0 && len(item.Probe) == 0 {
			probeError = "not probed"
		} else if item.CodecAudio == "" {
			probeError = "probe found no streams"
		} else {
			probeError = "no video stream"
		}
	}
	out["probeError"] = probeError
	return out
}
