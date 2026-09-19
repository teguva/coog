package acquire

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/anacrolix/torrent"

	"coog/internal/events"
	"coog/internal/jobs"
	"coog/internal/store"
	"coog/internal/streams"
)

const (
	torrentReadyBytes = 4 * 1024 * 1024
	torrentTailBytes  = 32 * 1024 * 1024
	torrentMetaWait   = 90 * time.Second
)

func (r *Runner) torrentClient() (*torrent.Client, error) {
	r.torrentMu.Lock()
	defer r.torrentMu.Unlock()
	if r.torrentCl != nil {
		return r.torrentCl, nil
	}
	cfg := torrent.NewDefaultClientConfig()
	cfg.DataDir = filepath.Join(r.cfg.DataPath, "torrents")
	if err := os.MkdirAll(cfg.DataDir, 0o755); err != nil {
		return nil, err
	}
	cfg.Seed = false
	cfg.ListenPort = 0
	cfg.DisableWebtorrent = true
	cfg.NoUpload = true
	cl, err := torrent.NewClient(cfg)
	if err != nil {
		return nil, err
	}
	r.torrentCl = cl
	return cl, nil
}

func (r *Runner) runTorrent(ctx context.Context, job *store.Job) error {
	hash := streams.InfoHash(job.InfoHash)
	if hash == "" {
		hash = streams.InfoHash(job.URL)
	}
	magnet := streams.MagnetWithTrackers(hash)
	if magnet == "" {
		return fmt.Errorf("missing torrent info hash")
	}
	job.InfoHash = hash
	job.Type = jobs.TypeTorrent
	job.Status = jobs.StatusDownloading
	_ = r.store.UpdateJob(*job)

	client, err := r.torrentClient()
	if err != nil {
		return err
	}
	t, err := client.AddMagnet(magnet)
	if err != nil {
		return err
	}
	defer dropTorrent(t)

	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.GotInfo():
	case <-time.After(torrentMetaWait):
		return fmt.Errorf("timed out waiting for torrent metadata (no peers yet)")
	}
	if t.Info() == nil {
		return fmt.Errorf("torrent metadata missing")
	}

	_, season, episode, _ := parseImdbRef(strings.TrimPrefix(job.URL, "imdb:"), job.ImdbID)
	files := make([]torrentFileMeta, 0, len(t.Files()))
	for i, f := range t.Files() {
		files = append(files, torrentFileMeta{Path: f.DisplayPath(), Length: f.Length(), Index: i})
	}
	idx := pickVideoFile(files, "", season, episode, -1)
	if idx < 0 || idx >= len(t.Files()) {
		return fmt.Errorf("no video file in torrent")
	}
	file := t.Files()[idx]
	file.Download()
	prioritizeTorrentFile(t, file)

	work := jobs.Dir(r.cfg.DataPath, job.ID)
	hls := jobs.HLSDir(r.cfg.DataPath, job.ID)
	if err := os.MkdirAll(hls, 0o755); err != nil {
		return err
	}
	tail := newLogSink()
	if job.LogTail != "" {
		_, _ = tail.Write([]byte(job.LogTail + "\n"))
	}
	_, _ = tail.Write([]byte("local torrent " + hash + " file=" + file.DisplayPath() + "\n"))
	job.WorkDir = work
	job.LogTail = tail.String()
	_ = r.store.UpdateJob(*job)

	if err := waitTorrentHead(ctx, t, file, wantsMoovTail(file.DisplayPath())); err != nil {
		return err
	}

	reader := file.NewReader()
	defer reader.Close()
	reader.SetResponsive()
	reader.SetReadahead(8 << 20)
	go func() {
		<-ctx.Done()
		_ = reader.Close()
	}()

	sourcePath := jobs.SourcePath(r.cfg.DataPath, job.ID)
	source, err := os.Create(sourcePath)
	if err != nil {
		return err
	}
	defer source.Close()

	pr, pw := io.Pipe()
	pull := exec.CommandContext(ctx, r.cfg.FFmpeg,
		"-hide_banner", "-loglevel", "error",
		"-probesize", "32M",
		"-analyzeduration", "32M",
		"-fflags", "+genpts+discardcorrupt",
		"-i", "pipe:0",
		"-map", "0:V:0",
		"-map", "0:a:0?",
		"-c", "copy",
		"-f", "mpegts",
		"pipe:1",
	)
	pull.Stdin = reader
	pull.Stdout = io.MultiWriter(source, pw)
	pull.Stderr = io.MultiWriter(os.Stderr, tail)

	pack := exec.CommandContext(ctx, r.cfg.FFmpeg,
		"-hide_banner", "-loglevel", "error",
		"-fflags", "+genpts",
		"-i", "pipe:0",
		"-map", "0:V:0",
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

	if err := pull.Start(); err != nil {
		_ = pw.Close()
		return err
	}
	if err := pack.Start(); err != nil {
		_ = pw.Close()
		_ = pull.Process.Kill()
		_ = pull.Wait()
		return err
	}

	var mu sync.Mutex
	save := func() {
		mu.Lock()
		defer mu.Unlock()
		job.LogTail = tail.String()
		_ = r.store.UpdateJob(*job)
	}
	bytesTotal := file.Length()
	stopWatch := make(chan struct{})
	done := make(chan struct{})
	go func() {
		r.watchReady(ctx, job, sourcePath, stopWatch, &mu, save, &bytesTotal)
		close(done)
	}()
	go r.watchTorrentProgress(ctx, job, t, file, stopWatch, &mu, save)

	pullErr := pull.Wait()
	_ = pw.Close()
	packErr := pack.Wait()
	close(stopWatch)
	waitClosed(done, 3*time.Second)
	job.LogTail = tail.String()

	if pullErr != nil && ctx.Err() == nil {
		return pullErr
	}
	if packErr != nil {
		slog.Warn("ffmpeg hls exited", "id", job.ID, "err", packErr)
	}
	_ = jobs.AppendEndList(jobs.PlaylistPath(r.cfg.DataPath, job.ID))
	return r.finishJob(ctx, job, sourcePath)
}

