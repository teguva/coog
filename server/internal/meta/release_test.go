package meta

import (
	"testing"
	"time"
)

func TestClassifyMovieReleasePhase(t *testing.T) {
	today := time.Date(2026, 9, 10, 0, 0, 0, 0, time.UTC)
	cases := []struct {
		name                       string
		status, primary, thea, dig string
		want                       string
	}{
		{"future theatrical", "", "2026-12-01", "2026-12-25", "", "coming_soon"},
		{"in theatres waiting digital", "", "2026-08-01", "2026-08-01", "2026-10-01", "theatrical"},
		{"digital out", "", "2026-01-01", "2026-01-01", "2026-02-01", "released"},
		{"90 day window", "", "", "2026-08-01", "", "theatrical"},
		{"past window no digital", "", "", "2026-01-01", "", "released"},
		{"primary future no theatrical", "", "2026-12-01", "", "", "coming_soon"},
		{"primary past no theatrical", "", "2026-01-01", "", "", "released"},
		{"preproduction status", "Post Production", "", "", "", "coming_soon"},
		{"released status", "Released", "", "", "", "released"},
	}
	for _, tc := range cases {
		got := ClassifyMovieReleasePhase(tc.status, tc.primary, tc.thea, tc.dig, today)
		if got != tc.want {
			t.Errorf("%s: got %q want %q", tc.name, got, tc.want)
		}
	}
}

func TestPreferredReleaseDateComingSoon(t *testing.T) {
	today := time.Date(2026, 9, 10, 0, 0, 0, 0, time.UTC)
	got := PreferredReleaseDate("", "2026-12-01", "2026-12-25", "", today)
	if got != "2026-12-25" {
		t.Fatalf("got %q", got)
	}
	got = PreferredReleaseDate("", "2026-11-01", "", "", today)
	if got != "2026-11-01" {
		t.Fatalf("primary got %q", got)
	}
}

func TestExtractMovieReleaseMilestonesPrefersUS(t *testing.T) {
	payload := tmdbReleaseDates{Results: []tmdbReleaseCountry{
		{ISO31661: "FR", ReleaseDates: []tmdbReleaseDateEntry{{ReleaseDate: "2026-01-01", Type: 3}}},
		{ISO31661: "US", ReleaseDates: []tmdbReleaseDateEntry{
			{ReleaseDate: "2026-03-15", Type: 3},
			{ReleaseDate: "2026-06-01", Type: 4},
		}},
	}}
	thea, dig := extractMovieReleaseMilestones(payload)
	if thea != "2026-03-15" || dig != "2026-06-01" {
		t.Fatalf("got %s %s", thea, dig)
	}
}
