package streams

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

const (
	onesMoviesBase = "https://1movies.stream"
	webUserAgent   = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)

var ErrBrowserPlayer = errors.New("this web player needs a browser captcha (Filemoon/Byse). Pick Real-Debrid or another server.")

var (
	watchHrefRe = regexp.MustCompile(`(?i)href=["']([^"']*(?:watch-series|watch-movie|watch-movies)[^"']*)["']`)
	titleAttrRe = regexp.MustCompile(`(?i)(?:title|alt)=["']([^"']+)["']`)
	tokenRe     = regexp.MustCompile(`data-token=["']([^"']+)["']`)
)

func ListWebCandidates(ctx context.Context, kind, title string, year, season, episode int) []Candidate {
	title = strings.Join(strings.Fields(title), " ")
	if title == "" {
		return nil
	}
	client, err := webClient()
	if err != nil {
		return nil
	}
	results, err := search1Movies(ctx, client, title, year)
	if err != nil || len(results) == 0 {
		return nil
	}
	match := pickWebMatch(results, kind, title, year)
	if match.url == "" {
		return nil
	}
	pageURL := match.url
	isSeries := strings.EqualFold(kind, "series") || strings.EqualFold(kind, "episode") || match.kind == "series"
	if isSeries {
		if season <= 0 || episode <= 0 {
			return nil
		}
		pageURL = episodePageURL(pageURL, season, episode)
	}
	servers, err := listWebServers(ctx, client, pageURL)
	if err != nil {
		return nil
	}
	// Prefer embeds we can already extract media from, but keep every server
	// the page advertises (some players are SPAs with no inline m3u8/mp4).
	servers = orderWebServersByPlayability(ctx, client, servers)
	out := make([]Candidate, 0, len(servers))
	for _, server := range servers {
		if server.link == "" {
			continue
		}
		release := title
		if isSeries {
			release = fmt.Sprintf("%s S%02dE%02d · %s", title, season, episode, server.name)
		} else {
			release = title + " · " + server.name
		}
		out = append(out, enrichCandidate(Candidate{
			Title:    release,
			Name:     "[Web] " + server.name,
			URL:      server.link,
			Quality:  DetectWebQuality(server.name + " " + release),
			Source:   "web",
			Provider: "1movies",
			Kind:     "web",
		}))
	}
	return out
}

type webHit struct {
	title string
	url   string
	kind  string
	score int
}

type webServer struct {
	name string
	link string
}

func webClient() (*http.Client, error) {
	jar, err := cookiejar.New(nil)
	if err != nil {
		return nil, err
	}
	return &http.Client{
		Timeout: 12 * time.Second,
		Jar:     jar,
	}, nil
}

func search1Movies(ctx context.Context, client *http.Client, title string, year int) ([]webHit, error) {
	hits, err := search1MoviesQuery(ctx, client, title, title, year)
	if err != nil || len(hits) > 0 {
		return hits, err
	}
	// livesearch is picky about punctuation ("90 Day The Single Life" misses
	// "90 Day: The Single Life"). A short prefix still ranks the right slug.
	words := strings.Fields(title)
	if len(words) >= 2 {
		return search1MoviesQuery(ctx, client, strings.Join(words[:2], " "), title, year)
	}
	return hits, nil
}

func search1MoviesQuery(ctx context.Context, client *http.Client, query, scoreTitle string, year int) ([]webHit, error) {
	q := url.Values{"q": {query}}
	html, err := webGet(ctx, client, onesMoviesBase+"/livesearch?"+q.Encode(), onesMoviesBase+"/")
	if err != nil {
		return nil, err
	}
	return parse1MoviesSearch(html, scoreTitle, year), nil
}

