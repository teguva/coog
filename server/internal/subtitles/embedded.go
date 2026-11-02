package subtitles

import (
	"context"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// Text codecs we can dump to SRT for overlay / autosync. Bitmap (PGS/VobSub) is skipped.
var textSubtitleCodecs = map[string]bool{
	"subrip":   true,
	"srt":      true,
	"ass":      true,
	"ssa":      true,
	"webvtt":   true,
	"mov_text": true,
	"ttml":     true,
}

// FindEmbedded lists text-based subtitle streams inside a video (ffprobe).
// IDs are emb:<streamIndex>:<absPath> so ResolveFile can extract on demand.
func FindEmbedded(ffprobe, videoPath string) []Track {
	path := strings.TrimSpace(videoPath)
	if path == "" {
		return nil
	}
	if st, err := os.Stat(path); err != nil || st.IsDir() {
		return nil
	}
	if strings.TrimSpace(ffprobe) == "" {
		ffprobe = "ffprobe"
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, ffprobe,
		"-v", "error",
		"-select_streams", "s",
		"-show_entries", "stream=index,codec_name:stream_tags=language,title",
		"-of", "json",
		path,
	)
	out, err := cmd.Output()
	if err != nil {
		return nil
	}
	var payload struct {
		Streams []struct {
			Index     int    `json:"index"`
			CodecName string `json:"codec_name"`
			Tags      struct {
				Language string `json:"language"`
				Title    string `json:"title"`
			} `json:"tags"`
		} `json:"streams"`
	}
	if err := json.Unmarshal(out, &payload); err != nil {
		return nil
	}
	outTracks := make([]Track, 0, len(payload.Streams))
	for _, s := range payload.Streams {
		codec := strings.ToLower(strings.TrimSpace(s.CodecName))
		if !textSubtitleCodecs[codec] {
			continue
		}
		lang := strings.ToLower(strings.TrimSpace(s.Tags.Language))
		if lang == "und" {
			lang = ""
		}
		title := strings.TrimSpace(s.Tags.Title)
		label := "Embedded"
		if lang != "" {
			label = strings.ToUpper(lang) + " · embedded"
		} else {
			label = fmt.Sprintf("Embedded #%d", s.Index)
		}
		if title != "" {
			label = label + " · " + title
		}
		outTracks = append(outTracks, Track{
			ID:       fmt.Sprintf("emb:%d:%s", s.Index, path),
			Source:   "embedded",
			Language: lang,
			Label:    label,
			Path:     path,
			Filename: filepath.Base(path),
		})
	}
	return outTracks
}

// ExtractEmbedded dumps one text subtitle stream to a cached .srt and returns its path.
func ExtractEmbedded(ffmpeg, dataPath, id string) (string, error) {
	index, videoPath, err := parseEmbeddedID(id)
	if err != nil {
		return "", err
	}
	if st, err := os.Stat(videoPath); err != nil || st.IsDir() {
		return "", fmt.Errorf("video for embedded subtitle not found")
	}
	if strings.TrimSpace(ffmpeg) == "" {
		ffmpeg = "ffmpeg"
	}
	if err := os.MkdirAll(CacheDir(dataPath), 0o755); err != nil {
		return "", err
	}
	sum := sha1.Sum([]byte(videoPath))
	dest := filepath.Join(CacheDir(dataPath), fmt.Sprintf("emb_%s_%d.srt", hex.EncodeToString(sum[:8]), index))
	if st, err := os.Stat(dest); err == nil && st.Size() > 32 {
		return dest, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()
	// Map by absolute stream index; ffmpeg writes SubRip even from ASS/mov_text.
	cmd := exec.CommandContext(ctx, ffmpeg,
		"-y",
		"-i", videoPath,
		"-map", fmt.Sprintf("0:%d", index),
		"-f", "srt",
		dest,
	)
	if out, err := cmd.CombinedOutput(); err != nil {
		_ = os.Remove(dest)
		msg := strings.TrimSpace(string(out))
		if msg == "" {
			msg = err.Error()
		}
		return "", fmt.Errorf("extract embedded subtitle: %s", truncate(msg, 240))
	}
	if st, err := os.Stat(dest); err != nil || st.Size() < 8 {
		_ = os.Remove(dest)
		return "", fmt.Errorf("embedded subtitle extract produced empty file")
	}
	return dest, nil
}

func parseEmbeddedID(id string) (int, string, error) {
	id = strings.TrimSpace(id)
	if !strings.HasPrefix(id, "emb:") {
		return 0, "", fmt.Errorf("not an embedded subtitle id")
	}
	rest := strings.TrimPrefix(id, "emb:")
	i := strings.IndexByte(rest, ':')
	if i <= 0 || i >= len(rest)-1 {
		return 0, "", fmt.Errorf("invalid embedded subtitle id")
	}
	index, err := strconv.Atoi(rest[:i])
	if err != nil || index < 0 {
		return 0, "", fmt.Errorf("invalid embedded stream index")
	}
	path := rest[i+1:]
	if !filepath.IsAbs(path) {
		return 0, "", fmt.Errorf("embedded subtitle path must be absolute")
	}
	return index, path, nil
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}
