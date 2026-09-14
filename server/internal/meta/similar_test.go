package meta

import "testing"

func TestGenreOverlapCount(t *testing.T) {
	if n := genreOverlapCount([]int{28, 12, 878}, []int{12, 53}); n != 1 {
		t.Fatalf("got %d", n)
	}
	if n := genreOverlapCount([]int{28}, []int{12}); n != 0 {
		t.Fatalf("got %d", n)
	}
}

func TestSimilarMediaOK(t *testing.T) {
	if !similarMediaOK(tmdbMovie{MediaType: "movie", Title: "X"}, "movie") {
		t.Fatal("movie typed")
	}
	if similarMediaOK(tmdbMovie{MediaType: "tv", Name: "Y"}, "movie") {
		t.Fatal("tv rejected for movie")
	}
	if !similarMediaOK(tmdbMovie{Title: "OnlyTitle"}, "movie") {
		t.Fatal("untagged movie title")
	}
	if !similarMediaOK(tmdbMovie{Name: "OnlyName"}, "series") {
		t.Fatal("untagged series name")
	}
}

func TestMergeTMDBRowKeepsBest(t *testing.T) {
	got := mergeTMDBRow(
		tmdbMovie{ID: 1, Title: "A", Popularity: 1},
		tmdbMovie{ID: 1, Overview: "plot", Popularity: 9, GenreIDs: []int{28}},
	)
	if got.Overview != "plot" || got.Popularity != 9 || len(got.GenreIDs) != 1 {
		t.Fatalf("%+v", got)
	}
}
