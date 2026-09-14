package meta

import (
	"context"
	"fmt"
	"math"
	"net/url"
	"sort"
	"strings"
)

const similarLimit = 16

type similarCand struct {
	row   tmdbMovie
	score float64
}

// Similar returns related titles ranked by director/creator overlap, shared cast,
// shared genres, and TMDB recommendations — not raw /similar alone.
func (e *Enricher) Similar(ctx context.Context, kind, imdb string) ([]CatalogItem, error) {
	if !e.tmdbEnabled() {
		return nil, fmt.Errorf("TMDB is not configured")
	}
	imdb = strings.TrimSpace(imdb)
	if !strings.HasPrefix(imdb, "tt") {
		return nil, fmt.Errorf("imdb id required")
	}
	found, err := e.tmdbFind(ctx, kind, imdb)
	if err != nil || found.ID == 0 {
		return nil, fmt.Errorf("title not found")
	}
	apiKind := tmdbAPIKind(kind, found)
	detail, err := e.tmdbDetail(ctx, apiKind, found.ID)
	if err != nil || detail.ID == 0 {
		detail = found
	}
	path := "movie"
	outKind := "movie"
	if apiKind == "series" || apiKind == "episode" {
		path = "tv"
		outKind = "series"
	}

	pool := map[int]*similarCand{}
	seedGenres := genreIDList(detail)
	add := func(row tmdbMovie, pts float64) {
		if pts <= 0 || row.ID == 0 || row.ID == detail.ID {
			return
		}
		if !similarMediaOK(row, outKind) {
			return
		}
		c := pool[row.ID]
		if c == nil {
			c = &similarCand{row: row}
			pool[row.ID] = c
		} else {
			c.row = mergeTMDBRow(c.row, row)
		}
		c.score += pts
	}

	e.collectRelated(ctx, path, detail.ID, "recommendations", func(row tmdbMovie, i int) {
		add(row, 6.5-float64(i)*0.15)
	})
	e.collectRelated(ctx, path, detail.ID, "similar", func(row tmdbMovie, i int) {
		add(row, 2.5-float64(i)*0.08)
	})

	if dir := directorOrCreatorFromTMDB(detail); dir != nil && dir.TMDBID != 0 {
		directorOnly := outKind == "movie" && strings.EqualFold(dir.Character, "Director")
		for i, row := range e.personTitleRows(ctx, dir.TMDBID, outKind, directorOnly) {
			if i >= 24 {
				break
			}
			add(row, 22.0-float64(i)*0.35)
		}
	}

	// Top aggregate cast is already ranked by series centrality (eps × popularity).
	cast := castFromTMDBMovie(detail)
	if len(cast) > 4 {
		cast = cast[:4]
	}
	for ci, person := range cast {
		if person.TMDBID == 0 {
			continue
		}
		// Lead cast must outrank generic TMDB recommendations.
		base := 18.0 - float64(ci)*2.0
		for i, row := range e.personTitleRows(ctx, person.TMDBID, outKind, false) {
			if i >= 16 {
				break
			}
			add(row, base-float64(i)*0.12)
		}
	}

	if len(seedGenres) > 0 {
		for i, row := range e.discoverByGenres(ctx, path, seedGenres) {
			if i >= 20 {
				break
			}
			overlap := genreOverlapCount(seedGenres, row.GenreIDs)
			// Soft filler only — keeps shelves full without drowning people-based matches.
			add(row, 0.9+float64(overlap)*0.9-float64(i)*0.04)
		}
	}

	cands := make([]*similarCand, 0, len(pool))
	for _, c := range pool {
		overlap := genreOverlapCount(seedGenres, c.row.GenreIDs)
		c.score += float64(overlap) * 1.25
		c.score += math.Log1p(c.row.Popularity) * 0.35
		if c.row.VoteAverage >= 7 {
			c.score += (c.row.VoteAverage - 6.5) * 0.4
		}
		cands = append(cands, c)
	}
	sort.SliceStable(cands, func(i, j int) bool {
		if cands[i].score != cands[j].score {
			return cands[i].score > cands[j].score
		}
		if cands[i].row.Popularity != cands[j].row.Popularity {
			return cands[i].row.Popularity > cands[j].row.Popularity
		}
		return cands[i].row.ID < cands[j].row.ID
	})
	if len(cands) > similarLimit {
		cands = cands[:similarLimit]
	}
	out := make([]CatalogItem, 0, len(cands))
	for _, c := range cands {
		out = append(out, e.searchItem(c.row, outKind))
	}
	return out, nil
}

func (e *Enricher) collectRelated(ctx context.Context, path string, id int, relation string, each func(tmdbMovie, int)) {
	u := fmt.Sprintf("https://api.themoviedb.org/3/%s/%d/%s?api_key=%s", path, id, relation, url.QueryEscape(e.tmdbKey))
	var wrap tmdbSearch
	if e.getJSON(ctx, u, &wrap) != nil {
		return
	}
	for i, row := range wrap.Results {
		each(row, i)
	}
}

