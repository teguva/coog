package meta

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
)

func TestTMDBSeasonUsesDiskCache(t *testing.T) {
	e := New(t.TempDir(), "test-key")
	var hits atomic.Int32
	e.client = &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		hits.Add(1)
		if !strings.Contains(req.URL.Path, "/tv/42/season/3") {
			t.Fatalf("unexpected url: %s", req.URL)
		}
		body := `{"episodes":[{"episode_number":1,"name":"Pilot","still_path":"/a.jpg"}]}`
		return &http.Response{
			StatusCode: 200,
			Body:       io.NopCloser(bytes.NewBufferString(body)),
			Header:     make(http.Header),
		}, nil
	})}

	first, err := e.tmdbSeason(context.Background(), 42, 3)
	if err != nil {
		t.Fatal(err)
	}
	if len(first.Episodes) != 1 || first.Episodes[0].Name != "Pilot" {
		t.Fatalf("first: %+v", first)
	}
	second, err := e.tmdbSeason(context.Background(), 42, 3)
	if err != nil {
		t.Fatal(err)
	}
	if len(second.Episodes) != 1 {
		t.Fatalf("second: %+v", second)
	}
	if hits.Load() != 1 {
		t.Fatalf("expected 1 HTTP hit, got %d", hits.Load())
	}
}
