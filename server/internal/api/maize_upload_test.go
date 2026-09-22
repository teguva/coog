package api

import (
	"bytes"
	"encoding/json"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"coog/internal/config"
	"coog/internal/events"
	"coog/internal/library"
	"coog/internal/maize"
	"coog/internal/meta"
	"coog/internal/probe"
	"coog/internal/store"
)

func maizeUploadServer(t *testing.T) (*Server, string) {
	t.Helper()
	dir := t.TempDir()
	lib := filepath.Join(dir, "Videos")
	data := filepath.Join(dir, "data")
	if err := os.MkdirAll(lib, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(data, 0o755); err != nil {
		t.Fatal(err)
	}
	st, err := store.Open(filepath.Join(data, "coog.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	prober := probe.New("ffmpeg", "ffprobe")
	s := &Server{
		cfg:     config.Config{LibraryPath: lib, DataPath: data},
		store:   st,
		scanner: library.NewScanner(st, prober, lib),
		prober:  prober,
		meta:    meta.New(data, ""),
		hub:     events.NewHub(),
	}
	return s, lib
}

func postMaizeUpload(t *testing.T, s *Server, title string, files map[string][]byte) *httptest.ResponseRecorder {
	t.Helper()
	var body bytes.Buffer
	w := multipart.NewWriter(&body)
	if title != "" {
		if err := w.WriteField("title", title); err != nil {
			t.Fatal(err)
		}
	}
	for name, data := range files {
		part, err := w.CreateFormFile("files", name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := part.Write(data); err != nil {
			t.Fatal(err)
		}
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest(http.MethodPost, "/api/v1/maize/upload", &body)
	req.Header.Set("Content-Type", w.FormDataContentType())
	rr := httptest.NewRecorder()
	s.handleMaizeUpload(rr, req)
	return rr
}

func TestMaizeFolderNameSanitizes(t *testing.T) {
	if got := maizeFolderName(`../evil/name`); strings.Contains(got, "/") || strings.Contains(got, "..") {
		t.Fatalf("unsafe folder name %q", got)
	}
	if got := maizeFolderName("   "); got != "Untitled" {
		t.Fatalf("empty=%q", got)
	}
}

func TestHandleMaizeUploadVideoOnly(t *testing.T) {
	s, lib := maizeUploadServer(t)
	rr := postMaizeUpload(t, s, "", map[string][]byte{
		"Night Scene.mp4": bytes.Repeat([]byte("video"), 40),
	})
	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
	var out map[string]any
	if err := json.Unmarshal(rr.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	items, _ := out["items"].([]any)
	if len(items) != 1 {
		t.Fatalf("items=%v", out["items"])
	}
	video := filepath.Join(lib, "Maize", "Night Scene", "Night Scene.mp4")
	if _, err := os.Stat(video); err != nil {
		t.Fatal(err)
	}
	if n := len(maize.ListFunscriptFiles(video)); n != 0 {
		t.Fatalf("funscripts=%d", n)
	}
}

func TestHandleMaizeUploadWithFunscript(t *testing.T) {
	s, lib := maizeUploadServer(t)
	rr := postMaizeUpload(t, s, "Custom Title", map[string][]byte{
		"scene.1080p.mp4": bytes.Repeat([]byte("video"), 40),
		"scene.funscript": []byte(`{"actions":[{"at":0,"pos":50},{"at":1000,"pos":20}]}`),
	})
	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
	video := filepath.Join(lib, "Maize", "Custom Title", "scene.1080p.mp4")
	if _, err := os.Stat(video); err != nil {
		t.Fatal(err)
	}
	scripts := maize.ListFunscriptFiles(video)
	if len(scripts) != 1 {
		t.Fatalf("scripts=%v", scripts)
	}
}

func TestHandleMaizeUploadTwoTitles(t *testing.T) {
	s, lib := maizeUploadServer(t)
	rr := postMaizeUpload(t, s, "", map[string][]byte{
		"Alpha.mp4": bytes.Repeat([]byte("video"), 40),
		"Beta.mkv":  bytes.Repeat([]byte("video"), 40),
	})
	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
	var out map[string]any
	if err := json.Unmarshal(rr.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	items, _ := out["items"].([]any)
	if len(items) != 2 {
		t.Fatalf("items=%v", out["items"])
	}
	if _, err := os.Stat(filepath.Join(lib, "Maize", "Alpha", "Alpha.mp4")); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(lib, "Maize", "Beta", "Beta.mkv")); err != nil {
		t.Fatal(err)
	}
}

func TestHandleMaizeUploadFunscriptOnly(t *testing.T) {
	s, _ := maizeUploadServer(t)
	rr := postMaizeUpload(t, s, "", map[string][]byte{
		"lonely.funscript": []byte(`{"actions":[{"at":0,"pos":1}]}`),
	})
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
}

func TestHandleMaizeUploadRejectsTraversalName(t *testing.T) {
	s, lib := maizeUploadServer(t)
	rr := postMaizeUpload(t, s, "", map[string][]byte{
		"../../outside.mp4": bytes.Repeat([]byte("video"), 40),
	})
	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
	if _, err := os.Stat(filepath.Join(lib, "outside.mp4")); err == nil {
		t.Fatal("escaped library root")
	}
	found := false
	_ = filepath.Walk(filepath.Join(lib, "Maize"), func(path string, info os.FileInfo, err error) error {
		if err == nil && !info.IsDir() && info.Name() == "outside.mp4" {
			found = true
		}
		return nil
	})
	if !found {
		t.Fatal("expected basename to be stored under Maize")
	}
}
