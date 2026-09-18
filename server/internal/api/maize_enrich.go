package api

import (
	"encoding/json"
	"net/http"
	"path/filepath"
	"strings"
	"sync"

	"coog/internal/maize"
	"coog/internal/maize/enrich"
)

var (
	maizeEnrichOnce sync.Once
	maizeEnrichCli  *enrich.Client
)

func (s *Server) maizeEnrichClient() *enrich.Client {
	maizeEnrichOnce.Do(func() {
		cache := filepath.Join(s.cfg.DataPath, "maize-enrich-cache")
		maizeEnrichCli = enrich.NewClient(cache)
	})
	return maizeEnrichCli
}

func (s *Server) handleMaizeMediaEnrich(w http.ResponseWriter, r *http.Request) {
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
		IAFDURL string `json:"iafdUrl"`
		Force   bool   `json:"force"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	iafdURL := strings.TrimSpace(body.IAFDURL)
	if iafdURL == "" {
		existing := maize.ReadSceneMeta(item.Path)
		if existing.Links != nil {
			iafdURL = existing.Links["iafd"]
		}
	}
	if enrich.NormalizeIAFDTitleURL(iafdURL) == "" {
		writeError(w, http.StatusBadRequest, "iafdUrl required (IAFD title.rme link)")
		return
	}
	current := maize.ReadSceneMeta(item.Path)
	if current.Locked && !body.Force {
		writeError(w, http.StatusConflict, "scene metadata is locked (pass force=true to overwrite)")
		return
	}
	folderLabel := filepath.Base(filepath.Dir(item.Path))
	merged, err := enrich.EnrichSceneFromIAFD(s.maizeEnrichClient(), current, iafdURL, folderLabel, body.Force)
	if err != nil {
		writeError(w, http.StatusBadGateway, err.Error())
		return
	}
	if err := maize.WriteSceneMeta(item.Path, merged); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	origin := strings.TrimRight(publicURL(r, "/"), "/")
	writeJSON(w, http.StatusOK, s.maizeView(item, origin, s.maizeProgressIndex()))
}

func (s *Server) handleMaizeActorEnrich(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdultOrAdmin(w, r) {
		return
	}
	slug := strings.TrimSpace(r.PathValue("slug"))
	if slug == "" {
		writeError(w, http.StatusBadRequest, "slug required")
		return
	}
	var body struct {
		Force        bool `json:"force"`
		GalleryLimit int  `json:"galleryLimit"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)

	peopleDir := s.maizePeopleDir()
	name := slug
	view, err := s.maizeActorView(slug, strings.TrimRight(publicURL(r, "/"), "/"))
	if err == nil {
		if n, ok := view["name"].(string); ok && strings.TrimSpace(n) != "" {
			name = n
		}
		if locked, ok := view["locked"].(bool); ok && locked && !body.Force {
			writeError(w, http.StatusConflict, "actor is locked (pass force=true to overwrite)")
			return
		}
	}

	meta, err := enrich.EnrichActor(s.maizeEnrichClient(), peopleDir, name, slug, body.Force, body.GalleryLimit)
	if err != nil {
		writeError(w, http.StatusBadGateway, err.Error())
		return
	}
	out, err := s.maizeActorView(meta.Slug, strings.TrimRight(publicURL(r, "/"), "/"))
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, out)
}