func prioritizeTorrentFile(t *torrent.Torrent, file *torrent.File) {
	info := t.Info()
	if info == nil || info.PieceLength <= 0 {
		return
	}
	start := int(file.Offset() / info.PieceLength)
	end := int((file.Offset() + file.Length() + info.PieceLength - 1) / info.PieceLength)
	if end > t.NumPieces() {
		end = t.NumPieces()
	}
	headPieces := int((torrentReadyBytes + info.PieceLength - 1) / info.PieceLength)
	if headPieces < 4 {
		headPieces = 4
	}
	for i := start; i < end && i < start+headPieces; i++ {
		t.Piece(i).SetPriority(torrent.PiecePriorityNow)
	}
	if wantsMoovTail(file.DisplayPath()) {
		tailPieces := int((torrentTailBytes + info.PieceLength - 1) / info.PieceLength)
		if tailPieces < 4 {
			tailPieces = 4
		}
		from := end - tailPieces
		if from < start {
			from = start
		}
		for i := from; i < end; i++ {
			t.Piece(i).SetPriority(torrent.PiecePriorityNow)
		}
	}
}

func waitTorrentHead(ctx context.Context, t *torrent.Torrent, file *torrent.File, wantTail bool) error {
	need := int64(torrentReadyBytes)
	if file.Length() > 0 && file.Length() < need {
		need = file.Length() / 20
		if need < 256*1024 {
			need = file.Length()
		}
	}
	deadline := time.Now().Add(3 * time.Minute)
	zeroSince := time.Now()
	for time.Now().Before(deadline) {
		if err := ctx.Err(); err != nil {
			return err
		}
		have := file.BytesCompleted()
		peers := 0
		if t != nil {
			peers = t.Stats().ActivePeers
		}
		if have > 0 {
			zeroSince = time.Time{}
		} else if zeroSince.IsZero() {
			zeroSince = time.Now()
		}
		// Dead swarm: metadata arrived but nobody is sending pieces.
		if have == 0 && peers == 0 && !zeroSince.IsZero() && time.Since(zeroSince) >= 45*time.Second {
			return fmt.Errorf("no peers downloading this torrent (0 / %d bytes). Pick a Cached / RD+ source — local download needs active seeders", need)
		}
		if have >= need {
			if !wantTail || have >= need+min64(torrentTailBytes, file.Length()/10) || have == file.Length() {
				return nil
			}
			// Tail pieces are prioritized; once we have a solid head, start remuxing.
			if have >= need*2 {
				return nil
			}
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(400 * time.Millisecond):
		}
	}
	peers := 0
	if t != nil {
		peers = t.Stats().ActivePeers
	}
	return fmt.Errorf("timed out waiting for torrent buffer (%d / %d bytes, peers=%d). Try a Cached / RD+ source", file.BytesCompleted(), need, peers)
}

