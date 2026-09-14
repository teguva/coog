package acquire

import (
	"os"
	"path/filepath"
	"testing"

	"coog/internal/store"
)

func TestLibraryDestStripsEpisodeFromShowFolder(t *testing.T) {
	job := store.Job{
		Title:  "Sex and the City S1E3",
		URL:    "imdb:tt0159206:1:3",
		ImdbID: "tt0159206",
	}
	name := "Sex and the City S1E3"
	relDir, fileBase, season, episode, kind := libraryDest(job, name)
	if kind != "series" {
		t.Fatalf("kind=%s", kind)
	}
	if season != 1 || episode != 3 {
		t.Fatalf("S/E = %d/%d", season, episode)
	}
	wantDir := filepath.Join("Series", "Sex and the City", "Season 01")
	if relDir != wantDir {
		t.Fatalf("relDir=%q want %q", relDir, wantDir)
	}
	if fileBase != "Sex and the City S01E03" {
		t.Fatalf("fileBase=%q", fileBase)
	}
}

func TestLibraryDestUsesImdbRefWhenTitleHasNoMarkers(t *testing.T) {
	job := store.Job{
		Title:  "Sex and the City",
		URL:    "imdb:tt0159206:1:1",
		ImdbID: "tt0159206",
		Year:   1998,
	}
	relDir, fileBase, season, episode, kind := libraryDest(job, "Sex and the City")
	if kind != "series" || season != 1 || episode != 1 {
		t.Fatalf("got kind=%s S%dE%d", kind, season, episode)
	}
	wantDir := filepath.Join("Series", "Sex and the City", "Season 01")
	if relDir != wantDir || fileBase != "Sex and the City S01E01" {
		t.Fatalf("relDir=%q fileBase=%q", relDir, fileBase)
	}
}

func TestLibraryDestMovieKeepsYearFolder(t *testing.T) {
	job := store.Job{Title: "Dune", Year: 2021, URL: "https://example.com/x"}
	relDir, fileBase, _, _, kind := libraryDest(job, "Dune")
	if kind != "movie" {
		t.Fatalf("kind=%s", kind)
	}
	want := filepath.Join("Movies", "Dune (2021)")
	if relDir != want || fileBase != "Dune (2021)" {
		t.Fatalf("relDir=%q fileBase=%q", relDir, fileBase)
	}
}

func TestUniqueLibraryBaseWritesSibling(t *testing.T) {
	dir := t.TempDir()
	base := "Moana (2026)"
	if err := os.WriteFile(filepath.Join(dir, base+".mp4"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	job := store.Job{ID: "abcdef012345", Quality: "2160p", Tags: []string{"DV", "Atmos", "WEB"}}
	got := uniqueLibraryBase(dir, base, job)
	want := base + " - 2160p DV Atmos WEB"
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
	if libraryBaseOccupied(dir, base) != true {
		t.Fatal("original should still be occupied")
	}
}
