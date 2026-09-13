package api

import (
	"testing"

	"coog/internal/meta"
	"coog/internal/store"
)

func TestAttachLibraryMarksLocalWithoutDropping(t *testing.T) {
	local := map[string]string{"tt111": "media-1"}
	out := attachLibrary([]meta.CatalogItem{
		{ID: "catalog:tt111", ImdbID: "tt111", Title: "Local"},
		{ID: "catalog:tt222", ImdbID: "tt222", Title: "Remote"},
	}, local)
	if len(out) != 2 {
		t.Fatalf("dropped items: %d", len(out))
	}
	if !out[0].InLibrary || out[0].MediaID != "media-1" {
		t.Fatalf("%+v", out[0])
	}
	if out[1].InLibrary || out[1].MediaID != "" {
		t.Fatalf("remote marked local: %+v", out[1])
	}
}

func TestMergeCatalogLibraryKeepsCatalogOrder(t *testing.T) {
	local := map[string]string{"tt111": "media-1"}
	out := mergeCatalogLibrary(
		[]meta.CatalogItem{
			{ID: "catalog:tt222", ImdbID: "tt222", Title: "Trending first"},
			{ID: "catalog:tt111", ImdbID: "tt111", Title: "Local in catalog"},
		},
		[]meta.CatalogItem{
			{ID: "disk-1", ImdbID: "tt111", Title: "Should not duplicate", MediaID: "media-1"},
			{ID: "disk-only", Title: "Only on disk", MediaID: "media-9"},
		},
		local,
	)
	if len(out) != 3 {
		t.Fatalf("want 3 items, got %d: %+v", len(out), out)
	}
	if out[0].Title != "Trending first" || out[0].InLibrary {
		t.Fatalf("catalog order broken: %+v", out[0])
	}
	if !out[1].InLibrary || out[1].MediaID != "media-1" {
		t.Fatalf("local in catalog not stamped: %+v", out[1])
	}
	if !out[2].InLibrary || out[2].Title != "Only on disk" {
		t.Fatalf("disk-only not appended: %+v", out[2])
	}
}

func TestMergeCatalogLibraryStampsByTitle(t *testing.T) {
	out := mergeCatalogLibrary(
		[]meta.CatalogItem{
			{ID: "catalog:tt1", ImdbID: "tt1", Title: "Spider-Man: Brand New Day", Year: 2026},
			{ID: "catalog:tt2", ImdbID: "tt2", Title: "Moana", Year: 2016},
		},
		[]meta.CatalogItem{
			{ID: "disk-1", Title: "Spider-Man - Brand New Day", MediaID: "m1"},
			{ID: "disk-2", Title: "Moana", Year: 2016, MediaID: "m2"},
		},
		map[string]string{},
	)
	if len(out) != 2 {
		t.Fatalf("duplicated locals: %d %+v", len(out), out)
	}
	if !out[0].InLibrary || out[0].MediaID != "m1" {
		t.Fatalf("title match not stamped: %+v", out[0])
	}
	if !out[1].InLibrary || out[1].MediaID != "m2" {
		t.Fatalf("exact title not stamped: %+v", out[1])
	}
}

func TestContinueKeyAndCompletion(t *testing.T) {
	if got := continueKey("episode", "tt123", 0, ""); got != "series:tt123" {
		t.Fatalf("series key: %s", got)
	}
	if got := continueKey("movie", "", 44, "m1"); got != "movie:tmdb:44" {
		t.Fatalf("tmdb key: %s", got)
	}
	if got := continueKey("movie", "", 0, "m1"); got != "media:m1" {
		t.Fatalf("media key: %s", got)
	}
}

func TestContinueEntryIsMaizeUsesPath(t *testing.T) {
	// Catalog / empty media IDs are never treated as Maize.
	s := &Server{}
	if s.continueEntryIsMaize(store.ContinueEntry{MediaID: ""}) {
		t.Fatal("empty media id")
	}
	if s.continueEntryIsMaize(store.ContinueEntry{MediaID: "catalog:tt1"}) {
		t.Fatal("catalog id")
	}
}

func TestOverlayEpisodeLibraryMarksAndKeepsMissing(t *testing.T) {
	imdb := map[string]string{"ep1": "tt1", "ep-extra": "tt1", "other": "tt9"}
	out := overlayEpisodeLibrary(
		[]meta.CatalogItem{
			{ID: "catalog:tt1:1:1", Season: 1, Episode: 1, Title: "Pilot"},
			{ID: "catalog:tt1:1:2", Season: 1, Episode: 2, Title: "Next"},
		},
		"tt1",
		[]store.MediaItem{
			{ID: "ep1", Kind: "episode", Season: 1, Episode: 1, Title: "Pilot file"},
			{ID: "ep-extra", Kind: "episode", Season: 1, Episode: 3, Title: "Unaired"},
			{ID: "other", Kind: "episode", Season: 1, Episode: 1, Title: "Wrong show"},
		},
		func(item store.MediaItem) string { return imdb[item.ID] },
	)
	if len(out) != 3 {
		t.Fatalf("want catalog + extra local, got %d: %+v", len(out), out)
	}
	if !out[0].InLibrary || out[0].MediaID != "ep1" {
		t.Fatalf("pilot not local: %+v", out[0])
	}
	if out[1].InLibrary {
		t.Fatalf("missing ep marked local: %+v", out[1])
	}
	if !out[2].InLibrary || out[2].Season != 1 || out[2].Episode != 3 {
		t.Fatalf("extra local dropped: %+v", out[2])
	}
}
