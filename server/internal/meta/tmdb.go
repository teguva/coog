package meta

import (
	"context"
	"fmt"
	"math"
	"net/url"
	"sort"
	"strings"
	"time"
)

type tmdbFind struct {
	MovieResults []tmdbMovie `json:"movie_results"`
	TVResults    []tmdbMovie `json:"tv_results"`
}

type tmdbSearch struct {
	Results []tmdbMovie `json:"results"`
}

type tmdbMovie struct {
	ID           int     `json:"id"`
	Title        string  `json:"title"`
	Name         string  `json:"name"`
	Overview     string  `json:"overview"`
	Tagline      string  `json:"tagline"`
	ReleaseDate  string  `json:"release_date"`
	FirstAirDate string  `json:"first_air_date"`
	PosterPath   string  `json:"poster_path"`
	BackdropPath string  `json:"backdrop_path"`
	VoteAverage  float64 `json:"vote_average"`
	Popularity   float64 `json:"popularity"`
	IMDBID       string  `json:"imdb_id"`
	Status       string  `json:"status"`
	MediaType    string  `json:"media_type"`
	GenreIDs     []int   `json:"genre_ids"`
	ProfilePath  string  `json:"profile_path"`
	KnownForDept string  `json:"known_for_department"`
	Genres       []struct {
		ID   int    `json:"id"`
		Name string `json:"name"`
	} `json:"genres"`
	Credits             tmdbCredits          `json:"credits"`
	AggregateCredits    tmdbAggregateCredits `json:"aggregate_credits"`
	CreatedBy           []tmdbCreator        `json:"created_by"`
	ReleaseDates        tmdbReleaseDates     `json:"release_dates"`
	ContentRatings      tmdbContentRatings   `json:"content_ratings"`
	Runtime             int                  `json:"runtime"`
	EpisodeRunTime      []int                `json:"episode_run_time"`
	NumberOfEpisodes    int                  `json:"number_of_episodes"`
	OriginCountry       []string             `json:"origin_country"`
	Job                 string               `json:"job"`
	Department          string               `json:"department"`
	ProductionCountries []struct {
		ISO31661 string `json:"iso_3166_1"`
		Name     string `json:"name"`
	} `json:"production_countries"`
}

type tmdbCreator struct {
	ID          int    `json:"id"`
	Name        string `json:"name"`
	ProfilePath string `json:"profile_path"`
}

type tmdbContentRatings struct {
	Results []struct {
		ISO31661 string `json:"iso_3166_1"`
		Rating   string `json:"rating"`
	} `json:"results"`
}

type tmdbCredits struct {
	Cast []tmdbCast `json:"cast"`
	Crew []tmdbCrew `json:"crew"`
}

type tmdbAggregateCredits struct {
	Cast []tmdbAggregateCast `json:"cast"`
	Crew []tmdbCrew          `json:"crew"`
}

type tmdbAggregateCast struct {
	ID                int     `json:"id"`
	Name              string  `json:"name"`
	ProfilePath       string  `json:"profile_path"`
	Order             int     `json:"order"`
	Popularity        float64 `json:"popularity"`
	TotalEpisodeCount int     `json:"total_episode_count"`
	Roles             []struct {
		Character    string `json:"character"`
		EpisodeCount int    `json:"episode_count"`
	} `json:"roles"`
}

type tmdbCrew struct {
	ID          int    `json:"id"`
	Name        string `json:"name"`
	Job         string `json:"job"`
	Department  string `json:"department"`
	ProfilePath string `json:"profile_path"`
}

type tmdbCast struct {
	ID          int     `json:"id"`
	Name        string  `json:"name"`
	Character   string  `json:"character"`
	ProfilePath string  `json:"profile_path"`
	Order       int     `json:"order"`
	Popularity  float64 `json:"popularity"`
}

func (e *Enricher) tmdbEnabled() bool {
	return strings.TrimSpace(e.tmdbKey) != ""
}

func (e *Enricher) TMDBEnabled() bool {
	return e.tmdbEnabled()
}

