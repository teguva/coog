package maize

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// Quality label / rank helpers for multi-file scene folders (resolution variants).

var heightLabelRE = regexp.MustCompile(`(?i)\b(2160|1440|1080|720|480|360)p\b`)

// StripQualityTags removes resolution/codec tags from a file stem (FunPlay parity).
func StripQualityTags(stem string) string {
	return stripQuality(stem)
}

// SceneKey returns a quality-stripped stem key for grouping same-title variants.
func SceneKey(filename string) string {
	stem := strings.TrimSuffix(filepath.Base(filename), filepath.Ext(filename))
	key := strings.ToLower(stripQuality(stem))
	if key == "" {
		key = strings.ToLower(stem)
	}
	return key
}

// FilenameQualityRank extracts a coarse quality score from a filename (higher is better).
func FilenameQualityRank(name string) int {
	low := strings.ToLower(name)
	switch {
	case strings.Contains(low, "2160p"), strings.Contains(low, "4k"), strings.Contains(low, "uhd"):
		return 2160
	case strings.Contains(low, "1440p"), strings.Contains(low, "2k"):
		return 1440
	case strings.Contains(low, "1080p"), strings.Contains(low, "fhd"):
		return 1080
	case strings.Contains(low, "720p"):
		return 720
	case strings.Contains(low, "480p"):
		return 480
	case strings.Contains(low, "360p"):
		return 360
	default:
		return 0
	}
}

// QualityLabel returns a short UI label like "1080p" from height and/or filename.
func QualityLabel(height int, filename string) string {
	if height >= 2000 {
		return "2160p"
	}
	if height >= 1400 {
		return "1440p"
	}
	if height >= 1000 {
		return "1080p"
	}
	if height >= 700 {
		return "720p"
	}
	if height >= 400 {
		return "480p"
	}
	if m := heightLabelRE.FindString(filename); m != "" {
		return strings.ToLower(m)
	}
	if r := FilenameQualityRank(filename); r > 0 {
		return strconv.Itoa(r) + "p"
	}
	stem := strings.TrimSuffix(filepath.Base(filename), filepath.Ext(filename))
	if stem != "" {
		return stem
	}
	return "video"
}

// QualityRank orders videos: higher is better (height, then filename tag, then size).
func QualityRank(height int, sizeBytes int64, filename string) int64 {
	h := height
	if h <= 0 {
		h = FilenameQualityRank(filename)
	}
	return int64(h)*1_000_000_000_000 + sizeBytes
}

// VideoCandidate is one playable file in a multi-res folder.
type VideoCandidate struct {
	ID        string `json:"id"`
	Label     string `json:"label"`
	Filename  string `json:"filename"`
	Width     int    `json:"width,omitempty"`
	Height    int    `json:"height,omitempty"`
	SizeBytes int64  `json:"sizeBytes,omitempty"`
	Preferred bool   `json:"preferred"`
}

// FunscriptCandidate is one .funscript beside a scene.
type FunscriptCandidate struct {
	Name      string  `json:"name"`
	Label     string  `json:"label"`
	Preferred bool    `json:"preferred"`
	Intensity float64 `json:"intensity,omitempty"`
}

// PreferFunscriptPath returns the default script for a video (stem match, else first).
func PreferFunscriptPath(mediaPath string) string {
	return FunscriptPath(mediaPath)
}

// ListFunscriptFiles returns every .funscript in the video folder (sorted).
func ListFunscriptFiles(mediaPath string) []string {
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
		if strings.EqualFold(filepath.Ext(name), ".funscript") {
			all = append(all, filepath.Join(dir, name))
		}
	}
	sort.Strings(all)
	return all
}

// ResolveFunscriptPath picks a script by basename (or default when name is empty).
// Rejects path separators / parent refs.
func ResolveFunscriptPath(mediaPath, scriptName string) string {
	scriptName = strings.TrimSpace(scriptName)
	if scriptName == "" {
		return PreferFunscriptPath(mediaPath)
	}
	base := filepath.Base(scriptName)
	if base != scriptName || strings.Contains(scriptName, "..") {
		return ""
	}
	if !strings.EqualFold(filepath.Ext(base), ".funscript") {
		base += ".funscript"
	}
	for _, p := range ListFunscriptFiles(mediaPath) {
		if strings.EqualFold(filepath.Base(p), base) {
			return p
		}
	}
	return ""
}

// FunscriptOptions lists all scripts with a preferred flag for the given video.
func FunscriptOptions(mediaPath string) []FunscriptCandidate {
	all := ListFunscriptFiles(mediaPath)
	if len(all) == 0 {
		return nil
	}
	pref := PreferFunscriptPath(mediaPath)
	names := make([]string, len(all))
	for i, p := range all {
		names[i] = filepath.Base(p)
	}
	labels := shortFunscriptLabels(names)
	out := make([]FunscriptCandidate, 0, len(all))
	for i, p := range all {
		name := names[i]
		c := FunscriptCandidate{
			Name:      name,
			Label:     labels[i],
			Preferred: pref != "" && strings.EqualFold(p, pref),
		}
		if prev, err := LoadFunscriptPreview(p, 48); err == nil {
			c.Intensity = prev.Intensity
		}
		out = append(out, c)
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].Preferred != out[j].Preferred {
			return out[i].Preferred
		}
		return strings.ToLower(out[i].Label) < strings.ToLower(out[j].Label)
	})
	return out
}

