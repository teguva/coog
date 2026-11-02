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

func TestCollectMovieFolder(t *testing.T) {
	dir := filepath.Join("/library", "Movies", "Collision Course (1989)")
	a := store.MediaItem{ID: "a", Kind: "movie", Path: filepath.Join(dir, "Collision Course.mkv"), Title: "Collision Course"}
	b := store.MediaItem{ID: "b", Kind: "movie", Path: filepath.Join(dir, "Collision Course - 1080p Remux.mkv"), Title: "Collision Course"}
	other := store.MediaItem{ID: "c", Kind: "movie", Path: filepath.Join("/library", "Movies", "Other (1990)", "Other.mkv"), Title: "Other"}
	got := collectMovieFolder([]store.MediaItem{a, b, other}, a)
	if len(got) != 2 {
		t.Fatalf("movie folder collect=%d want 2", len(got))
	}
	ep := store.MediaItem{ID: "e", Kind: "episode", Path: filepath.Join("/library", "Series", "Show", "Season 01", "Show S01E01.mkv"), Title: "Pilot"}
	if n := len(collectMovieFolder([]store.MediaItem{ep}, ep)); n != 1 {
		t.Fatalf("episode collectMovieFolder=%d want 1", n)
	}
}

func TestJobMatchesLibraryTarget(t *testing.T) {
	mediaIDs := map[string]bool{"mid1": true}
	scopes := []jobIMDBScope{{imdb: "tt123"}}
	if !jobMatchesLibraryTarget(store.Job{MediaID: "mid1", Status: "finished"}, mediaIDs, scopes) {
		t.Fatal("mediaId should match")
	}
	if !jobMatchesLibraryTarget(store.Job{ImdbID: "tt123", URL: "imdb:tt123", Status: "paused"}, mediaIDs, scopes) {
		t.Fatal("movie imdb job should match")
	}
	if jobMatchesLibraryTarget(store.Job{ImdbID: "tt123", URL: "imdb:tt123:1:2", Title: "Show S01E02", Status: "queued"}, mediaIDs, scopes) {
		t.Fatal("episode-scoped job must not match movie delete")
	}
	epScopes := []jobIMDBScope{{imdb: "tt123", season: 1, episode: 2}}
	if !jobMatchesLibraryTarget(store.Job{ImdbID: "tt123", URL: "imdb:tt123:1:2", Title: "Show S01E02", Status: "queued"}, mediaIDs, epScopes) {
		t.Fatal("episode job should match episode delete")
	}
}
