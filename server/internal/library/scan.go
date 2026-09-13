package library

import (
	"context"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"time"

	"coog/internal/maize"
	"coog/internal/probe"
	"coog/internal/store"
)

const minIndexBytes = 64 * 1024

type Scanner struct {
	store   *store.Store
	prober  *probe.Prober
	library string
}

func NewScanner(st *store.Store, prober *probe.Prober, libraryPath string) *Scanner {
	return &Scanner{store: st, prober: prober, library: libraryPath}
}

type ScanResult struct {
	Indexed int `json:"indexed"`
	Probed  int `json:"probed"`
	Removed int `json:"removed"`
	Skipped int `json:"skipped"`
}

func (s *Scanner) Scan(ctx context.Context) (ScanResult, error) {
	var result ScanResult
	root, err := filepath.Abs(s.library)
	if err != nil {
		return result, err
	}
	if err := os.MkdirAll(filepath.Join(root, "Movies"), 0o755); err != nil {
		slog.Warn("ensure Movies dir", "err", err)
	}
	if err := os.MkdirAll(filepath.Join(root, "Series"), 0o755); err != nil {
		slog.Warn("ensure Series dir", "err", err)
	}

	known, err := s.store.KnownByPath()
	if err != nil {
		return result, err
	}

	keep := make([]string, 0)
	// Public walk skips Maize/; keep existing adult rows so unlock sessions stay valid.
	for path, item := range known {
		rel := item.RelativePath
		if rel == "" {
			if r, err := filepath.Rel(root, path); err == nil {
				rel = filepath.ToSlash(r)
			}
		}
		if !IsMaizeRelative(rel) {
			continue
		}
		if st, err := os.Stat(path); err == nil && !st.IsDir() {
			keep = append(keep, item.ID)
		}
	}
	err = filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}
		name := d.Name()
		if d.IsDir() {
			if name == "." || name == ".." {
				return nil
			}
			if strings.HasPrefix(name, ".") || strings.EqualFold(name, "Trailers") {
				return fs.SkipDir
			}
			// Never index the adult bucket during the public library scan.
			if filepath.Dir(path) == root && strings.EqualFold(name, "Maize") {
				return fs.SkipDir
			}
			return nil
		}
		if strings.HasPrefix(name, ".") || strings.Contains(strings.ToLower(name), ".incompatible") {
			result.Skipped++
			return nil
		}
		if !IsVideo(path) {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			result.Skipped++
			return nil
		}
		if info.Size() < minIndexBytes {
			result.Skipped++
			return nil
		}
		rel, err := filepath.Rel(root, path)
		if err != nil {
			return nil
		}
		parsed := ParseRelative(rel)
		id := MediaID(rel)
		item := store.MediaItem{
			ID:           id,
			Kind:         parsed.Kind,
			Title:        parsed.Title,
			Year:         parsed.Year,
			Season:       parsed.Season,
			Episode:      parsed.Episode,
			ShowTitle:    parsed.ShowTitle,
			Path:         path,
			RelativePath: filepath.ToSlash(rel),
			SizeBytes:    info.Size(),
			MtimeUnix:    info.ModTime().Unix(),
			ContentType:  parsed.ContentType,
			UpdatedAt:    time.Now().Unix(),
		}
		if prev, ok := known[path]; ok && prev.SizeBytes == item.SizeBytes && prev.MtimeUnix == item.MtimeUnix && len(prev.Probe) > 2 {
			item.DurationMs = prev.DurationMs
			item.Probe = prev.Probe
			item.CodecVideo = prev.CodecVideo
			item.CodecAudio = prev.CodecAudio
			item.Width = prev.Width
			item.Height = prev.Height
			item.HDR = prev.HDR
		} else {
			infoProbe, err := s.prober.Probe(ctx, path)
			if err != nil {
				slog.Warn("ffprobe failed", "path", path, "err", err)
			} else {
				item.DurationMs = infoProbe.DurationMs
				item.Probe = infoProbe.Raw
				item.CodecVideo = infoProbe.VideoCodec
				item.CodecAudio = infoProbe.AudioCodec
				item.Width = infoProbe.Width
				item.Height = infoProbe.Height
				item.HDR = infoProbe.HDR
				result.Probed++
			}
		}
		if err := s.store.UpsertMedia(item); err != nil {
			slog.Error("upsert media", "path", path, "err", err)
			return nil
		}
		keep = append(keep, id)
		result.Indexed++
		return nil
	})
	if err != nil {
		return result, err
	}
	before, _ := s.store.Stats()
	if err := s.store.DeleteMissing(keep); err != nil {
		return result, err
	}
	after, _ := s.store.Stats()
	if before > after {
		result.Removed = before - after
	}
	slog.Info("library scan complete", "indexed", result.Indexed, "probed", result.Probed, "removed", result.Removed, "root", root)
	return result, nil
}

func (s *Scanner) LibraryRoot() string {
	return s.library
}

