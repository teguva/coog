package interactive

import (
	"regexp"
	"strings"
)

var nonSlug = regexp.MustCompile(`[^a-z0-9]+`)

func StableDeviceID(name string) string {
	slug := strings.ToLower(strings.TrimSpace(name))
	slug = nonSlug.ReplaceAllString(slug, "-")
	slug = strings.Trim(slug, "-")
	if slug == "" {
		slug = "device"
	}
	return "name:" + slug
}

func ClampIntensity(v int) int {
	if v < 10 {
		return 10
	}
	if v > 200 {
		return 200
	}
	return v
}

func SanitizeProfile(p string) string {
	switch strings.ToLower(strings.TrimSpace(p)) {
	case "hand", "body", "remote", "other":
		return strings.ToLower(strings.TrimSpace(p))
	default:
		return "other"
	}
}

func IsVortexName(name string) bool {
	n := strings.ToLower(strings.TrimSpace(name))
	return strings.Contains(n, "funplay vortex") ||
		strings.Contains(n, "vacuumctrl") ||
		n == "sexverse device" ||
		strings.Contains(n, "funplay vacuum")
}

func IsVortexStableID(deviceID string) bool {
	sid := strings.ToLower(strings.TrimSpace(deviceID))
	switch sid {
	case "name:funplay-vortex", "name:vacuumctrl", "name:funplay-vacuum", "name:sexverse-device":
		return true
	}
	return strings.Contains(sid, "vortex")
}

