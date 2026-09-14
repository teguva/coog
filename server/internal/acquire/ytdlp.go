package acquire

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/anacrolix/torrent"

	"coog/internal/config"
	"coog/internal/events"
	"coog/internal/jobs"
	"coog/internal/library"
	"coog/internal/meta"
	"coog/internal/probe"
	"coog/internal/store"
	"coog/internal/streams"
)

const (
	earlySegments = 2
	earlyBufferMs = 8_000
	earlyBytes    = 4 * 1024 * 1024
)

var percentRe = regexp.MustCompile(`(?i)\[download\]\s+(\d+(?:\.\d+)?)%`)

type Runner struct {
	cfg       config.Config
	store     *store.Store
	prober    *probe.Prober
	torrentMu sync.Mutex
	torrentCl *torrent.Client
}

func New(cfg config.Config, st *store.Store, prober *probe.Prober) *Runner {
	return &Runner{cfg: cfg, store: st, prober: prober}
}

func (r *Runner) Loop(ctx context.Context) error {
	if err := r.store.RequeueDownloading(); err != nil {
		slog.Warn("requeue stuck jobs", "err", err)
	}
	go r.heartbeat(ctx)
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			job, err := r.store.ClaimNextJob()
			if err != nil {
				if !errors.Is(err, store.ErrNotFound) {
					slog.Warn("claim job", "err", err)
				}
				continue
			}
			slog.Info("claimed job", "id", job.ID, "url", events.Redact(job.URL))
			r.run(ctx, job)
		}
	}
}

func (r *Runner) heartbeat(ctx context.Context) {
	tick := time.NewTicker(3 * time.Second)
	defer tick.Stop()
	_ = r.store.TouchWorkerHeartbeat(os.Getpid())
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
			_ = r.store.TouchWorkerHeartbeat(os.Getpid())
		}
	}
}

func (r *Runner) runYTDLP(ctx context.Context, job *store.Job) error {
	if isWebEmbed(job.URL) {
		return r.runWebEmbed(ctx, job)
	}
	work := jobs.Dir(r.cfg.DataPath, job.ID)
	hls := jobs.HLSDir(r.cfg.DataPath, job.ID)
	if err := os.MkdirAll(hls, 0o755); err != nil {
		return err
	}
	tail := newLogSink()
	syncTail := func() {
		job.LogTail = tail.String()
	}
	job.WorkDir = work
	job.Status = jobs.StatusDownloading
	syncTail()
	_ = r.store.UpdateJob(*job)

	info, err := r.dumpJSON(ctx, job.URL, tail)
	if err != nil {
		slog.Warn("yt-dlp dump-json", "id", job.ID, "err", err)
		syncTail()
	} else {
		if job.Title == "" && info.Title != "" {
			job.Title = info.Title
		}
		if info.Duration > 0 {
			job.ExpectedDurationMs = int64(info.Duration * 1000)
		}
		if job.Year == 0 && len(info.UploadDate) >= 4 {
			if y, err := strconv.Atoi(info.UploadDate[:4]); err == nil {
				job.Year = y
			}
		}
		_ = r.store.UpdateJob(*job)
	}
	if job.Title == "" {
		job.Title = "Download " + job.ID
	}

	sourcePath := jobs.SourcePath(r.cfg.DataPath, job.ID)
	source, err := os.Create(sourcePath)
	if err != nil {
		return err
	}
	defer source.Close()

	pr, pw := io.Pipe()
	ytdlp := exec.CommandContext(ctx, r.cfg.YTDLP, ytdlpDownloadArgs(job.URL)...)
	ytdlp.Stdout = io.MultiWriter(source, pw)
	stderr, err := ytdlp.StderrPipe()
	if err != nil {
		_ = pw.Close()
		return err
	}

	ffmpeg := exec.CommandContext(ctx, r.cfg.FFmpeg,
		"-hide_banner", "-loglevel", "error",
		"-fflags", "+genpts",
		"-i", "pipe:0",
		"-map", "0",
		"-c", "copy",
		"-f", "hls",
		"-hls_time", strconv.Itoa(jobs.SegmentTimeS),
		"-hls_list_size", "0",
		"-hls_playlist_type", "event",
		"-hls_flags", "independent_segments+omit_endlist",
		"-hls_segment_filename", filepath.Join(hls, "seg_%05d.ts"),
		jobs.PlaylistPath(r.cfg.DataPath, job.ID),
	)
	ffmpeg.Stdin = pr
	ffmpeg.Stderr = io.MultiWriter(os.Stderr, tail)

	if err := ffmpeg.Start(); err != nil {
		_ = pw.Close()
		return err
	}
	if err := ytdlp.Start(); err != nil {
		_ = pw.Close()
		_ = ffmpeg.Process.Kill()
		return err
	}

	var mu sync.Mutex
	save := func() {
		mu.Lock()
		defer mu.Unlock()
		syncTail()
		_ = r.store.UpdateJob(*job)
	}

	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		r.watchProgress(ctx, job, stderr, tail, &mu, save)
	}()

	stopWatch := make(chan struct{})
	wg.Add(1)
	go func() {
		defer wg.Done()
		r.watchReady(ctx, job, sourcePath, stopWatch, &mu, save, nil)
	}()

	ytdlpErr := ytdlp.Wait()
	_ = pw.Close()
	ffmpegErr := ffmpeg.Wait()
	close(stopWatch)
	wg.Wait()
	syncTail()

	if ytdlpErr != nil {
		return ytdlpErr
	}
	if ffmpegErr != nil {
		slog.Warn("ffmpeg hls exited", "id", job.ID, "err", ffmpegErr)
	}
	_ = jobs.AppendEndList(jobs.PlaylistPath(r.cfg.DataPath, job.ID))
	return r.finishJob(ctx, job, sourcePath)
}