func parse1MoviesSearch(html, query string, year int) []webHit {
	seen := map[string]bool{}
	var hits []webHit
	qlow := strings.ToLower(query)
	yearS := ""
	if year > 0 {
		yearS = strconv.Itoa(year)
	}
	for _, m := range watchHrefRe.FindAllStringSubmatchIndex(html, -1) {
		if len(m) < 4 {
			continue
		}
		href := html[m[2]:m[3]]
		abs := abs1Movies(href)
		if abs == "" || seen[abs] || strings.Contains(abs, "/search/") {
			continue
		}
		seen[abs] = true
		kind := "movie"
		if strings.Contains(abs, "/watch-series/") {
			kind = "series"
		}
		label := query
		start := m[0] - 200
		if start < 0 {
			start = 0
		}
		end := m[1] + 200
		if end > len(html) {
			end = len(html)
		}
		if tm := titleAttrRe.FindStringSubmatch(html[start:end]); len(tm) > 1 {
			if t := strings.TrimSpace(tm[1]); t != "" {
				label = t
			}
		}
		score := 0
		low := strings.ToLower(label)
		slug := strings.ToLower(abs)
		if qlow != "" && strings.Contains(low, qlow) {
			score += 10
		}
		slugQ := strings.ReplaceAll(qlow, " ", "-")
		if slugQ != "" && strings.Contains(slug, slugQ) {
			score += 8
		}
		if yearS != "" && (strings.Contains(label, yearS) || strings.Contains(abs, yearS)) {
			score += 5
		}
		hits = append(hits, webHit{title: label, url: abs, kind: kind, score: score})
	}
	for i := 0; i < len(hits); i++ {
		for j := i + 1; j < len(hits); j++ {
			if hits[j].score > hits[i].score || (hits[j].score == hits[i].score && hits[j].title < hits[i].title) {
				hits[i], hits[j] = hits[j], hits[i]
			}
		}
	}
	return hits
}

func pickWebMatch(hits []webHit, metaType, title string, year int) webHit {
	if len(hits) == 0 {
		return webHit{}
	}
	wantSeries := strings.EqualFold(metaType, "series") || strings.EqualFold(metaType, "episode") || strings.EqualFold(metaType, "tv") || strings.EqualFold(metaType, "show")
	pool := hits
	filtered := make([]webHit, 0, len(hits))
	want := "movie"
	if wantSeries {
		want = "series"
	}
	for _, h := range hits {
		if h.kind == want {
			filtered = append(filtered, h)
		}
	}
	if len(filtered) > 0 {
		pool = filtered
	}
	titleL := strings.ToLower(title)
	yearS := ""
	if year > 0 {
		yearS = strconv.Itoa(year)
	}
	for _, row := range pool {
		label := strings.ToLower(row.title)
		slug := strings.ToLower(row.url)
		slugQ := strings.ReplaceAll(titleL, " ", "-")
		matched := titleL != "" && (strings.Contains(label, titleL) || (slugQ != "" && strings.Contains(slug, slugQ)))
		if matched {
			if yearS == "" || strings.Contains(row.title, yearS) || strings.Contains(row.url, yearS) {
				return row
			}
		}
	}
	return pool[0]
}

func episodePageURL(showURL string, season, episode int) string {
	showURL = strings.TrimRight(abs1Movies(showURL), "/") + "/"
	slug := ""
	if i := strings.Index(showURL, "/watch-series/"); i >= 0 {
		rest := strings.Trim(showURL[i+len("/watch-series/"):], "/")
		slug, _, _ = strings.Cut(rest, "/")
	}
	if slug == "" {
		u, err := url.Parse(showURL)
		if err == nil {
			parts := strings.Split(strings.Trim(u.Path, "/"), "/")
			if len(parts) > 0 {
				slug = parts[len(parts)-1]
			}
		}
	}
	if season < 1 {
		season = 1
	}
	if episode < 1 {
		episode = 1
	}
	return fmt.Sprintf("%s/episode/%s/s%02d-e%02d/", onesMoviesBase, slug, season, episode)
}

func listWebServers(ctx context.Context, client *http.Client, pageURL string) ([]webServer, error) {
	pageURL = abs1Movies(pageURL)
	html, err := webGet(ctx, client, pageURL, onesMoviesBase+"/")
	if err != nil {
		return nil, err
	}
	token := ""
	if m := tokenRe.FindStringSubmatch(html); len(m) > 1 {
		token = m[1]
	}
	if token == "" {
		return nil, fmt.Errorf("missing players token")
	}
	// Movies answer `players`; episodes still use `players_show`.
	var lastErr error
	for _, field := range []string{"players", "players_show"} {
		raw, err := webPostForm(ctx, client, onesMoviesBase+"/ajax/ajax.php", url.Values{field: {token}}, pageURL)
		if err != nil {
			lastErr = err
			continue
		}
		servers, err := parsePlayersJSON(raw)
		if err != nil {
			lastErr = err
			continue
		}
		if len(servers) > 0 {
			return servers, nil
		}
	}
	if lastErr != nil {
		return nil, lastErr
	}
	return nil, fmt.Errorf("no playable sources")
}

