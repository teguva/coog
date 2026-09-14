package meta

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

const defaultMetaTTL = 30 * 24 * time.Hour

type catalogRecord struct {
	FetchedAt time.Time   `json:"fetchedAt"`
	Item      CatalogItem `json:"item"`
}

type showRecord struct {
	FetchedAt time.Time     `json:"fetchedAt"`
	Cover     CatalogItem   `json:"cover"`
	Episodes  []CatalogItem `json:"episodes"`
}

type personRecord struct {
	FetchedAt time.Time `json:"fetchedAt"`
	Person    Person    `json:"person"`
}

func (e *Enricher) dataRoot() string {
	return filepath.Dir(e.dir)
}

func (e *Enricher) catalogMetaDir() string {
	return filepath.Join(e.dataRoot(), "meta", "catalog")
}

func (e *Enricher) personMetaDir() string {
	return filepath.Join(e.dataRoot(), "meta", "person")
}

func (e *Enricher) catalogArtDir(key string) string {
	return filepath.Join(e.dataRoot(), "artwork", "catalog", sanitizeKey(key))
}

func (e *Enricher) trailerDir() string {
	return filepath.Join(e.dataRoot(), "trailers")
}

func (e *Enricher) TrailerMP4Path(imdb string) string {
	return filepath.Join(e.trailerDir(), sanitizeKey(imdb)+".hq.mp4")
}

func (e *Enricher) TrailerTSPath(imdb string) string {
	return filepath.Join(e.trailerDir(), sanitizeKey(imdb)+".hq.ts")
}

func (e *Enricher) trailerPath(imdb string) string {
	// Prefer merged MP4 cache; fall back to stream-cache MPEG-TS from first play.
	mp4 := e.TrailerMP4Path(imdb)
	if st, err := os.Stat(mp4); err == nil && st.Size() > 1024 {
		return mp4
	}
	return e.TrailerTSPath(imdb)
}

func sanitizeKey(key string) string {
	key = strings.TrimSpace(key)
	var b strings.Builder
	for _, r := range key {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == '-', r == '_', r == '.':
			b.WriteRune(r)
		default:
			b.WriteByte('_')
		}
	}
	out := b.String()
	if out == "" {
		return "unknown"
	}
	return out
}

func catalogIMDBKey(kind, imdb string) string {
	kind = strings.TrimSpace(kind)
	if kind == "" {
		kind = "movie"
	}
	if kind == "episode" {
		kind = "series"
	}
	return kind + "-" + strings.TrimSpace(imdb)
}

func catalogTMDBKey(kind string, id int) string {
	kind = strings.TrimSpace(kind)
	if kind == "" {
		kind = "movie"
	}
	return fmt.Sprintf("tmdb-%s-%d", kind, id)
}

func (e *Enricher) metaTTL() time.Duration {
	if e == nil {
		return defaultMetaTTL
	}
	if e.ttl > 0 {
		return e.ttl
	}
	return defaultMetaTTL
}

func (e *Enricher) SetMetaTTL(d time.Duration) {
	if d <= 0 {
		d = defaultMetaTTL
	}
	e.ttl = d
}

var catalogMem sync.Map // key -> catalogRecord | showRecord | personRecord

func catalogFresh(fetched time.Time, ttl time.Duration) bool {
	if fetched.IsZero() {
		return false
	}
	return time.Since(fetched) < ttl
}

// catalogTitleFresh is false for series titles cached without cast so we refetch
// once aggregate_credits is available instead of serving a blank cast until TTL.
func catalogTitleFresh(kind string, item CatalogItem, fetched time.Time, ttl time.Duration) bool {
	if !catalogFresh(fetched, ttl) {
		return false
	}
	if (kind == "series" || kind == "episode") && len(item.Cast) == 0 {
		return false
	}
	return true
}

func (e *Enricher) peekCatalogTitle(kind, imdb string) (CatalogItem, time.Time, bool) {
	key := catalogIMDBKey(kind, imdb)
	if v, ok := catalogMem.Load(key); ok {
		if rec, ok := v.(catalogRecord); ok {
			return rec.Item, rec.FetchedAt, true
		}
	}
	path := filepath.Join(e.catalogMetaDir(), sanitizeKey(key)+".json")
	b, err := os.ReadFile(path)
	if err != nil {
		return CatalogItem{}, time.Time{}, false
	}
	var rec catalogRecord
	if json.Unmarshal(b, &rec) != nil || rec.Item.ImdbID == "" && rec.Item.TMDBID == 0 {
		return CatalogItem{}, time.Time{}, false
	}
	catalogMem.Store(key, rec)
	return rec.Item, rec.FetchedAt, true
}

