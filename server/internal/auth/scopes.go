package auth

import (
	"context"
	"net/http"
	"strings"
)

type scopeKey struct{}

const (
	ScopeViewer = "viewer"
	ScopeOps    = "ops"
)

// Tokens holds configured API secrets. Empty Auth/Ops/Viewer means that slot is unused.
// Legacy AuthToken grants full access (viewer+ops) for backward compatibility.
type Tokens struct {
	Auth   string // COOG_AUTH_TOKEN — full access
	Ops    string // COOG_OPS_TOKEN — full access
	Viewer string // COOG_VIEWER_TOKEN — viewer only
}

func (t Tokens) Required() bool {
	return strings.TrimSpace(t.Auth) != "" ||
		strings.TrimSpace(t.Ops) != "" ||
		strings.TrimSpace(t.Viewer) != ""
}

// ResolveScopes returns scopes for a presented bearer/query token.
// Open installs (no secrets) get both scopes. Unknown tokens get nil.
func (t Tokens) ResolveScopes(got string) []string {
	got = strings.TrimSpace(got)
	if !t.Required() {
		return []string{ScopeViewer, ScopeOps}
	}
	if got == "" {
		return nil
	}
	auth := strings.TrimSpace(t.Auth)
	ops := strings.TrimSpace(t.Ops)
	viewer := strings.TrimSpace(t.Viewer)
	if auth != "" && secureEqual(got, auth) {
		return []string{ScopeViewer, ScopeOps}
	}
	if ops != "" && secureEqual(got, ops) {
		return []string{ScopeViewer, ScopeOps}
	}
	if viewer != "" && secureEqual(got, viewer) {
		return []string{ScopeViewer}
	}
	return nil
}

func WithScopes(ctx context.Context, scopes []string) context.Context {
	return context.WithValue(ctx, scopeKey{}, scopes)
}

func Scopes(ctx context.Context) []string {
	v, _ := ctx.Value(scopeKey{}).([]string)
	return v
}

func HasScope(ctx context.Context, want string) bool {
	for _, s := range Scopes(ctx) {
		if s == want {
			return true
		}
	}
	return false
}

func HasOps(ctx context.Context) bool {
	return HasScope(ctx, ScopeOps)
}

// RequireOps writes 403 and returns false when the request lacks ops scope.
func RequireOps(w http.ResponseWriter, r *http.Request) bool {
	if HasOps(r.Context()) {
		return true
	}
	w.Header().Set("Content-Type", "application/json")
	http.Error(w, `{"error":"forbidden","detail":"ops scope required"}`, http.StatusForbidden)
	return false
}

// Ops wraps a handler so it requires ops scope after authentication.
func Ops(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !RequireOps(w, r) {
			return
		}
		next(w, r)
	}
}

// NeedsOps reports whether this API call mutates ops-only resources.
func NeedsOps(method, path string) bool {
	method = strings.ToUpper(method)
	path = strings.TrimSuffix(path, "/")
	if !strings.HasPrefix(path, "/api/") && !strings.HasPrefix(path, "/ws/") {
		return false
	}
	switch {
	case method == http.MethodDelete && strings.HasPrefix(path, "/api/v1/library"):
		return true
	case method == http.MethodPost && path == "/api/v1/library/rescan":
		return true
	case method == http.MethodPost && strings.HasSuffix(path, "/ignore"):
		return true
	case method == http.MethodPost && strings.HasSuffix(path, "/rematch"):
		return true
	case (method == http.MethodPut || method == http.MethodPost) && strings.HasPrefix(path, "/api/v1/settings/"):
		return true
	case method == http.MethodPost && strings.HasPrefix(path, "/api/v1/catalog/cache/"):
		return true
	case method == http.MethodPost && (strings.HasSuffix(path, "/cancel") || strings.HasSuffix(path, "/pause") || strings.HasSuffix(path, "/retry")):
		return strings.HasPrefix(path, "/api/v1/jobs/")
	case method == http.MethodPut && path == "/api/v1/taste":
		return true
	case method == http.MethodPost && path == "/api/v1/taste/rebuild":
		return true
	case method == http.MethodPost && path == "/api/v1/maize/upload":
		return true
	case method == http.MethodPut && strings.Contains(path, "/maize/media/") && strings.HasSuffix(path, "/meta"):
		return true
	case method == http.MethodPost && strings.Contains(path, "/maize/media/") && (strings.Contains(path, "/art/") || strings.HasSuffix(path, "/enrich")):
		return true
	case method == http.MethodPut && strings.HasPrefix(path, "/api/v1/maize/actors/"):
		return true
	case method == http.MethodPost && strings.HasPrefix(path, "/api/v1/maize/actors/") && (strings.HasSuffix(path, "/enrich") || strings.HasSuffix(path, "/headshot")):
		return true
	case method == http.MethodPost && strings.HasPrefix(path, "/api/v1/interactive/engine"):
		return true
	case method == http.MethodPost && strings.HasPrefix(path, "/api/v1/interactive/devices"):
		return true
	case method == http.MethodPatch && strings.HasPrefix(path, "/api/v1/interactive/devices"):
		return true
	case method == http.MethodDelete && strings.HasPrefix(path, "/api/v1/interactive/devices"):
		return true
	case method == http.MethodPost && path == "/api/v1/interactive/params":
		return true
	default:
		return false
	}
}
