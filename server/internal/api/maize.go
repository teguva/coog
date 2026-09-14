package api

import (
	"encoding/json"
	"net"
	"net/http"
	"sort"
	"strconv"
	"strings"

	"coog/internal/library"
	"coog/internal/maize"
	"coog/internal/maize/actors"
	"coog/internal/meta"
	"coog/internal/store"
)

func (s *Server) maizeCfg() maize.Config {
	return maize.LoadConfig(s.cfg.DataPath)
}

func adultToken(r *http.Request) string {
	t := strings.TrimSpace(r.Header.Get("X-Coog-Adult-Session"))
	if t == "" {
		t = strings.TrimSpace(r.URL.Query().Get("adult"))
	}
	return t
}

func (s *Server) adultOK(r *http.Request) bool {
	tok := adultToken(r)
	if !s.maizeSessions.Valid(tok) {
		return false
	}
	s.maizeSessions.Touch(tok)
	return true
}

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(strings.TrimSpace(r.RemoteAddr))
	if err != nil {
		return strings.TrimSpace(r.RemoteAddr)
	}
	return host
}

func (s *Server) requireAdult(w http.ResponseWriter, r *http.Request) bool {
	if s.adultOK(r) {
		return true
	}
	writeError(w, http.StatusForbidden, "adult session required")
	return false
}

func (s *Server) handleMaizeStatus(w http.ResponseWriter, r *http.Request) {
	cfg := s.maizeCfg()
	writeJSON(w, http.StatusOK, maize.PublicView(cfg, s.adultOK(r)))
}

