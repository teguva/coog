package settings

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
)

type Streaming struct {
	SaveToLibrary       bool     `json:"saveToLibrary"`
	RealDebridToken     string   `json:"realDebridToken,omitempty"`
	AutoplayNextEpisode bool     `json:"autoplayNextEpisode"`
	AutoDownloadNext    bool     `json:"autoDownloadNextEpisode"`
	PrefetchMinutes     int      `json:"prefetchBeforeEndMinutes"`
	PrefetchCount       int      `json:"prefetchCount"`
	ContinueOverlaySec  int      `json:"continueOverlaySeconds"`
	TorrentioProviders  []string `json:"torrentioProviders"`
	ExcludeQualities    []string `json:"excludeQualities"`
	IncludeWebStreams   bool     `json:"includeWebStreams"`
	AutoSelectSource    bool     `json:"autoSelectSource"`
	PreferredQualities  []string `json:"preferredQualities"`
	PreferredLanguages  []string `json:"preferredLanguages"`
	// PreferredBackdropMax caps the "display" backdrop tier (hero / focused cards).
	// "1080p" (default), "1440p", or "2160p". Masters stay full-res as "orig".
	PreferredBackdropMax string       `json:"preferredBackdropMax"`
	MinSizeMB            int          `json:"minSizeMb"`
	MaxSizeMB            int          `json:"maxSizeMb"`
	PreferSingleEpisode  bool         `json:"preferSingleEpisode"`
	AllowSeasonPacks     bool         `json:"allowSeasonPacks"`
	RequireCached        bool         `json:"requireCached"`
	Movies               DownloadRule `json:"movies"`
	Series               DownloadRule `json:"series"`
}

// DownloadRule is the autodownload picker for one catalog kind (movies or series).
type DownloadRule struct {
	Rank                string   `json:"rank"`
	PreferredQualities  []string `json:"preferredQualities"`
	PreferredLanguages  []string `json:"preferredLanguages"`
	RequireLanguage     bool     `json:"requireLanguage"`
	MinSizeMB           int      `json:"minSizeMb"`
	MaxSizeMB           int      `json:"maxSizeMb"`
	RequireCached       bool     `json:"requireCached"`
	AllowWeb            bool     `json:"allowWeb"`
	PreferRemux         bool     `json:"preferRemux"`
	PreferHDR           bool     `json:"preferHdr"`
	PreferAtmos         bool     `json:"preferAtmos"`
	PreferSingleEpisode bool     `json:"preferSingleEpisode"`
	AllowSeasonPacks    bool     `json:"allowSeasonPacks"`
}

func DefaultStreaming() Streaming {
	return Streaming{
		SaveToLibrary:       true,
		AutoplayNextEpisode: true,
		AutoDownloadNext:    true,
		PrefetchMinutes:     5,
		PrefetchCount:       1,
		ContinueOverlaySec:  10,
		TorrentioProviders: []string{
			"yts", "eztv", "rarbg", "1337x", "thepiratebay",
			"kickasstorrents", "torrentgalaxy", "magnetdl", "rutor", "rutracker",
		},
		ExcludeQualities:     []string{"threed", "480p", "cam", "scr"},
		IncludeWebStreams:    true,
		AutoSelectSource:     true,
		PreferredQualities:   []string{"1080p", "2160p"},
		PreferredLanguages:   []string{"en", "eng", "english"},
		PreferredBackdropMax: "1080p",
		MinSizeMB:            0,
		MaxSizeMB:            0,
		PreferSingleEpisode:  true,
		AllowSeasonPacks:     true,
		RequireCached:        false,
	}
}

func DefaultMovieRule() DownloadRule {
	return DownloadRule{
		Rank:               "quality",
		PreferredQualities: []string{"1080p", "2160p"},
		PreferredLanguages: []string{"en"},
		AllowWeb:           true,
		PreferRemux:        true,
		PreferHDR:          true,
		PreferAtmos:        true,
	}
}

func DefaultSeriesRule() DownloadRule {
	r := DefaultMovieRule()
	r.PreferSingleEpisode = true
	r.AllowSeasonPacks = true
	return r
}

// NormalizeRank returns quality, size, or seeders.
func NormalizeRank(v string) string {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "size", "largest", "highest-size", "highest_size":
		return "size"
	case "seeders", "peers":
		return "seeders"
	default:
		return "quality"
	}
}

func (r DownloadRule) IsZero() bool {
	return r.Rank == "" &&
		len(r.PreferredQualities) == 0 &&
		len(r.PreferredLanguages) == 0 &&
		r.MinSizeMB == 0 &&
		r.MaxSizeMB == 0 &&
		!r.RequireLanguage &&
		!r.RequireCached
}

