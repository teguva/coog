package meta

import (
	"context"
	"fmt"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"
)

func (e *Enricher) CatalogTitle(ctx context.Context, kind, imdb string) (CatalogItem, error) {
	imdb = strings.TrimSpace(imdb)
	if !strings.HasPrefix(imdb, "tt") {
		return CatalogItem{}, fmt.Errorf("imdb id required")
	}
	if kind == "" {
		kind = "movie"
	}
	if item, fetched, ok := e.peekCatalogTitle(kind, imdb); ok {
		if catalogTitleFresh(kind, item, fetched, e.metaTTL()) {
			return item, nil
		}
		go func() {
			_, _ = e.fetchCatalogTitle(context.Background(), kind, imdb)
		}()
		return item, nil
	}
	return e.fetchCatalogTitle(ctx, kind, imdb)
}

func (e *Enricher) fetchCatalogTitle(ctx context.Context, kind, imdb string) (CatalogItem, error) {
	imdb = strings.TrimSpace(imdb)
	if kind == "" {
		kind = "movie"
	}
	item := CatalogItem{
		ID:     "catalog:" + imdb,
		Kind:   kind,
		Title:  imdb,
		ImdbID: imdb,
	}
	cinemetaKind := "movie"
	if kind == "series" || kind == "episode" {
		cinemetaKind = "episode"
	}
	if info, err := e.cinemetaByIMDB(ctx, cinemetaKind, imdb); err == nil {
		if info.Plot != "" {
			item.Plot = info.Plot
		}
		if info.PosterURL != "" {
			item.PosterURL = info.PosterURL
		}
		if info.BackdropURL != "" {
			item.BackdropURL = info.BackdropURL
		}
		if info.Rating > 0 {
			item.Rating = info.Rating
		}
		if len(info.Genres) > 0 {
			item.Genres = info.Genres
		}
		if info.Year > 0 {
			item.Year = info.Year
		}
		if info.RuntimeMinutes > 0 {
			item.RuntimeMinutes = info.RuntimeMinutes
		}
	}
	if kind == "series" || kind == "episode" {
		if cover, eps, err := e.fetchCatalogShow(ctx, imdb); err == nil {
			if cover.Title != "" {
				item.Title = cover.Title
			}
			if cover.Plot != "" {
				item.Plot = cover.Plot
			}
			if cover.PosterURL != "" {
				item.PosterURL = cover.PosterURL
			}
			if cover.BackdropURL != "" {
				item.BackdropURL = cover.BackdropURL
			}
			if n := len(eps); n > 0 {
				item.EpisodeCount = n
			} else if cover.EpisodeCount > 0 {
				item.EpisodeCount = cover.EpisodeCount
			}
		}
	}
	if !e.tmdbEnabled() {
		e.storeCatalogTitle(kind, imdb, item)
		return item, nil
	}
	movie, err := e.tmdbFind(ctx, kind, imdb)
	if err != nil || movie.ID == 0 {
		e.storeCatalogTitle(kind, imdb, item)
		return item, nil
	}
	detail, err := e.tmdbDetail(ctx, kind, movie.ID)
	if err == nil && detail.ID != 0 {
		movie = detail
	}
	filled := catalogFromTMDB(movie, kind)
	if filled.Title != "" {
		item.Title = filled.Title
	}
	if item.Plot == "" {
		item.Plot = filled.Plot
	}
	if filled.PosterURL != "" {
		item.PosterURL = filled.PosterURL
	}
	if filled.BackdropURL != "" {
		item.BackdropURL = filled.BackdropURL
	}
	if filled.Year > 0 {
		item.Year = filled.Year
	}
	if filled.Rating > 0 {
		item.Rating = filled.Rating
	}
	if item.EpisodeCount == 0 && filled.EpisodeCount > 0 {
		item.EpisodeCount = filled.EpisodeCount
	}
	item.TMDBID = movie.ID
	item.Cast = castFromTMDBMovie(movie)
	if kind == "movie" {
		thea, dig := extractMovieReleaseMilestones(movie.ReleaseDates)
		item.ReleasePhase = ClassifyMovieReleasePhase(movie.Status, movie.ReleaseDate, thea, dig, time.Time{})
		if d := PreferredReleaseDate(movie.Status, movie.ReleaseDate, thea, dig, time.Time{}); d != "" {
			item.ReleaseDate = d
		}
	}
	for _, g := range movie.Genres {
		if g.Name != "" {
			item.Genres = append(item.Genres, g.Name)
		}
	}
	item.Genres = uniqueStrings(item.Genres)
	applyOverviewMeta(&item, movie)
	e.storeCatalogTitle(kind, imdb, item)
	return item, nil
}

