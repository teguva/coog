package streams

import (
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode"
)

var sizeRe = regexp.MustCompile(`(?i)(\d+(?:\.\d+)?)\s*(tib|tb|gib|gb|mib|mb)\b`)

func DetectQuality(name string) string {
	text := strings.ToLower(name)
	if strings.Contains(text, "2160p") || strings.Contains(text, "3840x") || strings.Contains(text, "uhd") {
		return "2160p"
	}
	if has4KToken(text) {
		return "2160p"
	}
	if strings.Contains(text, "1080p") || strings.Contains(text, "1920x") {
		return "1080p"
	}
	if strings.Contains(text, "720p") || strings.Contains(text, "1280x") {
		return "720p"
	}
	if strings.Contains(text, "480p") {
		return "480p"
	}
	return ""
}

func ParseSizeBytes(text string) int64 {
	if text == "" {
		return 0
	}
	start := strings.Index(text, "💾")
	slice := text
	if start >= 0 {
		slice = text[start:]
	}
	m := sizeRe.FindStringSubmatch(slice)
	if m == nil {
		m = sizeRe.FindStringSubmatch(text)
	}
	if m == nil {
		return 0
	}
	n, err := strconv.ParseFloat(m[1], 64)
	if err != nil {
		return 0
	}
	switch strings.ToLower(m[2]) {
	case "tb", "tib":
		return int64(n * 1000 * 1000 * 1000 * 1000)
	case "gb", "gib":
		return int64(n * 1000 * 1000 * 1000)
	case "mb", "mib":
		return int64(n * 1000 * 1000)
	default:
		return 0
	}
}

func FormatSizeLabel(size int64) string {
	if size <= 0 {
		return ""
	}
	if size >= 1000*1000*1000 {
		return strconv.FormatFloat(float64(size)/(1000*1000*1000), 'f', 2, 64) + " GB"
	}
	if size >= 1000*1000 {
		return strconv.FormatFloat(float64(size)/(1000*1000), 'f', 0, 64) + " MB"
	}
	return strconv.FormatInt(size, 10) + " B"
}

func Score(c Candidate) int {
	s := c.Seeders
	if c.Cached {
		s += 10_000
	}
	if c.Source == "rdcatalog" {
		s += 2_000
	}
	u := strings.ToLower(strings.TrimSpace(c.URL))
	if strings.HasPrefix(u, "http://") || strings.HasPrefix(u, "https://") {
		if !strings.Contains(u, "torrentio.strem.fun") {
			s += 5_000
		}
	}
	return s
}

func SortCandidates(cands []Candidate) []Candidate {
	out := append([]Candidate(nil), cands...)
	sort.SliceStable(out, func(i, j int) bool {
		return Score(out[i]) > Score(out[j])
	})
	return out
}

func CapCandidates(cands []Candidate, n int) []Candidate {
	if n <= 0 || len(cands) <= n {
		return cands
	}
	return cands[:n]
}

func enrichCandidate(c Candidate) Candidate {
	blob := strings.TrimSpace(c.Title + " " + c.Name)
	if c.Quality == "" {
		c.Quality = DetectQuality(blob)
	}
	if c.Size <= 0 {
		c.Size = ParseSizeBytes(blob)
	}
	c.SizeLabel = FormatSizeLabel(c.Size)
	if c.Source == "" {
		blob := strings.ToLower(c.Name + " " + c.Title)
		switch {
		case isRDCatalog(blob):
			c.Source = "rdcatalog"
			c.Cached = true
		case c.Cached:
			c.Source = "realdebrid"
		default:
			c.Source = "torrent"
		}
	}
	if c.Provider == "" {
		if c.Source == "web" {
			c.Provider = "1movies"
		} else {
			c.Provider = "Torrentio"
		}
	}
	if c.Kind == "" {
		if c.Source == "web" {
			c.Kind = "web"
		} else {
			c.Kind = "torrent"
		}
	}
	EnrichMeta(&c)
	return c
}

func has4KToken(text string) bool {
	for i := 0; i+1 < len(text); i++ {
		if text[i] != '4' || (text[i+1] != 'k' && text[i+1] != 'K') {
			continue
		}
		prevOK := i == 0 || !isAlnum(rune(text[i-1]))
		nextOK := i+2 >= len(text) || !isAlnum(rune(text[i+2]))
		if prevOK && nextOK {
			return true
		}
	}
	return false
}

func isAlnum(r rune) bool {
	return (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9')
}

func firstNumber(s string) int {
	n := 0
	started := false
	for _, r := range s {
		if r >= '0' && r <= '9' {
			started = true
			n = n*10 + int(r-'0')
			if n > 1_000_000 {
				break
			}
			continue
		}
		if started {
			break
		}
		if unicode.IsLetter(r) && started {
			break
		}
	}
	return n
}
