package subtitles

import "testing"

func TestParseSeasonEpisode(t *testing.T) {
	cases := []struct {
		in      string
		season  int
		episode int
	}{
		{"Show.Name.S02E04.1080p.mkv", 2, 4},
		{"show s2e4 webrip", 2, 4},
		{"Show 2x09", 2, 9},
		{"Movie.2020.1080p", 0, 0},
	}
	for _, tc := range cases {
		s, e := ParseSeasonEpisode(tc.in)
		if s != tc.season || e != tc.episode {
			t.Fatalf("%q: got %d/%d want %d/%d", tc.in, s, e, tc.season, tc.episode)
		}
	}
}

func TestIsSeriesQuery(t *testing.T) {
	if !isSeriesQuery(SearchQuery{Kind: "episode", Season: 1, Episode: 2}) {
		t.Fatal("expected series")
	}
	if !isSeriesQuery(SearchQuery{Season: 1, Episode: 1}) {
		t.Fatal("season/episode implies series")
	}
	if isSeriesQuery(SearchQuery{Kind: "movie"}) {
		t.Fatal("movie should not be series")
	}
}

func TestReleaseScorePrefersSameRelease(t *testing.T) {
	video := "Berlin.S01E03.1080p.WEBRip.x264-GROUP.mkv"
	good := releaseScore(video, "Berlin.S01E03.1080p.WEBRip.x264-GROUP", "berlin.s01e03.srt")
	bad := releaseScore(video, "Berlin.S01E07.720p.HDTV", "berlin.s01e07.srt")
	if good <= bad {
		t.Fatalf("expected matching release to score higher: good=%d bad=%d", good, bad)
	}
}

func TestNormalizeImdb(t *testing.T) {
	if got := normalizeImdb("tt0123456"); got != "123456" {
		t.Fatalf("got %q", got)
	}
	if got := normalizeImdb("tt0539911"); got != "539911" {
		t.Fatalf("got %q", got)
	}
}
