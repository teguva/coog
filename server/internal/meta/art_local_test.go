package meta

import (
	"os"
	"path/filepath"
	"testing"
)

func TestAdoptOrCleanupArtMigratesPrefixed(t *testing.T) {
	dir := t.TempDir()
	media := filepath.Join(dir, "Movie (2011).mp4")
	if err := os.WriteFile(media, []byte("video"), 0o644); err != nil {
		t.Fatal(err)
	}
	prefixed := filepath.Join(dir, "Movie (2011)-poster.jpg")
	if err := os.WriteFile(prefixed, []byte("poster-bytes-long-enough!!!!!!!!!"), 0o644); err != nil {
		t.Fatal(err)
	}
	layout := localArtPaths(media)
	alt := alternateLocalArtPaths(media)
	if filepath.Base(layout.Poster) != "poster.jpg" {
		t.Fatalf("single-video layout: %s", layout.Poster)
	}
	if filepath.Base(alt.Poster) != "Movie (2011)-poster.jpg" {
		t.Fatalf("alt: %s", alt.Poster)
	}
	adoptOrCleanupArt(layout.Poster, alt.Poster)
	if !fileOK(layout.Poster) {
		t.Fatal("expected migrated poster.jpg")
	}
	if fileOK(alt.Poster) {
		t.Fatal("prefixed leftover should be removed")
	}
}
