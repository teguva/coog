package streams

import (
	"strings"

	"coog/internal/settings"
)

// SelectPrefs drives automatic source picking (from streaming settings).
type SelectPrefs struct {
	Rank                string   // quality, size, or seeders
	PreferredQualities  []string // e.g. 1080p, 2160p — empty = any not excluded
	ExcludeQualities    []string
	MinSizeBytes        int64 // 0 = no minimum
	MaxSizeBytes        int64 // 0 = no maximum
	PreferredLanguages  []string
	RequireLanguage     bool // reject unknown / non-matching audio when preferred list is set
	PreferSingleEpisode bool
	AllowSeasonPacks    bool // season/series packs OK when props match
	RequireCached       bool // only RD+ / cached
	AllowWeb            bool
	PreferRemux         bool
	PreferHDR           bool
	PreferAtmos         bool
}

// PrefsFromSettings maps streaming.json into picker prefs for a catalog kind.
func PrefsFromSettings(cfg settings.Streaming, kind string) SelectPrefs {
	rule := settings.RuleForKind(cfg, kind)
	requireCached := rule.RequireCached || cfg.RequireCached
	return SelectPrefs{
		Rank:                settings.NormalizeRank(rule.Rank),
		PreferredQualities:  rule.PreferredQualities,
		ExcludeQualities:    cfg.ExcludeQualities,
		MinSizeBytes:        int64(rule.MinSizeMB) * 1000 * 1000,
		MaxSizeBytes:        int64(rule.MaxSizeMB) * 1000 * 1000,
		PreferredLanguages:  rule.PreferredLanguages,
		RequireLanguage:     rule.RequireLanguage,
		PreferSingleEpisode: rule.PreferSingleEpisode,
		AllowSeasonPacks:    rule.AllowSeasonPacks,
		RequireCached:       requireCached,
		AllowWeb:            rule.AllowWeb && cfg.IncludeWebStreams,
		PreferRemux:         rule.PreferRemux,
		PreferHDR:           rule.PreferHDR,
		PreferAtmos:         rule.PreferAtmos,
	}
}

// SelectResult is the auto-pick outcome.
type SelectResult struct {
	Candidate Candidate
	OK        bool
	Reason    string
	Pack      PackKind
}

// EnrichMeta fills pack/tags/languages/quality/size on a candidate for API + UI.
func EnrichMeta(c *Candidate) {
	title := c.Title
	if title == "" {
		title = c.Name
	}
	if c.Quality == "" {
		c.Quality = DetectQuality(title)
	}
	if c.Size <= 0 {
		c.Size = ParseSizeBytes(title)
	}
	if c.SizeLabel == "" && c.Size > 0 {
		c.SizeLabel = FormatSizeLabel(c.Size)
	}
	c.Pack = string(ClassifyPack(title))
	c.Tags = DetectTags(title)
	langBlob := strings.TrimSpace(c.Title + " " + c.Name + " " + c.Filename)
	c.Languages = DetectLanguages(langBlob)
}

// PickPreferred chooses the best candidate matching prefs, or OK=false for manual pick.
func PickPreferred(cands []Candidate, prefs SelectPrefs) SelectResult {
	if len(cands) == 0 {
		return SelectResult{Reason: "no sources"}
	}
	type scored struct {
		c     Candidate
		pack  PackKind
		score int
	}
	var ok []scored
	for _, raw := range cands {
		c := raw
		EnrichMeta(&c)
		pack := PackKind(c.Pack)
		if why := rejectReason(c, pack, prefs); why != "" {
			continue
		}
		ok = append(ok, scored{c: c, pack: pack, score: preferenceScore(c, pack, prefs)})
	}
	if len(ok) == 0 {
		return SelectResult{Reason: "no source matches preferred quality, size, or language"}
	}
	best := ok[0]
	for _, s := range ok[1:] {
		if s.score > best.score {
			best = s
		}
	}
	return SelectResult{
		Candidate: best.c,
		OK:        true,
		Reason:    "matched preferences",
		Pack:      best.pack,
	}
}

