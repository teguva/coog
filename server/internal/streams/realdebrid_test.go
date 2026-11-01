package streams

import "testing"

func TestPickRDHostedLinkLargestM2TS(t *testing.T) {
	files := []rdTorrentFile{
		{ID: 1, Path: "/BDMV/STREAM/00001.m2ts", Bytes: 350_000_000, Selected: 1},
		{ID: 2, Path: "/BDMV/STREAM/00004.m2ts", Bytes: 143_000_000, Selected: 1},
		{ID: 3, Path: "/BDMV/STREAM/00006.m2ts", Bytes: 38_000_000, Selected: 1},
		{ID: 4, Path: "/BDMV/STREAM/00007.m2ts", Bytes: 58_490_000_000, Selected: 1},
		{ID: 5, Path: "/BDMV/STREAM/00026.m2ts", Bytes: 29_000_000, Selected: 1},
		{ID: 6, Path: "/BDMV/STREAM/00093.m2ts", Bytes: 238_000_000, Selected: 1},
	}
	links := []string{
		"https://real-debrid.com/d/JEYBE5PJYW4PO48C",
		"https://real-debrid.com/d/LJFTLXLPHHTNEBD4",
		"https://real-debrid.com/d/B67C3YSV5XSOW43A",
		"https://real-debrid.com/d/TENEY6O55D2X27A6",
		"https://real-debrid.com/d/B2CNLWA2LO2TY575",
		"https://real-debrid.com/d/YCZRY372QDKWAC67",
	}
	got := pickRDHostedLink(files, links, 0, "")
	want := "https://real-debrid.com/d/TENEY6O55D2X27A6"
	if got != want {
		t.Fatalf("got %s want %s (main feature 00007.m2ts)", got, want)
	}
}

func TestPickRDHostedLinkByFilename(t *testing.T) {
	files := []rdTorrentFile{
		{ID: 1, Path: "/movie.mkv", Bytes: 20_000_000_000, Selected: 1},
		{ID: 2, Path: "/extras/interview.mkv", Bytes: 500_000_000, Selected: 1},
	}
	links := []string{"https://real-debrid.com/d/BIG", "https://real-debrid.com/d/EXTRA"}
	got := pickRDHostedLink(files, links, 0, "interview.mkv")
	if got != "https://real-debrid.com/d/EXTRA" {
		t.Fatalf("got %s", got)
	}
}

func TestPickRDHostedLinkSkipsSample(t *testing.T) {
	files := []rdTorrentFile{
		{ID: 1, Path: "/Movie.Sample.mkv", Bytes: 40_000_000_000, Selected: 1}, // inflated size but sample
		{ID: 2, Path: "/Movie.2160p.mkv", Bytes: 25_000_000_000, Selected: 1},
	}
	links := []string{"https://real-debrid.com/d/SAMPLE", "https://real-debrid.com/d/MAIN"}
	got := pickRDHostedLink(files, links, 0, "")
	if got != "https://real-debrid.com/d/MAIN" {
		t.Fatalf("got %s want main (sample penalized)", got)
	}
}
