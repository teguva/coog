package meta

import "testing"

func TestImdbFromArtKey(t *testing.T) {
	cases := map[string]string{
		"movie-tt3521164":  "tt3521164",
		"series-tt0944947": "tt0944947",
		"tmdb-movie-123":   "",
		"":                 "",
	}
	for in, want := range cases {
		if got := imdbFromArtKey(in); got != want {
			t.Fatalf("%q: got %q want %q", in, got, want)
		}
	}
}
