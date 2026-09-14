package streams

import (
	"regexp"
	"strings"
)

// PackKind classifies a release from its title (not torrent file listing).
type PackKind string

const (
	PackSingle PackKind = "single"
	PackMulti  PackKind = "multi"  // several episodes, not a full season
	PackSeason PackKind = "season" // one season pack / complete season
	PackSeries PackKind = "series" // multi-season / complete series
)

var (
	epRangeRe     = regexp.MustCompile(`(?i)S(\d{1,2})E(\d{1,3})\s*[-~]+\s*(?:S\d{1,2})?E?(\d{1,3})`)
	epRangeBareRe = regexp.MustCompile(`(?i)\bE(\d{1,3})\s*[-~]+\s*E?(\d{1,3})\b`)
	seasonTokenRe = regexp.MustCompile(`(?i)\bS(\d{1,2})\b`)
	seasonRangeRe = regexp.MustCompile(`(?i)S(\d{1,2})\s*[-~]+\s*S(\d{1,2})`)
	seasonWordRe  = regexp.MustCompile(`(?i)\bseason\s*(\d{1,2})\b`)
	completeRe    = regexp.MustCompile(`(?i)\b(complete\s+(series|season)|season\s+pack|full\s+season)\b`)
	sxxexxRePack  = regexp.MustCompile(`(?i)\bS\d{1,2}E\d{1,3}\b`)
)

// ClassifyPack infers whether a release is a single episode or a pack.
func ClassifyPack(title string) PackKind {
	text := strings.TrimSpace(title)
	if text == "" {
		return PackSingle
	}
	lower := strings.ToLower(text)
	if seasonRangeRe.MatchString(text) || strings.Contains(lower, "complete series") {
		return PackSeries
	}
	if completeRe.MatchString(text) {
		if strings.Contains(lower, "series") {
			return PackSeries
		}
		return PackSeason
	}
	if m := epRangeRe.FindStringSubmatch(text); len(m) == 4 {
		start, end := atoiSafe(m[2]), atoiSafe(m[3])
		if end > start {
			if end-start >= 6 {
				return PackSeason
			}
			return PackMulti
		}
	}
	if m := epRangeBareRe.FindStringSubmatch(text); len(m) == 3 {
		start, end := atoiSafe(m[1]), atoiSafe(m[2])
		if end > start {
			if end-start >= 6 {
				return PackSeason
			}
			return PackMulti
		}
	}
	markers := extractSxxExx(text)
	if len(markers) >= 2 {
		return PackMulti
	}
	if len(markers) == 1 {
		return PackSingle
	}
	// "Show.S01.1080p" / "Season 1" without episode → season pack
	if seasonWordRe.MatchString(text) {
		return PackSeason
	}
	if seasonTokenRe.MatchString(text) && !sxxexxRePack.MatchString(text) {
		return PackSeason
	}
	return PackSingle
}

func extractSxxExx(text string) [][2]int {
	re := regexp.MustCompile(`(?i)\bS(\d{1,2})E(\d{1,3})\b`)
	var out [][2]int
	for _, m := range re.FindAllStringSubmatch(text, -1) {
		out = append(out, [2]int{atoiSafe(m[1]), atoiSafe(m[2])})
	}
	return out
}

func atoiSafe(s string) int {
	n := 0
	for _, r := range s {
		if r < '0' || r > '9' {
			break
		}
		n = n*10 + int(r-'0')
	}
	return n
}

// DetectLanguages returns language hints from a release title.
func DetectLanguages(title string) []string {
	lower := " " + strings.ToLower(title) + " "
	var out []string
	add := func(code string, needles ...string) {
		for _, n := range needles {
			if strings.Contains(lower, n) {
				out = append(out, code)
				return
			}
		}
	}
	add("en", " english ", " eng ", ".eng.", " eng.", " eng-", "-eng-", " english.", " multi ", " dual ", " dual-audio ")
	add("es", " spanish ", " latino ", " castellano ", " esp ", ".spa.")
	add("fr", " french ", " franais ", " français ", " vf ", " vostfr ", ".fra.")
	add("de", " german ", " deutsch ", ".ger.", " german.")
	add("it", " italian ", " ita ", ".ita.")
	add("pt", " portuguese ", " brazilian ", " multi-pt ", ".por.")
	add("ru", " russian ", ".rus.")
	add("ja", " japanese ", ".jpn.")
	add("ko", " korean ", ".kor.")
	add("zh", " chinese ", " mandarin ", ".chi.")
	add("nordic", " nordic ", " scandinavian ")
	return uniqueStrings(out)
}

// DetectTags returns encode/format tags for structured UI.
func DetectTags(title string) []string {
	raw := strings.ToLower(title)
	var out []string
	add := func(tag string, needles ...string) {
		for _, n := range needles {
			if strings.Contains(raw, n) {
				out = append(out, tag)
				return
			}
		}
	}
	add("DV", "dolby vision", "dovi", ".dv.", " dv ", "dvhe")
	add("HDR10+", "hdr10+")
	if !containsTag(out, "HDR10+") && !containsTag(out, "DV") {
		add("HDR", "hdr10", " hdr", ".hdr")
	}
	add("Atmos", "atmos")
	add("DTS-HD", "dts-hd", "dtshd", "dts:x")
	add("TrueHD", "truehd")
	add("Remux", "remux")
	add("BluRay", "bluray", "blu-ray", "bdrip", "bdremux")
	add("WEB", "web-dl", "webdl", "webrip")
	add("HEVC", "x265", "hevc", "h.265", "h265")
	if !containsTag(out, "HEVC") {
		add("AVC", "x264", "h.264", "h264", "avc")
	}
	add("Hybrid", "hybrid")
	add("Proper", "proper", "repack")
	return out
}

func containsTag(tags []string, want string) bool {
	for _, t := range tags {
		if t == want {
			return true
		}
	}
	return false
}

func uniqueStrings(in []string) []string {
	seen := map[string]bool{}
	out := make([]string, 0, len(in))
	for _, s := range in {
		if s == "" || seen[s] {
			continue
		}
		seen[s] = true
		out = append(out, s)
	}
	return out
}
