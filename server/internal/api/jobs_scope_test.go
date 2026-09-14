package api

import (
	"testing"

	"coog/internal/store"
)

func TestJobMatchesEpisodeScope(t *testing.T) {
	ep1 := store.Job{URL: "imdb:tt0159206:1:1", Title: "Sex and the City"}
	ep2 := store.Job{URL: "imdb:tt0159206:1:2", Title: "Sex and the City S01E02"}
	movie := store.Job{URL: "imdb:tt0111161", Title: "The Shawshank Redemption"}

	if !jobMatchesEpisodeScope(ep1, "episode", 1, 1) {
		t.Fatal("same episode should match")
	}
	if jobMatchesEpisodeScope(ep1, "episode", 1, 2) {
		t.Fatal("different episode must not reuse pack hash job")
	}
	if !jobMatchesEpisodeScope(ep2, "series", 1, 2) {
		t.Fatal("series kind with matching S/E should match")
	}
	if !jobMatchesEpisodeScope(movie, "movie", 0, 0) {
		t.Fatal("movie should match bare job")
	}
	if jobMatchesEpisodeScope(ep1, "movie", 0, 0) {
		t.Fatal("episode-scoped job must not satisfy movie enqueue")
	}
}
