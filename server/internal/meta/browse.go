package meta

import (
	"context"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const browseMaxGenres = 3

// BrowseQuery is the composable Movies/Series discovery filter set.
type BrowseQuery struct {
	Kind      string
	Sort      string
	GenreIDs  []int // AND, max 3
	YearMin   int   // inclusive, 0 = unset
	YearMax   int   // inclusive, 0 = unset
	Mood      string
	MinRating float64
}

// Mood is a curated keyword pack exposed to the client.
type Mood struct {
	ID    string `json:"id"`
	Label string `json:"label"`
}

// CatalogMoods returns curated mood packs (same list for movie/tv).
func (e *Enricher) CatalogMoods(_ context.Context, _ string) []Mood {
	out := make([]Mood, 0, len(browseMoodPacks))
	for _, p := range browseMoodPacks {
		out = append(out, Mood{ID: p.ID, Label: p.Label})
	}
	return out
}

type moodPack struct {
	ID         string
	Label      string
	KeywordIDs []int // OR'd in discover
}

// Curated TMDB keyword IDs (OR within a mood). Sparse tagging → multiple IDs per pack.
var browseMoodPacks = []moodPack{
	{ID: "funny", Label: "Funny", KeywordIDs: []int{377102, 327497, 322268, 155456, 275276}},
	{ID: "dark", Label: "Dark", KeywordIDs: []int{259094, 10123, 272553, 12565}},
	{ID: "feel_good", Label: "Feel-good", KeywordIDs: []int{275276, 319357, 194088, 10683}},
	{ID: "thrilling", Label: "Thrilling", KeywordIDs: []int{353406, 288394, 316832, 12565}},
	{ID: "mind_bending", Label: "Mind-bending", KeywordIDs: []int{362567, 275311, 3874}},
	{ID: "romantic", Label: "Romantic", KeywordIDs: []int{324429, 9840, 4516}},
}

func NormalizeBrowseQuery(q BrowseQuery) BrowseQuery {
	q.Kind = strings.ToLower(strings.TrimSpace(q.Kind))
	if q.Kind == "tv" || q.Kind == "episode" {
		q.Kind = "series"
	}
	if q.Kind != "series" {
		q.Kind = "movie"
	}
	q.Sort = strings.ToLower(strings.TrimSpace(q.Sort))
	if q.Sort == "" {
		q.Sort = "trending"
	}
	q.Mood = strings.ToLower(strings.TrimSpace(q.Mood))
	q.Mood = strings.ReplaceAll(q.Mood, "-", "_")

	seen := map[int]bool{}
	genres := make([]int, 0, browseMaxGenres)
	for _, id := range q.GenreIDs {
		if id <= 0 || seen[id] {
			continue
		}
		seen[id] = true
		genres = append(genres, id)
		if len(genres) >= browseMaxGenres {
			break
		}
	}
	q.GenreIDs = genres

	if q.YearMin > 0 && q.YearMax > 0 && q.YearMin > q.YearMax {
		q.YearMin, q.YearMax = q.YearMax, q.YearMin
	}
	if q.MinRating < 0 {
		q.MinRating = 0
	}
	if q.MinRating > 10 {
		q.MinRating = 10
	}
	return q
}

func (q BrowseQuery) HasFacets() bool {
	return len(q.GenreIDs) > 0 || q.YearMin > 0 || q.YearMax > 0 || q.Mood != "" || q.MinRating > 0
}

func (q BrowseQuery) mediaPath() string {
	if q.Kind == "series" {
		return "tv"
	}
	return "movie"
}

func moodKeywordClause(moodID string) string {
	ids := MoodKeywordIDs(moodID)
	if len(ids) == 0 {
		return ""
	}
	parts := make([]string, 0, len(ids))
	for _, id := range ids {
		parts = append(parts, strconv.Itoa(id))
	}
	return strings.Join(parts, "|")
}

func MoodKeywordIDs(moodID string) []int {
	moodID = strings.ToLower(strings.TrimSpace(moodID))
	moodID = strings.ReplaceAll(moodID, "-", "_")
	for _, p := range browseMoodPacks {
		if p.ID == moodID {
			return append([]int(nil), p.KeywordIDs...)
		}
	}
	return nil
}

// BrowseCatalog returns discover/trending results for Movies/Series browse.
func (e *Enricher) BrowseCatalog(ctx context.Context, q BrowseQuery) ([]CatalogItem, error) {
	q = NormalizeBrowseQuery(q)
	media := q.mediaPath()
	if !e.tmdbEnabled() {
		movies, series, err := e.HomeCatalog(ctx)
		if err != nil {
			return nil, err
		}
		if q.Kind == "series" {
			return series, nil
		}
		return movies, nil
	}

	useDiscover := q.HasFacets()
	sortBy := ""
	switch q.Sort {
	case "popular":
		useDiscover = true
		sortBy = "popularity.desc"
	case "new":
		useDiscover = true
		if media == "tv" {
			sortBy = "first_air_date.desc"
		} else {
			sortBy = "primary_release_date.desc"
		}
	case "rating", "top", "top_rated":
		useDiscover = true
		sortBy = "vote_average.desc"
	case "recommended", "for_you", "foryou", "trending":
		if useDiscover {
			sortBy = "popularity.desc"
		}
	default:
		if useDiscover {
			sortBy = "popularity.desc"
		}
	}

	if !useDiscover {
		return e.tmdbTrending(ctx, media)
	}
	return e.tmdbDiscoverQuery(ctx, media, sortBy, q)
}

func (e *Enricher) tmdbDiscoverQuery(ctx context.Context, media, sort string, q BrowseQuery) ([]CatalogItem, error) {
	if sort == "" {
		sort = "popularity.desc"
	}
	today := time.Now().UTC().Format("2006-01-02")
	voteFloor := 20
	if sort == "vote_average.desc" {
		voteFloor = 100
	}
	u := fmt.Sprintf(
		"https://api.themoviedb.org/3/discover/%s?api_key=%s&sort_by=%s&page=1&vote_count.gte=%d",
		media, url.QueryEscape(e.tmdbKey), url.QueryEscape(sort), voteFloor,
	)
	if len(q.GenreIDs) > 0 {
		parts := make([]string, len(q.GenreIDs))
		for i, id := range q.GenreIDs {
			parts[i] = strconv.Itoa(id)
		}
		u += "&with_genres=" + url.QueryEscape(strings.Join(parts, ","))
	}
	if kw := moodKeywordClause(q.Mood); kw != "" {
		u += "&with_keywords=" + url.QueryEscape(kw)
	}
	if q.MinRating > 0 {
		u += fmt.Sprintf("&vote_average.gte=%.1f", q.MinRating)
	}
	gte, lte := browseDateBounds(q, today)
	if media == "tv" {
		if gte != "" {
			u += "&first_air_date.gte=" + gte
		}
		if lte != "" {
			u += "&first_air_date.lte=" + lte
		}
	} else {
		if gte != "" {
			u += "&primary_release_date.gte=" + gte
		}
		if lte != "" {
			u += "&primary_release_date.lte=" + lte
		}
	}

	var wrap struct {
		Results []tmdbMovie `json:"results"`
	}
	if err := e.getJSON(ctx, u, &wrap); err != nil {
		return nil, err
	}
	return e.catalogFromTMDBList(ctx, media, wrap.Results), nil
}

func browseDateBounds(q BrowseQuery, today string) (gte, lte string) {
	if q.YearMin > 0 {
		gte = fmt.Sprintf("%04d-01-01", q.YearMin)
	}
	if q.YearMax > 0 {
		lte = fmt.Sprintf("%04d-12-31", q.YearMax)
	}
	if q.Sort == "new" {
		if lte == "" || lte > today {
			lte = today
		}
	}
	return gte, lte
}

// FilterMergedBrowse keeps remote hits and library-only extras that match local facets.
// Mood cannot be verified on locals → library-only rows are dropped when a mood is set.
func FilterMergedBrowse(remote, merged []CatalogItem, q BrowseQuery) []CatalogItem {
	q = NormalizeBrowseQuery(q)
	remoteKeys := map[string]bool{}
	for _, item := range remote {
		remoteKeys[browseItemKey(item)] = true
	}
	out := make([]CatalogItem, 0, len(merged))
	for _, item := range merged {
		key := browseItemKey(item)
		if remoteKeys[key] {
			out = append(out, item)
			continue
		}
		if q.Mood != "" {
			continue
		}
		if !itemMatchesBrowseLocal(item, q) {
			continue
		}
		out = append(out, item)
	}
	return out
}

func browseItemKey(item CatalogItem) string {
	if id := strings.ToLower(strings.TrimSpace(item.ImdbID)); id != "" {
		return id
	}
	if item.TMDBID != 0 {
		return fmt.Sprintf("tmdb:%d", item.TMDBID)
	}
	return strings.ToLower(strings.TrimSpace(item.ID))
}

func itemMatchesBrowseLocal(item CatalogItem, q BrowseQuery) bool {
	if len(q.GenreIDs) > 0 {
		have := map[int]bool{}
		for _, id := range item.GenreIDs {
			have[id] = true
		}
		for _, need := range q.GenreIDs {
			if !have[need] {
				return false
			}
		}
	}
	if q.YearMin > 0 && (item.Year == 0 || item.Year < q.YearMin) {
		return false
	}
	if q.YearMax > 0 && (item.Year == 0 || item.Year > q.YearMax) {
		return false
	}
	if q.MinRating > 0 && item.Rating+0.05 < q.MinRating {
		return false
	}
	return true
}

// ParseGenreCSV parses "35,10749" into unique positive IDs (max 3).
func ParseGenreCSV(raw string) []int {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil
	}
	parts := strings.FieldsFunc(raw, func(r rune) bool {
		return r == ',' || r == '|' || r == ' '
	})
	seen := map[int]bool{}
	out := make([]int, 0, browseMaxGenres)
	for _, p := range parts {
		id, err := strconv.Atoi(strings.TrimSpace(p))
		if err != nil || id <= 0 || seen[id] {
			continue
		}
		seen[id] = true
		out = append(out, id)
		if len(out) >= browseMaxGenres {
			break
		}
	}
	return out
}
