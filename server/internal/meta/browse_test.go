package meta

import "testing"

func TestNormalizeBrowseQuery(t *testing.T) {
	q := NormalizeBrowseQuery(BrowseQuery{
		Kind:     "tv",
		GenreIDs: []int{35, 35, 10749, 28, 12},
		YearMin:  2019,
		YearMax:  2010,
		Mood:     "Feel-Good",
	})
	if q.Kind != "series" {
		t.Fatalf("kind %s", q.Kind)
	}
	if len(q.GenreIDs) != 3 || q.GenreIDs[0] != 35 || q.GenreIDs[1] != 10749 || q.GenreIDs[2] != 28 {
		t.Fatalf("genres %+v", q.GenreIDs)
	}
	if q.YearMin != 2010 || q.YearMax != 2019 {
		t.Fatalf("years %d-%d", q.YearMin, q.YearMax)
	}
	if q.Mood != "feel_good" {
		t.Fatalf("mood %s", q.Mood)
	}
	if !q.HasFacets() {
		t.Fatal("expected facets")
	}
}

func TestParseGenreCSV(t *testing.T) {
	got := ParseGenreCSV("35, 10749|28")
	if len(got) != 3 || got[0] != 35 || got[1] != 10749 || got[2] != 28 {
		t.Fatalf("%+v", got)
	}
}

func TestMoodKeywordIDs(t *testing.T) {
	for _, id := range []string{"funny", "dark", "feel_good", "thrilling", "mind_bending", "romantic"} {
		if len(MoodKeywordIDs(id)) == 0 {
			t.Fatalf("mood %s empty", id)
		}
	}
	if MoodKeywordIDs("nope") != nil {
		t.Fatal("unknown mood")
	}
}

func TestItemMatchesBrowseLocal(t *testing.T) {
	item := CatalogItem{Year: 2015, Rating: 7.2, GenreIDs: []int{35, 10749}}
	if !itemMatchesBrowseLocal(item, BrowseQuery{GenreIDs: []int{35, 10749}, YearMin: 2010, YearMax: 2019, MinRating: 7}) {
		t.Fatal("should match")
	}
	if itemMatchesBrowseLocal(item, BrowseQuery{GenreIDs: []int{35, 28}}) {
		t.Fatal("missing genre 28")
	}
	if itemMatchesBrowseLocal(item, BrowseQuery{YearMin: 2020}) {
		t.Fatal("year")
	}
}

func TestFilterMergedBrowseDropsMoodLocals(t *testing.T) {
	remote := []CatalogItem{{ImdbID: "tt1", Title: "A"}}
	merged := []CatalogItem{
		{ImdbID: "tt1", Title: "A"},
		{ImdbID: "tt2", Title: "Local Only", Year: 2015, GenreIDs: []int{35}},
	}
	got := FilterMergedBrowse(remote, merged, BrowseQuery{Mood: "funny"})
	if len(got) != 1 || got[0].ImdbID != "tt1" {
		t.Fatalf("%+v", got)
	}
	got = FilterMergedBrowse(remote, merged, BrowseQuery{GenreIDs: []int{35}, YearMin: 2010})
	if len(got) != 2 {
		t.Fatalf("want remote+local, got %+v", got)
	}
}
