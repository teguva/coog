package subtitles

import "testing"

func TestTitleOverlapRejectsWrongMovie(t *testing.T) {
	if titleTokenOverlap("Shrek the Third", "LoveHappensEst") != 0 {
		t.Fatal("expected no overlap")
	}
	if titleTokenOverlap("Shrek the Third", "Shrek 3 - Est - 25fps") < 1 {
		t.Fatal("expected shrek token hit")
	}
}
