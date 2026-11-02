package jobs

import (
	"os"
	"path/filepath"
	"testing"
)

func TestSourceMetaRoundTripAndPath(t *testing.T) {
	dir := t.TempDir()
	meta := SourceMeta{
		Filename:    "Movie.Name.2160p.mkv",
		ContentType: "video/x-matroska",
		TotalBytes:  123456789,
	}
	if err := WriteSourceMeta(dir, "abc123", meta); err != nil {
		t.Fatal(err)
	}
	got, err := ReadSourceMeta(dir, "abc123")
	if err != nil {
		t.Fatal(err)
	}
	if got.Filename != meta.Filename || got.ContentType != meta.ContentType || got.TotalBytes != meta.TotalBytes {
		t.Fatalf("got %+v", got)
	}
	path := SourcePath(dir, "abc123")
	want := filepath.Join(Dir(dir, "abc123"), "source.mkv")
	if path != want {
		t.Fatalf("path=%s want=%s", path, want)
	}
	if err := os.WriteFile(path, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	if SourcePath(dir, "abc123") != want {
		t.Fatal("existing named source")
	}
}
