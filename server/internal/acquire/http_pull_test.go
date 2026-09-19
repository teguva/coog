package acquire

import (
	"strings"
	"testing"

	"coog/internal/jobs"
	"coog/internal/store"
	"coog/internal/streams"
)

func TestHTTPPullArgsRetryFlags(t *testing.T) {
	args := httpPullArgs("https://cdn.example/master.m3u8", "https://embed.example/")
	joined := strings.Join(args, " ")
	for _, want := range []string{
		"-seg_max_retry", "20",
		"-reconnect_at_eof", "1",
		"-reconnect_on_network_error", "1",
		"-reconnect_on_http_error", "5xx",
		"-reconnect_delay_total_max", "900",
		"-protocol_whitelist", "file,http,https,tcp,tls,crypto,udp,rtp,httpproxy",
		"-map", "0:V:0",
		"-referer", "https://embed.example/",
		"https://cdn.example/master.m3u8",
	} {
		if !strings.Contains(joined, want) {
			t.Fatalf("missing %q in %v", want, args)
		}
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
