package meta

import (
	"context"
	"fmt"
	"image"
	_ "image/jpeg"
	_ "image/png"
	"image/jpeg"
	"image/png"
	"os"
	"path/filepath"
	"strings"

	"golang.org/x/image/draw"
)

// Art size tiers for catalog (and shared library serving).
const (
	ArtSizeOrig    = "orig"
	ArtSizeDisplay = "display"
	ArtSizeThumb   = "thumb"
)

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
		_ = e.fetchArtTiers(ctx, key, "poster", item.PosterURL, 780, 300)
	}
	if item.BackdropURL != "" {
		_ = e.fetchArtTiers(ctx, key, "backdrop", item.BackdropURL, 1920, 480)
	}
}

func (e *Enricher) fetchArtTiers(ctx context.Context, key, kind, remoteURL string, displayMax, thumbMax int) error {
	orig := e.catalogArtFile(key, kind, ArtSizeOrig)
	if !fileOK(orig) {
		if err := e.FetchFile(ctx, remoteURL, orig); err != nil {
			return err
		}
	}
	display := e.catalogArtFile(key, kind, ArtSizeDisplay)
	thumb := e.catalogArtFile(key, kind, ArtSizeThumb)
	if !fileOK(display) {
		_ = deriveArtFile(orig, display, displayMax)
	}
	if !fileOK(thumb) {
		_ = deriveArtFile(orig, thumb, thumbMax)
	}
	return nil
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
		encErr = jpeg.Encode(out, dst, &jpeg.Options{Quality: 85})
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

// ResolveCatalogArtPath returns a local file for catalog art, deriving tiers if needed.
func (e *Enricher) ResolveCatalogArtPath(key, kind, size string) (string, error) {
	key = sanitizeKey(key)
	size = normalizeArtSize(size)
	path := e.catalogArtFile(key, kind, size)
	if fileOK(path) {
		return path, nil
	}
	orig := e.catalogArtFile(key, kind, ArtSizeOrig)
	if !fileOK(orig) {
		return "", os.ErrNotExist
	}
	max := 780
	if kind == "backdrop" {
		max = 1920
	}
	if size == ArtSizeThumb {
		max = 300
		if kind == "backdrop" {
			max = 480
		}
	}
	if size == ArtSizeOrig {
		return orig, nil
	}
	if err := deriveArtFile(orig, path, max); err != nil {
		return orig, nil
	}
	return path, nil
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