func (e *Enricher) storeCatalogTitle(kind, imdb string, item CatalogItem) {
	key := catalogIMDBKey(kind, imdb)
	rec := catalogRecord{FetchedAt: time.Now().UTC(), Item: item}
	catalogMem.Store(key, rec)
	_ = os.MkdirAll(e.catalogMetaDir(), 0o755)
	b, err := json.MarshalIndent(rec, "", "  ")
	if err != nil {
		return
	}
	path := filepath.Join(e.catalogMetaDir(), sanitizeKey(key)+".json")
	tmp := path + ".tmp"
	if os.WriteFile(tmp, b, 0o644) != nil {
		return
	}
	_ = os.Rename(tmp, path)
	go e.ensureCatalogArt(context.Background(), key, item)
}

func (e *Enricher) peekCatalogTMDB(kind string, id int) (CatalogItem, time.Time, bool) {
	key := catalogTMDBKey(kind, id)
	if v, ok := catalogMem.Load(key); ok {
		if rec, ok := v.(catalogRecord); ok {
			return rec.Item, rec.FetchedAt, true
		}
	}
	path := filepath.Join(e.catalogMetaDir(), sanitizeKey(key)+".json")
	b, err := os.ReadFile(path)
	if err != nil {
		return CatalogItem{}, time.Time{}, false
	}
	var rec catalogRecord
	if json.Unmarshal(b, &rec) != nil {
		return CatalogItem{}, time.Time{}, false
	}
	catalogMem.Store(key, rec)
	return rec.Item, rec.FetchedAt, true
}

func (e *Enricher) storeCatalogTMDB(kind string, id int, item CatalogItem) {
	key := catalogTMDBKey(kind, id)
	rec := catalogRecord{FetchedAt: time.Now().UTC(), Item: item}
	catalogMem.Store(key, rec)
	_ = os.MkdirAll(e.catalogMetaDir(), 0o755)
	b, err := json.MarshalIndent(rec, "", "  ")
	if err != nil {
		return
	}
	path := filepath.Join(e.catalogMetaDir(), sanitizeKey(key)+".json")
	tmp := path + ".tmp"
	if os.WriteFile(tmp, b, 0o644) != nil {
		return
	}
	_ = os.Rename(tmp, path)
	if item.ImdbID != "" {
		e.storeCatalogTitle(kind, item.ImdbID, item)
	} else {
		go e.ensureCatalogArt(context.Background(), key, item)
	}
}

func (e *Enricher) peekShow(imdb string) (CatalogItem, []CatalogItem, time.Time, bool) {
	key := "show-" + strings.TrimSpace(imdb)
	if v, ok := catalogMem.Load(key); ok {
		if rec, ok := v.(showRecord); ok {
			return rec.Cover, rec.Episodes, rec.FetchedAt, true
		}
	}
	path := filepath.Join(e.catalogMetaDir(), sanitizeKey(key)+".json")
	b, err := os.ReadFile(path)
	if err != nil {
		return CatalogItem{}, nil, time.Time{}, false
	}
	var rec showRecord
	if json.Unmarshal(b, &rec) != nil {
		return CatalogItem{}, nil, time.Time{}, false
	}
	catalogMem.Store(key, rec)
	return rec.Cover, rec.Episodes, rec.FetchedAt, true
}

func (e *Enricher) storeShow(imdb string, cover CatalogItem, eps []CatalogItem) {
	key := "show-" + strings.TrimSpace(imdb)
	rec := showRecord{FetchedAt: time.Now().UTC(), Cover: cover, Episodes: eps}
	catalogMem.Store(key, rec)
	_ = os.MkdirAll(e.catalogMetaDir(), 0o755)
	b, err := json.MarshalIndent(rec, "", "  ")
	if err != nil {
		return
	}
	path := filepath.Join(e.catalogMetaDir(), sanitizeKey(key)+".json")
	tmp := path + ".tmp"
	if os.WriteFile(tmp, b, 0o644) != nil {
		return
	}
	_ = os.Rename(tmp, path)
	e.storeCatalogTitle("series", imdb, cover)
}

type tmdbSeasonRecord struct {
	FetchedAt time.Time  `json:"fetchedAt"`
	Season    tmdbSeason `json:"season"`
}

func tmdbSeasonCacheKey(tvID, season int) string {
	return fmt.Sprintf("tmdb-season-%d-%d", tvID, season)
}

func (e *Enricher) peekTMDBSeason(tvID, season int) (tmdbSeason, time.Time, bool) {
	key := tmdbSeasonCacheKey(tvID, season)
	if v, ok := catalogMem.Load(key); ok {
		if rec, ok := v.(tmdbSeasonRecord); ok {
			return rec.Season, rec.FetchedAt, true
		}
	}
	path := filepath.Join(e.catalogMetaDir(), sanitizeKey(key)+".json")
	b, err := os.ReadFile(path)
	if err != nil {
		return tmdbSeason{}, time.Time{}, false
	}
	var rec tmdbSeasonRecord
	if json.Unmarshal(b, &rec) != nil {
		return tmdbSeason{}, time.Time{}, false
	}
	catalogMem.Store(key, rec)
	return rec.Season, rec.FetchedAt, true
}

