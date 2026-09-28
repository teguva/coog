package api

import (
	"testing"

	"coog/internal/streams"
)

func TestMatchLocalSourceByInfoHash(t *testing.T) {
	locals := []localSourceHit{{
		MediaID:  "abc",
		InfoHash: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
	}}
	c := streams.Candidate{InfoHash: "magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
	id, ok := matchLocalSource(c, locals)
	if !ok || id != "abc" {
		t.Fatalf("got %v %q", ok, id)
	}
}

func TestMatchLocalSourceByReleaseTitle(t *testing.T) {
	locals := []localSourceHit{{
		MediaID:      "m1",
		ReleaseTitle: "Bad.Boys.1995.2160p.BluRay.REMUX.mkv",
	}}
	c := streams.Candidate{Title: "Bad Boys 1995 2160p BluRay REMUX"}
	id, ok := matchLocalSource(c, locals)
	if !ok || id != "m1" {
		t.Fatalf("got %v %q", ok, id)
	}
}

func TestAnnotateLocalCandidatesSortsFirst(t *testing.T) {
	cands := []streams.Candidate{
		{Title: "Other", InfoHash: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", Quality: "1080p"},
		{Title: "Mine", InfoHash: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", Quality: "2160p"},
	}
	locals := []localSourceHit{{
		MediaID:  "local1",
		InfoHash: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
	}}
	out := annotateLocalCandidates(cands, locals)
	if len(out) != 2 {
		t.Fatalf("len=%d", len(out))
	}
	if out[0]["inLibrary"] != true || out[0]["mediaId"] != "local1" {
		t.Fatalf("first=%v", out[0])
	}
	if _, ok := out[1]["inLibrary"]; ok {
		t.Fatalf("second should not be local: %v", out[1])
	}
}
