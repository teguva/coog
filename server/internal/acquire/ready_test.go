package acquire

import (
	"strings"
	"testing"

	"coog/internal/store"
)

func TestProgressiveReadyNeedsHeavyRemux(t *testing.T) {
	segs, buf, bytes := progressiveReadyNeeds(&store.Job{
		SizeBytes:     25_000_000_000,
		Quality:       "2160p",
		ReleaseTitle:  "Movie 2160p BluRay REMUX",
	})
	if segs != heavySegments || buf != heavyBufferMs || bytes != heavyBytes {
		t.Fatalf("heavy got segs=%d buf=%d bytes=%d", segs, buf, bytes)
	}
}

func TestProgressiveReadyNeedsNormal(t *testing.T) {
	segs, buf, bytes := progressiveReadyNeeds(&store.Job{
		SizeBytes: 2_000_000_000,
		Quality:   "1080p",
	})
	if segs != earlySegments || buf != earlyBufferMs || bytes != earlyBytes {
		t.Fatalf("normal got segs=%d buf=%d bytes=%d", segs, buf, bytes)
	}
}

func TestProgressiveHLSArgsFMP4(t *testing.T) {
	args := progressiveHLSArgs("/data/jobs/x/hls", "/data/jobs/x/hls/index.m3u8")
	joined := strings.Join(args, " ")
	for _, want := range []string{
		"-hls_segment_type", "fmp4",
		"init.mp4",
		"seg_%05d.m4s",
		"index.m3u8",
	} {
		if !strings.Contains(joined, want) {
			t.Fatalf("missing %q in %v", want, args)
		}
	}
	if strings.Contains(joined, ".ts") {
		t.Fatalf("must not use mpegts segments: %v", args)
	}
}

func TestProgressivePackMapsHeavyUsesH264(t *testing.T) {
	joined := strings.Join(progressivePackMaps(true), " ")
	if !strings.Contains(joined, "libx264") || !strings.Contains(joined, "scale=") {
		t.Fatalf("heavy pack must transcode for TV: %s", joined)
	}
	if strings.Contains(joined, "-c copy") {
		t.Fatalf("heavy pack must not stream-copy video: %s", joined)
	}
	light := strings.Join(progressivePackMaps(false), " ")
	if !strings.Contains(light, "-c") || !strings.Contains(light, "copy") {
		t.Fatalf("light pack should copy: %s", light)
	}
}