// localTorrentOK is false for sources that are a poor fit for anacrolix local download
// (multi‑hour BluRay REMUXes rarely have enough peers to buffer in time).
func localTorrentOK(c streams.Candidate) bool {
	title := strings.ToLower(strings.TrimSpace(c.Title + " " + c.Name + " " + c.Quality))
	if strings.Contains(title, "remux") {
		return false
	}
	const maxLocal = 12 << 30 // 12 GiB
	if c.Size > maxLocal {
		return false
	}
	return true
}

func (r *Runner) watchTorrentProgress(ctx context.Context, job *store.Job, t *torrent.Torrent, file *torrent.File, stop <-chan struct{}, mu *sync.Mutex, save func()) {
	tick := time.NewTicker(1500 * time.Millisecond)
	defer tick.Stop()
	var lastRead, lastWritten int64
	var lastAt time.Time
	for {
		select {
		case <-ctx.Done():
			return
		case <-stop:
			return
		case <-tick.C:
			st := t.Stats()
			have := file.BytesCompleted()
			total := file.Length()
			now := time.Now()
			read := st.BytesReadUsefulData.Int64()
			written := st.BytesWrittenData.Int64()
			var downBps, upBps int64
			if !lastAt.IsZero() {
				dt := now.Sub(lastAt).Seconds()
				if dt > 0.2 {
					if d := read - lastRead; d > 0 {
						downBps = int64(float64(d) / dt)
					}
					if u := written - lastWritten; u > 0 {
						upBps = int64(float64(u) / dt)
					}
				}
			}
			lastRead, lastWritten, lastAt = read, written, now
			peers := st.ActivePeers
			seeders := st.ConnectedSeeders
			health := torrentHealth(peers, seeders, downBps)
			mu.Lock()
			if p := jobs.DownloadProgress(job.BufferedMs, job.ExpectedDurationMs, have, total); p > job.Progress {
				job.Progress = p
			}
			job.Transfer = &store.TransferStats{
				DownloadBps: downBps,
				UploadBps:   upBps,
				Peers:       peers,
				Seeders:     seeders,
				TotalPeers:  st.TotalPeers,
				Health:      health,
			}
			line := fmt.Sprintf(
				"torrent ↓%s ↑%s seeders=%d peers=%d total=%d health=%s have=%d/%d",
				formatByteRate(downBps), formatByteRate(upBps), seeders, peers, st.TotalPeers, health, have, total,
			)
			if job.LogTail == "" || !strings.Contains(job.LogTail, line) {
				job.LogTail = events.Redact(strings.TrimSpace(job.LogTail + "\n" + line))
			}
			mu.Unlock()
			save()
		}
	}
}

func torrentHealth(peers, seeders int, downBps int64) string {
	switch {
	case peers == 0:
		return "dead"
	case seeders == 0 && downBps < 32*1024:
		return "poor"
	case peers < 3 || downBps < 50*1024:
		return "poor"
	case peers < 8 || downBps < 500*1024:
		return "fair"
	case seeders >= 5 && downBps >= 2*1024*1024:
		return "excellent"
	default:
		return "good"
	}
}

func formatByteRate(bps int64) string {
	if bps < 1024 {
		return fmt.Sprintf("%d B/s", bps)
	}
	if bps < 1024*1024 {
		return fmt.Sprintf("%.1f KB/s", float64(bps)/1024)
	}
	return fmt.Sprintf("%.2f MB/s", float64(bps)/(1024*1024))
}

func min64(a, b int64) int64 {
	if a < b {
		return a
	}
	return b
}

func dropTorrent(t *torrent.Torrent) {
	if t == nil {
		return
	}
	done := make(chan struct{})
	go func() {
		t.Drop()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		slog.Warn("torrent drop timed out")
	}
}
