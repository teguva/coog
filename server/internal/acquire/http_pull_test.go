package acquire

import (
	"strings"
	"testing"
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
