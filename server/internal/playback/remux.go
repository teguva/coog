package playback

import (
	"context"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"coog/internal/jobs"
)

// remuxFormatVersion bumps when packing changes so stale caches rebuild.
// v5: AAC-only sidecar (no video copy) — Jellyfin "Direct Stream" style.
const remuxFormatVersion = "v5-mka-aac-only"

// AwkwardAudioName is the on-disk AAC-only remux (no video).
const AwkwardAudioName = "audio.mka"

// RemuxDir holds on-demand AAC remuxes for awkward-audio library titles.
func RemuxDir(dataPath, mediaID string) string {
	return filepath.Join(dataPath, "remux", mediaID)
}

func RemuxFile(dataPath, mediaID string) string {
	return filepath.Join(RemuxDir(dataPath, mediaID), AwkwardAudioName)
}

// RemuxPlaylist is kept for callers that still reference the old path helper.
func RemuxPlaylist(dataPath, mediaID string) string {
	return RemuxFile(dataPath, mediaID)
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
	want := fmt.Sprintf("%s\n%s\n%d\n%d", remuxFormatVersion, src, info.ModTime().Unix(), info.Size())
	return strings.TrimSpace(string(stamp)) == strings.TrimSpace(want)
}

func writeRemuxStamp(dir, src string) error {
	info, err := os.Stat(src)
	if err != nil {
		return err
	}
	body := fmt.Sprintf("%s\n%s\n%d\n%d\n", remuxFormatVersion, src, info.ModTime().Unix(), info.Size())
	return os.WriteFile(remuxStampPath(dir), []byte(body), 0o644)
}

// RemuxFileReady is true once ffmpeg has written enough AAC to start playback.
func RemuxFileReady(path string) bool {
	st, err := os.Stat(path)
	if err != nil {
		return false
	}
	// Audio-only — a few dozen KiB is enough to open.
	return st.Size() >= 64*1024
}

func clearRemuxOut(outDir string) error {
	if err := os.MkdirAll(outDir, 0o755); err != nil {
		return err
	}
	_ = os.Remove(filepath.Join(outDir, AwkwardAudioName))
	// Drop legacy full-file remuxes / HLS (were multi‑GB video duplicates).
	_ = os.Remove(filepath.Join(outDir, "audio.mkv"))
	_ = os.Remove(filepath.Join(outDir, jobs.PlaylistName))
	_ = os.Remove(filepath.Join(outDir, "init.mp4"))
	for _, pattern := range []string{"seg_*.ts", "seg_*.m4s"} {
		if hits, _ := filepath.Glob(filepath.Join(outDir, pattern)); len(hits) > 0 {
			for _, h := range hits {
				_ = os.Remove(h)
			}
		}
	}
	return nil
}

// PurgeLegacyFullRemuxes deletes old video-duplicating remux caches under data/remux.
func PurgeLegacyFullRemuxes(dataPath string) {
	root := filepath.Join(dataPath, "remux")
	entries, err := os.ReadDir(root)
	if err != nil {
		return
	}
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		dir := filepath.Join(root, e.Name())
		_ = os.Remove(filepath.Join(dir, "audio.mkv"))
		_ = os.Remove(filepath.Join(dir, jobs.PlaylistName))
		_ = os.Remove(filepath.Join(dir, "init.mp4"))
		if hits, _ := filepath.Glob(filepath.Join(dir, "seg_*")); len(hits) > 0 {
			for _, h := range hits {
				_ = os.Remove(h)
			}
		}
	}
}

func awkwardAudioOnlyArgs(srcOrPipe, outPath string, fromPipe bool, demux string) []string {
	args := []string{"-hide_banner", "-loglevel", "error", "-y"}
	if fromPipe {
		if demux != "" {
			args = append(args, "-f", demux)
		}
		args = append(args, "-probesize", "2M", "-analyzeduration", "2M", "-i", "pipe:0")
	} else {
		args = append(args,
			"-probesize", "2M",
			"-analyzeduration", "2M",
			"-i", srcOrPipe,
		)
	}
	// Audio only — Jellyfin "Direct Stream": leave original video on disk untouched.
	return append(args,
		"-map", "0:a?",
		"-c:a", "aac",
		"-ac", "2",
		"-b:a", "192k",
		"-vn",
		"-f", "matroska",
		outPath,
	)
}

func pipeDemuxFor(src string) string {
	switch strings.ToLower(filepath.Ext(src)) {
	case ".mkv":
		return "matroska"
	case ".mp4", ".m4v", ".mov":
		return "mp4"
	case ".ts", ".m2ts":
		return "mpegts"
	case ".webm":
		return "webm"
	default:
		return ""
	}
}

