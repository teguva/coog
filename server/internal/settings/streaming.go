package settings

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
)

type Streaming struct {
	SaveToLibrary        bool     `json:"saveToLibrary"`
	RealDebridToken      string   `json:"realDebridToken,omitempty"`
	AutoplayNextEpisode  bool     `json:"autoplayNextEpisode"`
	AutoDownloadNext     bool     `json:"autoDownloadNextEpisode"`
	PrefetchMinutes      int      `json:"prefetchBeforeEndMinutes"`
	PrefetchCount        int      `json:"prefetchCount"`
	ContinueOverlaySec   int      `json:"continueOverlaySeconds"`
	TorrentioProviders   []string `json:"torrentioProviders"`
	ExcludeQualities     []string `json:"excludeQualities"`
	IncludeWebStreams    bool     `json:"includeWebStreams"`
	AutoSelectSource     bool     `json:"autoSelectSource"`
	PreferredQualities   []string `json:"preferredQualities"`
	PreferredLanguages   []string `json:"preferredLanguages"`
	// PreferredBackdropMax caps the "display" backdrop tier (hero / focused cards).
	// "1080p" (default), "1440p", or "2160p". Masters stay full-res as "orig".
	PreferredBackdropMax string   `json:"preferredBackdropMax"`
	MinSizeMB            int      `json:"minSizeMb"`
	MaxSizeMB            int      `json:"maxSizeMb"`
	PreferSingleEpisode  bool     `json:"preferSingleEpisode"`
	AllowSeasonPacks     bool     `json:"allowSeasonPacks"`
	RequireCached        bool     `json:"requireCached"`
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
