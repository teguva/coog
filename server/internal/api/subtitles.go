package api

import (
	"encoding/json"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"coog/internal/subtitles"
)

func (s *Server) subClient() *subtitles.Client {
	return subtitles.NewClient(s.cfg.DataPath).WithAVTools(s.cfg.FFmpeg, s.cfg.FFprobe)
}

func (s *Server) handleSubtitleSettings(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, http.StatusOK, map[string]any{
			"ok":       true,
			"settings": subtitles.Load(s.cfg.DataPath).Public(),
		})
	case http.MethodPut, http.MethodPost:
		var body map[string]any
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeError(w, http.StatusBadRequest, "invalid json")
			return
		}
		cfg := subtitles.Load(s.cfg.DataPath)
		if v, ok := body["enabled"].(bool); ok {
			cfg.Enabled = v
		}
		if v, ok := body["autoLoad"].(bool); ok {
			cfg.AutoLoad = v
		}
		if v, ok := body["autoSync"].(bool); ok {
			cfg.AutoSync = v
		}
		if v, ok := body["preferEmbedded"].(bool); ok {
			cfg.PreferEmbedded = v
		}
		if v, ok := body["username"].(string); ok {
			cfg.Username = strings.TrimSpace(v)
		}
		if v, ok := body["userAgent"].(string); ok {
			if t := strings.TrimSpace(v); t != "" {
				cfg.UserAgent = t
			}
		}
		if v, ok := body["apiKey"].(string); ok {
			key := strings.TrimSpace(v)
			if key != "" && key != "********" && !strings.Contains(key, "…") {
				cfg.APIKey = key
			}
		}
		if clear, ok := body["clearApiKey"].(bool); ok && clear {
			cfg.APIKey = ""
		}
		if v, ok := body["password"].(string); ok {
			pass := strings.TrimSpace(v)
			if pass != "" && pass != "********" {
				cfg.Password = pass
			}
		}
		if clear, ok := body["clearPassword"].(bool); ok && clear {
			cfg.Password = ""
		}
		if langs, ok := body["languages"].([]any); ok {
			out := make([]string, 0, len(langs))
			for _, item := range langs {
				if s, ok := item.(string); ok {
					if t := strings.TrimSpace(s); t != "" {
						out = append(out, t)
					}
				}
			}
			if len(out) > 0 {
				cfg.Languages = out
			}
		} else if s, ok := body["languages"].(string); ok {
			parts := strings.FieldsFunc(s, func(r rune) bool {
				return r == ',' || r == ';' || r == ' '
			})
			out := make([]string, 0, len(parts))
			for _, p := range parts {
				if t := strings.TrimSpace(p); t != "" {
					out = append(out, t)
				}
			}
			if len(out) > 0 {
				cfg.Languages = out
			}
		}
		if err := subtitles.Save(s.cfg.DataPath, cfg); err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok":       true,
			"settings": subtitles.Load(s.cfg.DataPath).Public(),
		})
	default:
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (s *Server) handleSubtitlesList(w http.ResponseWriter, r *http.Request) {
	tracks := []subtitles.Track{{
		ID:     "off",
		Source: "none",
		Label:  "Off",
	}}
	mediaID := strings.TrimSpace(r.URL.Query().Get("mediaId"))
	var videoPath string
	if mediaID != "" {
		if item, err := s.store.GetMedia(mediaID); err == nil {
			videoPath = item.Path
		}
	}
	seen := map[string]bool{}
	if videoPath != "" {
		for _, row := range subtitles.FindSidecars(videoPath) {
			if row.Path != "" && seen[row.Path] {
				continue
			}
			if row.Path != "" {
				seen[row.Path] = true
			}
			tracks = append(tracks, row)
		}
		// Text streams inside the container (SubRip/ASS/…) — usable for playback + autosync.
		for _, row := range subtitles.FindEmbedded(s.cfg.FFprobe, videoPath) {
			if row.ID != "" && seen[row.ID] {
				continue
			}
			if row.ID != "" {
				seen[row.ID] = true
			}
			tracks = append(tracks, row)
		}
	}

	cfg := subtitles.Load(s.cfg.DataPath)
	var note string
	includeOS := r.URL.Query().Get("includeOpenSubtitles") != "0"
	if includeOS {
		if !cfg.Enabled {
			note = "OpenSubtitles disabled in settings"
		} else if strings.TrimSpace(cfg.APIKey) == "" {
			note = "Add an OpenSubtitles API key in Settings → Subtitles"
		} else {
			q := subtitles.SearchQuery{
				ImdbID: r.URL.Query().Get("imdbId"),
				TmdbID: r.URL.Query().Get("tmdbId"),
				Query:  r.URL.Query().Get("query"),
				Kind:   r.URL.Query().Get("kind"),
			}
			if v := r.URL.Query().Get("season"); v != "" {
				q.Season, _ = strconv.Atoi(v)
			}
			if v := r.URL.Query().Get("episode"); v != "" {
				q.Episode, _ = strconv.Atoi(v)
			}
			if q.Kind == "" && (q.Season > 0 || q.Episode > 0) {
				q.Kind = "series"
			}
			if videoPath != "" {
				q.FileName = filepath.Base(videoPath)
				// Hash helps OS return the release that actually matches this file
				// (timing/sync). Cheap to compute; skip silently if unreadable.
				q.MovieHash = subtitles.MovieHash(videoPath)
			}
			rows, err := s.subClient().Search(q)
			if err != nil {
				note = err.Error()
			} else {
				tracks = append(tracks, rows...)
			}
		}
	}

	out := map[string]any{
		"ok":     true,
		"tracks": tracks,
		"settings": map[string]any{
			"autoLoad":       cfg.AutoLoad,
			"autoSync":       cfg.AutoSync,
			"preferEmbedded": cfg.PreferEmbedded,
			"languages":      cfg.Languages,
			"enabled":        cfg.Enabled,
			"hasApiKey":      strings.TrimSpace(cfg.APIKey) != "",
		},
	}
	if note != "" {
		out["error"] = note
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleSubtitleFile(w http.ResponseWriter, r *http.Request) {
	id, _ := url.QueryUnescape(strings.TrimSpace(r.URL.Query().Get("id")))
	if id == "" {
		writeError(w, http.StatusBadRequest, "id required")
		return
	}
	path, ctype, err := s.subClient().ResolveFile(id)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	f, err := os.Open(path)
	if err != nil {
		writeError(w, http.StatusNotFound, "subtitle file not found")
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	w.Header().Set("Content-Type", ctype)
	w.Header().Set("Accept-Ranges", "bytes")
	http.ServeContent(w, r, st.Name(), st.ModTime(), f)
}