func filterPlayableWebServers(ctx context.Context, client *http.Client, servers []webServer) []webServer {
	out := make([]webServer, 0, len(servers))
	for _, server := range servers {
		html, err := webGet(ctx, client, server.link, onesMoviesBase+"/")
		if err != nil {
			continue
		}
		if ScrapeMediaURL(html) != "" {
			out = append(out, server)
		}
	}
	return out
}

// orderWebServersByPlayability puts embeds with extractable media first and
// leaves the rest intact so the sources list matches the site's server tabs.
func orderWebServersByPlayability(ctx context.Context, client *http.Client, servers []webServer) []webServer {
	if len(servers) <= 1 {
		return servers
	}
	playable := make([]webServer, 0, len(servers))
	rest := make([]webServer, 0, len(servers))
	for _, server := range servers {
		html, err := webGet(ctx, client, server.link, onesMoviesBase+"/")
		if err == nil && ScrapeMediaURL(html) != "" {
			playable = append(playable, server)
			continue
		}
		rest = append(rest, server)
	}
	return append(playable, rest...)
}

var (
	m3u8URLRe = regexp.MustCompile(`(?i)https?://[^"' \t\n<>\\]+?\.m3u8[^"' \t\n<>\\]*`)
	mp4URLRe  = regexp.MustCompile(`(?i)https?://[^"' \t\n<>\\]+?\.mp4(?:\?[^"' \t\n<>\\]*)?`)
	fileURLRe = regexp.MustCompile(`(?i)(?:file|source|src)\s*[:=]\s*["'](https?://[^"']+)["']`)
)

// ScrapeMediaURL pulls a direct HLS/MP4 URL out of an embed player page.
func ScrapeMediaURL(html string) string {
	html = strings.ReplaceAll(html, `\/`, `/`)
	if u := cleanMediaURL(m3u8URLRe.FindString(html)); u != "" {
		return u
	}
	if m := fileURLRe.FindStringSubmatch(html); len(m) > 1 {
		if u := cleanMediaURL(m[1]); u != "" && IsDirectMediaURL(u) {
			return u
		}
	}
	return cleanMediaURL(mp4URLRe.FindString(html))
}

func cleanMediaURL(raw string) string {
	raw = strings.TrimSpace(raw)
	raw = strings.Trim(raw, `"'`)
	raw = strings.TrimRight(raw, `.,);]}`)
	if !strings.HasPrefix(raw, "http://") && !strings.HasPrefix(raw, "https://") {
		return ""
	}
	return raw
}

func IsDirectMediaURL(raw string) bool {
	u := strings.ToLower(strings.TrimSpace(raw))
	if !strings.HasPrefix(u, "http://") && !strings.HasPrefix(u, "https://") {
		return false
	}
	return strings.Contains(u, ".m3u8") || strings.Contains(u, ".mp4") || strings.Contains(u, ".m4s") || strings.Contains(u, "/hls")
}

// ResolveWebEmbed fetches an embed page and returns a playable media URL.
func ResolveWebEmbed(ctx context.Context, embedURL string) (string, error) {
	embedURL = strings.TrimSpace(embedURL)
	if embedURL == "" {
		return "", fmt.Errorf("empty embed URL")
	}
	client, err := webClient()
	if err != nil {
		return "", err
	}
	html, err := webGet(ctx, client, embedURL, onesMoviesBase+"/")
	if err != nil {
		return "", err
	}
	if u := ScrapeMediaURL(html); u != "" {
		return u, nil
	}
	if looksLikeBysePlayer(html, embedURL) {
		return "", ErrBrowserPlayer
	}
	return "", fmt.Errorf("no media URL in embed")
}

