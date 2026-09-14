// Package actors implements Funplay-compatible Jellyfin-style People folders.
package actors

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"unicode"

	"golang.org/x/text/runes"
	"golang.org/x/text/transform"
	"golang.org/x/text/unicode/norm"
)

const (
	MetaFilename   = "actor.meta.json"
	GalleryDirname = "gallery"
)

var (
	imgExts       = []string{".jpg", ".jpeg", ".png", ".webp"}
	headshotNames = []string{"folder", "poster", "headshot"}
	nfoFilenames  = []string{"person.nfo", "actor.nfo"}
	nonAlnum      = regexp.MustCompile(`[^a-z0-9]+`)
	unsafeName    = regexp.MustCompile(`[\\/:*?"<>|]+`)
)

// Meta is stored actor profile metadata.
type Meta struct {
	Name         string            `json:"name"`
	Slug         string            `json:"slug"`
	Aliases      []string          `json:"aliases"`
	Bio          string            `json:"bio"`
	Birthday     string            `json:"birthday"`
	Birthplace   string            `json:"birthplace"`
	Ethnicity    string            `json:"ethnicity"`
	Height       string            `json:"height"`
	Measurements string            `json:"measurements"`
	YearsActive  string            `json:"years_active"`
	// Derived display fields (not always present on disk; filled by NormalizeMetaStats).
	Nationality string            `json:"nationality,omitempty"`
	HairColor   string            `json:"hairColor,omitempty"`
	EyeColor    string            `json:"eyeColor,omitempty"`
	Weight      string            `json:"weight,omitempty"`
	ShoeSize    string            `json:"shoeSize,omitempty"`
	Tattoos     string            `json:"tattoos,omitempty"`
	Piercings   string            `json:"piercings,omitempty"`
	Links       map[string]string `json:"links"`
	Sources     map[string]any    `json:"sources,omitempty"`
	GalleryCount int              `json:"gallery_count"`
	EnrichedAt  float64           `json:"enriched_at"`
	Locked      bool              `json:"locked"`
}

// Slugify returns a canonical key: lowercase, accents stripped, punctuation collapsed.
func Slugify(name string) string {
	if name == "" {
		return ""
	}
	t := transform.Chain(norm.NFKD, runes.Remove(runes.In(unicode.Mn)))
	s, _, err := transform.String(t, name)
	if err != nil {
		s = name
	}
	s = strings.ToLower(s)
	s = nonAlnum.ReplaceAllString(s, "-")
	return strings.Trim(s, "-")
}

func letterBucket(name string) string {
	slug := Slugify(name)
	if slug != "" && slug[0] >= 'a' && slug[0] <= 'z' {
		return strings.ToUpper(string(slug[0]))
	}
	return "#"
}

// ActorDirForName returns the canonical folder path (may not exist).
func ActorDirForName(peopleDir, name string) string {
	safe := strings.TrimSpace(unsafeName.ReplaceAllString(name, " "))
	if safe == "" {
		safe = Slugify(name)
	}
	if safe == "" {
		safe = "unknown"
	}
	return filepath.Join(peopleDir, letterBucket(name), safe)
}

func IterActorDirs(peopleDir string) []string {
	entries, err := os.ReadDir(peopleDir)
	if err != nil {
		return nil
	}
	var out []string
	for _, bucket := range entries {
		if !bucket.IsDir() {
			continue
		}
		bucketPath := filepath.Join(peopleDir, bucket.Name())
		children, err := os.ReadDir(bucketPath)
		if err != nil {
			continue
		}
		for _, child := range children {
			if child.IsDir() {
				out = append(out, filepath.Join(bucketPath, child.Name()))
			}
		}
	}
	return out
}

func dirSlug(actorDir string) string {
	metaPath := filepath.Join(actorDir, MetaFilename)
	if b, err := os.ReadFile(metaPath); err == nil {
		var raw map[string]any
		if json.Unmarshal(b, &raw) == nil {
			if slug, ok := raw["slug"].(string); ok && strings.TrimSpace(slug) != "" {
				return strings.TrimSpace(slug)
			}
		}
	}
	return Slugify(filepath.Base(actorDir))
}

// FindActorDir locates an existing actor folder by slug (or alias).
func FindActorDir(peopleDir, slug string) string {
	want := Slugify(slug)
	if want == "" {
		return ""
	}
	for _, actorDir := range IterActorDirs(peopleDir) {
		if dirSlug(actorDir) == want {
			return actorDir
		}
		meta := ReadMeta(actorDir, "")
		for _, alias := range meta.Aliases {
			if Slugify(alias) == want {
				return actorDir
			}
		}
	}
	return ""
}

// DefaultMeta returns empty profile fields for a display name.
func DefaultMeta(name string) Meta {
	return Meta{
		Name:    name,
		Slug:    Slugify(name),
		Aliases: []string{},
		Links:   map[string]string{},
	}
}

