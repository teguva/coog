package api

import (
	"path/filepath"
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
			MediaID:   item.ID,
			SizeBytes: item.SizeBytes,
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
			hit.ReleaseTitle = filepath.Base(item.Path)
		}
		out = append(out, hit)
	}
	return out
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

func annotateLocalCandidates(cands []streams.Candidate, locals []localSourceHit) []map[string]any {
	out := make([]map[string]any, 0, len(cands))
	localsFirst := make([]map[string]any, 0)
	rest := make([]map[string]any, 0, len(cands))
	for _, c := range cands {
		row := streams.PublicCandidate(c)
		if mediaID, ok := matchLocalSource(c, locals); ok {
			row["inLibrary"] = true
			row["mediaId"] = mediaID
			localsFirst = append(localsFirst, row)
			continue
		}
		rest = append(rest, row)
	}
	out = append(out, localsFirst...)
	out = append(out, rest...)
	return out
}