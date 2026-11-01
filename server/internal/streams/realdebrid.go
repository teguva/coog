package streams

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"path/filepath"
	"strings"
	"time"
)

const rdAPI = "https://api.real-debrid.com/rest/1.0"

type rdClient struct {
	token  string
	client *http.Client
}

func User(ctx context.Context, token string) (UserInfo, error) {
	if strings.TrimSpace(token) == "" {
		return UserInfo{}, fmt.Errorf("real-debrid is not configured")
	}
	rd := &rdClient{token: token, client: &http.Client{Timeout: 12 * time.Second}}
	var user UserInfo
	if err := rd.json(ctx, http.MethodGet, "/user", nil, &user); err != nil {
		return UserInfo{}, err
	}
	return user, nil
}

type UserInfo struct {
	ID         int    `json:"id"`
	Username   string `json:"username"`
	Email      string `json:"email"`
	Type       string `json:"type"`
	Premium    int64  `json:"premium"`
	Expiration string `json:"expiration"`
}

func ResolveHTTP(ctx context.Context, token string, cand Candidate) (string, error) {
	if token == "" {
		return "", fmt.Errorf("real-debrid is not configured")
	}
	if strings.EqualFold(cand.Source, "web") || strings.EqualFold(cand.Kind, "web") {
		return "", fmt.Errorf("web embed is not a Real-Debrid link")
	}
	if u := httpURL(cand.URL); u != "" && !strings.Contains(strings.ToLower(u), "torrentio.strem.fun") {
		return u, nil
	}
	hash := cand.InfoHash
	fileIndex := cand.FileIndex
	filename := cand.Filename
	if parsed, ok := parseTorrentioResolve(cand.URL); ok {
		if hash == "" {
			hash = parsed.InfoHash
		}
		if filename == "" {
			filename = parsed.Filename
		}
		if fileIndex == 0 && parsed.FileIndex > 0 {
			fileIndex = parsed.FileIndex
		}
	}
	if hash == "" {
		hash = InfoHash(cand.URL)
	}
	if hash == "" {
		return "", fmt.Errorf("no magnet or info hash")
	}
	rd := &rdClient{token: token, client: &http.Client{Timeout: 30 * time.Second}}
	direct, err := rd.unrestrictMagnet(ctx, hash, fileIndex, filename)
	if err != nil {
		return "", annotateRD(err, cand)
	}
	return direct, nil
}

func httpURL(raw string) string {
	u := strings.TrimSpace(raw)
	if strings.HasPrefix(strings.ToLower(u), "http://") || strings.HasPrefix(strings.ToLower(u), "https://") {
		return u
	}
	return ""
}

func (c *rdClient) unrestrictMagnet(ctx context.Context, hash string, fileIndex int, filename string) (string, error) {
	// Torrentio resolve with fileIndex 0 picks the first disc file (often a
	// 300MB menu/trailer .m2ts). Only trust it when the candidate names a file.
	if filename != "" || fileIndex > 0 {
		if u, err := resolveViaTorrentio(ctx, c.token, hash, fileIndex, filename); err == nil && u != "" {
			return u, nil
		}
	}
	if existing, err := c.findTorrent(ctx, hash); err == nil && existing != nil && existing.ID != "" {
		if u, err := c.waitAndUnrestrict(ctx, existing.ID, fileIndex, filename); err == nil && u != "" {
			return u, nil
		}
	}

	magnet := Magnet(hash)
	form := url.Values{"magnet": {magnet}}
	var added struct {
		ID string `json:"id"`
	}
	if err := c.form(ctx, http.MethodPost, "/torrents/addMagnet", form, &added); err != nil {
		return "", err
	}
	if added.ID == "" {
		return "", fmt.Errorf("real-debrid addMagnet returned no id")
	}
	_ = c.form(ctx, http.MethodPost, "/torrents/selectFiles/"+added.ID, url.Values{"files": {"all"}}, nil)
	return c.waitAndUnrestrict(ctx, added.ID, fileIndex, filename)
}

type rdTorrent struct {
	ID     string   `json:"id"`
	Hash   string   `json:"hash"`
	Status string   `json:"status"`
	Links  []string `json:"links"`
}

type rdTorrentFile struct {
	ID       int    `json:"id"`
	Path     string `json:"path"`
	Bytes    int64  `json:"bytes"`
	Selected int    `json:"selected"`
}

func (c *rdClient) findTorrent(ctx context.Context, hash string) (*rdTorrent, error) {
	var list []rdTorrent
	if err := c.json(ctx, http.MethodGet, "/torrents", nil, &list); err != nil {
		return nil, err
	}
	needle := strings.ToLower(hash)
	for i := range list {
		if strings.ToLower(list[i].Hash) == needle {
			return &list[i], nil
		}
	}
	return nil, nil
}

func (c *rdClient) waitAndUnrestrict(ctx context.Context, torrentID string, fileIndex int, filename string) (string, error) {
	deadline := time.Now().Add(90 * time.Second)
	var info struct {
		Status string         `json:"status"`
		Links  []string       `json:"links"`
		Files  []rdTorrentFile `json:"files"`
	}
	for time.Now().Before(deadline) {
		if err := ctx.Err(); err != nil {
			return "", err
		}
		if err := c.json(ctx, http.MethodGet, "/torrents/info/"+torrentID, nil, &info); err != nil {
			return "", err
		}
		switch info.Status {
		case "downloaded", "uploading":
			if link := pickRDHostedLink(info.Files, info.Links, fileIndex, filename); link != "" {
				return c.unrestrict(ctx, link)
			}
			if len(info.Links) > 0 {
				return c.unrestrict(ctx, info.Links[0])
			}
		case "magnet_error", "error", "virus", "dead":
			return "", formatRDTorrentStatus(info.Status)
		}
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case <-time.After(2 * time.Second):
		}
	}
	return "", fmt.Errorf("Real-Debrid timed out waiting for this torrent to become ready. It may not be cached — try a Cached / RD+ source.")
}

