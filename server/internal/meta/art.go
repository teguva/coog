package meta

import (
	"context"
	"log/slog"
	"os"
	"path/filepath"
	"strings"

	"coog/internal/library"
	"coog/internal/store"
)

type localArt struct {
	Dir       string
	Poster    string
	Fanart    string
	Logo      string
	PosterRel string
	FanartRel string
	LogoRel   string
}

func localArtPaths(mediaPath string) localArt {
	artDir := library.ArtDir(mediaPath)
	stem := strings.TrimSuffix(filepath.Base(mediaPath), filepath.Ext(mediaPath))
	prefixed := library.CountVideos(artDir) > 1
	poster, fanart, logo := "poster.jpg", "fanart.jpg", "logo.png"
	if prefixed {
		poster = stem + "-poster.jpg"
		fanart = stem + "-fanart.jpg"
		logo = stem + "-logo.png"
	}
	return localArt{
		Dir:       artDir,
		Poster:    filepath.Join(artDir, poster),
		Fanart:    filepath.Join(artDir, fanart),
		Logo:      filepath.Join(artDir, logo),
		PosterRel: poster,
		FanartRel: fanart,
		LogoRel:   logo,
	}
}

// alternateLocalArtPaths is the other naming scheme (plain vs stem-prefixed).
// Used to migrate leftovers when a folder flips between one and many videos.
func alternateLocalArtPaths(mediaPath string) localArt {
	artDir := library.ArtDir(mediaPath)
	stem := strings.TrimSuffix(filepath.Base(mediaPath), filepath.Ext(mediaPath))
	prefixed := library.CountVideos(artDir) > 1
	poster, fanart, logo := stem+"-poster.jpg", stem+"-fanart.jpg", stem+"-logo.png"
	if prefixed {
		poster, fanart, logo = "poster.jpg", "fanart.jpg", "logo.png"
	}
	return localArt{
		Dir:       artDir,
		Poster:    filepath.Join(artDir, poster),
		Fanart:    filepath.Join(artDir, fanart),
		Logo:      filepath.Join(artDir, logo),
		PosterRel: poster,
		FanartRel: fanart,
		LogoRel:   logo,
	}
}

func adoptOrCleanupArt(want, alt string) {
	if want == "" || want == alt {
		return
	}
	if fileOK(want) {
		if alt != "" && alt != want {
			_ = os.Remove(alt)
		}
		return
	}
	if fileOK(alt) {
		if err := os.Rename(alt, want); err != nil {
			// Cross-device or collision: copy isn't needed for same-dir art; drop alt.
			_ = os.Remove(alt)
			return
		}
		return
	}
	if alt != "" && alt != want {
		_ = os.Remove(alt)
	}
}

func fileOK(path string) bool {
	st, err := os.Stat(path)
	return err == nil && st.Size() > 32
}

func (e *Enricher) persistIdentity(item store.MediaItem, info Info) {
	if strings.TrimSpace(item.Path) == "" {
		return
	}
	existing, has := ReadSidecar(item.Path)
	if has && (existing.MatchStatus == "ignored" || existing.MatchStatus == "suggested") {
		return
	}
	if has && existing.MatchStatus == "unmatched" && info.MatchStatus != "matched" {
		return
	}
	sc := existing
	title := strings.TrimSpace(item.Title)
	if item.Kind == "episode" && item.ShowTitle != "" {
		title = item.ShowTitle
	}
	if info.MatchStatus == "matched" && info.ImdbID != "" {
		sc.MatchStatus = "matched"
		sc.ImdbID = info.ImdbID
		if sc.Title == "" {
			sc.Title = title
		}
		if sc.Year == 0 {
			sc.Year = item.Year
			if sc.Year == 0 {
				sc.Year = info.Year
			}
		}
		if sc.Plot == "" {
			sc.Plot = info.Plot
		}
		if sc.Tagline == "" {
			sc.Tagline = info.Tagline
		}
		if len(sc.Genres) == 0 {
			sc.Genres = info.Genres
		}
		if sc.Rating == 0 {
			sc.Rating = info.Rating
		}
	} else {
		sc = Sidecar{MatchStatus: "unmatched", Title: title, Year: item.Year}
	}
	layout := localArtPaths(item.Path)
	if fileOK(layout.Poster) {
		sc.Poster = layout.PosterRel
	}
	if fileOK(layout.Fanart) {
		sc.Fanart = layout.FanartRel
	}
	if fileOK(layout.Logo) {
		sc.Logo = layout.LogoRel
	}
	if err := WriteSidecar(item.Path, sc); err != nil {
		slog.Debug("sidecar write", "path", item.Path, "err", err)
	}
}

func (e *Enricher) persistLocalArt(ctx context.Context, item store.MediaItem, info *Info) {
	if info == nil || info.MatchStatus != "matched" || info.ImdbID == "" {
		return
	}
	layout := localArtPaths(item.Path)
	alt := alternateLocalArtPaths(item.Path)
	if err := os.MkdirAll(layout.Dir, 0o755); err != nil {
		slog.Debug("art dir", "dir", layout.Dir, "err", err)
		return
	}
	adoptOrCleanupArt(layout.Poster, alt.Poster)
	adoptOrCleanupArt(layout.Fanart, alt.Fanart)
	adoptOrCleanupArt(layout.Logo, alt.Logo)
	if info.PosterURL != "" && !fileOK(layout.Poster) {
		if err := e.FetchFile(ctx, info.PosterURL, layout.Poster); err != nil {
			slog.Debug("poster fetch", "id", item.ID, "err", err)
		}
	}
	if info.BackdropURL != "" && !fileOK(layout.Fanart) {
		if err := e.FetchFile(ctx, info.BackdropURL, layout.Fanart); err != nil {
			slog.Debug("fanart fetch", "id", item.ID, "err", err)
		}
	}
	logoURL := info.LogoURL
	if logoURL == "" {
		logoURL = metahubLogo(info.ImdbID)
	}
	if logoURL != "" && !fileOK(layout.Logo) {
		if err := e.FetchFile(ctx, logoURL, layout.Logo); err != nil {
			slog.Debug("logo fetch", "id", item.ID, "err", err)
		}
	}
	e.persistIdentity(item, *info)
}

func (e *Enricher) invalidateArtwork(id string) {
	base := filepath.Join(filepath.Dir(e.dir), "artwork", id)
	_ = os.Remove(base + "-poster.jpg")
	_ = os.Remove(base + "-backdrop.jpg")
	_ = os.Remove(base + "-logo.png")
}

func IdentityConfirmed(mediaPath string, info Info) bool {
	status := strings.ToLower(strings.TrimSpace(info.MatchStatus))
	if status == "unmatched" || status == "ignored" || status == "suggested" {
		return false
	}
	if strings.TrimSpace(info.ImdbID) == "" {
		return false
	}
	id := FindIMDB(mediaPath, "", 0)
	return strings.EqualFold(id, info.ImdbID)
}
