package acquire

import (
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"

	"coog/internal/jobs"
)

type ffmpegHelpCache struct {
	mu   sync.Mutex
	help map[string]string
}

var ffmpegHelps = ffmpegHelpCache{help: map[string]string{}}

func ffmpegOptionHelp(bin string) string {
	bin = strings.TrimSpace(bin)
	if bin == "" {
		bin = "ffmpeg"
	}
	ffmpegHelps.mu.Lock()
	if h, ok := ffmpegHelps.help[bin]; ok {
		ffmpegHelps.mu.Unlock()
		return h
	}
	ffmpegHelps.mu.Unlock()
	out, err := exec.Command(bin, "-hide_banner", "-h", "full").CombinedOutput()
	text := string(out)
	if err != nil && text == "" {
		text = err.Error()
	}
	ffmpegHelps.mu.Lock()
	ffmpegHelps.help[bin] = text
	ffmpegHelps.mu.Unlock()
	return text
}

func ffmpegHasOption(help, name string) bool {
	name = strings.TrimPrefix(strings.TrimSpace(name), "-")
	if name == "" {
		return false
	}
	token := "  -" + name
	for _, rest := range []string{" ", "\t", " <"} {
		if strings.Contains(help, token+rest) {
			return true
		}
	}
	return false
}

func appendIfFFmpegOption(args []string, help, name, value string) []string {
	if !ffmpegHasOption(help, name) {
		return args
	}
	return append(args, "-"+name, value)
}

// progressiveAVMaps copies video and transcodes every audio track to AAC stereo.
// MPEG-TS / HLS progressive play cannot carry TrueHD/DTS-HD (common on RD remuxes);
// stream-copy drops those tracks and the TV plays silent video. Mapping all audio
// streams (0:a?) keeps language tracks selectable in the player.
func progressiveAVMaps() []string {
	return []string{
		"-map", "0:V:0",
		"-map", "0:a?",
		"-c:v", "copy",
		"-c:a", "aac",
		"-ac", "2",
		"-b:a", "192k",
	}
}

// progressivePackMaps remuxes the already-prepared pull pipe into progressive HLS.
// Light titles stay stream-copy. Heavy 4K remuxes are re-encoded to 1080p H.264 so
// the TV can hold a real buffer — full-rate HEVC saturates ExoPlayer and looks like
// a low framerate stutter even on LAN. Audio is already AAC from the pull stage.
func progressivePackMaps(heavy bool) []string {
	if !heavy {
		return []string{
			"-map", "0:V:0",
			"-map", "0:a?",
			"-c", "copy",
		}
	}
	return append([]string{
		"-map", "0:V:0",
		"-map", "0:a?",
		"-c:a", "copy",
	}, progressiveHeavyVideo()...)
}

// progressiveHeavyPlayMaps is a single-pass encode from the origin file (no prior AAC).
func progressiveHeavyPlayMaps() []string {
	return append([]string{
		"-map", "0:V:0",
		"-map", "0:a?",
		"-c:a", "aac",
		"-ac", "2",
		"-b:a", "192k",
	}, progressiveHeavyVideo()...)
}

func progressiveHeavyVideo() []string {
	return []string{
		"-vf", "scale=-2:'min(1080,ih)'",
		"-c:v", "libx264",
		"-preset", "veryfast",
		"-crf", "21",
		"-maxrate", "10M",
		"-bufsize", "20M",
		"-pix_fmt", "yuv420p",
	}
}

// progressiveHLSArgs packs locally buffered media as fMP4 HLS. HEVC-in-MPEG-TS
// often never reaches first frame on Google TV / ExoPlayer; fMP4 does.
func progressiveHLSArgs(hlsDir, playlist string) []string {
	return []string{
		"-f", "hls",
		"-hls_time", strconv.Itoa(jobs.SegmentTimeS),
		"-hls_list_size", "0",
		"-hls_playlist_type", "event",
		"-hls_segment_type", "fmp4",
		"-hls_fmp4_init_filename", "init.mp4",
		"-hls_flags", "independent_segments+omit_endlist",
		"-hls_segment_filename", filepath.Join(hlsDir, "seg_%05d.m4s"),
		playlist,
	}
}
