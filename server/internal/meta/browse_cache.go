package meta

import (
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const browsePageTTL = 15 * time.Minute

type browsePageRecord struct {
	FetchedAt time.Time     `json:"fetchedAt"`
	Items     []CatalogItem `json:"items"`
}

var browseMem sync.Map // key -> browsePageRecord

func browsePageCacheKey(q BrowseQuery) string {
	q = NormalizeBrowseQuery(q)
	parts := make([]string, 0, len(q.GenreIDs))
	for _, id := range q.GenreIDs {
		parts = append(parts, strconv.Itoa(id))
	}
	raw := fmt.Sprintf(
		"%s|%s|%s|%d|%d|%s|%.1f|%d",
		q.Kind, q.Sort, strings.Join(parts, ","),
		q.YearMin, q.YearMax, q.Mood, q.MinRating, q.Page,
	)
	// v2: mood packs are genre presets (not keyword OR packs).
	sum := sha1.Sum([]byte("v2|" + raw))
	return "browse-" + hex.EncodeToString(sum[:])
}

func (e *Enricher) browsePagePath(key string) string {
	return filepath.Join(e.catalogMetaDir(), sanitizeKey(key)+".json")
}

func (e *Enricher) peekBrowsePage(q BrowseQuery) ([]CatalogItem, bool) {
	key := browsePageCacheKey(q)
	if v, ok := browseMem.Load(key); ok {
		if rec, ok := v.(browsePageRecord); ok && catalogFresh(rec.FetchedAt, browsePageTTL) {
			return append([]CatalogItem(nil), rec.Items...), true
		}
	}
	b, err := os.ReadFile(e.browsePagePath(key))
	if err != nil {
		return nil, false
	}
	var rec browsePageRecord
	if json.Unmarshal(b, &rec) != nil || !catalogFresh(rec.FetchedAt, browsePageTTL) {
		return nil, false
	}
	browseMem.Store(key, rec)
	return append([]CatalogItem(nil), rec.Items...), true
}

func (e *Enricher) storeBrowsePage(q BrowseQuery, items []CatalogItem) {
	key := browsePageCacheKey(q)
	rec := browsePageRecord{
		FetchedAt: time.Now().UTC(),
		Items:     append([]CatalogItem(nil), items...),
	}
	browseMem.Store(key, rec)
	_ = os.MkdirAll(e.catalogMetaDir(), 0o755)
	b, err := json.MarshalIndent(rec, "", "  ")
	if err != nil {
		return
	}
	path := e.browsePagePath(key)
	tmp := path + ".tmp"
	if os.WriteFile(tmp, b, 0o644) != nil {
		return
	}
	_ = os.Rename(tmp, path)
}

func clearBrowsePageCache() {
	browseMem = sync.Map{}
}

// RecentBrowseCatalogItems returns catalog rows from in-memory browse pages (newest first),
// capped and deduped by CatalogArtKey — used by scheduled art warming.
func (e *Enricher) RecentBrowseCatalogItems(limit int) []CatalogItem {
	if limit <= 0 {
		limit = 120
	}
	type dated struct {
		at    time.Time
		items []CatalogItem
	}
	var pages []dated
	browseMem.Range(func(_, v any) bool {
		rec, ok := v.(browsePageRecord)
		if !ok || len(rec.Items) == 0 {
			return true
		}
		if !catalogFresh(rec.FetchedAt, browsePageTTL) {
			return true
		}
		pages = append(pages, dated{at: rec.FetchedAt, items: rec.Items})
		return true
	})
	if len(pages) == 0 {
		return nil
	}
	sort.Slice(pages, func(i, j int) bool { return pages[i].at.After(pages[j].at) })
	seen := map[string]bool{}
	out := make([]CatalogItem, 0, limit)
	for _, p := range pages {
		for _, item := range p.items {
			key := CatalogArtKeyForItem(item)
			if key == "" || seen[key] {
				continue
			}
			seen[key] = true
			out = append(out, item)
			if len(out) >= limit {
				return out
			}
		}
	}
	return out
}
