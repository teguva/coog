package acquire

import (
	"os/exec"
	"strings"
	"sync"
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
