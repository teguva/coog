package maize

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/argon2"
)

const (
	defaultBucket      = "Maize"
	defaultIdleMinutes = 20
	sessionTTL         = 24 * time.Hour
	maxPinFails        = 5
	failLockout        = 5 * time.Minute
)

// Config is persisted under $COOG_DATA_PATH/maize.json.
type Config struct {
	PinHash     string `json:"pinHash,omitempty"`
	PinSalt     string `json:"pinSalt,omitempty"`
	Bucket      string `json:"bucket,omitempty"`
	IdleMinutes int    `json:"idleMinutes,omitempty"`
	// PeopleDir is Jellyfin-style actor profiles (Funplay-compatible).
	// Empty → $HOME/.cache/funplay/People.
	PeopleDir string `json:"peopleDir,omitempty"`
	// Phase 2 hooks (unused until encryption is enabled).
	Encrypted  bool   `json:"encrypted,omitempty"`
	MountPoint string `json:"mountPoint,omitempty"`
}

// ResolvedPeopleDir returns the configured People folder, or the Funplay default.
func (c Config) ResolvedPeopleDir() string {
	if strings.TrimSpace(c.PeopleDir) != "" {
		return filepath.Clean(strings.TrimSpace(c.PeopleDir))
	}
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		return filepath.Join(".cache", "funplay", "People")
	}
	return filepath.Join(home, ".cache", "funplay", "People")
}

func DefaultConfig() Config {
	return Config{
		Bucket:      defaultBucket,
		IdleMinutes: defaultIdleMinutes,
	}
}

func configPath(dataPath string) string {
	return filepath.Join(dataPath, "maize.json")
}

func LoadConfig(dataPath string) Config {
	cfg := DefaultConfig()
	b, err := os.ReadFile(configPath(dataPath))
	if err == nil {
		_ = json.Unmarshal(b, &cfg)
	}
	if strings.TrimSpace(cfg.Bucket) == "" {
		cfg.Bucket = defaultBucket
	}
	if cfg.IdleMinutes <= 0 {
		cfg.IdleMinutes = defaultIdleMinutes
	}
	return cfg
}

func SaveConfig(dataPath string, cfg Config) error {
	if err := os.MkdirAll(dataPath, 0o755); err != nil {
		return err
	}
	if strings.TrimSpace(cfg.Bucket) == "" {
		cfg.Bucket = defaultBucket
	}
	if cfg.IdleMinutes <= 0 {
		cfg.IdleMinutes = defaultIdleMinutes
	}
	b, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(configPath(dataPath), b, 0o600)
}

func (c Config) PinConfigured() bool {
	return c.PinHash != "" && c.PinSalt != ""
}

func HashPIN(pin string) (hashB64, saltB64 string, err error) {
	pin = strings.TrimSpace(pin)
	if len(pin) < 4 || len(pin) > 12 {
		return "", "", fmt.Errorf("pin must be 4–12 digits")
	}
	for _, r := range pin {
		if r < '0' || r > '9' {
			return "", "", fmt.Errorf("pin must be numeric")
		}
	}
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", "", err
	}
	hash := argon2.IDKey([]byte(pin), salt, 1, 64*1024, 4, 32)
	return base64.RawStdEncoding.EncodeToString(hash), base64.RawStdEncoding.EncodeToString(salt), nil
}

func VerifyPIN(cfg Config, pin string) bool {
	if !cfg.PinConfigured() {
		return false
	}
	salt, err := base64.RawStdEncoding.DecodeString(cfg.PinSalt)
	if err != nil {
		return false
	}
	want, err := base64.RawStdEncoding.DecodeString(cfg.PinHash)
	if err != nil {
		return false
	}
	got := argon2.IDKey([]byte(strings.TrimSpace(pin)), salt, 1, 64*1024, 4, 32)
	return subtle.ConstantTimeCompare(got, want) == 1
}