func (e *Enricher) overlayTMDB(ctx context.Context, kind string, info Info, title string, year int) Info {
	if !e.tmdbEnabled() {
		return info
	}
	// Only enrich by confirmed IMDB id. Title search must never invent a match.
	if info.ImdbID == "" {
		return info
	}
	movie, err := e.tmdbFind(ctx, kind, info.ImdbID)
	if err != nil || movie.ID == 0 {
		return info
	}
	_ = title
	_ = year
	// TMDB reuses numeric ids across movie/tv (e.g. movie/105 = BTTF, tv/105 = SATC).
	// Always detail with the media type from find, not the file's guessed kind.
	apiKind := tmdbAPIKind(kind, movie)
	detail, err := e.tmdbDetail(ctx, apiKind, movie.ID)
	if err == nil && detail.ID != 0 {
		movie = detail
		if movie.MediaType == "" {
			if apiKind == "series" || apiKind == "episode" {
				movie.MediaType = "tv"
			} else {
				movie.MediaType = "movie"
			}
		}
	}
	if movie.Tagline != "" {
		info.Tagline = strings.TrimSpace(movie.Tagline)
	}
	if info.Plot == "" {
		info.Plot = strings.TrimSpace(movie.Overview)
	}
	if movie.VoteAverage > 0 && info.Rating <= 0 {
		info.Rating = movie.VoteAverage
	}
	if y := yearFromRelease(movie.ReleaseDate); y > 0 && info.Year == 0 {
		info.Year = y
	}
	if y := yearFromRelease(movie.FirstAirDate); y > 0 && info.Year == 0 {
		info.Year = y
	}
	if movie.IMDBID != "" && info.ImdbID == "" {
		info.ImdbID = movie.IMDBID
	}
	if len(movie.Genres) > 0 && len(info.Genres) == 0 {
		for _, g := range movie.Genres {
			if g.Name != "" {
				info.Genres = append(info.Genres, g.Name)
			}
		}
	}
	if movie.PosterPath != "" {
		info.PosterURL = "https://image.tmdb.org/t/p/w500" + movie.PosterPath
	}
	if movie.BackdropPath != "" {
		info.BackdropURL = "https://image.tmdb.org/t/p/original" + movie.BackdropPath
	}
	// Primary backdrop_path is often only 1080p; pick the highest-res image when available.
	if best := e.tmdbBestBackdropURL(ctx, apiKind, movie.ID); best != "" {
		info.BackdropURL = best
	}
	if info.Source == "" {
		info.Source = "tmdb"
	} else if !strings.Contains(info.Source, "tmdb") {
		info.Source += "+tmdb"
	}
	if movie.ID != 0 {
		info.TMDBID = movie.ID
	}
	if info.RuntimeMinutes == 0 {
		if movie.Runtime > 0 {
			info.RuntimeMinutes = movie.Runtime
		} else if len(movie.EpisodeRunTime) > 0 {
			info.RuntimeMinutes = movie.EpisodeRunTime[0]
		}
	}
	if info.Country == "" {
		info.Country = countryFromTMDB(movie)
	}
	if info.Certification == "" {
		info.Certification = certificationFromTMDB(movie)
	}
	if info.EpisodeCount == 0 && movie.NumberOfEpisodes > 0 {
		info.EpisodeCount = movie.NumberOfEpisodes
	}
	if info.Director == nil {
		info.Director = directorOrCreatorFromTMDB(movie)
	}
	if len(info.Cast) == 0 {
		info.Cast = castFromTMDBMovie(movie)
	}
	return info
}

