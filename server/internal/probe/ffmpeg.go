package probe

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

type Prober struct {
	ffmpeg  string
	ffprobe string
}

type Info struct {
	VideoCodec string
	AudioCodec string // normalized; "dts-hd" when profile is DTS-HD
	Width      int
	Height     int
	HDR        string // dolbyvision | hdr10+ | hdr10 | hlg
	Atmos      bool
	DurationMs int64
	Raw        json.RawMessage
}

func New(ffmpeg, ffprobe string) *Prober {
	return &Prober{ffmpeg: ffmpeg, ffprobe: ffprobe}
}

func (p *Prober) FFmpeg() string  { return p.ffmpeg }
func (p *Prober) FFprobe() string { return p.ffprobe }

func (p *Prober) Version(ctx context.Context) string {
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, p.ffmpeg, "-version")
	out, err := cmd.Output()
	if err != nil {
		return ""
	}
	line, _, _ := strings.Cut(string(out), "\n")
	return strings.TrimSpace(line)
}

func (p *Prober) Duration(ctx context.Context, src string) (int64, error) {
	if strings.TrimSpace(src) == "" {
		return 0, fmt.Errorf("empty probe source")
	}
	ctx, cancel := context.WithTimeout(ctx, 12*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, p.ffprobe,
		"-v", "quiet",
		"-probesize", "4M",
		"-analyzeduration", "4M",
		"-show_entries", "format=duration",
		"-of", "csv=p=0",
		src,
	)
	out, err := cmd.Output()
	if err != nil {
		return 0, err
	}
	secs, err := strconv.ParseFloat(strings.TrimSpace(string(out)), 64)
	if err != nil || secs <= 0 {
		return 0, fmt.Errorf("no duration")
	}
	return int64(secs * 1000), nil
}

func (p *Prober) Probe(ctx context.Context, path string) (Info, error) {
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, p.ffprobe,
		"-v", "quiet",
		"-print_format", "json",
		"-show_format",
		"-show_streams",
		path,
	)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return Info{}, fmt.Errorf("ffprobe: %w (%s)", err, strings.TrimSpace(stderr.String()))
	}
	raw := json.RawMessage(bytes.TrimSpace(stdout.Bytes()))
	var parsed ffprobeResult
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return Info{}, err
	}
	info := Info{Raw: raw}
	if parsed.Format.Duration != "" {
		if secs, err := strconv.ParseFloat(parsed.Format.Duration, 64); err == nil {
			info.DurationMs = int64(secs * 1000)
		}
	}
	bestAudioRank := -1
	for _, stream := range parsed.Streams {
		switch stream.CodecType {
		case "video":
			if stream.Disposition.AttachedPic == 1 {
				continue
			}
			codec := normalizeCodec(stream.CodecName)
			hdr := detectHDR(stream)
			if info.VideoCodec == "" {
				info.VideoCodec = codec
				info.Width = stream.Width
				info.Height = stream.Height
				info.HDR = hdr
				continue
			}
			// Prefer a stream that exposes Dolby Vision / HDR10+ over a SDR twin.
			if hdrRank(hdr) > hdrRank(info.HDR) {
				info.HDR = hdr
				if stream.Width > 0 {
					info.Width = stream.Width
					info.Height = stream.Height
				}
				if codec != "" {
					info.VideoCodec = codec
				}
			}
		case "audio":
			codec := normalizeAudioCodec(stream)
			if audioHasAtmos(stream) {
				info.Atmos = true
			}
			rank := audioRank(codec)
			if rank > bestAudioRank {
				bestAudioRank = rank
				info.AudioCodec = codec
			}
		}
	}
	return info, nil
}

type ffprobeResult struct {
	Streams []ffStream `json:"streams"`
	Format  ffFormat   `json:"format"`
}

type ffFormat struct {
	Duration string `json:"duration"`
}

type ffStream struct {
	CodecType      string            `json:"codec_type"`
	CodecName      string            `json:"codec_name"`
	Profile        string            `json:"profile"`
	Width          int               `json:"width"`
	Height         int               `json:"height"`
	ColorTransfer  string            `json:"color_transfer"`
	ColorPrimaries string            `json:"color_primaries"`
	Disposition    ffDisposition     `json:"disposition"`
	SideDataList   []ffSideData      `json:"side_data_list"`
	Tags           map[string]string `json:"tags"`
}

type ffDisposition struct {
	AttachedPic int `json:"attached_pic"`
}

type ffSideData struct {
	Type string `json:"side_data_type"`
}

func detectHDR(s ffStream) string {
	best := ""
	for _, sd := range s.SideDataList {
		t := strings.ToLower(sd.Type)
		switch {
		case strings.Contains(t, "dovi") || strings.Contains(t, "dolby vision"):
			return "dolbyvision"
		case strings.Contains(t, "smpte2094") || strings.Contains(t, "hdr10+") ||
			(strings.Contains(t, "dynamic metadata") && strings.Contains(t, "hdr")):
			best = maxHDR(best, "hdr10+")
		}
	}
	for _, v := range s.Tags {
		lv := strings.ToLower(v)
		switch {
		case strings.Contains(lv, "dolby vision") || strings.Contains(lv, "dovi"):
			return "dolbyvision"
		case strings.Contains(lv, "hdr10+"):
			best = maxHDR(best, "hdr10+")
		case strings.Contains(lv, "hdr10"):
			best = maxHDR(best, "hdr10")
		}
	}
	switch strings.ToLower(s.ColorTransfer) {
	case "smpte2084":
		best = maxHDR(best, "hdr10")
	case "arib-std-b67":
		best = maxHDR(best, "hlg")
	}
	return best
}

func hdrRank(h string) int {
	switch strings.ToLower(h) {
	case "dolbyvision", "dovi":
		return 4
	case "hdr10+":
		return 3
	case "hdr10":
		return 2
	case "hlg":
		return 1
	default:
		return 0
	}
}

func maxHDR(a, b string) string {
	if hdrRank(b) > hdrRank(a) {
		return b
	}
	return a
}

func normalizeAudioCodec(s ffStream) string {
	name := normalizeCodec(s.CodecName)
	prof := strings.ToLower(s.Profile)
	if name == "dts" && (strings.Contains(prof, "dts-hd") || strings.Contains(prof, "ma") || strings.Contains(prof, "hra")) {
		return "dts-hd"
	}
	if name == "truehd" {
		return "truehd"
	}
	return name
}

func audioHasAtmos(s ffStream) bool {
	blob := strings.ToLower(s.CodecName + " " + s.Profile)
	for k, v := range s.Tags {
		blob += " " + strings.ToLower(k) + " " + strings.ToLower(v)
	}
	return strings.Contains(blob, "atmos")
}

func audioRank(codec string) int {
	switch strings.ToLower(codec) {
	case "truehd":
		return 6
	case "dts-hd":
		return 5
	case "dts":
		return 4
	case "eac3":
		return 3
	case "ac3":
		return 2
	case "aac", "opus", "flac":
		return 1
	default:
		return 0
	}
}

func normalizeCodec(name string) string {
	switch strings.ToLower(name) {
	case "h264", "avc":
		return "h264"
	case "hevc", "h265":
		return "hevc"
	case "av1":
		return "av1"
	case "vp9":
		return "vp9"
	case "vp8":
		return "vp8"
	case "aac":
		return "aac"
	case "ac3":
		return "ac3"
	case "eac3":
		return "eac3"
	case "truehd":
		return "truehd"
	case "dts":
		return "dts"
	case "opus":
		return "opus"
	case "flac":
		return "flac"
	default:
		return strings.ToLower(name)
	}
}