func (e *Enricher) storeTMDBSeason(tvID, season int, payload tmdbSeason) {
	key := tmdbSeasonCacheKey(tvID, season)
	rec := tmdbSeasonRecord{FetchedAt: time.Now().UTC(), Season: payload}
	catalogMem.Store(key, rec)
	_ = os.MkdirAll(e.catalogMetaDir(), 0o755)
	b, err := json.MarshalIndent(rec, "", "  ")
	if err != nil {
		return
	}
	path := filepath.Join(e.catalogMetaDir(), sanitizeKey(key)+".json")
	tmp := path + ".tmp"
	if os.WriteFile(tmp, b, 0o644) != nil {
		return
	}
	_ = os.Rename(tmp, path)
}

func (e *Enricher) peekPerson(id int) (Person, time.Time, bool) {
	key := strconv.Itoa(id)
	if v, ok := catalogMem.Load("person-" + key); ok {
		if rec, ok := v.(personRecord); ok {
			return rec.Person, rec.FetchedAt, true
		}
	}
	path := filepath.Join(e.personMetaDir(), sanitizeKey(key)+".json")
	b, err := os.ReadFile(path)
	if err != nil {
		return Person{}, time.Time{}, false
	}
	var rec personRecord
	if json.Unmarshal(b, &rec) != nil {
		return Person{}, time.Time{}, false
	}
	catalogMem.Store("person-"+key, rec)
	return rec.Person, rec.FetchedAt, true
}

func (e *Enricher) storePerson(p Person) {
	if p.TMDBID == 0 {
		return
	}
	key := strconv.Itoa(p.TMDBID)
	rec := personRecord{FetchedAt: time.Now().UTC(), Person: p}
	catalogMem.Store("person-"+key, rec)
	_ = os.MkdirAll(e.personMetaDir(), 0o755)
	b, err := json.MarshalIndent(rec, "", "  ")
	if err != nil {
		return
	}
	path := filepath.Join(e.personMetaDir(), sanitizeKey(key)+".json")
	tmp := path + ".tmp"
	if os.WriteFile(tmp, b, 0o644) != nil {
		return
	}
	_ = os.Rename(tmp, path)
}

// CatalogCacheStats summarizes on-disk catalog cache usage.
func (e *Enricher) CatalogCacheStats() map[string]any {
	titles, people, trailers := 0, 0, 0
	var bytes int64
	_ = filepath.Walk(e.catalogMetaDir(), func(path string, info os.FileInfo, err error) error {
		if err != nil || info == nil || info.IsDir() {
			return nil
		}
		if strings.HasSuffix(path, ".json") {
			titles++
			bytes += info.Size()
		}
		return nil
	})
	_ = filepath.Walk(e.personMetaDir(), func(path string, info os.FileInfo, err error) error {
		if err != nil || info == nil || info.IsDir() {
			return nil
		}
		if strings.HasSuffix(path, ".json") {
			people++
			bytes += info.Size()
		}
		return nil
	})
	_ = filepath.Walk(filepath.Join(e.dataRoot(), "trailers"), func(path string, info os.FileInfo, err error) error {
		if err != nil || info == nil || info.IsDir() {
			return nil
		}
		trailers++
		bytes += info.Size()
		return nil
	})
	_ = filepath.Walk(filepath.Join(e.dataRoot(), "artwork", "catalog"), func(path string, info os.FileInfo, err error) error {
		if err != nil || info == nil || info.IsDir() {
			return nil
		}
		bytes += info.Size()
		return nil
	})
	return map[string]any{
		"titles":   titles,
		"people":   people,
		"trailers": trailers,
		"bytes":    bytes,
		"ttlDays":  int(e.metaTTL() / (24 * time.Hour)),
	}
}

// ClearCatalogCache removes catalog meta, person meta, catalog artwork, and trailers.
func (e *Enricher) ClearCatalogCache() error {
	catalogMem = sync.Map{}
	roots := []string{
		e.catalogMetaDir(),
		e.personMetaDir(),
		filepath.Join(e.dataRoot(), "artwork", "catalog"),
		filepath.Join(e.dataRoot(), "trailers"),
	}
	for _, root := range roots {
		_ = os.RemoveAll(root)
	}
	return nil
}

