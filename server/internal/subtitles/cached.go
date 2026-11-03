package subtitles

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

// FindCached lists OpenSubtitles downloads already on disk that look related to
// this video / query. Used when SearchOnline is false so testing can reuse
// previously downloaded packs without hitting the OS API.
func FindCached(dataPath, videoPath, query string) []Track {
	dir := CacheDir(dataPath)
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	videoBase := ""
	if videoPath != "" {
		videoBase = filepath.Base(videoPath)
	}
	q := strings.TrimSpace(query)
	type scored struct {
		track Track
		score int
	}
	var hits []scored
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		name := e.Name()
		// Skip embedded extracts (emb_…) and non-subtitle junk.
		if strings.HasPrefix(name, "emb_") {
			continue
		}
		ext := strings.ToLower(filepath.Ext(name))
		if ext != ".srt" && ext != ".vtt" && ext != ".ass" && ext != ".ssa" {
			continue
		}
		// OS cache naming: {fileId}_{originalName}.srt
		under := strings.IndexByte(name, '_')
		if under <= 0 {
			continue
		}
		fileID, err := strconv.Atoi(name[:under])
		if err != nil || fileID <= 0 {
			continue
		}
		rest := name[under+1:]
		// Score filename only — never put query into the haystack (that made every
		// cache file look like a hit because hay contained "Shrek the Third").
		score := releaseScore(videoBase, "", rest)
		if score < 8 && q != "" {
			score = releaseScore(q+".mkv", "", rest)
		}
		if score < 8 {
			continue
		}
		lang := guessLangFromName(rest)
		path := filepath.Join(dir, name)
		label := strings.ToUpper(lang)
		if label == "" {
			label = "SUB"
		}
		label += " · cached"
		if rest != "" {
			label += " · " + rest
		}
		hits = append(hits, scored{
			score: score,
			track: Track{
				ID:         fmt.Sprintf("os:%d", fileID),
				Source:     "opensubtitles",
				Language:   lang,
				Label:      label,
				Path:       path,
				Filename:   rest,
				MatchScore: score,
			},
		})
	}
	sort.SliceStable(hits, func(i, j int) bool {
		if hits[i].score != hits[j].score {
			return hits[i].score > hits[j].score
		}
		return hits[i].track.Filename < hits[j].track.Filename
	})
	out := make([]Track, 0, len(hits))
	for _, h := range hits {
		out = append(out, h.track)
	}
	return out
}

func guessLangFromName(name string) string {
	low := strings.ToLower(name)
	// Common patterns: .en.srt, .eng.srt, _et_, -ell-
	for _, code := range []string{
		"eng", "en", "est", "et", "ell", "gre", "el", "heb", "he",
		"hun", "hu", "pol", "pl", "rum", "ron", "ro", "vie", "vi",
		"may", "ms", "fre", "fra", "fr", "ger", "deu", "de", "spa", "es",
		"ita", "it", "por", "pt", "rus", "ru", "jpn", "ja", "chi", "zho", "zh",
	} {
		needle := "." + code + "."
		if strings.Contains(low, needle) ||
			strings.Contains(low, "_"+code+"_") ||
			strings.Contains(low, "-"+code+"-") ||
			strings.HasSuffix(low, "."+code+".srt") ||
			strings.HasSuffix(low, "."+code+".vtt") {
			return normalizeEmbeddedLang(code)
		}
	}
	return ""
}
