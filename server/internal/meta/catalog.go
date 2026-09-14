package meta

import (
	"context"
	"fmt"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

type CatalogItem struct {
	ID             string       `json:"id"`
	Kind           string       `json:"kind"`
	Title          string       `json:"title"`
	Year           int          `json:"year,omitempty"`
	Plot           string       `json:"plot,omitempty"`
	PosterURL      string       `json:"posterUrl,omitempty"`
	BackdropURL    string       `json:"backdropUrl,omitempty"`
	LogoURL        string       `json:"logoUrl,omitempty"`
	ImdbID         string       `json:"imdbId,omitempty"`
	MediaID        string       `json:"mediaId,omitempty"`
	InLibrary      bool         `json:"inLibrary"`
	Rating         float64      `json:"rating,omitempty"`
	Genres         []string     `json:"genres,omitempty"`
	ShowTitle      string       `json:"showTitle,omitempty"`
	Season         int          `json:"season,omitempty"`
	Episode        int          `json:"episode,omitempty"`
	ReleasePhase   string       `json:"releasePhase,omitempty"`
	ReleaseDate    string       `json:"releaseDate,omitempty"`
	TMDBID         int          `json:"tmdbId,omitempty"`
	Cast           []CastMember `json:"cast,omitempty"`
	Director       *CastMember  `json:"director,omitempty"`
	RuntimeMinutes int          `json:"runtimeMinutes,omitempty"`
	Certification  string       `json:"certification,omitempty"`
	Country        string       `json:"country,omitempty"`
	GenreIDs       []int        `json:"genreIds,omitempty"`
	PositionMs     int64        `json:"positionMs,omitempty"`
	DurationMs     int64        `json:"durationMs,omitempty"`
	EpisodeCount   int          `json:"episodeCount,omitempty"`
	MatchPercent   int          `json:"matchPercent,omitempty"`
}

type CastMember struct {
	TMDBID     int    `json:"tmdbId"`
	Name       string `json:"name"`
	Character  string `json:"character,omitempty"`
	ProfileURL string `json:"profileUrl,omitempty"`
}

type Person struct {
	TMDBID             int           `json:"tmdbId"`
	Name               string        `json:"name"`
	ProfileURL         string        `json:"profileUrl,omitempty"`
	KnownForDepartment string        `json:"knownForDepartment,omitempty"`
	Biography          string        `json:"biography,omitempty"`
	Birthday           string        `json:"birthday,omitempty"`
	PlaceOfBirth       string        `json:"placeOfBirth,omitempty"`
	Credits            []CatalogItem `json:"credits,omitempty"`
}

type SearchResult struct {
	Movies []CatalogItem `json:"movies"`
	Series []CatalogItem `json:"series"`
	People []Person      `json:"people"`
}

type catalogCache struct {
	mu      sync.Mutex
	movies  []CatalogItem
	series  []CatalogItem
	fetched time.Time
}

func (c *catalogCache) get() (movies, series []CatalogItem, ok bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if time.Since(c.fetched) > 15*time.Minute || (len(c.movies) == 0 && len(c.series) == 0) {
		return nil, nil, false
	}
	return append([]CatalogItem(nil), c.movies...), append([]CatalogItem(nil), c.series...), true
}

func (c *catalogCache) set(movies, series []CatalogItem) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.movies = movies
	c.series = series
	c.fetched = time.Now()
}

var homeCatalog = &catalogCache{}

func (e *Enricher) HomeCatalog(ctx context.Context) (movies, series []CatalogItem, err error) {
	if m, s, ok := homeCatalog.get(); ok {
		return m, s, nil
	}
	if e.tmdbEnabled() {
		movies, _ = e.tmdbTrending(ctx, "movie")
		series, _ = e.tmdbTrending(ctx, "tv")
	}
	if len(movies) == 0 {
		movies, _ = e.cinemetaTop(ctx, "movie")
	}
	if len(series) == 0 {
		series, _ = e.cinemetaTop(ctx, "series")
	}
	homeCatalog.set(movies, series)
	return movies, series, nil
}

