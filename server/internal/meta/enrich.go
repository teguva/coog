package meta

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"coog/internal/store"
)

type Info struct {
	ImdbID         string       `json:"imdbId,omitempty"`
	Tagline        string       `json:"tagline,omitempty"`
	Plot           string       `json:"plot,omitempty"`
	Genres         []string     `json:"genres,omitempty"`
	Rating         float64      `json:"rating,omitempty"`
	Year           int          `json:"year,omitempty"`
	PosterURL      string       `json:"posterUrl,omitempty"`
	BackdropURL    string       `json:"backdropUrl,omitempty"`
	LogoURL        string       `json:"logoUrl,omitempty"`
	Source         string       `json:"source,omitempty"`
	MatchStatus    string       `json:"matchStatus,omitempty"` // matched|unmatched|ignored|suggested
	RuntimeMinutes int          `json:"runtimeMinutes,omitempty"`
	Certification  string       `json:"certification,omitempty"`
	Country        string       `json:"country,omitempty"`
	TMDBID         int          `json:"tmdbId,omitempty"`
	Cast           []CastMember `json:"cast,omitempty"`
	Director       *CastMember  `json:"director,omitempty"`
	EpisodeCount   int          `json:"episodeCount,omitempty"`
}

type Enricher struct {
	dir      string
	tmdbKey  string
	client   *http.Client
	mu       sync.Mutex
	mem      map[string]Info
	inflight map[string]chan struct{}
	ttl      time.Duration
	// backdropDisplayMax caps size=display long edge (default 1920 / 1080p).
	backdropDisplayMax atomic.Int32
}

func New(dataPath, tmdbKey string) *Enricher {
	e := &Enricher{
		dir:      filepath.Join(dataPath, "meta"),
		tmdbKey:  tmdbKey,
		client:   &http.Client{Timeout: 10 * time.Second},
		mem:      map[string]Info{},
		inflight: map[string]chan struct{}{},
	}
	e.SetBackdropDisplayMax(1920)
	return e
}

// SetBackdropDisplayMax sets the long-edge cap for backdrop size=display.
func (e *Enricher) SetBackdropDisplayMax(maxEdge int) {
	if maxEdge < 640 {
		maxEdge = 1920
	}
	e.backdropDisplayMax.Store(int32(maxEdge))
}

func (e *Enricher) BackdropDisplayMax() int {
	v := int(e.backdropDisplayMax.Load())
	if v <= 0 {
		return 1920
	}
	return v
}

func (e *Enricher) Peek(id string) (Info, bool) {
	e.mu.Lock()
	if info, ok := e.mem[id]; ok {
		e.mu.Unlock()
		return info, true
	}
	e.mu.Unlock()
	info, ok := e.readDisk(id)
	if ok {
		e.mu.Lock()
		e.mem[id] = info
		e.mu.Unlock()
	}
	return info, ok
}

func (e *Enricher) Ensure(ctx context.Context, item store.MediaItem) Info {
	if sc, ok := ReadSidecar(item.Path); ok && sc.MatchStatus == "ignored" {
		info := infoFromSidecar(sc)
		info.Source = "ignored"
		e.store(item.ID, info)
		return info
	}
	if info, ok := e.Peek(item.ID); ok && e.cacheStillValid(item, info) {
		e.persistIdentity(item, info)
		if IdentityConfirmed(item.Path, info) {
			needTMDB := (info.Tagline == "" && !strings.Contains(info.Source, "tmdb")) ||
				((item.Kind == "episode" || item.Kind == "series") && info.EpisodeCount == 0) ||
				((item.Kind == "episode" || item.Kind == "series") && len(info.Cast) == 0)
			if e.tmdbEnabled() && needTMDB {
				title := item.Title
				if item.Kind == "episode" && item.ShowTitle != "" {
					title = item.ShowTitle
				}
				info = e.overlayTMDB(ctx, item.Kind, info, title, item.Year)
				e.store(item.ID, info)
			}
			e.persistLocalArt(ctx, item, &info)
		}
		return info
	}
	e.mu.Lock()
	if ch, ok := e.inflight[item.ID]; ok {
		e.mu.Unlock()
		select {
		case <-ch:
		case <-ctx.Done():
			info, _ := e.Peek(item.ID)
			return info
		}
		info, _ := e.Peek(item.ID)
		return info
	}
	done := make(chan struct{})
	e.inflight[item.ID] = done
	e.mu.Unlock()
	prev, hadPrev := e.Peek(item.ID)
	info := e.resolve(ctx, item)
	if info.MatchStatus != "matched" && hadPrev && (prev.ImdbID != "" || prev.MatchStatus == "matched") {
		e.invalidateArtwork(item.ID)
	}
	e.persistIdentity(item, info)
	if info.MatchStatus == "matched" {
		e.persistLocalArt(ctx, item, &info)
	}
	e.store(item.ID, info)
	e.mu.Lock()
	delete(e.inflight, item.ID)
	e.mu.Unlock()
	close(done)
	return info
}

func (e *Enricher) Warm(ctx context.Context, items []store.MediaItem) {
	go func() {
		for _, item := range items {
			select {
			case <-ctx.Done():
				return
			default:
			}
			e.Ensure(ctx, item)
		}
	}()
}

