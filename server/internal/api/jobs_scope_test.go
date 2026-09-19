package api

import (
	"testing"

	"coog/internal/jobs"
	"coog/internal/store"
)

func TestJobMatchesEpisodeScope(t *testing.T) {
	ep1 := store.Job{URL: "imdb:tt0159206:1:1", Title: "Sex and the City"}
	ep2 := store.Job{URL: "imdb:tt0159206:1:2", Title: "Sex and the City S01E02"}
	movie := store.Job{URL: "imdb:tt0111161", Title: "The Shawshank Redemption"}

	if !jobMatchesEpisodeScope(ep1, "episode", 1, 1) {
		t.Fatal("same episode should match")
	}
	if jobMatchesEpisodeScope(ep1, "episode", 1, 2) {
		t.Fatal("different episode must not reuse pack hash job")
	}
	if !jobMatchesEpisodeScope(ep2, "series", 1, 2) {
		t.Fatal("series kind with matching S/E should match")
	}
	if !jobMatchesEpisodeScope(movie, "movie", 0, 0) {
		t.Fatal("movie should match bare job")
	}
	if jobMatchesEpisodeScope(ep1, "movie", 0, 0) {
		t.Fatal("episode-scoped job must not satisfy movie enqueue")
	}
}

func TestRestoreRetryTypeKeepsLocalTorrent(t *testing.T) {
	torrent := store.Job{
		Type:     jobs.TypeTorrent,
		URL:      "imdb:tt22084616",
		InfoHash: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
	}
	restoreRetryType(&torrent)
	if torrent.Type != jobs.TypeTorrent {
		t.Fatalf("local torrent retry became %s", torrent.Type)
	}

	httpCatalog := store.Job{Type: jobs.TypeHTTP, URL: "imdb:tt22084616"}
	restoreRetryType(&httpCatalog)
	if httpCatalog.Type != jobs.TypeDebrid {
		t.Fatalf("http catalog retry became %s", httpCatalog.Type)
	}

	web := store.Job{Type: jobs.TypeYTDLP, URL: "https://mfw09.org/e/abc"}
	restoreRetryType(&web)
	if web.Type != jobs.TypeYTDLP {
		t.Fatalf("web retry became %s", web.Type)
	}

	magnet := store.Job{Type: jobs.TypeHTTP, URL: "magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
	restoreRetryType(&magnet)
	if magnet.Type != jobs.TypeTorrent {
		t.Fatalf("magnet retry became %s", magnet.Type)
	}
}
