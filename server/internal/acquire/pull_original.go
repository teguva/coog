package acquire

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"mime"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"coog/internal/jobs"
	"coog/internal/store"
	"coog/internal/streams"
)

// pullAndPack downloads the original remote file to disk for direct Range play.
// No ffmpeg remux / HLS — Stremio/Jellyfin-style byte copy onto the NAS.
func (r *Runner) pullAndPack(ctx context.Context, job *store.Job, mediaURL, referer string) error {
	work := jobs.Dir(r.cfg.DataPath, job.ID)
	if err := os.MkdirAll(work, 0o755); err != nil {
		return err
	}
	tail := newLogSink()
	if job.LogTail != "" {
		_, _ = tail.Write([]byte(job.LogTail + "\n"))
	}
	syncTail := func() {
		job.LogTail = tail.String()
	}
	job.WorkDir = work
	job.Status = jobs.StatusDownloading
	syncTail()
	_ = r.store.UpdateJob(*job)

	if err := mediaURLReady(mediaURL); err != nil {
		job.LogTail = err.Error()
		syncTail()
		_ = r.store.UpdateJob(*job)
		return err
	}

	if err := jobs.WriteOrigin(r.cfg.DataPath, job.ID, mediaURL, referer); err != nil {
		slog.Warn("write origin url", "id", job.ID, "err", err)
	}

	var mu sync.Mutex
	save := func() {
		mu.Lock()
		defer mu.Unlock()
		syncTail()
		_ = r.store.UpdateJob(*job)
	}
	var bytesTotal int64
	go r.inspectHTTPSource(ctx, job, mediaURL, referer, &bytesTotal, &mu, save)

	if referer == "" {
		if err := sniffHTTPMedia(ctx, mediaURL, referer); err != nil {
			job.LogTail = err.Error()
			syncTail()
			_ = r.store.UpdateJob(*job)
			return err
		}
	}

	sourcePath, err := r.downloadHTTPOriginal(ctx, job, mediaURL, referer, &bytesTotal, &mu, save, tail)
	if err != nil {
		syncTail()
		_ = r.store.UpdateJob(*job)
		return err
	}
	syncTail()
	return r.finishJob(ctx, job, sourcePath)
}