var funscriptVariantRE = regexp.MustCompile(`(?i)(?:^|[.\-_ ])(twist|valve|filler|heat|raw|alt|alpha|beta)(?:$|[.\-_ ])`)

// shortFunscriptLabels builds short, unique chip labels for funscript basenames.
func shortFunscriptLabels(names []string) []string {
	raw := make([]string, len(names))
	for i, name := range names {
		raw[i] = provisionalFunscriptLabel(name)
	}
	counts := map[string]int{}
	for _, l := range raw {
		counts[strings.ToLower(l)]++
	}
	used := map[string]int{}
	out := make([]string, len(names))
	for i, name := range names {
		label := raw[i]
		key := strings.ToLower(label)
		if counts[key] > 1 {
			// Disambiguate with a compact stem hint (quality / last token).
			hint := funscriptDisambiguator(name)
			if hint != "" && !strings.EqualFold(hint, label) {
				label = label + " · " + hint
			} else {
				used[key]++
				if used[key] > 1 {
					label = fmt.Sprintf("%s %d", label, used[key])
				}
			}
		}
		out[i] = label
	}
	// Second pass if · hints still collide.
	counts = map[string]int{}
	for _, l := range out {
		counts[strings.ToLower(l)]++
	}
	used = map[string]int{}
	for i, l := range out {
		key := strings.ToLower(l)
		if counts[key] <= 1 {
			continue
		}
		used[key]++
		if used[key] > 1 {
			out[i] = fmt.Sprintf("%s %d", l, used[key])
		}
	}
	return out
}

func provisionalFunscriptLabel(filename string) string {
	stem := strings.TrimSuffix(filepath.Base(filename), filepath.Ext(filename))
	low := strings.ToLower(stem)
	switch {
	case strings.Contains(low, ".twist") || strings.HasSuffix(low, "_twist") || strings.HasSuffix(low, "-twist") || strings.HasSuffix(low, " twist"):
		return "Twist"
	case strings.Contains(low, ".valve") || strings.HasSuffix(low, "_valve") || strings.HasSuffix(low, "-valve"):
		return "Valve"
	case strings.Contains(low, "filler"):
		return "Filler"
	case funscriptVariantRE.MatchString(stem):
		m := funscriptVariantRE.FindStringSubmatch(stem)
		if len(m) > 1 {
			s := strings.ToLower(m[1])
			return strings.ToUpper(s[:1]) + s[1:]
		}
	}
	if q := FilenameQualityRank(stem); q > 0 {
		return strconv.Itoa(q) + "p"
	}
	return "Main"
}

func funscriptDisambiguator(filename string) string {
	stem := strings.TrimSuffix(filepath.Base(filename), filepath.Ext(filename))
	// Drop known variant suffixes so we can show the base title hint.
	cleaned := stem
	for _, suf := range []string{".twist", ".valve", "_twist", "_valve", "-twist", "-valve", ".filler", "_filler", "-filler"} {
		if strings.HasSuffix(strings.ToLower(cleaned), suf) {
			cleaned = cleaned[:len(cleaned)-len(suf)]
			break
		}
	}
	if q := FilenameQualityRank(cleaned); q > 0 {
		return strconv.Itoa(q) + "p"
	}
	// Last parenthetical token, e.g. (g90aked)
	if i := strings.LastIndex(cleaned, "("); i >= 0 {
		if j := strings.LastIndex(cleaned, ")"); j > i {
			tok := strings.TrimSpace(cleaned[i+1 : j])
			if tok != "" && len(tok) <= 16 {
				return tok
			}
		}
	}
	cleaned = stripQuality(cleaned)
	parts := strings.FieldsFunc(cleaned, func(r rune) bool {
		return r == ' ' || r == '-' || r == '_' || r == '.'
	})
	if len(parts) == 0 {
		return ""
	}
	last := parts[len(parts)-1]
	if len(last) > 14 {
		last = last[:14]
	}
	return last
}

// PreferIndex returns the index of the highest-ranked video in a peer group.
func PreferIndex(heights []int, sizes []int64, filenames []string) int {
	if len(filenames) == 0 {
		return 0
	}
	best := 0
	bestRank := QualityRank(heights[0], sizes[0], filenames[0])
	for i := 1; i < len(filenames); i++ {
		r := QualityRank(heights[i], sizes[i], filenames[i])
		if r > bestRank {
			bestRank = r
			best = i
		}
	}
	return best
}
