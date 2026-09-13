package library

import "testing"

func TestIsSidecarVideo(t *testing.T) {
	cases := map[string]bool{
		"/lib/Maize/Scene/movie.mp4":           false,
		"/lib/Maize/Scene/trailer.mp4":         true,
		"/lib/Maize/Scene/trailer-1.mkv":       true,
		"/lib/Maize/Scene/trailer.official.mp4": true,
		"/lib/Maize/Scene/movie-trailer.mp4":   true,
		"/lib/Maize/Scene/sample.mp4":          true,
		"/lib/Maize/Scene/theme.mp4":           true,
		"/lib/Maize/Scene/clip.mp4":            true,
		"/lib/Maize/Scene/scenes.mp4":          true,
	}
	for path, want := range cases {
		if got := IsSidecarVideo(path); got != want {
			t.Fatalf("%s: got %v want %v", path, got, want)
		}
	}
}
