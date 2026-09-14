package meta

import (
	"strings"
	"time"
)

const theatricalWindowDays = 90

func ClassifyMovieReleasePhase(status, primary, theatrical, digital string, today time.Time) string {
	if today.IsZero() {
		today = time.Now()
	}
	today = today.UTC().Truncate(24 * time.Hour)
	dig := parseISODate(digital)
	thea := parseISODate(theatrical)
	prim := parseISODate(primary)

	if !dig.IsZero() && !dig.After(today) {
		return "released"
	}
	if !thea.IsZero() && thea.After(today) {
		return "coming_soon"
	}
	if !thea.IsZero() && !thea.After(today) {
		if !dig.IsZero() && dig.After(today) {
			return "theatrical"
		}
		if dig.IsZero() {
			if today.Sub(thea) <= theatricalWindowDays*24*time.Hour {
				return "theatrical"
			}
			return "released"
		}
		return "released"
	}
	if !prim.IsZero() && prim.After(today) {
		return "coming_soon"
	}
	if !prim.IsZero() && !prim.After(today) {
		return "released"
	}
	switch strings.TrimSpace(status) {
	case "Rumored", "Planned", "In Production", "Post Production", "Canceled", "Cancelled":
		return "coming_soon"
	case "Released":
		return "released"
	}
	return ""
}

// PreferredReleaseDate is the calendar date to show for a title (ISO YYYY-MM-DD).
// For coming-soon movies this is the next theatrical/primary date when known.
func PreferredReleaseDate(status, primary, theatrical, digital string, today time.Time) string {
	if today.IsZero() {
		today = time.Now()
	}
	today = today.UTC().Truncate(24 * time.Hour)
	dig := parseISODate(digital)
	thea := parseISODate(theatrical)
	prim := parseISODate(primary)
	phase := ClassifyMovieReleasePhase(status, primary, theatrical, digital, today)
	switch phase {
	case "coming_soon":
		if !thea.IsZero() && thea.After(today) {
			return thea.Format("2006-01-02")
		}
		if !prim.IsZero() && prim.After(today) {
			return prim.Format("2006-01-02")
		}
	case "theatrical":
		if !dig.IsZero() && dig.After(today) {
			return dig.Format("2006-01-02")
		}
		if !thea.IsZero() {
			return thea.Format("2006-01-02")
		}
	}
	if !prim.IsZero() {
		return prim.Format("2006-01-02")
	}
	if !thea.IsZero() {
		return thea.Format("2006-01-02")
	}
	if !dig.IsZero() {
		return dig.Format("2006-01-02")
	}
	return ""
}

func parseISODate(s string) time.Time {
	s = strings.TrimSpace(s)
	if len(s) < 10 {
		return time.Time{}
	}
	t, err := time.Parse("2006-01-02", s[:10])
	if err != nil {
		return time.Time{}
	}
	return t.UTC().Truncate(24 * time.Hour)
}

func extractMovieReleaseMilestones(payload tmdbReleaseDates) (theatrical, digital string) {
	wide, limited, home := collectReleaseDates(payload, "US")
	if len(wide) == 0 && len(limited) == 0 && len(home) == 0 {
		wide, limited, home = collectReleaseDates(payload, "")
	}
	theatricalDates := wide
	if len(theatricalDates) == 0 {
		theatricalDates = limited
	}
	if len(theatricalDates) > 0 {
		theatrical = minTime(theatricalDates).Format("2006-01-02")
	}
	if len(home) > 0 {
		digital = minTime(home).Format("2006-01-02")
	}
	return theatrical, digital
}

func collectReleaseDates(payload tmdbReleaseDates, country string) (wide, limited, home []time.Time) {
	for _, row := range payload.Results {
		if country != "" && !strings.EqualFold(row.ISO31661, country) {
			continue
		}
		for _, entry := range row.ReleaseDates {
			parsed := parseISODate(entry.ReleaseDate)
			if parsed.IsZero() {
				continue
			}
			switch entry.Type {
			case 3:
				wide = append(wide, parsed)
			case 2:
				limited = append(limited, parsed)
			case 4, 5, 6:
				home = append(home, parsed)
			}
		}
	}
	return wide, limited, home
}

func minTime(in []time.Time) time.Time {
	best := in[0]
	for _, t := range in[1:] {
		if t.Before(best) {
			best = t
		}
	}
	return best
}

type tmdbReleaseDateEntry struct {
	ReleaseDate   string `json:"release_date"`
	Type          int    `json:"type"`
	Certification string `json:"certification"`
}

type tmdbReleaseCountry struct {
	ISO31661     string                 `json:"iso_3166_1"`
	ReleaseDates []tmdbReleaseDateEntry `json:"release_dates"`
}

type tmdbReleaseDates struct {
	Results []tmdbReleaseCountry `json:"results"`
}
