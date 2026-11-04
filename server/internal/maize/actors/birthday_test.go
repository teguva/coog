package actors

import (
	"testing"
	"time"
)

func TestNormalizeBirthdayIAFD(t *testing.T) {
	got := NormalizeBirthday("February 23, 1989 (37 years old)")
	if got != "1989-02-23" {
		t.Fatalf("got %q", got)
	}
}

func TestNormalizeBirthdayISO(t *testing.T) {
	got := NormalizeBirthday("1989-2-23")
	if got != "1989-02-23" {
		t.Fatalf("got %q", got)
	}
}

func TestAgeFromBirthday(t *testing.T) {
	now := time.Date(2026, 11, 3, 12, 0, 0, 0, time.UTC)
	age, ok := AgeFromBirthday("February 23, 1989 (37 years old)", now)
	if !ok || age != 37 {
		t.Fatalf("age=%d ok=%v", age, ok)
	}
	// Day before birthday this year.
	age, ok = AgeFromBirthday("1989-02-23", time.Date(2026, 2, 22, 12, 0, 0, 0, time.UTC))
	if !ok || age != 36 {
		t.Fatalf("pre-bday age=%d ok=%v", age, ok)
	}
}
