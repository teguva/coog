package probe

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

func SidecarPoster(mediaPath string) string {
	stem := strings.TrimSuffix(filepath.Base(mediaPath), filepath.Ext(mediaPath))
	if sc, base, ok := readSidecarNames(mediaPath); ok && sc.poster != "" {
		if p := resolveBeside(base, sc.poster); p != "" {
			return p
		}
	}
	names := []string{
		stem + "-poster.jpg", stem + "-poster.png", stem + "-poster.webp",
		"poster.jpg", "poster.png", "poster.webp",
		"cover.jpg", "cover.png", "folder.jpg", "folder.png",
		stem + ".jpg", stem + ".png",
	}
	return firstExistingIn(artSearchDirs(mediaPath), names)
}

func SidecarBackdrop(mediaPath string) string {
	stem := strings.TrimSuffix(filepath.Base(mediaPath), filepath.Ext(mediaPath))
	if sc, base, ok := readSidecarNames(mediaPath); ok && sc.fanart != "" {
		if p := resolveBeside(base, sc.fanart); p != "" {
			return p
		}
	}
	names := []string{
		stem + "-backdrop.jpg", stem + "-backdrop.png", stem + "-fanart.jpg",
		"fanart.jpg", "fanart.png", "backdrop.jpg", "backdrop.png",
		"background.jpg", "background.png", "banner.jpg",
	}
	return firstExistingIn(artSearchDirs(mediaPath), names)
}

func SidecarLogo(mediaPath string) string {
	stem := strings.TrimSuffix(filepath.Base(mediaPath), filepath.Ext(mediaPath))
	if sc, base, ok := readSidecarNames(mediaPath); ok && sc.logo != "" {
		if p := resolveBeside(base, sc.logo); p != "" {
			return p
		}
	}
	names := []string{
		stem + "-logo.png", stem + "-logo.webp", stem + "-clearlogo.png",
		"logo.png", "logo.webp", "clearlogo.png", "clearlogo.webp",
		"logo.jpg", "clearlogo.jpg",
	}
	return firstExistingIn(artSearchDirs(mediaPath), names)
}

type sidecarArtNames struct {
	poster string
	fanart string
	logo   string
}

func readSidecarNames(mediaPath string) (sidecarArtNames, string, bool) {
	dir := filepath.Dir(mediaPath)
	stem := strings.TrimSuffix(filepath.Base(mediaPath), filepath.Ext(mediaPath))
	candidates := []string{
		filepath.Join(dir, "coog.json"),
		filepath.Join(dir, stem+".coog.json"),
	}
	if seasonDirNameRe.MatchString(filepath.Base(dir)) {
		candidates = append(candidates, filepath.Join(filepath.Dir(dir), "coog.json"))
	}
	for _, p := range candidates {
		b, err := os.ReadFile(p)
		if err != nil || len(b) == 0 {
			continue
		}
		var raw struct {
			Poster string `json:"poster"`
			Fanart string `json:"fanart"`
			Logo   string `json:"logo"`
		}
		if err := json.Unmarshal(b, &raw); err != nil {
			continue
		}
		return sidecarArtNames{poster: raw.Poster, fanart: raw.Fanart, logo: raw.Logo}, filepath.Dir(p), true
	}
	return sidecarArtNames{}, "", false
}

func artSearchDirs(mediaPath string) []string {
	dir := filepath.Dir(mediaPath)
	art := dir
	if seasonDirNameRe.MatchString(filepath.Base(dir)) {
		art = filepath.Dir(dir)
	}
	if art != dir {
		return []string{dir, art}
	}
	return []string{dir}
}

var seasonDirNameRe = regexp.MustCompile(`(?i)^Season\s+(\d{1,2})$`)

func firstExistingIn(dirs []string, names []string) string {
	for _, dir := range dirs {
		if p := firstExisting(dir, names); p != "" {
			return p
		}
	}
	return ""
}

func resolveBeside(dir, name string) string {
	name = strings.TrimSpace(name)
	if name == "" || strings.Contains(name, "..") {
		return ""
	}
	if filepath.IsAbs(name) {
		return ""
	}
	p := filepath.Join(dir, filepath.Clean(name))
	if st, err := os.Stat(p); err == nil && st.Size() > 32 {
		return p
	}
	return ""
}

func firstExisting(dir string, names []string) string {
	for _, name := range names {
		p := filepath.Join(dir, name)
		if st, err := os.Stat(p); err == nil && st.Size() > 32 {
			return p
		}
	}
	return ""
}

