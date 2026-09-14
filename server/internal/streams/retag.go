package streams

import "strings"

// sourceOnlyTags cannot be verified by ffprobe; keep from the release title.
var sourceOnlyTags = map[string]bool{
	"Remux":  true,
	"BluRay": true,
	"WEB":    true,
	"Hybrid": true,
	"Proper": true,
}

// FileProbeMeta is the subset of ffprobe results used to retag library sidecars.
type FileProbeMeta struct {
	Height     int
	VideoCodec string
	AudioCodec string // hevc/h264/… or truehd/eac3/dts/dts-hd
	HDR        string // dolbyvision | hdr10+ | hdr10 | hlg
	Atmos      bool
	SizeBytes  int64
}

// MergeFileMeta prefers probed technical facts and keeps source-origin tags from the release.
func MergeFileMeta(releaseQuality string, releaseTags []string, p FileProbeMeta) (quality string, tags []string, sizeLabel string) {
	quality = qualityFromHeight(p.Height)
	if quality == "" {
		quality = strings.TrimSpace(releaseQuality)
	}

	tech := technicalTagsFromProbe(p)
	var kept []string
	for _, t := range releaseTags {
		if sourceOnlyTags[t] && !containsTag(kept, t) {
			kept = append(kept, t)
		}
	}
	// If probe found nothing technical, fall back to full release tags.
	if len(tech) == 0 && p.Height <= 0 && strings.TrimSpace(p.VideoCodec) == "" {
		tags = uniqueStrings(append([]string(nil), releaseTags...))
	} else {
		tags = orderFileTags(append(kept, tech...))
	}

	if p.SizeBytes > 0 {
		sizeLabel = FormatSizeLabel(p.SizeBytes)
	}
	return quality, tags, sizeLabel
}

func qualityFromHeight(h int) string {
	switch {
	case h >= 2160:
		return "2160p"
	case h >= 1080:
		return "1080p"
	case h >= 720:
		return "720p"
	case h >= 480:
		return "480p"
	default:
		return ""
	}
}

func technicalTagsFromProbe(p FileProbeMeta) []string {
	var out []string
	switch strings.ToLower(strings.TrimSpace(p.HDR)) {
	case "dolbyvision", "dovi":
		out = append(out, "DV")
	case "hdr10+":
		out = append(out, "HDR10+")
	case "hdr10":
		out = append(out, "HDR")
	}
	if p.Atmos {
		out = append(out, "Atmos")
	}
	switch strings.ToLower(strings.TrimSpace(p.AudioCodec)) {
	case "truehd":
		out = append(out, "TrueHD")
	case "dts-hd", "dtshd":
		out = append(out, "DTS-HD")
	}
	switch strings.ToLower(strings.TrimSpace(p.VideoCodec)) {
	case "hevc", "h265":
		out = append(out, "HEVC")
	case "h264", "avc":
		out = append(out, "AVC")
	}
	return out
}

func orderFileTags(in []string) []string {
	order := []string{
		"Remux", "BluRay", "WEB", "Hybrid",
		"DV", "HDR10+", "HDR",
		"Atmos", "TrueHD", "DTS-HD",
		"HEVC", "AVC", "Proper",
	}
	var out []string
	seen := map[string]bool{}
	for _, want := range order {
		for _, t := range in {
			if t == want && !seen[t] {
				out = append(out, t)
				seen[t] = true
			}
		}
	}
	for _, t := range in {
		if t != "" && !seen[t] {
			out = append(out, t)
			seen[t] = true
		}
	}
	return out
}
