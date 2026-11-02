package acquire

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"

	"coog/internal/config"
	"coog/internal/jobs"
	"coog/internal/store"
)

func TestDownloadHTTPOriginalResumesWithRange(t *testing.T) {
	full := []byte("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
	var sawRange string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sawRange = r.Header.Get("Range")
		if sawRange == "" {
			http.Error(w, "expected Range", http.StatusBadRequest)
			return
		}
		start, ok := parseResumeStart(sawRange)
		if !ok {
			t.Errorf("unexpected Range %q", sawRange)
			http.Error(w, "bad range", http.StatusBadRequest)
			return
		}
		if start < 0 || int(start) > len(full) {
			w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
			return
		}
		w.Header().Set("Content-Type", "video/mp4")
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, len(full)-1, len(full)))
		w.Header().Set("Content-Length", strconv.Itoa(len(full)-int(start)))
		w.WriteHeader(http.StatusPartialContent)
		_, _ = w.Write(full[start:])
	}))
	defer srv.Close()

	dir := t.TempDir()
	st, err := store.Open(filepath.Join(dir, "coog.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	r := &Runner{cfg: config.Config{DataPath: dir}, store: st}
	job := &store.Job{ID: "job-resume", Type: jobs.TypeHTTP, URL: srv.URL, Status: jobs.StatusDownloading}
	if err := st.InsertJob(*job); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(jobs.Dir(dir, job.ID), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := jobs.WriteSourceMeta(dir, job.ID, jobs.SourceMeta{
		Filename: "clip.mp4", ContentType: "video/mp4", TotalBytes: int64(len(full)),
	}); err != nil {
		t.Fatal(err)
	}
	partial := jobs.SourcePath(dir, job.ID)
	if err := os.WriteFile(partial, full[:10], 0o644); err != nil {
		t.Fatal(err)
	}

	var bytesTotal int64 = int64(len(full))
	var mu sync.Mutex
	path, err := r.downloadHTTPOriginal(context.Background(), job, srv.URL, "", &bytesTotal, &mu, func() {}, newLogSink())
	if err != nil {
		t.Fatal(err)
	}
	if sawRange != "bytes=10-" {
		t.Fatalf("expected Range bytes=10-, got %q", sawRange)
	}
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(full) {
		t.Fatalf("got %q want %q", got, full)
	}
}

func TestDownloadHTTPOriginalRestartsWhenRangeIgnored(t *testing.T) {
	full := []byte("0123456789abcdefghij")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "video/mp4")
		w.Header().Set("Content-Disposition", `attachment; filename="clip.mp4"`)
		// Always 200 with full body — simulates a CDN that ignores Range.
		_, _ = w.Write(full)
	}))
	defer srv.Close()

	dir := t.TempDir()
	st, err := store.Open(filepath.Join(dir, "coog.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	r := &Runner{cfg: config.Config{DataPath: dir}, store: st}
	job := &store.Job{ID: "job-norange", Type: jobs.TypeHTTP, URL: srv.URL, Status: jobs.StatusDownloading}
	if err := st.InsertJob(*job); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(jobs.Dir(dir, job.ID), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := jobs.WriteSourceMeta(dir, job.ID, jobs.SourceMeta{
		Filename: "clip.mp4", TotalBytes: int64(len(full)),
	}); err != nil {
		t.Fatal(err)
	}
	partial := jobs.SourcePath(dir, job.ID)
	if err := os.WriteFile(partial, []byte("XXXXX"), 0o644); err != nil {
		t.Fatal(err)
	}

	var bytesTotal int64 = int64(len(full))
	var mu sync.Mutex
	path, err := r.downloadHTTPOriginal(context.Background(), job, srv.URL, "", &bytesTotal, &mu, func() {}, newLogSink())
	if err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(full) {
		t.Fatalf("got %q", got)
	}
}

func parseResumeStart(header string) (int64, bool) {
	spec, ok := strings.CutPrefix(header, "bytes=")
	if !ok || !strings.HasSuffix(spec, "-") {
		return 0, false
	}
	n, err := strconv.ParseInt(strings.TrimSuffix(spec, "-"), 10, 64)
	if err != nil {
		return 0, false
	}
	return n, true
}