func (e *Enricher) tmdbFind(ctx context.Context, kind, imdb string) (tmdbMovie, error) {
	u := fmt.Sprintf("https://api.themoviedb.org/3/find/%s?external_source=imdb_id&api_key=%s", url.PathEscape(imdb), url.QueryEscape(e.tmdbKey))
	var wrap tmdbFind
	if err := e.getJSON(ctx, u, &wrap); err != nil {
		return tmdbMovie{}, err
	}
	wantTV := kind == "episode" || kind == "series"
	if wantTV && len(wrap.TVResults) > 0 {
		return withMediaType(wrap.TVResults[0], "tv"), nil
	}
	if !wantTV && len(wrap.MovieResults) > 0 {
		return withMediaType(wrap.MovieResults[0], "movie"), nil
	}
	// Fall back across types only when the preferred bucket is empty. Callers must
	// use MediaType / tmdbAPIKind so movie/105 is never confused with tv/105.
	if len(wrap.MovieResults) > 0 {
		return withMediaType(wrap.MovieResults[0], "movie"), nil
	}
	if len(wrap.TVResults) > 0 {
		return withMediaType(wrap.TVResults[0], "tv"), nil
	}
	return tmdbMovie{}, fmt.Errorf("tmdb find empty")
}

func withMediaType(m tmdbMovie, mediaType string) tmdbMovie {
	m.MediaType = mediaType
	return m
}

// tmdbAPIKind picks movie vs series for TMDB detail/video routes.
// Numeric TMDB ids collide across types; MediaType from find is authoritative.
func tmdbAPIKind(requested string, found tmdbMovie) string {
	switch strings.ToLower(strings.TrimSpace(found.MediaType)) {
	case "tv":
		return "series"
	case "movie":
		return "movie"
	}
	if requested == "series" || requested == "episode" {
		return "series"
	}
	// Find TV hits often have Name/FirstAirDate and empty Title.
	if strings.TrimSpace(found.Name) != "" && strings.TrimSpace(found.Title) == "" {
		return "series"
	}
	if requested == "" || requested == "movie" {
		return "movie"
	}
	return requested
}

func (e *Enricher) tmdbSearch(ctx context.Context, kind, title string, year int) (tmdbMovie, error) {
	q := CleanQuery(title)
	path := "search/movie"
	if kind == "episode" || kind == "series" {
		path = "search/tv"
	}
	u := fmt.Sprintf("https://api.themoviedb.org/3/%s?query=%s&api_key=%s", path, url.QueryEscape(q), url.QueryEscape(e.tmdbKey))
	if year > 0 && kind != "episode" && kind != "series" {
		u += fmt.Sprintf("&year=%d", year)
	}
	var wrap tmdbSearch
	if err := e.getJSON(ctx, u, &wrap); err != nil {
		return tmdbMovie{}, err
	}
	if len(wrap.Results) == 0 {
		return tmdbMovie{}, fmt.Errorf("tmdb search empty")
	}
	want := strings.ToLower(q)
	var exact []tmdbMovie
	for _, r := range wrap.Results {
		name := strings.ToLower(strings.TrimSpace(r.Title))
		if name == "" {
			name = strings.ToLower(strings.TrimSpace(r.Name))
		}
		if name != want {
			continue
		}
		y := yearFromRelease(r.ReleaseDate)
		if y == 0 {
			y = yearFromRelease(r.FirstAirDate)
		}
		if year > 0 && y > 0 && y != year {
			continue
		}
		exact = append(exact, r)
	}
	if year > 0 {
		for _, r := range exact {
			y := yearFromRelease(r.ReleaseDate)
			if y == 0 {
				y = yearFromRelease(r.FirstAirDate)
			}
			if y == year {
				return r, nil
			}
		}
		return tmdbMovie{}, fmt.Errorf("tmdb no exact year match")
	}
	if len(exact) == 1 {
		return exact[0], nil
	}
	return tmdbMovie{}, fmt.Errorf("tmdb no exact title match")
}

type tmdbSeason struct {
	Episodes []tmdbSeasonEpisode `json:"episodes"`
}

type tmdbSeasonEpisode struct {
	EpisodeNumber int    `json:"episode_number"`
	Name          string `json:"name"`
	Overview      string `json:"overview"`
	StillPath     string `json:"still_path"`
	Runtime       int    `json:"runtime"`
	AirDate       string `json:"air_date"`
}