func (e *Enricher) CatalogShow(ctx context.Context, imdb string) (CatalogItem, []CatalogItem, error) {
	imdb = strings.TrimSpace(imdb)
	if cover, eps, fetched, ok := e.peekShow(imdb); ok {
		if catalogFresh(fetched, e.metaTTL()) {
			return cover, eps, nil
		}
		go func() {
			_, _, _ = e.fetchCatalogShow(context.Background(), imdb)
		}()
		return cover, eps, nil
	}
	return e.fetchCatalogShow(ctx, imdb)
}

func (e *Enricher) fetchCatalogShow(ctx context.Context, imdb string) (CatalogItem, []CatalogItem, error) {
	imdb = strings.TrimSpace(imdb)
	info, _ := e.cinemetaByIMDB(ctx, "episode", imdb)
	cover := CatalogItem{
		ID:          "catalog:" + imdb,
		Kind:        "series",
		Title:       imdb,
		Plot:        info.Plot,
		PosterURL:   info.PosterURL,
		BackdropURL: info.BackdropURL,
		ImdbID:      imdb,
		Rating:      info.Rating,
		Genres:      info.Genres,
		Year:        info.Year,
	}
	var wrap struct {
		Meta struct {
			cinemetaMeta
			Videos []struct {
				ID        string `json:"id"`
				Title     string `json:"title"`
				Name      string `json:"name"`
				Season    int    `json:"season"`
				Episode   int    `json:"episode"`
				Released  string `json:"released"`
				Thumbnail string `json:"thumbnail"`
			} `json:"videos"`
		} `json:"meta"`
	}
	u := "https://v3-cinemeta.strem.io/meta/series/" + url.PathEscape(imdb) + ".json"
	if err := e.getJSON(ctx, u, &wrap); err != nil {
		return cover, nil, err
	}
	if wrap.Meta.Name != "" {
		cover.Title = wrap.Meta.Name
	}
	if wrap.Meta.Description != "" {
		cover.Plot = wrap.Meta.Description
	}
	if wrap.Meta.Poster != "" {
		cover.PosterURL = wrap.Meta.Poster
	}
	if wrap.Meta.Background != "" {
		cover.BackdropURL = wrap.Meta.Background
	}
	eps := []CatalogItem{}
	for _, v := range wrap.Meta.Videos {
		if v.Season < 0 || v.Episode <= 0 {
			continue
		}
		title := strings.TrimSpace(v.Title)
		if title == "" {
			title = strings.TrimSpace(v.Name)
		}
		if title == "" {
			title = fmt.Sprintf("S%02dE%02d", v.Season, v.Episode)
		}
		still := episodeArtURL(v.Thumbnail, cover.PosterURL, cover.BackdropURL)
		eps = append(eps, CatalogItem{
			ID:          fmt.Sprintf("catalog:%s:%d:%d", imdb, v.Season, v.Episode),
			Kind:        "episode",
			Title:       title,
			ShowTitle:   cover.Title,
			Season:      v.Season,
			Episode:     v.Episode,
			Year:        yearFromRelease(v.Released),
			PosterURL:   still,
			BackdropURL: still,
			ImdbID:      imdb,
		})
	}
	sort.Slice(eps, func(i, j int) bool {
		if eps[i].Season != eps[j].Season {
			return eps[i].Season < eps[j].Season
		}
		return eps[i].Episode < eps[j].Episode
	})
	cover.EpisodeCount = len(eps)
	e.storeShow(imdb, cover, eps)
	return cover, eps, nil
}

func episodeArtURL(thumb, seriesPoster, seriesBackdrop string) string {
	thumb = strings.TrimSpace(thumb)
	if thumb == "" || thumb == seriesPoster || thumb == seriesBackdrop {
		return ""
	}
	return thumb
}

