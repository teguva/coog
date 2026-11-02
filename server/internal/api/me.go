package api

import (
	"net/http"

	"coog/internal/auth"
	"coog/internal/config"
)

func (s *Server) handleMe(w http.ResponseWriter, r *http.Request) {
	scopes := auth.Scopes(r.Context())
	if scopes == nil {
		scopes = []string{}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"scopes":  scopes,
		"ops":     auth.HasOps(r.Context()),
		"viewer":  auth.HasScope(r.Context(), auth.ScopeViewer),
		"version": config.Version,
	})
}