func (e *Enricher) cacheStillValid(item store.MediaItem, info Info) bool {
	if sc, ok := ReadSidecar(item.Path); ok && sc.MatchStatus == "ignored" {
		return true
	}
	explicit := FindIMDB(item.Path, item.Title, item.Year)
	status := strings.ToLower(strings.TrimSpace(info.MatchStatus))
	if status == "unmatched" || status == "ignored" || info.Source == "unmatched" || info.Source == "ignored" || info.Source == "none" {
		if explicit != "" {
			return false
		}
		return cacheAge(e.cachePath(item.ID)) < 24*time.Hour
	}
	if info.ImdbID == "" {
		return cacheAge(e.cachePath(item.ID)) < 24*time.Hour
	}
	if explicit == "" {
		return false
	}
	return strings.EqualFold(explicit, info.ImdbID)
}

func (e *Enricher) resolve(ctx context.Context, item store.MediaItem) Info {
	title := item.Title
	if item.Kind == "episode" && item.ShowTitle != "" {
		title = item.ShowTitle
	}

	if sc, ok := ReadSidecar(item.Path); ok {
		switch sc.MatchStatus {
		case "ignored":
			info := infoFromSidecar(sc)
			info.Source = "ignored"
			return info
		case "suggested":
			info := infoFromSidecar(sc)
			info.ImdbID = ""
			info.PosterURL = ""
			info.BackdropURL = ""
			info.LogoURL = ""
			info.MatchStatus = "suggested"
			info.Source = "sidecar"
			return info
		}
	}

	imdb := FindIMDB(item.Path, title, item.Year)
	if imdb == "" {
		return Info{Source: "none", MatchStatus: "unmatched"}
	}

	info, err := e.cinemetaByIMDB(ctx, item.Kind, imdb)
	if err != nil {
		slog.Debug("cinemeta imdb", "id", item.ID, "imdb", imdb, "err", err)
		info = Info{
			ImdbID:      imdb,
			PosterURL:   metahubPoster(imdb),
			BackdropURL: metahubBackdrop(imdb),
			LogoURL:     metahubLogo(imdb),
			Source:      "metahub",
			MatchStatus: "matched",
		}
	} else {
		info.MatchStatus = "matched"
	}
	if sc, ok := ReadSidecar(item.Path); ok && sc.MatchStatus == "matched" {
		if sc.Plot != "" {
			info.Plot = sc.Plot
		}
		if sc.Tagline != "" {
			info.Tagline = sc.Tagline
		}
	}
	info = e.overlayTMDB(ctx, item.Kind, info, title, item.Year)
	info.MatchStatus = "matched"
	info.ImdbID = imdb
	if info.LogoURL == "" {
		info.LogoURL = metahubLogo(imdb)
	}
	return info
}

func (e *Enricher) store(id string, info Info) {
	e.mu.Lock()
	e.mem[id] = info
	e.mu.Unlock()
	if err := os.MkdirAll(e.dir, 0o755); err != nil {
		return
	}
	b, err := json.MarshalIndent(info, "", "  ")
	if err != nil {
		return
	}
	_ = os.WriteFile(e.cachePath(id), b, 0o644)
}

func (e *Enricher) readDisk(id string) (Info, bool) {
	b, err := os.ReadFile(e.cachePath(id))
	if err != nil {
		return Info{}, false
	}
	var info Info
	if json.Unmarshal(b, &info) != nil {
		return Info{}, false
	}
	return info, true
}

func (e *Enricher) Drop(id string) {
	if id == "" {
		return
	}
	e.mu.Lock()
	delete(e.mem, id)
	e.mu.Unlock()
	_ = os.Remove(e.cachePath(id))
	e.invalidateArtwork(id)
}

func (e *Enricher) cachePath(id string) string {
	return filepath.Join(e.dir, id+".json")
}

func cacheAge(path string) time.Duration {
	st, err := os.Stat(path)
	if err != nil {
		return 0
	}
	return time.Since(st.ModTime())
}

func (e *Enricher) getJSON(ctx context.Context, rawURL string, dest any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "coog/0.1")
	resp, err := e.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		return errStatus(resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	if err != nil {
		return err
	}
	return json.Unmarshal(body, dest)
}

func (e *Enricher) FetchFile(ctx context.Context, rawURL, dest string) error {
	if rawURL == "" {
		return errStatus(http.StatusNotFound)
	}
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", "coog/0.1")
	resp, err := e.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		return errStatus(resp.StatusCode)
	}
	tmp := dest + ".tmp"
	f, err := os.Create(tmp)
	if err != nil {
		return err
	}
	_, err = io.Copy(f, io.LimitReader(resp.Body, 12<<20))
	closeErr := f.Close()
	if err != nil {
		_ = os.Remove(tmp)
		return err
	}
	if closeErr != nil {
		_ = os.Remove(tmp)
		return closeErr
	}
	return os.Rename(tmp, dest)
}

type statusErr int

func (e statusErr) Error() string { return http.StatusText(int(e)) }

func errStatus(code int) error { return statusErr(code) }