func (r *Runner) runWebEmbed(ctx context.Context, job *store.Job) error {
	tail := newLogSink()
	media, err := streams.ResolveWebEmbed(ctx, job.URL)
	if err != nil || media == "" {
		media, err = r.ytdlpStreamURL(ctx, job.URL, tail)
	}
	if err != nil || media == "" {
		if tail.String() != "" {
			job.LogTail = tail.String()
			_ = r.store.UpdateJob(*job)
		}
		return fmt.Errorf("could not extract video from this web source")
	}
	job.LogTail = tail.String()
	return r.pullAndPack(ctx, job, media, streams.EmbedReferer(job.URL))
}

func (r *Runner) ytdlpStreamURL(ctx context.Context, pageURL string, tail *logSink) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, 45*time.Second)
	defer cancel()
	args := []string{"-g", "-f", "bv*+ba/b", "--no-playlist", "--no-warnings"}
	args = append(args, ytdlpHeaderArgs(pageURL)...)
	args = append(args, "--", pageURL)
	cmd := exec.CommandContext(ctx, r.cfg.YTDLP, args...)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	if tail != nil && stderr.Len() > 0 {
		_, _ = tail.Write(stderr.Bytes())
	}
	if err != nil {
		return "", err
	}
	for _, line := range strings.Split(string(out), "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "http://") || strings.HasPrefix(line, "https://") {
			return line, nil
		}
	}
	return "", fmt.Errorf("yt-dlp returned no stream URL")
}

func ytdlpDownloadArgs(pageURL string) []string {
	args := []string{
		"--no-playlist",
		"--no-warnings",
		"--newline",
		"--progress",
		"--hls-use-mpegts",
		"--merge-output-format", "mpegts",
		"-f", "bv*+ba/b",
		"-o", "-",
	}
	args = append(args, ytdlpHeaderArgs(pageURL)...)
	return append(args, "--", pageURL)
}

func ytdlpHeaderArgs(pageURL string) []string {
	if !isWebEmbed(pageURL) {
		return nil
	}
	return []string{
		"--referer", streams.EmbedReferer(pageURL),
		"--add-header", "User-Agent: " + streams.WebUserAgent(),
	}
}

func isWebEmbed(raw string) bool {
	u := strings.ToLower(strings.TrimSpace(raw))
	if !strings.HasPrefix(u, "http://") && !strings.HasPrefix(u, "https://") {
		return false
	}
	if strings.Contains(u, "youtube.com") || strings.Contains(u, "youtu.be") || strings.Contains(u, "googlevideo.com") {
		return false
	}
	return true
}

