package streams

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"coog/internal/settings"
)

const torrentioBase = "https://torrentio.strem.fun"

type Candidate struct {
	Title     string   `json:"title"`
	Name      string   `json:"name,omitempty"`
	InfoHash  string   `json:"infoHash"`
	URL       string   `json:"url,omitempty"`
	Seeders   int      `json:"seeders"`
	Size      int64    `json:"size"`
	SizeLabel string   `json:"sizeLabel,omitempty"`
	Cached    bool     `json:"cached"`
	Quality   string   `json:"quality,omitempty"`
	Source    string   `json:"source,omitempty"`
	Provider  string   `json:"provider,omitempty"`
	Kind      string   `json:"kind,omitempty"`
	FileIndex int      `json:"fileIndex,omitempty"`
	Filename  string   `json:"filename,omitempty"`
	Pack      string   `json:"pack,omitempty"`
	Tags      []string `json:"tags,omitempty"`
	Languages []string `json:"languages,omitempty"`
}

func PublicCandidate(c Candidate) map[string]any {
	c = enrichCandidate(c)
	kind := c.Kind
	if kind == "" {
		if c.Source == "web" {
			kind = "web"
		} else {
			kind = "torrent"
		}
	}
	out := map[string]any{
		"infoHash":      c.InfoHash,
		"title":         c.Title,
		"name":          c.Name,
		"quality":       c.Quality,
		"cached":        c.Cached,
		"seeders":       c.Seeders,
		"size":          c.Size,
		"sizeLabel":     c.SizeLabel,
		"source":        c.Source,
		"provider":      c.Provider,
		"kind":          kind,
		"pack":          c.Pack,
		"tags":          c.Tags,
		"languages":     c.Languages,
		"languageFlags": LanguageFlags(c.Languages),
	}
	if kind == "web" && httpURL(c.URL) != "" {
		out["url"] = c.URL
	}
	return out
}

func SearchTorrentio(ctx context.Context, cfg settings.Streaming, kind, imdb string, season, episode int) ([]Candidate, error) {
	imdb = strings.TrimSpace(imdb)
	if imdb == "" {
		return nil, fmt.Errorf("missing imdb id")
	}
	typ := "movie"
	id := imdb
	if kind == "series" || kind == "episode" {
		typ = "series"
		s, e := season, episode
		if s <= 0 {
			s = 1
		}
		if e <= 0 {
			e = 1
		}
		id = fmt.Sprintf("%s:%d:%d", imdb, s, e)
	}
	u := torrentioStreamURL(cfg, typ, id)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "coog/0.1")
	client := &http.Client{Timeout: 25 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		return nil, fmt.Errorf("torrentio http %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return nil, err
	}
	var wrap struct {
		Streams []map[string]any `json:"streams"`
	}
	if err := json.Unmarshal(body, &wrap); err != nil {
		return nil, err
	}
	out := []Candidate{}
	for _, row := range wrap.Streams {
		c := candidateFromStream(row)
		if c.InfoHash == "" && c.URL == "" {
			continue
		}
		out = append(out, enrichCandidate(c))
	}
	return SortCandidates(out), nil
}

func torrentioConfigPath(cfg settings.Streaming) string {
	var parts []string
	if len(cfg.TorrentioProviders) > 0 {
		parts = append(parts, "providers="+strings.Join(cfg.TorrentioProviders, ","))
	}
	if len(cfg.ExcludeQualities) > 0 {
		parts = append(parts, "qualityfilter="+strings.Join(cfg.ExcludeQualities, ","))
	}
	// Omit debridoptions=nocatalog so Torrentio also returns titles already
	// in the Real-Debrid cloud library (RD Catalog / RD+).
	if tok := strings.TrimSpace(cfg.RealDebridToken); tok != "" {
		parts = append(parts, "realdebrid="+tok)
	}
	return strings.Join(parts, "|")
}

func torrentioStreamURL(cfg settings.Streaming, typ, id string) string {
	path := torrentioConfigPath(cfg)
	if path == "" {
		return torrentioBase + "/stream/" + typ + "/" + url.PathEscape(id) + ".json"
	}
	return torrentioBase + "/" + path + "/stream/" + typ + "/" + url.PathEscape(id) + ".json"
}

