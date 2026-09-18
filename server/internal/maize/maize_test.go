package maize

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestHashAndVerifyPIN(t *testing.T) {
	hash, salt, err := HashPIN("1234")
	if err != nil {
		t.Fatal(err)
	}
	cfg := Config{PinHash: hash, PinSalt: salt}
	if !VerifyPIN(cfg, "1234") {
		t.Fatal("expected pin to verify")
	}
	if VerifyPIN(cfg, "9999") {
		t.Fatal("wrong pin should fail")
	}
}

func TestIsMaizeRel(t *testing.T) {
	if !IsMaizeRel("Maize/foo.mkv", "Maize") {
		t.Fatal("expected maize")
	}
	if IsMaizeRel("Movies/foo.mkv", "Maize") {
		t.Fatal("movies should not match")
	}
}

func TestSessions(t *testing.T) {
	s := NewSessions(nil)
	tok, err := s.Create()
	if err != nil || tok == "" {
		t.Fatal(err)
	}
	if !s.Valid(tok) {
		t.Fatal("token should be valid")
	}
	s.Revoke(tok)
	if s.Valid(tok) {
		t.Fatal("revoked token should be invalid")
	}
}

func TestFindFunscripts(t *testing.T) {
	dir := t.TempDir()
	video := filepath.Join(dir, "Scene [1080p].mp4")
	if err := os.WriteFile(video, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	matched := filepath.Join(dir, "Scene.funscript")
	other := filepath.Join(dir, "alt.funscript")
	if err := os.WriteFile(matched, []byte(`{"actions":[]}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(other, []byte(`{"actions":[]}`), 0o644); err != nil {
		t.Fatal(err)
	}
	got := FindFunscripts(video)
	if len(got) != 1 || got[0] != matched {
		t.Fatalf("expected stem match %v, got %v", matched, got)
	}
	// No stem match → all scripts in folder.
	video2 := filepath.Join(dir, "Other.mp4")
	if err := os.WriteFile(video2, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	got2 := FindFunscripts(video2)
	if len(got2) != 2 {
		t.Fatalf("expected fallback to all scripts, got %v", got2)
	}
}

func TestResolvePreviewBuckets(t *testing.T) {
	// Short clip (~60s): auto ≈ duration/1000 but floored to min 48.
	if n := resolvePreviewBuckets(60_000, 0); n != 60 {
		t.Fatalf("60s auto=%d want 60", n)
	}
	if n := resolvePreviewBuckets(30_000, 0); n != previewMinBars {
		t.Fatalf("30s auto=%d want min %d", n, previewMinBars)
	}
	// Long clip caps at max.
	if n := resolvePreviewBuckets(3_600_000, 0); n != previewMaxBars {
		t.Fatalf("1h auto=%d want max %d", n, previewMaxBars)
	}
	// Explicit override still clamped to legacy 64–960.
	if n := resolvePreviewBuckets(60_000, 480); n != 480 {
		t.Fatalf("explicit 480=%d", n)
	}
}

func TestReleaseDatePrecision(t *testing.T) {
	cases := []struct {
		in, want, prec string
		year           int
	}{
		{"2024", "2024", "year", 2024},
		{"2024-3", "2024-03", "month", 2024},
		{"2024-03-15", "2024-03-15", "day", 2024},
		{"2024/07/04", "2024-07-04", "day", 2024},
	}
	for _, tc := range cases {
		got, y, ok := NormalizeReleaseDate(tc.in)
		if !ok || got != tc.want || y != tc.year {
			t.Fatalf("%q → %q/%d ok=%v want %q/%d", tc.in, got, y, ok, tc.want, tc.year)
		}
		if ReleasePrecision(got) != tc.prec {
			t.Fatalf("precision(%s)=%s want %s", got, ReleasePrecision(got), tc.prec)
		}
	}
	if _, _, ok := NormalizeReleaseDate("2024-02-31"); ok {
		t.Fatal("Feb 31 should fail")
	}
	dir := t.TempDir()
	video := filepath.Join(dir, "scene.mp4")
	if err := os.WriteFile(video, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := WriteSceneMeta(video, SceneMeta{Title: "Dated", ReleaseDate: "2021-06"}); err != nil {
		t.Fatal(err)
	}
	got := ReadSceneMeta(video)
	if got.ReleaseDate != "2021-06" || got.Year != 2021 || ReleasePrecision(got.ReleaseDate) != "month" {
		t.Fatalf("round-trip: %+v", got)
	}
}

func TestWriteSceneMetaRoundTrip(t *testing.T) {
	dir := t.TempDir()
	video := filepath.Join(dir, "scene.mp4")
	if err := os.WriteFile(video, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	in := SceneMeta{
		Title:       "  Test Scene  ",
		Description: "Plot line",
		Studio:      " Studio X ",
		Year:        2024,
		Rating:      8.5,
		Performers:  []string{" Alice ", "", "Bob", "Alice"},
		Tags:        []string{"tag1", " tag2 "},
		Director:    "Dir",
	}
	if err := WriteSceneMeta(video, in); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "movie.meta.json")
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var raw map[string]any
	if err := json.Unmarshal(b, &raw); err != nil {
		t.Fatal(err)
	}
	if _, ok := raw["hasMeta"]; ok {
		t.Fatal("hasMeta must not be persisted")
	}
	got := ReadSceneMeta(video)
	if got.Title != "Test Scene" || got.Description != "Plot line" || got.Studio != "Studio X" {
		t.Fatalf("scalar mismatch: %+v", got)
	}
	if got.Year != 2024 || got.Rating != 8.5 {
		t.Fatalf("year/rating: %+v", got)
	}
	if len(got.Performers) != 2 || got.Performers[0] != "Alice" || got.Performers[1] != "Bob" {
		t.Fatalf("performers: %v", got.Performers)
	}
	if len(got.Tags) != 2 || got.Tags[0] != "tag1" || got.Tags[1] != "tag2" {
		t.Fatalf("tags: %v", got.Tags)
	}
	if got.Director != "Dir" || !got.HasMeta {
		t.Fatalf("director/hasMeta: %+v", got)
	}
}

func TestLoadFunscriptPreviewAutoBuckets(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "scene.funscript")
	// 90s script at 1Hz → auto should be ~90 bars (within min/max).
	actions := make([]map[string]float64, 0, 91)
	for i := 0; i <= 90; i++ {
		actions = append(actions, map[string]float64{"at": float64(i * 1000), "pos": float64(i % 100)})
	}
	body, _ := json.Marshal(map[string]any{"actions": actions})
	if err := os.WriteFile(path, body, 0o644); err != nil {
		t.Fatal(err)
	}
	prev, err := LoadFunscriptPreview(path, 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(prev.Points) != 90 {
		t.Fatalf("points=%d want 90", len(prev.Points))
	}
}