type ytdlpInfo struct {
	Title      string  `json:"title"`
	Duration   float64 `json:"duration"`
	UploadDate string  `json:"upload_date"`
}

func (r *Runner) dumpJSON(ctx context.Context, url string, tail *logSink) (ytdlpInfo, error) {
	ctx, cancel := context.WithTimeout(ctx, 90*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, r.cfg.YTDLP, append(append([]string{"--dump-json", "--no-playlist", "--no-download", "--no-warnings"}, ytdlpHeaderArgs(url)...), "--", url)...)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	if tail != nil && stderr.Len() > 0 {
		_, _ = tail.Write(stderr.Bytes())
	}
	if err != nil {
		if tail != nil {
			_, _ = tail.Write([]byte(err.Error() + "\n"))
		}
		return ytdlpInfo{}, err
	}
	var info ytdlpInfo
	if err := json.Unmarshal(firstJSONLine(out), &info); err != nil {
		return ytdlpInfo{}, err
	}
	return info, nil
}

func (r *Runner) watchProgress(ctx context.Context, job *store.Job, stderr io.Reader, tail *logSink, mu *sync.Mutex, save func()) {
	buf := make([]byte, 0, 4096)
	tmp := make([]byte, 1024)
	for {
		n, err := stderr.Read(tmp)
		if n > 0 {
			buf = append(buf, tmp[:n]...)
			for {
				i := indexByte(buf, '\n')
				if i < 0 {
					break
				}
				line := string(buf[:i])
				buf = buf[i+1:]
				if tail != nil {
					_, _ = tail.Write([]byte(line + "\n"))
				}
				m := percentRe.FindStringSubmatch(line)
				if len(m) != 2 {
					continue
				}
				p, convErr := strconv.ParseFloat(m[1], 64)
				if convErr != nil {
					continue
				}
				mu.Lock()
				job.Progress = p / 100
				if job.Progress > 0.99 {
					job.Progress = 0.99
				}
				mu.Unlock()
				save()
			}
		}
		if err != nil {
			return
		}
		select {
		case <-ctx.Done():
			return
		default:
		}
	}
}

func (r *Runner) watchReady(ctx context.Context, job *store.Job, sourcePath string, stop <-chan struct{}, mu *sync.Mutex, save func(), bytesTotal *int64) {
	t := time.NewTicker(time.Second)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-stop:
			return
		case <-t.C:
			playlist := jobs.PlaylistPath(r.cfg.DataPath, job.ID)
			buffered, segments := jobs.PlaylistBufferedMs(playlist)
			var size int64
			if st, err := os.Stat(sourcePath); err == nil {
				size = st.Size()
			}
			var total int64
			if bytesTotal != nil {
				total = atomic.LoadInt64(bytesTotal)
			}
			mu.Lock()
			if buffered > 0 {
				job.BufferedMs = buffered
			}
			if p := jobs.DownloadProgress(job.BufferedMs, job.ExpectedDurationMs, size, total); p > job.Progress {
				job.Progress = p
			}
			if !job.Ready && segments >= earlySegments && buffered >= earlyBufferMs && size >= earlyBytes {
				job.Ready = true
				job.Status = jobs.StatusReady
				slog.Info("job ready for progressive play", "id", job.ID, "segments", segments, "bufferedMs", buffered)
			}
			mu.Unlock()
			save()
		}
	}
}

