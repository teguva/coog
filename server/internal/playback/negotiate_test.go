package playback

import (
	"strings"
	"testing"

	"coog/internal/store"
)

func TestNegotiateNoProfileAwkwardRemux(t *testing.T) {
	got, err := Negotiate(store.MediaItem{CodecVideo: "hevc", CodecAudio: "truehd"}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if got.Method != MethodRemux {
		t.Fatalf("method %s", got.Method)
	}
}

func TestNegotiateNoProfileAACDirect(t *testing.T) {
	got, err := Negotiate(store.MediaItem{CodecVideo: "h264", CodecAudio: "aac"}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if got.Method != MethodDirect {
		t.Fatalf("method %s", got.Method)
	}
}

func TestNegotiateAwkwardAudioRemux(t *testing.T) {
	caps := &Capabilities{VideoCodecs: []string{"hevc"}, AudioCodecs: []string{"aac"}}
	got, err := Negotiate(store.MediaItem{CodecVideo: "hevc", CodecAudio: "truehd"}, caps)
	if err != nil {
		t.Fatal(err)
	}
	if got.Method != MethodRemux {
		t.Fatalf("method %s reason %s", got.Method, got.Reason)
	}
	if !strings.Contains(got.Reason, "truehd") {
		t.Fatalf("reason %q", got.Reason)
	}
}

func TestNegotiateEAC3Remux(t *testing.T) {
	caps := &Capabilities{VideoCodecs: []string{"hevc", "h264"}, AudioCodecs: []string{"aac", "opus", "mp3"}}
	got, err := Negotiate(store.MediaItem{CodecVideo: "hevc", CodecAudio: "eac3"}, caps)
	if err != nil {
		t.Fatal(err)
	}
	if got.Method != MethodRemux {
		t.Fatalf("method %s reason %s", got.Method, got.Reason)
	}
}

func TestNegotiateSupportedAwkwardAudioDirect(t *testing.T) {
	caps := &Capabilities{VideoCodecs: []string{"hevc"}, AudioCodecs: []string{"aac", "truehd"}}
	got, err := Negotiate(store.MediaItem{CodecVideo: "hevc", CodecAudio: "truehd"}, caps)
	if err != nil {
		t.Fatal(err)
	}
	if got.Method != MethodDirect {
		t.Fatalf("method %s reason %s", got.Method, got.Reason)
	}
}

func TestNegotiateUnknownVideoErrors(t *testing.T) {
	caps := &Capabilities{VideoCodecs: []string{"h264"}}
	_, err := Negotiate(store.MediaItem{CodecVideo: "mpeg2video"}, caps)
	if err == nil {
		t.Fatal("expected transcode error")
	}
}

func TestCapsForcingAudioRemuxStripsAwkward(t *testing.T) {
	caps := &Capabilities{
		VideoCodecs: []string{"h264", "hevc"},
		AudioCodecs: []string{"aac", "eac3", "ac3", "opus"},
	}
	out := CapsForcingAudioRemux(caps)
	if out == nil {
		t.Fatal("nil")
	}
	for _, c := range out.AudioCodecs {
		if c == "eac3" || c == "ac3" {
			t.Fatalf("awkward codec still present: %s", c)
		}
	}
	if !NeedsAudioRemux("eac3", out) {
		t.Fatal("expected remux for eac3 after force")
	}
	if NeedsAudioRemux("eac3", caps) {
		t.Fatal("original caps still claim eac3")
	}
}