func (e *Enricher) OverlayEpisodeStills(ctx context.Context, imdb string, eps []CatalogItem) []CatalogItem {
	if !e.tmdbEnabled() || len(eps) == 0 || strings.TrimSpace(imdb) == "" {
		return eps
	}
	tv, err := e.tmdbFind(ctx, "series", imdb)
	if err != nil || tv.ID == 0 {
		return eps
	}
	seasons := map[int]struct{}{}
	for _, ep := range eps {
		seasons[ep.Season] = struct{}{}
	}
	meta := map[[2]int]episodeStill{}
	for season := range seasons {
		payload, err := e.tmdbSeason(ctx, tv.ID, season)
		if err != nil {
			continue
		}
		for _, te := range payload.Episodes {
			meta[[2]int{season, te.EpisodeNumber}] = episodeStill{
				Title:   strings.TrimSpace(te.Name),
				Plot:    strings.TrimSpace(te.Overview),
				Still:   tmdbImage(te.StillPath, "w780"),
				Runtime: te.Runtime,
				Year:    yearFromRelease(te.AirDate),
			}
		}
	}
	return applyEpisodeStills(eps, meta)
}

func (e *Enricher) tmdbSeason(ctx context.Context, tvID, season int) (tmdbSeason, error) {
	if payload, fetched, ok := e.peekTMDBSeason(tvID, season); ok && catalogFresh(fetched, e.metaTTL()) {
		return payload, nil
	}
	u := fmt.Sprintf("https://api.themoviedb.org/3/tv/%d/season/%d?api_key=%s", tvID, season, url.QueryEscape(e.tmdbKey))
	var payload tmdbSeason
	if err := e.getJSON(ctx, u, &payload); err != nil {
		if cached, _, ok := e.peekTMDBSeason(tvID, season); ok {
			return cached, nil
		}
		return tmdbSeason{}, err
	}
	e.storeTMDBSeason(tvID, season, payload)
	return payload, nil
}

func (e *Enricher) tmdbDetail(ctx context.Context, kind string, id int) (tmdbMovie, error) {
	path := "movie"
	append := "credits,release_dates"
	if kind == "episode" || kind == "series" {
		path = "tv"
		// aggregate_credits = full series cast; credits = latest season only (often sparse).
		append = "aggregate_credits,credits,content_ratings"
	}
	u := fmt.Sprintf("https://api.themoviedb.org/3/%s/%d?api_key=%s&append_to_response=%s", path, id, url.QueryEscape(e.tmdbKey), append)
	var movie tmdbMovie
	if err := e.getJSON(ctx, u, &movie); err != nil {
		return tmdbMovie{}, err
	}
	return movie, nil
}

type tmdbImageAsset struct {
	FilePath     string  `json:"file_path"`
	Width        int     `json:"width"`
	Height       int     `json:"height"`
	ISO6391      string  `json:"iso_639_1"`
	VoteAverage  float64 `json:"vote_average"`
	VoteCount    int     `json:"vote_count"`
	AspectRatio  float64 `json:"aspect_ratio"`
}

type tmdbImages struct {
	Backdrops []tmdbImageAsset `json:"backdrops"`
	Posters   []tmdbImageAsset `json:"posters"`
}

// tmdbBestBackdropURL returns the highest-resolution backdrop original URL.
// Prefers textless (null language) assets, then vote score, among max pixel count.
func (e *Enricher) tmdbBestBackdropURL(ctx context.Context, kind string, id int) string {
	path := e.tmdbBestBackdropPath(ctx, kind, id)
	if path == "" {
		return ""
	}
	return tmdbImage(path, "original")
}

func (e *Enricher) tmdbBestBackdropPath(ctx context.Context, kind string, id int) string {
	if !e.tmdbEnabled() || id == 0 {
		return ""
	}
	media := "movie"
	if kind == "episode" || kind == "series" {
		media = "tv"
	}
	u := fmt.Sprintf("https://api.themoviedb.org/3/%s/%d/images?api_key=%s", media, id, url.QueryEscape(e.tmdbKey))
	var wrap tmdbImages
	if err := e.getJSON(ctx, u, &wrap); err != nil || len(wrap.Backdrops) == 0 {
		return ""
	}
	return pickBestTMDBBackdrop(wrap.Backdrops)
}

