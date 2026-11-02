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
	// Handler clamps end >= rangeTotal; parser itself returns the asked end.
	start, end, ok := parseBytesRange("bytes=900-5000", 1000)
	if !ok || start != 900 || end != 5000 {
		t.Fatalf("got %d-%d ok=%v", start, end, ok)
	}
}

func TestJobFileRangeTotalWithoutOriginUsesPresent(t *testing.T) {
	present := int64(2_400_000_000)
	total := int64(13_810_000_000)
	got := jobFileRangeTotal(present, total, false)
	if got != present {
		t.Fatalf("no origin: want present=%d, got %d", present, got)
	}
	got = jobFileRangeTotal(total, total, false)
	if got != total {
		t.Fatalf("complete file: want total=%d, got %d", total, got)
	}
}

func TestJobFileRangeTotalWithOriginAdvertisesFinalSize(t *testing.T) {
	present := int64(1_900_000_000)
	total := int64(4_994_736_776)
	got := jobFileRangeTotal(present, total, true)
	if got != total {
		t.Fatalf("with origin: want final=%d, got %d", total, got)
	}
	// Cue-style open end near EOF must parse against final size.
	start, end, ok := parseBytesRange("bytes=4990000000-", total)
	if !ok || start != 4_990_000_000 || end != total-1 {
		t.Fatalf("cue range against total: %d-%d ok=%v", start, end, ok)
	}
	if start < present {
		t.Fatal("cue range should start past local present so handler proxies origin")
	}
}
