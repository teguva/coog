package acquire

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"coog/internal/events"
	"coog/internal/jobs"
	"coog/internal/settings"
	"coog/internal/store"
	"coog/internal/streams"
)

func (r *Runner) run(ctx context.Context, job store.Job) {
	if cur, err := r.store.GetJob(job.ID); err == nil && (cur.Status == jobs.StatusCancelled || cur.Status == jobs.StatusPaused) {
		return
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	go r.watchCancel(ctx, cancel, job.ID)
	var err error
	switch job.Type {
	case jobs.TypeDebrid:
		err = r.runDebrid(ctx, &job)
	case jobs.TypeHTTP:
		err = r.runHTTP(ctx, &job)
	case jobs.TypeTorrent:
		err = r.runTorrent(ctx, &job)
	default:
		err = r.runYTDLP(ctx, &job)
	}
	if err != nil {
		if cur, e := r.store.GetJob(job.ID); e != nil || cur.Status == jobs.StatusCancelled || cur.Status == jobs.StatusPaused {
			if e != nil || cur.Status == jobs.StatusCancelled {
				jobs.Cleanup(r.cfg.DataPath, job.ID)
			}
			return
		}
		msg := events.Redact(err.Error())
		if line := lastLogLine(job.LogTail); line != "" && !strings.Contains(msg, line) {
			msg = msg + ": " + line
		}
		slog.Error("job failed", "id", job.ID, "err", msg)
		job.Status = jobs.StatusError
		job.Error = msg
		job.Ready = false
		_ = r.store.UpdateJob(job)
	}
}

func (r *Runner) watchCancel(ctx context.Context, cancel context.CancelFunc, id string) {
	tick := time.NewTicker(400 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
			job, err := r.store.GetJob(id)
			if err != nil {
				if errors.Is(err, store.ErrNotFound) {
					cancel()
					return
				}
				continue
			}
			if job.Status == jobs.StatusCancelled || job.Status == jobs.StatusPaused {
				cancel()
				return
			}
		}
	}
}

func (r *Runner) runDebrid(ctx context.Context, job *store.Job) error {
	cfg := settings.Load(r.cfg.DataPath)
	kind := "movie"
	season, episode := 0, 0
	imdb := job.ImdbID
	if rest, ok := strings.CutPrefix(job.URL, "imdb:"); ok {
		imdb, season, episode, kind = parseImdbRef(rest, imdb)
	}
	if imdb == "" {
		return fmt.Errorf("missing imdb id")
	}
	job.ImdbID = imdb
	job.Status = jobs.StatusDownloading
	_ = r.store.UpdateJob(*job)

	best := streams.Candidate{InfoHash: streams.InfoHash(job.InfoHash), Title: job.Title}
	// Episode jobs always consult torrentio so season-pack hashes keep the correct fileIndex,
	// even when the client already supplied an infoHash from Smart Play.
	if best.InfoHash == "" || season > 0 || episode > 0 {
		cands, err := streams.SearchTorrentio(ctx, cfg, kind, imdb, season, episode)
		if err != nil && best.InfoHash == "" {
			job.LogTail = events.Redact(err.Error())
			return err
		}
		if err == nil && len(cands) > 0 {
			if best.InfoHash != "" {
				hash := strings.ToLower(best.InfoHash)
				matched := false
				for _, c := range cands {
					if strings.ToLower(c.InfoHash) == hash {
						best = c
						matched = true
						break
					}
				}
				if !matched {
					best = streams.PickBestPreferred(cands, cfg)
				}
			} else {
				best = streams.PickBestPreferred(cands, cfg)
			}
		}
	}
	if best.InfoHash == "" && best.URL == "" {
		err := fmt.Errorf("no streams found")
		job.LogTail = err.Error()
		return err
	}
	if best.InfoHash != "" {
		job.InfoHash = best.InfoHash
	}
	if job.Quality == "" && best.Quality != "" {
		job.Quality = best.Quality
	}
	if job.SizeLabel == "" && best.SizeLabel != "" {
		job.SizeLabel = best.SizeLabel
	}
	if job.SizeBytes == 0 && best.Size > 0 {
		job.SizeBytes = best.Size
		if job.SizeLabel == "" {
			job.SizeLabel = streams.FormatSizeLabel(best.Size)
		}
	}
	if job.Pack == "" && best.Pack != "" {
		job.Pack = best.Pack
	}
	if len(job.Tags) == 0 && len(best.Tags) > 0 {
		job.Tags = best.Tags
	}
	if len(job.Languages) == 0 && len(best.Languages) > 0 {
		job.Languages = best.Languages
	}
	if job.ReleaseTitle == "" {
		if t := strings.TrimSpace(best.Title); t != "" {
			job.ReleaseTitle = t
		} else if n := strings.TrimSpace(best.Name); n != "" {
			job.ReleaseTitle = n
		}
	}
	_ = r.store.UpdateJob(*job)
	direct, err := streams.ResolveHTTP(ctx, cfg.RealDebridToken, best)
	if err != nil {
		if streams.ShouldFallbackLocal(err) && best.InfoHash != "" && localTorrentOK(best) {
			job.LogTail = events.Redact("Real-Debrid unavailable, downloading torrent locally: " + err.Error())
			job.Type = jobs.TypeTorrent
			job.InfoHash = best.InfoHash
			job.Status = jobs.StatusDownloading
			_ = r.store.UpdateJob(*job)
			return r.runTorrent(ctx, job)
		}
		if streams.ShouldFallbackLocal(err) && best.InfoHash != "" && !localTorrentOK(best) {
			job.LogTail = events.Redact(err.Error() + " — skipped local torrent (huge REMUX / no healthy peers expected). Pick a Cached / RD+ source.")
			return fmt.Errorf("%w — pick a Cached / RD+ source instead of this REMUX", err)
		}
		job.LogTail = events.Redact(err.Error())
		return err
	}
	// Keep imdb:tt:S:E on job.URL so libraryDest / episode job matching still work.
	job.Type = jobs.TypeHTTP
	_ = r.store.UpdateJob(*job)
	return r.pullAndPack(ctx, job, direct, "")
}

