package acquire

import (
	"context"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
)

func Pipe(ctx context.Context, ytdlp, pageURL string) (io.ReadCloser, func() error, error) {
	if ytdlp == "" {
		ytdlp = "yt-dlp"
	}
	cmd := exec.CommandContext(ctx, ytdlp,
		"--no-playlist",
		"--no-warnings",
		"-f", "b[ext=mp4]/bv*+ba/b",
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

// DownloadToFile downloads a YouTube (or similar) page to a local mp4 via yt-dlp.
func DownloadToFile(ctx context.Context, ytdlp, pageURL, dest string) error {
	if ytdlp == "" {
		ytdlp = "yt-dlp"
	}
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return err
	}
	tmp := dest + ".part"
	_ = os.Remove(tmp)
	cmd := exec.CommandContext(ctx, ytdlp,
		"--no-playlist",
		"--no-warnings",
		"-f", "best[height<=1080][ext=mp4]/best[ext=mp4]/b",
		"-o", tmp,
		"--",
		pageURL,
	)
	cmd.Stdout = io.Discard
	cmd.Stderr = io.Discard
	if err := cmd.Run(); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	if st, err := os.Stat(tmp); err != nil || st.Size() < 1024 {
		_ = os.Remove(tmp)
		return fmt.Errorf("trailer download empty")
	}
	return os.Rename(tmp, dest)
}