func (e *Enricher) personTitleRows(ctx context.Context, personID int, kind string, directorOnly bool) []tmdbMovie {
	if personID == 0 {
		return nil
	}
	var credits struct {
		Cast []tmdbMovie `json:"cast"`
		Crew []tmdbMovie `json:"crew"`
	}
	u := fmt.Sprintf("https://api.themoviedb.org/3/person/%d/combined_credits?api_key=%s", personID, url.QueryEscape(e.tmdbKey))
	if e.getJSON(ctx, u, &credits) != nil {
		return nil
	}
	var rows []tmdbMovie
	if directorOnly {
		for _, row := range credits.Crew {
			if !strings.EqualFold(strings.TrimSpace(row.Job), "Director") {
				continue
			}
			rows = append(rows, row)
		}
	} else {
		rows = append(rows, credits.Cast...)
		rows = append(rows, credits.Crew...)
	}
	seen := map[int]bool{}
	out := make([]tmdbMovie, 0, len(rows))
	for _, row := range rows {
		if row.ID == 0 || seen[row.ID] || !similarMediaOK(row, kind) {
			continue
		}
		seen[row.ID] = true
		out = append(out, row)
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].Popularity != out[j].Popularity {
			return out[i].Popularity > out[j].Popularity
		}
		return yearFromRelease(out[i].ReleaseDate+out[i].FirstAirDate) > yearFromRelease(out[j].ReleaseDate+out[j].FirstAirDate)
	})
	return out
}

func (e *Enricher) discoverByGenres(ctx context.Context, path string, genres []int) []tmdbMovie {
	if len(genres) == 0 {
		return nil
	}
	ids := make([]string, 0, 2)
	for _, id := range genres {
		if id == 0 {
			continue
		}
		ids = append(ids, fmt.Sprintf("%d", id))
		if len(ids) >= 2 {
			break
		}
	}
	if len(ids) == 0 {
		return nil
	}
	// Pipe = OR so we don't over-constrain to the exact intersection.
	u := fmt.Sprintf(
		"https://api.themoviedb.org/3/discover/%s?api_key=%s&sort_by=popularity.desc&vote_count.gte=40&with_genres=%s",
		path, url.QueryEscape(e.tmdbKey), url.QueryEscape(strings.Join(ids, "|")),
	)
	var wrap tmdbSearch
	if e.getJSON(ctx, u, &wrap) != nil {
		return nil
	}
	return wrap.Results
}

func similarMediaOK(row tmdbMovie, kind string) bool {
	mt := strings.ToLower(strings.TrimSpace(row.MediaType))
	if mt == "" {
		// Discover / related lists omit media_type; title vs name is a soft hint.
		if kind == "movie" {
			return strings.TrimSpace(row.Title) != "" || strings.TrimSpace(row.Name) == ""
		}
		return strings.TrimSpace(row.Name) != "" || strings.TrimSpace(row.Title) == ""
	}
	if kind == "movie" {
		return mt == "movie"
	}
	return mt == "tv"
}

func genreIDList(movie tmdbMovie) []int {
	seen := map[int]struct{}{}
	out := make([]int, 0, len(movie.GenreIDs)+len(movie.Genres))
	for _, id := range movie.GenreIDs {
		if id == 0 {
			continue
		}
		if _, ok := seen[id]; ok {
			continue
		}
		seen[id] = struct{}{}
		out = append(out, id)
	}
	for _, g := range movie.Genres {
		if g.ID == 0 {
			continue
		}
		if _, ok := seen[g.ID]; ok {
			continue
		}
		seen[g.ID] = struct{}{}
		out = append(out, g.ID)
	}
	return out
}

func genreOverlapCount(seed, other []int) int {
	if len(seed) == 0 || len(other) == 0 {
		return 0
	}
	set := map[int]struct{}{}
	for _, id := range seed {
		if id != 0 {
			set[id] = struct{}{}
		}
	}
	n := 0
	for _, id := range other {
		if _, ok := set[id]; ok {
			n++
		}
	}
	return n
}

func mergeTMDBRow(a, b tmdbMovie) tmdbMovie {
	out := a
	if out.Title == "" {
		out.Title = b.Title
	}
	if out.Name == "" {
		out.Name = b.Name
	}
	if out.PosterPath == "" {
		out.PosterPath = b.PosterPath
	}
	if out.BackdropPath == "" {
		out.BackdropPath = b.BackdropPath
	}
	if out.Overview == "" {
		out.Overview = b.Overview
	}
	if out.ReleaseDate == "" {
		out.ReleaseDate = b.ReleaseDate
	}
	if out.FirstAirDate == "" {
		out.FirstAirDate = b.FirstAirDate
	}
	if out.MediaType == "" {
		out.MediaType = b.MediaType
	}
	if out.Popularity < b.Popularity {
		out.Popularity = b.Popularity
	}
	if out.VoteAverage < b.VoteAverage {
		out.VoteAverage = b.VoteAverage
	}
	if len(out.GenreIDs) == 0 {
		out.GenreIDs = append([]int(nil), b.GenreIDs...)
	}
	return out
}
