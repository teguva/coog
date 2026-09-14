package library

import (
	"crypto/sha256"
	"encoding/hex"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

var videoExts = map[string]string{
	".mkv":  "video/x-matroska",
	".mp4":  "video/mp4",
	".m4v":  "video/mp4",
	".mov":  "video/quicktime",
	".avi":  "video/x-msvideo",
	".wmv":  "video/x-ms-wmv",
	".webm": "video/webm",
	".ts":   "video/mp2t",
	".m2ts": "video/mp2t",
}

var (
	yearFolderRe = regexp.MustCompile(`^(?P<title>.+?)\s*\((?P<year>\d{4})\)\s*$`)
	seRe         = regexp.MustCompile(`(?i)S(\d{1,2})E(\d{1,3})`)
	nxnRe        = regexp.MustCompile(`(?i)(?:^|[^\d])(\d{1,2})x(\d{2})(?:[^\d]|$)`)
	seasonDirRe  = regexp.MustCompile(`(?i)^Season\s+(\d{1,2})$`)
	trailingSERe = regexp.MustCompile(`(?i)\s+S\d{1,2}E\d{1,3}\s*$`)
)

type Parsed struct {
	Kind         string
	Title        string
	Year         int
	Season       int
	Episode      int
	ShowTitle    string
	ContentType  string
	RelativePath string
}

func IsVideo(path string) bool {
	ext := strings.ToLower(filepath.Ext(path))
	_, ok := videoExts[ext]
	return ok
}

func ContentType(path string) string {
	ext := strings.ToLower(filepath.Ext(path))
	if t, ok := videoExts[ext]; ok {
		return t
	}
	return "application/octet-stream"
}

func MediaID(relativePath string) string {
	sum := sha256.Sum256([]byte(filepath.ToSlash(relativePath)))
	return hex.EncodeToString(sum[:])[:16]
}

func ParseRelative(rel string) Parsed {
	rel = filepath.ToSlash(rel)
	p := Parsed{
		Kind:         "other",
		Title:        titleFromFilename(filepath.Base(rel)),
		ContentType:  ContentType(rel),
		RelativePath: rel,
	}
	parts := strings.Split(rel, "/")
	if len(parts) == 0 {
		return p
	}
	root := strings.ToLower(parts[0])
	switch root {
	case "movies":
		rest := parts[1:]
		if looksLikeSeriesPath(rest) {
			p.Kind = "episode"
			p.ShowTitle, p.Title, p.Season, p.Episode, p.Year = parseSeries(rest)
		} else {
			p.Kind = "movie"
			p.Title, p.Year = parseMovie(rest)
		}
	case "series":
		p.Kind = "episode"
		p.ShowTitle, p.Title, p.Season, p.Episode, p.Year = parseSeries(parts[1:])
	case "maize":
		// Adult bucket: never classify as a TV series with show title "Maize".
		p.Kind = "other"
		if len(parts) >= 2 && !IsVideo(parts[1]) {
			p.Title = restoreColon(parts[1])
		}
	default:
		if looksLikeSeriesPath(parts) {
			p.Kind = "episode"
			p.ShowTitle, p.Title, p.Season, p.Episode, p.Year = parseSeries(parts)
		}
	}
	return p
}

func looksLikeSeriesPath(parts []string) bool {
	for _, part := range parts {
		if seasonDirRe.MatchString(part) {
			return true
		}
		if seRe.MatchString(part) || nxnRe.MatchString(part) {
			return true
		}
	}
	return false
}

func parseMovie(parts []string) (title string, year int) {
	if len(parts) == 0 {
		return "Untitled", 0
	}
	folder := parts[0]
	if m := yearFolderRe.FindStringSubmatch(strings.TrimSuffix(folder, filepath.Ext(folder))); len(m) == 3 && !IsVideo(folder) {
		return restoreColon(m[1]), atoi(m[2])
	}
	base := strings.TrimSuffix(filepath.Base(parts[len(parts)-1]), filepath.Ext(parts[len(parts)-1]))
	if m := yearFolderRe.FindStringSubmatch(base); len(m) == 3 {
		return restoreColon(m[1]), atoi(m[2])
	}
	if m := yearFolderRe.FindStringSubmatch(folder); len(m) == 3 {
		return restoreColon(m[1]), atoi(m[2])
	}
	if IsVideo(folder) {
		return restoreColon(titleFromFilename(folder)), 0
	}
	return restoreColon(folder), 0
}

func parseSeries(parts []string) (show, title string, season, episode, year int) {
	if len(parts) == 0 {
		return "Unknown show", "Episode", 0, 0, 0
	}
	show = restoreColon(parts[0])
	if m := yearFolderRe.FindStringSubmatch(show); len(m) == 3 {
		show = restoreColon(m[1])
		year = atoi(m[2])
	}
	// Download folders like "Sex and the City S1E3" must not become the show title.
	show = CleanShowTitle(show)
	if show == "" {
		show = "Unknown show"
	}
	file := filepath.Base(parts[len(parts)-1])
	stem := strings.TrimSuffix(file, filepath.Ext(file))
	if se := seRe.FindStringSubmatch(stem); len(se) == 3 {
		season = atoi(se[1])
		episode = atoi(se[2])
	} else if nx := nxnRe.FindStringSubmatch(stem); len(nx) == 3 {
		season = atoi(nx[1])
		episode = atoi(nx[2])
	}
	for _, part := range parts {
		if sm := seasonDirRe.FindStringSubmatch(part); len(sm) == 2 && season == 0 {
			season = atoi(sm[1])
		}
	}
	title = strings.TrimSpace(seRe.ReplaceAllString(stem, ""))
	title = strings.Trim(title, " -_.")
	if title == "" || strings.EqualFold(title, show) {
		if episode > 0 {
			title = show + " S" + pad2(season) + "E" + pad2(episode)
		} else {
			title = stem
		}
	}
	return show, restoreColon(title), season, episode, year
}

func titleFromFilename(name string) string {
	stem := strings.TrimSuffix(name, filepath.Ext(name))
	stem = seRe.ReplaceAllString(stem, "")
	stem = strings.ReplaceAll(stem, ".", " ")
	stem = strings.Trim(stem, " -_")
	if stem == "" {
		return name
	}
	return restoreColon(stem)
}

func restoreColon(s string) string {
	return strings.ReplaceAll(strings.TrimSpace(s), "_ ", ": ")
}

// CleanShowTitle strips episode markers so download/job titles become a stable show folder name.
func CleanShowTitle(name string) string {
	name = strings.TrimSpace(name)
	if name == "" {
		return ""
	}
	if m := yearFolderRe.FindStringSubmatch(name); len(m) == 3 {
		name = strings.TrimSpace(m[1])
	}
	name = trailingSERe.ReplaceAllString(name, "")
	name = seRe.ReplaceAllString(name, "")
	name = nxnRe.ReplaceAllString(name, "")
	name = strings.Join(strings.Fields(strings.Trim(name, " -_.")), " ")
	return strings.TrimSpace(name)
}

func atoi(s string) int {
	n, _ := strconv.Atoi(s)
	return n
}

func pad2(n int) string {
	if n < 10 {
		return "0" + strconv.Itoa(n)
	}
	return strconv.Itoa(n)
}
