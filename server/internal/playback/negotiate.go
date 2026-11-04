package playback

import (
	"fmt"
	"strings"

	"coog/internal/store"
)

type Capabilities struct {
	VideoCodecs []string `json:"videoCodecs"`
	AudioCodecs []string `json:"audioCodecs"`
	Containers  []string `json:"containers"`
	HDR         []string `json:"hdr"`
}

type Result struct {
	Method string
	Reason string
}

const (
	MethodDirect      = "direct"
	MethodRemux       = "remux"
	MethodTranscode   = "transcode"
	MethodProgressive = "progressive"
)

// ConcurrentTranscodeLimit is documented for Phase 6; not enforced yet.
const ConcurrentTranscodeLimit = 2

func Negotiate(item store.MediaItem, caps *Capabilities) (Result, error) {
	if caps == nil || len(caps.VideoCodecs) == 0 {
		// Without a profile, still remux TrueHD/DTS/E-AC3 — raw progressive
		// play is silent on most Android / Google TV builds.
		if awkwardAudio(item.CodecAudio) {
			return Result{
				Method: MethodRemux,
				Reason: "awkward audio " + item.CodecAudio + "; remux to AAC (no client profile)",
			}, nil
		}
		return Result{Method: MethodDirect, Reason: "no client profile; try direct play"}, nil
	}
	if item.CodecVideo != "" && !containsCodec(caps.VideoCodecs, item.CodecVideo) {
		return Result{}, fmt.Errorf("transcode required: video codec %s is not in client profile", item.CodecVideo)
	}
	if awkwardAudio(item.CodecAudio) && !containsCodec(caps.AudioCodecs, item.CodecAudio) {
		return Result{
			Method: MethodRemux,
			Reason: "awkward audio " + item.CodecAudio + "; remux to AAC for client",
		}, nil
	}
	return Result{Method: MethodDirect, Reason: "client can direct play"}, nil
}

// NeedsAudioRemux is true when the source audio will be silent on the client.
func NeedsAudioRemux(codec string, caps *Capabilities) bool {
	if !awkwardAudio(codec) {
		return false
	}
	if caps == nil || len(caps.AudioCodecs) == 0 {
		return true
	}
	return !containsCodec(caps.AudioCodecs, codec)
}

// CapsForcingAudioRemux returns a copy of caps with awkward audio codecs removed
// so Negotiate / NeedsAudioRemux fall back to AAC remux for this session only.
func CapsForcingAudioRemux(caps *Capabilities) *Capabilities {
	if caps == nil {
		return nil
	}
	cp := *caps
	filtered := make([]string, 0, len(caps.AudioCodecs))
	for _, c := range caps.AudioCodecs {
		if !awkwardAudio(c) {
			filtered = append(filtered, c)
		}
	}
	cp.AudioCodecs = filtered
	return &cp
}

func awkwardAudio(codec string) bool {
	switch normalize(codec) {
	case "truehd", "dts", "dtshd", "pcm_bluray", "pcm_s24le", "ac3", "eac3":
		return true
	default:
		return false
	}
}

func containsCodec(list []string, codec string) bool {
	want := normalize(codec)
	for _, c := range list {
		have := normalize(c)
		if have == want {
			return true
		}
		if want == "hevc" && (have == "h265" || have == "hevc") {
			return true
		}
		if want == "h264" && (have == "avc" || have == "h264") {
			return true
		}
		// DTS-HD decoders handle core DTS; MediaCodec often advertises "dtshd" while
		// ffprobe labels the stream "dts-hd".
		if want == "dts" && have == "dtshd" {
			return true
		}
	}
	return false
}

func normalize(s string) string {
	s = strings.ToLower(strings.TrimSpace(s))
	s = strings.TrimPrefix(s, "video/")
	s = strings.TrimPrefix(s, "audio/")
	s = strings.ReplaceAll(s, ".", "")
	s = strings.ReplaceAll(s, "_", "")
	s = strings.ReplaceAll(s, "-", "")
	s = strings.ReplaceAll(s, ":", "")
	s = strings.ReplaceAll(s, " ", "")
	switch s {
	case "h265", "hev1", "hvc1":
		return "hevc"
	case "avc", "avc1":
		return "h264"
	case "ec3", "ddp", "ddplus", "eacjoc":
		return "eac3"
	case "ac3":
		return "ac3"
	case "truehd", "mlp":
		return "truehd"
	case "dca", "dts":
		return "dts"
	case "dtshd", "dtsma", "dtsx":
		return "dtshd"
	}
	// ffprobe "dts-hd" → "dtshd" after separator strip; also catch dtsma/hd leftovers.
	if strings.HasPrefix(s, "dts") && s != "dts" {
		return "dtshd"
	}
	return s
}
