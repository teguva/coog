package playback

import (
	"bufio"
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"coog/internal/jobs"
)

// RemuxDir holds on-demand HLS packs for awkward-audio library titles.
func RemuxDir(dataPath, mediaID string) string {
	return filepath.Join(dataPath, "remux", mediaID)
}

func RemuxPlaylist(dataPath, mediaID string) string {
	return filepath.Join(RemuxDir(dataPath, mediaID), jobs.PlaylistName)
}

func remuxStampPath(dir string) string {
	return filepath.Join(dir, ".source")
}

// RemuxCacheFresh reports whether an existing remux matches the current source file.
func RemuxCacheFresh(dataPath, mediaID, src string) bool {
	dir := RemuxDir(dataPath, mediaID)
	stamp, err := os.ReadFile(remuxStampPath(dir))
	if err != nil {
		return false
	}
	info, err := os.Stat(src)
	if err != nil {
		return false
	}
	want := fmt.Sprintf("%s\n%d\n%d", src, info.ModTime().Unix(), info.Size())
	return strings.TrimSpace(string(stamp)) == strings.TrimSpace(want)
}

func writeRemuxStamp(dir, src string) error {
	info, err := os.Stat(src)
	if err != nil {
		return err
	}
	body := fmt.Sprintf("%s\n%d\n%d\n", src, info.ModTime().Unix(), info.Size())
	return os.WriteFile(remuxStampPath(dir), []byte(body), 0o644)
}

// PlaylistHasMedia is true once ffmpeg has written at least one media segment.
func PlaylistHasMedia(playlist string) bool {
	f, err := os.Open(playlist)
	if err != nil {
		return false
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		if strings.HasPrefix(strings.TrimSpace(sc.Text()), "#EXTINF:") {
			return true
		}
	}
	return false
}

// StartAwkwardAudioHLS remuxes a finished library file to HLS: copy video, AAC audio.
// Caller owns the process lifetime (Wait / Kill).
func StartAwkwardAudioHLS(ctx context.Context, ffmpeg, src, outDir string) (*exec.Cmd, error) {
	if strings.TrimSpace(ffmpeg) == "" {
		ffmpeg = "ffmpeg"
	}
	if err := os.MkdirAll(outDir, 0o755); err != nil {
		return nil, err
	}
	_ = os.Remove(filepath.Join(outDir, jobs.PlaylistName))
	if hits, _ := filepath.Glob(filepath.Join(outDir, "seg_*.ts")); len(hits) > 0 {
		for _, h := range hits {
			_ = os.Remove(h)
		}
	}
	if err := writeRemuxStamp(outDir, src); err != nil {
		return nil, err
	}
	playlist := filepath.Join(outDir, jobs.PlaylistName)
	cmd := exec.CommandContext(ctx, ffmpeg,
		"-hide_banner", "-loglevel", "error",
		"-i", src,
		"-map", "0:V:0",
		"-map", "0:a:0?",
		"-c:v", "copy",
		"-c:a", "aac",
		"-ac", "2",
		"-b:a", "192k",
		"-f", "hls",
		"-hls_time", strconv.Itoa(jobs.SegmentTimeS),
		"-hls_list_size", "0",
		"-hls_playlist_type", "event",
		"-hls_flags", "independent_segments+omit_endlist",
		"-hls_segment_filename", filepath.Join(outDir, "seg_%05d.ts"),
		playlist,
	)
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	return cmd, nil
}

// WaitForPlaylist blocks until the playlist has media or the deadline passes.
func WaitForPlaylist(playlist string, timeout time.Duration) bool {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if PlaylistHasMedia(playlist) {
			return true
		}
		time.Sleep(200 * time.Millisecond)
	}
	return PlaylistHasMedia(playlist)
}
