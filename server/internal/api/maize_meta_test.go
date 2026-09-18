package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"coog/internal/config"
	"coog/internal/maize"
	"coog/internal/meta"
	"coog/internal/store"
)

func TestParseMetaStringList(t *testing.T) {
	got := parseMetaStringList(" Alice , Bob,, ")
	if len(got) != 4 {
		t.Fatalf("csv split len=%d got=%v", len(got), got)
	}
	arr := parseMetaStringList([]any{"A", "B"})
	if len(arr) != 2 || arr[0] != "A" {
		t.Fatalf("array: %v", arr)
	}
}

func TestHandleMaizeMediaMetaRejectsNonMaize(t *testing.T) {
	dir := t.TempDir()
	lib := filepath.Join(dir, "Videos")
	data := filepath.Join(dir, "data")
	if err := os.MkdirAll(filepath.Join(lib, "Movies"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(data, 0o755); err != nil {
		t.Fatal(err)
	}
	video := filepath.Join(lib, "Movies", "plain.mp4")
	if err := os.WriteFile(video, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	st, err := store.Open(filepath.Join(data, "coog.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	item := store.MediaItem{
		ID:           "media-plain",
		Path:         video,
		RelativePath: "Movies/plain.mp4",
		Title:        "plain",
	}
	if err := st.UpsertMedia(item); err != nil {
		t.Fatal(err)
	}
	s := &Server{
		cfg:   config.Config{LibraryPath: lib, DataPath: data, AuthToken: ""},
		store: st,
		meta:  meta.New(data, ""),
	}
	body, _ := json.Marshal(map[string]any{"title": "Nope"})
	req := httptest.NewRequest(http.MethodPut, "/api/v1/maize/media/media-plain/meta", bytes.NewReader(body))
	req.SetPathValue("id", "media-plain")
	rr := httptest.NewRecorder()
	s.handleMaizeMediaMeta(rr, req)
	if rr.Code != http.StatusNotFound {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
}

func TestHandleMaizeMediaMetaWritesSidecar(t *testing.T) {
	dir := t.TempDir()
	lib := filepath.Join(dir, "Videos")
	data := filepath.Join(dir, "data")
	sceneDir := filepath.Join(lib, "Maize", "SceneOne")
	if err := os.MkdirAll(sceneDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(data, 0o755); err != nil {
		t.Fatal(err)
	}
	video := filepath.Join(sceneDir, "video.mp4")
	if err := os.WriteFile(video, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	st, err := store.Open(filepath.Join(data, "coog.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	item := store.MediaItem{
		ID:           "media-maize",
		Path:         video,
		RelativePath: "Maize/SceneOne/video.mp4",
		Title:        "SceneOne",
	}
	if err := st.UpsertMedia(item); err != nil {
		t.Fatal(err)
	}
	s := &Server{
		cfg:   config.Config{LibraryPath: lib, DataPath: data, AuthToken: ""},
		store: st,
		meta:  meta.New(data, ""),
	}
	payload, _ := json.Marshal(map[string]any{
		"title":       "Edited",
		"description": "Desc",
		"studio":      "Studio",
		"year":        2021,
		"rating":      7.5,
		"performers":  "Ann, Bea",
		"tags":        []string{"a", "b"},
	})
	req := httptest.NewRequest(http.MethodPut, "/api/v1/maize/media/media-maize/meta", bytes.NewReader(payload))
	req.SetPathValue("id", "media-maize")
	rr := httptest.NewRecorder()
	s.handleMaizeMediaMeta(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
	got := maize.ReadSceneMeta(video)
	if got.Title != "Edited" || got.Studio != "Studio" || len(got.Performers) != 2 {
		t.Fatalf("sidecar: %+v", got)
	}
	if _, err := os.Stat(filepath.Join(sceneDir, "movie.meta.json")); err != nil {
		t.Fatal(err)
	}
}