func (r *Runner) runHTTP(ctx context.Context, job *store.Job) error {
	return r.pullAndPack(ctx, job, job.URL, "")
}

func (r *Runner) pullAndPack(ctx context.Context, job *store.Job, mediaURL, referer string) error {
	work := jobs.Dir(r.cfg.DataPath, job.ID)
	hls := jobs.HLSDir(r.cfg.DataPath, job.ID)
	if err := os.MkdirAll(hls, 0o755); err != nil {
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

	sourcePath := jobs.SourcePath(r.cfg.DataPath, job.ID)
	source, err := os.Create(sourcePath)
	if err != nil {
		return err
	}
	defer source.Close()

	var mu sync.Mutex
	save := func() {
		mu.Lock()
		defer mu.Unlock()
		syncTail()
		_ = r.store.UpdateJob(*job)
	}
	var bytesTotal int64
	go r.inspectHTTPSource(ctx, job, mediaURL, &bytesTotal, &mu, save)

	if err := sniffHTTPMedia(ctx, mediaURL, referer); err != nil {
		job.LogTail = err.Error()
		syncTail()
		_ = r.store.UpdateJob(*job)
		return err
	}

	pr, pw := io.Pipe()
	pullArgs := httpPullArgs(mediaURL, referer)
	pull := exec.CommandContext(ctx, r.cfg.FFmpeg, pullArgs...)
	pull.Stdout = io.MultiWriter(source, pw)
	pull.Stderr = io.MultiWriter(os.Stderr, tail)

	pack := exec.CommandContext(ctx, r.cfg.FFmpeg,
		"-hide_banner", "-loglevel", "error",
		"-fflags", "+genpts+discardcorrupt",
		"-probesize", "32M",
		"-analyzeduration", "10M",
		"-f", "mpegts",
		"-i", "pipe:0",
		"-map", "0:v:0",
		"-map", "0:a:0?",
		"-c", "copy",
		"-f", "hls",
		"-hls_time", strconv.Itoa(jobs.SegmentTimeS),
		"-hls_list_size", "0",
		"-hls_playlist_type", "event",
		"-hls_flags", "independent_segments+omit_endlist",
		"-hls_segment_filename", filepath.Join(hls, "seg_%05d.ts"),
		jobs.PlaylistPath(r.cfg.DataPath, job.ID),
	)
	pack.Stdin = pr
	pack.Stderr = io.MultiWriter(os.Stderr, tail)

	if err := pack.Start(); err != nil {
		_ = pw.Close()
		return err
	}
	if err := pull.Start(); err != nil {
		_ = pw.Close()
		_ = pack.Process.Kill()
		return err
	}

	stopWatch := make(chan struct{})
	done := make(chan struct{})
	go func() {
		r.watchReady(ctx, job, sourcePath, stopWatch, &mu, save, &bytesTotal)
		close(done)
	}()

	pullErr := pull.Wait()
	_ = pw.Close()
	packErr := pack.Wait()
	close(stopWatch)
	<-done
	syncTail()

	if pullErr != nil || packErr != nil {
		if _, segs := jobs.PlaylistBufferedMs(jobs.PlaylistPath(r.cfg.DataPath, job.ID)); segs == 0 {
			slog.Warn("http pipe failed; retrying direct HLS", "id", job.ID, "pull", pullErr, "pack", packErr)
			if err := r.ffmpegHLSDirect(ctx, job, mediaURL, referer, sourcePath, hls, tail); err != nil {
				if pullErr != nil {
					return pullErr
				}
				return packErr
			}
		} else if packErr != nil {
			slog.Warn("ffmpeg hls exited", "id", job.ID, "err", packErr)
		}
	}
	_ = jobs.AppendEndList(jobs.PlaylistPath(r.cfg.DataPath, job.ID))
	return r.finishJob(ctx, job, sourcePath)
}

func (r *Runner) ffmpegHLSDirect(ctx context.Context, job *store.Job, mediaURL, referer, sourcePath, hls string, tail *logSink) error {
	run := func(withSource bool) error {
		args := httpInputArgs(mediaURL, referer)
		args = append(args,
			"-fflags", "+genpts+discardcorrupt",
			"-i", mediaURL,
			"-map", "0:v:0",
			"-map", "0:a:0?",
			"-c", "copy",
			"-f", "hls",
			"-hls_time", strconv.Itoa(jobs.SegmentTimeS),
			"-hls_list_size", "0",
			"-hls_playlist_type", "event",
			"-hls_flags", "independent_segments+omit_endlist",
			"-hls_segment_filename", filepath.Join(hls, "seg_%05d.ts"),
			jobs.PlaylistPath(r.cfg.DataPath, job.ID),
		)
		if withSource {
			args = append(args,
				"-map", "0:v:0",
				"-map", "0:a:0?",
				"-c", "copy",
				"-f", "mpegts",
				sourcePath,
			)
		}
		cmd := exec.CommandContext(ctx, r.cfg.FFmpeg, args...)
		cmd.Stderr = io.MultiWriter(os.Stderr, tail)
		return cmd.Run()
	}
	if err := run(true); err != nil {
		if err2 := run(false); err2 != nil {
			return err2
		}
	}
	if _, segs := jobs.PlaylistBufferedMs(jobs.PlaylistPath(r.cfg.DataPath, job.ID)); segs == 0 {
		return fmt.Errorf("direct HLS produced no segments")
	}
	return nil
}

// httpPullArgs downloads an HTTP(S)/HLS media URL into mpegts on stdout.
// Reconnect + HLS segment retries matter more than failing the job: brief CDN
// blips and host sleep/wake otherwise skip segments (default seg_max_retry=0).
func httpInputArgs(mediaURL, referer string) []string {
	args := []string{
		"-hide_banner", "-loglevel", "error",
		"-reconnect", "1",
		"-reconnect_streamed", "1",
		"-reconnect_on_network_error", "1",
		"-reconnect_at_eof", "1",
		"-reconnect_on_http_error", "5xx",
		"-reconnect_delay_max", "30",
		"-reconnect_max_retries", "30",
		"-reconnect_delay_total_max", "900",
		"-seg_max_retry", "20",
		"-probesize", "32M",
		"-analyzeduration", "10M",
		"-user_agent", streams.WebUserAgent(),
	}
	if referer != "" {
		args = append(args, "-referer", referer)
	}
	return args
}

func httpPullArgs(mediaURL, referer string) []string {
	return append(httpInputArgs(mediaURL, referer),
		"-i", mediaURL,
		"-map", "0:v:0",
		"-map", "0:a:0?",
		"-c", "copy",
		"-f", "mpegts",
		"pipe:1",
	)
}

func sniffHTTPMedia(ctx context.Context, mediaURL, referer string) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, mediaURL, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", streams.WebUserAgent())
	req.Header.Set("Range", "bytes=0-1023")
	if referer != "" {
		req.Header.Set("Referer", referer)
	}
	resp, err := (&http.Client{Timeout: 20 * time.Second}).Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusRequestedRangeNotSatisfiable || resp.StatusCode == http.StatusMethodNotAllowed {
		return nil
	}
	if resp.StatusCode >= 400 {
		return fmt.Errorf("source returned HTTP %d (not a playable video). Try another stream.", resp.StatusCode)
	}
	buf, _ := io.ReadAll(io.LimitReader(resp.Body, 1024))
	return rejectNonMedia(buf, resp.Header.Get("Content-Type"))
}

