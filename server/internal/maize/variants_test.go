package maize

import "testing"

func TestQualityRankPrefersHeight(t *testing.T) {
	low := QualityRank(720, 9_000_000_000, "a.mp4")
	high := QualityRank(1080, 1_000_000, "b.mp4")
	if high <= low {
		t.Fatalf("1080p should beat larger 720p file")
	}
}

func TestQualityLabel(t *testing.T) {
	if QualityLabel(1080, "x.mp4") != "1080p" {
		t.Fatal(QualityLabel(1080, "x.mp4"))
	}
	if QualityLabel(0, "Scene [2160p].mp4") != "2160p" {
		t.Fatal(QualityLabel(0, "Scene [2160p].mp4"))
	}
}

func TestResolveFunscriptRejectsTraversal(t *testing.T) {
	if ResolveFunscriptPath("/tmp/scene/a.mp4", "../other.funscript") != "" {
		t.Fatal("expected reject")
	}
}

func TestShortFunscriptLabels(t *testing.T) {
	names := []string{
		"Eva Elfie - Tries a Big Cock inside her Tight Pussy (g90aked).funscript",
		"Eva Elfie - Tries a Big Cock inside her Tight Pussy (g90aked).twist.funscript",
		"Eva Elfie tries a big cock inside her tight pussy.funscript",
		"Eva Elfie tries a big cock inside her tight pussy.twist.funscript",
	}
	labels := shortFunscriptLabels(names)
	if len(labels) != 4 {
		t.Fatalf("got %v", labels)
	}
	seen := map[string]bool{}
	for _, l := range labels {
		if l == "" || seen[l] {
			t.Fatalf("labels not unique/short: %v", labels)
		}
		seen[l] = true
		if len(l) > 40 {
			t.Fatalf("label too long %q", l)
		}
	}
}