type episodeStill struct {
	Title   string
	Plot    string
	Still   string
	Runtime int
	Year    int
}

func applyEpisodeStills(eps []CatalogItem, meta map[[2]int]episodeStill) []CatalogItem {
	if len(meta) == 0 {
		return eps
	}
	out := append([]CatalogItem(nil), eps...)
	for i := range out {
		m, ok := meta[[2]int{out[i].Season, out[i].Episode}]
		if !ok {
			continue
		}
		if m.Still != "" {
			out[i].PosterURL = m.Still
			out[i].BackdropURL = m.Still
		}
		if m.Title != "" {
			out[i].Title = m.Title
		}
		if m.Plot != "" {
			out[i].Plot = m.Plot
		}
		if m.Runtime > 0 {
			out[i].RuntimeMinutes = m.Runtime
		}
		if m.Year > 0 && out[i].Year == 0 {
			out[i].Year = m.Year
		}
	}
	return out
}

type Genre struct {
	ID   int    `json:"id"`
	Name string `json:"name"`
}

func (e *Enricher) CatalogGenres(ctx context.Context, kind string) ([]Genre, error) {
	if !e.tmdbEnabled() {
		return nil, fmt.Errorf("TMDB is not configured")
	}
	path := "movie"
	if kind == "series" || kind == "tv" || kind == "episode" {
		path = "tv"
	}
	var wrap struct {
		Genres []Genre `json:"genres"`
	}
	u := fmt.Sprintf("https://api.themoviedb.org/3/genre/%s/list?api_key=%s", path, url.QueryEscape(e.tmdbKey))
	if err := e.getJSON(ctx, u, &wrap); err != nil {
		return nil, err
	}
	return wrap.Genres, nil
}

func (e *Enricher) tmdbTrending(ctx context.Context, media string) ([]CatalogItem, error) {
	var wrap struct {
		Results []tmdbMovie `json:"results"`
	}
	u := fmt.Sprintf("https://api.themoviedb.org/3/trending/%s/day?api_key=%s", media, url.QueryEscape(e.tmdbKey))
	if err := e.getJSON(ctx, u, &wrap); err != nil {
		return nil, err
	}
	return e.catalogFromTMDBList(ctx, media, wrap.Results), nil
}

func (e *Enricher) catalogFromTMDBList(ctx context.Context, media string, rows []tmdbMovie) []CatalogItem {
	kind := "movie"
	if media == "tv" {
		kind = "series"
	}
	type pending struct {
		item    CatalogItem
		primary string
		status  string
	}
	work := []pending{}
	for _, row := range rows {
		if len(work) >= 20 {
			break
		}
		item := catalogFromTMDB(row, kind)
		item.TMDBID = row.ID
		if item.ImdbID == "" {
			item.ImdbID = e.tmdbExternalIMDB(ctx, media, row.ID)
		}
		if item.ImdbID == "" {
			continue
		}
		item.ID = "catalog:" + item.ImdbID
		if d := strings.TrimSpace(row.ReleaseDate); len(d) >= 10 {
			item.ReleaseDate = d[:10]
		}
		work = append(work, pending{item: item, primary: row.ReleaseDate, status: row.Status})
	}
	if kind == "movie" {
		var wg sync.WaitGroup
		for i := range work {
			if work[i].item.TMDBID == 0 {
				continue
			}
			wg.Add(1)
			go func(i int) {
				defer wg.Done()
				phase, date := e.tmdbMovieReleaseMeta(ctx, work[i].item.TMDBID, work[i].primary, work[i].status)
				work[i].item.ReleasePhase = phase
				if date != "" {
					work[i].item.ReleaseDate = date
				}
			}(i)
		}
		wg.Wait()
	}
	if kind == "series" {
		var wg sync.WaitGroup
		for i := range work {
			if work[i].item.TMDBID == 0 {
				continue
			}
			wg.Add(1)
			go func(i int) {
				defer wg.Done()
				if n := e.tmdbEpisodeCount(ctx, work[i].item.TMDBID); n > 0 {
					work[i].item.EpisodeCount = n
				}
			}(i)
		}
		wg.Wait()
	}
	out := make([]CatalogItem, 0, len(work))
	for _, row := range work {
		out = append(out, row.item)
	}
	return out
}

