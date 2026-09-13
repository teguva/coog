package interactive

import (
	"regexp"
	"strings"
)

// Lovense BLE broadcast names → stable-id keywords (never match on "lovense" alone).
// Ported from Funplay interacter LVS_BLE_PATTERNS.
var lvsBLEPatterns = map[string][]string{
	"ferri":  {"lvs-x02", "x02"},
	"gush":   {"lvs-gush", "gush"},
	"solace": {"lvs-b12", "b12", "solace"},
	"lush":   {"lvs-lush", "lvs-c18", "c18", "lush"},
	"edge":   {"lvs-edge", "edge"},
	"nora":   {"lvs-nora", "nora"},
	"max":    {"lvs-max", "max"},
	"hush":   {"lvs-hush", "hush"},
	"domi":   {"lvs-domi", "domi"},
}

var (
	reSlugToken   = regexp.MustCompile(`[a-z0-9]+`)
	slugSkipWords = map[string]struct{}{
		"lovense": {}, "name": {}, "pro": {}, "2": {}, "the": {}, "a": {}, "an": {},
	}
)

func deviceSlugKeywords(deviceID, displayName string) map[string]struct{} {
	slug := deviceID
	if strings.HasPrefix(slug, "name:") {
		slug = slug[5:]
	}
	out := map[string]struct{}{}
	add := func(text string) {
		for _, tok := range reSlugToken.FindAllString(strings.ToLower(text), -1) {
			if _, skip := slugSkipWords[tok]; skip {
				continue
			}
			out[tok] = struct{}{}
		}
	}
	add(slug)
	add(displayName)
	return out
}

// bleNameMatchesDevice maps an advertisement (often LVS-C18) to a paired toy
// using model-specific patterns — never "any LVS ↔ any Lovense".
func bleNameMatchesDevice(bleName, deviceID, name, storedBle string) bool {
	bleName = strings.TrimSpace(bleName)
	if bleName == "" {
		return false
	}
	if storedBle != "" && strings.EqualFold(storedBle, bleName) && !isLVSAdvertisement(bleName) {
		return true
	}
	if StableDeviceID(bleName) == deviceID {
		return true
	}
	bleLower := strings.ToLower(bleName)
	for kw := range deviceSlugKeywords(deviceID, name) {
		patterns := lvsBLEPatterns[kw]
		if patterns == nil {
			patterns = []string{kw}
		}
		for _, p := range patterns {
			if strings.Contains(bleLower, p) {
				return true
			}
		}
	}
	// Exact / substring display match only when neither side is a bare brand token.
	if name != "" {
		n := strings.ToLower(name)
		if n == bleLower || (len(bleLower) >= 4 && strings.Contains(n, bleLower)) ||
			(len(n) >= 4 && !strings.EqualFold(n, "lovense") && strings.Contains(bleLower, n)) {
			return true
		}
	}
	// Exact stored LVS name only if patterns already agreed above; allow stored
	// match when patterns matched via keywords (already returned). If the only
	// signal is a previously saved LVS-* on the wrong toy, reject it.
	return false
}

func isLVSAdvertisement(bleName string) bool {
	return strings.HasPrefix(strings.ToUpper(strings.TrimSpace(bleName)), "LVS-")
}
