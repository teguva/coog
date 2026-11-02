package auth

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestBearerAdminHTMLPublic(t *testing.T) {
	h := Bearer(Tokens{Auth: "secret"})(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	cases := []struct {
		path string
		want int
	}{
		{"/", http.StatusOK},
		{"/assets/index.js", http.StatusOK},
		{"/health", http.StatusOK},
		{"/api/v1/library", http.StatusUnauthorized},
		{"/ws", http.StatusUnauthorized},
	}
	for _, tc := range cases {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, tc.path, nil))
		if rec.Code != tc.want {
			t.Fatalf("%s: got %d want %d", tc.path, rec.Code, tc.want)
		}
	}
}

func TestBearerAcceptsQueryToken(t *testing.T) {
	h := Bearer(Tokens{Auth: "secret"})(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/ws?token=secret", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("got %d", rec.Code)
	}
}

func TestViewerForbiddenOnOpsRoutes(t *testing.T) {
	h := Bearer(Tokens{Viewer: "view", Ops: "ops"})(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodDelete, "/api/v1/library/abc", nil)
	req.Header.Set("Authorization", "Bearer view")
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("viewer delete: got %d want 403", rec.Code)
	}

	rec = httptest.NewRecorder()
	req = httptest.NewRequest(http.MethodGet, "/api/v1/library", nil)
	req.Header.Set("Authorization", "Bearer view")
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("viewer list: got %d want 200", rec.Code)
	}

	rec = httptest.NewRecorder()
	req = httptest.NewRequest(http.MethodDelete, "/api/v1/library/abc", nil)
	req.Header.Set("Authorization", "Bearer ops")
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("ops delete: got %d want 200", rec.Code)
	}
}

func TestResolveScopes(t *testing.T) {
	tok := Tokens{Auth: "legacy", Ops: "ops", Viewer: "view"}
	if got := tok.ResolveScopes("legacy"); !hasScope(got, ScopeOps) || !hasScope(got, ScopeViewer) {
		t.Fatalf("legacy: %v", got)
	}
	if got := tok.ResolveScopes("view"); hasScope(got, ScopeOps) || !hasScope(got, ScopeViewer) {
		t.Fatalf("viewer: %v", got)
	}
	if got := tok.ResolveScopes("nope"); got != nil {
		t.Fatalf("unknown: %v", got)
	}
	open := Tokens{}
	if got := open.ResolveScopes(""); !hasScope(got, ScopeOps) {
		t.Fatalf("open: %v", got)
	}
	_ = json.Marshal
}
