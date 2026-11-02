package acquire

import (
	"net/http"
	"testing"

	"coog/internal/jobs"
	"coog/internal/store"
	"coog/internal/streams"
)

func TestFFmpegHasOptionDoesNotPrefixMatch(t *testing.T) {
	help := "  -reconnect         <boolean>\n  -reconnect_delay_max <int>\n"
	if !ffmpegHasOption(help, "reconnect") {
		t.Fatal("reconnect")
	}
	if ffmpegHasOption(help, "reconnect_max_retries") {
		t.Fatal("prefix")
	}
}

func TestRejectNonMedia(t *testing.T) {
	if err := rejectNonMedia([]byte("<!DOCTYPE html>"), "text/html"); err == nil {
		t.Fatal("html")
	}
	if err := rejectNonMedia([]byte("PK\x03\x04file"), "application/zip"); err == nil {
		t.Fatal("zip")
	}
	if err := rejectNonMedia([]byte("#EXTM3U\n#EXT-X-STREAM-INF\n"), "application/vnd.apple.mpegurl"); err != nil {
		t.Fatal(err)
	}
	if err := rejectNonMedia([]byte{0x1A, 0x45, 0xDF, 0xA3}, "video/x-matroska"); err != nil {
		t.Fatal(err)
	}
}

func TestCatalogJobURL(t *testing.T) {
	if !isCatalogJobURL("imdb:tt22084616") {
		t.Fatal("imdb")
	}
	if isHTTPMediaURL("imdb:tt22084616") {
		t.Fatal("imdb is not http")
	}
	if !isHTTPMediaURL("https://download.real-debrid.com/d/abc") {
		t.Fatal("https")
	}
	if err := mediaURLReady("imdb:tt22084616"); err == nil {
		t.Fatal("catalog ref must not be downloaded as a file")
	}
	if err := mediaURLReady("https://cdn.example/master.m3u8"); err != nil {
		t.Fatal(err)
	}
}

func TestEffectiveJobType(t *testing.T) {
	if got := effectiveJobType(store.Job{Type: jobs.TypeHTTP, URL: "imdb:tt22084616"}); got != jobs.TypeDebrid {
		t.Fatalf("http+imdb: %s", got)
	}
	if got := effectiveJobType(store.Job{Type: jobs.TypeYTDLP, URL: "imdb:tt22084616"}); got != jobs.TypeDebrid {
		t.Fatalf("ytdlp+imdb: %s", got)
	}
	if got := effectiveJobType(store.Job{Type: jobs.TypeYTDLP, URL: "https://mfw09.org/e/abc"}); got != jobs.TypeYTDLP {
		t.Fatalf("web embed: %s", got)
	}
	if got := effectiveJobType(store.Job{Type: jobs.TypeHTTP, URL: "https://download.real-debrid.com/d/abc"}); got != jobs.TypeHTTP {
		t.Fatalf("rd http: %s", got)
	}
	if got := effectiveJobType(store.Job{Type: jobs.TypeTorrent, URL: "imdb:tt22084616", InfoHash: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}); got != jobs.TypeTorrent {
		t.Fatalf("local torrent+imdb: %s", got)
	}
	if got := effectiveJobType(store.Job{Type: jobs.TypeHTTP, URL: "magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}); got != jobs.TypeTorrent {
		t.Fatalf("magnet: %s", got)
	}
}

func TestIsWebCandidate(t *testing.T) {
	if !isWebCandidate(streams.Candidate{Source: "web", URL: "https://mfw09.org/e/abc"}) {
		t.Fatal("web")
	}
	if isWebCandidate(streams.Candidate{InfoHash: "abc", URL: "https://torrentio.strem.fun/resolve/x"}) {
		t.Fatal("torrentio")
	}
}

func TestFilenameFromResponse(t *testing.T) {
	resp := &http.Response{Header: http.Header{}}
	resp.Header.Set("Content-Disposition", `attachment; filename="Movie.Name.2160p.mkv"`)
	if got := filenameFromResponse(resp, "https://cdn.example/d/abc"); got != "Movie.Name.2160p.mkv" {
		t.Fatalf("disposition: %s", got)
	}
	resp2 := &http.Response{Header: http.Header{}}
	resp2.Header.Set("Content-Type", "video/x-matroska")
	if got := filenameFromResponse(resp2, "https://cdn.example/d/abc"); got != "source.mkv" {
		t.Fatalf("ctype: %s", got)
	}
	resp3 := &http.Response{Header: http.Header{}}
	if got := filenameFromResponse(resp3, "https://cdn.example/files/clip.mp4?token=1"); got != "clip.mp4" {
		t.Fatalf("url: %s", got)
	}
}
