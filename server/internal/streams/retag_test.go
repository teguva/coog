package streams

import (
	"reflect"
	"testing"
)

func TestMergeFileMetaPrefersProbeTechnical(t *testing.T) {
	q, tags, size := MergeFileMeta(
		"1080p",
		[]string{"DV", "Atmos", "WEB", "HEVC", "Hybrid"},
		FileProbeMeta{
			Height:     2160,
			VideoCodec: "hevc",
			AudioCodec: "eac3",
			HDR:        "hdr10",
			Atmos:      false,
			SizeBytes:  20_540_000_000,
		},
	)
	if q != "2160p" {
		t.Fatalf("quality=%s", q)
	}
	if size != "20.54 GB" {
		t.Fatalf("size=%s", size)
	}
	// DV/Atmos from title dropped when probe disagrees; WEB/Hybrid kept.
	want := []string{"WEB", "Hybrid", "HDR", "HEVC"}
	if !reflect.DeepEqual(tags, want) {
		t.Fatalf("tags=%v want %v", tags, want)
	}
}

func TestMergeFileMetaKeepsDVWhenProbed(t *testing.T) {
	_, tags, _ := MergeFileMeta(
		"2160p",
		[]string{"WEB", "DV", "Atmos"},
		FileProbeMeta{
			Height:     2160,
			VideoCodec: "hevc",
			AudioCodec: "truehd",
			HDR:        "dolbyvision",
			Atmos:      true,
			SizeBytes:  22_000_000_000,
		},
	)
	want := []string{"WEB", "DV", "Atmos", "TrueHD", "HEVC"}
	if !reflect.DeepEqual(tags, want) {
		t.Fatalf("tags=%v want %v", tags, want)
	}
}

func TestMergeFileMetaFallsBackWhenNoProbe(t *testing.T) {
	q, tags, size := MergeFileMeta("1080p", []string{"WEB", "HEVC"}, FileProbeMeta{})
	if q != "1080p" {
		t.Fatalf("quality=%s", q)
	}
	if size != "" {
		t.Fatalf("size=%s", size)
	}
	if !reflect.DeepEqual(tags, []string{"WEB", "HEVC"}) {
		t.Fatalf("tags=%v", tags)
	}
}
