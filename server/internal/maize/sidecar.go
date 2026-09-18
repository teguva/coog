package maize

import (
	"encoding/json"
	"encoding/xml"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

// SceneMeta is Funplay-compatible sidecar metadata for a maize title.
type SceneMeta struct {
	Title       string   `json:"title,omitempty"`
	Description string   `json:"description,omitempty"`
	Performers  []string `json:"performers,omitempty"`
	Studio      string   `json:"studio,omitempty"`
	Tags        []string `json:"tags,omitempty"`
	Year        int      `json:"year,omitempty"`
	// ReleaseDate is a partial ISO date: YYYY, YYYY-MM, or YYYY-MM-DD.
	ReleaseDate string            `json:"releaseDate,omitempty"`
	Rating      float64           `json:"rating,omitempty"`
	Director    string            `json:"director,omitempty"`
	Duration    string            `json:"duration,omitempty"`
	Aliases     []string          `json:"aliases,omitempty"`
	Links       map[string]string `json:"links,omitempty"`
	Sources     map[string]any    `json:"sources,omitempty"`
	EnrichedAt  float64           `json:"enriched_at,omitempty"`
	Locked      bool              `json:"locked,omitempty"`
	HasMeta     bool              `json:"hasMeta"`
}

// ReadSceneMeta loads movie.meta.json / per-file JSON / NFO beside a media file.
func ReadSceneMeta(mediaPath string) SceneMeta {
	dir := filepath.Dir(mediaPath)
	stem := strings.TrimSuffix(filepath.Base(mediaPath), filepath.Ext(mediaPath))
	out := SceneMeta{Title: filepath.Base(dir)}

	// NFO first (lower precedence)
	for _, name := range []string{"movie.nfo", stem + ".nfo"} {
		if m, ok := parseNFO(filepath.Join(dir, name)); ok {
			mergeMeta(&out, m)
		}
	}
	// Legacy per-file JSON
	if m, ok := readJSONMeta(mediaPath + ".meta.json"); ok {
		mergeMeta(&out, m)
	}
	if m, ok := readJSONMeta(filepath.Join(dir, stem+".meta.json")); ok {
		mergeMeta(&out, m)
	}
	// Preferred Funplay sidecar
	if m, ok := readJSONMeta(filepath.Join(dir, "movie.meta.json")); ok {
		mergeMeta(&out, m)
	}

	out.HasMeta = out.Description != "" || len(out.Performers) > 0 || len(out.Tags) > 0 ||
		out.Studio != "" || out.Director != "" || out.Rating > 0 || len(out.Aliases) > 0
	out = NormalizeSceneMeta(out)
	return out
}

// ReleasePrecision returns "year", "month", "day", or "" for an empty/invalid value.
func ReleasePrecision(releaseDate string) string {
	s, _, ok := NormalizeReleaseDate(releaseDate)
	if !ok {
		return ""
	}
	switch len(s) {
	case 4:
		return "year"
	case 7:
		return "month"
	case 10:
		return "day"
	default:
		return ""
	}
}

// NormalizeReleaseDate accepts YYYY, YYYY-MM, YYYY-MM-DD (and loose variants).
// It returns the canonical string and the year component.
func NormalizeReleaseDate(raw string) (normalized string, year int, ok bool) {
	s := strings.TrimSpace(raw)
	if s == "" {
		return "", 0, false
	}
	s = strings.ReplaceAll(s, "/", "-")
	parts := strings.Split(s, "-")
	if len(parts) == 0 || len(parts) > 3 {
		return "", 0, false
	}
	y, err := strconv.Atoi(strings.TrimSpace(parts[0]))
	if err != nil || y < 1900 || y > 2100 {
		return "", 0, false
	}
	if len(parts) == 1 {
		return fmt.Sprintf("%04d", y), y, true
	}
	m, err := strconv.Atoi(strings.TrimSpace(parts[1]))
	if err != nil || m < 1 || m > 12 {
		return "", 0, false
	}
	if len(parts) == 2 {
		return fmt.Sprintf("%04d-%02d", y, m), y, true
	}
	d, err := strconv.Atoi(strings.TrimSpace(parts[2]))
	if err != nil || d < 1 || d > 31 {
		return "", 0, false
	}
	// Soft day check via time.Date; invalid days (e.g. Feb 31) fail.
	if t := time.Date(y, time.Month(m), d, 0, 0, 0, 0, time.UTC); t.Year() != y || int(t.Month()) != m || t.Day() != d {
		return "", 0, false
	}
	return fmt.Sprintf("%04d-%02d-%02d", y, m, d), y, true
}

// FormatReleaseDate builds a partial ISO date for the given precision.
func FormatReleaseDate(precision string, year, month, day int) (string, error) {
	precision = strings.ToLower(strings.TrimSpace(precision))
	switch precision {
	case "", "year":
		if year <= 0 {
			return "", nil
		}
		s, _, ok := NormalizeReleaseDate(fmt.Sprintf("%04d", year))
		if !ok {
			return "", fmt.Errorf("invalid year")
		}
		return s, nil
	case "month":
		s, _, ok := NormalizeReleaseDate(fmt.Sprintf("%04d-%02d", year, month))
		if !ok {
			return "", fmt.Errorf("invalid year/month")
		}
		return s, nil
	case "day":
		s, _, ok := NormalizeReleaseDate(fmt.Sprintf("%04d-%02d-%02d", year, month, day))
		if !ok {
			return "", fmt.Errorf("invalid date")
		}
		return s, nil
	default:
		return "", fmt.Errorf("precision must be year, month, or day")
	}
}

// NormalizeSceneMeta trims strings and drops empty list entries.
func NormalizeSceneMeta(meta SceneMeta) SceneMeta {
	meta.Title = strings.TrimSpace(meta.Title)
	meta.Description = strings.TrimSpace(meta.Description)
	meta.Studio = strings.TrimSpace(meta.Studio)
	meta.Director = strings.TrimSpace(meta.Director)
	meta.Duration = strings.TrimSpace(meta.Duration)
	meta.Performers = cleanStringList(meta.Performers)
	meta.Tags = cleanStringList(meta.Tags)
	meta.Aliases = cleanStringList(meta.Aliases)
	if len(meta.Links) > 0 {
		links := map[string]string{}
		for k, v := range meta.Links {
			k = strings.TrimSpace(k)
			v = strings.TrimSpace(v)
			if k != "" && v != "" {
				links[k] = v
			}
		}
		if len(links) == 0 {
			meta.Links = nil
		} else {
			meta.Links = links
		}
	}
	if s, y, ok := NormalizeReleaseDate(meta.ReleaseDate); ok {
		meta.ReleaseDate = s
		meta.Year = y
	} else {
		meta.ReleaseDate = ""
		if meta.Year > 0 {
			// Legacy year-only sidecars: expose as year-precision releaseDate.
			if s, y, ok := NormalizeReleaseDate(strconv.Itoa(meta.Year)); ok {
				meta.ReleaseDate = s
				meta.Year = y
			} else {
				meta.Year = 0
			}
		}
	}
	meta.HasMeta = meta.Description != "" || len(meta.Performers) > 0 || len(meta.Tags) > 0 ||
		meta.Studio != "" || meta.Director != "" || meta.Rating > 0 || len(meta.Aliases) > 0
	return meta
}

// WriteSceneMeta writes the preferred Funplay sidecar movie.meta.json beside the media file.
func WriteSceneMeta(mediaPath string, meta SceneMeta) error {
	mediaPath = strings.TrimSpace(mediaPath)
	if mediaPath == "" {
		return os.ErrInvalid
	}
	meta = NormalizeSceneMeta(meta)
	dir := filepath.Dir(mediaPath)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	raw := map[string]any{}
	if meta.Title != "" {
		raw["title"] = meta.Title
	}
	if meta.Description != "" {
		raw["description"] = meta.Description
	}
	if meta.Studio != "" {
		raw["studio"] = meta.Studio
	}
	if meta.Director != "" {
		raw["director"] = meta.Director
	}
	if meta.Year > 0 {
		raw["year"] = meta.Year
	}
	if meta.ReleaseDate != "" {
		raw["releaseDate"] = meta.ReleaseDate
	}
	if meta.Rating > 0 {
		raw["rating"] = meta.Rating
	}
	if len(meta.Performers) > 0 {
		raw["performers"] = meta.Performers
	}
	if len(meta.Tags) > 0 {
		raw["tags"] = meta.Tags
	}
	if len(meta.Aliases) > 0 {
		raw["aliases"] = meta.Aliases
	}
	if len(meta.Links) > 0 {
		raw["links"] = meta.Links
	}
	if meta.Duration != "" {
		raw["duration"] = meta.Duration
	}
	if len(meta.Sources) > 0 {
		raw["sources"] = meta.Sources
	}
	if meta.EnrichedAt > 0 {
		raw["enriched_at"] = meta.EnrichedAt
	}
	if meta.Locked {
		raw["locked"] = true
	}
	b, err := json.MarshalIndent(raw, "", "  ")
	if err != nil {
		return err
	}
	b = append(b, '\n')
	return os.WriteFile(filepath.Join(dir, "movie.meta.json"), b, 0o644)
}

func cleanStringList(in []string) []string {
	if len(in) == 0 {
		return nil
	}
	out := make([]string, 0, len(in))
	seen := map[string]bool{}
	for _, s := range in {
		s = strings.TrimSpace(s)
		if s == "" || seen[s] {
			continue
		}
		seen[s] = true
		out = append(out, s)
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

func readJSONMeta(path string) (SceneMeta, bool) {
	b, err := os.ReadFile(path)
	if err != nil {
		return SceneMeta{}, false
	}
	var raw map[string]any
	if json.Unmarshal(b, &raw) != nil {
		return SceneMeta{}, false
	}
	m := SceneMeta{}
	m.Title = strField(raw, "title")
	m.Description = strField(raw, "description")
	if m.Description == "" {
		m.Description = strField(raw, "plot")
	}
	m.Studio = strField(raw, "studio")
	m.Director = strField(raw, "director")
	m.Duration = strField(raw, "duration")
	m.Year = intField(raw, "year")
	m.ReleaseDate = strField(raw, "releaseDate")
	if m.ReleaseDate == "" {
		m.ReleaseDate = strField(raw, "release_date")
	}
	if m.ReleaseDate == "" {
		m.ReleaseDate = strField(raw, "premiered")
	}
	m.Rating = floatField(raw, "rating")
	m.Performers = stringList(raw["performers"])
	m.Tags = stringList(raw["tags"])
	m.Aliases = stringList(raw["aliases"])
	if links, ok := raw["links"].(map[string]any); ok {
		m.Links = map[string]string{}
		for k, v := range links {
			if s, ok := v.(string); ok && strings.TrimSpace(s) != "" {
				m.Links[k] = s
			}
		}
	}
	if sources, ok := raw["sources"].(map[string]any); ok && len(sources) > 0 {
		m.Sources = sources
	}
	switch v := raw["enriched_at"].(type) {
	case float64:
		m.EnrichedAt = v
	case int:
		m.EnrichedAt = float64(v)
	}
	if b, ok := raw["locked"].(bool); ok {
		m.Locked = b
	}
	return m, true
}

type nfoRoot struct {
	XMLName  xml.Name `xml:"movie"`
	Title    string   `xml:"title"`
	Plot     string   `xml:"plot"`
	Outline  string   `xml:"outline"`
	Studio   string   `xml:"studio"`
	Director string   `xml:"director"`
	Year     string   `xml:"year"`
	Rating   string   `xml:"rating"`
	Actor    []struct {
		Name string `xml:"name"`
	} `xml:"actor"`
	Genre []string `xml:"genre"`
	Tag   []string `xml:"tag"`
}

func parseNFO(path string) (SceneMeta, bool) {
	b, err := os.ReadFile(path)
	if err != nil {
		return SceneMeta{}, false
	}
	var root nfoRoot
	if xml.Unmarshal(b, &root) != nil {
		// Some NFOs are bare IMDB ids; ignore.
		return SceneMeta{}, false
	}
	m := SceneMeta{
		Title:       strings.TrimSpace(root.Title),
		Description: strings.TrimSpace(root.Plot),
		Studio:      strings.TrimSpace(root.Studio),
		Director:    strings.TrimSpace(root.Director),
	}
	if m.Description == "" {
		m.Description = strings.TrimSpace(root.Outline)
	}
	if y, err := strconv.Atoi(strings.TrimSpace(root.Year)); err == nil {
		m.Year = y
	}
	if r, err := strconv.ParseFloat(strings.TrimSpace(root.Rating), 64); err == nil {
		m.Rating = r
	}
	for _, a := range root.Actor {
		if n := strings.TrimSpace(a.Name); n != "" {
			m.Performers = append(m.Performers, n)
		}
	}
	m.Tags = append(m.Tags, root.Genre...)
	m.Tags = append(m.Tags, root.Tag...)
	return m, m.Title != "" || m.Description != "" || len(m.Performers) > 0
}

func mergeMeta(dst *SceneMeta, src SceneMeta) {
	if src.Title != "" {
		dst.Title = src.Title
	}
	if src.Description != "" {
		dst.Description = src.Description
	}
	if src.Studio != "" {
		dst.Studio = src.Studio
	}
	if src.Director != "" {
		dst.Director = src.Director
	}
	if src.Year > 0 {
		dst.Year = src.Year
	}
	if src.ReleaseDate != "" {
		dst.ReleaseDate = src.ReleaseDate
	}
	if src.Rating > 0 {
		dst.Rating = src.Rating
	}
	if len(src.Performers) > 0 {
		dst.Performers = src.Performers
	}
	if len(src.Tags) > 0 {
		dst.Tags = src.Tags
	}
	if len(src.Aliases) > 0 {
		dst.Aliases = src.Aliases
	}
	if len(src.Links) > 0 {
		dst.Links = src.Links
	}
}

func strField(m map[string]any, key string) string {
	v, _ := m[key].(string)
	return strings.TrimSpace(v)
}

func intField(m map[string]any, key string) int {
	switch v := m[key].(type) {
	case float64:
		return int(v)
	case int:
		return v
	case string:
		n, _ := strconv.Atoi(strings.TrimSpace(v))
		return n
	}
	return 0
}

func floatField(m map[string]any, key string) float64 {
	switch v := m[key].(type) {
	case float64:
		return v
	case int:
		return float64(v)
	case string:
		n, _ := strconv.ParseFloat(strings.TrimSpace(v), 64)
		return n
	}
	return 0
}

func stringList(v any) []string {
	arr, ok := v.([]any)
	if !ok {
		return nil
	}
	out := make([]string, 0, len(arr))
	for _, x := range arr {
		if s, ok := x.(string); ok {
			s = strings.TrimSpace(s)
			if s != "" {
				out = append(out, s)
			}
		}
	}
	return out
}

// FunscriptPath returns the preferred companion .funscript path if present.
func FunscriptPath(mediaPath string) string {
	scripts := FindFunscripts(mediaPath)
	if len(scripts) == 0 {
		return ""
	}
	return scripts[0]
}

var qualityTagRE = regexp.MustCompile(`(?i)\[\s*(?:(?:Bluray|BluRay|BDRip|WEB|WEBRip|WEB-DL|HDTV|DVD|DVDRip)-)?(2160p|1440p|1080p|720p|480p|360p|4K|2K|UHD|FHD|HD|SD)\s*\]`)
var qualityLooseRE = regexp.MustCompile(`(?i)[.\-_\s](2160p|1080p|720p|480p|360p|4k|uhd|hdr10|hdr|hevc|x265|x264|h265|h264|av1|bluray|webrip|web-dl|webdl|hdtv|remux)`)

func stripQuality(stem string) string {
	s := qualityTagRE.ReplaceAllString(stem, "")
	s = qualityLooseRE.ReplaceAllString(s, "")
	return strings.Trim(s, " -._")
}

// FindFunscripts lists .funscript files in the video's folder (Funplay rules):
// prefer quality-stripped stem match, else any scripts in the folder.
func FindFunscripts(mediaPath string) []string {
	mediaPath = strings.TrimSpace(mediaPath)
	if mediaPath == "" {
		return nil
	}
	dir := filepath.Dir(mediaPath)
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	var all []string
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		name := e.Name()
		if !strings.EqualFold(filepath.Ext(name), ".funscript") {
			continue
		}
		all = append(all, filepath.Join(dir, name))
	}
	if len(all) == 0 {
		return nil
	}
	sort.Strings(all)
	videoStem := strings.TrimSuffix(filepath.Base(mediaPath), filepath.Ext(mediaPath))
	videoKey := strings.ToLower(stripQuality(videoStem))
	if videoKey == "" {
		videoKey = strings.ToLower(videoStem)
	}
	var matched []string
	for _, p := range all {
		stem := strings.TrimSuffix(filepath.Base(p), filepath.Ext(p))
		key := strings.ToLower(stripQuality(stem))
		if key == "" {
			key = strings.ToLower(stem)
		}
		if key == videoKey || strings.EqualFold(stem, videoStem) {
			matched = append(matched, p)
		}
	}
	if len(matched) > 0 {
		return matched
	}
	return all
}