func rejectNonMedia(buf []byte, contentType string) error {
	head := strings.TrimSpace(string(buf))
	low := strings.ToLower(head)
	ctype := strings.ToLower(contentType)
	if strings.HasPrefix(low, "#extm3u") {
		return nil
	}
	if strings.Contains(ctype, "text/html") || strings.HasPrefix(low, "<!doctype") || strings.HasPrefix(low, "<html") || strings.HasPrefix(low, "<?xml") {
		return fmt.Errorf("source returned a web page instead of video. Try a Cached / RD+ torrent, not this file.")
	}
	if len(buf) >= 4 && buf[0] == 'P' && buf[1] == 'K' {
		return fmt.Errorf("source is a ZIP archive, not a video. Try another stream.")
	}
	if len(buf) >= 4 && string(buf[:4]) == "Rar!" {
		return fmt.Errorf("source is a RAR archive, not a video. Try another stream.")
	}
	if len(buf) >= 6 && buf[0] == 0x37 && buf[1] == 0x7A && buf[2] == 0xBC && buf[3] == 0xAF {
		return fmt.Errorf("source is a 7z archive, not a video. Try another stream.")
	}
	if strings.Contains(ctype, "json") || (strings.HasPrefix(low, "{") && strings.Contains(low, "error")) {
		return fmt.Errorf("source returned an error page instead of video. Try another stream.")
	}
	return nil
}

