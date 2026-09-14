package acquire

import (
	"context"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// Prefer the highest available stream up to 4K (YouTube often only has
// AV1/VP9 above 1080p). Fall back to H.264+AAC, then any progressive.
// Progressive best[ext=mp4] alone often resolves to 360p–720p.
const trailerFormat = "bv*[height<=2160]+ba/bv*[vcodec^=avc1][height<=2160]+ba[acodec^=mp4a]/b[height<=2160]/b"

func Pipe(ctx context.Context, ytdlp, pageURL string) (io.ReadCloser, func() error, error) {
	if ytdlp == "" {
		ytdlp = "yt-dlp"
	}
	cmd := exec.CommandContext(ctx, ytdlp,
		"--no-playlist",
		"--no-warnings",
		"-f", trailerFormat,
		"--merge-output-format", "mpegts",
		"-o", "-",
		"--",
		pageURL,
	)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, nil, err
	}
	cmd.Stderr = io.Discard
	if err := cmd.Start(); err != nil {
		return nil, nil, err
	}
	return stdout, cmd.Wait, nil
}

// StreamAndCache pipes yt-dlp stdout to the caller while writing the same bytes
// to dest.part → dest so the next request is a warm cache hit.
// dest should be an .hq.ts path when merging DASH to a pipe (mpegts).
func StreamAndCache(ctx context.Context, ytdlp, pageURL, dest string) (io.ReadCloser, func() error, error) {
	if ytdlp == "" {
		ytdlp = "yt-dlp"
	}
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return nil, nil, err
	}
	tmp := dest + ".part"
	_ = os.Remove(tmp)
	part, err := os.Create(tmp)
	if err != nil {
		return nil, nil, err
	}
	cmd := exec.CommandContext(ctx, ytdlp,
		"--no-playlist",
		"--no-warnings",
		"-f", trailerFormat,
		"--merge-output-format", "mpegts",
		"-o", "-",
		"--",
		pageURL,
	)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		_ = part.Close()
		_ = os.Remove(tmp)
		return nil, nil, err
	}
	cmd.Stderr = io.Discard
	if err := cmd.Start(); err != nil {
		_ = part.Close()
		_ = os.Remove(tmp)
		return nil, nil, err
	}
	pr, pw := io.Pipe()
	done := make(chan error, 1)
	go func() {
		mw := io.MultiWriter(pw, part)
		_, copyErr := io.Copy(mw, stdout)
		waitErr := cmd.Wait()
		_ = part.Close()
		var final error
		switch {
		case copyErr != nil:
			final = copyErr
		case waitErr != nil:
			final = waitErr
		default:
			if st, err := os.Stat(tmp); err != nil || st.Size() < 1024 {
				final = fmt.Errorf("trailer download empty")
			} else if err := os.Rename(tmp, dest); err != nil {
				final = err
			}
		}
		if final != nil {
			_ = os.Remove(tmp)
			_ = pw.CloseWithError(final)
		} else {
			_ = pw.Close()
		}
		done <- final
	}()
	return pr, func() error { return <-done }, nil
}

// DownloadToFile downloads a YouTube (or similar) page to a local mp4 via yt-dlp.
// Uses a work directory so merge temp names (*.f401.mp4) never collide with dest.part.
func DownloadToFile(ctx context.Context, ytdlp, pageURL, dest string) error {
	if ytdlp == "" {
		ytdlp = "yt-dlp"
	}
	dir := filepath.Dir(dest)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	work := filepath.Join(dir, "."+filepath.Base(dest)+".work")
	_ = os.RemoveAll(work)
	if err := os.MkdirAll(work, 0o755); err != nil {
		return err
	}
	defer func() { _ = os.RemoveAll(work) }()
	outTpl := filepath.Join(work, "trailer.%(ext)s")
	cmd := exec.CommandContext(ctx, ytdlp,
		"--no-playlist",
		"--no-warnings",
		"-f", trailerFormat,
		"--merge-output-format", "mp4",
		"-o", outTpl,
		"--",
		pageURL,
	)
	cmd.Stdout = io.Discard
	var errBuf strings.Builder
	cmd.Stderr = &errBuf
	if err := cmd.Run(); err != nil {
		msg := strings.TrimSpace(errBuf.String())
		if msg != "" {
			return fmt.Errorf("yt-dlp: %w (%s)", err, msg)
		}
		return err
	}
	produced, err := findTrailerDownload(work)
	if err != nil {
		return err
	}
	tmp := dest + ".part"
	_ = os.Remove(tmp)
	if err := copyFile(produced, tmp); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	if st, err := os.Stat(tmp); err != nil || st.Size() < 1024 {
		_ = os.Remove(tmp)
		return fmt.Errorf("trailer download empty")
	}
	return os.Rename(tmp, dest)
}

func findTrailerDownload(workDir string) (string, error) {
	entries, err := os.ReadDir(workDir)
	if err != nil {
		return "", err
	}
	var best string
	var bestSize int64
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		name := e.Name()
		ext := strings.ToLower(filepath.Ext(name))
		if ext != ".mp4" && ext != ".mkv" && ext != ".webm" && ext != ".ts" {
			continue
		}
		// Skip in-progress yt-dlp fragments.
		if strings.Contains(name, ".f") && strings.Count(name, ".") >= 3 {
			continue
		}
		info, err := e.Info()
		if err != nil || info.Size() < 1024 {
			continue
		}
		if info.Size() > bestSize {
			bestSize = info.Size()
			best = filepath.Join(workDir, name)
		}
	}
	if best == "" {
		return "", fmt.Errorf("trailer download empty")
	}
	return best, nil
}
