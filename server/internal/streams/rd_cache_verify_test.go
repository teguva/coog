package streams

import "testing"

func TestIsRealDebridHostedURL(t *testing.T) {
	cases := []struct {
		url  string
		want bool
	}{
		{"https://21-4.download.real-debrid.com/d/ABC/file.mkv", true},
		{"https://real-debrid.com/d/ABC/file.mkv", true},
		{"https://torrentio.strem.fun/videos/downloading_v3.mp4", false},
		{"https://torrentio.strem.fun/resolve/realdebrid/TOKEN/hash/null/0/x.mkv", false},
		{"", false},
	}
	for _, tc := range cases {
		if got := isRealDebridHostedURL(tc.url); got != tc.want {
			t.Fatalf("%s: got %v want %v", tc.url, got, tc.want)
		}
	}
}