// ReadMeta merges default <- person.nfo <- actor.meta.json.
func ReadMeta(actorDir, fallbackName string) Meta {
	name := fallbackName
	if name == "" {
		name = filepath.Base(actorDir)
	}
	base := DefaultMeta(name)

	for _, nfoName := range nfoFilenames {
		nfo := filepath.Join(actorDir, nfoName)
		if st, err := os.Stat(nfo); err == nil && !st.IsDir() {
			if parsed := parsePersonNFO(nfo); parsed != nil {
				mergeMeta(&base, parsed)
				break
			}
		}
	}

	metaPath := filepath.Join(actorDir, MetaFilename)
	if b, err := os.ReadFile(metaPath); err == nil {
		var stored Meta
		if json.Unmarshal(b, &stored) == nil {
			mergeMeta(&base, &stored)
		} else {
			// Tolerate partial / extra JSON via map merge.
			var raw map[string]any
			if json.Unmarshal(b, &raw) == nil {
				applyRaw(&base, raw)
			}
		}
	}

	if strings.TrimSpace(base.Slug) == "" {
		base.Slug = Slugify(base.Name)
	}
	if base.Aliases == nil {
		base.Aliases = []string{}
	}
	if base.Links == nil {
		base.Links = map[string]string{}
	}
	NormalizeMetaStats(&base)
	return base
}

func mergeMeta(dst, src *Meta) {
	if src.Name != "" {
		dst.Name = src.Name
	}
	if src.Slug != "" {
		dst.Slug = src.Slug
	}
	if len(src.Aliases) > 0 {
		dst.Aliases = append([]string{}, src.Aliases...)
	}
	if src.Bio != "" {
		dst.Bio = src.Bio
	}
	if src.Birthday != "" {
		dst.Birthday = src.Birthday
	}
	if src.Birthplace != "" {
		dst.Birthplace = src.Birthplace
	}
	if src.Ethnicity != "" {
		dst.Ethnicity = src.Ethnicity
	}
	if src.Height != "" {
		dst.Height = src.Height
	}
	if src.Measurements != "" {
		dst.Measurements = src.Measurements
	}
	if src.YearsActive != "" {
		dst.YearsActive = src.YearsActive
	}
	if len(src.Links) > 0 {
		dst.Links = src.Links
	}
	if src.GalleryCount > 0 {
		dst.GalleryCount = src.GalleryCount
	}
	if src.EnrichedAt > 0 {
		dst.EnrichedAt = src.EnrichedAt
	}
	if src.Locked {
		dst.Locked = true
	}
}

func applyRaw(dst *Meta, raw map[string]any) {
	if v, ok := raw["name"].(string); ok && v != "" {
		dst.Name = v
	}
	if v, ok := raw["slug"].(string); ok && v != "" {
		dst.Slug = v
	}
	if v, ok := raw["bio"].(string); ok {
		dst.Bio = v
	}
	if v, ok := raw["birthday"].(string); ok {
		dst.Birthday = v
	}
	if v, ok := raw["birthplace"].(string); ok {
		dst.Birthplace = v
	}
	if v, ok := raw["ethnicity"].(string); ok {
		dst.Ethnicity = v
	}
	if v, ok := raw["height"].(string); ok {
		dst.Height = v
	}
	if v, ok := raw["measurements"].(string); ok {
		dst.Measurements = v
	}
	if v, ok := raw["years_active"].(string); ok {
		dst.YearsActive = v
	}
	if v, ok := raw["locked"].(bool); ok {
		dst.Locked = v
	}
	switch v := raw["enriched_at"].(type) {
	case float64:
		dst.EnrichedAt = v
	case int:
		dst.EnrichedAt = float64(v)
	}
	switch v := raw["gallery_count"].(type) {
	case float64:
		dst.GalleryCount = int(v)
	case int:
		dst.GalleryCount = v
	}
	if arr, ok := raw["aliases"].([]any); ok {
		aliases := make([]string, 0, len(arr))
		for _, a := range arr {
			if s, ok := a.(string); ok && strings.TrimSpace(s) != "" {
				aliases = append(aliases, s)
			}
		}
		if len(aliases) > 0 {
			dst.Aliases = aliases
		}
	}
	if m, ok := raw["links"].(map[string]any); ok {
		links := map[string]string{}
		for k, v := range m {
			if s, ok := v.(string); ok {
				links[k] = s
			}
		}
		if len(links) > 0 {
			dst.Links = links
		}
	}
}

// HeadshotPath returns the first existing headshot file, or "".
func HeadshotPath(actorDir string) string {
	for _, name := range headshotNames {
		for _, ext := range imgExts {
			p := filepath.Join(actorDir, name+ext)
			if st, err := os.Stat(p); err == nil && !st.IsDir() {
				return p
			}
		}
	}
	return ""
}

// GalleryImagePaths returns sorted gallery image paths.
func GalleryImagePaths(actorDir string) []string {
	gdir := filepath.Join(actorDir, GalleryDirname)
	entries, err := os.ReadDir(gdir)
	if err != nil {
		return nil
	}
	var out []string
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		ext := strings.ToLower(filepath.Ext(e.Name()))
		ok := false
		for _, want := range imgExts {
			if ext == want {
				ok = true
				break
			}
		}
		if ok {
			out = append(out, filepath.Join(gdir, e.Name()))
		}
	}
	return out
}

func ImageContentType(path string) string {
	switch strings.ToLower(filepath.Ext(path)) {
	case ".png":
		return "image/png"
	case ".webp":
		return "image/webp"
	case ".jpeg", ".jpg":
		return "image/jpeg"
	default:
		return "application/octet-stream"
	}
}