// IsMaizeRel reports whether a library-relative path is under the Maize bucket.
func IsMaizeRel(rel, bucket string) bool {
	rel = filepath.ToSlash(strings.TrimPrefix(rel, "/"))
	bucket = strings.TrimSpace(bucket)
	if bucket == "" {
		bucket = defaultBucket
	}
	first, _, _ := strings.Cut(rel, "/")
	if first == "" {
		first = rel
	}
	return strings.EqualFold(first, bucket)
}

// IsMaizePath reports whether an absolute media path sits under libraryRoot/bucket.
func IsMaizePath(libraryRoot, absPath, bucket string) bool {
	root, err := filepath.Abs(libraryRoot)
	if err != nil {
		return false
	}
	abs, err := filepath.Abs(absPath)
	if err != nil {
		return false
	}
	if bucket == "" {
		bucket = defaultBucket
	}
	prefix := filepath.Join(root, bucket)
	rel, err := filepath.Rel(prefix, abs)
	if err != nil {
		return false
	}
	return rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}

// Sessions tracks short-lived adult unlock tokens with per-IP rate limits.
type Sessions struct {
	mu      sync.Mutex
	tokens  map[string]time.Time
	fails   map[string]failState
	mount   MountBackend
}

type failState struct {
	count   int
	until   time.Time
}

func NewSessions(mount MountBackend) *Sessions {
	if mount == nil {
		mount = NoopMount{}
	}
	return &Sessions{
		tokens: map[string]time.Time{},
		fails:  map[string]failState{},
		mount:  mount,
	}
}

func (s *Sessions) Mount() MountBackend { return s.mount }

func (s *Sessions) Create() (token string, err error) {
	b := make([]byte, 24)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	token = base64.RawURLEncoding.EncodeToString(b)
	s.mu.Lock()
	s.tokens[token] = time.Now().Add(sessionTTL)
	s.mu.Unlock()
	return token, nil
}

func (s *Sessions) Valid(token string) bool {
	token = strings.TrimSpace(token)
	if token == "" {
		return false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	exp, ok := s.tokens[token]
	if !ok {
		return false
	}
	if time.Now().After(exp) {
		delete(s.tokens, token)
		return false
	}
	return true
}

func (s *Sessions) Touch(token string) {
	token = strings.TrimSpace(token)
	if token == "" {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.tokens[token]; ok {
		s.tokens[token] = time.Now().Add(sessionTTL)
	}
}

func (s *Sessions) Revoke(token string) {
	s.mu.Lock()
	delete(s.tokens, strings.TrimSpace(token))
	s.mu.Unlock()
}

func (s *Sessions) RevokeAll() {
	s.mu.Lock()
	s.tokens = map[string]time.Time{}
	s.mu.Unlock()
}

// ActiveCount returns the number of non-expired adult sessions (prunes expired).
func (s *Sessions) ActiveCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	for tok, exp := range s.tokens {
		if now.After(exp) {
			delete(s.tokens, tok)
		}
	}
	return len(s.tokens)
}

func (s *Sessions) LockedOut(ip string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	st, ok := s.fails[ip]
	if !ok {
		return false
	}
	return time.Now().Before(st.until)
}

func (s *Sessions) RecordFail(ip string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	st := s.fails[ip]
	st.count++
	if st.count >= maxPinFails {
		st.until = time.Now().Add(failLockout)
		st.count = 0
	}
	s.fails[ip] = st
}

func (s *Sessions) ClearFails(ip string) {
	s.mu.Lock()
	delete(s.fails, ip)
	s.mu.Unlock()
}

// PublicView is safe for admin/API status responses.
func PublicView(cfg Config, unlocked bool) map[string]any {
	return map[string]any{
		"configured":  cfg.PinConfigured(),
		"bucket":      cfg.Bucket,
		"idleMinutes": cfg.IdleMinutes,
		"unlocked":    unlocked,
		"encrypted":   cfg.Encrypted,
		"mountPoint":  cfg.MountPoint,
	}
}
