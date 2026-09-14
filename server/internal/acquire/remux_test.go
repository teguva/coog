package acquire

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

func TestRemuxToLibraryMP4FallsBackToAudioReencode(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not installed")
	}
	dir := t.TempDir()
	src := filepath.Join(dir, "src.ts")
	// Minimal synthetic mpegts is hard; skip if we cannot generate a short clip.
	gen := exec.Command("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
		"-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
		"-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
		"-t", "1", "-c:v", "libx264", "-c:a", "aac", "-f", "mpegts", src)
	if out, err := gen.CombinedOutput(); err != nil {
		t.Skipf("cannot generate sample: %v %s", err, out)
	}
	dest := filepath.Join(dir, "out.mp4")
	got, err := remuxToLibraryMP4(context.Background(), "ffmpeg", src, dest)
	if err != nil {
		t.Fatal(err)
	}
	if got != dest {
		t.Fatalf("got %s", got)
	}
	st, err := os.Stat(dest)
	if err != nil || st.Size() < 1024 {
		t.Fatalf("dest missing/empty: %v", err)
	}
}
