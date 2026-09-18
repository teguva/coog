package api

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"coog/internal/library"
	"coog/internal/probe"
)

const maizeArtUploadMax = 20 << 20 // 20 MiB

// preferredMaizeArtPath returns an existing sidecar to overwrite, or the Coog write target.
func preferredMaizeArtPath(mediaPath, kind string) string {
	kind = normalizeMaizeArtKind(kind)
	switch kind {
	case "poster":
		if p := probe.SidecarPoster(mediaPath); p != "" {
			return p
		}
	case "backdrop":
		if p := probe.SidecarBackdrop(mediaPath); p != "" {
			return p
		}
	case "logo":
		if p := probe.SidecarLogo(mediaPath); p != "" {
			return p
		}
	default:
		return ""
	}
	artDir := library.ArtDir(mediaPath)
	stem := strings.TrimSuffix(filepath.Base(mediaPath), filepath.Ext(mediaPath))
	prefixed := library.NeedsStemPrefixedArt(artDir)
	var name string
	switch kind {
	case "poster":
		name = "poster.jpg"
		if prefixed {
			name = stem + "-poster.jpg"
		}
	case "backdrop":
		name = "fanart.jpg"
		if prefixed {
			name = stem + "-fanart.jpg"
		}
	case "logo":
		name = "logo.png"
		if prefixed {
			name = stem + "-logo.png"
		}
	}
	return filepath.Join(artDir, name)
}

func (s *Server) handleMaizeArtUpload(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdultOrAdmin(w, r) {
		return
	}
	item, err := s.store.GetMedia(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusNotFound, "media not found")
		return
	}
	if !s.isMaizeItem(item.Path, item.RelativePath) {
		writeError(w, http.StatusNotFound, "media not found")
		return
	}
	kind := normalizeMaizeArtKind(r.URL.Query().Get("kind"))
	if kind == "" {
		writeError(w, http.StatusBadRequest, "kind must be poster, backdrop, or logo")
		return
	}
	if err := r.ParseMultipartForm(maizeArtUploadMax); err != nil {
		writeError(w, http.StatusBadRequest, "invalid multipart form")
		return
	}
	file, hdr, err := r.FormFile("file")
	if err != nil {
		writeError(w, http.StatusBadRequest, "file required")
		return
	}
	defer file.Close()
	if hdr.Size > maizeArtUploadMax {
		writeError(w, http.StatusRequestEntityTooLarge, "file too large")
		return
	}
	ext := strings.ToLower(filepath.Ext(hdr.Filename))
	switch ext {
	case ".jpg", ".jpeg", ".png", ".webp":
	default:
		// sniff from content-type as fallback
		ct := strings.ToLower(hdr.Header.Get("Content-Type"))
		switch {
		case strings.Contains(ct, "jpeg"):
			ext = ".jpg"
		case strings.Contains(ct, "png"):
			ext = ".png"
		case strings.Contains(ct, "webp"):
			ext = ".webp"
		default:
			writeError(w, http.StatusBadRequest, "unsupported image type")
			return
		}
	}
	dest := preferredMaizeArtPath(item.Path, kind)
	if dest == "" {
		writeError(w, http.StatusBadRequest, "invalid kind")
		return
	}
	// Keep preferred extension; convert via ffmpeg when upload ext differs.
	tmp := dest + ".upload-tmp" + ext
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	out, err := os.Create(tmp)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	written, copyErr := io.Copy(out, io.LimitReader(file, maizeArtUploadMax+1))
	_ = out.Close()
	if copyErr != nil {
		_ = os.Remove(tmp)
		writeError(w, http.StatusInternalServerError, copyErr.Error())
		return
	}
	if written > maizeArtUploadMax {
		_ = os.Remove(tmp)
		writeError(w, http.StatusRequestEntityTooLarge, "file too large")
		return
	}
	if written < 32 {
		_ = os.Remove(tmp)
		writeError(w, http.StatusBadRequest, "empty file")
		return
	}
	// Normalize to preferred extension via ffmpeg when needed.
	finalTmp := dest + ".write-tmp"
	if strings.EqualFold(filepath.Ext(tmp), filepath.Ext(dest)) {
		finalTmp = tmp
	} else if s.prober != nil {
		if err := s.prober.MaterializeImage(r.Context(), tmp, finalTmp); err != nil {
			_ = os.Remove(tmp)
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		_ = os.Remove(tmp)
	} else {
		finalTmp = tmp
		// Best-effort rename even if extensions differ.
	}
	if err := os.Rename(finalTmp, dest); err != nil {
		// Cross-device: copy
		if data, rerr := os.ReadFile(finalTmp); rerr == nil {
			_ = os.WriteFile(dest, data, 0o644)
			_ = os.Remove(finalTmp)
		} else {
			_ = os.Remove(finalTmp)
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
	}
	s.clearMediaArtCache(item.ID, kind)
	origin := strings.TrimRight(publicURL(r, "/"), "/")
	writeJSON(w, http.StatusOK, s.maizeView(item, origin, s.maizeProgressIndex()))
}

func (s *Server) handleMaizeArtFrame(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdultOrAdmin(w, r) {
		return
	}
	item, err := s.store.GetMedia(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusNotFound, "media not found")
		return
	}
	if !s.isMaizeItem(item.Path, item.RelativePath) {
		writeError(w, http.StatusNotFound, "media not found")
		return
	}
	var body struct {
		Kind       string `json:"kind"`
		PositionMs int64  `json:"positionMs"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	kind := normalizeMaizeArtKind(body.Kind)
	if kind == "" {
		writeError(w, http.StatusBadRequest, "kind must be poster, backdrop, or logo")
		return
	}
	if body.PositionMs < 0 {
		body.PositionMs = 0
	}
	if s.prober == nil {
		writeError(w, http.StatusServiceUnavailable, "ffmpeg unavailable")
		return
	}
	dest := preferredMaizeArtPath(item.Path, kind)
	if dest == "" {
		writeError(w, http.StatusBadRequest, "invalid kind")
		return
	}
	tmp := dest + ".frame-tmp" + filepath.Ext(dest)
	if err := s.prober.ExtractStillAt(context.Background(), item.Path, tmp, body.PositionMs); err != nil {
		_ = os.Remove(tmp)
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if err := os.Rename(tmp, dest); err != nil {
		data, rerr := os.ReadFile(tmp)
		_ = os.Remove(tmp)
		if rerr != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		if err := os.WriteFile(dest, data, 0o644); err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
	}
	s.clearMediaArtCache(item.ID, kind)
	origin := strings.TrimRight(publicURL(r, "/"), "/")
	writeJSON(w, http.StatusOK, s.maizeView(item, origin, s.maizeProgressIndex()))
}

func normalizeMaizeArtKind(kind string) string {
	switch strings.ToLower(strings.TrimSpace(kind)) {
	case "poster":
		return "poster"
	case "backdrop", "fanart":
		return "backdrop"
	case "logo":
		return "logo"
	default:
		return ""
	}
}

func (s *Server) clearMediaArtCache(id, kind string) {
	id = strings.TrimSpace(id)
	kind = normalizeMaizeArtKind(kind)
	if id == "" || kind == "" || s.cfg.DataPath == "" {
		return
	}
	dir := filepath.Join(s.cfg.DataPath, "artwork")
	patterns := []string{
		filepath.Join(dir, id+"-"+kind+"*"),
	}
	if kind == "backdrop" {
		patterns = append(patterns, filepath.Join(dir, id+"-fanart*"))
	}
	for _, pat := range patterns {
		matches, _ := filepath.Glob(pat)
		for _, m := range matches {
			_ = os.Remove(m)
		}
	}
}
