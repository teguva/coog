package library

import (
	"os"
	"path/filepath"
	"testing"
)

func TestWithinRoot(t *testing.T) {
	root := t.TempDir()
	inside := filepath.Join(root, "Movies", "Foo (2020)", "Foo.mkv")
	if !WithinRoot(root, inside) {
		t.Fatal("expected inside")
	}
	if WithinRoot(root, root) {
		t.Fatal("library root itself is not a deletable path")
	}
	if WithinRoot(root, filepath.Join(root, "..", "other.mkv")) {
		t.Fatal("parent path must be refused")
	}
	if WithinRoot(root, "/tmp/outside.mkv") {
		t.Fatal("absolute outside path must be refused")
	}
}

func TestRemoveVideoClearsMovieFolder(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "Movies", "Foo (2020)")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	video := filepath.Join(dir, "Foo.mkv")
	if err := os.WriteFile(video, []byte("video"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "poster.jpg"), []byte("art"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "Foo.coog.json"), []byte("{}"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := RemoveVideo(root, video, "movie"); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(dir); !os.IsNotExist(err) {
		t.Fatalf("movie folder should be gone, err=%v", err)
	}
}

func TestSeasonDir(t *testing.T) {
	ep := filepath.Join("Series", "Show", "Season 01", "Show S01E01.mkv")
	got := SeasonDir(ep)
	want := filepath.Join("Series", "Show", "Season 01")
	if got != want {
		t.Fatalf("SeasonDir=%q want %q", got, want)
	}
	movie := filepath.Join("Movies", "Foo (2020)", "Foo.mkv")
	if SeasonDir(movie) != "" {
		t.Fatal("movie folders are not season dirs")
	}
}

func TestRemoveVideoKeepsShowArt(t *testing.T) {
	root := t.TempDir()
	show := filepath.Join(root, "Series", "Show")
	season := filepath.Join(show, "Season 01")
	if err := os.MkdirAll(season, 0o755); err != nil {
		t.Fatal(err)
	}
	ep := filepath.Join(season, "Show S01E01.mkv")
	if err := os.WriteFile(ep, []byte("video"), 0o644); err != nil {
		t.Fatal(err)
	}
	poster := filepath.Join(show, "poster.jpg")
	if err := os.WriteFile(poster, []byte("art"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := RemoveVideo(root, ep, "episode"); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(poster); err != nil {
		t.Fatalf("show poster must remain: %v", err)
	}
	if _, err := os.Stat(ep); !os.IsNotExist(err) {
		t.Fatal("episode file should be gone")
	}
}
