package subtitles

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

const apiBase = "https://api.opensubtitles.com/api/v1"

// Track is a unified subtitle row for the TV / admin clients.
type Track struct {
	ID              string `json:"id"`
	Source          string `json:"source"` // none | sidecar | embedded | opensubtitles
	Language        string `json:"language,omitempty"`
	Label           string `json:"label"`
	Path            string `json:"path,omitempty"`
	Filename        string `json:"filename,omitempty"`
	Release         string `json:"release,omitempty"`
	FileID          int    `json:"fileId,omitempty"`
	DownloadCount   int    `json:"downloadCount,omitempty"`
	HearingImpaired bool   `json:"hearingImpaired,omitempty"`
	Season          int    `json:"season,omitempty"`
	Episode         int    `json:"episode,omitempty"`
	MatchScore      int    `json:"matchScore,omitempty"`
	MovieHashMatch  bool   `json:"movieHashMatch,omitempty"`
}

type SearchQuery struct {
	ImdbID    string
	TmdbID    string
	Query     string
	FileName  string // video basename — used for release scoring / query hint
	Kind      string // movie | series | episode
	Season    int
	Episode   int
	Languages []string
	MovieHash string
}

type Client struct {
	DataPath string
	FFmpeg   string
	FFprobe  string
	HTTP     *http.Client
}

func NewClient(dataPath string) *Client {
	return &Client{
		DataPath: dataPath,
		FFmpeg:   "ffmpeg",
		FFprobe:  "ffprobe",
		HTTP:     &http.Client{Timeout: 25 * time.Second},
	}
}

// WithAVTools sets ffmpeg/ffprobe binaries used for embedded subtitle extract.
func (c *Client) WithAVTools(ffmpeg, ffprobe string) *Client {
	if c == nil {
		return nil
	}
	if strings.TrimSpace(ffmpeg) != "" {
		c.FFmpeg = ffmpeg
	}
	if strings.TrimSpace(ffprobe) != "" {
		c.FFprobe = ffprobe
	}
	return c
}

func (c *Client) settings() Settings {
	return Load(c.DataPath)
}