func (e *Enricher) CatalogByTMDB(ctx context.Context, kind string, id int) (CatalogItem, error) {
	if kind == "tv" {
		kind = "series"
	}
	if kind == "" {
		kind = "movie"
	}
	if item, fetched, ok := e.peekCatalogTMDB(kind, id); ok {
		if catalogTitleFresh(kind, item, fetched, e.metaTTL()) {
			return item, nil
		}
		go func() {
			_, _ = e.fetchCatalogByTMDB(context.Background(), kind, id)
		}()
		return item, nil
	}
	return e.fetchCatalogByTMDB(ctx, kind, id)
}

func (e *Enricher) fetchCatalogByTMDB(ctx context.Context, kind string, id int) (CatalogItem, error) {
	if !e.tmdbEnabled() {
		return CatalogItem{}, fmt.Errorf("TMDB is not configured")
	}
	if kind == "tv" {
		kind = "series"
	}
	if kind == "" {
		kind = "movie"
	}
	detail, err := e.tmdbDetail(ctx, kind, id)
	if err != nil {
		return CatalogItem{}, err
	}
	media := "movie"
	if kind == "series" || kind == "episode" {
		media = "tv"
	}
	if detail.IMDBID == "" {
		detail.IMDBID = e.tmdbExternalIMDB(ctx, media, id)
	}
	item := catalogFromTMDB(detail, kind)
	item.TMDBID = id
	item.Cast = castFromTMDBMovie(detail)
	if item.ImdbID != "" {
		item.ID = "catalog:" + item.ImdbID
	} else {
		item.ID = fmt.Sprintf("catalog:tmdb:%d", id)
	}
	if kind == "movie" {
		thea, dig := extractMovieReleaseMilestones(detail.ReleaseDates)
		item.ReleasePhase = ClassifyMovieReleasePhase(detail.Status, detail.ReleaseDate, thea, dig, time.Time{})
		if d := PreferredReleaseDate(detail.Status, detail.ReleaseDate, thea, dig, time.Time{}); d != "" {
			item.ReleaseDate = d
		}
	}
	applyOverviewMeta(&item, detail)
	e.storeCatalogTMDB(kind, id, item)
	return item, nil
}

func (e *Enricher) Search(ctx context.Context, q string) (SearchResult, error) {
	out := SearchResult{Movies: []CatalogItem{}, Series: []CatalogItem{}, People: []Person{}}
	q = strings.TrimSpace(q)
	if q == "" {
		return out, nil
	}
	if !e.tmdbEnabled() {
		return out, fmt.Errorf("TMDB is not configured")
	}
	var multi struct {
		Results []tmdbMovie `json:"results"`
	}
	u := fmt.Sprintf("https://api.themoviedb.org/3/search/multi?query=%s&api_key=%s", url.QueryEscape(q), url.QueryEscape(e.tmdbKey))
	if err := e.getJSON(ctx, u, &multi); err != nil {
		return out, err
	}
	var peopleWrap struct {
		Results []tmdbMovie `json:"results"`
	}
	pu := fmt.Sprintf("https://api.themoviedb.org/3/search/person?query=%s&api_key=%s", url.QueryEscape(q), url.QueryEscape(e.tmdbKey))
	_ = e.getJSON(ctx, pu, &peopleWrap)

	for _, row := range multi.Results {
		switch row.MediaType {
		case "movie":
			if len(out.Movies) >= 16 {
				continue
			}
			item := e.searchItem(row, "movie")
			out.Movies = append(out.Movies, item)
		case "tv":
			if len(out.Series) >= 16 {
				continue
			}
			item := e.searchItem(row, "series")
			out.Series = append(out.Series, item)
		}
	}
	seenPerson := map[int]bool{}
	addPerson := func(row tmdbMovie) {
		if row.ID == 0 || seenPerson[row.ID] || len(out.People) >= 12 {
			return
		}
		name := strings.TrimSpace(row.Name)
		if name == "" {
			name = strings.TrimSpace(row.Title)
		}
		if name == "" {
			return
		}
		seenPerson[row.ID] = true
		out.People = append(out.People, Person{
			TMDBID:             row.ID,
			Name:               name,
			ProfileURL:         tmdbImage(row.ProfilePath, "w185"),
			KnownForDepartment: row.KnownForDept,
		})
	}
	for _, row := range peopleWrap.Results {
		addPerson(row)
	}
	if len(out.People) < 12 {
		for _, row := range multi.Results {
			if row.MediaType == "person" {
				addPerson(row)
			}
		}
	}
	return out, nil
}

func (e *Enricher) searchItem(row tmdbMovie, kind string) CatalogItem {
	item := catalogFromTMDB(row, kind)
	item.TMDBID = row.ID
	if item.ImdbID != "" {
		item.ID = "catalog:" + item.ImdbID
	} else if row.ID != 0 {
		item.ID = fmt.Sprintf("catalog:tmdb:%d", row.ID)
	}
	return item
}

