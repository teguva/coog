package api

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"unicode/utf8"

	"coog/internal/events"
	"coog/internal/library"
	"coog/internal/maize"
	"coog/internal/store"
)

const maizeUploadMax = 32 << 30 // 32 GiB across the whole request

var maizeFolderUnsafe = regexp.MustCompile(`[<>:"/\\|?*\x00-\x1f]`)

type maizeIncomingFile struct {
	tmp  string
	name string
	kind string // video | funscript
}

func safeUploadBase(name string) string {
	base := filepath.Base(strings.ReplaceAll(name, "\\", "/"))
	base = strings.TrimSpace(base)
	if base == "" || base == "." || base == ".." || strings.Contains(base, "\x00") {
		return ""
	}
	return base
}

func maizeFolderName(title string) string {
	title = maizeFolderUnsafe.ReplaceAllString(strings.TrimSpace(title), "-")
	title = strings.Join(strings.Fields(strings.ReplaceAll(title, "_", " ")), " ")
	title = strings.Trim(title, " .-")
	if title == "" {
		return "Untitled"
	}
	if utf8.RuneCountInString(title) > 120 {
		runes := []rune(title)
		title = string(runes[:120])
		title = strings.Trim(title, " .-")
	}
	if title == "" {
		return "Untitled"
	}
	return title
}

func maizeTitleFromUpload(name string) string {
	stem := strings.TrimSuffix(filepath.Base(name), filepath.Ext(name))
	stripped := maize.StripQualityTags(stem)
	if stripped != "" {
		stem = stripped
	}
	stem = strings.ReplaceAll(stem, ".", " ")
	stem = strings.ReplaceAll(stem, "_", " ")
	stem = strings.Join(strings.Fields(stem), " ")
	return maizeFolderName(stem)
}

func classifyMaizeUpload(name string) string {
	ext := strings.ToLower(filepath.Ext(name))
	if ext == ".funscript" {
		return "funscript"
	}
	if library.IsVideo(name) && !library.IsSidecarVideo(name) {
		return "video"
	}
	return ""
}

func allocateMaizeDir(root, name string) (string, error) {
	name = maizeFolderName(name)
	candidate := filepath.Join(root, name)
	if _, err := os.Stat(candidate); os.IsNotExist(err) {
		return candidate, os.MkdirAll(candidate, 0o755)
	}
	for i := 2; i < 1000; i++ {
		candidate = filepath.Join(root, fmt.Sprintf("%s (%d)", name, i))
		if _, err := os.Stat(candidate); os.IsNotExist(err) {
			return candidate, os.MkdirAll(candidate, 0o755)
		}
	}
	return "", fmt.Errorf("could not allocate a unique folder")
}

func replaceUploadedFile(src, dest string) error {
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return err
	}
	if err := os.Rename(src, dest); err == nil {
		return nil
	}
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.Create(dest)
	if err != nil {
		return err
	}
	_, copyErr := io.Copy(out, in)
	closeErr := out.Close()
	if copyErr != nil {
		_ = os.Remove(dest)
		return copyErr
	}
	if closeErr != nil {
		_ = os.Remove(dest)
		return closeErr
	}
	_ = os.Remove(src)
	return nil
}

