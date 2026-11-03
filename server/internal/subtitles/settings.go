package subtitles

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"coog/internal/config"
)

var versionRe = regexp.MustCompile(`(?i)\bv\d`)

// Settings mirrors linux-tv-interface opensubtitles.json.
type Settings struct {
	Enabled         bool     `json:"enabled"`
	APIKey          string   `json:"apiKey,omitempty"`
	Username        string   `json:"username,omitempty"`
	Password        string   `json:"password,omitempty"`
	UserAgent       string   `json:"userAgent,omitempty"`
	Languages       []string `json:"languages"`
	AutoLoad        bool     `json:"autoLoad"`
	AutoSync        bool     `json:"autoSync"`
	PreferEmbedded  bool     `json:"preferEmbedded"`
	// SearchOnline when false skips live OpenSubtitles API search and only
	// lists already-downloaded cache files that match the title.
	SearchOnline bool `json:"searchOnline"`
}

func DefaultSettings() Settings {
	return Settings{
		Enabled:        true,
		UserAgent:      "Coog v" + config.Version,
		Languages:      []string{"en"},
		AutoLoad:       true,
		AutoSync:       false,
		PreferEmbedded: true,
		SearchOnline:   true,
	}
}

func Path(dataPath string) string {
	return filepath.Join(dataPath, "subtitles.json")
}

func CacheDir(dataPath string) string {
	return filepath.Join(dataPath, "subtitles")
}

func Load(dataPath string) Settings {
	cfg := DefaultSettings()
	b, err := os.ReadFile(Path(dataPath))
	if err != nil {
		return normalize(cfg)
	}
	var raw map[string]json.RawMessage
	_ = json.Unmarshal(b, &raw)
	_ = json.Unmarshal(b, &cfg)
	// Missing key must keep default true (Go bool zero is false).
	if _, ok := raw["searchOnline"]; !ok {
		cfg.SearchOnline = true
	}
	return normalize(cfg)
}

func Save(dataPath string, cfg Settings) error {
	cfg = normalize(cfg)
	if err := os.MkdirAll(dataPath, 0o755); err != nil {
		return err
	}
	current := Load(dataPath)
	if strings.TrimSpace(cfg.Password) == "" {
		cfg.Password = current.Password
	}
	if strings.TrimSpace(cfg.APIKey) == "" {
		cfg.APIKey = current.APIKey
	}
	b, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(Path(dataPath), b, 0o600)
}

func normalize(cfg Settings) Settings {
	if len(cfg.Languages) == 0 {
		cfg.Languages = []string{"en"}
	}
	out := make([]string, 0, len(cfg.Languages))
	seen := map[string]bool{}
	for _, l := range cfg.Languages {
		code := strings.ToLower(strings.TrimSpace(l))
		if code == "" || seen[code] {
			continue
		}
		seen[code] = true
		out = append(out, code)
	}
	if len(out) == 0 {
		out = []string{"en"}
	}
	cfg.Languages = out
	ua := strings.TrimSpace(cfg.UserAgent)
	if ua == "" {
		ua = strings.TrimSpace(os.Getenv("OPENSUBTITLES_USER_AGENT"))
	}
	if ua == "" {
		ua = "Coog v" + config.Version
	}
	if !versionRe.MatchString(ua) {
		ua = ua + " v" + config.Version
	}
	cfg.UserAgent = ua
	if key := strings.TrimSpace(os.Getenv("OPENSUBTITLES_API_KEY")); key != "" && strings.TrimSpace(cfg.APIKey) == "" {
		cfg.APIKey = key
	}
	return cfg
}

// Public masks secrets for API responses.
func (s Settings) Public() map[string]any {
	hasKey := strings.TrimSpace(s.APIKey) != ""
	masked := ""
	if hasKey {
		key := s.APIKey
		if len(key) > 8 {
			masked = key[:4] + "…" + key[len(key)-4:]
		} else {
			masked = "••••"
		}
	}
	return map[string]any{
		"enabled":         s.Enabled,
		"hasApiKey":       hasKey,
		"apiKeyMasked":    masked,
		"username":        s.Username,
		"hasPassword":     strings.TrimSpace(s.Password) != "",
		"userAgent":       s.UserAgent,
		"languages":       s.Languages,
		"autoLoad":        s.AutoLoad,
		"autoSync":        s.AutoSync,
		"preferEmbedded":  s.PreferEmbedded,
		"searchOnline":    s.SearchOnline,
	}
}
