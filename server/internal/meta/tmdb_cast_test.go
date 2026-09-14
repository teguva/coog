package meta

import (
	"testing"
	"time"
)

func TestCastFromAggregateCreditsPrefersRoles(t *testing.T) {
	cast := castFromAggregateCredits(tmdbAggregateCredits{
		Cast: []tmdbAggregateCast{
			{
				ID:                2,
				Name:              "Guest",
				TotalEpisodeCount: 2,
				Order:             20,
				Popularity:        5,
				Roles: []struct {
					Character    string `json:"character"`
					EpisodeCount int    `json:"episode_count"`
				}{{Character: "Cameo", EpisodeCount: 2}},
			},
			{
				ID:                1,
				Name:              "Lead",
				TotalEpisodeCount: 80,
				Order:             1,
				Popularity:        1,
				ProfilePath:       "/lead.jpg",
				Roles: []struct {
					Character    string `json:"character"`
					EpisodeCount int    `json:"episode_count"`
				}{{Character: "Hero", EpisodeCount: 80}},
			},
		},
	})
	if len(cast) != 2 {
		t.Fatalf("got %d cast, want 2", len(cast))
	}
	if cast[0].Name != "Lead" || cast[0].Character != "Hero" {
		t.Fatalf("first cast = %+v, want Lead/Hero", cast[0])
	}
	if cast[0].ProfileURL == "" {
		t.Fatal("expected profile url")
	}
}

func TestCastFromAggregateCreditsPrefersOrderZeroHost(t *testing.T) {
	// Mirrors Clarkson's Farm: host at order=0, supporting cast billed 1+.
	cast := castFromAggregateCredits(tmdbAggregateCredits{
		Cast: []tmdbAggregateCast{
			{ID: 2, Name: "Farmhand", TotalEpisodeCount: 40, Order: 1, Popularity: 0.4},
			{ID: 1, Name: "Host", TotalEpisodeCount: 40, Order: 0, Popularity: 2.3,
				Roles: []struct {
					Character    string `json:"character"`
					EpisodeCount int    `json:"episode_count"`
				}{{Character: "Self - Host", EpisodeCount: 40}}},
			{ID: 3, Name: "Cameo", TotalEpisodeCount: 1, Order: 544, Popularity: 1.6},
		},
	})
	if len(cast) == 0 || cast[0].Name != "Host" {
		t.Fatalf("first cast = %+v, want Host", cast)
	}
}

func TestCastFromTMDBMoviePrefersAggregate(t *testing.T) {
	movie := tmdbMovie{
		Credits: tmdbCredits{Cast: []tmdbCast{{ID: 9, Name: "SeasonOnly", Character: "X"}}},
		AggregateCredits: tmdbAggregateCredits{
			Cast: []tmdbAggregateCast{{ID: 1, Name: "SeriesRegular", TotalEpisodeCount: 10, Order: 1}},
		},
	}
	got := castFromTMDBMovie(movie)
	if len(got) != 1 || got[0].Name != "SeriesRegular" {
		t.Fatalf("got %+v, want SeriesRegular from aggregate", got)
	}
}

func TestCastFromTMDBMovieFallsBackToCredits(t *testing.T) {
	movie := tmdbMovie{
		Credits: tmdbCredits{Cast: []tmdbCast{{ID: 9, Name: "SeasonOnly", Character: "X"}}},
	}
	got := castFromTMDBMovie(movie)
	if len(got) != 1 || got[0].Name != "SeasonOnly" {
		t.Fatalf("got %+v, want SeasonOnly fallback", got)
	}
}

func TestDirectorOrCreatorFromTMDB(t *testing.T) {
	movie := tmdbMovie{
		CreatedBy: []tmdbCreator{{ID: 7, Name: "Showrunner", ProfilePath: "/c.jpg"}},
	}
	got := directorOrCreatorFromTMDB(movie)
	if got == nil || got.Name != "Showrunner" || got.Character != "Creator" {
		t.Fatalf("got %+v, want Creator Showrunner", got)
	}
	withDir := tmdbMovie{
		Credits:   tmdbCredits{Crew: []tmdbCrew{{ID: 3, Name: "Dir", Job: "Director"}}},
		CreatedBy: []tmdbCreator{{ID: 7, Name: "Showrunner"}},
	}
	got = directorOrCreatorFromTMDB(withDir)
	if got == nil || got.Name != "Dir" || got.Character != "Director" {
		t.Fatalf("got %+v, want Director Dir", got)
	}
}

func TestCatalogTitleFreshEmptySeriesCast(t *testing.T) {
	fresh := time.Now().UTC()
	ttl := 24 * time.Hour
	if catalogTitleFresh("series", CatalogItem{Title: "X"}, fresh, ttl) {
		t.Fatal("series with empty cast should not be fresh")
	}
	item := CatalogItem{Title: "X", Cast: []CastMember{{Name: "A"}}}
	if !catalogTitleFresh("series", item, fresh, ttl) {
		t.Fatal("series with cast should be fresh within TTL")
	}
	if !catalogTitleFresh("movie", CatalogItem{Title: "M"}, fresh, ttl) {
		t.Fatal("movie without cast can still be fresh")
	}
}