func (c *Client) Search(q SearchQuery) ([]Track, error) {
	cfg := c.settings()
	if !cfg.Enabled {
		return nil, fmt.Errorf("OpenSubtitles disabled in settings")
	}
	if strings.TrimSpace(cfg.APIKey) == "" {
		return nil, fmt.Errorf("Add an OpenSubtitles API key in Settings → Subtitles")
	}
	langs := q.Languages
	if len(langs) == 0 {
		langs = cfg.Languages
	}
	params := url.Values{}
	params.Set("order_by", "download_count")
	params.Set("order_direction", "desc")
	params.Set("machine_translated", "exclude")
	params.Set("ai_translated", "include")
	// Keep settings order (et before en) — do not alphabetize; OS accepts any order,
	// and we use this same slice for fair per-language quotas below.
	if len(langs) > 0 {
		params.Set("languages", strings.Join(langs, ","))
	}

	series := isSeriesQuery(q)
	imdb := normalizeImdb(q.ImdbID)
	tmdb := strings.TrimSpace(q.TmdbID)

	if series {
		// Show-level IDs must use parent_* with season/episode. Passing the series
		// imdb_id makes OpenSubtitles treat it as a feature id and ignore S/E —
		// which is why episode subs were so often wrong / out of sync.
		params.Set("type", "episode")
		if imdb != "" {
			params.Set("parent_imdb_id", imdb)
		}
		if tmdb != "" && isDigits(tmdb) {
			params.Set("parent_tmdb_id", tmdb)
		}
		if q.Season > 0 {
			params.Set("season_number", strconv.Itoa(q.Season))
		}
		if q.Episode > 0 {
			params.Set("episode_number", strconv.Itoa(q.Episode))
		}
	} else {
		params.Set("type", "movie")
		if imdb != "" {
			params.Set("imdb_id", imdb)
		}
		if tmdb != "" && isDigits(tmdb) {
			params.Set("tmdb_id", tmdb)
		}
	}

	hash := strings.ToLower(strings.TrimSpace(q.MovieHash))
	if len(hash) == 16 {
		params.Set("moviehash", hash)
	}

	// Free-text query: only when we lack feature IDs, or alongside moviehash (OS docs).
	// Sending the video filename together with imdb_id biases toward popular EN packs
	// and used to hide Estonian (and other) results in the client top-N.
	hasID := imdb != "" || (tmdb != "" && isDigits(tmdb))
	switch {
	case len(hash) == 16:
		if name := strings.TrimSpace(q.FileName); name != "" {
			params.Set("query", strings.ToLower(name))
		} else if title := strings.TrimSpace(q.Query); title != "" {
			params.Set("query", strings.ToLower(title))
		}
	case !hasID:
		query := strings.TrimSpace(q.Query)
		if query == "" {
			query = strings.TrimSpace(q.FileName)
		}
		if query != "" {
			params.Set("query", strings.ToLower(query))
		}
	}

	token, _ := c.login(cfg)
	var payload struct {
		Data []struct {
			Attributes struct {
				Language        string `json:"language"`
				Release         string `json:"release"`
				DownloadCount   int    `json:"download_count"`
				HearingImpaired bool   `json:"hearing_impaired"`
				MovieHashMatch  bool   `json:"moviehash_match"`
				FPS             float64 `json:"fps"`
				FeatureDetails  struct {
					Title         string `json:"title"`
					MovieName     string `json:"movie_name"`
					SeasonNumber  int    `json:"season_number"`
					EpisodeNumber int    `json:"episode_number"`
					ImdbID        int    `json:"imdb_id"`
					ParentImdbID  int    `json:"parent_imdb_id"`
					FeatureType   string `json:"feature_type"`
				} `json:"feature_details"`
				Files []struct {
					FileID   int    `json:"file_id"`
					FileName string `json:"file_name"`
				} `json:"files"`
			} `json:"attributes"`
		} `json:"data"`
	}
	if err := c.doJSON(http.MethodGet, "/subtitles", cfg, token, params, nil, &payload); err != nil {
		return nil, err
	}

	videoHint := strings.TrimSpace(q.FileName)
	if videoHint == "" {
		videoHint = strings.TrimSpace(q.Query)
	}
	titleHint := strings.TrimSpace(q.Query)

	type scored struct {
		track Track
		score int
	}
	var ranked []scored
	for _, row := range payload.Data {
		if len(row.Attributes.Files) == 0 || row.Attributes.Files[0].FileID == 0 {
			continue
		}
		f := row.Attributes.Files[0]
		lang := strings.ToLower(strings.TrimSpace(row.Attributes.Language))
		release := strings.TrimSpace(row.Attributes.Release)
		if release == "" {
			release = strings.TrimSpace(row.Attributes.FeatureDetails.Title)
		}
		if release == "" {
			release = strings.TrimSpace(row.Attributes.FeatureDetails.MovieName)
		}
		fileName := strings.TrimSpace(f.FileName)

		season := row.Attributes.FeatureDetails.SeasonNumber
		episode := row.Attributes.FeatureDetails.EpisodeNumber
		if season == 0 && episode == 0 {
			season, episode = ParseSeasonEpisode(release + " " + fileName)
		}

		if series {
			if q.Season > 0 && season > 0 && season != q.Season {
				continue
			}
			if q.Episode > 0 && episode > 0 && episode != q.Episode {
				continue
			}
			// When OS omits S/E entirely, only keep hash matches — otherwise
			// popular wrong-episode releases leak through on download_count.
			if (q.Season > 0 || q.Episode > 0) && season == 0 && episode == 0 && !row.Attributes.MovieHashMatch {
				continue
			}
		}

		score := 0
		if row.Attributes.MovieHashMatch {
			score += 120
		}
		if series && q.Season > 0 && season == q.Season {
			score += 40
		}
		if series && q.Episode > 0 && episode == q.Episode {
			score += 40
		}
		score += releaseScore(videoHint, release, fileName)
		if titleHint != "" {
			overlap := titleTokenOverlap(titleHint, release+" "+fileName)
			if overlap == 0 {
				// Popular mis-tags (e.g. LoveHappensEst under Shrek) — keep listed
				// but never outrank real title matches.
				score -= 40
			} else {
				score += overlap * 3
			}
		}
		// Mild popularity nudge among otherwise equal matches.
		switch {
		case row.Attributes.DownloadCount >= 5000:
			score += 6
		case row.Attributes.DownloadCount >= 1000:
			score += 4
		case row.Attributes.DownloadCount >= 200:
			score += 2
		}
		if row.Attributes.HearingImpaired {
			score -= 2
		}

		labelBits := []string{}
		if lang != "" {
			labelBits = append(labelBits, strings.ToUpper(lang))
		} else {
			labelBits = append(labelBits, "SUB")
		}
		if series && season > 0 && episode > 0 {
			labelBits = append(labelBits, fmt.Sprintf("S%02dE%02d", season, episode))
		}
		relShort := release
		if relShort == "" {
			relShort = fileName
		}
		if relShort != "" {
			if len(relShort) > 48 {
				relShort = relShort[:48]
			}
			labelBits = append(labelBits, relShort)
		}
		if row.Attributes.MovieHashMatch {
			labelBits = append(labelBits, "hash")
		}
		labelBits = append(labelBits, "OpenSubtitles")

		ranked = append(ranked, scored{
			score: score,
			track: Track{
				ID:              fmt.Sprintf("os:%d", f.FileID),
				Source:          "opensubtitles",
				Language:        lang,
				Label:           strings.Join(labelBits, " · "),
				Filename:        fileName,
				Release:         release,
				FileID:          f.FileID,
				DownloadCount:   row.Attributes.DownloadCount,
				HearingImpaired: row.Attributes.HearingImpaired,
				Season:          season,
				Episode:         episode,
				MatchScore:      score,
				MovieHashMatch:  row.Attributes.MovieHashMatch,
			},
		})
	}

	langRank := map[string]int{}
	for i, lang := range langs {
		langRank[strings.ToLower(strings.TrimSpace(lang))] = i
	}
	sort.SliceStable(ranked, func(i, j int) bool {
		li, lok := langRank[ranked[i].track.Language]
		lj, lok2 := langRank[ranked[j].track.Language]
		if !lok {
			li = 1000
		}
		if !lok2 {
			lj = 1000
		}
		if li != lj {
			return li < lj
		}
		if ranked[i].score != ranked[j].score {
			return ranked[i].score > ranked[j].score
		}
		return ranked[i].track.DownloadCount > ranked[j].track.DownloadCount
	})

	// Few of each preferred language (et, en, …) — never let one language fill the list.
	const perLang = 4
	maxTotal := perLang * len(langs)
	if maxTotal < perLang {
		maxTotal = perLang
	}
	used := map[string]int{}
	out := make([]Track, 0, maxTotal)
	for _, row := range ranked {
		lang := row.track.Language
		if lang == "" {
			lang = "und"
		}
		if used[lang] >= perLang {
			continue
		}
		used[lang]++
		out = append(out, row.track)
		if len(out) >= maxTotal {
			break
		}
	}
	return out, nil
}