// pickRDHostedLink chooses the main feature among RD hosted links.
// BluRay remuxes often list menu/trailer .m2ts first (Links[0]) while the
// feature is a later 50GB+ file — always prefer the largest video unless a
// filename / fileIndex hint says otherwise.
func pickRDHostedLink(files []rdTorrentFile, links []string, fileIndex int, filename string) string {
	if len(links) == 0 {
		return ""
	}
	type hit struct {
		link  string
		path  string
		bytes int64
		idx   int // index among selected files (= links index)
		orig  int // index in full files list
	}
	var selected []hit
	orig := 0
	for _, f := range files {
		if f.Selected != 1 {
			orig++
			continue
		}
		if len(selected) >= len(links) {
			break
		}
		selected = append(selected, hit{
			link:  links[len(selected)],
			path:  f.Path,
			bytes: f.Bytes,
			idx:   len(selected),
			orig:  orig,
		})
		orig++
	}
	if len(selected) == 0 {
		return links[0]
	}

	hint := strings.ToLower(filepath.Base(strings.TrimSpace(filename)))
	score := func(h hit) int64 {
		name := strings.ToLower(filepath.Base(h.path))
		ext := strings.ToLower(filepath.Ext(h.path))
		n := h.bytes
		switch ext {
		case ".mkv", ".mp4", ".m4v", ".ts", ".m2ts", ".avi", ".mov", ".wmv", ".webm":
			// keep size
		default:
			n = n / 100
			if n < 1 {
				n = 1
			}
		}
		if strings.Contains(name, "sample") || strings.Contains(name, "trailer") ||
			strings.Contains(name, "preview") || strings.Contains(name, "menu") {
			n = n / 20
			if n < 1 {
				n = 1
			}
		}
		return n
	}

	if hint != "" {
		var best hit
		var bestN int64
		found := false
		for _, h := range selected {
			name := strings.ToLower(filepath.Base(h.path))
			if hint == name || strings.Contains(name, hint) || strings.Contains(hint, name) {
				if n := score(h); !found || n > bestN {
					best, bestN, found = h, n, true
				}
			}
		}
		if found {
			return best.link
		}
	}

	// Explicit non-zero file index from Torrentio (0-based in the torrent file list).
	if fileIndex > 0 {
		for _, h := range selected {
			if h.orig == fileIndex {
				return h.link
			}
		}
		if fileIndex < len(selected) {
			return selected[fileIndex].link
		}
	}

	best := selected[0]
	bestN := score(best)
	for _, h := range selected[1:] {
		if n := score(h); n > bestN {
			best, bestN = h, n
		}
	}
	return best.link
}

func resolveViaTorrentio(ctx context.Context, token, hash string, fileIndex int, filename string) (string, error) {
	u := torrentioResolveURL(token, hash, fileIndex, filename)
	if u == "" {
		return "", fmt.Errorf("no torrentio resolve url")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", "coog/0.1")
	req.Header.Set("Range", "bytes=0-0")
	client := &http.Client{
		Timeout: 45 * time.Second,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 8 {
				return fmt.Errorf("too many redirects")
			}
			return nil
		},
	}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	_, _ = io.CopyN(io.Discard, resp.Body, 64)
	final := ""
	if resp.Request != nil && resp.Request.URL != nil {
		final = resp.Request.URL.String()
	}
	lower := strings.ToLower(final)
	if final == "" || strings.Contains(lower, "torrentio.strem.fun") {
		return "", fmt.Errorf("torrentio resolve stayed on torrentio")
	}
	if strings.Contains(lower, "failed_infringement") || strings.Contains(lower, "failed_forbidden") {
		return "", fmt.Errorf("torrentio resolve infringement stub")
	}
	if !strings.HasPrefix(lower, "http://") && !strings.HasPrefix(lower, "https://") {
		return "", fmt.Errorf("torrentio resolve returned no url")
	}
	return final, nil
}

func (c *rdClient) unrestrict(ctx context.Context, link string) (string, error) {
	var row struct {
		Download string `json:"download"`
	}
	if err := c.form(ctx, http.MethodPost, "/unrestrict/link", url.Values{"link": {link}}, &row); err != nil {
		return "", err
	}
	if row.Download == "" {
		return "", fmt.Errorf("real-debrid unrestrict returned no url")
	}
	return row.Download, nil
}

func (c *rdClient) form(ctx context.Context, method, path string, form url.Values, dest any) error {
	return c.do(ctx, method, path, "application/x-www-form-urlencoded", strings.NewReader(form.Encode()), dest)
}

func (c *rdClient) json(ctx context.Context, method, path string, body io.Reader, dest any) error {
	return c.do(ctx, method, path, "application/json", body, dest)
}

func (c *rdClient) do(ctx context.Context, method, path, contentType string, body io.Reader, dest any) error {
	req, err := http.NewRequestWithContext(ctx, method, rdAPI+path, body)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	if body != nil && contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	resp, err := c.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	payload, _ := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	if resp.StatusCode >= 400 {
		return formatRDHTTPError(resp.StatusCode, path, payload)
	}
	if dest == nil || len(payload) == 0 {
		return nil
	}
	return json.Unmarshal(payload, dest)
}
