package subtitles

import (
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

var (
	seMarker = regexp.MustCompile(`(?i)(?:^|[^a-z0-9])s(\d{1,2})[ ._-]?e(\d{1,3})(?:[^a-z0-9]|$)`)
	xeMarker = regexp.MustCompile(`(?i)(?:^|[^a-z0-9])(\d{1,2})x(\d{1,3})(?:[^a-z0-9]|$)`)
)

// ParseSeasonEpisode pulls S/E from release / file names (S01E02, 1x02).
func ParseSeasonEpisode(text string) (season, episode int) {
	s := strings.TrimSpace(text)
	if s == "" {
		return 0, 0
	}
	if m := seMarker.FindStringSubmatch(s); len(m) == 3 {
		season, _ = strconv.Atoi(m[1])
		episode, _ = strconv.Atoi(m[2])
		return season, episode
	}
	if m := xeMarker.FindStringSubmatch(s); len(m) == 3 {
		season, _ = strconv.Atoi(m[1])
		episode, _ = strconv.Atoi(m[2])
		return season, episode
	}
	return 0, 0
}

func isSeriesQuery(q SearchQuery) bool {
	kind := strings.ToLower(strings.TrimSpace(q.Kind))
	switch kind {
	case "series", "episode", "tv", "tvshow", "show":
		return true
	}
	return q.Season > 0 || q.Episode > 0
}

// releaseScore measures how well an OpenSubtitles release/filename matches the video file.
func releaseScore(videoName, release, subFile string) int {
	stem := normalizeRelease(strings.TrimSuffix(filepath.Base(videoName), filepath.Ext(videoName)))
	if stem == "" {
		stem = normalizeRelease(videoName)
	}
	hay := normalizeRelease(release + " " + subFile)
	if stem == "" || hay == "" {
		return 0
	}
	tokens := strings.Fields(stem)
	if len(tokens) == 0 {
		return 0
	}
	hits := 0
	for _, tok := range tokens {
		if len(tok) < 3 {
			continue
		}
		// Skip ultra-common release tags / stop words — they inflate false positives.
		switch tok {
		case "1080p", "720p", "2160p", "480p", "webrip", "webdl", "web", "bluray",
			"bdrip", "hdtv", "x264", "x265", "h264", "h265", "hevc", "aac", "dts",
			"proper", "repack", "internal", "extended", "directors", "cut",
			"the", "and", "with", "for", "from", "part", "disc", "cd1", "cd2":
			continue
		}
		if strings.Contains(hay, tok) {
			hits++
		}
	}
	if hits == 0 {
		return 0
	}
	// Prefer denser overlap without requiring a full filename match.
	score := hits * 8
	if hits*2 >= len(tokens) {
		score += 20
	}
	return score
}

func normalizeRelease(s string) string {
	s = strings.ToLower(strings.TrimSpace(s))
	if s == "" {
		return ""
	}
	var b strings.Builder
	b.Grow(len(s))
	prevSpace := false
	for _, r := range s {
		switch {
		case r >= 'a' && r <= 'z', r >= '0' && r <= '9':
			b.WriteRune(r)
			prevSpace = false
		default:
			if !prevSpace {
				b.WriteByte(' ')
				prevSpace = true
			}
		}
	}
	return strings.Join(strings.Fields(b.String()), " ")
}

func titleTokenOverlap(title, haystack string) int {
	title = strings.ToLower(strings.TrimSpace(title))
	hay := strings.ToLower(haystack)
	if title == "" || hay == "" {
		return 0
	}
	tokens := strings.FieldsFunc(title, func(r rune) bool {
		return !(r >= 'a' && r <= 'z' || r >= '0' && r <= '9')
	})
	hits := 0
	for _, tok := range tokens {
		if len(tok) <= 2 {
			continue
		}
		switch tok {
		case "the", "and", "for", "with", "season", "episode":
			continue
		}
		if strings.Contains(hay, tok) {
			hits++
		}
	}
	return hits
}
