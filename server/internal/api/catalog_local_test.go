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

func TestAnnotateLocalCandidatesInjectsOrphans(t *testing.T) {
	cands := []streams.Candidate{
		{Title: "Remote only", InfoHash: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", Quality: "1080p"},
	}
	locals := []localSourceHit{{
		MediaID:    "orphan1",
		FileName:   "Bad.Boys.broken.mkv",
		Quality:    "2160p",
		ProbeError: "probe found no streams",
		SizeBytes:  100,
		SizeLabel:  "100 B",
	}}
	out := annotateLocalCandidates(cands, locals)
	if len(out) != 2 {
		t.Fatalf("len=%d out=%v", len(out), out)
	}
	if out[0]["source"] != "local" || out[0]["mediaId"] != "orphan1" {
		t.Fatalf("orphan first=%v", out[0])
	}
	if out[0]["playable"] != false {
		t.Fatalf("broken orphan should not be playable: %v", out[0])
	}
}

func TestMatchLocalSourceSkipsWeb(t *testing.T) {
	locals := []localSourceHit{{
		MediaID:    "local1",
		Quality:    "1080p",
		SizeLabel:  "2.1 GB",
		ReleaseTitle: "Bad.Boys.1080p.mkv",
	}}
	web := streams.Candidate{
		Title:    "Bad Boys · Server 1",
		Name:     "[Web] Server 1",
		Quality:  "1080p",
		Source:   "web",
		Provider: "1movies",
		Kind:     "web",
	}
	if id, ok := matchLocalSource(web, locals); ok {
		t.Fatalf("web must not match local: id=%q", id)
	}
}

func TestMatchLocalSourceRequiresQualityAndSize(t *testing.T) {
	locals := []localSourceHit{{
		MediaID:   "local1",
		Quality:   "1080p",
		SizeLabel: "2.1 GB",
	}}
	onlyQuality := streams.Candidate{
		Title:    "Some.Torrent.1080p",
		InfoHash: "cccccccccccccccccccccccccccccccccccccccc",
		Quality:  "1080p",
		Source:   "torrentio",
		Kind:     "torrent",
	}
	if id, ok := matchLocalSource(onlyQuality, locals); ok {
		t.Fatalf("quality-only must not match: id=%q", id)
	}
	both := onlyQuality
	both.SizeLabel = "2.1 GB"
	id, ok := matchLocalSource(both, locals)
	if !ok || id != "local1" {
		t.Fatalf("quality+size should match: ok=%v id=%q", ok, id)
	}
}
