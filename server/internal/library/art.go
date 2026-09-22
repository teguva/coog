package library

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

var (
	artQualityTagRE   = regexp.MustCompile(`(?i)\[\s*(?:(?:Bluray|BluRay|BDRip|WEB|WEBRip|WEB-DL|HDTV|DVD|DVDRip)-)?(2160p|1440p|1080p|720p|480p|360p|4K|2K|UHD|FHD|HD|SD)\s*\]`)
	artQualityLooseRE = regexp.MustCompile(`(?i)[.\-_\s](2160p|1080p|720p|480p|360p|4k|uhd|hdr10|hdr|hevc|x265|x264|h265|h264|av1|bluray|webrip|web-dl|webdl|hdtv|remux)`)
)

func IsSeasonDir(name string) bool {
	return seasonDirRe.MatchString(name)
}

// SeasonDir is the Season NN folder for an episode, or empty when the file
// is not stored that way.
func SeasonDir(mediaPath string) string {
	dir := filepath.Dir(mediaPath)
	if IsSeasonDir(filepath.Base(dir)) {
		return dir
	}
	return ""
}

// ArtDir is where identity and artwork live for a media file: the file's
// folder, or the show folder when the file sits in Season NN.
func ArtDir(mediaPath string) string {
	dir := filepath.Dir(mediaPath)
	if IsSeasonDir(filepath.Base(dir)) {
		return filepath.Dir(dir)
	}
	return dir
}

func CountVideos(dir string) int {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return 0
	}
	n := 0
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		if IsVideo(e.Name()) {
			n++
		}
	}
	return n
}

func stripArtQuality(stem string) string {
	s := artQualityTagRE.ReplaceAllString(stem, "")
	s = artQualityLooseRE.ReplaceAllString(s, "")
	return strings.Trim(s, " -._")
}

// DistinctVideoSceneKeys returns quality-stripped stem keys for videos in dir.
// Multiple keys mean the folder holds distinct titles (not just resolution variants).
func DistinctVideoSceneKeys(dir string) []string {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	seen := map[string]bool{}
	var keys []string
	for _, e := range entries {
		if e.IsDir() || !IsVideo(e.Name()) || IsSidecarVideo(e.Name()) {
			continue
		}
		stem := strings.TrimSuffix(e.Name(), filepath.Ext(e.Name()))
		key := strings.ToLower(stripArtQuality(stem))
		if key == "" {
			key = strings.ToLower(stem)
		}
		if seen[key] {
			continue
		}
		seen[key] = true
		keys = append(keys, key)
	}
	return keys
}

// NeedsStemPrefixedArt is true when a folder has multiple distinct titles
// (stem-prefixed poster/fanart). Resolution variants of one title share art.
func NeedsStemPrefixedArt(dir string) bool {
	return len(DistinctVideoSceneKeys(dir)) > 1
}
