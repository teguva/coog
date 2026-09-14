package settings

import "testing"

func TestNormalizePreferredBackdropMax(t *testing.T) {
	cases := map[string]string{
		"":       "1080p",
		"1080p":  "1080p",
		"1440p":  "1440p",
		"4k":     "2160p",
		"2160":   "2160p",
		"qhd":    "1440p",
	}
	for in, want := range cases {
		if got := NormalizePreferredBackdropMax(in); got != want {
			t.Fatalf("%q: got %s want %s", in, got, want)
		}
	}
}

func TestBackdropDisplayMaxEdge(t *testing.T) {
	if BackdropDisplayMaxEdge("1080p") != 1920 {
		t.Fatal("1080")
	}
	if BackdropDisplayMaxEdge("1440p") != 2560 {
		t.Fatal("1440")
	}
	if BackdropDisplayMaxEdge("2160p") != 3840 {
		t.Fatal("2160")
	}
}
