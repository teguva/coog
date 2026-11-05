package meta

import (
	"context"
	"image"
	"image/jpeg"
	"image/png"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestCatalogArtKeyCandidates(t *testing.T) {
	got := catalogArtKeyCandidates("tt22084616")
	want := []string{"tt22084616", "movie-tt22084616", "series-tt22084616"}
	if len(got) != len(want) {
		t.Fatalf("got %v want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("got %v want %v", got, want)
		}
	}
}

func TestCatalogArtStorageKeyAliasesIMDb(t *testing.T) {
	dir := t.TempDir()
	e := New(dir, "")
	item := CatalogItem{Kind: "movie", ImdbID: "tt22084616"}
	key := CatalogArtKeyForItem(item)
	if err := os.MkdirAll(e.catalogArtDir(key), 0o755); err != nil {
		t.Fatal(err)
	}
	poster := e.catalogArtFile(key, "poster", ArtSizeOrig)
	if err := os.WriteFile(poster, []byte(strings.Repeat("x", 64)), 0o644); err != nil {
		t.Fatal(err)
	}
	got := e.catalogArtStorageKey("tt22084616", "poster")
	if got != key {
		t.Fatalf("got %s want %s", got, key)
	}
}

func TestPreferTMDBSize(t *testing.T) {
	in := "https://image.tmdb.org/t/p/w1280/i50h4Gz6e9BMGak5l6gg5qfIjcv.jpg"
	got := preferTMDBSize(in, "original")
	want := "https://image.tmdb.org/t/p/original/i50h4Gz6e9BMGak5l6gg5qfIjcv.jpg"
	if got != want {
		t.Fatalf("got %s want %s", got, want)
	}
	other := "https://images.metahub.space/background/medium/tt1/img"
	if preferTMDBSize(other, "original") != other {
		t.Fatal("non-tmdb url changed")
	}
}

func TestDeriveArtFileDoesNotUpscale(t *testing.T) {
	dir := t.TempDir()
	src := dir + "/src.jpg"
	dst := dir + "/dst.jpg"
	img := image.NewRGBA(image.Rect(0, 0, 64, 36))
	f, err := os.Create(src)
	if err != nil {
		t.Fatal(err)
	}
	if err := jpeg.Encode(f, img, &jpeg.Options{Quality: 90}); err != nil {
		t.Fatal(err)
	}
	_ = f.Close()
	if err := deriveArtFile(src, dst, 1920); err != nil {
		t.Fatal(err)
	}
	w, h, err := imageBounds(dst)
	if err != nil {
		t.Fatal(err)
	}
	if w != 64 || h != 36 {
		t.Fatalf("upscaled to %dx%d", w, h)
	}
}

func TestDeriveLogoTiersKeepsPNG(t *testing.T) {
	dir := t.TempDir()
	e := New(dir, "")
	key := "movie-tt0000001"
	_ = os.MkdirAll(e.catalogArtDir(key), 0o755)
	orig := e.catalogArtFile(key, "logo", ArtSizeOrig)
	// 800x200 transparent-ish PNG master
	img := image.NewRGBA(image.Rect(0, 0, 800, 200))
	f, err := os.Create(orig)
	if err != nil {
		t.Fatal(err)
	}
	if err := png.Encode(f, img); err != nil {
		t.Fatal(err)
	}
	_ = f.Close()

	e.ensureLogoTiers(key)
	disp := e.catalogArtFile(key, "logo", ArtSizeDisplay)
	thumb := e.catalogArtFile(key, "logo", ArtSizeThumb)
	dw, dh, err := imageBounds(disp)
	if err != nil {
		t.Fatal(err)
	}
	tw, th, err := imageBounds(thumb)
	if err != nil {
		t.Fatal(err)
	}
	if dw > LogoDisplayMax+1 || dh > LogoDisplayMax+1 {
		t.Fatalf("display too large: %dx%d", dw, dh)
	}
	if tw > LogoThumbMax+1 || th > LogoThumbMax+1 {
		t.Fatalf("thumb too large: %dx%d", tw, th)
	}
	path, err := e.ResolveCatalogArtPath(key, "logo", ArtSizeThumb)
	if err != nil || path != thumb {
		t.Fatalf("resolve thumb: path=%s err=%v", path, err)
	}
}

func TestPickBestTMDBBackdropPrefers4KTextless(t *testing.T) {
	got := pickBestTMDBBackdrop([]tmdbImageAsset{
		{FilePath: "/small.jpg", Width: 1920, Height: 1080, VoteCount: 99},
		{FilePath: "/text-4k.jpg", Width: 3840, Height: 2160, ISO6391: "en", VoteCount: 3},
		{FilePath: "/best.jpg", Width: 3840, Height: 2160, VoteCount: 8},
	})
	if got != "/best.jpg" {
		t.Fatalf("got %s", got)
	}
}

func TestEnsureClarksonBackdropUpgrade(t *testing.T) {
	data := filepath.Join(os.Getenv("HOME"), ".local", "share", "coog")
	key := strings.TrimSpace(os.Getenv("TMDB_API_KEY"))
	if key == "" {
		key = tmdbKeyFromTest()
	}
	e := New(data, key)
	if !e.tmdbEnabled() {
		t.Skip("TMDB key not configured")
	}
	item := CatalogItem{
		Kind:        "series",
		ImdbID:      "tt10541088",
		TMDBID:      117648,
		BackdropURL: "https://image.tmdb.org/t/p/w1280/i50h4Gz6e9BMGak5l6gg5qfIjcv.jpg",
	}
	artKey := CatalogArtKeyForItem(item)
	dir := e.catalogArtDir(artKey)
	for _, name := range []string{"backdrop-orig.jpg", "backdrop-display.jpg", "backdrop-thumb.jpg", "backdrop-orig.src"} {
		_ = os.Remove(filepath.Join(dir, name))
	}
	e.ensureCatalogArt(context.Background(), artKey, item)
	orig := e.catalogArtFile(artKey, "backdrop", ArtSizeOrig)
	disp := e.catalogArtFile(artKey, "backdrop", ArtSizeDisplay)
	ow, oh, err := imageBounds(orig)
	if err != nil {
		t.Fatal(err)
	}
	dw, dh, err := imageBounds(disp)
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("orig=%dx%d display=%dx%d", ow, oh, dw, dh)
	if dw > ow+8 || dh > oh+8 {
		t.Fatalf("display upscaled beyond orig")
	}
	// Catalog derive prefers w1280 masters (not original/4K) to keep TV decode cheap.
	if ow < 1200 {
		t.Fatalf("expected HD-class backdrop master, got %d", ow)
	}
}

func tmdbKeyFromTest() string {
	paths := []string{
		filepath.Join(os.Getenv("HOME"), ".config", "coog", "tmdb.json"),
		filepath.Join(os.Getenv("HOME"), ".config", "tv-shell", "tmdb.json"),
	}
	for _, p := range paths {
		b, err := os.ReadFile(p)
		if err != nil {
			continue
		}
		// {"apiKey":"..."} or {"key":"..."}
		s := string(b)
		for _, field := range []string{`"apiKey"`, `"key"`} {
			i := strings.Index(s, field)
			if i < 0 {
				continue
			}
			rest := s[i+len(field):]
			q1 := strings.IndexByte(rest, '"')
			if q1 < 0 {
				continue
			}
			rest = rest[q1+1:]
			q2 := strings.IndexByte(rest, '"')
			if q2 <= 0 {
				continue
			}
			return rest[:q2]
		}
	}
	return ""
}

func TestDeriveThumbJPEGLeanerThanDisplay(t *testing.T) {
	dir := t.TempDir()
	src := filepath.Join(dir, "src.jpg")
	thumb := filepath.Join(dir, "poster-thumb.jpg")
	display := filepath.Join(dir, "poster-display.jpg")
	img := image.NewRGBA(image.Rect(0, 0, 600, 900))
	for y := 0; y < 900; y++ {
		for x := 0; x < 600; x++ {
			img.Set(x, y, image.NewRGBA(image.Rect(0, 0, 1, 1)).At(0, 0))
		}
	}
	// Fill with varying pixels so JPEG has real entropy.
	for y := 0; y < 900; y++ {
		for x := 0; x < 600; x++ {
			img.SetRGBA(x, y, img.RGBAAt(0, 0))
			img.Pix[(y*img.Stride)+x*4+0] = uint8((x * 3) % 255)
			img.Pix[(y*img.Stride)+x*4+1] = uint8((y * 5) % 255)
			img.Pix[(y*img.Stride)+x*4+2] = uint8((x + y) % 255)
			img.Pix[(y*img.Stride)+x*4+3] = 255
		}
	}
	f, err := os.Create(src)
	if err != nil {
		t.Fatal(err)
	}
	if err := jpeg.Encode(f, img, &jpeg.Options{Quality: 95}); err != nil {
		t.Fatal(err)
	}
	_ = f.Close()
	if err := deriveArtFile(src, thumb, 300); err != nil {
		t.Fatal(err)
	}
	if err := deriveArtFile(src, display, 300); err != nil {
		t.Fatal(err)
	}
	ts, _ := os.Stat(thumb)
	ds, _ := os.Stat(display)
	if ts == nil || ds == nil {
		t.Fatal("missing outputs")
	}
	if ts.Size() >= ds.Size() {
		t.Fatalf("thumb (%d) should be leaner than display-named (%d) at same edge", ts.Size(), ds.Size())
	}
	if artThumbBloated(thumb) {
		t.Fatalf("fresh thumb marked bloated: %d bytes", ts.Size())
	}
}
