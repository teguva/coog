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
	Page      int // 1-based TMDB page; progressive browse loads one page at a time
}

// Mood is a curated discover preset exposed to the client (genre shelf shortcut).
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
	ID                string
	Label             string
	GenreIDs          []int // movie with_genres OR
	WithoutGenreIDs   []int // movie without_genres
	TVGenreIDs        []int // tv with_genres OR (when empty, use GenreIDs)
	TVWithoutGenreIDs []int // tv without_genres (when empty, use WithoutGenreIDs)
}

// Simple genre-anchored mood presets (feel-based pools deferred).
var browseMoodPacks = []moodPack{
	{ID: "funny", Label: "Funny", GenreIDs: []int{35}, TVGenreIDs: []int{35}},
	{ID: "romantic", Label: "Romantic", GenreIDs: []int{10749}, TVGenreIDs: []int{18}}, // TV has no Romance id
	{ID: "thrilling", Label: "Thrilling",
		GenreIDs:   []int{53, 80, 9648},
		TVGenreIDs: []int{80, 9648, 10759}}, // Crime|Mystery|Action & Adventure
	{ID: "dark", Label: "Dark",
		GenreIDs:          []int{18, 53},
		WithoutGenreIDs:   []int{35},
		TVGenreIDs:        []int{18, 80, 9648},
		TVWithoutGenreIDs: []int{35}},
	{ID: "feel_good", Label: "Feel-good",
		GenreIDs:          []int{35, 10751},
		WithoutGenreIDs:   []int{27, 53},
		TVGenreIDs:        []int{35, 10751},
		TVWithoutGenreIDs: []int{80, 9648}},
	{ID: "mind_bending", Label: "Mind-bending",
		GenreIDs:   []int{878, 9648},
		TVGenreIDs: []int{10765, 9648}}, // Sci-Fi & Fantasy|Mystery
}

func moodPackByID(moodID string) *moodPack {
	moodID = strings.ToLower(strings.TrimSpace(moodID))
	moodID = strings.ReplaceAll(moodID, "-", "_")
	for i := range browseMoodPacks {
		if browseMoodPacks[i].ID == moodID {
			return &browseMoodPacks[i]
		}
	}
	return nil
}

func (p *moodPack) genresFor(media string) []int {
	if p == nil {
		return nil
	}
	if media == "tv" && len(p.TVGenreIDs) > 0 {
		return append([]int(nil), p.TVGenreIDs...)
	}
	return append([]int(nil), p.GenreIDs...)
}

func (p *moodPack) withoutGenresFor(media string) []int {
	if p == nil {
		return nil
	}
	if media == "tv" && len(p.TVWithoutGenreIDs) > 0 {
		return append([]int(nil), p.TVWithoutGenreIDs...)
	}
	return append([]int(nil), p.WithoutGenreIDs...)
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
	if q.Page < 1 {
		q.Page = 1
	}
	if q.Page > 20 {
		q.Page = 20
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

func joinGenreOR(ids []int) string {
	if len(ids) == 0 {
		return ""
	}
	parts := make([]string, 0, len(ids))
	for _, id := range ids {
		if id > 0 {
			parts = append(parts, strconv.Itoa(id))
		}
	}
	return strings.Join(parts, "|")
}

func joinGenreCSV(ids []int) string {
	if len(ids) == 0 {
		return ""
	}
	parts := make([]string, 0, len(ids))
	for _, id := range ids {
		if id > 0 {
			parts = append(parts, strconv.Itoa(id))
		}
	}
	return strings.Join(parts, ",")
}

// MoodGenreIDs returns the mood's with_genres OR list for media ("movie" or "tv").
func MoodGenreIDs(moodID, media string) []int {
	return moodPackByID(moodID).genresFor(media)
}

// BrowseCatalog returns discover/trending results for Movies/Series browse.
func (e *Enricher) BrowseCatalog(ctx context.Context, q BrowseQuery) ([]CatalogItem, error) {
	q = NormalizeBrowseQuery(q)
	if items, ok := e.peekBrowsePage(q); ok {
		return items, nil
	}
	items, err := e.browseCatalogUncached(ctx, q)
	if err != nil {
		return nil, err
	}
	e.storeBrowsePage(q, items)
	return items, nil
}

func (e *Enricher) browseCatalogUncached(ctx context.Context, q BrowseQuery) ([]CatalogItem, error) {
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
		return e.tmdbTrendingPage(ctx, media, q.Page, 20, true)
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
	page := q.Page
	if page < 1 {
		page = 1
	}
	u := fmt.Sprintf(
		"https://api.themoviedb.org/3/discover/%s?api_key=%s&sort_by=%s&page=%d&vote_count.gte=%d",
		media, url.QueryEscape(e.tmdbKey), url.QueryEscape(sort), page, voteFloor,
	)
	pack := moodPackByID(q.Mood)
	if len(q.GenreIDs) > 0 {
		// User genres are AND.
		if csv := joinGenreCSV(q.GenreIDs); csv != "" {
			u += "&with_genres=" + url.QueryEscape(csv)
		}
	} else if or := joinGenreOR(pack.genresFor(media)); or != "" {
		// Mood alone: genre shelf shortcut (OR within pack).
		u += "&with_genres=" + url.QueryEscape(or)
	}
	// Mood exclusions always apply when set (even alongside user genres).
	if without := joinGenreCSV(pack.withoutGenresFor(media)); without != "" {
		u += "&without_genres=" + url.QueryEscape(without)
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
	return e.catalogFromTMDBListN(ctx, media, wrap.Results, 20, true), nil
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
	return BrowseItemKey(item)
}

// BrowseItemKey identifies a catalog title across discover pages / library merge.
// TMDB movie and TV ids are separate namespaces — always include kind.
func BrowseItemKey(item CatalogItem) string {
	if id := strings.ToLower(strings.TrimSpace(item.ImdbID)); id != "" {
		return id
	}
	if item.TMDBID != 0 {
		kind := "movie"
		switch strings.ToLower(strings.TrimSpace(item.Kind)) {
		case "series", "episode", "tv":
			kind = "series"
		}
		return fmt.Sprintf("tmdb:%s:%d", kind, item.TMDBID)
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