func pickBestTMDBBackdrop(backs []tmdbImageAsset) string {
	if len(backs) == 0 {
		return ""
	}
	best := backs[0]
	bestScore := tmdbBackdropScore(best)
	for _, b := range backs[1:] {
		if b.FilePath == "" {
			continue
		}
		s := tmdbBackdropScore(b)
		if s > bestScore {
			best = b
			bestScore = s
		}
	}
	return strings.TrimSpace(best.FilePath)
}

func tmdbBackdropScore(b tmdbImageAsset) int64 {
	pixels := int64(b.Width) * int64(b.Height)
	// Prefer textless art (null/empty language) over localized text overlays.
	langBonus := int64(0)
	if strings.TrimSpace(b.ISO6391) == "" {
		langBonus = 1_000_000_000_000
	}
	votes := int64(b.VoteAverage*100) + int64(b.VoteCount)
	return langBonus + pixels*1000 + votes
}

func (e *Enricher) tmdbMovieReleasePhase(ctx context.Context, id int, primary, status string) string {
	if id == 0 {
		return ClassifyMovieReleasePhase(status, primary, "", "", time.Time{})
	}
	u := fmt.Sprintf("https://api.themoviedb.org/3/movie/%d/release_dates?api_key=%s", id, url.QueryEscape(e.tmdbKey))
	var payload tmdbReleaseDates
	if err := e.getJSON(ctx, u, &payload); err != nil {
		return ClassifyMovieReleasePhase(status, primary, "", "", time.Time{})
	}
	thea, dig := extractMovieReleaseMilestones(payload)
	return ClassifyMovieReleasePhase(status, primary, thea, dig, time.Time{})
}

func (e *Enricher) tmdbMovieReleaseMeta(ctx context.Context, id int, primary, status string) (phase, releaseDate string) {
	if id == 0 {
		phase = ClassifyMovieReleasePhase(status, primary, "", "", time.Time{})
		releaseDate = PreferredReleaseDate(status, primary, "", "", time.Time{})
		return phase, releaseDate
	}
	u := fmt.Sprintf("https://api.themoviedb.org/3/movie/%d/release_dates?api_key=%s", id, url.QueryEscape(e.tmdbKey))
	var payload tmdbReleaseDates
	thea, dig := "", ""
	if err := e.getJSON(ctx, u, &payload); err == nil {
		thea, dig = extractMovieReleaseMilestones(payload)
	}
	phase = ClassifyMovieReleasePhase(status, primary, thea, dig, time.Time{})
	releaseDate = PreferredReleaseDate(status, primary, thea, dig, time.Time{})
	return phase, releaseDate
}

func tmdbImage(path, size string) string {
	path = strings.TrimSpace(path)
	if path == "" {
		return ""
	}
	if !strings.HasPrefix(path, "/") {
		path = "/" + path
	}
	return "https://image.tmdb.org/t/p/" + size + path
}

func creditsFromTMDB(credits tmdbCredits) []CastMember {
	out := []CastMember{}
	for _, row := range credits.Cast {
		if strings.TrimSpace(row.Name) == "" {
			continue
		}
		out = append(out, CastMember{
			TMDBID:     row.ID,
			Name:       row.Name,
			Character:  row.Character,
			ProfileURL: tmdbImage(row.ProfilePath, "w185"),
		})
		if len(out) >= 16 {
			break
		}
	}
	return out
}

func castFromTMDBMovie(movie tmdbMovie) []CastMember {
	if agg := castFromAggregateCredits(movie.AggregateCredits); len(agg) > 0 {
		return agg
	}
	return creditsFromTMDB(movie.Credits)
}

