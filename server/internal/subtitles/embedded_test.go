package subtitles

import "testing"

func TestParseEmbeddedID(t *testing.T) {
	idx, path, err := parseEmbeddedID("emb:14:/home/priit/Videos/Movies/Shrek the Third (2007)/Shrek the Third (2007).mkv")
	if err != nil {
		t.Fatal(err)
	}
	if idx != 14 {
		t.Fatalf("index=%d", idx)
	}
	if path == "" || path[0] != '/' {
		t.Fatalf("path=%q", path)
	}
	if _, _, err := parseEmbeddedID("emb:nope"); err == nil {
		t.Fatal("expected error")
	}
}

func TestTracksFromProbeStreams_MpvParityNoInference(t *testing.T) {
	// Mirrors Shrek-like layout: tagged PGS, unlabeled PGS, then unlabeled SubRip.
	// Must emit all rows in order; must not copy gre onto the SubRip.
	streams := []probeSubStream{
		{Index: 2, CodecName: "hdmv_pgs_subtitle", Language: "gre"},
		{Index: 3, CodecName: "hdmv_pgs_subtitle", Language: "heb"},
		{Index: 9, CodecName: "hdmv_pgs_subtitle", Language: ""},
		{Index: 14, CodecName: "subrip", Language: ""},
		{Index: 15, CodecName: "subrip", Language: ""},
	}
	got := tracksFromProbeStreams("/media/Shrek.mkv", streams)
	if len(got) != 5 {
		t.Fatalf("len=%d want 5 (no filtering)", len(got))
	}
	if got[0].Language != "el" || got[0].Codec != "hdmv_pgs_subtitle" {
		t.Fatalf("sid1: lang=%q codec=%q", got[0].Language, got[0].Codec)
	}
	if got[0].Label != "#1 · EL · PGS" {
		t.Fatalf("sid1 label=%q", got[0].Label)
	}
	if got[2].Language != "" || got[2].Label != "#3 · und · PGS" {
		t.Fatalf("sid3 unlabeled: lang=%q label=%q", got[2].Language, got[2].Label)
	}
	if got[3].Language != "" || got[3].Codec != "subrip" {
		t.Fatalf("sid4 subrip must stay unlabeled, got lang=%q codec=%q", got[3].Language, got[3].Codec)
	}
	if got[3].Label != "#4 · und · SRT" {
		t.Fatalf("sid4 label=%q", got[3].Label)
	}
	if got[3].ID != "emb:14:/media/Shrek.mkv" {
		t.Fatalf("id=%q", got[3].ID)
	}
}
