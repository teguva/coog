package library

import "testing"

func TestParseMovieFolder(t *testing.T) {
	p := ParseRelative("Movies/Dune (2021)/Dune (2021).mkv")
	if p.Kind != "movie" || p.Title != "Dune" || p.Year != 2021 {
		t.Fatalf("got %+v", p)
	}
}

func TestParseSeries(t *testing.T) {
	p := ParseRelative("Series/The Expanse/Season 01/The Expanse S01E02.mkv")
	if p.Kind != "episode" || p.ShowTitle != "The Expanse" || p.Season != 1 || p.Episode != 2 {
		t.Fatalf("got %+v", p)
	}
	p = ParseRelative("Series/Sex and the City S1E3/Season 01/Sex and the City S1E3 S01E03.mp4")
	if p.Kind != "episode" || p.ShowTitle != "Sex and the City" || p.Season != 1 || p.Episode != 3 {
		t.Fatalf("episode-tagged show folder: %+v", p)
	}
}

func TestCleanShowTitle(t *testing.T) {
	if got := CleanShowTitle("Sex and the City S1E3"); got != "Sex and the City" {
		t.Fatalf("got %q", got)
	}
	if got := CleanShowTitle("The Expanse S01E02"); got != "The Expanse" {
		t.Fatalf("got %q", got)
	}
	if got := CleanShowTitle("Show Name"); got != "Show Name" {
		t.Fatalf("got %q", got)
	}
}

func TestParseMaizeNotSeries(t *testing.T) {
	p := ParseRelative("Maize/Studio Scene/video.mp4")
	if p.Kind != "other" || p.ShowTitle != "" {
		t.Fatalf("got %+v", p)
	}
	if p.Title != "Studio Scene" {
		t.Fatalf("title=%q", p.Title)
	}
	p = ParseRelative("Maize/Studio/Season 01/clip.mp4")
	if p.Kind != "other" || p.ShowTitle == "Maize" {
		t.Fatalf("season under maize must not become series: %+v", p)
	}
}

func TestParseSeriesLandedInMovies(t *testing.T) {
	p := ParseRelative("Movies/Clarkson's Farm/Season 01/Clarksons.Farm.S01E01.mkv")
	if p.Kind != "episode" || p.ShowTitle != "Clarkson's Farm" || p.Season != 1 || p.Episode != 1 {
		t.Fatalf("got %+v", p)
	}
	p = ParseRelative("Movies/Show/Show.S02E03.mkv")
	if p.Kind != "episode" || p.Season != 2 || p.Episode != 3 {
		t.Fatalf("got %+v", p)
	}
}

func TestMediaIDStable(t *testing.T) {
	a := MediaID("Movies/Dune (2021)/Dune.mkv")
	b := MediaID("Movies/Dune (2021)/Dune.mkv")
	if a != b || len(a) != 16 {
		t.Fatalf("id=%s", a)
	}
}

func TestContentType(t *testing.T) {
	if ContentType("x.mkv") != "video/x-matroska" {
		t.Fatal(ContentType("x.mkv"))
	}
}
