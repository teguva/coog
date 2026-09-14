package streams

import (
	"strings"
	"testing"
)

func TestClassifyPack(t *testing.T) {
	cases := map[string]PackKind{
		"Show.S01E01.1080p.WEB":         PackSingle,
		"Show.S01E01-E04.1080p":         PackMulti,
		"Show.S01E01-E12.BluRay":        PackSeason,
		"Show.S01.COMPLETE.1080p":       PackSeason,
		"Show Complete Series 1080p":    PackSeries,
		"Show.S01-S03.1080p":            PackSeries,
		"Show Season 1 1080p BluRay":    PackSeason,
		"Show.S01E01.S01E02.S01E03.WEB": PackMulti,
	}
	for title, want := range cases {
		if got := ClassifyPack(title); got != want {
			t.Fatalf("%q: got %s want %s", title, got, want)
		}
	}
}

func TestPickPreferredFiltersQualityAndSize(t *testing.T) {
	cands := []Candidate{
		{Title: "Show.S01E01.480p.CAM", Size: 400_000_000, Seeders: 50},
		{Title: "Show.S01E01.1080p.BluRay.x265", Size: 2_500_000_000, Seeders: 20, Cached: true},
		{Title: "Show.S01E01.2160p.REMUX", Size: 40_000_000_000, Seeders: 5},
	}
	prefs := SelectPrefs{
		PreferredQualities:  []string{"1080p"},
		ExcludeQualities:    []string{"cam", "480p"},
		MinSizeBytes:        1_000_000_000,
		MaxSizeBytes:        8_000_000_000,
		PreferSingleEpisode: true,
		AllowSeasonPacks:    true,
	}
	got := PickPreferred(cands, prefs)
	if !got.OK {
		t.Fatalf("expected match, got %s", got.Reason)
	}
	if !strings.Contains(got.Candidate.Title, "1080p") {
		t.Fatalf("picked %s", got.Candidate.Title)
	}
}

func TestPickPreferredRejectsBadSeasonPack(t *testing.T) {
	cands := []Candidate{
		{Title: "Show.S01.COMPLETE.CAM.480p", Size: 8_000_000_000, Seeders: 100},
	}
	prefs := SelectPrefs{
		PreferredQualities: []string{"1080p"},
		ExcludeQualities:   []string{"cam", "480p"},
		AllowSeasonPacks:   true,
	}
	got := PickPreferred(cands, prefs)
	if got.OK {
		t.Fatalf("should reject junk pack")
	}
}

func TestPickPreferredPrefersSingleOverSeason(t *testing.T) {
	cands := []Candidate{
		{Title: "Show.S01.COMPLETE.1080p.BluRay", Size: 25_000_000_000, Seeders: 30, Cached: true},
		{Title: "Show.S01E03.1080p.BluRay.x265", Size: 2_200_000_000, Seeders: 25, Cached: true},
	}
	prefs := SelectPrefs{
		PreferredQualities:  []string{"1080p"},
		PreferSingleEpisode: true,
		AllowSeasonPacks:    true,
		MaxSizeBytes:        5_000_000_000,
	}
	got := PickPreferred(cands, prefs)
	if !got.OK {
		t.Fatal(got.Reason)
	}
	if got.Pack != PackSingle {
		t.Fatalf("expected single, got %s (%s)", got.Pack, got.Candidate.Title)
	}
}

func TestPickPreferredArchivesHighestQuality(t *testing.T) {
	cands := []Candidate{
		{Title: "Movie.2024.1080p.WEB-DL.HDR10+.x265", Size: 4_000_000_000, Seeders: 40, Cached: true},
		{Title: "Movie.2024.1080p.WEB-DL.DV.Hybrid.x265", Size: 4_200_000_000, Seeders: 35, Cached: true},
		{Title: "Movie.2024.1080p.WEB-DL.DV.Atmos.x265", Size: 5_000_000_000, Seeders: 30, Cached: true},
		{Title: "Movie.2024.1080p.WEB-DL.HEVC", Size: 3_500_000_000, Seeders: 50, Cached: true},
	}
	prefs := SelectPrefs{
		PreferredQualities: []string{"1080p", "2160p"},
	}
	got := PickPreferred(cands, prefs)
	if !got.OK {
		t.Fatal(got.Reason)
	}
	if !strings.Contains(got.Candidate.Title, "DV.Atmos") {
		t.Fatalf("want DV Atmos archival pick, got %s", got.Candidate.Title)
	}
}

func TestPickPreferredPrefersLargerWithinWindow(t *testing.T) {
	cands := []Candidate{
		{Title: "Movie.2024.1080p.BluRay.x265", Size: 2_000_000_000, Seeders: 40, Cached: true},
		{Title: "Movie.2024.1080p.BluRay.x265.BIG", Size: 7_000_000_000, Seeders: 20, Cached: true},
	}
	prefs := SelectPrefs{
		PreferredQualities: []string{"1080p"},
		MinSizeBytes:       1_000_000_000,
		MaxSizeBytes:       8_000_000_000,
	}
	got := PickPreferred(cands, prefs)
	if !got.OK {
		t.Fatal(got.Reason)
	}
	if got.Candidate.Size < 6_000_000_000 {
		t.Fatalf("want larger encode, got %s size=%d", got.Candidate.Title, got.Candidate.Size)
	}
}