func (e *Enricher) tmdbExternalIMDB(ctx context.Context, media string, id int) string {
	if id == 0 {
		return ""
	}
	path := "movie"
	if media == "tv" {
		path = "tv"
	}
	var wrap struct {
		IMDBID string `json:"imdb_id"`
	}
	u := fmt.Sprintf("https://api.themoviedb.org/3/%s/%d/external_ids?api_key=%s", path, id, url.QueryEscape(e.tmdbKey))
	if e.getJSON(ctx, u, &wrap) != nil {
		return ""
	}
	if strings.HasPrefix(wrap.IMDBID, "tt") {
		return wrap.IMDBID
	}
	return ""
}

func catalogFromTMDB(row tmdbMovie, kind string) CatalogItem {
	title := strings.TrimSpace(row.Title)
	if title == "" {
		title = strings.TrimSpace(row.Name)
	}
	year := yearFromRelease(row.ReleaseDate)
	if year == 0 {
		year = yearFromRelease(row.FirstAirDate)
	}
	item := CatalogItem{
		Kind:     kind,
		Title:    title,
		Year:     year,
		Plot:     strings.TrimSpace(row.Overview),
		ImdbID:   row.IMDBID,
		Rating:   row.VoteAverage,
		TMDBID:   row.ID,
		GenreIDs: append([]int(nil), row.GenreIDs...),
	}
	if row.PosterPath != "" {
		item.PosterURL = "https://image.tmdb.org/t/p/w500" + row.PosterPath
	}
	if row.BackdropPath != "" {
		item.BackdropURL = "https://image.tmdb.org/t/p/original" + row.BackdropPath
	}
	if d := strings.TrimSpace(row.ReleaseDate); len(d) >= 10 {
		item.ReleaseDate = d[:10]
	} else if d := strings.TrimSpace(row.FirstAirDate); len(d) >= 10 {
		item.ReleaseDate = d[:10]
	}
	if item.PosterURL == "" && item.ImdbID != "" {
		item.PosterURL = metahubPoster(item.ImdbID)
	}
	if item.BackdropURL == "" && item.ImdbID != "" {
		item.BackdropURL = metahubBackdrop(item.ImdbID)
	}
	applyOverviewMeta(&item, row)
	return item
}

func (e *Enricher) cinemetaTop(ctx context.Context, typ string) ([]CatalogItem, error) {
	var wrap cinemetaSearch
	u := "https://v3-cinemeta.strem.io/catalog/" + typ + "/top.json"
	if err := e.getJSON(ctx, u, &wrap); err != nil {
		return nil, err
	}
	kind := "movie"
	if typ == "series" {
		kind = "series"
	}
	out := []CatalogItem{}
	for _, m := range wrap.Metas {
		if !strings.HasPrefix(m.ID, "tt") {
			continue
		}
		if len(out) >= 20 {
			break
		}
		item := CatalogItem{
			ID:          "catalog:" + m.ID,
			Kind:        kind,
			Title:       m.Name,
			Year:        yearFromRelease(m.ReleaseInfo),
			Plot:        m.Description,
			PosterURL:   m.Poster,
			BackdropURL: m.Background,
			ImdbID:      m.ID,
			Genres:      m.Genres,
		}
		if item.PosterURL == "" {
			item.PosterURL = metahubPoster(m.ID)
		}
		if item.BackdropURL == "" {
			item.BackdropURL = metahubBackdrop(m.ID)
		}
		if r, err := strconv.ParseFloat(m.IMDBRating, 64); err == nil {
			item.Rating = r
		}
		out = append(out, item)
	}
	return out, nil
}
