package api

import (
	"encoding/json"
	"net/http"
	"sort"
	"strconv"
	"strings"

	"coog/internal/meta"
	"coog/internal/settings"
	"coog/internal/store"
	"coog/internal/streams"
)

func (s *Server) handleCatalogHome(w http.ResponseWriter, r *http.Request) {
	movies, series, err := s.meta.HomeCatalog(r.Context())
	if err != nil {
		s.setCatalogError(err.Error())
		s.note("error", "api", "catalog.error", err.Error(), "", "")
		writeError(w, http.StatusBadGateway, err.Error())
		return
	}
	s.setCatalogError("")
	origin := strings.TrimRight(publicURL(r, "/"), "/")
	movies = s.withCatalogArt(origin, s.meta.HydrateFromCacheAll(movies), meta.ArtSizeThumb)
	series = s.withCatalogArt(origin, s.meta.HydrateFromCacheAll(series), meta.ArtSizeThumb)
	localMovies, localSeries := s.imdbIndex()
	scoredMovies := s.withMatchAll(mergeCatalogLibrary(movies, s.localCatalogItems("movie"), localMovies))
	scoredSeries := s.withMatchAll(mergeCatalogLibrary(series, s.localCatalogItems("series"), localSeries))
	cfg := s.tasteConfig()
	cold := s.tasteProfile().ColdStart(cfg)
	forYou := []meta.CatalogItem{}
	if !cold {
		forYou = topTasteItems(append(append([]meta.CatalogItem{}, scoredMovies...), scoredSeries...), 12)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"trendingMovies": catalogListAsMedia(scoredMovies),
		"trendingSeries": catalogListAsMedia(scoredSeries),
		"forYou":         catalogListAsMedia(forYou),
		"coldStart":      cold,
	})
}

func (s *Server) handleCatalogBrowse(w http.ResponseWriter, r *http.Request) {
	kind := strings.TrimSpace(r.URL.Query().Get("kind"))
	if kind == "" {
		kind = "movie"
	}
	sortKey := strings.TrimSpace(r.URL.Query().Get("sort"))
	genreID, _ := strconv.Atoi(r.URL.Query().Get("genre"))
	items, err := s.meta.BrowseCatalog(r.Context(), kind, sortKey, genreID)
	if err != nil {
		writeError(w, http.StatusBadGateway, err.Error())
		return
	}
	origin := strings.TrimRight(publicURL(r, "/"), "/")
	items = s.withCatalogArt(origin, s.meta.HydrateFromCacheAll(items), meta.ArtSizeThumb)
	localMovies, localSeries := s.imdbIndex()
	local := localMovies
	extrasKind := "movie"
	if kind == "series" || kind == "tv" || kind == "episode" {
		local = localSeries
		extrasKind = "series"
	}
	merged := s.withMatchAll(mergeCatalogLibrary(items, s.localCatalogItems(extrasKind), local))
	if tasteBrowseSort(sortKey) && !s.tasteProfile().ColdStart(s.tasteConfig()) {
		merged = rankByTaste(merged)
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": catalogListAsMedia(merged)})
}

func tasteBrowseSort(sortKey string) bool {
	switch strings.ToLower(strings.TrimSpace(sortKey)) {
	case "", "recommended", "trending", "for_you", "foryou":
		return true
	default:
		return false
	}
}

func rankByTaste(items []meta.CatalogItem) []meta.CatalogItem {
	out := append([]meta.CatalogItem(nil), items...)
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].MatchPercent != out[j].MatchPercent {
			return out[i].MatchPercent > out[j].MatchPercent
		}
		return out[i].Title < out[j].Title
	})
	return out
}

func topTasteItems(items []meta.CatalogItem, n int) []meta.CatalogItem {
	ranked := rankByTaste(items)
	seen := map[string]bool{}
	out := make([]meta.CatalogItem, 0, n)
	for _, item := range ranked {
		key := strings.ToLower(strings.TrimSpace(item.ImdbID))
		if key == "" {
			key = item.ID
		}
		if key == "" || seen[key] {
			continue
		}
		seen[key] = true
		out = append(out, item)
		if len(out) >= n {
			break
		}
	}
	return out
}

