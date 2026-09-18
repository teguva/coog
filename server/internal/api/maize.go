package api

import (
	"encoding/json"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"

	"coog/internal/library"
	"coog/internal/maize"
	"coog/internal/maize/actors"
	"coog/internal/meta"
	"coog/internal/probe"
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
	return s.maizeViewOpts(item, origin, progress, nil, true)
}

// maizeViewOpts builds a maize media JSON object.
// detail=true includes videos/funscripts (with intensity) and scriptIntensity.
// byDir, when set, avoids per-item ListMedia sibling scans.
func (s *Server) maizeViewOpts(
	item store.MediaItem,
	origin string,
	progress map[string]store.ContinueEntry,
	byDir map[string][]store.MediaItem,
	detail bool,
) map[string]any {
	info, _ := s.meta.Peek(item.ID)
	view := viewItem(item, info, origin)
	sc := maize.ReadSceneMeta(item.Path)
	scripts := maize.ListFunscriptFiles(item.Path)
	hasScript := len(scripts) > 0
	view["hasFunscript"] = hasScript
	view["hasMeta"] = sc.HasMeta
	if detail && hasScript {
		view["scriptIntensity"] = maize.ScriptIntensity(item.Path)
	}
	poster := probe.SidecarPoster(item.Path)
	backdrop := probe.SidecarBackdrop(item.Path)
	logo := probe.SidecarLogo(item.Path)
	view["hasPoster"] = poster != ""
	view["hasBackdrop"] = backdrop != ""
	view["hasLogo"] = logo != ""
	view["logoUrl"] = origin + "/api/v1/media/" + item.ID + "/logo"
	var artRev int64
	for _, p := range []string{poster, backdrop, logo} {
		if p == "" {
			continue
		}
		if st, err := os.Stat(p); err == nil {
			if t := st.ModTime().Unix(); t > artRev {
				artRev = t
			}
		}
	}
	view["artRev"] = artRev
	if sc.HasMeta {
		view["matchStatus"] = "matched"
	}
	if sc.Title != "" {
		view["title"] = sc.Title
	}
	if sc.Description != "" {
		view["plot"] = sc.Description
		view["description"] = sc.Description
	}
	if sc.Year > 0 {
		view["year"] = sc.Year
	}
	if sc.ReleaseDate != "" {
		view["releaseDate"] = sc.ReleaseDate
		view["releasePrecision"] = maize.ReleasePrecision(sc.ReleaseDate)
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
	if sc.Duration != "" {
		view["duration"] = sc.Duration
	}
	if sc.Locked {
		view["locked"] = true
	}
	if sc.EnrichedAt > 0 {
		view["enrichedAt"] = sc.EnrichedAt
	}

	var sibs []store.MediaItem
	if byDir != nil {
		sibs = byDir[filepath.Clean(filepath.Dir(item.Path))]
	} else if detail {
		sibs = s.mediaSiblings(item)
	} else {
		sibs = []store.MediaItem{item}
	}
	if detail {
		if vids := videoCandidates(sibs); len(vids) > 0 {
			view["videos"] = vids
		}
		if opts := maize.FunscriptOptions(item.Path); len(opts) > 0 {
			view["funscripts"] = opts
			view["hasFunscript"] = true
			for _, scOpt := range opts {
				if scOpt.Preferred {
					view["funscriptName"] = scOpt.Name
					break
				}
			}
		}
	}

	if entry, ok := progress[item.ID]; ok {
		view["positionMs"] = entry.PositionMs
		if entry.DurationMs > 0 {
			view["durationMs"] = entry.DurationMs
		}
	} else {
		for _, sib := range sibs {
			if sib.ID == item.ID {
				continue
			}
			if entry, ok := progress[sib.ID]; ok {
				view["positionMs"] = entry.PositionMs
				if entry.DurationMs > 0 {
					view["durationMs"] = entry.DurationMs
				}
				break
			}
		}
	}
	return view
}

// groupMaizeByDir indexes media items by parent folder (for sibling lookup).
func groupMaizeByDir(items []store.MediaItem) map[string][]store.MediaItem {
	out := map[string][]store.MediaItem{}
	for _, it := range items {
		if library.IsSidecarVideo(it.Path) {
			continue
		}
		dir := filepath.Clean(filepath.Dir(it.Path))
		out[dir] = append(out[dir], it)
	}
	for dir, group := range out {
		sort.SliceStable(group, func(i, j int) bool {
			ri := maize.QualityRank(group[i].Height, group[i].SizeBytes, group[i].Path)
			rj := maize.QualityRank(group[j].Height, group[j].SizeBytes, group[j].Path)
			if ri != rj {
				return ri > rj
			}
			return strings.ToLower(group[i].Path) < strings.ToLower(group[j].Path)
		})
		out[dir] = group
	}
	return out
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
	if !s.requireAdultOrAdmin(w, r) {
		return
	}
	items, cfg, err := s.listMaizeItems()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	byDir := groupMaizeByDir(items)
	items = collapseMaizeByFolder(items)
	filter := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("filter")))
	sortKey := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("sort")))
	origin := strings.TrimRight(publicURL(r, "/"), "/")
	progress := s.maizeProgressIndex()
	needIntensity := sortKey == "intensity" || sortKey == "script"
	views := make([]map[string]any, 0, len(items))
	for _, item := range items {
		sc := maize.ReadSceneMeta(item.Path)
		hasScript := len(maize.ListFunscriptFiles(item.Path)) > 0
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
		view := s.maizeViewOpts(item, origin, progress, byDir, false)
		if needIntensity && hasScript {
			view["scriptIntensity"] = maize.ScriptIntensity(item.Path)
		}
		views = append(views, view)
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
	byDir := groupMaizeByDir(items)
	byID := map[string]store.MediaItem{}
	for _, item := range items {
		byID[item.ID] = item
	}
	collapsed := collapseMaizeByFolder(items)

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
		continueItems = append(continueItems, s.maizeViewOpts(item, origin, progress, byDir, false))
		if len(continueItems) >= 24 {
			break
		}
	}

	recent := append([]store.MediaItem{}, collapsed...)
	sort.SliceStable(recent, func(i, j int) bool {
		return recent[i].MtimeUnix > recent[j].MtimeUnix
	})
	recentViews := make([]map[string]any, 0, 24)
	for _, item := range recent {
		recentViews = append(recentViews, s.maizeViewOpts(item, origin, progress, byDir, false))
		if len(recentViews) >= 24 {
			break
		}
	}

	libraryViews := make([]map[string]any, 0, len(collapsed))
	for _, item := range collapsed {
		libraryViews = append(libraryViews, s.maizeViewOpts(item, origin, progress, byDir, false))
	}
	sort.SliceStable(libraryViews, func(i, j int) bool {
		return strings.ToLower(asString(libraryViews[i]["title"])) < strings.ToLower(asString(libraryViews[j]["title"]))
	})

	writeJSON(w, http.StatusOK, map[string]any{
		"bucket":           cfg.Bucket,
		"continueWatching": continueItems,
		"recentlyAdded":    recentViews,
		"library":          libraryViews,
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
	if !s.requireAdultOrAdmin(w, r) {
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
	view := s.maizeViewOpts(item, origin, s.maizeProgressIndex(), nil, true)
	scriptName := strings.TrimSpace(r.URL.Query().Get("script"))
	path := maize.ResolveFunscriptPath(item.Path, scriptName)
	if path != "" {
		if prev, err := maize.LoadFunscriptPreview(path, 0); err == nil {
			view["funscript"] = prev
			view["funscriptName"] = filepath.Base(path)
		}
	}
	writeJSON(w, http.StatusOK, view)
}

func (s *Server) handleMaizeMediaMeta(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdultOrAdmin(w, r) {
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
	var body struct {
		Title       string  `json:"title"`
		Description string  `json:"description"`
		Studio      string  `json:"studio"`
		Year        int     `json:"year"`
		ReleaseDate string  `json:"releaseDate"`
		Rating      float64 `json:"rating"`
		Performers  any     `json:"performers"`
		Tags        any     `json:"tags"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	existing := maize.ReadSceneMeta(item.Path)
	meta := maize.SceneMeta{
		Title:       body.Title,
		Description: body.Description,
		Studio:      body.Studio,
		Year:        body.Year,
		ReleaseDate: body.ReleaseDate,
		Rating:      body.Rating,
		Performers:  parseMetaStringList(body.Performers),
		Tags:        parseMetaStringList(body.Tags),
		// Preserve fields the admin form does not edit.
		Director:   existing.Director,
		Duration:   existing.Duration,
		Aliases:    existing.Aliases,
		Links:      existing.Links,
		Sources:    existing.Sources,
		EnrichedAt: existing.EnrichedAt,
		Locked:     existing.Locked,
	}
	if body.ReleaseDate != "" {
		if _, _, ok := maize.NormalizeReleaseDate(body.ReleaseDate); !ok {
			writeError(w, http.StatusBadRequest, "invalid releaseDate (use YYYY, YYYY-MM, or YYYY-MM-DD)")
			return
		}
	} else if body.Year > 0 {
		meta.ReleaseDate = strconv.Itoa(body.Year)
	}
	if err := maize.WriteSceneMeta(item.Path, meta); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	origin := strings.TrimRight(publicURL(r, "/"), "/")
	writeJSON(w, http.StatusOK, s.maizeView(item, origin, s.maizeProgressIndex()))
}

// parseMetaStringList accepts a JSON array or comma-separated string (FunPlay parity).
func parseMetaStringList(v any) []string {
	switch x := v.(type) {
	case nil:
		return nil
	case []any:
		out := make([]string, 0, len(x))
		for _, e := range x {
			if s, ok := e.(string); ok {
				out = append(out, s)
			}
		}
		return out
	case []string:
		return x
	case string:
		parts := strings.Split(x, ",")
		out := make([]string, 0, len(parts))
		for _, p := range parts {
			out = append(out, p)
		}
		return out
	default:
		return nil
	}
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
	path := maize.ResolveFunscriptPath(item.Path, r.URL.Query().Get("script"))
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
	writeJSON(w, http.StatusOK, map[string]any{
		"durationMs":  prev.DurationMs,
		"actionCount": prev.ActionCount,
		"intensity":   prev.Intensity,
		"points":      prev.Points,
		"name":        filepath.Base(path),
	})
}

func (s *Server) maizePeopleDir() string {
	return s.maizeCfg().ResolvedPeopleDir()
}

func (s *Server) maizeSceneCredits(origin string) ([]actors.SceneCredit, map[string]int) {
	items, _, err := s.listMaizeItems()
	if err != nil {
		return nil, map[string]int{}
	}
	items = collapseMaizeByFolder(items)
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
			View:       s.maizeViewOpts(item, origin, progress, nil, false),
		})
	}
	return credits, counts
}

func (s *Server) handleMaizeActorHeadshot(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdultOrAdmin(w, r) {
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
	if !s.requireAdultOrAdmin(w, r) {
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
	return s.requireAdultOrAdmin(w, r)
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