func castFromAggregateCredits(credits tmdbAggregateCredits) []CastMember {
	rows := append([]tmdbAggregateCast(nil), credits.Cast...)
	// Prefer series regulars / hosts over billing order. TMDB often leaves the
	// actual star at order=0 while supporting cast starts at 1 (e.g. Clarkson's Farm).
	sort.SliceStable(rows, func(i, j int) bool {
		si, sj := aggregateCastRank(rows[i]), aggregateCastRank(rows[j])
		if si != sj {
			return si > sj
		}
		if rows[i].TotalEpisodeCount != rows[j].TotalEpisodeCount {
			return rows[i].TotalEpisodeCount > rows[j].TotalEpisodeCount
		}
		if rows[i].Popularity != rows[j].Popularity {
			return rows[i].Popularity > rows[j].Popularity
		}
		if rows[i].Order != rows[j].Order {
			return rows[i].Order < rows[j].Order
		}
		return rows[i].ID < rows[j].ID
	})
	out := []CastMember{}
	for _, row := range rows {
		name := strings.TrimSpace(row.Name)
		if name == "" {
			continue
		}
		character := ""
		if len(row.Roles) > 0 {
			character = strings.TrimSpace(row.Roles[0].Character)
		}
		out = append(out, CastMember{
			TMDBID:     row.ID,
			Name:       name,
			Character:  character,
			ProfileURL: tmdbImage(row.ProfilePath, "w185"),
		})
		if len(out) >= 16 {
			break
		}
	}
	return out
}

// aggregateCastRank scores how central a person is to a series. Episode count
// dominates so one-off cameos do not outrank hosts; popularity breaks ties.
func aggregateCastRank(row tmdbAggregateCast) float64 {
	eps := float64(row.TotalEpisodeCount)
	if eps <= 0 && len(row.Roles) > 0 {
		for _, role := range row.Roles {
			eps += float64(role.EpisodeCount)
		}
	}
	return eps*math.Log1p(row.Popularity+1) + row.Popularity
}

func applyOverviewMeta(item *CatalogItem, movie tmdbMovie) {
	if item.RuntimeMinutes == 0 {
		if movie.Runtime > 0 {
			item.RuntimeMinutes = movie.Runtime
		} else if len(movie.EpisodeRunTime) > 0 {
			item.RuntimeMinutes = movie.EpisodeRunTime[0]
		}
	}
	if item.Country == "" {
		item.Country = countryFromTMDB(movie)
	}
	if item.Certification == "" {
		item.Certification = certificationFromTMDB(movie)
	}
	if item.EpisodeCount == 0 && movie.NumberOfEpisodes > 0 {
		item.EpisodeCount = movie.NumberOfEpisodes
	}
	if item.Director == nil {
		item.Director = directorOrCreatorFromTMDB(movie)
	}
	if len(item.Cast) == 0 {
		item.Cast = castFromTMDBMovie(movie)
	}
}

func (e *Enricher) tmdbEpisodeCount(ctx context.Context, id int) int {
	if id == 0 {
		return 0
	}
	var wrap struct {
		NumberOfEpisodes int `json:"number_of_episodes"`
	}
	u := fmt.Sprintf("https://api.themoviedb.org/3/tv/%d?api_key=%s", id, url.QueryEscape(e.tmdbKey))
	if e.getJSON(ctx, u, &wrap) != nil {
		return 0
	}
	return wrap.NumberOfEpisodes
}

func countryFromTMDB(movie tmdbMovie) string {
	if len(movie.OriginCountry) > 0 && strings.TrimSpace(movie.OriginCountry[0]) != "" {
		return displayCountry(movie.OriginCountry[0])
	}
	if len(movie.ProductionCountries) > 0 {
		code := strings.TrimSpace(movie.ProductionCountries[0].ISO31661)
		if code != "" {
			return displayCountry(code)
		}
	}
	return ""
}

func displayCountry(code string) string {
	switch strings.ToUpper(strings.TrimSpace(code)) {
	case "US", "USA":
		return "USA"
	case "GB", "UK":
		return "UK"
	default:
		return strings.ToUpper(strings.TrimSpace(code))
	}
}