func (s *Server) handleMaizeUpload(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdultOrAdmin(w, r) {
		return
	}
	if s.scanner == nil {
		writeError(w, http.StatusServiceUnavailable, "library scanner not ready")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, maizeUploadMax)
	mr, err := r.MultipartReader()
	if err != nil {
		writeError(w, http.StatusBadRequest, "multipart form required")
		return
	}
	cfg := s.maizeCfg()
	root, err := filepath.Abs(s.cfg.LibraryPath)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	maizeRoot := filepath.Join(root, cfg.Bucket)
	if err := os.MkdirAll(maizeRoot, 0o755); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	nonce := make([]byte, 8)
	if _, err := rand.Read(nonce); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	incoming := filepath.Join(maizeRoot, ".incoming", hex.EncodeToString(nonce))
	if err := os.MkdirAll(incoming, 0o755); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	defer func() { _ = os.RemoveAll(incoming) }()

	var title string
	var files []maizeIncomingFile
	usedNames := map[string]int{}
	for {
		part, err := mr.NextPart()
		if err == io.EOF {
			break
		}
		if err != nil {
			writeError(w, http.StatusBadRequest, "invalid multipart form")
			return
		}
		formName := part.FormName()
		filename := part.FileName()
		if filename == "" {
			if formName == "title" {
				b, readErr := io.ReadAll(io.LimitReader(part, 512))
				_ = part.Close()
				if readErr != nil {
					writeError(w, http.StatusBadRequest, "invalid title")
					return
				}
				title = strings.TrimSpace(string(b))
			} else {
				_ = part.Close()
			}
			continue
		}
		base := safeUploadBase(filename)
		if base == "" {
			_ = part.Close()
			writeError(w, http.StatusBadRequest, "invalid filename")
			return
		}
		kind := classifyMaizeUpload(base)
		if kind == "" {
			_ = part.Close()
			writeError(w, http.StatusBadRequest, "unsupported file type: "+base)
			return
		}
		origKey := strings.ToLower(base)
		if n := usedNames[origKey]; n > 0 {
			ext := filepath.Ext(base)
			stem := strings.TrimSuffix(base, ext)
			base = fmt.Sprintf("%s (%d)%s", stem, n+1, ext)
		}
		usedNames[origKey]++
		tmp := filepath.Join(incoming, base)
		out, createErr := os.Create(tmp)
		if createErr != nil {
			_ = part.Close()
			writeError(w, http.StatusInternalServerError, createErr.Error())
			return
		}
		_, copyErr := io.Copy(out, part)
		_ = part.Close()
		closeErr := out.Close()
		if copyErr != nil {
			writeError(w, http.StatusInternalServerError, copyErr.Error())
			return
		}
		if closeErr != nil {
			writeError(w, http.StatusInternalServerError, closeErr.Error())
			return
		}
		st, statErr := os.Stat(tmp)
		if statErr != nil || st.Size() < 8 {
			writeError(w, http.StatusBadRequest, "empty file: "+base)
			return
		}
		files = append(files, maizeIncomingFile{tmp: tmp, name: base, kind: kind})
	}
	if len(files) == 0 {
		writeError(w, http.StatusBadRequest, "choose at least one video")
		return
	}

	type uploadGroup struct {
		key     string
		videos  []maizeIncomingFile
		scripts []maizeIncomingFile
	}
	groups := map[string]*uploadGroup{}
	order := make([]string, 0)
	var unmatched []maizeIncomingFile
	for _, f := range files {
		key := maize.SceneKey(f.name)
		if key == "" {
			key = strings.ToLower(f.name)
		}
		g, ok := groups[key]
		if !ok {
			g = &uploadGroup{key: key}
			groups[key] = g
			order = append(order, key)
		}
		if f.kind == "video" {
			g.videos = append(g.videos, f)
		} else {
			g.scripts = append(g.scripts, f)
		}
	}
	var videoGroups []*uploadGroup
	for _, key := range order {
		g := groups[key]
		if len(g.videos) == 0 {
			unmatched = append(unmatched, g.scripts...)
			continue
		}
		videoGroups = append(videoGroups, g)
	}
	if len(videoGroups) == 0 {
		writeError(w, http.StatusBadRequest, "choose at least one video; funscripts are optional")
		return
	}
	if len(unmatched) > 0 && len(videoGroups) == 1 {
		videoGroups[0].scripts = append(videoGroups[0].scripts, unmatched...)
		unmatched = nil
	}

	var skipped []string
	for _, f := range unmatched {
		skipped = append(skipped, f.name+": no matching video")
	}

	origin := strings.TrimRight(publicURL(r, "/"), "/")
	progress := s.maizeProgressIndex()
	indexed := make([]store.MediaItem, 0)
	views := make([]map[string]any, 0)
	for _, g := range videoGroups {
		folderTitle := maizeTitleFromUpload(g.videos[0].name)
		if title != "" && len(videoGroups) == 1 {
			folderTitle = maizeFolderName(title)
		}
		dir, err := allocateMaizeDir(maizeRoot, folderTitle)
		if err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		for _, f := range append(append([]maizeIncomingFile{}, g.videos...), g.scripts...) {
			dest := filepath.Join(dir, f.name)
			if err := replaceUploadedFile(f.tmp, dest); err != nil {
				writeError(w, http.StatusInternalServerError, err.Error())
				return
			}
		}
		for _, f := range g.videos {
			item, err := s.scanner.IndexPath(r.Context(), filepath.Join(dir, f.name))
			if err != nil {
				skipped = append(skipped, f.name+": "+err.Error())
				continue
			}
			indexed = append(indexed, item)
			views = append(views, s.maizeView(item, origin, progress))
		}
	}
	if len(indexed) == 0 {
		writeError(w, http.StatusInternalServerError, "files were written but none could be indexed")
		return
	}
	if s.hub != nil {
		s.hub.Broadcast(events.Event{Type: "library.changed"})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":      true,
		"items":   views,
		"skipped": skipped,
	})
}
