package api

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"path/filepath"
	"strings"

	"coog/internal/maize/actors"
)

const maizeActorUploadMax = 20 << 20

func (s *Server) handleMaizeActors(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdultOrAdmin(w, r) {
		return
	}
	_, counts := s.maizeSceneCredits(strings.TrimRight(publicURL(r, "/"), "/"))
	list := actors.BuildActorList(s.maizePeopleDir(), counts)
	writeJSON(w, http.StatusOK, map[string]any{"actors": list})
}

func (s *Server) handleMaizeActorGet(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdultOrAdmin(w, r) {
		return
	}
	slug := strings.TrimSpace(r.PathValue("slug"))
	if slug == "" {
		writeError(w, http.StatusBadRequest, "slug required")
		return
	}
	view, err := s.maizeActorView(slug, strings.TrimRight(publicURL(r, "/"), "/"))
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, view)
}

func (s *Server) handleMaizeActorPut(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdultOrAdmin(w, r) {
		return
	}
	slug := strings.TrimSpace(r.PathValue("slug"))
	if slug == "" {
		writeError(w, http.StatusBadRequest, "slug required")
		return
	}
	var body struct {
		Name         string            `json:"name"`
		Bio          string            `json:"bio"`
		Birthday     string            `json:"birthday"`
		Birthplace   string            `json:"birthplace"`
		Ethnicity    string            `json:"ethnicity"`
		Height       string            `json:"height"`
		Measurements string            `json:"measurements"`
		YearsActive  string            `json:"yearsActive"`
		Aliases      any               `json:"aliases"`
		Links        map[string]string `json:"links"`
		Locked       *bool             `json:"locked"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	peopleDir := s.maizePeopleDir()
	actorDir, meta, err := actors.EnsureActorDir(peopleDir, slug, body.Name)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if body.Name != "" {
		meta.Name = body.Name
	}
	meta.Bio = body.Bio
	meta.Birthday = body.Birthday
	meta.Birthplace = body.Birthplace
	meta.Ethnicity = body.Ethnicity
	meta.Height = body.Height
	meta.Measurements = body.Measurements
	meta.YearsActive = body.YearsActive
	if body.Aliases != nil {
		meta.Aliases = parseMetaStringList(body.Aliases)
	}
	if body.Links != nil {
		links := map[string]string{}
		if meta.Links != nil {
			for k, v := range meta.Links {
				links[k] = v
			}
		}
		for _, key := range []string{"iafd", "babehub", "pornpics", "pornhub"} {
			if v, ok := body.Links[key]; ok {
				v = strings.TrimSpace(v)
				if v == "" {
					delete(links, key)
				} else {
					links[key] = v
				}
			}
		}
		meta.Links = links
	}
	if body.Locked != nil {
		meta.Locked = *body.Locked
	} else {
		// Manual edit implies lock (FunPlay warm skip), unless explicitly unlocked.
		meta.Locked = true
	}
	if err := actors.WriteMeta(actorDir, meta); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	view, err := s.maizeActorView(meta.Slug, strings.TrimRight(publicURL(r, "/"), "/"))
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, view)
}

func (s *Server) handleMaizeActorHeadshotUpload(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdultOrAdmin(w, r) {
		return
	}
	slug := strings.TrimSpace(r.PathValue("slug"))
	if slug == "" {
		writeError(w, http.StatusBadRequest, "slug required")
		return
	}
	if err := r.ParseMultipartForm(maizeActorUploadMax); err != nil {
		writeError(w, http.StatusBadRequest, "invalid multipart form")
		return
	}
	file, hdr, err := r.FormFile("file")
	if err != nil {
		writeError(w, http.StatusBadRequest, "file required")
		return
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, maizeActorUploadMax+1))
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if len(data) > maizeActorUploadMax {
		writeError(w, http.StatusRequestEntityTooLarge, "file too large")
		return
	}
	ext := strings.ToLower(filepath.Ext(hdr.Filename))
	switch ext {
	case ".jpg", ".jpeg", ".png", ".webp":
		if ext == ".jpeg" {
			ext = ".jpg"
		}
	default:
		ct := strings.ToLower(hdr.Header.Get("Content-Type"))
		switch {
		case strings.Contains(ct, "jpeg"):
			ext = ".jpg"
		case strings.Contains(ct, "png"):
			ext = ".png"
		case strings.Contains(ct, "webp"):
			ext = ".webp"
		default:
			writeError(w, http.StatusBadRequest, "unsupported image type")
			return
		}
	}
	name := strings.TrimSpace(r.FormValue("name"))
	actorDir, _, err := actors.EnsureActorDir(s.maizePeopleDir(), slug, name)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if _, err := actors.SaveHeadshot(actorDir, data, ext); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	view, err := s.maizeActorView(slug, strings.TrimRight(publicURL(r, "/"), "/"))
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, view)
}

func (s *Server) maizeActorView(slug, origin string) (map[string]any, error) {
	slug = strings.TrimSpace(slug)
	if slug == "" {
		return nil, errActorNotFound
	}
	peopleDir := s.maizePeopleDir()
	aliases := actors.AliasMap(peopleDir)
	credits, counts := s.maizeSceneCredits(origin)
	want := actors.Slugify(slug)
	matched := actors.ScenesForActor(slug, credits, aliases)

	actorDir := actors.FindActorDir(peopleDir, slug)
	var meta actors.Meta
	if actorDir != "" {
		meta = actors.ReadMeta(actorDir, "")
	} else {
		display := slug
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

	out := map[string]any{
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
	}
	if hasHeadshot {
		out["headshotUrl"] = origin + "/api/v1/maize/actors/" + meta.Slug + "/headshot"
	}
	return out, nil
}

var errActorNotFound = errors.New("actor not found")