// RefreshStaleCatalog forces re-fetch of catalog title files older than TTL (best-effort).
func (e *Enricher) RefreshStaleCatalog(ctx context.Context, limit int) (refreshed int) {
	if limit <= 0 {
		limit = 20
	}
	ttl := e.metaTTL()
	_ = filepath.Walk(e.catalogMetaDir(), func(path string, info os.FileInfo, err error) error {
		if err != nil || info == nil || info.IsDir() || refreshed >= limit {
			return nil
		}
		if !strings.HasSuffix(path, ".json") {
			return nil
		}
		if time.Since(info.ModTime()) < ttl {
			return nil
		}
		base := strings.TrimSuffix(filepath.Base(path), ".json")
		if strings.HasPrefix(base, "show-") {
			imdb := strings.TrimPrefix(base, "show-")
			if _, _, err := e.fetchCatalogShow(ctx, imdb); err == nil {
				refreshed++
			}
			return nil
		}
		if strings.HasPrefix(base, "tmdb-") {
			return nil
		}
		// kind-imdb e.g. movie-tt123
		parts := strings.SplitN(base, "-", 2)
		if len(parts) != 2 || !strings.HasPrefix(parts[1], "tt") {
			return nil
		}
		if _, err := e.fetchCatalogTitle(ctx, parts[0], parts[1]); err == nil {
			refreshed++
		}
		return nil
	})
	return refreshed
}

func (e *Enricher) TrailerPath(imdb string) string {
	return e.trailerPath(imdb)
}

// HasCatalogArt reports whether any usable art file exists for the given key/kind.
func (e *Enricher) HasCatalogArt(key, kind, size string) bool {
	key = sanitizeKey(key)
	if key == "" || key == "unknown" {
		return false
	}
	size = normalizeArtSize(size)
	if fileOK(e.catalogArtFile(key, kind, size)) {
		return true
	}
	return fileOK(e.catalogArtFile(key, kind, ArtSizeOrig))
}

// CatalogArtVersion is a cache-buster based on the art file mtime (unix seconds).
func (e *Enricher) CatalogArtVersion(key, kind, size string) string {
	key = sanitizeKey(key)
	if key == "" || key == "unknown" {
		return ""
	}
	size = normalizeArtSize(size)
	path := e.catalogArtFile(key, kind, size)
	if !fileOK(path) {
		path = e.catalogArtFile(key, kind, ArtSizeOrig)
	}
	st, err := os.Stat(path)
	if err != nil {
		return ""
	}
	return strconv.FormatInt(st.ModTime().Unix(), 10)
}

// HydrateFromCache overlays richer on-disk catalog meta onto a list row when present.
func (e *Enricher) HydrateFromCache(item CatalogItem) CatalogItem {
	kind := item.Kind
	if kind == "episode" {
		kind = "series"
	}
	if kind == "" {
		kind = "movie"
	}
	var cached CatalogItem
	var ok bool
	if item.ImdbID != "" {
		cached, _, ok = e.peekCatalogTitle(kind, item.ImdbID)
	}
	if !ok && item.TMDBID != 0 {
		cached, _, ok = e.peekCatalogTMDB(kind, item.TMDBID)
	}
	if !ok {
		return item
	}
	if cached.Title != "" {
		item.Title = cached.Title
	}
	if cached.Plot != "" {
		item.Plot = cached.Plot
	}
	if cached.PosterURL != "" {
		item.PosterURL = cached.PosterURL
	}
	if cached.BackdropURL != "" {
		item.BackdropURL = cached.BackdropURL
	}
	if cached.Year > 0 {
		item.Year = cached.Year
	}
	if cached.Rating > 0 {
		item.Rating = cached.Rating
	}
	if len(cached.Genres) > 0 {
		item.Genres = cached.Genres
	}
	if cached.TMDBID != 0 {
		item.TMDBID = cached.TMDBID
	}
	if cached.RuntimeMinutes > 0 {
		item.RuntimeMinutes = cached.RuntimeMinutes
	}
	if cached.Certification != "" {
		item.Certification = cached.Certification
	}
	if cached.Country != "" {
		item.Country = cached.Country
	}
	if cached.EpisodeCount > 0 {
		item.EpisodeCount = cached.EpisodeCount
	}
	if len(cached.Cast) > 0 {
		item.Cast = cached.Cast
	}
	if cached.Director != nil {
		item.Director = cached.Director
	}
	if cached.ReleasePhase != "" {
		item.ReleasePhase = cached.ReleasePhase
	}
	if cached.ReleaseDate != "" {
		item.ReleaseDate = cached.ReleaseDate
	}
	return item
}

func (e *Enricher) HydrateFromCacheAll(items []CatalogItem) []CatalogItem {
	out := make([]CatalogItem, len(items))
	for i, item := range items {
		out[i] = e.HydrateFromCache(item)
	}
	return out
}