func rejectReason(c Candidate, pack PackKind, prefs SelectPrefs) string {
	title := strings.ToLower(c.Title + " " + c.Name + " " + c.Quality)
	for _, ex := range prefs.ExcludeQualities {
		ex = strings.ToLower(strings.TrimSpace(ex))
		if ex == "" {
			continue
		}
		if ex == "threed" && (strings.Contains(title, "3d") || strings.Contains(title, "hsbs")) {
			return "excluded quality"
		}
		if strings.Contains(title, ex) || strings.EqualFold(c.Quality, ex) {
			return "excluded quality"
		}
	}
	if len(prefs.PreferredQualities) > 0 {
		if !qualityAllowed(c.Quality, title, prefs.PreferredQualities) {
			return "quality not preferred"
		}
	}
	if !prefs.AllowWeb && isWebCandidate(c) {
		return "web not allowed"
	}
	if prefs.RequireCached && !c.Cached && !strings.EqualFold(c.Source, "rdcatalog") {
		return "not cached"
	}
	size := effectiveSize(c, pack)
	if prefs.MinSizeBytes > 0 && size > 0 && size < prefs.MinSizeBytes {
		return "below min size"
	}
	if prefs.MaxSizeBytes > 0 && size > prefs.MaxSizeBytes {
		return "above max size"
	}
	// Unknown size: allow unless both min and max are set tightly — still allow.
	if pack == PackSeason || pack == PackSeries {
		if !prefs.AllowSeasonPacks {
			return "season pack not allowed"
		}
		// Packs must still look "worthy": remux/bluray/web in preferred band already checked.
		if isJunkRelease(title) {
			return "pack quality too low"
		}
	}
	if pack == PackMulti && prefs.PreferSingleEpisode {
		// Multi-ep bundles are OK if props match; prefer singles via score, don't reject.
	}
	if prefs.RequireLanguage && len(prefs.PreferredLanguages) > 0 {
		if len(c.Languages) == 0 || !languageOK(c.Languages, prefs.PreferredLanguages) {
			return "language mismatch"
		}
	}
	return ""
}

func isWebCandidate(c Candidate) bool {
	if strings.EqualFold(c.Kind, "web") || strings.EqualFold(c.Source, "web") {
		return true
	}
	return false
}

func effectiveSize(c Candidate, pack PackKind) int64 {
	if c.Size <= 0 {
		return 0
	}
	// For packs, judge average size per episode when we can estimate count from title.
	if pack == PackSeason || pack == PackSeries || pack == PackMulti {
		if n := estimateEpisodeCount(c.Title + " " + c.Name); n > 1 {
			return c.Size / int64(n)
		}
		// Unknown pack size: treat total as ~10 episodes for season, ~3 for multi.
		if pack == PackMulti {
			return c.Size / 3
		}
		return c.Size / 10
	}
	return c.Size
}

func estimateEpisodeCount(title string) int {
	if m := epRangeRe.FindStringSubmatch(title); len(m) == 4 {
		start, end := atoiSafe(m[2]), atoiSafe(m[3])
		if end > start {
			return end - start + 1
		}
	}
	if m := epRangeBareRe.FindStringSubmatch(title); len(m) == 3 {
		start, end := atoiSafe(m[1]), atoiSafe(m[2])
		if end > start {
			return end - start + 1
		}
	}
	if n := len(extractSxxExx(title)); n > 1 {
		return n
	}
	return 0
}

func qualityAllowed(quality, title string, preferred []string) bool {
	q := strings.ToLower(strings.TrimSpace(quality))
	if q == "" {
		q = strings.ToLower(DetectQuality(title))
	}
	for _, p := range preferred {
		p = strings.ToLower(strings.TrimSpace(p))
		if p == "" {
			continue
		}
		if q == p || strings.Contains(title, p) {
			return true
		}
		// 4k ↔ 2160p
		if (p == "2160p" || p == "4k" || p == "uhd") && (q == "2160p" || strings.Contains(title, "2160") || strings.Contains(title, "4k")) {
			return true
		}
	}
	return false
}

func languageOK(found, preferred []string) bool {
	if len(found) == 0 {
		return true // unknown → don't block
	}
	pref := map[string]bool{}
	for _, p := range preferred {
		pref[strings.ToLower(strings.TrimSpace(p))] = true
	}
	for _, f := range found {
		f = strings.ToLower(f)
		if pref[f] || (pref["en"] && (f == "eng" || f == "english" || f == "en")) {
			return true
		}
		if pref["eng"] && (f == "en" || f == "english") {
			return true
		}
		if f == "multi" || f == "dual" {
			return true
		}
		if pref["nordic"] && (f == "nordic" || f == "da" || f == "no" || f == "sv" || f == "fi") {
			return true
		}
		if pref["latino"] && (f == "latino" || f == "es") {
			return true
		}
		if pref["es"] && f == "latino" {
			return true
		}
	}
	return false
}

