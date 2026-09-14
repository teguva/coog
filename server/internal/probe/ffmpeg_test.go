package probe

import (
	"encoding/json"
	"testing"
)

func TestDetectHDRAndAtmosFromStreams(t *testing.T) {
	raw := []byte(`{
	  "streams": [
	    {
	      "codec_type": "video",
	      "codec_name": "hevc",
	      "width": 3840,
	      "height": 2160,
	      "color_transfer": "smpte2084",
	      "side_data_list": [{"side_data_type": "DOVI configuration record"}]
	    },
	    {
	      "codec_type": "audio",
	      "codec_name": "eac3",
	      "tags": {"title": "English Atmos"}
	    },
	    {
	      "codec_type": "audio",
	      "codec_name": "aac"
	    }
	  ],
	  "format": {"duration": "120.5"}
	}`)
	var parsed ffprobeResult
	if err := json.Unmarshal(raw, &parsed); err != nil {
		t.Fatal(err)
	}
	info := Info{}
	bestAudioRank := -1
	for _, stream := range parsed.Streams {
		switch stream.CodecType {
		case "video":
			info.VideoCodec = normalizeCodec(stream.CodecName)
			info.Width = stream.Width
			info.Height = stream.Height
			info.HDR = detectHDR(stream)
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
	if info.HDR != "dolbyvision" {
		t.Fatalf("hdr=%s", info.HDR)
	}
	if !info.Atmos {
		t.Fatal("expected atmos")
	}
	if info.AudioCodec != "eac3" {
		t.Fatalf("audio=%s", info.AudioCodec)
	}
	if info.Height != 2160 {
		t.Fatalf("height=%d", info.Height)
	}
}

func TestDetectHDR10Plus(t *testing.T) {
	s := ffStream{
		ColorTransfer: "smpte2084",
		SideDataList:  []ffSideData{{Type: "HDR Dynamic Metadata SMPTE2094-40"}},
	}
	if got := detectHDR(s); got != "hdr10+" {
		t.Fatalf("got %s", got)
	}
}
