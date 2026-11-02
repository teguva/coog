package acquire

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
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

	done := make(chan error, 1)
	go func() {
		var err error
		switch effectiveJobType(job) {
		case jobs.TypeDebrid:
			job.Type = jobs.TypeDebrid
			err = r.runDebrid(ctx, &job)
		case jobs.TypeHTTP:
			err = r.runHTTP(ctx, &job)
		case jobs.TypeTorrent:
			err = r.runTorrent(ctx, &job)
		default:
			err = r.runYTDLP(ctx, &job)
		}
		done <- err
	}()

	var err error
	select {
	case err = <-done:
	case <-ctx.Done():
		select {
		case err = <-done:
		case <-time.After(12 * time.Second):
			slog.Error("abandoned stuck job after cancel", "id", job.ID)
			jobs.Cleanup(r.cfg.DataPath, job.ID)
			return
		}
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
	job.Type = jobs.TypeDebrid
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
					best = streams.PickBestPreferred(cands, cfg, kind)
				}
			} else {
				best = streams.PickBestPreferred(cands, cfg, kind)
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
	if isWebCandidate(best) {
		job.Type = jobs.TypeYTDLP
		job.URL = strings.TrimSpace(best.URL)
		_ = r.store.UpdateJob(*job)
		return r.runWebEmbed(ctx, job)
	}
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
	// Keep type=debrid and imdb: URL. Switching to http left catalog jobs
	// retrying ffmpeg against imdb:tt… after a worker restart.
	return r.pullAndPack(ctx, job, direct, "")
}

func (r *Runner) runHTTP(ctx context.Context, job *store.Job) error {
	return r.pullAndPack(ctx, job, job.URL, "")
}

func sniffHTTPMedia(ctx context.Context, mediaURL, referer string) error {
	if err := mediaURLReady(mediaURL); err != nil {
		return err
	}
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
		return fmt.Errorf("download produced no usable file")
	}
	total := job.SizeBytes
	if meta, err := jobs.ReadSourceMeta(r.cfg.DataPath, job.ID); err == nil && meta.TotalBytes > 0 {
		total = meta.TotalBytes
	}
	if !downloadLooksComplete(job, src, total) {
		return fmt.Errorf(
			"Real-Debrid dropped this transfer before it finished (%s). Pick another source — large remuxes often fail",
			job.SizeLabel,
		)
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
	if item.DurationMs > job.ExpectedDurationMs {
		job.ExpectedDurationMs = item.DurationMs
	}
	if item.DurationMs > 0 {
		job.BufferedMs = item.DurationMs
	}
	job.Progress = 1
	job.Ready = true
	job.Status = jobs.StatusFinished
	job.Error = ""
	return r.store.UpdateJob(*job)
}

// downloadLooksComplete rejects truncated RD/HTTP/torrent byte copies.
// Original pulls are bit-identical, so size must land close to Content-Length / torrent length.
func downloadLooksComplete(job *store.Job, sourcePath string, bytesTotal int64) bool {
	st, err := os.Stat(sourcePath)
	if err != nil {
		return false
	}
	size := st.Size()
	if size < 256*1024 {
		return false
	}
	if bytesTotal <= 0 && job != nil {
		bytesTotal = job.SizeBytes
	}
	if bytesTotal > 1<<20 {
		return float64(size) >= float64(bytesTotal)*0.98
	}
	if job != nil && job.SizeBytes > 50<<20 {
		min := job.SizeBytes * 95 / 100
		if min < 100<<20 {
			min = 100 << 20
		}
		return size >= min
	}
	if job != nil && job.ExpectedDurationMs > 120_000 && job.BufferedMs > 0 {
		return float64(job.BufferedMs) >= float64(job.ExpectedDurationMs)*0.85
	}
	return true
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

func (r *Runner) inspectHTTPSource(ctx context.Context, job *store.Job, mediaURL, referer string, bytesTotal *int64, mu *sync.Mutex, save func()) {
	if n := httpContentLength(ctx, mediaURL, referer); n > 0 {
		atomic.StoreInt64(bytesTotal, n)
	}
	if r.prober == nil {
		return
	}
	ms, err := r.prober.Duration(ctx, mediaURL)
	if err != nil || ms <= 0 {
		return
	}
	if !plausibleSourceDuration(job.SizeBytes, ms) {
		slog.Warn("ignoring implausible remote duration", "id", job.ID, "durationMs", ms, "sizeBytes", job.SizeBytes)
		return
	}
	mu.Lock()
	if ms > job.ExpectedDurationMs {
		job.ExpectedDurationMs = ms
	}
	mu.Unlock()
	save()
}

// plausibleSourceDuration rejects sample/stub probes (e.g. 60s) for multi‑GB remuxes
// so the UI does not show "0:59 left" / 98% for a fake Tokyo Drift clip.
func plausibleSourceDuration(sizeBytes, durationMs int64) bool {
	if durationMs <= 0 {
		return false
	}
	switch {
	case sizeBytes >= 20<<30 && durationMs < 45*60*1000: // ≥20GB needs ≥45min
		return false
	case sizeBytes >= 5<<30 && durationMs < 20*60*1000: // ≥5GB needs ≥20min
		return false
	case sizeBytes >= 1<<30 && durationMs < 5*60*1000: // ≥1GB needs ≥5min
		return false
	default:
		return true
	}
}

func httpContentLength(ctx context.Context, raw, referer string) int64 {
	raw = strings.TrimSpace(raw)
	if !isHTTPMediaURL(raw) {
		return 0
	}
	client := &http.Client{Timeout: 12 * time.Second}
	if n := contentLengthFrom(ctx, client, http.MethodHead, raw, "", referer); n > 0 {
		return n
	}
	return contentLengthFrom(ctx, client, http.MethodGet, raw, "bytes=0-0", referer)
}

func contentLengthFrom(ctx context.Context, client *http.Client, method, raw, rng, referer string) int64 {
	req, err := http.NewRequestWithContext(ctx, method, raw, nil)
	if err != nil {
		return 0
	}
	req.Header.Set("User-Agent", streams.WebUserAgent())
	if referer != "" {
		req.Header.Set("Referer", referer)
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

func isHTTPMediaURL(raw string) bool {
	u := strings.ToLower(strings.TrimSpace(raw))
	return strings.HasPrefix(u, "http://") || strings.HasPrefix(u, "https://")
}

func isCatalogJobURL(raw string) bool {
	u := strings.ToLower(strings.TrimSpace(raw))
	return strings.HasPrefix(u, "imdb:") || strings.HasPrefix(u, "magnet:")
}

func mediaURLReady(raw string) error {
	if isCatalogJobURL(raw) || !isHTTPMediaURL(raw) {
		return fmt.Errorf("not a download URL")
	}
	return nil
}

func effectiveJobType(job store.Job) string {
	if isCatalogJobURL(job.URL) {
		if job.Type == jobs.TypeTorrent || strings.HasPrefix(strings.ToLower(strings.TrimSpace(job.URL)), "magnet:") {
			return jobs.TypeTorrent
		}
		return jobs.TypeDebrid
	}
	if job.Type == "" {
		return jobs.TypeYTDLP
	}
	return job.Type
}

func isWebCandidate(c streams.Candidate) bool {
	if strings.EqualFold(c.Source, "web") || strings.EqualFold(c.Kind, "web") {
		return strings.HasPrefix(strings.ToLower(strings.TrimSpace(c.URL)), "http")
	}
	return false
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
