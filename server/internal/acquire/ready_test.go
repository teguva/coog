package acquire

import (
	"testing"

	"coog/internal/store"
)

func TestProgressiveReadyNeedsHeavyRemux(t *testing.T) {
	buf, bytes := progressiveReadyNeeds(&store.Job{
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
		SizeBytes: 2_000_000_000,
		Quality:   "1080p",
	})
	if buf != earlyBufferMs || bytes != earlyBytes {
		t.Fatalf("normal got buf=%d bytes=%d", buf, bytes)
	}
}
