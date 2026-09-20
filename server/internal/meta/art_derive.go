package meta

import (
	"context"
	"fmt"
	"image"
	_ "image/jpeg"
	_ "image/png"
	"image/jpeg"
	"image/png"
	"io"
	"os"
	"path/filepath"
	"strings"

	"golang.org/x/image/draw"
	"golang.org/x/sync/singleflight"
)

// Art size tiers for catalog (and shared library serving):
//   thumb   — row posters / unfocused cards (backdrop ≤480)
//   display — hero / focused cards (backdrop capped by PreferredBackdropMax)
//   orig    — full TMDB master (may be 4K); not served to TVs by default
const (
	ArtSizeOrig    = "orig"
	ArtSizeDisplay = "display"
	ArtSizeThumb   = "thumb"
)

var catalogArtFlight singleflight.Group

func normalizeArtSize(size string) string {
	switch strings.ToLower(strings.TrimSpace(size)) {
	case ArtSizeOrig, "original":
		return ArtSizeOrig
	case ArtSizeThumb, "low", "small":
		return ArtSizeThumb
	default:
		return ArtSizeDisplay
	}
}

func (e *Enricher) catalogArtFile(key, kind, size string) string {
	size = normalizeArtSize(size)
	kind = strings.ToLower(strings.TrimSpace(kind))
	ext := ".jpg"
	if kind == "logo" {
		ext = ".png"
	}
	return filepath.Join(e.catalogArtDir(key), kind+"-"+size+ext)
}

func (e *Enricher) ensureCatalogArt(ctx context.Context, key string, item CatalogItem) {
	key = sanitizeKey(key)
	if key == "" || key == "unknown" {
		return
	}
	_ = os.MkdirAll(e.catalogArtDir(key), 0o755)
	if item.PosterURL != "" {
		_ = e.fetchArtTiers(ctx, key, "poster", preferTMDBSize(item.PosterURL, "w780"), 780, 300)
	}
	if item.BackdropURL != "" || item.TMDBID != 0 || item.ImdbID != "" {
		remote := e.resolveCatalogBackdropURL(ctx, item)
		if remote != "" {
			_ = e.fetchArtTiers(ctx, key, "backdrop", remote, e.BackdropDisplayMax(), 480)
		}
	}
	logoRemote := strings.TrimSpace(item.LogoURL)
	if logoRemote == "" && item.ImdbID != "" {
		logoRemote = metahubLogo(item.ImdbID)
	}
	if logoRemote != "" {
		_ = e.fetchCatalogLogo(ctx, key, logoRemote)
	}
}

func (e *Enricher) fetchCatalogLogo(ctx context.Context, key, remoteURL string) error {
	orig := e.catalogArtFile(key, "logo", ArtSizeOrig)
	if fileOK(orig) {
		return nil
	}
	return e.FetchFile(ctx, remoteURL, orig)
}

func (e *Enricher) fetchArtTiers(ctx context.Context, key, kind, remoteURL string, displayMax, thumbMax int) error {
	key = sanitizeKey(key)
	kind = strings.ToLower(strings.TrimSpace(kind))
	remoteURL = strings.TrimSpace(remoteURL)
	if key == "" || kind == "" || remoteURL == "" {
		return fmt.Errorf("art fetch missing args")
	}
	_, err, _ := catalogArtFlight.Do(key+"|"+kind, func() (any, error) {
		return nil, e.fetchArtTiersLocked(ctx, key, kind, remoteURL, displayMax, thumbMax)
	})
	return err
}

func (e *Enricher) resolveCatalogBackdropURL(ctx context.Context, item CatalogItem) string {
	fallback := preferTMDBSize(strings.TrimSpace(item.BackdropURL), "original")
	tmdbID := item.TMDBID
	kind := item.Kind
	if tmdbID == 0 && item.ImdbID != "" && e.tmdbEnabled() {
		if movie, err := e.tmdbFind(ctx, kind, item.ImdbID); err == nil && movie.ID != 0 {
			tmdbID = movie.ID
			kind = tmdbAPIKind(kind, movie)
		}
	}
	if best := e.tmdbBestBackdropURL(ctx, kind, tmdbID); best != "" {
		return best
	}
	return fallback
}