func torrentioResolveURL(token, hash string, fileIndex int, filename string) string {
	token = strings.TrimSpace(token)
	hash = InfoHash(hash)
	if token == "" || hash == "" {
		return ""
	}
	if fileIndex < 0 {
		fileIndex = 0
	}
	name := strings.TrimSpace(filename)
	if name == "" {
		name = "video"
	}
	return torrentioBase + "/resolve/realdebrid/" + token + "/" + hash + "/null/" +
		strconv.Itoa(fileIndex) + "/" + url.PathEscape(name)
}

func PickBest(cands []Candidate) Candidate {
	sorted := SortCandidates(cands)
	if len(sorted) == 0 {
		return Candidate{}
	}
	return sorted[0]
}

// PickBestPreferred uses streaming prefs when possible, else falls back to PickBest.
func PickBestPreferred(cands []Candidate, cfg settings.Streaming, kind string) Candidate {
	got := PickPreferred(cands, PrefsFromSettings(cfg, kind))
	if got.OK {
		return got.Candidate
	}
	return PickBest(cands)
}

func candidateFromStream(row map[string]any) Candidate {
	title := str(row["title"])
	name := str(row["name"])
	if title == "" {
		title = name
	}
	hash := InfoHash(str(row["infoHash"]))
	if hash == "" {
		hash = InfoHash(str(row["info_hash"]))
	}
	rawURL := str(row["url"])
	resolved, _ := parseTorrentioResolve(rawURL)
	if hash == "" {
		hash = resolved.InfoHash
	}
	if hash == "" {
		hash = InfoHash(rawURL)
	}
	fileIndex := resolved.FileIndex
	filename := resolved.Filename
	if hints, ok := row["behaviorHints"].(map[string]any); ok {
		if filename == "" {
			filename = str(hints["filename"])
		}
		if v := int64Val(hints["fileIdx"]); fileIndex == 0 && v > 0 {
			fileIndex = int(v)
		}
		if v := int64Val(hints["fileIndex"]); fileIndex == 0 && v > 0 {
			fileIndex = int(v)
		}
	}
	seeders, _ := strconv.Atoi(str(row["seeders"]))
	if seeders == 0 {
		seeders = parseSeeders(title + " " + name)
	}
	blob := strings.ToLower(name + " " + title)
	cached := strings.Contains(blob, "[rd+") || strings.Contains(blob, "[rd +")
	catalog := isRDCatalog(blob)
	if catalog {
		cached = true
	}
	size := int64Val(row["size"])
	if size == 0 {
		size = int64Val(row["fileSize"])
	}
	if size == 0 {
		if hints, ok := row["behaviorHints"].(map[string]any); ok {
			size = int64Val(hints["videoSize"])
		}
	}
	return Candidate{
		Title:     title,
		Name:      name,
		InfoHash:  hash,
		URL:       rawURL,
		Seeders:   seeders,
		Size:      size,
		Cached:    cached,
		FileIndex: fileIndex,
		Filename:  filename,
	}
}

func isRDCatalog(blob string) bool {
	if !strings.Contains(blob, "catalog") {
		return false
	}
	return strings.Contains(blob, "[rd") ||
		strings.Contains(blob, "rd catalog") ||
		strings.Contains(blob, "real-debrid") ||
		strings.Contains(blob, "realdebrid")
}

func str(v any) string {
	switch t := v.(type) {
	case string:
		return strings.TrimSpace(t)
	case float64:
		return strconv.FormatInt(int64(t), 10)
	default:
		return ""
	}
}

func int64Val(v any) int64 {
	switch t := v.(type) {
	case float64:
		return int64(t)
	case int64:
		return t
	case int:
		return int64(t)
	case json.Number:
		n, _ := t.Int64()
		return n
	case string:
		n, _ := strconv.ParseInt(strings.TrimSpace(t), 10, 64)
		return n
	default:
		return 0
	}
}

func parseSeeders(title string) int {
	idx := strings.Index(title, "👤")
	if idx < 0 {
		idx = strings.Index(strings.ToLower(title), "seed")
	}
	if idx < 0 {
		return 0
	}
	return firstNumber(title[idx:])
}
