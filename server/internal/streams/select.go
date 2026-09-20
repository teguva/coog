package streams

import (
	"strings"

	"coog/internal/settings"
)

// SelectPrefs drives automatic source picking (from streaming settings).
type SelectPrefs struct {
	PreferredQualities  []string // e.g. 1080p, 2160p — empty = any not excluded
	ExcludeQualities    []string
	MinSizeBytes        int64 // 0 = no minimum
	MaxSizeBytes        int64 // 0 = no maximum
	PreferredLanguages  []string
	PreferSingleEpisode bool
	AllowSeasonPacks    bool // season/series packs OK when props match
	RequireCached       bool // only RD+ / cached
}

// PrefsFromSettings maps streaming.json into picker prefs.
func PrefsFromSettings(cfg settings.Streaming) SelectPrefs {
	return SelectPrefs{
		PreferredQualities:  cfg.PreferredQualities,
		ExcludeQualities:    cfg.ExcludeQualities,
		MinSizeBytes:        int64(cfg.MinSizeMB) * 1000 * 1000,
		MaxSizeBytes:        int64(cfg.MaxSizeMB) * 1000 * 1000,
		PreferredLanguages:  cfg.PreferredLanguages,
		PreferSingleEpisode: cfg.PreferSingleEpisode,
		AllowSeasonPacks:    cfg.AllowSeasonPacks,
		RequireCached:       cfg.RequireCached,
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
	if len(prefs.PreferredLanguages) > 0 && !languageOK(c.Languages, prefs.PreferredLanguages) {
		return "language mismatch"
	}
	return ""
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
	}
	return false
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
	// Rank for storing highest-quality library files (resolution → source → HDR → audio → bitrate).
	s := Score(c)
	s += qualityRank(c.Quality) * 500
	for _, tag := range c.Tags {
		switch tag {
		case "Remux":
			s += 800
		case "BluRay":
			s += 400
		case "DV":
			s += 400 // prefer DV over HDR10+/HDR for archival
		case "HDR10+":
			s += 320
		case "HDR":
			s += 250
		case "Atmos":
			s += 180
		case "TrueHD", "DTS-HD":
			s += 150
		case "WEB":
			s += 100
		case "HEVC":
			s += 50
		case "Hybrid":
			// Often DV+HDR10 remux/web; mild bump, still below pure DV Atmos remux.
			s += 40
		case "Proper":
			s += 30
		}
	}
	if prefs.PreferSingleEpisode {
		switch pack {
		case PackSingle:
			s += 600
		case PackMulti:
			s += 100
		case PackSeason:
			s -= 200
		case PackSeries:
			s -= 400
		}
	}
	if len(c.Languages) > 0 {
		s += 80
	}
	// Prefer larger files (bitrate proxy) within any allowed size window.
	if size := effectiveSize(c, pack); size > 0 {
		s += sizeQualityBonus(size, prefs.MinSizeBytes, prefs.MaxSizeBytes)
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
