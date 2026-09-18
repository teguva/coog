package api

import (
	"bytes"
	"encoding/json"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"testing"

	"coog/internal/config"
	"coog/internal/meta"
	"coog/internal/probe"
	"coog/internal/store"
)

func TestPreferredMaizeArtPath(t *testing.T) {
	dir := t.TempDir()
	video := filepath.Join(dir, "scene.mp4")
	if err := os.WriteFile(video, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	got := preferredMaizeArtPath(video, "poster")
	want := filepath.Join(dir, "poster.jpg")
	if got != want {
		t.Fatalf("poster=%s want %s", got, want)
	}
	existing := filepath.Join(dir, "cover.jpg")
	if err := os.WriteFile(existing, bytes.Repeat([]byte("p"), 64), 0o644); err != nil {
		t.Fatal(err)
	}
	if got := preferredMaizeArtPath(video, "poster"); got != existing {
		t.Fatalf("should overwrite existing cover.jpg, got %s", got)
	}
}

func TestHandleMaizeArtUpload(t *testing.T) {
	dir := t.TempDir()
	lib := filepath.Join(dir, "Videos")
	data := filepath.Join(dir, "data")
	sceneDir := filepath.Join(lib, "Maize", "ArtScene")
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
		ID:           "media-art",
		Path:         video,
		RelativePath: "Maize/ArtScene/video.mp4",
		Title:        "ArtScene",
	}
	if err := st.UpsertMedia(item); err != nil {
		t.Fatal(err)
	}
	s := &Server{
		cfg:    config.Config{LibraryPath: lib, DataPath: data, AuthToken: ""},
		store:  st,
		meta:   meta.New(data, ""),
		prober: probe.New("ffmpeg", "ffprobe"),
	}

	var body bytes.Buffer
	w := multipart.NewWriter(&body)
	part, err := w.CreateFormFile("file", "poster.jpg")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := part.Write(bytes.Repeat([]byte{0xff, 0xd8, 0xff}, 40)); err != nil {
		t.Fatal(err)
	}
	_ = w.Close()

	req := httptest.NewRequest(http.MethodPost, "/api/v1/maize/media/media-art/art/upload?kind=poster", &body)
	req.Header.Set("Content-Type", w.FormDataContentType())
	req.SetPathValue("id", "media-art")
	rr := httptest.NewRecorder()
	s.handleMaizeArtUpload(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
	if probe.SidecarPoster(video) == "" {
		t.Fatal("expected sidecar poster after upload")
	}
	var view map[string]any
	if err := json.Unmarshal(rr.Body.Bytes(), &view); err != nil {
		t.Fatal(err)
	}
	if view["hasPoster"] != true {
		t.Fatalf("hasPoster=%v", view["hasPoster"])
	}
}

func TestHandleMaizeArtUploadRejectsNonMaize(t *testing.T) {
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
	if err := st.UpsertMedia(store.MediaItem{
		ID: "media-plain", Path: video, RelativePath: "Movies/plain.mp4", Title: "plain",
	}); err != nil {
		t.Fatal(err)
	}
	s := &Server{
		cfg:   config.Config{LibraryPath: lib, DataPath: data},
		store: st,
		meta:  meta.New(data, ""),
	}
	var body bytes.Buffer
	w := multipart.NewWriter(&body)
	part, _ := w.CreateFormFile("file", "poster.jpg")
	_, _ = part.Write(bytes.Repeat([]byte("p"), 64))
	_ = w.Close()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/maize/media/media-plain/art/upload?kind=poster", &body)
	req.Header.Set("Content-Type", w.FormDataContentType())
	req.SetPathValue("id", "media-plain")
	rr := httptest.NewRecorder()
	s.handleMaizeArtUpload(rr, req)
	if rr.Code != http.StatusNotFound {
		t.Fatalf("status=%d", rr.Code)
	}
}

func TestHandleMaizeArtFrame(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not available")
	}
	dir := t.TempDir()
	lib := filepath.Join(dir, "Videos")
	data := filepath.Join(dir, "data")
	sceneDir := filepath.Join(lib, "Maize", "FrameScene")
	if err := os.MkdirAll(sceneDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(data, 0o755); err != nil {
		t.Fatal(err)
	}
	video := filepath.Join(sceneDir, "clip.mp4")
	cmd := exec.Command("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
		"-f", "lavfi", "-i", "testsrc=duration=2:size=320x180:rate=24",
		"-f", "lavfi", "-i", "sine=frequency=440:duration=2",
		"-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", video)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg generate: %v (%s)", err, out)
	}
	st, err := store.Open(filepath.Join(data, "coog.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	item := store.MediaItem{
		ID: "media-frame", Path: video, RelativePath: "Maize/FrameScene/clip.mp4", Title: "FrameScene",
	}
	if err := st.UpsertMedia(item); err != nil {
		t.Fatal(err)
	}
	s := &Server{
		cfg:    config.Config{LibraryPath: lib, DataPath: data},
		store:  st,
		meta:   meta.New(data, ""),
		prober: probe.New("ffmpeg", "ffprobe"),
	}
	payload, _ := json.Marshal(map[string]any{"kind": "backdrop", "positionMs": 500})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/maize/media/media-frame/art/frame", bytes.NewReader(payload))
	req.SetPathValue("id", "media-frame")
	rr := httptest.NewRecorder()
	s.handleMaizeArtFrame(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
	if probe.SidecarBackdrop(video) == "" {
		t.Fatal("expected backdrop sidecar")
	}
}
