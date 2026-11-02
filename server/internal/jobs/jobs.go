package jobs

import (
	"bufio"
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const (
	TypeYTDLP   = "ytdlp"
	TypeHTTP    = "http"
	TypeDebrid  = "debrid"
	TypeTorrent = "torrent"

	StatusQueued      = "queued"
	StatusDownloading = "downloading"
	StatusReady       = "ready"
	StatusFinished    = "finished"
	StatusError       = "error"
	StatusCancelled   = "cancelled"
	StatusPaused      = "paused"

	// PauseReasonYieldQueue marks a progressive-ready transfer paused so a
	// queued job can take the worker. Auto-resumed when the queue is idle.
	PauseReasonYieldQueue = "paused so the next download can start"

	PlaylistName = "index.m3u8"
	SegmentTimeS = 4
)

func Dir(dataPath, id string) string {
	return filepath.Join(dataPath, "jobs", id)
}

const viewerMarker = ".viewer"

// TouchViewer marks a job as having an active progressive/stream client so the
// worker will not pause it to free a queue slot for another title.
func TouchViewer(dataPath, id string) {
	dir := Dir(dataPath, id)
	if strings.TrimSpace(dir) == "" || strings.TrimSpace(id) == "" {
		return
	}
	_ = os.MkdirAll(dir, 0o755)
	path := filepath.Join(dir, viewerMarker)
	_ = os.WriteFile(path, []byte(strconv.FormatInt(time.Now().Unix(), 10)+"\n"), 0o644)
}

// RecentlyViewed reports whether a client streamed this job within within.
func RecentlyViewed(workDir string, within time.Duration) bool {
	if strings.TrimSpace(workDir) == "" || within <= 0 {
		return false
	}
	st, err := os.Stat(filepath.Join(workDir, viewerMarker))
	if err != nil {
		return false
	}
	return time.Since(st.ModTime()) < within
}

// Cleanup removes the job work directory (partial downloads + progressive HLS).
// Library files under COOG_LIBRARY_PATH are never touched here.
func Cleanup(dataPath, id string) {
	if strings.TrimSpace(dataPath) == "" || strings.TrimSpace(id) == "" {
		return
	}
	_ = os.RemoveAll(Dir(dataPath, id))
}

func HLSDir(dataPath, id string) string {
	return filepath.Join(Dir(dataPath, id), "hls")
}

func SourcePath(dataPath, id string) string {
	// Prefer a named original (source.mkv / source.mp4) when acquire recorded one.
	if meta, err := ReadSourceMeta(dataPath, id); err == nil && strings.TrimSpace(meta.Filename) != "" {
		ext := filepath.Ext(meta.Filename)
		if ext != "" && !strings.Contains(ext, "/") {
			named := filepath.Join(Dir(dataPath, id), "source"+strings.ToLower(ext))
			if _, err := os.Stat(named); err == nil {
				return named
			}
			return named
		}
	}
	// Legacy progressive remux intermediate.
	if _, err := os.Stat(filepath.Join(Dir(dataPath, id), "source.ts")); err == nil {
		return filepath.Join(Dir(dataPath, id), "source.ts")
	}
	return filepath.Join(Dir(dataPath, id), "source")
}

// SourceMeta describes the original download for direct Range play (Jellyfin/Stremio-style).
type SourceMeta struct {
	Filename    string `json:"filename,omitempty"`
	ContentType string `json:"contentType,omitempty"`
	TotalBytes  int64  `json:"totalBytes,omitempty"`
}

func SourceMetaPath(dataPath, id string) string {
	return filepath.Join(Dir(dataPath, id), "source.meta.json")
}

func WriteSourceMeta(dataPath, id string, meta SourceMeta) error {
	if err := os.MkdirAll(Dir(dataPath, id), 0o755); err != nil {
		return err
	}
	raw, err := json.Marshal(meta)
	if err != nil {
		return err
	}
	return os.WriteFile(SourceMetaPath(dataPath, id), append(raw, '\n'), 0o600)
}

func ReadSourceMeta(dataPath, id string) (SourceMeta, error) {
	raw, err := os.ReadFile(SourceMetaPath(dataPath, id))
	if err != nil {
		return SourceMeta{}, err
	}
	var meta SourceMeta
	if err := json.Unmarshal(raw, &meta); err != nil {
		return SourceMeta{}, err
	}
	return meta, nil
}

func OriginURLPath(dataPath, id string) string {
	return filepath.Join(Dir(dataPath, id), "origin.url")
}

func OriginRefererPath(dataPath, id string) string {
	return filepath.Join(Dir(dataPath, id), "origin.referer")
}

func PlaylistPath(dataPath, id string) string {
	return filepath.Join(HLSDir(dataPath, id), PlaylistName)
}

// WriteOrigin records the upstream HTTP(S) media URL so playback can proxy the
// complete remote file while the library copy is still downloading.
func WriteOrigin(dataPath, id, mediaURL, referer string) error {
	mediaURL = strings.TrimSpace(mediaURL)
	if mediaURL == "" {
		return nil
	}
	if err := os.MkdirAll(Dir(dataPath, id), 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(OriginURLPath(dataPath, id), []byte(mediaURL+"\n"), 0o600); err != nil {
		return err
	}
	referer = strings.TrimSpace(referer)
	if referer == "" {
		_ = os.Remove(OriginRefererPath(dataPath, id))
		return nil
	}
	return os.WriteFile(OriginRefererPath(dataPath, id), []byte(referer+"\n"), 0o600)
}

func ReadOrigin(dataPath, id string) (mediaURL, referer string) {
	raw, err := os.ReadFile(OriginURLPath(dataPath, id))
	if err != nil {
		return "", ""
	}
	mediaURL = strings.TrimSpace(string(raw))
	if ref, err := os.ReadFile(OriginRefererPath(dataPath, id)); err == nil {
		referer = strings.TrimSpace(string(ref))
	}
	return mediaURL, referer
}

func SafeName(title string) string {
	title = strings.TrimSpace(title)
	if title == "" {
		return "Untitled"
	}
	repl := strings.NewReplacer(
		"/", "-", "\\", "-", ":", " -", "?", "", "*", "", "\"", "",
		"<", "", ">", "", "|", "", "\n", " ", "\r", "",
	)
	title = strings.Join(strings.Fields(repl.Replace(title)), " ")
	if len(title) > 120 {
		title = strings.TrimSpace(title[:120])
	}
	if title == "" {
		return "Untitled"
	}
	return title
}

func PlaylistBufferedMs(playlistPath string) (int64, int) {
	f, err := os.Open(playlistPath)
	if err != nil {
		return 0, 0
	}
	defer f.Close()
	var total float64
	segments := 0
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if !strings.HasPrefix(line, "#EXTINF:") {
			continue
		}
		payload := strings.TrimPrefix(line, "#EXTINF:")
		payload, _, _ = strings.Cut(payload, ",")
		sec, err := strconv.ParseFloat(strings.TrimSpace(payload), 64)
		if err != nil {
			continue
		}
		total += sec
		segments++
	}
	return int64(total * 1000), segments
}

// DownloadProgress is 0–1. Prefer bytes when known — bufferedMs is derived from
// the same fraction and must not inflate progress above bytes on disk.
func DownloadProgress(bufferedMs, expectedMs, bytesHave, bytesTotal int64) float64 {
	var p float64
	if bytesTotal > 0 && bytesHave > 0 {
		p = float64(bytesHave) / float64(bytesTotal)
	} else if expectedMs > 0 && bufferedMs > 0 {
		p = float64(bufferedMs) / float64(expectedMs)
	}
	if p < 0 {
		return 0
	}
	if p > 0.99 {
		return 0.99
	}
	return p
}

func AppendEndList(playlistPath string) error {
	b, err := os.ReadFile(playlistPath)
	if err != nil {
		return err
	}
	text := string(b)
	if strings.Contains(text, "#EXT-X-ENDLIST") {
		return nil
	}
	if !strings.HasSuffix(text, "\n") {
		text += "\n"
	}
	return os.WriteFile(playlistPath, []byte(text+"#EXT-X-ENDLIST\n"), 0o644)
}
