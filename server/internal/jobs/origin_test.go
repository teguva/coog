package jobs

import (
	"path/filepath"
	"testing"
)

func TestWriteReadOrigin(t *testing.T) {
	dir := t.TempDir()
	if err := WriteOrigin(dir, "abc123", "https://download.example/file.mkv", "https://ref.example/"); err != nil {
		t.Fatal(err)
	}
	u, ref := ReadOrigin(dir, "abc123")
	if u != "https://download.example/file.mkv" {
		t.Fatalf("url=%q", u)
	}
	if ref != "https://ref.example/" {
		t.Fatalf("referer=%q", ref)
	}
	if !filepath.IsAbs(OriginURLPath(dir, "abc123")) {
		t.Fatal("expected abs path")
	}
}

func TestWriteOriginClearsReferer(t *testing.T) {
	dir := t.TempDir()
	_ = WriteOrigin(dir, "j1", "https://a/x", "https://ref/")
	_ = WriteOrigin(dir, "j1", "https://a/y", "")
	u, ref := ReadOrigin(dir, "j1")
	if u != "https://a/y" || ref != "" {
		t.Fatalf("url=%q ref=%q", u, ref)
	}
}