func (r *Runner) finalizeLibrary(ctx context.Context, job *store.Job, sourcePath string) (store.MediaItem, error) {
	name := jobs.SafeName(job.Title)
	relDir, fileBase, season, episode, _ := libraryDest(*job, name)
	absDir := filepath.Join(r.cfg.LibraryPath, relDir)
	if err := os.MkdirAll(absDir, 0o755); err != nil {
		return store.MediaItem{}, err
	}
	fileBase = uniqueLibraryBase(absDir, fileBase, *job)
	destMP4 := filepath.Join(absDir, fileBase+".mp4")
	cmd := exec.CommandContext(ctx, r.cfg.FFmpeg,
		"-y", "-hide_banner", "-loglevel", "error",
		"-i", sourcePath,
		"-c", "copy",
		"-movflags", "+faststart",
		destMP4,
	)
	dest := destMP4
	if err := cmd.Run(); err != nil {
		slog.Warn("remux to mp4 failed, keeping mpegts", "id", job.ID, "err", err)
		dest = filepath.Join(absDir, fileBase+".ts")
		if copyErr := copyFile(sourcePath, dest); copyErr != nil {
			return store.MediaItem{}, copyErr
		}
	}
	info, err := os.Stat(dest)
	if err != nil {
		return store.MediaItem{}, err
	}
	rel, err := filepath.Rel(r.cfg.LibraryPath, dest)
	if err != nil {
		rel = filepath.Join(relDir, filepath.Base(dest))
	}
	parsed := library.ParseRelative(rel)
	item := store.MediaItem{
		ID:           library.MediaID(rel),
		Kind:         parsed.Kind,
		Title:        name,
		Year:         job.Year,
		Season:       season,
		Episode:      episode,
		ShowTitle:    parsed.ShowTitle,
		Path:         dest,
		RelativePath: filepath.ToSlash(rel),
		SizeBytes:    info.Size(),
		MtimeUnix:    info.ModTime().Unix(),
		ContentType:  library.ContentType(dest),
		UpdatedAt:    time.Now().Unix(),
	}
	if job.Year > 0 {
		item.Year = job.Year
	} else {
		item.Year = parsed.Year
	}
	pr, err := r.prober.Probe(ctx, dest)
	probeOK := false
	if err != nil {
		slog.Warn("probe finished download", "id", job.ID, "err", err)
		item.DurationMs = job.ExpectedDurationMs
	} else {
		probeOK = true
		item.DurationMs = pr.DurationMs
		item.Probe = pr.Raw
		item.CodecVideo = pr.VideoCodec
		item.CodecAudio = pr.AudioCodec
		item.Width = pr.Width
		item.Height = pr.Height
		item.HDR = pr.HDR
	}
	if err := r.store.UpsertMedia(item); err != nil {
		return store.MediaItem{}, err
	}
	hasReleaseMeta := strings.TrimSpace(job.ImdbID) != "" ||
		strings.TrimSpace(job.Quality) != "" ||
		strings.TrimSpace(job.SizeLabel) != "" ||
		strings.TrimSpace(job.ReleaseTitle) != "" ||
		len(job.Tags) > 0 ||
		len(job.Languages) > 0
	if hasReleaseMeta || probeOK {
		sc, _ := meta.ReadSidecar(dest)
		if imdb := strings.TrimSpace(job.ImdbID); imdb != "" {
			sc.MatchStatus = "matched"
			sc.ImdbID = imdb
		}
		if name != "" {
			sc.Title = name
		}
		if item.Year > 0 {
			sc.Year = item.Year
		}
		if q := strings.TrimSpace(job.Quality); q != "" {
			sc.Quality = q
		}
		if label := strings.TrimSpace(job.SizeLabel); label != "" {
			sc.SizeLabel = label
		} else if job.SizeBytes > 0 {
			sc.SizeLabel = streams.FormatSizeLabel(job.SizeBytes)
		}
		if p := strings.TrimSpace(job.Pack); p != "" {
			sc.Pack = p
		}
		if len(job.Tags) > 0 {
			sc.Tags = job.Tags
		}
		if len(job.Languages) > 0 {
			sc.Languages = job.Languages
		}
		if rt := strings.TrimSpace(job.ReleaseTitle); rt != "" {
			sc.ReleaseTitle = rt
		}
		if probeOK {
			q, tags, sizeLabel := streams.MergeFileMeta(sc.Quality, sc.Tags, streams.FileProbeMeta{
				Height:     pr.Height,
				VideoCodec: pr.VideoCodec,
				AudioCodec: pr.AudioCodec,
				HDR:        pr.HDR,
				Atmos:      pr.Atmos,
				SizeBytes:  item.SizeBytes,
			})
			if q != "" {
				if sc.Quality != "" && !strings.EqualFold(sc.Quality, q) {
					slog.Info("probe retag quality", "id", job.ID, "release", sc.Quality, "probe", q)
				}
				sc.Quality = q
			}
			if len(tags) > 0 {
				sc.Tags = tags
			}
			if sizeLabel != "" {
				sc.SizeLabel = sizeLabel
			}
		}
		_ = meta.WriteSidecar(dest, sc)
	}
	return item, nil
}

