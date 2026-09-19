package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

const Version = "0.1.18"

type Config struct {
	Listen      string
	LibraryPath string
	DataPath    string
	FFmpeg      string
	FFprobe     string
	AuthToken   string
	AdminDir    string
	TMDBKey     string
	YTDLP       string
	MetaTTLDays int

	IntifaceEnabled bool
	IntifaceBin     string
	IntifacePort    int
	IntifaceUDCF    string
	FunplayState    string
}

func FromEnv() Config {
	home, _ := os.UserHomeDir()
	if home == "" {
		home = "."
	}
	data := env("COOG_DATA_PATH", filepath.Join(home, ".local", "share", "coog"))
	bin := env("COOG_INTIFACE_BIN", "intiface-engine")
	enabled := envBool("COOG_INTIFACE_ENABLED", lookPath(bin))
	return Config{
		Listen:          env("COOG_LISTEN", ":8090"),
		LibraryPath:     env("COOG_LIBRARY_PATH", filepath.Join(home, "Videos")),
		DataPath:        data,
		FFmpeg:          env("COOG_FFMPEG", "ffmpeg"),
		FFprobe:         env("COOG_FFPROBE", "ffprobe"),
		AuthToken:       os.Getenv("COOG_AUTH_TOKEN"),
		AdminDir:        os.Getenv("COOG_ADMIN_DIR"),
		TMDBKey:         firstNonEmpty(env("COOG_TMDB_API_KEY", os.Getenv("TMDB_API_KEY")), tmdbKeyFromDisk(home)),
		YTDLP:           env("COOG_YTDLP", "yt-dlp"),
		MetaTTLDays:     envInt("COOG_META_TTL_DAYS", 30),
		IntifaceEnabled: enabled,
		IntifaceBin:     bin,
		IntifacePort:    envInt("COOG_INTIFACE_PORT", 12345),
		IntifaceUDCF:    env("COOG_INTIFACE_UDCF", filepath.Join(data, "buttplug-user-device-config-v4.json")),
		FunplayState:    env("COOG_FUNPLAY_STATE", filepath.Join(home, ".cache", "funplay", "library_state.json")),
	}
}

func (c Config) DBPath() string {
	return filepath.Join(c.DataPath, "coog.db")
}

func env(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return fallback
}

func envInt(key string, fallback int) int {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return fallback
	}
	n, err := strconv.Atoi(v)
	if err != nil || n <= 0 {
		return fallback
	}
	return n
}

func envBool(key string, fallback bool) bool {
	v := strings.TrimSpace(strings.ToLower(os.Getenv(key)))
	if v == "" {
		return fallback
	}
	switch v {
	case "1", "true", "yes", "on":
		return true
	case "0", "false", "no", "off":
		return false
	default:
		return fallback
	}
}

func lookPath(bin string) bool {
	if bin == "" {
		return false
	}
	if filepath.IsAbs(bin) {
		st, err := os.Stat(bin)
		return err == nil && !st.IsDir()
	}
	pathEnv := os.Getenv("PATH")
	for _, dir := range filepath.SplitList(pathEnv) {
		p := filepath.Join(dir, bin)
		st, err := os.Stat(p)
		if err == nil && !st.IsDir() {
			return true
		}
	}
	return false
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}

func tmdbKeyFromDisk(home string) string {
	paths := []string{
		filepath.Join(home, ".config", "coog", "tmdb.json"),
		filepath.Join(home, ".config", "tv-shell", "tmdb.json"),
	}
	for _, p := range paths {
		b, err := os.ReadFile(p)
		if err != nil {
			continue
		}
		var wrap struct {
			APIKey string `json:"apiKey"`
			Key    string `json:"key"`
		}
		if json.Unmarshal(b, &wrap) != nil {
			continue
		}
		if k := firstNonEmpty(wrap.APIKey, wrap.Key); k != "" {
			return k
		}
	}
	return ""
}