func (cfg *Streaming) HydrateDownloadRules() {
	if cfg.Movies.IsZero() {
		cfg.Movies = ruleFromLegacy(*cfg, false)
	} else {
		cfg.Movies.Rank = NormalizeRank(cfg.Movies.Rank)
		cfg.Movies.PreferredLanguages = compactLangs(cfg.Movies.PreferredLanguages)
	}
	if cfg.Series.IsZero() {
		cfg.Series = ruleFromLegacy(*cfg, true)
	} else {
		cfg.Series.Rank = NormalizeRank(cfg.Series.Rank)
		cfg.Series.PreferredLanguages = compactLangs(cfg.Series.PreferredLanguages)
	}
}

func ruleFromLegacy(cfg Streaming, series bool) DownloadRule {
	r := DownloadRule{
		Rank:                "quality",
		PreferredQualities:  append([]string(nil), cfg.PreferredQualities...),
		PreferredLanguages:  append([]string(nil), cfg.PreferredLanguages...),
		MinSizeMB:           cfg.MinSizeMB,
		MaxSizeMB:           cfg.MaxSizeMB,
		RequireCached:       cfg.RequireCached,
		AllowWeb:            cfg.IncludeWebStreams,
		PreferRemux:         true,
		PreferHDR:           true,
		PreferAtmos:         true,
		PreferSingleEpisode: cfg.PreferSingleEpisode,
		AllowSeasonPacks:    cfg.AllowSeasonPacks,
	}
	if !series {
		r.PreferSingleEpisode = false
		r.AllowSeasonPacks = false
	}
	if len(r.PreferredQualities) == 0 {
		r.PreferredQualities = DefaultMovieRule().PreferredQualities
	}
	r.PreferredLanguages = compactLangs(r.PreferredLanguages)
	if len(r.PreferredLanguages) == 0 {
		r.PreferredLanguages = DefaultMovieRule().PreferredLanguages
	}
	return r
}

func compactLangs(in []string) []string {
	out := make([]string, 0, len(in))
	seen := map[string]bool{}
	for _, raw := range in {
		s := strings.ToLower(strings.TrimSpace(raw))
		switch s {
		case "eng", "english":
			s = "en"
		case "fra", "fre", "french":
			s = "fr"
		case "spa", "spanish":
			s = "es"
		case "ger", "deu", "german":
			s = "de"
		}
		if s == "" || seen[s] {
			continue
		}
		seen[s] = true
		out = append(out, s)
	}
	return out
}

func RuleForKind(cfg Streaming, kind string) DownloadRule {
	cfg.HydrateDownloadRules()
	switch strings.ToLower(strings.TrimSpace(kind)) {
	case "series", "episode", "show", "tv":
		return cfg.Series
	default:
		return cfg.Movies
	}
}

// ApplyDownloadRule copies present keys from a JSON object onto a rule.
func ApplyDownloadRule(cur DownloadRule, raw map[string]any) DownloadRule {
	if v, ok := raw["rank"].(string); ok {
		cur.Rank = NormalizeRank(v)
	}
	if v, exists := raw["preferredQualities"]; exists {
		cur.PreferredQualities = anyStrings(v)
	}
	if v, exists := raw["preferredLanguages"]; exists {
		cur.PreferredLanguages = compactLangs(anyStrings(v))
	}
	if v, ok := anyBool(raw["requireLanguage"]); ok {
		cur.RequireLanguage = v
	}
	if v, ok := anyInt(raw["minSizeMb"]); ok {
		cur.MinSizeMB = v
	}
	if v, ok := anyInt(raw["maxSizeMb"]); ok {
		cur.MaxSizeMB = v
	}
	if v, ok := anyBool(raw["requireCached"]); ok {
		cur.RequireCached = v
	}
	if v, ok := anyBool(raw["allowWeb"]); ok {
		cur.AllowWeb = v
	}
	if v, ok := anyBool(raw["preferRemux"]); ok {
		cur.PreferRemux = v
	}
	if v, ok := anyBool(raw["preferHdr"]); ok {
		cur.PreferHDR = v
	}
	if v, ok := anyBool(raw["preferAtmos"]); ok {
		cur.PreferAtmos = v
	}
	if v, ok := anyBool(raw["preferSingleEpisode"]); ok {
		cur.PreferSingleEpisode = v
	}
	if v, ok := anyBool(raw["allowSeasonPacks"]); ok {
		cur.AllowSeasonPacks = v
	}
	if cur.Rank == "" {
		cur.Rank = "quality"
	}
	return cur
}

