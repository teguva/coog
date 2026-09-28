package api

import (
	"path/filepath"
	"strconv"
	"strings"

	"coog/internal/library"
	"coog/internal/meta"
	"coog/internal/streams"
)

type localSourceHit struct {
	MediaID      string
	InfoHash     string
	ReleaseTitle string
	Quality      string
	SizeLabel    string
	SizeBytes    int64
	CodecVideo   string
	CodecAudio   string
	Width        int
	Height       int
	HDR          string
	ProbeError   string
	FileName     string
}

func (s *Server) localSourcesForTitle(imdb string, season, episode int) []localSourceHit {
	imdb = strings.ToLower(strings.TrimSpace(imdb))
	if imdb == "" {
		return nil
	}
	items, err := s.store.ListMedia()
	if err != nil {
		return nil
	}
	series := season > 0 || episode > 0
	out := make([]localSourceHit, 0)
	for _, item := range items {
		if library.IsSidecarVideo(item.Path) || s.isMaizeItem(item.Path, item.RelativePath) {
			continue
		}
		itemImdb := strings.ToLower(strings.TrimSpace(s.mediaImdb(item)))
		if itemImdb == "" || itemImdb != imdb {
			continue
		}
		if series {
			if item.Season != season || item.Episode != episode {
				continue
			}
		} else if item.Kind == "episode" || item.Season > 0 || item.Episode > 0 {
			continue
		}
		hit := localSourceHit{
			MediaID:    item.ID,
			SizeBytes:  item.SizeBytes,
			CodecVideo: strings.TrimSpace(item.CodecVideo),
			CodecAudio: strings.TrimSpace(item.CodecAudio),
			Width:      item.Width,
			Height:     item.Height,
			HDR:        strings.TrimSpace(item.HDR),
			FileName:   filepath.Base(item.Path),
			ProbeError: localProbeError(item.CodecVideo, item.CodecAudio, item.DurationMs, len(item.Probe)),
		}
		if sc, ok := meta.ReadSidecar(item.Path); ok {
			hit.InfoHash = streams.InfoHash(sc.InfoHash)
			hit.ReleaseTitle = strings.TrimSpace(sc.ReleaseTitle)
			hit.Quality = strings.TrimSpace(sc.Quality)
			hit.SizeLabel = strings.TrimSpace(sc.SizeLabel)
		}
		if hit.SizeLabel == "" && item.SizeBytes > 0 {
			hit.SizeLabel = streams.FormatSizeLabel(item.SizeBytes)
		}
		if hit.Quality == "" {
			hit.Quality = qualityFromHeight(item.Height)
		}
		if hit.ReleaseTitle == "" {
			hit.ReleaseTitle = hit.FileName
		}
		out = append(out, hit)
	}
	return out
}

func localProbeError(codecVideo, codecAudio string, durationMs int64, probeLen int) string {
	if strings.TrimSpace(codecVideo) != "" {
		return ""
	}
	if durationMs == 0 && probeLen == 0 {
		return "not probed"
	}
	if strings.TrimSpace(codecAudio) == "" {
		return "probe found no streams"
	}
	return "no video stream"
}

func qualityFromHeight(h int) string {
	switch {
	case h >= 2100:
		return "2160p"
	case h >= 1400:
		return "1440p"
	case h >= 900:
		return "1080p"
	case h >= 700:
		return "720p"
	case h >= 500:
		return "576p"
	case h >= 400:
		return "480p"
	default:
		return ""
	}
}

func matchLocalSource(c streams.Candidate, locals []localSourceHit) (string, bool) {
	if len(locals) == 0 {
		return "", false
	}
	hash := streams.InfoHash(c.InfoHash)
	if hash != "" {
		for _, loc := range locals {
			if loc.InfoHash != "" && loc.InfoHash == hash {
				return loc.MediaID, true
			}
		}
	}
	candRelease := normalizeReleaseKey(c.Title + " " + c.Name)
	if candRelease != "" {
		for _, loc := range locals {
			if loc.ReleaseTitle == "" {
				continue
			}
			if normalizeReleaseKey(loc.ReleaseTitle) == candRelease {
				return loc.MediaID, true
			}
		}
	}
	candQ := strings.ToLower(strings.TrimSpace(c.Quality))
	candSize := strings.ToLower(strings.TrimSpace(c.SizeLabel))
	if candSize == "" && c.Size > 0 {
		candSize = strings.ToLower(streams.FormatSizeLabel(c.Size))
	}
	if candQ == "" && candSize == "" {
		return "", false
	}
	for _, loc := range locals {
		lq := strings.ToLower(strings.TrimSpace(loc.Quality))
		ls := strings.ToLower(strings.TrimSpace(loc.SizeLabel))
		if candQ != "" && lq != "" && candQ != lq {
			continue
		}
		if candSize != "" && ls != "" && candSize != ls {
			continue
		}
		if (candQ != "" && lq != "") || (candSize != "" && ls != "") {
			return loc.MediaID, true
		}
	}
	return "", false
}

