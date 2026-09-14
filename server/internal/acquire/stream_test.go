package acquire

import (
	"os"
	"path/filepath"
	"testing"
)

func TestFindTrailerDownloadPrefersMergedMP4(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "trailer.f137.mp4"), []byte("fragment-too-small"), 0o644); err != nil {
		t.Fatal(err)
	}
	big := make([]byte, 2048)
	if err := os.WriteFile(filepath.Join(dir, "trailer.mp4"), big, 0o644); err != nil {
		t.Fatal(err)
	}
	got, err := findTrailerDownload(dir)
	if err != nil {
		t.Fatal(err)
	}
	if filepath.Base(got) != "trailer.mp4" {
		t.Fatalf("got %s", got)
	}
}