func anyStrings(v any) []string {
	switch t := v.(type) {
	case []string:
		return cleanRuleStrings(t)
	case []any:
		out := make([]string, 0, len(t))
		for _, item := range t {
			s, ok := item.(string)
			if !ok {
				continue
			}
			s = strings.TrimSpace(s)
			if s != "" {
				out = append(out, s)
			}
		}
		return cleanRuleStrings(out)
	case string:
		return cleanRuleStrings(strings.Split(t, ","))
	default:
		return nil
	}
}

func cleanRuleStrings(in []string) []string {
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
	return out
}

func anyInt(v any) (int, bool) {
	switch t := v.(type) {
	case float64:
		return int(t), true
	case int:
		return t, true
	case int64:
		return int(t), true
	case string:
		s := strings.TrimSpace(t)
		if s == "" {
			return 0, true
		}
		n := 0
		for _, r := range s {
			if r < '0' || r > '9' {
				return 0, false
			}
			n = n*10 + int(r-'0')
		}
		return n, true
	default:
		return 0, false
	}
}

func anyBool(v any) (bool, bool) {
	b, ok := v.(bool)
	return b, ok
}

// NormalizePreferredBackdropMax returns 1080p, 1440p, or 2160p.
func NormalizePreferredBackdropMax(v string) string {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "1440", "1440p", "qhd", "2k":
		return "1440p"
	case "2160", "2160p", "4k", "uhd", "3840":
		return "2160p"
	default:
		return "1080p"
	}
}

// BackdropDisplayMaxEdge is the long-edge pixel cap for size=display backdrops.
func BackdropDisplayMaxEdge(preferred string) int {
	switch NormalizePreferredBackdropMax(preferred) {
	case "1440p":
		return 2560
	case "2160p":
		return 3840
	default:
		return 1920
	}
}

func Path(dataPath string) string {
	return filepath.Join(dataPath, "streaming.json")
}

func Load(dataPath string) Streaming {
	cfg := DefaultStreaming()
	b, err := os.ReadFile(Path(dataPath))
	if err == nil {
		_ = json.Unmarshal(b, &cfg)
	}
	if cfg.RealDebridToken == "" {
		cfg.RealDebridToken = tokenFromDisk()
	}
	if v := strings.TrimSpace(os.Getenv("REALDEBRID_API_TOKEN")); v != "" {
		cfg.RealDebridToken = v
	}
	if len(cfg.TorrentioProviders) == 0 {
		cfg.TorrentioProviders = DefaultStreaming().TorrentioProviders
	}
	if len(cfg.ExcludeQualities) == 0 {
		cfg.ExcludeQualities = DefaultStreaming().ExcludeQualities
	}
	if len(cfg.PreferredQualities) == 0 {
		cfg.PreferredQualities = DefaultStreaming().PreferredQualities
	}
	if len(cfg.PreferredLanguages) == 0 {
		cfg.PreferredLanguages = DefaultStreaming().PreferredLanguages
	}
	if cfg.PrefetchCount <= 0 {
		cfg.PrefetchCount = 1
	}
	if cfg.ContinueOverlaySec <= 0 {
		cfg.ContinueOverlaySec = 10
	}
	cfg.PreferredBackdropMax = NormalizePreferredBackdropMax(cfg.PreferredBackdropMax)
	cfg.HydrateDownloadRules()
	return cfg
}

func Save(dataPath string, cfg Streaming) error {
	if err := os.MkdirAll(dataPath, 0o755); err != nil {
		return err
	}
	current := Load(dataPath)
	if strings.TrimSpace(cfg.RealDebridToken) == "" {
		cfg.RealDebridToken = current.RealDebridToken
	}
	b, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(Path(dataPath), b, 0o600)
}

func MaskToken(token string) string {
	token = strings.TrimSpace(token)
	if token == "" {
		return ""
	}
	if len(token) <= 8 {
		return "••••"
	}
	return token[:4] + "…" + token[len(token)-4:]
}

func tokenFromDisk() string {
	home, _ := os.UserHomeDir()
	if home == "" {
		return ""
	}
	paths := []string{
		filepath.Join(home, ".config", "coog", "realdebrid.json"),
		filepath.Join(home, ".config", "tv-shell", "realdebrid.json"),
	}
	for _, p := range paths {
		b, err := os.ReadFile(p)
		if err != nil {
			continue
		}
		var wrap struct {
			APIToken string `json:"apiToken"`
			Token    string `json:"token"`
		}
		if json.Unmarshal(b, &wrap) != nil {
			continue
		}
		if v := strings.TrimSpace(wrap.APIToken); v != "" {
			return v
		}
		if v := strings.TrimSpace(wrap.Token); v != "" {
			return v
		}
	}
	return ""
}
