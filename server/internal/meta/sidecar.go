package meta

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"

	"coog/internal/library"
)

// Sidecar is the on-disk identity for a library file (coog.json next to the media).
// Remote enrich never invents a match when MatchStatus is unmatched/ignored.
type Sidecar struct {
	MatchStatus  string   `json:"matchStatus,omitempty"` // matched|unmatched|ignored|suggested
	ImdbID       string   `json:"imdbId,omitempty"`
	Title        string   `json:"title,omitempty"`
	Year         int      `json:"year,omitempty"`
	Plot         string   `json:"plot,omitempty"`
	Tagline      string   `json:"tagline,omitempty"`
	Genres       []string `json:"genres,omitempty"`
	Rating       float64  `json:"rating,omitempty"`
	Poster       string   `json:"poster,omitempty"` // relative filename beside media
	Fanart       string   `json:"fanart,omitempty"` // relative filename
	Logo         string   `json:"logo,omitempty"`   // relative filename
	Quality      string   `json:"quality,omitempty"`
	SizeLabel    string   `json:"sizeLabel,omitempty"`
	Pack         string   `json:"pack,omitempty"`
	Tags         []string `json:"tags,omitempty"`
	Languages    []string `json:"languages,omitempty"`
	ReleaseTitle string   `json:"releaseTitle,omitempty"`
}

func sidecarCandidates(mediaPath string) []string {
	dir := filepath.Dir(mediaPath)
	stem := strings.TrimSuffix(filepath.Base(mediaPath), filepath.Ext(mediaPath))
	out := []string{
		filepath.Join(dir, "coog.json"),
		filepath.Join(dir, stem+".coog.json"),
		filepath.Join(dir, "."+stem+".coog.json"),
	}
	if art := library.ArtDir(mediaPath); art != dir {
		out = append(out,
			filepath.Join(art, "coog.json"),
			filepath.Join(art, "tvshow.coog.json"),
		)
	}
	return out
}

// SidecarPath is where we write identity for this file.
func SidecarPath(mediaPath string) string {
	for _, p := range sidecarCandidates(mediaPath) {
		if st, err := os.Stat(p); err == nil && st.Size() > 0 {
			return p
		}
	}
	dir := filepath.Dir(mediaPath)
	art := library.ArtDir(mediaPath)
	if art != dir {
		return filepath.Join(art, "coog.json")
	}
	if library.CountVideos(dir) > 1 {
		stem := strings.TrimSuffix(filepath.Base(mediaPath), filepath.Ext(mediaPath))
		return filepath.Join(dir, stem+".coog.json")
	}
	return filepath.Join(dir, "coog.json")
}

// ReadSidecar loads the first coog.json beside the media file or show folder.
func ReadSidecar(mediaPath string) (Sidecar, bool) {
	for _, p := range sidecarCandidates(mediaPath) {
		b, err := os.ReadFile(p)
		if err != nil || len(b) == 0 || len(b) > 1<<20 {
			continue
		}
		var sc Sidecar
		if err := json.Unmarshal(b, &sc); err != nil {
			continue
		}
		sc.MatchStatus = strings.ToLower(strings.TrimSpace(sc.MatchStatus))
		sc.ImdbID = strings.TrimSpace(sc.ImdbID)
		return sc, true
	}
	return Sidecar{}, false
}

// WriteSidecar writes identity next to the media (or show folder for episodes).
func WriteSidecar(mediaPath string, sc Sidecar) error {
	path := SidecarPath(mediaPath)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	if sc.MatchStatus == "" && sc.ImdbID != "" {
		sc.MatchStatus = "matched"
	}
	b, err := json.MarshalIndent(sc, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(b, '\n'), 0o644)
}

func infoFromSidecar(sc Sidecar) Info {
	status := sc.MatchStatus
	if status == "" {
		if sc.ImdbID != "" {
			status = "matched"
		} else {
			status = "unmatched"
		}
	}
	info := Info{
		ImdbID:      sc.ImdbID,
		Plot:        strings.TrimSpace(sc.Plot),
		Tagline:     strings.TrimSpace(sc.Tagline),
		Genres:      sc.Genres,
		Rating:      sc.Rating,
		Year:        sc.Year,
		Source:      "sidecar",
		MatchStatus: status,
	}
	if sc.ImdbID != "" && status == "matched" {
		info.PosterURL = metahubPoster(sc.ImdbID)
		info.BackdropURL = metahubBackdrop(sc.ImdbID)
		info.LogoURL = metahubLogo(sc.ImdbID)
	}
	return info
}
