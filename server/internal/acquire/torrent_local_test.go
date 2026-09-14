package acquire

import (
	"testing"

	"coog/internal/streams"
)

func TestLocalTorrentOK(t *testing.T) {
	if localTorrentOK(streams.Candidate{Title: "Movie.2011.EXTENDED.1080p.BluRay.REMUX.mkv", Size: 20 << 30}) {
		t.Fatal("remux should skip local")
	}
	if localTorrentOK(streams.Candidate{Title: "Movie.2011.1080p.BluRay.x264", Size: 20 << 30}) {
		t.Fatal("huge file should skip local")
	}
	if !localTorrentOK(streams.Candidate{Title: "Movie.2011.1080p.BluRay.x264", Size: 8 << 30}) {
		t.Fatal("normal encode should allow local")
	}
}