func (r *Runner) finishJob(ctx context.Context, job *store.Job, sourcePath string) error {
	cfg := settings.Load(r.cfg.DataPath)
	src := sourcePath
	if st, err := os.Stat(sourcePath); err != nil || st.Size() < 1024 {
		if pl := jobs.PlaylistPath(r.cfg.DataPath, job.ID); pl != "" {
			src = pl
		}
	}
	if !cfg.SaveToLibrary {
		job.Progress = 1
		job.Ready = true
		job.Status = jobs.StatusFinished
		job.Error = ""
		return r.store.UpdateJob(*job)
	}
	item, err := r.finalizeLibrary(ctx, job, src)
	if err != nil {
		return err
	}
	job.MediaID = item.ID
	job.OutputPath = item.Path
	if item.DurationMs > 0 {
		job.ExpectedDurationMs = item.DurationMs
		job.BufferedMs = item.DurationMs
	}
	job.Progress = 1
	job.Ready = true
	job.Status = jobs.StatusFinished
	job.Error = ""
	return r.store.UpdateJob(*job)
}

func parseImdbRef(ref, fallback string) (imdb string, season, episode int, kind string) {
	kind = "movie"
	imdb = fallback
	parts := splitColon(ref)
	if len(parts) == 0 {
		return imdb, 0, 0, kind
	}
	if parts[0] != "" {
		imdb = parts[0]
	}
	if len(parts) >= 3 {
		kind = "series"
		season, _ = strconv.Atoi(parts[1])
		episode, _ = strconv.Atoi(parts[2])
	}
	return imdb, season, episode, kind
}

func (r *Runner) inspectHTTPSource(ctx context.Context, job *store.Job, mediaURL string, bytesTotal *int64, mu *sync.Mutex, save func()) {
	if n := httpContentLength(ctx, mediaURL); n > 0 {
		atomic.StoreInt64(bytesTotal, n)
	}
	if r.prober == nil {
		return
	}
	ms, err := r.prober.Duration(ctx, mediaURL)
	if err != nil || ms <= 0 {
		return
	}
	mu.Lock()
	if ms > job.ExpectedDurationMs {
		job.ExpectedDurationMs = ms
	}
	mu.Unlock()
	save()
}

func httpContentLength(ctx context.Context, raw string) int64 {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return 0
	}
	client := &http.Client{Timeout: 12 * time.Second}
	if n := contentLengthFrom(ctx, client, http.MethodHead, raw, ""); n > 0 {
		return n
	}
	return contentLengthFrom(ctx, client, http.MethodGet, raw, "bytes=0-0")
}

func contentLengthFrom(ctx context.Context, client *http.Client, method, raw, rng string) int64 {
	req, err := http.NewRequestWithContext(ctx, method, raw, nil)
	if err != nil {
		return 0
	}
	if rng != "" {
		req.Header.Set("Range", rng)
	}
	resp, err := client.Do(req)
	if err != nil {
		return 0
	}
	defer resp.Body.Close()
	if resp.ContentLength > 0 && rng == "" {
		return resp.ContentLength
	}
	if n := parseContentRangeTotal(resp.Header.Get("Content-Range")); n > 0 {
		return n
	}
	if resp.ContentLength > 0 {
		return resp.ContentLength
	}
	return 0
}

func parseContentRangeTotal(header string) int64 {
	// bytes 0-0/12345
	_, rest, ok := strings.Cut(header, "/")
	if !ok {
		return 0
	}
	n, err := strconv.ParseInt(strings.TrimSpace(rest), 10, 64)
	if err != nil || n <= 0 {
		return 0
	}
	return n
}

func splitColon(s string) []string {
	out := []string{}
	cur := ""
	for _, r := range s {
		if r == ':' {
			out = append(out, cur)
			cur = ""
			continue
		}
		cur += string(r)
	}
	out = append(out, cur)
	return out
}
