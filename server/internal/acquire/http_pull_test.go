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
		"-reconnect_on_http_error", "4xx,5xx",
		"-reconnect_delay_total_max", "900",
		"-referer", "https://embed.example/",
		"https://cdn.example/master.m3u8",
	} {
		if !strings.Contains(joined, want) {
			t.Fatalf("missing %q in %v", want, args)
		}
	}
}