func (e *Enricher) PersonCredits(ctx context.Context, id int) (Person, error) {
	if id == 0 {
		return Person{}, fmt.Errorf("person id required")
	}
	if person, fetched, ok := e.peekPerson(id); ok {
		if catalogFresh(fetched, e.metaTTL()) {
			return person, nil
		}
		go func() {
			_, _ = e.fetchPersonCredits(context.Background(), id)
		}()
		return person, nil
	}
	return e.fetchPersonCredits(ctx, id)
}

func (e *Enricher) fetchPersonCredits(ctx context.Context, id int) (Person, error) {
	if !e.tmdbEnabled() {
		return Person{}, fmt.Errorf("TMDB is not configured")
	}
	if id == 0 {
		return Person{}, fmt.Errorf("person id required")
	}
	var detail struct {
		ID                 int    `json:"id"`
		Name               string `json:"name"`
		ProfilePath        string `json:"profile_path"`
		KnownForDepartment string `json:"known_for_department"`
		Biography          string `json:"biography"`
		Birthday           string `json:"birthday"`
		PlaceOfBirth       string `json:"place_of_birth"`
	}
	u := fmt.Sprintf("https://api.themoviedb.org/3/person/%d?api_key=%s", id, url.QueryEscape(e.tmdbKey))
	if err := e.getJSON(ctx, u, &detail); err != nil {
		return Person{}, err
	}
	person := Person{
		TMDBID:             detail.ID,
		Name:               detail.Name,
		ProfileURL:         tmdbImage(detail.ProfilePath, "h632"),
		KnownForDepartment: detail.KnownForDepartment,
		Biography:          strings.TrimSpace(detail.Biography),
		Birthday:           strings.TrimSpace(detail.Birthday),
		PlaceOfBirth:       strings.TrimSpace(detail.PlaceOfBirth),
		Credits:            []CatalogItem{},
	}
	var credits struct {
		Cast []tmdbMovie `json:"cast"`
		Crew []tmdbMovie `json:"crew"`
	}
	cu := fmt.Sprintf("https://api.themoviedb.org/3/person/%d/combined_credits?api_key=%s", id, url.QueryEscape(e.tmdbKey))
	if err := e.getJSON(ctx, cu, &credits); err != nil {
		return person, err
	}
	rows := append([]tmdbMovie{}, credits.Cast...)
	rows = append(rows, credits.Crew...)
	seen := map[int]bool{}
	picked := []tmdbMovie{}
	for _, row := range rows {
		if row.ID == 0 || seen[row.ID] {
			continue
		}
		if row.MediaType != "movie" && row.MediaType != "tv" && row.MediaType != "" {
			continue
		}
		seen[row.ID] = true
		picked = append(picked, row)
	}
	sort.SliceStable(picked, func(i, j int) bool {
		if picked[i].Popularity != picked[j].Popularity {
			return picked[i].Popularity > picked[j].Popularity
		}
		return yearFromRelease(picked[i].ReleaseDate+picked[i].FirstAirDate) > yearFromRelease(picked[j].ReleaseDate+picked[j].FirstAirDate)
	})
	if len(picked) > 40 {
		picked = picked[:40]
	}
	type scored struct {
		item CatalogItem
		pop  float64
	}
	var mu sync.Mutex
	var wg sync.WaitGroup
	scoredCredits := make([]scored, 0, len(picked))
	for _, row := range picked {
		wg.Add(1)
		go func(row tmdbMovie) {
			defer wg.Done()
			kind := "movie"
			if row.MediaType == "tv" {
				kind = "series"
			}
			item := e.searchItem(row, kind)
			mu.Lock()
			scoredCredits = append(scoredCredits, scored{item: item, pop: row.Popularity})
			mu.Unlock()
		}(row)
	}
	wg.Wait()
	sort.SliceStable(scoredCredits, func(i, j int) bool {
		if scoredCredits[i].pop != scoredCredits[j].pop {
			return scoredCredits[i].pop > scoredCredits[j].pop
		}
		if scoredCredits[i].item.Year != scoredCredits[j].item.Year {
			return scoredCredits[i].item.Year > scoredCredits[j].item.Year
		}
		return scoredCredits[i].item.Title < scoredCredits[j].item.Title
	})
	person.Credits = make([]CatalogItem, 0, len(scoredCredits))
	for _, row := range scoredCredits {
		person.Credits = append(person.Credits, row.item)
	}
	e.storePerson(person)
	return person, nil
}

func uniqueStrings(in []string) []string {
	out := make([]string, 0, len(in))
	seen := map[string]bool{}
	for _, s := range in {
		s = strings.TrimSpace(s)
		if s == "" || seen[s] {
			continue
		}
		seen[s] = true
		out = append(out, s)
	}
	return out
}
