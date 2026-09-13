package api

import (
	"encoding/json"
	"net/http"
	"strings"

	"coog/internal/meta"
	"coog/internal/store"
	"coog/internal/taste"
)

const householdTasteID = "household"

func (s *Server) tasteProfile() taste.Profile {
	items, err := s.store.ListMedia()
	if err != nil {
		items = nil
	}
	cont, err := s.store.ListContinue()
	if err != nil {
		cont = nil
	}
	var mediaUpdated, contUpdated int64
	for _, item := range items {
		if item.UpdatedAt > mediaUpdated {
			mediaUpdated = item.UpdatedAt
		}
	}
	for _, row := range cont {
		if row.UpdatedAt > contUpdated {
			contUpdated = row.UpdatedAt
		}
	}
	fp := taste.Fingerprint(len(items), mediaUpdated, len(cont), contUpdated)
	s.tasteMu.Lock()
	defer s.tasteMu.Unlock()
	if s.tasteProf != nil && s.tasteFP == fp {
		return *s.tasteProf
	}
	if row, err := s.store.GetTasteProfile(householdTasteID); err == nil && row.Fingerprint == fp {
		if p, err := taste.Decode(row.Payload); err == nil {
			s.tasteProf = &p
			s.tasteFP = fp
			return p
		}
	}
	p := taste.Build(s.tasteSignals(items, cont))
	if payload, err := p.Encode(); err == nil {
		_ = s.store.PutTasteProfile(store.TasteRow{
			ID:          householdTasteID,
			Fingerprint: fp,
			Titles:      p.Titles,
			Payload:     payload,
		})
	}
	s.tasteProf = &p
	s.tasteFP = fp
	return p
}

func (s *Server) tasteSignals(items []store.MediaItem, cont []store.ContinueEntry) []taste.Signal {
	out := make([]taste.Signal, 0, 64)
	seen := map[string]bool{}
	add := func(imdb string, info meta.Info, kind string, weight float64) {
		imdb = strings.ToLower(strings.TrimSpace(imdb))
		if imdb == "" || seen[imdb] {
			return
		}
		if len(info.Genres) == 0 && info.Year == 0 && info.Country == "" {
			return
		}
		seen[imdb] = true
		if kind == "episode" {
			kind = "series"
		}
		out = append(out, taste.Signal{
			ImdbID:  imdb,
			Kind:    kind,
			Genres:  append([]string(nil), info.Genres...),
			Year:    info.Year,
			Country: info.Country,
			Weight:  weight,
		})
	}
	for _, item := range items {
		if isTrailerFile(item) {
			continue
		}
		if s.isMaizeItem(item.Path, item.RelativePath) {
			continue
		}
		info, ok := s.meta.Peek(item.ID)
		if !ok || !meta.IdentityConfirmed(item.Path, info) {
			continue
		}
		add(info.ImdbID, info, item.Kind, 1)
	}
	for _, row := range cont {
		if s.continueEntryIsMaize(row) {
			continue
		}
		imdb := strings.ToLower(strings.TrimSpace(row.ImdbID))
		if imdb == "" || seen[imdb] {
			continue
		}
		info := meta.Info{Year: row.Year}
		if local, ok := s.findLocalByIMDB(imdb, row.Kind); ok {
			if peeked, ok := s.meta.Peek(local.ID); ok {
				info = peeked
			}
		}
		add(imdb, info, row.Kind, 0.55)
	}
	return out
}

func isTrailerFile(item store.MediaItem) bool {
	name := strings.ToLower(item.Title)
	path := strings.ToLower(item.Path)
	return name == "trailer" || strings.Contains(path, "/trailer") || strings.Contains(path, "\\trailer")
}

func (s *Server) tasteConfig() taste.Config {
	return taste.LoadConfig(s.cfg.DataPath)
}

func (s *Server) withMatch(item meta.CatalogItem) meta.CatalogItem {
	p := s.tasteProfile()
	cfg := s.tasteConfig()
	item.MatchPercent = p.Score(cfg, item.Genres, item.GenreIDs, item.Year, item.Country, item.Rating)
	return item
}

func (s *Server) withMatchAll(items []meta.CatalogItem) []meta.CatalogItem {
	if len(items) == 0 {
		return items
	}
	p := s.tasteProfile()
	cfg := s.tasteConfig()
	for i := range items {
		items[i].MatchPercent = p.Score(cfg, items[i].Genres, items[i].GenreIDs, items[i].Year, items[i].Country, items[i].Rating)
	}
	return items
}

func (s *Server) mediaJSON(item meta.CatalogItem) map[string]any {
	return catalogAsMedia(s.withMatch(item))
}

func (s *Server) mediaList(items []meta.CatalogItem) []map[string]any {
	return catalogListAsMedia(s.withMatchAll(items))
}

// tasteView builds the admin-facing snapshot of the household taste profile.
func (s *Server) tasteView() map[string]any {
	p := s.tasteProfile()
	cfg := s.tasteConfig()
	row, _ := s.store.GetTasteProfile(householdTasteID)
	genres := p.TopGenres(8)
	countries := p.TopCountries(6)
	meanYear := 0
	if p.MeanYear > 0 {
		meanYear = int(p.MeanYear + 0.5)
	}
	return map[string]any{
		"titles":       p.Titles,
		"coldStart":    p.ColdStart(cfg),
		"meanYear":     meanYear,
		"topGenres":    genres,
		"topCountries": countries,
		"fingerprint":  row.Fingerprint,
		"updatedAt":    row.UpdatedAt,
		"config": map[string]any{
			"enabled":        cfg.Enabled,
			"personalWeight": cfg.PersonalWeight,
			"minTitles":      cfg.MinTitles,
		},
	}
}

func (s *Server) handleTaste(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.tasteView())
}

func (s *Server) handleTasteUpdate(w http.ResponseWriter, r *http.Request) {
	cur := s.tasteConfig()
	var body struct {
		Enabled        *bool    `json:"enabled"`
		PersonalWeight *float64 `json:"personalWeight"`
		MinTitles      *int     `json:"minTitles"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if body.Enabled != nil {
		cur.Enabled = *body.Enabled
	}
	if body.PersonalWeight != nil {
		cur.PersonalWeight = *body.PersonalWeight
	}
	if body.MinTitles != nil {
		cur.MinTitles = *body.MinTitles
	}
	if err := taste.SaveConfig(s.cfg.DataPath, cur); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, s.tasteView())
}

func (s *Server) handleTasteRebuild(w http.ResponseWriter, r *http.Request) {
	s.tasteMu.Lock()
	s.tasteProf = nil
	s.tasteFP = ""
	s.tasteMu.Unlock()
	writeJSON(w, http.StatusOK, s.tasteView())
}