func (e *Enricher) fetchArtTiersLocked(ctx context.Context, key, kind, remoteURL string, displayMax, thumbMax int) error {
	orig := e.catalogArtFile(key, kind, ArtSizeOrig)
	display := e.catalogArtFile(key, kind, ArtSizeDisplay)
	thumb := e.catalogArtFile(key, kind, ArtSizeThumb)
	srcMark := artSourceMarker(orig)

	needFetch := !fileOK(orig)
	if !needFetch && kind == "backdrop" && shouldUpgradeBackdrop(orig, srcMark, remoteURL) {
		_ = os.Remove(orig)
		needFetch = true
	}
	if needFetch {
		if err := e.FetchFile(ctx, remoteURL, orig); err != nil {
			return err
		}
		_ = writeArtSourceMarker(srcMark, remoteURL)
	} else if kind == "backdrop" && !fileOK(srcMark) {
		_ = writeArtSourceMarker(srcMark, remoteURL)
	}
	if !fileOK(orig) {
		return fmt.Errorf("orig art missing")
	}

	if artTierStale(orig, display) || artLooksUpscaled(orig, display) || artDisplayMismatched(orig, display, displayMax) {
		_ = os.Remove(display)
		if err := deriveArtFile(orig, display, displayMax); err != nil {
			return err
		}
	}
	if artTierStale(orig, thumb) {
		_ = os.Remove(thumb)
		_ = deriveArtFile(orig, thumb, thumbMax)
	}
	return nil
}

// preferTMDBSize rewrites image.tmdb.org /t/p/<old>/ paths to the requested size.
func preferTMDBSize(rawURL, size string) string {
	const marker = "image.tmdb.org/t/p/"
	i := strings.Index(rawURL, marker)
	if i < 0 {
		return rawURL
	}
	rest := rawURL[i+len(marker):]
	slash := strings.IndexByte(rest, '/')
	if slash <= 0 {
		return rawURL
	}
	return rawURL[:i+len(marker)] + size + rest[slash:]
}

func artSourceMarker(origPath string) string {
	return strings.TrimSuffix(origPath, filepath.Ext(origPath)) + ".src"
}