func (s *Server) handleMaizeUnlock(w http.ResponseWriter, r *http.Request) {
	cfg := s.maizeCfg()
	if !cfg.PinConfigured() {
		writeError(w, http.StatusBadRequest, "pin not configured")
		return
	}
	ip := clientIP(r)
	if s.maizeSessions.LockedOut(ip) {
		writeError(w, http.StatusTooManyRequests, "too many attempts")
		return
	}
	var body struct {
		Pin string `json:"pin"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid body")
		return
	}
	if !maize.VerifyPIN(cfg, body.Pin) {
		s.maizeSessions.RecordFail(ip)
		writeError(w, http.StatusUnauthorized, "incorrect")
		return
	}
	s.maizeSessions.ClearFails(ip)
	if err := s.maizeSessions.Mount().Unlock(r.Context(), cfg, body.Pin); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	token, err := s.maizeSessions.Create()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	_, _ = s.scanner.ScanMaize(r.Context(), cfg.Bucket)
	if s.interactive != nil {
		s.interactive.SetDesired(true)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":          true,
		"session":     token,
		"idleMinutes": cfg.IdleMinutes,
		"bucket":      cfg.Bucket,
	})
}

func (s *Server) handleMaizeLock(w http.ResponseWriter, r *http.Request) {
	cfg := s.maizeCfg()
	tok := adultToken(r)
	if tok != "" {
		s.maizeSessions.Revoke(tok)
	} else {
		s.maizeSessions.RevokeAll()
	}
	_ = s.maizeSessions.Mount().Lock(r.Context(), cfg)
	if s.interactive != nil && s.maizeSessions.ActiveCount() == 0 {
		s.interactive.SetDesired(false)
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) listMaizeItems() ([]store.MediaItem, maize.Config, error) {
	cfg := s.maizeCfg()
	items, err := s.store.ListMedia()
	if err != nil {
		return nil, cfg, err
	}
	out := make([]store.MediaItem, 0)
	for _, item := range items {
		if library.IsSidecarVideo(item.Path) {
			continue
		}
		if maize.IsMaizeRel(item.RelativePath, cfg.Bucket) || maize.IsMaizePath(s.cfg.LibraryPath, item.Path, cfg.Bucket) {
			out = append(out, item)
		}
	}
	return out, cfg, nil
}

func (s *Server) maizeView(item store.MediaItem, origin string, progress map[string]store.ContinueEntry) map[string]any {
	info, _ := s.meta.Peek(item.ID)
	view := viewItem(item, info, origin)
	sc := maize.ReadSceneMeta(item.Path)
	hasScript := library.HasFunscript(item.Path)
	view["hasFunscript"] = hasScript
	view["hasMeta"] = sc.HasMeta
	view["scriptIntensity"] = maize.ScriptIntensity(item.Path)
	if sc.HasMeta {
		view["matchStatus"] = "matched"
	}
	if sc.Title != "" {
		view["title"] = sc.Title
	}
	if sc.Description != "" {
		view["plot"] = sc.Description
	}
	if sc.Year > 0 {
		view["year"] = sc.Year
	}
	if sc.Rating > 0 {
		view["rating"] = sc.Rating
	}
	if sc.Studio != "" {
		view["studio"] = sc.Studio
	}
	if sc.Director != "" {
		view["director"] = meta.CastMember{Name: sc.Director}
	}
	if len(sc.Tags) > 0 {
		view["genres"] = sc.Tags
		view["tags"] = sc.Tags
	}
	if len(sc.Performers) > 0 {
		cast := make([]meta.CastMember, 0, len(sc.Performers))
		for _, name := range sc.Performers {
			cast = append(cast, meta.CastMember{Name: name})
		}
		view["cast"] = cast
		view["performers"] = sc.Performers
	}
	if len(sc.Aliases) > 0 {
		view["aliases"] = sc.Aliases
	}
	if len(sc.Links) > 0 {
		view["links"] = sc.Links
	}
	if entry, ok := progress[item.ID]; ok {
		view["positionMs"] = entry.PositionMs
		if entry.DurationMs > 0 {
			view["durationMs"] = entry.DurationMs
		}
	}
	return view
}

func (s *Server) maizeProgressIndex() map[string]store.ContinueEntry {
	out := map[string]store.ContinueEntry{}
	entries, err := s.store.ListContinue()
	if err != nil {
		return out
	}
	for _, e := range entries {
		id := strings.TrimSpace(e.MediaID)
		if id == "" || strings.HasPrefix(id, "catalog:") || strings.HasPrefix(id, "continue:") {
			continue
		}
		out[id] = e
	}
	return out
}

func (s *Server) handleMaizeLibrary(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdult(w, r) {
		return
	}
	items, cfg, err := s.listMaizeItems()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	filter := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("filter")))
	sortKey := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("sort")))
	origin := strings.TrimRight(publicURL(r, "/"), "/")
	progress := s.maizeProgressIndex()
	views := make([]map[string]any, 0, len(items))
	for _, item := range items {
		sc := maize.ReadSceneMeta(item.Path)
		hasScript := library.HasFunscript(item.Path)
		switch filter {
		case "scripted":
			if !hasScript {
				continue
			}
		case "meta":
			if !sc.HasMeta {
				continue
			}
		case "nometa", "no-meta":
			if sc.HasMeta {
				continue
			}
		}
		views = append(views, s.maizeView(item, origin, progress))
	}
	sort.SliceStable(views, func(i, j int) bool {
		a, b := views[i], views[j]
		switch sortKey {
		case "duration", "longest":
			return asInt64(a["durationMs"]) > asInt64(b["durationMs"])
		case "shortest":
			return asInt64(a["durationMs"]) < asInt64(b["durationMs"])
		case "intensity", "script":
			return asFloat(a["scriptIntensity"]) > asFloat(b["scriptIntensity"])
		case "recent", "added":
			return asInt64(a["mtimeUnix"]) > asInt64(b["mtimeUnix"])
		default: // title / a-z
			return strings.ToLower(asString(a["title"])) < strings.ToLower(asString(b["title"]))
		}
	})
	writeJSON(w, http.StatusOK, map[string]any{
		"items":  views,
		"bucket": cfg.Bucket,
		"filter": filter,
		"sort":   sortKey,
	})
}

func (s *Server) handleMaizeHome(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdult(w, r) {
		return
	}
	items, cfg, err := s.listMaizeItems()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	origin := strings.TrimRight(publicURL(r, "/"), "/")
	progress := s.maizeProgressIndex()
	byID := map[string]store.MediaItem{}
	for _, item := range items {
		byID[item.ID] = item
	}

	continueItems := make([]map[string]any, 0)
	for _, e := range mustListContinue(s) {
		item, ok := byID[strings.TrimSpace(e.MediaID)]
		if !ok {
			continue
		}
		if e.PositionMs < 30_000 {
			continue
		}
		dur := e.DurationMs
		if dur <= 0 {
			dur = item.DurationMs
		}
		if dur > 0 {
			pct := float64(e.PositionMs) / float64(dur)
			if pct < 0.02 || pct >= 0.92 {
				continue
			}
		}
		continueItems = append(continueItems, s.maizeView(item, origin, progress))
		if len(continueItems) >= 24 {
			break
		}
	}

	recent := append([]store.MediaItem{}, items...)
	sort.SliceStable(recent, func(i, j int) bool {
		return recent[i].MtimeUnix > recent[j].MtimeUnix
	})
	recentViews := make([]map[string]any, 0, 24)
	for _, item := range recent {
		recentViews = append(recentViews, s.maizeView(item, origin, progress))
		if len(recentViews) >= 24 {
			break
		}
	}

	libraryViews := make([]map[string]any, 0, len(items))
	for _, item := range items {
		libraryViews = append(libraryViews, s.maizeView(item, origin, progress))
	}
	sort.SliceStable(libraryViews, func(i, j int) bool {
		return strings.ToLower(asString(libraryViews[i]["title"])) < strings.ToLower(asString(libraryViews[j]["title"]))
	})

	writeJSON(w, http.StatusOK, map[string]any{
		"bucket":         cfg.Bucket,
		"continueWatching": continueItems,
		"recentlyAdded":  recentViews,
		"library":        libraryViews,
	})
}

func mustListContinue(s *Server) []store.ContinueEntry {
	entries, err := s.store.ListContinue()
	if err != nil {
		return nil
	}
	return entries
}

func (s *Server) handleMaizeMediaGet(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdult(w, r) {
		return
	}
	item, err := s.store.GetMedia(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusNotFound, "media not found")
		return
	}
	if !s.isMaizeItem(item.Path, item.RelativePath) {
		writeError(w, http.StatusNotFound, "media not found")
		return
	}
	origin := strings.TrimRight(publicURL(r, "/"), "/")
	view := s.maizeView(item, origin, s.maizeProgressIndex())
	if library.HasFunscript(item.Path) {
		if prev, err := maize.LoadFunscriptPreview(maize.FunscriptPath(item.Path), 0); err == nil {
			view["funscript"] = prev
		}
	}
	writeJSON(w, http.StatusOK, view)
}

func (s *Server) handleMaizeFunscript(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdult(w, r) {
		return
	}
	item, err := s.store.GetMedia(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusNotFound, "media not found")
		return
	}
	if !s.isMaizeItem(item.Path, item.RelativePath) {
		writeError(w, http.StatusNotFound, "media not found")
		return
	}
	path := maize.FunscriptPath(item.Path)
	if path == "" {
		writeError(w, http.StatusNotFound, "no funscript")
		return
	}
	buckets, _ := strconv.Atoi(r.URL.Query().Get("buckets"))
	prev, err := maize.LoadFunscriptPreview(path, buckets)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, prev)
}

func (s *Server) maizePeopleDir() string {
	return s.maizeCfg().ResolvedPeopleDir()
}

func (s *Server) maizeSceneCredits(origin string) ([]actors.SceneCredit, map[string]int) {
	items, _, err := s.listMaizeItems()
	if err != nil {
		return nil, map[string]int{}
	}
	progress := s.maizeProgressIndex()
	counts := map[string]int{}
	credits := make([]actors.SceneCredit, 0, len(items))
	for _, item := range items {
		sc := maize.ReadSceneMeta(item.Path)
		if len(sc.Performers) == 0 {
			continue
		}
		for _, name := range sc.Performers {
			name = strings.TrimSpace(name)
			if name != "" {
				counts[name]++
			}
		}
		credits = append(credits, actors.SceneCredit{
			ID:         item.ID,
			Performers: append([]string{}, sc.Performers...),
			View:       s.maizeView(item, origin, progress),
		})
	}
	return credits, counts
}

func (s *Server) handleMaizeActors(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdult(w, r) {
		return
	}
	_, counts := s.maizeSceneCredits(strings.TrimRight(publicURL(r, "/"), "/"))
	list := actors.BuildActorList(s.maizePeopleDir(), counts)
	writeJSON(w, http.StatusOK, map[string]any{"actors": list})
}

func (s *Server) handleMaizeActorGet(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdult(w, r) {
		return
	}
	slug := strings.TrimSpace(r.PathValue("slug"))
	if slug == "" {
		writeError(w, http.StatusBadRequest, "slug required")
		return
	}
	origin := strings.TrimRight(publicURL(r, "/"), "/")
	peopleDir := s.maizePeopleDir()
	aliases := actors.AliasMap(peopleDir)
	credits, counts := s.maizeSceneCredits(origin)
	matched := actors.ScenesForActor(slug, credits, aliases)

	actorDir := actors.FindActorDir(peopleDir, slug)
	var meta actors.Meta
	if actorDir != "" {
		meta = actors.ReadMeta(actorDir, "")
	} else {
		display := slug
		want := actors.Slugify(slug)
		for name := range counts {
			if actors.CanonicalSlug(name, aliases) == want || actors.Slugify(name) == want {
				display = name
				break
			}
		}
		if len(matched) > 0 {
			for _, p := range matched[0].Performers {
				if actors.CanonicalSlug(p, aliases) == want {
					display = p
					break
				}
			}
		}
		meta = actors.DefaultMeta(display)
	}

	scenes := make([]map[string]any, 0, len(matched))
	for _, c := range matched {
		if c.View != nil {
			scenes = append(scenes, c.View)
		}
	}
	similar := actors.TopSimilar(actors.CostarsForActor(slug, credits, aliases), aliases, 12)

	hasHeadshot := actorDir != "" && actors.HeadshotPath(actorDir) != ""
	galleryCount := meta.GalleryCount
	if actorDir != "" {
		if n := len(actors.GalleryImagePaths(actorDir)); n > 0 {
			galleryCount = n
		}
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"slug":         meta.Slug,
		"name":         meta.Name,
		"sceneCount":   len(matched),
		"hasHeadshot":  hasHeadshot,
		"galleryCount": galleryCount,
		"enriched":     meta.EnrichedAt > 0,
		"bio":          meta.Bio,
		"birthday":     meta.Birthday,
		"birthplace":   meta.Birthplace,
		"ethnicity":    meta.Ethnicity,
		"nationality":  meta.Nationality,
		"hairColor":    meta.HairColor,
		"eyeColor":     meta.EyeColor,
		"height":       meta.Height,
		"weight":       meta.Weight,
		"measurements": meta.Measurements,
		"shoeSize":     meta.ShoeSize,
		"tattoos":      meta.Tattoos,
		"piercings":    meta.Piercings,
		"yearsActive":  meta.YearsActive,
		"aliases":      meta.Aliases,
		"links":        meta.Links,
		"locked":       meta.Locked,
		"scenes":       scenes,
		"similar":      similar,
	})
}

func (s *Server) handleMaizeActorHeadshot(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdult(w, r) {
		return
	}
	slug := strings.TrimSpace(r.PathValue("slug"))
	actorDir := actors.FindActorDir(s.maizePeopleDir(), slug)
	if actorDir == "" {
		writeError(w, http.StatusNotFound, "no headshot")
		return
	}
	path := actors.HeadshotPath(actorDir)
	if path == "" {
		writeError(w, http.StatusNotFound, "no headshot")
		return
	}
	serveImage(w, r, path)
}

func (s *Server) handleMaizeActorGallery(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdult(w, r) {
		return
	}
	slug := strings.TrimSpace(r.PathValue("slug"))
	idx, err := strconv.Atoi(r.PathValue("index"))
	if err != nil || idx < 0 {
		writeError(w, http.StatusBadRequest, "bad index")
		return
	}
	actorDir := actors.FindActorDir(s.maizePeopleDir(), slug)
	if actorDir == "" {
		writeError(w, http.StatusNotFound, "no gallery")
		return
	}
	images := actors.GalleryImagePaths(actorDir)
	if idx >= len(images) {
		writeError(w, http.StatusNotFound, "no gallery image")
		return
	}
	serveImage(w, r, images[idx])
}

// handleMaizeSyncStatus documents the interactive extension point.
func (s *Server) handleMaizeSyncStatus(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdult(w, r) {
		return
	}
	if s.interactive != nil && s.interactive.Enabled() {
		st := s.interactive.Status()
		writeJSON(w, http.StatusOK, map[string]any{
			"supported": true,
			"message":   "Interactive sync via /ws/v1/sync",
			"status":    st,
			"endpoints": map[string]string{
				"funscript": "/api/v1/maize/media/{id}/funscript",
				"load":      "/api/v1/interactive/load",
				"syncWs":    "/ws/v1/sync",
				"engine":    "/api/v1/interactive/engine",
			},
		})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"supported": false,
		"message":   "Install intiface-engine and enable COOG_INTIFACE_ENABLED",
		"endpoints": map[string]string{
			"funscript": "/api/v1/maize/media/{id}/funscript",
		},
	})
}

func (s *Server) handleMaizeSettings(w http.ResponseWriter, r *http.Request) {
	cfg := s.maizeCfg()
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, http.StatusOK, maize.PublicView(cfg, false))
		return
	case http.MethodPut, http.MethodPost:
		var body struct {
			Pin         *string `json:"pin"`
			ClearPin    bool    `json:"clearPin"`
			Bucket      *string `json:"bucket"`
			IdleMinutes *int    `json:"idleMinutes"`
			Encrypted   *bool   `json:"encrypted"`
			MountPoint  *string `json:"mountPoint"`
			LockAll     bool    `json:"lockAll"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeError(w, http.StatusBadRequest, "invalid body")
			return
		}
		if body.ClearPin {
			cfg.PinHash = ""
			cfg.PinSalt = ""
		}
		if body.Pin != nil && strings.TrimSpace(*body.Pin) != "" {
			hash, salt, err := maize.HashPIN(*body.Pin)
			if err != nil {
				writeError(w, http.StatusBadRequest, err.Error())
				return
			}
			cfg.PinHash = hash
			cfg.PinSalt = salt
		}
		if body.Bucket != nil {
			b := strings.TrimSpace(*body.Bucket)
			if b == "" {
				b = "Maize"
			}
			cfg.Bucket = b
		}
		if body.IdleMinutes != nil && *body.IdleMinutes > 0 {
			cfg.IdleMinutes = *body.IdleMinutes
		}
		if body.Encrypted != nil {
			cfg.Encrypted = *body.Encrypted
		}
		if body.MountPoint != nil {
			cfg.MountPoint = strings.TrimSpace(*body.MountPoint)
		}
		if err := maize.SaveConfig(s.cfg.DataPath, cfg); err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		if body.LockAll {
			s.maizeSessions.RevokeAll()
			_ = s.maizeSessions.Mount().Lock(r.Context(), cfg)
			if s.interactive != nil {
				s.interactive.SetDesired(false)
			}
		}
		writeJSON(w, http.StatusOK, maize.PublicView(cfg, false))
		return
	default:
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (s *Server) isMaizeItem(itemPath, rel string) bool {
	cfg := s.maizeCfg()
	return maize.IsMaizeRel(rel, cfg.Bucket) || maize.IsMaizePath(s.cfg.LibraryPath, itemPath, cfg.Bucket)
}

func (s *Server) gateMaizeMedia(w http.ResponseWriter, r *http.Request, itemPath, rel string) bool {
	if !s.isMaizeItem(itemPath, rel) {
		return true
	}
	return s.requireAdult(w, r)
}

func asString(v any) string {
	s, _ := v.(string)
	return s
}

func asInt64(v any) int64 {
	switch n := v.(type) {
	case int64:
		return n
	case int:
		return int64(n)
	case float64:
		return int64(n)
	}
	return 0
}

func asFloat(v any) float64 {
	switch n := v.(type) {
	case float64:
		return n
	case int:
		return float64(n)
	case int64:
		return float64(n)
	}
	return 0
}