func (s *Server) handleCatalogGenres(w http.ResponseWriter, r *http.Request) {
	kind := strings.TrimSpace(r.URL.Query().Get("kind"))
	if kind == "" {
		kind = "movie"
	}
	genres, err := s.meta.CatalogGenres(r.Context(), kind)
	if err != nil {
		if !s.meta.TMDBEnabled() {
			writeJSON(w, http.StatusOK, map[string]any{"items": []any{}})
			return
		}
		writeError(w, http.StatusBadGateway, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": genres})
}

func (s *Server) handleCatalogShow(w http.ResponseWriter, r *http.Request) {
	imdb := strings.TrimSpace(r.PathValue("imdb"))
	if !strings.HasPrefix(imdb, "tt") {
		writeError(w, http.StatusBadRequest, "imdb id required")
		return
	}
	cover, eps, err := s.meta.CatalogShow(r.Context(), imdb)
	if err != nil {
		writeError(w, http.StatusBadGateway, err.Error())
		return
	}
	if detail, err := s.meta.CatalogTitle(r.Context(), "series", imdb); err == nil {
		if len(detail.Cast) > 0 {
			cover.Cast = detail.Cast
		}
		if detail.Plot != "" {
			cover.Plot = detail.Plot
		}
		if detail.Title != "" {
			cover.Title = detail.Title
		}
	}
	_, localSeries := s.imdbIndex()
	covers := attachLibrary([]meta.CatalogItem{cover}, localSeries)
	cover = covers[0]
	eps = s.attachEpisodeLibrary(eps, imdb)
	eps = s.meta.OverlayEpisodeStills(r.Context(), imdb, eps)
	origin := strings.TrimRight(publicURL(r, "/"), "/")
	cover = s.rewriteItemArt(origin, cover, meta.ArtSizeDisplay)
	eps = s.withCatalogArt(origin, eps, meta.ArtSizeThumb)
	writeJSON(w, http.StatusOK, map[string]any{
		"item":     s.mediaJSON(cover),
		"episodes": s.mediaList(eps),
	})
}

func (s *Server) imdbIndex() (movies, series map[string]string) {
	movies, series = map[string]string{}, map[string]string{}
	items, err := s.store.ListMedia()
	if err != nil {
		return movies, series
	}
	for _, item := range items {
		info, ok := s.meta.Peek(item.ID)
		if !ok || !meta.IdentityConfirmed(item.Path, info) {
			continue
		}
		id := strings.ToLower(info.ImdbID)
		switch item.Kind {
		case "movie":
			if _, exists := movies[id]; !exists {
				movies[id] = item.ID
			}
		case "episode":
			if _, exists := series[id]; !exists {
				series[id] = item.ID
			}
		}
	}
	return movies, series
}

func attachLibrary(items []meta.CatalogItem, local map[string]string) []meta.CatalogItem {
	out := make([]meta.CatalogItem, 0, len(items))
	for _, item := range items {
		if mediaID, ok := local[strings.ToLower(item.ImdbID)]; ok {
			item.MediaID = mediaID
			item.InLibrary = true
		}
		out = append(out, item)
	}
	return out
}

func mergeCatalogLibrary(items []meta.CatalogItem, extras []meta.CatalogItem, local map[string]string) []meta.CatalogItem {
	mergedLocal := map[string]string{}
	for k, v := range local {
		mergedLocal[k] = v
	}
	for _, extra := range extras {
		id := strings.ToLower(strings.TrimSpace(extra.ImdbID))
		if id != "" && extra.MediaID != "" {
			if _, ok := mergedLocal[id]; !ok {
				mergedLocal[id] = extra.MediaID
			}
		}
	}
	out := attachLibrary(items, mergedLocal)
	seenImdb := map[string]bool{}
	seenMedia := map[string]bool{}
	byTitle := map[string][]int{}
	for i, item := range out {
		if id := strings.ToLower(strings.TrimSpace(item.ImdbID)); id != "" {
			seenImdb[id] = true
		}
		if item.MediaID != "" {
			seenMedia[item.MediaID] = true
		}
		key := catalogTitleKey(item.Title)
		if key != "" {
			byTitle[key] = append(byTitle[key], i)
		}
	}
	for _, extra := range extras {
		if id := strings.ToLower(strings.TrimSpace(extra.ImdbID)); id != "" && seenImdb[id] {
			continue
		}
		if extra.MediaID != "" && seenMedia[extra.MediaID] {
			continue
		}
		matched := false
		for _, idx := range byTitle[catalogTitleKey(extra.Title)] {
			if !yearsCompatible(out[idx].Year, extra.Year) {
				continue
			}
			if !out[idx].InLibrary {
				out[idx].InLibrary = true
				if extra.MediaID != "" {
					out[idx].MediaID = extra.MediaID
				}
			}
			if extra.MediaID != "" {
				seenMedia[extra.MediaID] = true
			}
			matched = true
			break
		}
		if matched {
			continue
		}
		extra.InLibrary = true
		out = append(out, extra)
		if id := strings.ToLower(strings.TrimSpace(extra.ImdbID)); id != "" {
			seenImdb[id] = true
		}
		if extra.MediaID != "" {
			seenMedia[extra.MediaID] = true
		}
	}
	return out
}

func catalogTitleKey(title string) string {
	t := strings.ToLower(strings.TrimSpace(title))
	t = strings.ReplaceAll(t, ":", " ")
	t = strings.ReplaceAll(t, "-", " ")
	return strings.Join(strings.Fields(t), " ")
}

func yearsCompatible(a, b int) bool {
	return a == 0 || b == 0 || a == b
}

func stripEpisodeLabel(title string) string {
	title = strings.TrimSpace(title)
	if i := strings.Index(title, " · "); i > 0 {
		return strings.TrimSpace(title[:i])
	}
	return title
}

func (s *Server) localCatalogItems(kind string) []meta.CatalogItem {
	items, err := s.store.ListMedia()
	if err != nil {
		return nil
	}
	if kind == "series" {
		seen := map[string]bool{}
		out := []meta.CatalogItem{}
		for _, item := range items {
			if item.Kind != "episode" {
				continue
			}
			info, _ := s.meta.Peek(item.ID)
			show := strings.TrimSpace(item.ShowTitle)
			if show == "" {
				show = item.Title
			}
			show = stripEpisodeLabel(show)
			key := strings.ToLower(strings.TrimSpace(info.ImdbID))
			if key == "" {
				key = "title:" + catalogTitleKey(show)
			}
			if key == "title:" || seen[key] {
				continue
			}
			seen[key] = true
			cat := localAsCatalog(item, info, "series")
			if show != "" {
				cat.Title = show
			}
			cat.Season = 0
			cat.Episode = 0
			out = append(out, cat)
		}
		return out
	}
	out := []meta.CatalogItem{}
	for _, item := range items {
		if item.Kind != "movie" {
			continue
		}
		info, _ := s.meta.Peek(item.ID)
		out = append(out, localAsCatalog(item, info, "movie"))
	}
	return out
}

func localAsCatalog(item store.MediaItem, info meta.Info, kind string) meta.CatalogItem {
	title := item.Title
	if kind == "series" && strings.TrimSpace(item.ShowTitle) != "" {
		title = item.ShowTitle
	}
	year := item.Year
	if info.Year > 0 {
		year = info.Year
	}
	return meta.CatalogItem{
		ID:             item.ID,
		Kind:           kind,
		Title:          title,
		Year:           year,
		Plot:           info.Plot,
		PosterURL:      info.PosterURL,
		BackdropURL:    info.BackdropURL,
		ImdbID:         info.ImdbID,
		MediaID:        item.ID,
		InLibrary:      true,
		Rating:         info.Rating,
		Genres:         info.Genres,
		TMDBID:         info.TMDBID,
		RuntimeMinutes: info.RuntimeMinutes,
		Certification:  info.Certification,
		Country:        info.Country,
		EpisodeCount:   info.EpisodeCount,
	}
}

func catalogAsMedia(item meta.CatalogItem) map[string]any {
	out := map[string]any{
		"id":             item.ID,
		"kind":           item.Kind,
		"title":          item.Title,
		"year":           item.Year,
		"season":         item.Season,
		"episode":        item.Episode,
		"showTitle":      item.ShowTitle,
		"plot":           item.Plot,
		"posterUrl":      item.PosterURL,
		"backdropUrl":    item.BackdropURL,
		"imdbId":         item.ImdbID,
		"rating":         item.Rating,
		"genres":         item.Genres,
		"path":           "",
		"inLibrary":      item.InLibrary,
		"mediaId":        item.MediaID,
		"releasePhase":   item.ReleasePhase,
		"tmdbId":         item.TMDBID,
		"matchStatus":    "matched",
		"runtimeMinutes": item.RuntimeMinutes,
		"certification":  item.Certification,
		"country":        item.Country,
		"genreIds":       item.GenreIDs,
		"positionMs":     item.PositionMs,
		"durationMs":     item.DurationMs,
		"episodeCount":   item.EpisodeCount,
	}
	if item.MatchPercent > 0 {
		out["matchPercent"] = item.MatchPercent
	}
	if item.Genres == nil {
		out["genres"] = []string{}
	}
	if len(item.Cast) > 0 {
		out["cast"] = item.Cast
	}
	if item.Director != nil {
		out["director"] = item.Director
	}
	return out
}

func catalogListAsMedia(items []meta.CatalogItem) []map[string]any {
	out := make([]map[string]any, 0, len(items))
	for _, item := range items {
		out = append(out, catalogAsMedia(item))
	}
	return out
}

func (s *Server) findLocalByIMDB(imdb, kind string) (store.MediaItem, bool) {
	return s.findLocalMedia(imdb, kind, 0, 0)
}

func (s *Server) findLocalMedia(imdb, kind string, season, episode int) (store.MediaItem, bool) {
	imdb = strings.ToLower(strings.TrimSpace(imdb))
	if imdb == "" {
		return store.MediaItem{}, false
	}
	items, err := s.store.ListMedia()
	if err != nil {
		return store.MediaItem{}, false
	}
	wantMovie := kind == "" || kind == "movie"
	var first store.MediaItem
	var hasFirst bool
	for _, item := range items {
		info, ok := s.meta.Peek(item.ID)
		if !ok || !meta.IdentityConfirmed(item.Path, info) || strings.ToLower(info.ImdbID) != imdb {
			continue
		}
		if wantMovie && item.Kind == "movie" {
			return item, true
		}
		if !wantMovie && item.Kind == "episode" {
			if season > 0 || episode > 0 {
				if item.Season == season && item.Episode == episode {
					return item, true
				}
				continue
			}
			if !hasFirst {
				first = item
				hasFirst = true
			}
		}
	}
	if hasFirst {
		return first, true
	}
	return store.MediaItem{}, false
}

func (s *Server) attachEpisodeLibrary(eps []meta.CatalogItem, imdb string) []meta.CatalogItem {
	items, err := s.store.ListMedia()
	if err != nil {
		return eps
	}
	return overlayEpisodeLibrary(eps, imdb, items, func(item store.MediaItem) string {
		info, ok := s.meta.Peek(item.ID)
		if !ok || !meta.IdentityConfirmed(item.Path, info) {
			return ""
		}
		return info.ImdbID
	})
}

func overlayEpisodeLibrary(eps []meta.CatalogItem, imdb string, local []store.MediaItem, imdbOf func(store.MediaItem) string) []meta.CatalogItem {
	imdb = strings.ToLower(strings.TrimSpace(imdb))
	type key struct {
		season, episode int
	}
	files := map[key]store.MediaItem{}
	var extras []store.MediaItem
	for _, item := range local {
		if item.Kind != "episode" {
			continue
		}
		if strings.ToLower(strings.TrimSpace(imdbOf(item))) != imdb {
			continue
		}
		k := key{item.Season, item.Episode}
		if _, exists := files[k]; !exists {
			files[k] = item
		}
		extras = append(extras, item)
	}
	seen := map[key]bool{}
	out := make([]meta.CatalogItem, 0, len(eps)+len(extras))
	for _, ep := range eps {
		k := key{ep.Season, ep.Episode}
		seen[k] = true
		if item, ok := files[k]; ok {
			ep.MediaID = item.ID
			ep.InLibrary = true
		}
		out = append(out, ep)
	}
	for _, item := range extras {
		k := key{item.Season, item.Episode}
		if seen[k] {
			continue
		}
		seen[k] = true
		out = append(out, meta.CatalogItem{
			ID:        "library:" + item.ID,
			Kind:      "episode",
			Title:     item.Title,
			ShowTitle: item.ShowTitle,
			Season:    item.Season,
			Episode:   item.Episode,
			Year:      item.Year,
			ImdbID:    imdb,
			MediaID:   item.ID,
			InLibrary: true,
		})
	}
	return out
}

func (s *Server) handleCatalogStreams(w http.ResponseWriter, r *http.Request) {
	imdb := strings.TrimSpace(r.URL.Query().Get("imdb"))
	if !strings.HasPrefix(imdb, "tt") {
		writeError(w, http.StatusBadRequest, "imdb id required")
		return
	}
	kind := strings.TrimSpace(r.URL.Query().Get("kind"))
	season, _ := strconv.Atoi(r.URL.Query().Get("season"))
	episode, _ := strconv.Atoi(r.URL.Query().Get("episode"))
	title := strings.TrimSpace(r.URL.Query().Get("title"))
	year, _ := strconv.Atoi(r.URL.Query().Get("year"))
	cfg := settings.Load(s.cfg.DataPath)
	webCh := make(chan []streams.Candidate, 1)
	go func() {
		title := title
		year := year
		if title == "" || year == 0 {
			if item, err := s.meta.CatalogTitle(r.Context(), kind, imdb); err == nil {
				if title == "" {
					title = item.Title
				}
				if year == 0 {
					year = item.Year
				}
			}
		}
		webCh <- streams.ListWebCandidates(r.Context(), kind, title, year, season, episode)
	}()
	cands, err := streams.SearchTorrentio(r.Context(), cfg, kind, imdb, season, episode)
	if err != nil {
		writeError(w, http.StatusBadGateway, err.Error())
		return
	}
	cands = streams.CapCandidates(cands, 40)
	if web := <-webCh; len(web) > 0 {
		cands = append(cands, web...)
	}
	out := make([]map[string]any, 0, len(cands))
	for _, c := range cands {
		out = append(out, streams.PublicCandidate(c))
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": out})
}

func (s *Server) handleCatalogSearch(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if q == "" {
		writeError(w, http.StatusBadRequest, "q is required")
		return
	}
	result, err := s.meta.Search(r.Context(), q)
	if err != nil {
		if !s.meta.TMDBEnabled() {
			writeJSON(w, http.StatusOK, map[string]any{
				"movies": []any{},
				"series": []any{},
				"people": []any{},
				"error":  err.Error(),
			})
			return
		}
		writeError(w, http.StatusBadGateway, err.Error())
		return
	}
	localMovies, localSeries := s.imdbIndex()
	origin := strings.TrimRight(publicURL(r, "/"), "/")
	movies := s.withCatalogArt(origin, s.meta.HydrateFromCacheAll(result.Movies), meta.ArtSizeThumb)
	series := s.withCatalogArt(origin, s.meta.HydrateFromCacheAll(result.Series), meta.ArtSizeThumb)
	writeJSON(w, http.StatusOK, map[string]any{
		"movies": s.mediaList(attachLibrary(movies, localMovies)),
		"series": s.mediaList(attachLibrary(series, localSeries)),
		"people": result.People,
	})
}

func (s *Server) handleCatalogTitle(w http.ResponseWriter, r *http.Request) {
	imdb := strings.TrimSpace(r.PathValue("imdb"))
	kind := strings.TrimSpace(r.URL.Query().Get("kind"))
	if kind == "" {
		kind = "movie"
	}
	item, err := s.meta.CatalogTitle(r.Context(), kind, imdb)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	localMovies, localSeries := s.imdbIndex()
	local := localMovies
	if kind == "series" || kind == "episode" {
		local = localSeries
	}
	item = attachLibrary([]meta.CatalogItem{item}, local)[0]
	origin := strings.TrimRight(publicURL(r, "/"), "/")
	item = s.rewriteItemArt(origin, item, meta.ArtSizeDisplay)
	writeJSON(w, http.StatusOK, s.mediaJSON(item))
}

func (s *Server) handleCatalogSimilar(w http.ResponseWriter, r *http.Request) {
	imdb := strings.TrimSpace(r.PathValue("imdb"))
	kind := strings.TrimSpace(r.URL.Query().Get("kind"))
	if kind == "" {
		kind = "movie"
	}
	items, err := s.meta.Similar(r.Context(), kind, imdb)
	if err != nil {
		if !s.meta.TMDBEnabled() {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}
		writeError(w, http.StatusBadGateway, err.Error())
		return
	}
	localMovies, localSeries := s.imdbIndex()
	local := localMovies
	if kind == "series" || kind == "episode" {
		local = localSeries
	}
	origin := strings.TrimRight(publicURL(r, "/"), "/")
	items = s.withCatalogArt(origin, s.meta.HydrateFromCacheAll(items), meta.ArtSizeThumb)
	writeJSON(w, http.StatusOK, map[string]any{
		"items": s.mediaList(attachLibrary(items, local)),
	})
}

func (s *Server) handleCatalogTMDB(w http.ResponseWriter, r *http.Request) {
	kind := strings.TrimSpace(r.PathValue("kind"))
	id, _ := strconv.Atoi(r.PathValue("id"))
	if id == 0 {
		writeError(w, http.StatusBadRequest, "tmdb id required")
		return
	}
	item, err := s.meta.CatalogByTMDB(r.Context(), kind, id)
	if err != nil {
		writeError(w, http.StatusBadGateway, err.Error())
		return
	}
	localMovies, localSeries := s.imdbIndex()
	local := localMovies
	if item.Kind == "series" || kind == "tv" || kind == "series" {
		local = localSeries
	}
	item = attachLibrary([]meta.CatalogItem{item}, local)[0]
	origin := strings.TrimRight(publicURL(r, "/"), "/")
	item = s.rewriteItemArt(origin, item, meta.ArtSizeDisplay)
	writeJSON(w, http.StatusOK, s.mediaJSON(item))
}

func (s *Server) handleCatalogPerson(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.Atoi(r.PathValue("id"))
	person, err := s.meta.PersonCredits(r.Context(), id)
	if err != nil {
		if !s.meta.TMDBEnabled() {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}
		writeError(w, http.StatusBadGateway, err.Error())
		return
	}
	localMovies, localSeries := s.imdbIndex()
	origin := strings.TrimRight(publicURL(r, "/"), "/")
	credits := make([]map[string]any, 0, len(person.Credits))
	for _, c := range person.Credits {
		local := localMovies
		if c.Kind == "series" {
			local = localSeries
		}
		c = attachLibrary([]meta.CatalogItem{c}, local)[0]
		c = s.rewriteItemArt(origin, s.meta.HydrateFromCache(c), meta.ArtSizeThumb)
		credits = append(credits, s.mediaJSON(c))
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"tmdbId":             person.TMDBID,
		"name":               person.Name,
		"profileUrl":         person.ProfileURL,
		"knownForDepartment": person.KnownForDepartment,
		"biography":          person.Biography,
		"birthday":           person.Birthday,
		"placeOfBirth":       person.PlaceOfBirth,
		"credits":            credits,
	})
}

func (s *Server) handleStreamingSettings(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		s.writeStreamingSettings(w)
	case http.MethodPut, http.MethodPost:
		var body map[string]any
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeError(w, http.StatusBadRequest, "invalid json")
			return
		}
		cfg := settings.Load(s.cfg.DataPath)
		if v, ok := body["saveToLibrary"].(bool); ok {
			cfg.SaveToLibrary = v
		}
		if v, ok := body["autoplayNextEpisode"].(bool); ok {
			cfg.AutoplayNextEpisode = v
		}
		if v, ok := body["autoDownloadNextEpisode"].(bool); ok {
			cfg.AutoDownloadNext = v
		}
		if v, ok := body["includeWebStreams"].(bool); ok {
			cfg.IncludeWebStreams = v
		}
		if v, ok := body["prefetchBeforeEndMinutes"].(float64); ok {
			cfg.PrefetchMinutes = int(v)
		}
		if v, ok := body["prefetchCount"].(float64); ok {
			cfg.PrefetchCount = int(v)
		}
		if v, ok := body["continueOverlaySeconds"].(float64); ok {
			cfg.ContinueOverlaySec = int(v)
		}
		if v, ok := body["realDebridToken"].(string); ok {
			cfg.RealDebridToken = strings.TrimSpace(v)
		}
		if v, ok := body["torrentioProviders"]; ok {
			if list := asStringSlice(v); list != nil {
				cfg.TorrentioProviders = list
			}
		}
		if v, ok := body["excludeQualities"]; ok {
			if list := asStringSlice(v); list != nil {
				cfg.ExcludeQualities = list
			}
		}
		if err := settings.Save(s.cfg.DataPath, cfg); err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		s.rdMu.Lock()
		s.rdStatus = nil
		s.rdMu.Unlock()
		s.writeStreamingSettings(w)
	default:
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (s *Server) writeStreamingSettings(w http.ResponseWriter) {
	// Prefetch knobs: when autoDownloadNextEpisode is true, TV may POST
	// /api/v1/playback/prefetch-next near end-of-playback using
	// prefetchBeforeEndMinutes / prefetchCount.
	cfg := settings.Load(s.cfg.DataPath)
	writeJSON(w, http.StatusOK, map[string]any{
		"saveToLibrary":            cfg.SaveToLibrary,
		"autoplayNextEpisode":      cfg.AutoplayNextEpisode,
		"autoDownloadNextEpisode":  cfg.AutoDownloadNext,
		"prefetchBeforeEndMinutes": cfg.PrefetchMinutes,
		"prefetchCount":            cfg.PrefetchCount,
		"continueOverlaySeconds":   cfg.ContinueOverlaySec,
		"includeWebStreams":        cfg.IncludeWebStreams,
		"torrentioProviders":       cfg.TorrentioProviders,
		"excludeQualities":         cfg.ExcludeQualities,
		"realDebridConfigured":     strings.TrimSpace(cfg.RealDebridToken) != "",
		"realDebridTokenMasked":    settings.MaskToken(cfg.RealDebridToken),
	})
}

func asStringSlice(v any) []string {
	switch t := v.(type) {
	case []string:
		return cleanStrings(t)
	case []any:
		out := make([]string, 0, len(t))
		for _, item := range t {
			s, ok := item.(string)
			if !ok {
				continue
			}
			s = strings.TrimSpace(s)
			if s != "" {
				out = append(out, s)
			}
		}
		return out
	case string:
		parts := strings.Split(t, ",")
		return cleanStrings(parts)
	default:
		return nil
	}
}

func cleanStrings(in []string) []string {
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