func looksLikeBysePlayer(html, embedURL string) bool {
	_ = embedURL
	return strings.Contains(html, "Byse Frontend") || strings.Contains(html, "video-embed-mode")
}

func EmbedReferer(embedURL string) string {
	u, err := url.Parse(embedURL)
	if err != nil || u.Scheme == "" || u.Host == "" {
		return onesMoviesBase + "/"
	}
	return u.Scheme + "://" + u.Host + "/"
}

func WebUserAgent() string {
	return webUserAgent
}

func parsePlayersJSON(raw string) ([]webServer, error) {
	raw = strings.TrimSpace(raw)
	var rows []map[string]any
	if err := json.Unmarshal([]byte(raw), &rows); err != nil {
		var one map[string]any
		if err2 := json.Unmarshal([]byte(raw), &one); err2 != nil {
			return nil, fmt.Errorf("invalid players JSON")
		}
		if msg := strings.TrimSpace(str(one["error"])); msg != "" {
			return nil, fmt.Errorf("players: %s", msg)
		}
		if one != nil {
			rows = []map[string]any{one}
		}
	}
	out := make([]webServer, 0, len(rows))
	for _, row := range rows {
		name := strings.TrimSpace(str(row["name"]))
		if name == "" {
			name = "Server"
		}
		link := strings.TrimSpace(str(row["link"]))
		if link == "" {
			continue
		}
		out = append(out, webServer{name: name, link: link})
	}
	return out, nil
}

func DetectWebQuality(label string) string {
	text := strings.ToLower(label)
	if strings.Contains(text, "2160") || strings.Contains(text, "3840") || strings.Contains(text, "uhd") || has4KToken(text) {
		return "2160p"
	}
	if strings.Contains(text, "1080") || strings.Contains(text, "1920") || strings.Contains(text, "fhd") || strings.Contains(text, "fullhd") || strings.Contains(text, "full hd") {
		return "1080p"
	}
	if strings.Contains(text, "720") || strings.Contains(text, "1280") {
		return "720p"
	}
	if strings.Contains(text, "480") {
		return "480p"
	}
	if strings.Contains(text, "360") {
		return "360p"
	}
	for _, token := range []string{"cam", "telesync", "screener", "scr", "ts", "sd"} {
		if hasWord(text, token) {
			return "SD"
		}
	}
	return "1080p"
}

func hasWord(text, token string) bool {
	idx := 0
	for {
		i := strings.Index(text[idx:], token)
		if i < 0 {
			return false
		}
		i += idx
		prevOK := i == 0 || !isAlnum(rune(text[i-1]))
		next := i + len(token)
		nextOK := next >= len(text) || !isAlnum(rune(text[next]))
		if prevOK && nextOK {
			return true
		}
		idx = i + len(token)
	}
}

func abs1Movies(href string) string {
	href = strings.TrimSpace(href)
	if href == "" {
		return ""
	}
	u, err := url.Parse(onesMoviesBase + "/")
	if err != nil {
		return href
	}
	ref, err := u.Parse(href)
	if err != nil {
		return href
	}
	return ref.String()
}

func webGet(ctx context.Context, client *http.Client, raw, referer string) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, raw, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", webUserAgent)
	req.Header.Set("Accept", "text/html,application/json,*/*")
	if referer != "" {
		req.Header.Set("Referer", referer)
	}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	if err != nil {
		return "", err
	}
	if resp.StatusCode >= 400 {
		return "", fmt.Errorf("web GET %d", resp.StatusCode)
	}
	return string(body), nil
}

func webPostForm(ctx context.Context, client *http.Client, raw string, fields url.Values, referer string) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, raw, strings.NewReader(fields.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", webUserAgent)
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json,text/plain,*/*")
	req.Header.Set("X-Requested-With", "XMLHttpRequest")
	req.Header.Set("Origin", onesMoviesBase)
	if referer != "" {
		req.Header.Set("Referer", referer)
	}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return "", err
	}
	if resp.StatusCode >= 400 {
		return "", fmt.Errorf("web POST %d", resp.StatusCode)
	}
	return string(body), nil
}
