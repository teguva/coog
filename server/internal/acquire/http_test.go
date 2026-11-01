package acquire

import (
	"os"
	"path/filepath"
	"testing"

	"coog/internal/store"
)

func TestParseContentRangeTotal(t *testing.T) {
	if got := parseContentRangeTotal("bytes 0-0/5003804672"); got != 5003804672 {
		t.Fatalf("got %d", got)
	}
	if parseContentRangeTotal("bytes 0-1/*") != 0 {
		t.Fatal("star")
	}
}

func TestDownloadLooksCompleteRejectsTruncatedRemux(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "partial.ts")
	if err := writeN(path, 335<<20); err != nil {
		t.Fatal(err)
	}
	job := &store.Job{SizeBytes: 59_270_000_000, SizeLabel: "59.27 GB"}
	if downloadLooksComplete(job, path, 0) {
		t.Fatal("335MB of a 59GB source must be incomplete")
	}
}

func TestDownloadLooksCompleteAcceptsNearFullContentLength(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "full.ts")
	total := int64(500 << 20)
	if err := writeN(path, total); err != nil {
		t.Fatal(err)
	}
	job := &store.Job{}
	if !downloadLooksComplete(job, path, total) {
		t.Fatal("exact Content-Length must pass")
	}
	if downloadLooksComplete(job, path, total*2) {
		t.Fatal("half of Content-Length must fail")
	}
}

func writeN(path string, n int64) error {
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	defer f.Close()
	if err := f.Truncate(n); err != nil {
		return err
	}
	return f.Close()
}