func normalizeReleaseKey(raw string) string {
	raw = strings.ToLower(strings.TrimSpace(raw))
	if raw == "" {
		return ""
	}
	for _, ext := range []string{".mkv", ".mp4", ".m4v", ".ts", ".m2ts", ".avi", ".mov", ".webm"} {
		if strings.HasSuffix(raw, ext) {
			raw = strings.TrimSuffix(raw, ext)
			break
		}
	}
	var b strings.Builder
	b.Grow(len(raw))
	prevDash := false
	for _, r := range raw {
		switch {
		case r >= 'a' && r <= 'z', r >= '0' && r <= '9':
			b.WriteRune(r)
			prevDash = false
		default:
			if !prevDash {
				b.WriteByte('-')
				prevDash = true
			}
		}
	}
	return strings.Trim(b.String(), "-")
}

func localHitStreamRow(loc localSourceHit) map[string]any {
	tags := make([]string, 0, 4)
	if loc.CodecVideo != "" {
		tags = append(tags, strings.ToLower(loc.CodecVideo))
	}
	if loc.HDR != "" {
		tags = append(tags, strings.ToLower(loc.HDR))
	}
	if loc.Width > 0 && loc.Height > 0 {
		tags = append(tags, formatResTag(loc.Width, loc.Height))
	}
	title := loc.ReleaseTitle
	if title == "" {
		title = loc.FileName
	}
	row := map[string]any{
		"infoHash":   loc.InfoHash,
		"title":      title,
		"name":       loc.FileName,
		"quality":    loc.Quality,
		"cached":     true,
		"seeders":    0,
		"size":       loc.SizeBytes,
		"sizeLabel":  loc.SizeLabel,
		"source":     "local",
		"provider":   "library",
		"kind":       "local",
		"pack":       "",
		"tags":       tags,
		"languages":  []string{},
		"inLibrary":  true,
		"mediaId":    loc.MediaID,
		"probeError": loc.ProbeError,
		"playable":   loc.ProbeError == "",
	}
	return row
}

func formatResTag(w, h int) string {
	if w <= 0 || h <= 0 {
		return ""
	}
	return strconv.Itoa(w) + "×" + strconv.Itoa(h)
}

func annotateLocalCandidates(cands []streams.Candidate, locals []localSourceHit) []map[string]any {
	matched := map[string]struct{}{}
	localsFirst := make([]map[string]any, 0)
	rest := make([]map[string]any, 0, len(cands))
	for _, c := range cands {
		row := streams.PublicCandidate(c)
		if mediaID, ok := matchLocalSource(c, locals); ok {
			row["inLibrary"] = true
			row["mediaId"] = mediaID
			row["playable"] = true
			if loc := localByID(locals, mediaID); loc != nil {
				row["probeError"] = loc.ProbeError
				if loc.ProbeError != "" {
					row["playable"] = false
				}
			}
			matched[mediaID] = struct{}{}
			localsFirst = append(localsFirst, row)
			continue
		}
		rest = append(rest, row)
	}
	// Library files with no matching remote source still belong on Sources
	// (broken remuxes, intentional alt encodes, unmatched hashes).
	orphans := make([]map[string]any, 0)
	for _, loc := range locals {
		if _, ok := matched[loc.MediaID]; ok {
			continue
		}
		orphans = append(orphans, localHitStreamRow(loc))
	}
	out := make([]map[string]any, 0, len(localsFirst)+len(orphans)+len(rest))
	out = append(out, localsFirst...)
	out = append(out, orphans...)
	out = append(out, rest...)
	return out
}

func localByID(locals []localSourceHit, id string) *localSourceHit {
	for i := range locals {
		if locals[i].MediaID == id {
			return &locals[i]
		}
	}
	return nil
}