func libraryDest(job store.Job, name string) (relDir, fileBase string, season, episode int, kind string) {
	kind = "movie"
	if rest, ok := strings.CutPrefix(job.URL, "imdb:"); ok {
		_, season, episode, kind = parseImdbRef(rest, job.ImdbID)
	}
	if season == 0 && episode == 0 {
		for _, m := range extractEpisodeMarkers(job.Title + " " + name) {
			season, episode = m[0], m[1]
			kind = "series"
			break
		}
	}
	if kind == "series" || season > 0 || episode > 0 {
		if season <= 0 {
			season = 1
		}
		show := library.CleanShowTitle(name)
		if show == "" {
			show = "Unknown show"
		}
		relDir = filepath.Join("Series", show, fmt.Sprintf("Season %02d", season))
		fileBase = show
		if episode > 0 {
			fileBase = fmt.Sprintf("%s S%02dE%02d", show, season, episode)
		}
		return relDir, fileBase, season, episode, "series"
	}
	folder := name
	if job.Year > 0 {
		folder = name + " (" + strconv.Itoa(job.Year) + ")"
	}
	return filepath.Join("Movies", folder), folder, 0, 0, "movie"
}

// uniqueLibraryBase keeps an existing library file and writes a sibling when re-downloading.
func uniqueLibraryBase(absDir, fileBase string, job store.Job) string {
	if !libraryBaseOccupied(absDir, fileBase) {
		return fileBase
	}
	suffix := libraryFileSuffix(job)
	candidate := fileBase + " - " + suffix
	if !libraryBaseOccupied(absDir, candidate) {
		return candidate
	}
	id := strings.TrimSpace(job.ID)
	if len(id) > 8 {
		id = id[:8]
	}
	if id == "" {
		id = strconv.FormatInt(time.Now().Unix(), 10)
	}
	return fileBase + " - " + suffix + " " + id
}

func libraryBaseOccupied(absDir, fileBase string) bool {
	for _, ext := range []string{".mp4", ".mkv", ".ts", ".m4v", ".webm"} {
		if _, err := os.Stat(filepath.Join(absDir, fileBase+ext)); err == nil {
			return true
		}
	}
	return false
}

func libraryFileSuffix(job store.Job) string {
	var parts []string
	if q := strings.TrimSpace(job.Quality); q != "" {
		parts = append(parts, q)
	}
	want := map[string]bool{
		"Remux": true, "BluRay": true, "WEB": true, "DV": true,
		"HDR10+": true, "HDR": true, "Atmos": true, "HEVC": true, "AVC": true,
	}
	for _, t := range job.Tags {
		if want[t] {
			parts = append(parts, t)
		}
	}
	parts = uniqueNonEmpty(parts)
	if len(parts) > 0 {
		return strings.Join(parts, " ")
	}
	id := strings.TrimSpace(job.ID)
	if len(id) > 8 {
		id = id[:8]
	}
	if id != "" {
		return id
	}
	return "copy"
}

func uniqueNonEmpty(in []string) []string {
	seen := map[string]bool{}
	out := make([]string, 0, len(in))
	for _, s := range in {
		s = strings.TrimSpace(s)
		if s == "" || seen[s] {
			continue
		}
		seen[s] = true
		out = append(out, s)
	}
	return out
}

func copyFile(src, dest string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.Create(dest)
	if err != nil {
		return err
	}
	defer out.Close()
	if _, err := io.Copy(out, in); err != nil {
		return err
	}
	return out.Sync()
}

func indexByte(b []byte, c byte) int {
	for i, v := range b {
		if v == c {
			return i
		}
	}
	return -1
}

func firstJSONLine(raw []byte) []byte {
	raw = bytes.TrimSpace(raw)
	if i := bytes.IndexByte(raw, '\n'); i > 0 {
		return raw[:i]
	}
	return raw
}
