package api

import (
	"context"
	"log/slog"
	"net/http"
	"net/url"
	"path/filepath"
	"strings"
	"time"

	"coog/internal/meta"
)

func (s *Server) handleCatalogCacheStats(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.meta.CatalogCacheStats())
}

func (s *Server) handleCatalogCacheRefresh(w http.ResponseWriter, r *http.Request) {
	n := s.meta.RefreshStaleCatalog(r.Context(), 40)
	writeJSON(w, http.StatusOK, map[string]any{"refreshed": n, "stats": s.meta.CatalogCacheStats()})
}

func (s *Server) handleCatalogCacheWarmArt(w http.ResponseWriter, r *http.Request) {
	go s.warmBrowseArtThumbs(context.Background())
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "started": true})
}

func (s *Server) handleCatalogCacheClear(w http.ResponseWriter, r *http.Request) {
	if err := s.meta.ClearCatalogCache(); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "stats": s.meta.CatalogCacheStats()})
}

func (s *Server) metaCacheSweep(ctx context.Context) {
	s.startArtWarmQueue(ctx)
	ticker := time.NewTicker(6 * time.Hour)
	defer ticker.Stop()
	artTicker := time.NewTicker(45 * time.Minute)
	defer artTicker.Stop()
	run := func() {
		n := s.meta.RefreshStaleCatalog(context.Background(), 25)
		if n > 0 {
			slog.Info("catalog cache soft refresh", "refreshed", n)
		}
	}
	run()
	go s.warmBrowseArtThumbs(ctx)
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			run()
		case <-artTicker.C:
			s.warmBrowseArtThumbs(ctx)
		}
	}
}

// warmBrowseArtThumbs pre-derives poster/backdrop thumbs for home, continue/library,
// and recent browse pages so shelves serve from disk instead of download+resize.
func (s *Server) warmBrowseArtThumbs(ctx context.Context) {
	bg, cancel := context.WithTimeout(context.Background(), 8*time.Minute)
	defer cancel()
	select {
	case <-ctx.Done():
		return
	default:
	}

	warmed := 0
	warmItem := func(item meta.CatalogItem) {
		if bg.Err() != nil {
			return
		}
		key := meta.CatalogArtKeyForItem(item)
		if key == "" {
			return
		}
		if s.meta.HasCatalogArt(key, "poster", meta.ArtSizeThumb) &&
			s.meta.HasCatalogArt(key, "backdrop", meta.ArtSizeThumb) {
			return
		}
		s.meta.WarmCatalogArt(bg, item)
		warmed++
	}

	movies, series, err := s.meta.HomeCatalog(bg)
	if err == nil {
		for _, item := range append(movies, series...) {
			warmItem(item)
		}
	}

	// Continue rows (catalog identity) before deep browse.
	if cont, err := s.store.ListContinue(); err == nil {
		for _, e := range cont {
			if bg.Err() != nil {
				break
			}
			kind := e.Kind
			if kind == "" {
				kind = "movie"
			}
			item := meta.CatalogItem{
				Kind:   kind,
				Title:  e.Title,
				Year:   e.Year,
				ImdbID: e.ImdbID,
				TMDBID: e.TmdbID,
			}
			warmItem(item)
		}
	}

	for _, item := range s.meta.RecentBrowseCatalogItems(120) {
		warmItem(item)
	}

	seen := map[string]bool{}
	ids := make([]string, 0, 80)
	if cont, err := s.store.ListContinue(); err == nil {
		for _, e := range cont {
			id := strings.TrimSpace(e.MediaID)
			if id == "" || seen[id] {
				continue
			}
			seen[id] = true
			ids = append(ids, id)
		}
	}
	if items, err := s.store.ListMedia(); err == nil {
		for _, item := range items {
			if item.ID == "" || seen[item.ID] {
				continue
			}
			if item.Kind != "movie" && item.Kind != "episode" && item.Kind != "series" {
				continue
			}
			seen[item.ID] = true
			ids = append(ids, item.ID)
			if len(ids) >= 80 {
				break
			}
		}
	}
	for _, id := range ids {
		if bg.Err() != nil {
			return
		}
		if s.warmMediaArtThumbs(id) {
			warmed++
		}
	}
	if warmed > 0 {
		slog.Info("browse art thumb warm", "touched", warmed)
	}
}

func (s *Server) warmMediaArtThumbs(id string) bool {
	touched := false
	for _, kind := range []string{"poster", "backdrop"} {
		dest := filepath.Join(s.cfg.DataPath, "artwork", id+"-"+kind+".jpg")
		if !fresh(dest, 0) {
			continue
		}
		thumb := filepath.Join(s.cfg.DataPath, "artwork", id+"-"+kind+"-thumb"+filepath.Ext(dest))
		max := 300
		if kind == "backdrop" {
			max = 480
		}
		if fresh(thumb, 0) {
			continue
		}
		if err := meta.DeriveArtFile(dest, thumb, max); err == nil {
			touched = true
		}
	}
	return touched
}

func (s *Server) withCatalogArt(origin string, items []meta.CatalogItem, size string) []meta.CatalogItem {
	origin = strings.TrimRight(origin, "/")
	size = strings.TrimSpace(size)
	if size == "" {
		size = meta.ArtSizeThumb
	}
	out := make([]meta.CatalogItem, len(items))
	for i, item := range items {
		out[i] = s.rewriteItemArt(origin, item, size)
	}
	// Event-triggered warm (Jellyfin path-refresh style): never block the response.
	s.enqueueCatalogArtWarm(out)
	return out
}

func (s *Server) rewriteItemArt(origin string, item meta.CatalogItem, size string) meta.CatalogItem {
	// Episodes keep per-episode still URLs; catalog art keys collapse episodes to the series.
	if item.Kind == "episode" {
		return item
	}
	key := meta.CatalogArtKeyForItem(item)
	if key == "" {
		return item
	}
	esc := url.PathEscape(key)
	if s.meta.HasCatalogArt(key, "poster", size) {
		v := s.meta.CatalogArtVersion(key, "poster", size)
		item.PosterURL = origin + "/api/v1/catalog/art/" + esc + "/poster?size=" + size + artVersionQuery(v)
	}
	if s.meta.HasCatalogArt(key, "backdrop", size) {
		v := s.meta.CatalogArtVersion(key, "backdrop", size)
		item.BackdropURL = origin + "/api/v1/catalog/art/" + esc + "/backdrop?size=" + size + artVersionQuery(v)
	}
	// Always expose a logo endpoint when we have an IMDb id — ResolveCatalogArt fetches on demand.
	if item.ImdbID != "" || s.meta.HasCatalogArt(key, "logo", size) {
		v := s.meta.CatalogArtVersion(key, "logo", size)
		item.LogoURL = origin + "/api/v1/catalog/art/" + esc + "/logo?size=" + size + artVersionQuery(v)
	}
	return item
}

func artVersionQuery(v string) string {
	if v == "" {
		return ""
	}
	return "&v=" + url.QueryEscape(v)
}
