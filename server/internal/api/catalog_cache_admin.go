package api

import (
	"context"
	"log/slog"
	"net/http"
	"net/url"
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

func (s *Server) handleCatalogCacheClear(w http.ResponseWriter, r *http.Request) {
	if err := s.meta.ClearCatalogCache(); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "stats": s.meta.CatalogCacheStats()})
}

func (s *Server) metaCacheSweep(ctx context.Context) {
	ticker := time.NewTicker(6 * time.Hour)
	defer ticker.Stop()
	run := func() {
		n := s.meta.RefreshStaleCatalog(context.Background(), 25)
		if n > 0 {
			slog.Info("catalog cache soft refresh", "refreshed", n)
		}
	}
	run()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			run()
		}
	}
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
	return out
}

func (s *Server) rewriteItemArt(origin string, item meta.CatalogItem, size string) meta.CatalogItem {
	key := meta.CatalogArtKeyForItem(item)
	if key == "" {
		return item
	}
	esc := url.PathEscape(key)
	if s.meta.HasCatalogArt(key, "poster", size) {
		item.PosterURL = origin + "/api/v1/catalog/art/" + esc + "/poster?size=" + size
	}
	if s.meta.HasCatalogArt(key, "backdrop", size) {
		item.BackdropURL = origin + "/api/v1/catalog/art/" + esc + "/backdrop?size=" + size
	}
	return item
}
