package enrich

import (
	"crypto/sha1"
	"encoding/hex"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	userAgent       = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
	requestGap      = 1200 * time.Millisecond
	httpTimeout     = 20 * time.Second
	htmlCacheTTL    = 14 * 24 * time.Hour
	minImageBytes   = 2048
	defaultGalleryN = 50
)

// Client is a polite, single-flight HTTP helper with on-disk HTML cache.
type Client struct {
	cacheDir string
	mu       sync.Mutex
	lastAt   time.Time
	http     *http.Client
}

func NewClient(cacheDir string) *Client {
	_ = os.MkdirAll(cacheDir, 0o755)
	return &Client{
		cacheDir: cacheDir,
		http: &http.Client{
			Timeout: httpTimeout,
			CheckRedirect: func(req *http.Request, via []*http.Request) error {
				if len(via) >= 10 {
					return http.ErrUseLastResponse
				}
				return nil
			},
		},
	}
}

func (c *Client) throttle() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if wait := requestGap - time.Since(c.lastAt); wait > 0 {
		time.Sleep(wait)
	}
	c.lastAt = time.Now()
}

func (c *Client) cachePath(url string) string {
	sum := sha1.Sum([]byte(url))
	return filepath.Join(c.cacheDir, hex.EncodeToString(sum[:])+".html")
}

func (c *Client) GetText(url string) (string, error) {
	url = strings.TrimSpace(url)
	if url == "" {
		return "", nil
	}
	cache := c.cachePath(url)
	if st, err := os.Stat(cache); err == nil && time.Since(st.ModTime()) < htmlCacheTTL {
		b, err := os.ReadFile(cache)
		if err == nil && len(b) > 0 {
			return string(b), nil
		}
	}
	c.throttle()
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", userAgent)
	if strings.Contains(strings.ToLower(url), "pornhub.com") {
		req.AddCookie(&http.Cookie{Name: "accessAgeDisclaimerPH", Value: "1"})
		req.AddCookie(&http.Cookie{Name: "age_verified", Value: "1"})
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", nil
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		return "", err
	}
	text := string(b)
	_ = os.WriteFile(cache, b, 0o644)
	return text, nil
}

func (c *Client) GetBytes(url, referer string) ([]byte, error) {
	url = strings.TrimSpace(url)
	if url == "" {
		return nil, nil
	}
	c.throttle()
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", userAgent)
	if referer != "" {
		req.Header.Set("Referer", referer)
	}
	if strings.Contains(strings.ToLower(url), "pornhub.com") || strings.Contains(strings.ToLower(url), "phncdn.com") {
		req.AddCookie(&http.Cookie{Name: "accessAgeDisclaimerPH", Value: "1"})
		req.AddCookie(&http.Cookie{Name: "age_verified", Value: "1"})
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, nil
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 20<<20))
	if err != nil {
		return nil, err
	}
	if len(b) < minImageBytes {
		return nil, nil
	}
	return b, nil
}

func extFromURL(url, fallback string) string {
	low := strings.ToLower(url)
	for _, ext := range []string{".jpeg", ".jpg", ".png", ".webp"} {
		if strings.Contains(low, ext) {
			if ext == ".jpeg" {
				return ".jpg"
			}
			return ext
		}
	}
	if fallback == "" {
		return ".jpg"
	}
	return fallback
}