func certificationFromTMDB(movie tmdbMovie) string {
	best := ""
	for _, row := range movie.ReleaseDates.Results {
		for _, entry := range row.ReleaseDates {
			cert := strings.TrimSpace(entry.Certification)
			if cert == "" {
				continue
			}
			if strings.EqualFold(row.ISO31661, "US") {
				return cert
			}
			if best == "" {
				best = cert
			}
		}
	}
	for _, row := range movie.ContentRatings.Results {
		cert := strings.TrimSpace(row.Rating)
		if cert == "" {
			continue
		}
		if strings.EqualFold(row.ISO31661, "US") {
			return cert
		}
		if best == "" {
			best = cert
		}
	}
	return best
}

func directorFromTMDB(credits tmdbCredits) *CastMember {
	for _, row := range credits.Crew {
		if !strings.EqualFold(strings.TrimSpace(row.Job), "Director") {
			continue
		}
		name := strings.TrimSpace(row.Name)
		if name == "" {
			continue
		}
		return &CastMember{
			TMDBID:     row.ID,
			Name:       name,
			Character:  "Director",
			ProfileURL: tmdbImage(row.ProfilePath, "w185"),
		}
	}
	return nil
}

func creatorFromTMDB(creators []tmdbCreator) *CastMember {
	for _, row := range creators {
		name := strings.TrimSpace(row.Name)
		if name == "" {
			continue
		}
		return &CastMember{
			TMDBID:     row.ID,
			Name:       name,
			Character:  "Creator",
			ProfileURL: tmdbImage(row.ProfilePath, "w185"),
		}
	}
	return nil
}

func directorOrCreatorFromTMDB(movie tmdbMovie) *CastMember {
	if d := directorFromTMDB(movie.Credits); d != nil {
		return d
	}
	if d := directorFromTMDB(tmdbCredits{Crew: movie.AggregateCredits.Crew}); d != nil {
		return d
	}
	return creatorFromTMDB(movie.CreatedBy)
}

type tmdbVideo struct {
	Key      string `json:"key"`
	Site     string `json:"site"`
	Type     string `json:"type"`
	Official bool   `json:"official"`
	Name     string `json:"name"`
}

func (e *Enricher) OfficialTrailer(ctx context.Context, kind, imdb string, tmdbID int) (string, error) {
	if !e.tmdbEnabled() {
		return "", fmt.Errorf("TMDB is not configured")
	}
	id := tmdbID
	apiKind := kind
	if id == 0 {
		imdb = strings.TrimSpace(imdb)
		if !strings.HasPrefix(imdb, "tt") {
			return "", fmt.Errorf("imdb id required")
		}
		movie, err := e.tmdbFind(ctx, kind, imdb)
		if err != nil || movie.ID == 0 {
			return "", fmt.Errorf("title not found")
		}
		id = movie.ID
		apiKind = tmdbAPIKind(kind, movie)
	}
	path := "movie"
	if apiKind == "series" || apiKind == "episode" {
		path = "tv"
	}
	u := fmt.Sprintf("https://api.themoviedb.org/3/%s/%d/videos?api_key=%s", path, id, url.QueryEscape(e.tmdbKey))
	var wrap struct {
		Results []tmdbVideo `json:"results"`
	}
	if err := e.getJSON(ctx, u, &wrap); err != nil {
		return "", err
	}
	page := pickYouTubeTrailer(wrap.Results)
	if page == "" {
		return "", fmt.Errorf("no youtube trailer")
	}
	return page, nil
}

func pickYouTubeTrailer(videos []tmdbVideo) string {
	score := func(v tmdbVideo) int {
		if !strings.EqualFold(v.Site, "YouTube") || strings.TrimSpace(v.Key) == "" {
			return 0
		}
		typ := strings.ToLower(strings.TrimSpace(v.Type))
		n := 1
		if typ == "trailer" {
			n = 4
		} else if typ == "teaser" {
			n = 2
		}
		if v.Official {
			n += 2
		}
		if strings.Contains(strings.ToLower(v.Name), "official") {
			n++
		}
		return n
	}
	best := tmdbVideo{}
	bestScore := 0
	for _, v := range videos {
		if s := score(v); s > bestScore {
			best = v
			bestScore = s
		}
	}
	if bestScore == 0 {
		return ""
	}
	return "https://www.youtube.com/watch?v=" + strings.TrimSpace(best.Key)
}