func SidecarTrailer(mediaPath string) string {
	dir := filepath.Dir(mediaPath)
	stem := strings.TrimSuffix(filepath.Base(mediaPath), filepath.Ext(mediaPath))
	names := []string{
		stem + "-trailer.mp4", stem + ".trailer.mp4",
		"trailer.mp4", "trailer.mkv", "trailer.webm", "Trailer.mp4",
		"trailer-1.mp4", "official-trailer.mp4",
	}
	return firstExistingMin(dir, names, 64*1024)
}

func LibraryTrailer(libraryRoot, imdb string) string {
	if imdb == "" || libraryRoot == "" {
		return ""
	}
	dirs := []string{
		filepath.Join(libraryRoot, "Movies", "Trailers"),
		filepath.Join(libraryRoot, "Trailers"),
		filepath.Join(libraryRoot, "Series", "Trailers"),
	}
	for _, dir := range dirs {
		matches, _ := filepath.Glob(filepath.Join(dir, "movie_"+imdb+"*"))
		for _, p := range matches {
			if st, err := os.Stat(p); err == nil && st.Size() > 64*1024 {
				return p
			}
		}
		matches, _ = filepath.Glob(filepath.Join(dir, "*"+imdb+"*"))
		for _, p := range matches {
			ext := strings.ToLower(filepath.Ext(p))
			if ext != ".mp4" && ext != ".mkv" && ext != ".webm" {
				continue
			}
			if st, err := os.Stat(p); err == nil && st.Size() > 64*1024 {
				return p
			}
		}
	}
	return ""
}

func firstExistingMin(dir string, names []string, min int64) string {
	for _, name := range names {
		p := filepath.Join(dir, name)
		if st, err := os.Stat(p); err == nil && st.Size() > min {
			return p
		}
	}
	return ""
}

func (p *Prober) ExtractStill(ctx context.Context, src, dest string, durationMs int64) error {
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return err
	}
	seek := 8.0
	if durationMs > 2000 {
		seek = float64(durationMs) / 1000.0 * 0.12
		if seek > 40 {
			seek = 40
		}
		if seek < 2 {
			seek = 2
		}
	}
	stillCtx, cancelStill := context.WithTimeout(ctx, 25*time.Second)
	defer cancelStill()
	return p.runFFmpeg(stillCtx, dest,
		"-y", "-ss", fmt.Sprintf("%.1f", seek), "-i", src,
		"-frames:v", "1", "-q:v", "4",
		"-vf", "scale=1920:1080:force_original_aspect_ratio=increase,crop=1920:1080",
		dest,
	)
}

// ExtractStillAt grabs a single frame at positionMs and writes it to dest.
// JPEG destinations get a mild scale; PNG (logos) keep the frame without forced crop.
func (p *Prober) ExtractStillAt(ctx context.Context, src, dest string, positionMs int64) error {
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return err
	}
	seek := float64(positionMs) / 1000.0
	if seek < 0 {
		seek = 0
	}
	stillCtx, cancelStill := context.WithTimeout(ctx, 25*time.Second)
	defer cancelStill()
	ext := strings.ToLower(filepath.Ext(dest))
	args := []string{"-y", "-ss", fmt.Sprintf("%.3f", seek), "-i", src, "-frames:v", "1"}
	if ext == ".png" {
		args = append(args, "-vf", "scale=1920:-2:force_original_aspect_ratio=decrease", dest)
	} else {
		args = append(args, "-q:v", "2", "-vf", "scale=1920:-2:force_original_aspect_ratio=decrease", dest)
	}
	return p.runFFmpeg(stillCtx, dest, args...)
}

func (p *Prober) MaterializeImage(ctx context.Context, src, dest string) error {
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return err
	}
	ext := strings.ToLower(filepath.Ext(src))
	if ext == ".jpg" || ext == ".jpeg" {
		in, err := os.ReadFile(src)
		if err != nil {
			return err
		}
		return os.WriteFile(dest, in, 0o644)
	}
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	return p.runFFmpeg(ctx, dest, "-y", "-i", src, "-frames:v", "1", "-q:v", "4", dest)
}

func (p *Prober) runFFmpeg(ctx context.Context, dest string, args ...string) error {
	cmd := exec.CommandContext(ctx, p.ffmpeg, args...)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("ffmpeg still: %w (%s)", err, strings.TrimSpace(string(out)))
	}
	if st, err := os.Stat(dest); err != nil || st.Size() < 32 {
		return fmt.Errorf("ffmpeg still: empty output")
	}
	return nil
}
