package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"coog/internal/config"
	"coog/internal/events"
	"coog/internal/jobs"
	"coog/internal/store"
)

func TestCreateJobPausesSupersededSibling(t *testing.T) {
	data := t.TempDir()
	st, err := store.Open(filepath.Join(data, "coog.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	first := store.Job{
		ID:       "oldjob",
		Type:     jobs.TypeDebrid,
		URL:      "imdb:tt32261958",
		Title:    "Verity",
		Status:   jobs.StatusDownloading,
		ImdbID:   "tt32261958",
		InfoHash: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
		Quality:  "2160p",
	}
	if err := st.InsertJob(first); err != nil {
		t.Fatal(err)
	}
	s := &Server{
		cfg:   config.Config{DataPath: data},
		store: st,
		hub:   events.NewHub(),
	}
	body, _ := json.Marshal(map[string]any{
		"type":      "debrid",
		"imdbId":    "tt32261958",
		"infoHash":  "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
		"title":     "Verity",
		"kind":      "movie",
		"force":     true,
		"quality":   "1080p",
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/jobs", bytes.NewReader(body))
	rr := httptest.NewRecorder()
	s.handleCreateJob(rr, req)
	if rr.Code != http.StatusCreated {
		t.Fatalf("status %d body %s", rr.Code, rr.Body.String())
	}
	var resp map[string]any
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}
	superseded, ok := resp["superseded"].([]any)
	if !ok || len(superseded) != 1 {
		t.Fatalf("superseded: %#v", resp["superseded"])
	}
	old, err := st.GetJob("oldjob")
	if err != nil || old.Status != jobs.StatusPaused {
		t.Fatalf("old job status: %+v %v", old, err)
	}
	if resp["infoHash"] != "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" {
		t.Fatalf("new hash: %#v", resp["infoHash"])
	}
}