// IsSidecarVideo reports Jellyfin-style extras that must not become a primary library item.
func IsSidecarVideo(path string) bool {
	stem := strings.ToLower(strings.TrimSuffix(filepath.Base(path), filepath.Ext(path)))
	if stem == "trailer" || strings.HasPrefix(stem, "trailer.") || strings.HasPrefix(stem, "trailer-") {
		return true
	}
	if strings.HasSuffix(stem, "-trailer") || strings.HasSuffix(stem, ".trailer") {
		return true
	}
	switch stem {
	case "sample", "theme", "clip", "scenes":
		return true
	}
	return false
}

// IsMaizeRelative reports whether a slash-separated library-relative path is under Maize/.
func IsMaizeRelative(rel string) bool {
	rel = filepath.ToSlash(strings.TrimPrefix(strings.TrimSpace(rel), "/"))
	first, _, _ := strings.Cut(rel, "/")
	if first == "" {
		first = rel
	}
	return strings.EqualFold(first, "Maize")
}

// ScanMaize indexes only the top-level Maize bucket (adult session).
func (s *Scanner) ScanMaize(ctx context.Context, bucket string) (ScanResult, error) {
	var result ScanResult
	if strings.TrimSpace(bucket) == "" {
		bucket = "Maize"
	}
	root, err := filepath.Abs(s.library)
	if err != nil {
		return result, err
	}
	maizeRoot := filepath.Join(root, bucket)
	st, err := os.Stat(maizeRoot)
	if err != nil || !st.IsDir() {
		return result, nil
	}
	known, err := s.store.KnownByPath()
	if err != nil {
		return result, err
	}
	keep := make([]string, 0)
	err = filepath.WalkDir(maizeRoot, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}
		name := d.Name()
		if d.IsDir() {
			if name == "." || name == ".." {
				return nil
			}
			if strings.HasPrefix(name, ".") || strings.EqualFold(name, "Trailers") {
				return fs.SkipDir
			}
			return nil
		}
		if strings.HasPrefix(name, ".") || strings.Contains(strings.ToLower(name), ".incompatible") {
			result.Skipped++
			return nil
		}
		if !IsVideo(path) {
			return nil
		}
		if IsSidecarVideo(path) {
			result.Skipped++
			return nil
		}
		info, err := d.Info()
		if err != nil {
			result.Skipped++
			return nil
		}
		if info.Size() < minIndexBytes {
			result.Skipped++
			return nil
		}
		rel, err := filepath.Rel(root, path)
		if err != nil {
			return nil
		}
		parsed := ParseRelative(rel)
		id := MediaID(rel)
		item := store.MediaItem{
			ID:           id,
			Kind:         parsed.Kind,
			Title:        parsed.Title,
			Year:         parsed.Year,
			Season:       parsed.Season,
			Episode:      parsed.Episode,
			ShowTitle:    parsed.ShowTitle,
			Path:         path,
			RelativePath: filepath.ToSlash(rel),
			SizeBytes:    info.Size(),
			MtimeUnix:    info.ModTime().Unix(),
			ContentType:  parsed.ContentType,
			UpdatedAt:    time.Now().Unix(),
		}
		if prev, ok := known[path]; ok && prev.SizeBytes == item.SizeBytes && prev.MtimeUnix == item.MtimeUnix && len(prev.Probe) > 2 {
			item.DurationMs = prev.DurationMs
			item.Probe = prev.Probe
			item.CodecVideo = prev.CodecVideo
			item.CodecAudio = prev.CodecAudio
			item.Width = prev.Width
			item.Height = prev.Height
			item.HDR = prev.HDR
		} else {
			infoProbe, err := s.prober.Probe(ctx, path)
			if err != nil {
				slog.Warn("ffprobe failed", "path", path, "err", err)
			} else {
				item.DurationMs = infoProbe.DurationMs
				item.Probe = infoProbe.Raw
				item.CodecVideo = infoProbe.VideoCodec
				item.CodecAudio = infoProbe.AudioCodec
				item.Width = infoProbe.Width
				item.Height = infoProbe.Height
				item.HDR = infoProbe.HDR
				result.Probed++
			}
		}
		if err := s.store.UpsertMedia(item); err != nil {
			slog.Error("upsert maize media", "path", path, "err", err)
			return nil
		}
		keep = append(keep, id)
		result.Indexed++
		return nil
	})
	if err != nil {
		return result, err
	}
	// Drop stale maize rows only (leave public library alone).
	all, err := s.store.ListMedia()
	if err != nil {
		return result, err
	}
	keepSet := map[string]bool{}
	for _, id := range keep {
		keepSet[id] = true
	}
	for _, item := range all {
		if !IsMaizeRelative(item.RelativePath) {
			continue
		}
		if keepSet[item.ID] {
			continue
		}
		_ = s.store.DeleteMedia(item.ID)
		result.Removed++
	}
	slog.Info("maize scan complete", "indexed", result.Indexed, "probed", result.Probed, "removed", result.Removed, "root", maizeRoot)
	return result, nil
}

// HasFunscript reports whether a companion .funscript exists beside the video.
func HasFunscript(mediaPath string) bool {
	return maize.FunscriptPath(mediaPath) != ""
}
