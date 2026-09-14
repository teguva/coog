package store

import (
	"path/filepath"
	"testing"
)

func TestJobClaim(t *testing.T) {
	dir := t.TempDir()
	st, err := Open(filepath.Join(dir, "coog.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	if err := st.InsertJob(Job{ID: "a", Type: "ytdlp", URL: "https://example.com", Status: "queued"}); err != nil {
		t.Fatal(err)
	}
	job, err := st.ClaimNextJob()
	if err != nil {
		t.Fatal(err)
	}
	if job.ID != "a" || job.Status != "downloading" {
		t.Fatalf("%+v", job)
	}
	if _, err := st.ClaimNextJob(); err != ErrNotFound {
		t.Fatalf("expected empty queue, got %v", err)
	}
}

func TestJobLogTailAndCancel(t *testing.T) {
	dir := t.TempDir()
	st, err := Open(filepath.Join(dir, "coog.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	job := Job{ID: "b", Type: "ytdlp", URL: "https://example.com", Status: "downloading", LogTail: "ffmpeg line"}
	if err := st.InsertJob(job); err != nil {
		t.Fatal(err)
	}
	got, err := st.GetJob("b")
	if err != nil || got.LogTail != "ffmpeg line" {
		t.Fatalf("%+v %v", got, err)
	}
	job.Status = "cancelled"
	if err := st.UpdateJob(job); err != nil {
		t.Fatal(err)
	}
	job.Status = "downloading"
	job.Progress = 0.5
	if err := st.UpdateJob(job); err != nil {
		t.Fatal(err)
	}
	got, err = st.GetJob("b")
	if err != nil || got.Status != "cancelled" {
		t.Fatalf("cancel overwritten: %+v %v", got, err)
	}
	job.Status = "paused"
	if err := st.UpdateJob(job); err != nil {
		t.Fatal(err)
	}
	job.Status = "downloading"
	job.Progress = 0.8
	if err := st.UpdateJob(job); err != nil {
		t.Fatal(err)
	}
	got, err = st.GetJob("b")
	if err != nil || got.Status != "paused" {
		t.Fatalf("pause overwritten: %+v %v", got, err)
	}
	job.Status = "queued"
	if err := st.UpdateJob(job); err != nil {
		t.Fatal(err)
	}
	got, err = st.GetJob("b")
	if err != nil || got.Status != "queued" {
		t.Fatalf("resume failed: %+v %v", got, err)
	}
	if err := st.InsertJob(Job{ID: "c", Type: "debrid", URL: "imdb:tt1", Status: "queued", InfoHash: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", ImdbID: "tt1"}); err != nil {
		t.Fatal(err)
	}
	got, err = st.FindActiveJobByHash("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
	if err != nil || got.ID != "c" {
		t.Fatalf("hash lookup: %+v %v", got, err)
	}

	if err := st.InsertJob(Job{ID: "ep1", Type: "debrid", URL: "imdb:tt0159206:1:1", Status: "queued", ImdbID: "tt0159206"}); err != nil {
		t.Fatal(err)
	}
	if err := st.InsertJob(Job{ID: "ep2", Type: "debrid", URL: "imdb:tt0159206:1:2", Status: "ready", ImdbID: "tt0159206"}); err != nil {
		t.Fatal(err)
	}
	got, err = st.FindActiveJobByIMDB("tt0159206", 1, 2)
	if err != nil || got.ID != "ep2" {
		t.Fatalf("episode job lookup S01E02: %+v %v", got, err)
	}
	got, err = st.FindActiveJobByIMDB("tt0159206", 1, 1)
	if err != nil || got.ID != "ep1" {
		t.Fatalf("episode job lookup S01E01: %+v %v", got, err)
	}
	if _, err := st.FindActiveJobByIMDB("tt0159206", 0, 0); err != ErrNotFound {
		t.Fatalf("bare imdb must not match episode jobs, got %v", err)
	}
	if err := st.InsertJob(Job{ID: "movie", Type: "debrid", URL: "imdb:tt9999999", Status: "queued", ImdbID: "tt9999999"}); err != nil {
		t.Fatal(err)
	}
	got, err = st.FindActiveJobByIMDB("tt9999999", 0, 0)
	if err != nil || got.ID != "movie" {
		t.Fatalf("movie job lookup: %+v %v", got, err)
	}

	s, e := JobSeasonEpisode(Job{URL: "https://rd.example/x", Title: "Show S02E04"})
	if s != 2 || e != 4 {
		t.Fatalf("title fallback S/E = %d/%d", s, e)
	}

	if err := st.TouchWorkerHeartbeat(42); err != nil {
		t.Fatal(err)
	}
	hb, err := st.WorkerHeartbeat()
	if err != nil || hb.PID != 42 || hb.UpdatedAt == 0 {
		t.Fatalf("%+v %v", hb, err)
	}

	if err := st.DeleteJob("b"); err != nil {
		t.Fatal(err)
	}
	if _, err := st.GetJob("b"); err != ErrNotFound {
		t.Fatalf("expected deleted job, got %v", err)
	}
	if err := st.DeleteJob("missing"); err != ErrNotFound {
		t.Fatalf("expected not found, got %v", err)
	}
}

func TestJobFileMetaRoundTrip(t *testing.T) {
	dir := t.TempDir()
	st, err := Open(filepath.Join(dir, "coog.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	job := Job{
		ID: "meta1", Type: "debrid", URL: "imdb:tt1", Title: "Show S01E01", Status: "queued",
		Quality: "1080p", SizeBytes: 4_200_000_000, SizeLabel: "4.2 GB", Pack: "single",
		Tags: []string{"WEB", "HEVC"}, Languages: []string{"en"}, ReleaseTitle: "Show.S01E01.1080p.WEB",
	}
	if err := st.InsertJob(job); err != nil {
		t.Fatal(err)
	}
	got, err := st.GetJob("meta1")
	if err != nil {
		t.Fatal(err)
	}
	if got.Quality != "1080p" || got.SizeLabel != "4.2 GB" || got.Pack != "single" || got.ReleaseTitle == "" {
		t.Fatalf("%+v", got)
	}
	if len(got.Tags) != 2 || got.Tags[0] != "WEB" {
		t.Fatalf("tags: %+v", got.Tags)
	}
}
