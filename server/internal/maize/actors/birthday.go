package actors

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"
)

var (
	reISODate       = regexp.MustCompile(`(?i)^\s*(\d{4})-(\d{1,2})-(\d{1,2})\s*`)
	reIAFDBirthday  = regexp.MustCompile(`(?i)^\s*([A-Za-z]+)\s+(\d{1,2}),\s*(\d{4})(?:\s*\(\d+\s*years?\s*old\))?\s*$`)
	reEmbeddedAge   = regexp.MustCompile(`(?i)\s*\(\d+\s*years?\s*old\)\s*`)
	monthNameToNum  = map[string]time.Month{
		"january": time.January, "jan": time.January,
		"february": time.February, "feb": time.February,
		"march": time.March, "mar": time.March,
		"april": time.April, "apr": time.April,
		"may": time.May,
		"june": time.June, "jun": time.June,
		"july": time.July, "jul": time.July,
		"august": time.August, "aug": time.August,
		"september": time.September, "sep": time.September, "sept": time.September,
		"october": time.October, "oct": time.October,
		"november": time.November, "nov": time.November,
		"december": time.December, "dec": time.December,
	}
)

// NormalizeBirthday stores calendar dates as YYYY-MM-DD and strips embedded
// "(N years old)" text from IAFD scrapes so age can be computed at display time.
func NormalizeBirthday(raw string) string {
	s := strings.TrimSpace(raw)
	if s == "" {
		return ""
	}
	if m := reISODate.FindStringSubmatch(s); m != nil {
		y, _ := strconv.Atoi(m[1])
		mo, _ := strconv.Atoi(m[2])
		d, _ := strconv.Atoi(m[3])
		if t, ok := validDate(y, time.Month(mo), d); ok {
			return t.Format("2006-01-02")
		}
	}
	if m := reIAFDBirthday.FindStringSubmatch(s); m != nil {
		mon, ok := monthNameToNum[strings.ToLower(m[1])]
		if !ok {
			return strings.TrimSpace(reEmbeddedAge.ReplaceAllString(s, ""))
		}
		d, _ := strconv.Atoi(m[2])
		y, _ := strconv.Atoi(m[3])
		if t, ok := validDate(y, mon, d); ok {
			return t.Format("2006-01-02")
		}
	}
	// Unknown shape — still drop a trailing static age so we don't display a lie.
	cleaned := strings.TrimSpace(reEmbeddedAge.ReplaceAllString(s, ""))
	if cleaned != s {
		return cleaned
	}
	return s
}

// AgeFromBirthday returns whole years since a YYYY-MM-DD (or normalizeable) birthday.
func AgeFromBirthday(raw string, now time.Time) (int, bool) {
	iso := NormalizeBirthday(raw)
	if iso == "" || !reISODate.MatchString(iso) {
		return 0, false
	}
	t, err := time.ParseInLocation("2006-01-02", iso, time.UTC)
	if err != nil {
		return 0, false
	}
	if now.IsZero() {
		now = time.Now().UTC()
	}
	age := now.Year() - t.Year()
	bday := time.Date(now.Year(), t.Month(), t.Day(), 0, 0, 0, 0, time.UTC)
	if now.Before(bday) {
		age--
	}
	if age < 0 || age > 130 {
		return 0, false
	}
	return age, true
}

func validDate(year int, month time.Month, day int) (time.Time, bool) {
	if year < 1900 || year > time.Now().Year() || month < 1 || month > 12 || day < 1 || day > 31 {
		return time.Time{}, false
	}
	t := time.Date(year, month, day, 0, 0, 0, 0, time.UTC)
	if t.Year() != year || t.Month() != month || t.Day() != day {
		return time.Time{}, false
	}
	return t, true
}

// FormatBirthdayDisplay turns YYYY-MM-DD into "2 Jan 1990"; other strings pass through.
func FormatBirthdayDisplay(raw string) string {
	iso := NormalizeBirthday(raw)
	if iso == "" {
		return ""
	}
	if t, err := time.ParseInLocation("2006-01-02", iso, time.UTC); err == nil {
		return fmt.Sprintf("%d %s %d", t.Day(), t.Format("Jan"), t.Year())
	}
	return iso
}
