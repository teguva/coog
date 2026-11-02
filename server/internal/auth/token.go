package auth

import (
	"crypto/subtle"
	"net/http"
	"strings"
)

func secureEqual(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

// Bearer authenticates API requests and attaches scopes to the request context.
// Health checks and static SPA assets are always public.
// If no tokens are configured, all requests are allowed with full scopes (dev mode).
func Bearer(tokens Tokens) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if isPublic(r.URL.Path) {
				next.ServeHTTP(w, r)
				return
			}
			got := extractToken(r)
			scopes := tokens.ResolveScopes(got)
			if tokens.Required() && scopes == nil {
				http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
				return
			}
			if scopes == nil {
				scopes = []string{ScopeViewer, ScopeOps}
			}
			if NeedsOps(r.Method, r.URL.Path) && !hasScope(scopes, ScopeOps) {
				w.Header().Set("Content-Type", "application/json")
				http.Error(w, `{"error":"forbidden","detail":"ops scope required"}`, http.StatusForbidden)
				return
			}
			ctx := WithScopes(r.Context(), scopes)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

func extractToken(r *http.Request) string {
	got := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	got = strings.TrimSpace(got)
	if got == "" {
		got = strings.TrimSpace(r.URL.Query().Get("token"))
	}
	return got
}

func hasScope(scopes []string, want string) bool {
	for _, s := range scopes {
		if s == want {
			return true
		}
	}
	return false
}

func isPublic(path string) bool {
	if path == "/health" || path == "/health/" {
		return true
	}
	if path == "/ws" || strings.HasPrefix(path, "/ws/") {
		return false
	}
	if path == "/api" || strings.HasPrefix(path, "/api/") {
		return false
	}
	return true
}
