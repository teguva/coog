package streams

import (
	"strings"
	"testing"
)

func TestParse1MoviesSearchAndPick(t *testing.T) {
	html := `
<a href="/watch-movie/the-matrix-1999" title="The Matrix">The Matrix</a>
<a href="/watch-series/the-matrix-resurrections" title="Other">nope</a>
<a href="/watch-movie/drawn-together-the-movie">Drawn Together The Movie</a>
`
	hits := parse1MoviesSearch(html, "Drawn Together", 0)
	if len(hits) < 1 {
		t.Fatalf("hits %v", hits)
	}
	got := pickWebMatch(hits, "movie", "Drawn Together", 0)
	if !strings.Contains(got.url, "drawn-together") {
		t.Fatalf("pick %+v", got)
	}
}

func TestEpisodePageURL(t *testing.T) {
	u := episodePageURL("https://1movies.stream/watch-series/slow-horses/", 1, 3)
	if u != "https://1movies.stream/episode/slow-horses/s01-e03/" {
		t.Fatalf("got %s", u)
	}
}

func TestParsePlayersJSON(t *testing.T) {
	rows, err := parsePlayersJSON(`[{"name":"Vidhide","link":"https://vidhide.com/e/abc"},{"name":"","link":""}]`)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0].link == "" {
		t.Fatalf("%+v", rows)
	}
	rows, err = parsePlayersJSON(`[{"name":"1Movies","link":"https://gn1r5n.org/e/abc"},{"name":"Vidmoly","link":"https://kaembed.net/embed-x.html"},{"name":"Videasy","link":"https://player.videasy.net/movie/1"}]`)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 3 {
		t.Fatalf("want 3 servers, got %+v", rows)
	}
	if _, err := parsePlayersJSON(`{"error":"Invalid payload"}`); err == nil {
		t.Fatal("expected error payload failure")
	}
}

func TestScrapeMediaURL(t *testing.T) {
	html := `jwplayer("vplayer").setup({ sources: [{ file: 'https://cdn.example/hls2/ep.m3u8?t=abc' }] });`
	got := ScrapeMediaURL(html)
	if !strings.Contains(got, "ep.m3u8") {
		t.Fatalf("got %q", got)
	}
	escaped := `{"file":"https:\/\/cdn.example\/a\/master.m3u8?x=1"}`
	if !strings.Contains(ScrapeMediaURL(escaped), "master.m3u8") {
		t.Fatal("escaped")
	}
	if ScrapeMediaURL("<html>Byse Frontend</html>") != "" {
		t.Fatal("empty spa")
	}
}

func TestLooksLikeBysePlayer(t *testing.T) {
	if !looksLikeBysePlayer("<title>Byse Frontend</title>", "https://mfw09.org/e/abc") {
		t.Fatal("byse")
	}
	if looksLikeBysePlayer(`jwplayer({file:"https://x/a.m3u8"})`, "https://vidhide.com/e/abc") {
		t.Fatal("jw")
	}
}

func TestEmbedReferer(t *testing.T) {
	if got := EmbedReferer("https://kaembed.net/embed-abc.html?x=1"); got != "https://kaembed.net/" {
		t.Fatalf("got %s", got)
	}
}

func TestDetectWebQuality(t *testing.T) {
	if DetectWebQuality("Server HD") != "1080p" {
		t.Fatal("hd")
	}
	if DetectWebQuality("CAM print") != "SD" {
		t.Fatal("cam")
	}
	if DetectWebQuality("4K WEB") != "2160p" {
		t.Fatal("4k")
	}
}

func TestMagnetWithTrackers(t *testing.T) {
	m := MagnetWithTrackers("0123456789abcdef0123456789abcdef01234567")
	if !strings.Contains(m, "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567") {
		t.Fatalf("magnet %s", m)
	}
	if !strings.Contains(m, "tr=") {
		t.Fatal("trackers")
	}
}

func TestShouldFallbackLocal(t *testing.T) {
	if !ShouldFallbackLocal(formatRDHTTPError(451, "/torrents/addMagnet", []byte(`{"error":"infringing_file"}`))) {
		t.Fatal("infringing")
	}
	if ShouldFallbackLocal(formatRDHTTPError(401, "/user", []byte(`{"error":"bad_token"}`))) {
		t.Fatal("token should not fallback")
	}
}
