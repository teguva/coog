package api

import "testing"

func TestParseBytesRange(t *testing.T) {
	start, end, ok := parseBytesRange("bytes=0-99", 1000)
	if !ok || start != 0 || end != 99 {
		t.Fatalf("full range: %d-%d ok=%v", start, end, ok)
	}
	start, end, ok = parseBytesRange("bytes=100-", 500)
	if !ok || start != 100 || end != 499 {
		t.Fatalf("open end: %d-%d ok=%v", start, end, ok)
	}
	start, end, ok = parseBytesRange("bytes=-50", 200)
	if !ok || start != 150 || end != 199 {
		t.Fatalf("suffix: %d-%d ok=%v", start, end, ok)
	}
	if _, _, ok := parseBytesRange("bytes=abc-10", 100); ok {
		t.Fatal("bad start must fail")
	}
}

func TestParseBytesRangeClampsToPresentViaCaller(t *testing.T) {
	// Handler clamps end >= present; parser itself returns the asked end.
	start, end, ok := parseBytesRange("bytes=900-5000", 1000)
	if !ok || start != 900 || end != 5000 {
		t.Fatalf("got %d-%d ok=%v", start, end, ok)
	}
}