func writeArtSourceMarker(path, remoteURL string) error {
	remoteURL = strings.TrimSpace(remoteURL)
	if path == "" || remoteURL == "" {
		return nil
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, []byte(remoteURL+"\n"), 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func readArtSourceMarker(path string) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

func shouldUpgradeBackdrop(origPath, srcMark, remoteURL string) bool {
	remoteURL = strings.TrimSpace(remoteURL)
	if remoteURL == "" {
		return false
	}
	if prev := readArtSourceMarker(srcMark); prev != "" && prev != remoteURL {
		return true
	}
	if !strings.Contains(remoteURL, "image.tmdb.org/t/p/original/") {
		return false
	}
	w, _, err := imageBounds(origPath)
	if err != nil {
		return true
	}
	// w1280 masters are typically 1280 wide; also refetch when source marker is missing
	// and we only have 1080p while remote may be a newer best-pick URL.
	if w > 0 && w < 1600 {
		return true
	}
	if prev := readArtSourceMarker(srcMark); prev == "" && w > 0 && w < 3000 {
		return true
	}
	return false
}

func artTierStale(orig, tier string) bool {
	if !fileOK(tier) {
		return true
	}
	o, err1 := os.Stat(orig)
	t, err2 := os.Stat(tier)
	if err1 != nil || err2 != nil {
		return true
	}
	return o.ModTime().After(t.ModTime())
}

func artLooksUpscaled(orig, tier string) bool {
	ow, oh, err1 := imageBounds(orig)
	tw, th, err2 := imageBounds(tier)
	if err1 != nil || err2 != nil {
		return false
	}
	return tw > ow+8 || th > oh+8
}

// ArtDisplayNeedsRebuild reports whether dest should be re-derived from src for maxEdge.
func ArtDisplayNeedsRebuild(src, dest string, maxEdge int) bool {
	return artDisplayMismatched(src, dest, maxEdge)
}

// artDisplayMismatched is true when the display tier does not match the preferred max
// (too large after a prefs downgrade, or too small while orig can supply more).
func artDisplayMismatched(orig, display string, maxEdge int) bool {
	if maxEdge <= 0 || !fileOK(display) {
		return false
	}
	dw, dh, err := imageBounds(display)
	if err != nil {
		return true
	}
	dLong := dw
	if dh > dLong {
		dLong = dh
	}
	if dLong > maxEdge+16 {
		return true
	}
	ow, oh, err := imageBounds(orig)
	if err != nil {
		return false
	}
	oLong := ow
	if oh > oLong {
		oLong = oh
	}
	target := maxEdge
	if oLong < target {
		target = oLong
	}
	return dLong < target-32 && oLong > dLong+32
}

func imageBounds(path string) (w, h int, err error) {
	f, err := os.Open(path)
	if err != nil {
		return 0, 0, err
	}
	defer f.Close()
	cfg, _, err := image.DecodeConfig(f)
	if err != nil {
		return 0, 0, err
	}
	return cfg.Width, cfg.Height, nil
}

// DeriveArtFile resizes src into dest with max edge length.
func DeriveArtFile(src, dest string, maxEdge int) error {
	return deriveArtFile(src, dest, maxEdge)
}

func deriveArtFile(src, dest string, maxEdge int) error {
	if maxEdge <= 0 {
		return fmt.Errorf("max edge required")
	}
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	img, format, err := image.Decode(in)
	if err != nil {
		return err
	}
	b := img.Bounds()
	w, h := b.Dx(), b.Dy()
	if w <= 0 || h <= 0 {
		return fmt.Errorf("empty image")
	}
	scale := 1.0
	if w >= h {
		if w > maxEdge {
			scale = float64(maxEdge) / float64(w)
		}
	} else if h > maxEdge {
		scale = float64(maxEdge) / float64(h)
	}
	// Never upscale, and avoid re-encoding when size is unchanged (JPEG banding).
	if scale >= 1.0 {
		return copyFile(src, dest)
	}
	nw := int(float64(w) * scale)
	nh := int(float64(h) * scale)
	if nw < 1 {
		nw = 1
	}
	if nh < 1 {
		nh = 1
	}
	dst := image.NewRGBA(image.Rect(0, 0, nw, nh))
	draw.CatmullRom.Scale(dst, dst.Bounds(), img, b, draw.Over, nil)
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return err
	}
	tmp := dest + ".tmp"
	out, err := os.Create(tmp)
	if err != nil {
		return err
	}
	var encErr error
	switch {
	case strings.EqualFold(filepath.Ext(dest), ".png") || format == "png":
		encErr = png.Encode(out, dst)
	default:
		encErr = jpeg.Encode(out, dst, &jpeg.Options{Quality: 90})
	}
	closeErr := out.Close()
	if encErr != nil {
		_ = os.Remove(tmp)
		return encErr
	}
	if closeErr != nil {
		_ = os.Remove(tmp)
		return closeErr
	}
	return os.Rename(tmp, dest)
}

func copyFile(src, dest string) error {
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return err
	}
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
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

// ResolveCatalogArtPath returns a local file for catalog art, deriving tiers if needed.
func (e *Enricher) ResolveCatalogArtPath(key, kind, size string) (string, error) {
	return e.ResolveCatalogArtPathCtx(context.Background(), key, kind, size)
}

// catalogArtKeyCandidates are on-disk folders that may hold art for a request key.
// Catalog browse stores posters as movie-tt… / series-tt…; downloads may only have tt….
func catalogArtKeyCandidates(key string) []string {
	key = sanitizeKey(key)
	seen := map[string]struct{}{}
	out := make([]string, 0, 4)
	add := func(k string) {
		k = sanitizeKey(k)
		if k == "" || k == "unknown" {
			return
		}
		if _, ok := seen[k]; ok {
			return
		}
		seen[k] = struct{}{}
		out = append(out, k)
	}
	add(key)
	if imdb := imdbFromArtKey(key); imdb != "" {
		add("movie-" + imdb)
		add("series-" + imdb)
		add(imdb)
	}
	return out
}

func (e *Enricher) existingCatalogArtKey(key, kind string) string {
	for _, cand := range catalogArtKeyCandidates(key) {
		if fileOK(e.catalogArtFile(cand, kind, ArtSizeOrig)) ||
			fileOK(e.catalogArtFile(cand, kind, ArtSizeThumb)) ||
			fileOK(e.catalogArtFile(cand, kind, ArtSizeDisplay)) {
			return cand
		}
	}
	return ""
}

func (e *Enricher) catalogArtStorageKey(key, kind string) string {
	key = sanitizeKey(key)
	if existing := e.existingCatalogArtKey(key, kind); existing != "" {
		return existing
	}
	imdb := imdbFromArtKey(key)
	if imdb == "" {
		return key
	}
	lower := strings.ToLower(key)
	if strings.HasPrefix(lower, "series-") {
		return sanitizeKey("series-" + imdb)
	}
	if strings.HasPrefix(lower, "movie-") {
		return sanitizeKey("movie-" + imdb)
	}
	return sanitizeKey("movie-" + imdb)
}

func (e *Enricher) fetchMissingCatalogArt(ctx context.Context, key, kind string) error {
	orig := e.catalogArtFile(key, kind, ArtSizeOrig)
	if fileOK(orig) {
		return nil
	}
	imdb := imdbFromArtKey(key)
	if imdb == "" {
		return os.ErrNotExist
	}
	remote := ""
	switch kind {
	case "poster":
		remote = metahubPoster(imdb)
	case "backdrop":
		remote = metahubBackdrop(imdb)
	case "logo":
		remote = metahubLogo(imdb)
	}
	if remote == "" {
		return os.ErrNotExist
	}
	if err := os.MkdirAll(e.catalogArtDir(key), 0o755); err != nil {
		return err
	}
	return e.FetchFile(ctx, remote, orig)
}

// ResolveCatalogArtPathCtx is like ResolveCatalogArtPath but can fetch missing logos
// and rebuild stale/corrupt display tiers.
func (e *Enricher) ResolveCatalogArtPathCtx(ctx context.Context, key, kind, size string) (string, error) {
	kind = strings.ToLower(strings.TrimSpace(kind))
	size = normalizeArtSize(size)
	key = e.catalogArtStorageKey(key, kind)
	if kind == "logo" {
		orig := e.catalogArtFile(key, "logo", ArtSizeOrig)
		if !fileOK(orig) {
			if err := e.fetchMissingCatalogArt(ctx, key, "logo"); err != nil {
				return "", err
			}
		}
		if !fileOK(orig) {
			return "", os.ErrNotExist
		}
		return orig, nil
	}
	orig := e.catalogArtFile(key, kind, ArtSizeOrig)
	if !fileOK(orig) {
		_ = e.fetchMissingCatalogArt(ctx, key, kind)
		orig = e.catalogArtFile(key, kind, ArtSizeOrig)
	}
	if fileOK(orig) {
		display := e.catalogArtFile(key, kind, ArtSizeDisplay)
		thumb := e.catalogArtFile(key, kind, ArtSizeThumb)
		displayMax := 780
		if kind == "backdrop" {
			displayMax = e.BackdropDisplayMax()
		}
		if artTierStale(orig, display) || artLooksUpscaled(orig, display) || artDisplayMismatched(orig, display, displayMax) {
			_ = os.Remove(display)
			_ = deriveArtFile(orig, display, displayMax)
		}
		if artTierStale(orig, thumb) {
			_ = os.Remove(thumb)
			max := 300
			if kind == "backdrop" {
				max = 480
			}
			_ = deriveArtFile(orig, thumb, max)
		}
	}
	if size == ArtSizeOrig {
		if !fileOK(orig) {
			return "", os.ErrNotExist
		}
		return orig, nil
	}
	path := e.catalogArtFile(key, kind, size)
	if fileOK(path) {
		return path, nil
	}
	if !fileOK(orig) {
		return "", os.ErrNotExist
	}
	max := 780
	if kind == "backdrop" {
		max = e.BackdropDisplayMax()
	}
	if size == ArtSizeThumb {
		max = 300
		if kind == "backdrop" {
			max = 480
		}
	}
	if err := deriveArtFile(orig, path, max); err != nil {
		return orig, nil
	}
	if fileOK(path) {
		return path, nil
	}
	return orig, nil
}

func imdbFromArtKey(key string) string {
	key = strings.ToLower(sanitizeKey(key))
	idx := strings.Index(key, "tt")
	if idx < 0 {
		return ""
	}
	id := key[idx:]
	for i, r := range id {
		if i >= 2 && (r < '0' || r > '9') {
			id = id[:i]
			break
		}
	}
	if len(id) < 9 { // tt + 7 digits
		return ""
	}
	return id
}

// CatalogArtKeyForItem picks the on-disk art folder key for a catalog item.
func CatalogArtKeyForItem(item CatalogItem) string {
	kind := item.Kind
	if kind == "episode" {
		kind = "series"
	}
	if item.ImdbID != "" {
		return sanitizeKey(catalogIMDBKey(kind, item.ImdbID))
	}
	if item.TMDBID != 0 {
		return sanitizeKey(catalogTMDBKey(kind, item.TMDBID))
	}
	return ""
}