func (r *Runner) downloadHTTPOriginal(
	ctx context.Context,
	job *store.Job,
	mediaURL, referer string,
	bytesTotal *int64,
	mu *sync.Mutex,
	save func(),
	tail *logSink,
) (string, error) {
	// Resolve destination before the GET so a paused/errored job can resume
	// the same partial file with a Range request instead of rewriting from 0.
	if prev, err := jobs.ReadSourceMeta(r.cfg.DataPath, job.ID); err == nil && prev.TotalBytes > 0 {
		atomic.StoreInt64(bytesTotal, prev.TotalBytes)
	}
	sourcePath := jobs.SourcePath(r.cfg.DataPath, job.ID)
	resumeFrom := fileSize(sourcePath)
	if resumeFrom > 0 {
		known := atomic.LoadInt64(bytesTotal)
		if known <= 0 {
			known = job.SizeBytes
		}
		if known > 0 && downloadLooksComplete(job, sourcePath, known) {
			slog.Info("original download already complete", "id", job.ID, "path", sourcePath, "bytes", resumeFrom)
			return sourcePath, nil
		}
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, mediaURL, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", streams.WebUserAgent())
	if referer != "" {
		req.Header.Set("Referer", referer)
	}
	if resumeFrom > 0 {
		req.Header.Set("Range", fmt.Sprintf("bytes=%d-", resumeFrom))
	}
	client := &http.Client{Timeout: 0}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	appending := false
	switch {
	case resumeFrom > 0 && resp.StatusCode == http.StatusPartialContent:
		appending = true
	case resumeFrom > 0 && resp.StatusCode == http.StatusRequestedRangeNotSatisfiable:
		// Partial is already the full object (or server rejects the open end).
		if downloadLooksComplete(job, sourcePath, atomic.LoadInt64(bytesTotal)) {
			return sourcePath, nil
		}
		return "", fmt.Errorf("download HTTP %d", resp.StatusCode)
	case resp.StatusCode >= 200 && resp.StatusCode < 300:
		if resumeFrom > 0 {
			// Server ignored Range — rewrite from byte 0.
			_, _ = tail.Write([]byte("remote ignored Range; restarting download from 0\n"))
			resumeFrom = 0
		}
	default:
		return "", fmt.Errorf("download HTTP %d", resp.StatusCode)
	}

	total := resp.ContentLength
	if resp.StatusCode == http.StatusPartialContent {
		if n := parseContentRangeTotal(resp.Header.Get("Content-Range")); n > 0 {
			total = n
		} else if known := atomic.LoadInt64(bytesTotal); known > 0 {
			total = known
		} else if resumeFrom > 0 && resp.ContentLength > 0 {
			total = resumeFrom + resp.ContentLength
		}
	}
	if total <= 0 {
		total = atomic.LoadInt64(bytesTotal)
	}
	if total > 0 {
		atomic.StoreInt64(bytesTotal, total)
		mu.Lock()
		if job.SizeBytes <= 0 {
			job.SizeBytes = total
			job.SizeLabel = streams.FormatSizeLabel(total)
		}
		if resumeFrom > 0 && total > 0 {
			if p := float64(resumeFrom) / float64(total); p > job.Progress {
				job.Progress = p
			}
		}
		mu.Unlock()
	}

	filename := filenameFromResponse(resp, mediaURL)
	ctype := strings.TrimSpace(resp.Header.Get("Content-Type"))
	if i := strings.Index(ctype, ";"); i >= 0 {
		ctype = strings.TrimSpace(ctype[:i])
	}
	meta := jobs.SourceMeta{Filename: filename, ContentType: ctype, TotalBytes: total}
	if prev, err := jobs.ReadSourceMeta(r.cfg.DataPath, job.ID); err == nil {
		if meta.Filename == "" {
			meta.Filename = prev.Filename
		}
		if meta.ContentType == "" {
			meta.ContentType = prev.ContentType
		}
		if meta.TotalBytes <= 0 {
			meta.TotalBytes = prev.TotalBytes
		}
	}
	if err := jobs.WriteSourceMeta(r.cfg.DataPath, job.ID, meta); err != nil {
		return "", err
	}

	// Meta may rename the preferred path (source → source.mkv); re-resolve.
	sourcePath = jobs.SourcePath(r.cfg.DataPath, job.ID)
	if !appending {
		resumeFrom = 0
	} else if got := fileSize(sourcePath); got != resumeFrom {
		// Path changed under us; only append when the partial is still here.
		appending = false
		resumeFrom = 0
	}

	var out *os.File
	if appending && resumeFrom > 0 {
		out, err = os.OpenFile(sourcePath, os.O_WRONLY|os.O_APPEND, 0o644)
		_, _ = tail.Write([]byte(fmt.Sprintf("resuming download at byte %d\n", resumeFrom)))
		slog.Info("resuming original download", "id", job.ID, "from", resumeFrom, "total", total)
	} else {
		_ = os.Remove(sourcePath)
		out, err = os.Create(sourcePath)
	}
	if err != nil {
		return "", err
	}
	defer out.Close()
	save()

	stopWatch := make(chan struct{})
	done := make(chan struct{})
	go func() {
		r.watchReady(ctx, job, sourcePath, stopWatch, mu, save, bytesTotal)
		close(done)
	}()

	_, copyErr := io.Copy(out, resp.Body)
	_ = out.Sync()
	close(stopWatch)
	waitClosed(done, 3*time.Second)
	if copyErr != nil && ctx.Err() == nil {
		_, _ = tail.Write([]byte(copyErr.Error() + "\n"))
		return sourcePath, copyErr
	}
	if !downloadLooksComplete(job, sourcePath, atomic.LoadInt64(bytesTotal)) {
		return sourcePath, fmt.Errorf(
			"Real-Debrid dropped this transfer before it finished (%s). Pick another source — large remuxes often fail",
			job.SizeLabel,
		)
	}
	slog.Info("original download complete", "id", job.ID, "path", sourcePath, "bytes", fileSize(sourcePath))
	return sourcePath, nil
}

func filenameFromResponse(resp *http.Response, mediaURL string) string {
	if cd := resp.Header.Get("Content-Disposition"); cd != "" {
		if _, params, err := mime.ParseMediaType(cd); err == nil {
			if name := strings.TrimSpace(params["filename"]); name != "" {
				return filepath.Base(name)
			}
		}
	}
	if u, err := url.Parse(mediaURL); err == nil {
		base := path.Base(u.Path)
		if base != "" && base != "." && base != "/" && strings.Contains(base, ".") {
			return base
		}
	}
	ctype := strings.ToLower(resp.Header.Get("Content-Type"))
	switch {
	case strings.Contains(ctype, "matroska"), strings.Contains(ctype, "x-mkv"):
		return "source.mkv"
	case strings.Contains(ctype, "mp4"), strings.Contains(ctype, "m4v"):
		return "source.mp4"
	case strings.Contains(ctype, "mpegts"), strings.Contains(ctype, "mp2t"):
		return "source.ts"
	case strings.Contains(ctype, "avi"):
		return "source.avi"
	default:
		return "source.bin"
	}
}

func fileSize(path string) int64 {
	st, err := os.Stat(path)
	if err != nil {
		return 0
	}
	return st.Size()
}
