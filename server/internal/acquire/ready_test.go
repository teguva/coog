package acquire

import (
	"testing"

	"coog/internal/store"
)

func TestProgressiveReadyNeedsHeavyRemux(t *testing.T) {
	buf, bytes := progressiveReadyNeeds(&store.Job{
		Type:         "torrent",
		SizeBytes:    25_000_000_000,
		Quality:      "2160p",
		ReleaseTitle: "Movie 2160p BluRay REMUX",
	})
	if buf != heavyBufferMs || bytes != heavyBytes {
		t.Fatalf("heavy got buf=%d bytes=%d", buf, bytes)
	}
}

func TestProgressiveReadyNeedsNormal(t *testing.T) {
	buf, bytes := progressiveReadyNeeds(&store.Job{
		Type:      "torrent",
		SizeBytes: 2_000_000_000,
		Quality:   "1080p",
	})
	if buf != earlyBufferMs || bytes != earlyBytes {
		t.Fatalf("normal got buf=%d bytes=%d", buf, bytes)
	}
}

func TestProgressiveReadyNeedsDebridHTTPStartsFast(t *testing.T) {
	buf, bytes := progressiveReadyNeeds(&store.Job{
		Type:      "debrid",
		SizeBytes: 5_400_000_000,
		Quality:   "2160p",
	})
	if buf != httpHeavyBufferMs || bytes != httpHeavyBytes {
		t.Fatalf("debrid 4k got buf=%d bytes=%d want %d/%d", buf, bytes, httpHeavyBufferMs, httpHeavyBytes)
	}
	buf, bytes = progressiveReadyNeeds(&store.Job{
		Type:      "http",
		SizeBytes: 2_000_000_000,
		Quality:   "1080p",
	})
	if buf != httpEarlyBufferMs || bytes != httpEarlyBytes {
		t.Fatalf("http got buf=%d bytes=%d want %d/%d", buf, bytes, httpEarlyBufferMs, httpEarlyBytes)
	}
}
