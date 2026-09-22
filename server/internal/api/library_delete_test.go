package api

import (
	"path/filepath"
	"testing"

	"coog/internal/store"
)

func TestSameShowAndCollectSeason(t *testing.T) {
	show := filepath.Join("/library", "Series", "Show")
	s1 := filepath.Join(show, "Season 01")
	a := store.MediaItem{ID: "a", Kind: "episode", Path: filepath.Join(s1, "Show S01E01.mkv"), ShowTitle: "Show", Season: 1}
	b := store.MediaItem{ID: "b", Kind: "episode", Path: filepath.Join(s1, "Show S01E02.mkv"), ShowTitle: "Show", Season: 1}
	c := store.MediaItem{ID: "c", Kind: "episode", Path: filepath.Join(show, "Season 02", "Show S02E01.mkv"), ShowTitle: "Show", Season: 2}
	other := store.MediaItem{ID: "d", Kind: "episode", Path: filepath.Join("/library", "Series", "Other", "Season 01", "Other S01E01.mkv"), ShowTitle: "Other", Season: 1}
	if !sameShow(a, b) {
		t.Fatal("episodes in the same show folder should match")
	}
	if sameShow(a, other) {
		t.Fatal("different shows must not match")
	}
	got := collectSeason([]store.MediaItem{a, b, c, other}, a)
	if len(got) != 2 {
		t.Fatalf("season collect=%d want 2", len(got))
	}
	series := collectSeries([]store.MediaItem{a, b, c, other}, a)
	if len(series) != 3 {
		t.Fatalf("series collect=%d want 3", len(series))
	}
}