func languagePreferred(found, preferred []string) bool {
	if len(preferred) == 0 {
		return false
	}
	if len(found) == 0 {
		return false
	}
	return languageOK(found, preferred)
}

func isJunkRelease(title string) bool {
	for _, n := range []string{"cam", "hdcam", "hdts", "telesync", "telecine", "scr", "screener", "r5"} {
		if strings.Contains(title, n) {
			return true
		}
	}
	return false
}

func preferenceScore(c Candidate, pack PackKind, prefs SelectPrefs) int {
	lang := 0
	if languagePreferred(c.Languages, prefs.PreferredLanguages) {
		lang = 220
	}
	size := effectiveSize(c, pack)
	switch prefs.Rank {
	case "size":
		s := 0
		if size > 0 {
			s += int(size / 1_000_000) // 1 point per MB
		}
		s += qualityRank(c.Quality) * 3
		s += lang
		if c.Cached {
			s += 8
		}
		return s + packScore(pack, prefs)
	case "seeders":
		s := c.Seeders * 100
		s += qualityRank(c.Quality) * 10
		if size > 0 {
			s += int(size / 100_000_000)
		}
		s += lang
		if c.Cached {
			s += 50
		}
		return s + packScore(pack, prefs)
	default:
		// Rank for storing highest-quality library files (resolution → source → HDR → audio → bitrate).
		s := Score(c)
		s += qualityRank(c.Quality) * 500
		s += tagScore(c.Tags, prefs)
		s += packScore(pack, prefs)
		s += lang
		if size > 0 {
			s += sizeQualityBonus(size, prefs.MinSizeBytes, prefs.MaxSizeBytes)
		}
		return s
	}
}

func packScore(pack PackKind, prefs SelectPrefs) int {
	if !prefs.PreferSingleEpisode {
		return 0
	}
	switch pack {
	case PackSingle:
		return 600
	case PackMulti:
		return 100
	case PackSeason:
		return -200
	case PackSeries:
		return -400
	default:
		return 0
	}
}

func tagScore(tags []string, prefs SelectPrefs) int {
	s := 0
	for _, tag := range tags {
		switch tag {
		case "Remux":
			if prefs.PreferRemux {
				s += 800
			}
		case "BluRay":
			s += 400
		case "DV":
			if prefs.PreferHDR {
				s += 400
			}
		case "HDR10+":
			if prefs.PreferHDR {
				s += 320
			}
		case "HDR":
			if prefs.PreferHDR {
				s += 250
			}
		case "Atmos":
			if prefs.PreferAtmos {
				s += 180
			}
		case "TrueHD", "DTS-HD":
			if prefs.PreferAtmos {
				s += 150
			}
		case "WEB":
			s += 100
		case "HEVC":
			s += 50
		case "Hybrid":
			s += 40
		case "Proper":
			s += 30
		}
	}
	return s
}

// sizeQualityBonus favors bigger encodes for library storage.
// With min+max: score rises toward max. With neither: mild log-ish size bump (capped).
func sizeQualityBonus(size, minB, maxB int64) int {
	if minB > 0 && maxB > 0 && maxB > minB {
		span := maxB - minB
		pos := size - minB
		if pos < 0 {
			pos = 0
		}
		if pos > span {
			pos = span
		}
		return int(350 * float64(pos) / float64(span))
	}
	if maxB > 0 {
		if size >= maxB {
			return 300
		}
		return int(300 * float64(size) / float64(maxB))
	}
	// ~20 points per GB, capped so seeders/Score still matter on huge outliers.
	gb := float64(size) / 1_000_000_000
	bonus := int(gb * 20)
	if bonus > 400 {
		bonus = 400
	}
	return bonus
}

func qualityRank(q string) int {
	switch strings.ToLower(strings.TrimSpace(q)) {
	case "2160p", "4k", "uhd":
		return 4
	case "1080p":
		return 3
	case "720p":
		return 2
	case "480p":
		return 1
	default:
		return 0
	}
}