// JobRemuxPath is the progressive AAC-only remux for a download job.
func JobRemuxPath(dataPath, jobID string) string {
	return filepath.Join(jobs.HLSDir(dataPath, jobID), AwkwardAudioName)
}

// StartAwkwardAudioRemux remuxes awkward audio from a finished library file to AAC-only.
func StartAwkwardAudioRemux(ctx context.Context, ffmpeg, src, outDir string) (*exec.Cmd, error) {
	if strings.TrimSpace(ffmpeg) == "" {
		ffmpeg = "ffmpeg"
	}
	if err := clearRemuxOut(outDir); err != nil {
		return nil, err
	}
	if err := writeRemuxStamp(outDir, src); err != nil {
		return nil, err
	}
	outPath := filepath.Join(outDir, AwkwardAudioName)
	cmd := exec.CommandContext(ctx, ffmpeg, awkwardAudioOnlyArgs(src, outPath, false, "")...)
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	return cmd, nil
}

// StartGrowingAwkwardAudioRemux remuxes audio from a still-downloading original into AAC-only.
func StartGrowingAwkwardAudioRemux(ctx context.Context, ffmpeg, src, outDir string, expectedTotal int64) (*exec.Cmd, error) {
	if strings.TrimSpace(ffmpeg) == "" {
		ffmpeg = "ffmpeg"
	}
	if err := clearRemuxOut(outDir); err != nil {
		return nil, err
	}
	pr, pw := io.Pipe()
	go followGrowingFile(ctx, src, pw, expectedTotal)

	outPath := filepath.Join(outDir, AwkwardAudioName)
	cmd := exec.CommandContext(ctx, ffmpeg, awkwardAudioOnlyArgs(src, outPath, true, pipeDemuxFor(src))...)
	cmd.Stdin = pr
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		_ = pw.Close()
		_ = pr.Close()
		return nil, err
	}
	return cmd, nil
}

func followGrowingFile(ctx context.Context, src string, w *io.PipeWriter, expectedTotal int64) {
	defer w.Close()
	f, err := os.Open(src)
	if err != nil {
		_ = w.CloseWithError(err)
		return
	}
	defer f.Close()

	var offset int64
	idleRounds := 0
	buf := make([]byte, 256*1024)
	for {
		if err := ctx.Err(); err != nil {
			return
		}
		st, err := f.Stat()
		if err != nil {
			_ = w.CloseWithError(err)
			return
		}
		size := st.Size()
		if size > offset {
			idleRounds = 0
			if _, err := f.Seek(offset, io.SeekStart); err != nil {
				_ = w.CloseWithError(err)
				return
			}
			for offset < size {
				toRead := size - offset
				if toRead > int64(len(buf)) {
					toRead = int64(len(buf))
				}
				n, readErr := f.Read(buf[:toRead])
				if n > 0 {
					if _, writeErr := w.Write(buf[:n]); writeErr != nil {
						return
					}
					offset += int64(n)
				}
				if readErr == io.EOF {
					break
				}
				if readErr != nil {
					_ = w.CloseWithError(readErr)
					return
				}
			}
			if expectedTotal > 0 && offset >= expectedTotal {
				return
			}
			continue
		}
		if expectedTotal > 0 && offset >= expectedTotal {
			return
		}
		idleRounds++
		if expectedTotal <= 0 && idleRounds >= 50 {
			return
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(300 * time.Millisecond):
		}
	}
}

// WaitForRemuxFile blocks until the AAC sidecar is playable or the deadline passes.
func WaitForRemuxFile(path string, timeout time.Duration) bool {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if RemuxFileReady(path) {
			return true
		}
		time.Sleep(150 * time.Millisecond)
	}
	return RemuxFileReady(path)
}

// PlaylistHasMedia kept for legacy progressive HLS dirs.
func PlaylistHasMedia(playlist string) bool {
	f, err := os.Open(playlist)
	if err != nil {
		return false
	}
	defer f.Close()
	buf := make([]byte, 4096)
	n, _ := f.Read(buf)
	return strings.Contains(string(buf[:n]), "#EXTINF:")
}

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

// Deprecated aliases.
func StartAwkwardAudioHLS(ctx context.Context, ffmpeg, src, outDir string) (*exec.Cmd, error) {
	return StartAwkwardAudioRemux(ctx, ffmpeg, src, outDir)
}

func StartGrowingAwkwardAudioHLS(ctx context.Context, ffmpeg, src, outDir string, expectedTotal int64) (*exec.Cmd, error) {
	return StartGrowingAwkwardAudioRemux(ctx, ffmpeg, src, outDir, expectedTotal)
}