func (c *Client) Download(fileID int) (string, error) {
	cfg := c.settings()
	if strings.TrimSpace(cfg.APIKey) == "" {
		return "", fmt.Errorf("OpenSubtitles API key required")
	}
	if err := os.MkdirAll(CacheDir(c.DataPath), 0o755); err != nil {
		return "", err
	}
	prefix := fmt.Sprintf("%d_", fileID)
	if entries, err := os.ReadDir(CacheDir(c.DataPath)); err == nil {
		for _, e := range entries {
			if !e.IsDir() && strings.HasPrefix(e.Name(), prefix) {
				path := filepath.Join(CacheDir(c.DataPath), e.Name())
				if st, err := os.Stat(path); err == nil && st.Size() > 0 {
					return path, nil
				}
			}
		}
	}
	token, err := c.login(cfg)
	if err != nil {
		return "", err
	}
	if token == "" {
		return "", fmt.Errorf("OpenSubtitles username/password required to download (API key alone is not enough)")
	}
	var payload struct {
		Link     string `json:"link"`
		FileName string `json:"file_name"`
	}
	body := map[string]any{"file_id": fileID}
	if err := c.doJSON(http.MethodPost, "/download", cfg, token, nil, body, &payload); err != nil {
		return "", err
	}
	link := strings.TrimSpace(payload.Link)
	if link == "" {
		return "", fmt.Errorf("OpenSubtitles download link missing")
	}
	fileName := strings.TrimSpace(payload.FileName)
	if fileName == "" {
		fileName = fmt.Sprintf("%d.srt", fileID)
	}
	safe := sanitizeName(fileName)
	dest := filepath.Join(CacheDir(c.DataPath), fmt.Sprintf("%d_%s", fileID, safe))
	req, err := http.NewRequest(http.MethodGet, link, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", cfg.UserAgent)
	req.Header.Set("X-User-Agent", cfg.UserAgent)
	req.Header.Set("Api-Key", cfg.APIKey)
	req.Header.Set("Authorization", "Bearer "+token)
	res, err := c.HTTP.Do(req)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()
	if res.StatusCode >= 300 {
		b, _ := io.ReadAll(io.LimitReader(res.Body, 300))
		return "", fmt.Errorf("download HTTP %d: %s", res.StatusCode, string(b))
	}
	data, err := io.ReadAll(res.Body)
	if err != nil {
		return "", err
	}
	if err := os.WriteFile(dest, data, 0o644); err != nil {
		return "", err
	}
	return dest, nil
}

func (c *Client) ResolveFile(id string) (string, string, error) {
	id = strings.TrimSpace(id)
	switch {
	case strings.HasPrefix(id, "os:"):
		fileID, err := strconv.Atoi(strings.TrimPrefix(id, "os:"))
		if err != nil || fileID <= 0 {
			return "", "", fmt.Errorf("invalid OpenSubtitles id")
		}
		path, err := c.Download(fileID)
		if err != nil {
			return "", "", err
		}
		return path, contentTypeFor(path), nil
	case strings.HasPrefix(id, "file:"):
		path := strings.TrimPrefix(id, "file:")
		if !filepath.IsAbs(path) {
			return "", "", fmt.Errorf("subtitle path must be absolute")
		}
		if st, err := os.Stat(path); err != nil || st.IsDir() {
			return "", "", fmt.Errorf("subtitle file not found")
		}
		return path, contentTypeFor(path), nil
	case strings.HasPrefix(id, "emb:"):
		path, err := ExtractEmbedded(c.FFmpeg, c.DataPath, id)
		if err != nil {
			return "", "", err
		}
		return path, contentTypeFor(path), nil
	default:
		return "", "", fmt.Errorf("unknown subtitle id")
	}
}

func (c *Client) login(cfg Settings) (string, error) {
	if strings.TrimSpace(cfg.Username) == "" || strings.TrimSpace(cfg.Password) == "" {
		return "", nil
	}
	var payload struct {
		Token string `json:"token"`
	}
	body := map[string]any{"username": cfg.Username, "password": cfg.Password}
	if err := c.doJSON(http.MethodPost, "/login", cfg, "", nil, body, &payload); err != nil {
		return "", err
	}
	token := strings.TrimSpace(payload.Token)
	if token == "" {
		return "", fmt.Errorf("OpenSubtitles login failed")
	}
	return token, nil
}

func (c *Client) doJSON(method, path string, cfg Settings, token string, params url.Values, body any, out any) error {
	u := apiBase + path
	if len(params) > 0 {
		// Stable query order like the Python client.
		keys := make([]string, 0, len(params))
		for k := range params {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		parts := make([]string, 0, len(keys))
		for _, k := range keys {
			for _, v := range params[k] {
				parts = append(parts, url.QueryEscape(k)+"="+url.QueryEscape(v))
			}
		}
		u += "?" + strings.Join(parts, "&")
	}
	var bodyReader io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return err
		}
		bodyReader = strings.NewReader(string(b))
	}
	req, err := http.NewRequest(method, u, bodyReader)
	if err != nil {
		return err
	}
	req.Header.Set("Api-Key", cfg.APIKey)
	req.Header.Set("User-Agent", cfg.UserAgent)
	req.Header.Set("X-User-Agent", cfg.UserAgent)
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	res, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(res.Body, 2<<20))
	if err != nil {
		return err
	}
	if res.StatusCode >= 300 {
		detail := string(raw)
		if len(detail) > 300 {
			detail = detail[:300]
		}
		hint := ""
		if res.StatusCode == 403 && strings.Contains(strings.ToLower(detail), "user-agent") {
			hint = fmt.Sprintf(" — set User-Agent to the Application Name from your OpenSubtitles API consumer (sending %q)", cfg.UserAgent)
		}
		return fmt.Errorf("HTTP %d: %s%s", res.StatusCode, detail, hint)
	}
	if out == nil || len(raw) == 0 {
		return nil
	}
	return json.Unmarshal(raw, out)
}

func normalizeImdb(value string) string {
	text := strings.ToLower(strings.TrimSpace(value))
	re := regexp.MustCompile(`tt(\d+)`)
	digits := ""
	if m := re.FindStringSubmatch(text); len(m) == 2 {
		digits = m[1]
	} else if isDigits(text) {
		digits = text
	}
	if digits == "" {
		return ""
	}
	// OpenSubtitles docs: strip leading zeros from the numeric imdb id.
	trimmed := strings.TrimLeft(digits, "0")
	if trimmed == "" {
		return "0"
	}
	return trimmed
}

func isDigits(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

var unsafeName = regexp.MustCompile(`[^\w.\-]+`)

func sanitizeName(name string) string {
	s := unsafeName.ReplaceAllString(name, "_")
	if len(s) > 120 {
		s = s[:120]
	}
	if s == "" {
		s = "subtitle.srt"
	}
	return s
}

func contentTypeFor(path string) string {
	switch strings.ToLower(filepath.Ext(path)) {
	case ".vtt":
		return "text/vtt"
	case ".ass", ".ssa":
		return "text/x-ssa"
	default:
		return "application/x-subrip"
	}
}
