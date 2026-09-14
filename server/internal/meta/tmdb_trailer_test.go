package meta

import "testing"

func TestPickYouTubeTrailerPrefersOfficial(t *testing.T) {
	got := pickYouTubeTrailer([]tmdbVideo{
		{Key: "tease", Site: "YouTube", Type: "Teaser", Official: true},
		{Key: "off", Site: "YouTube", Type: "Trailer", Official: true, Name: "Official Trailer"},
		{Key: "vimeo", Site: "Vimeo", Type: "Trailer", Official: true},
	})
	if got != "https://www.youtube.com/watch?v=off" {
		t.Fatalf("got %q", got)
	}
}

func TestTmdbAPIKindUsesFindMediaType(t *testing.T) {
	// TMDB id 105 is BTTF as movie and SATC as tv — kind guess must not win.
	if k := tmdbAPIKind("movie", tmdbMovie{ID: 105, MediaType: "tv", Name: "Sex and the City"}); k != "series" {
		t.Fatalf("tv find under movie kind → series, got %q", k)
	}
	if k := tmdbAPIKind("series", tmdbMovie{ID: 105, MediaType: "movie", Title: "Back to the Future"}); k != "movie" {
		t.Fatalf("movie find under series kind → movie, got %q", k)
	}
	if k := tmdbAPIKind("movie", tmdbMovie{ID: 1, Name: "Only Name"}); k != "series" {
		t.Fatalf("name-only hit should be series, got %q", k)
	}
	if k := tmdbAPIKind("movie", tmdbMovie{ID: 1, Title: "A Film"}); k != "movie" {
		t.Fatalf("title hit should stay movie, got %q", k)
	}
}
