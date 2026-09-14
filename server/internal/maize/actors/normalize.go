package actors

import (
	"net/url"
	"regexp"
	"strings"
)

var (
	reHeight = regexp.MustCompile(`(?i)(\d+\s*(cm|mm)\b)|(\d+\s*feet)|(\d+\s*ft\b)|(\d+\s*inches?\b)|(\d+'\s*\d)|(\d+\s*"\s*\d)`)
	reWeight = regexp.MustCompile(`(?i)\b\d+(\.\d+)?\s*(lbs?|kg)\b`)
	reMeas   = regexp.MustCompile(`(?i)\b\d{2,3}\s*[a-z]?\s*[-–]\s*\d{2}(\s*[-–]\s*\d{2})?\b`)
	reShoe   = regexp.MustCompile(`(?i)^\s*(us|eu|uk)\s*\d+(\.\d+)?\s*$`)
	reURL    = regexp.MustCompile(`(?i)^https?://|www\.`)
)

var eyeColors = map[string]bool{
	"blue": true, "green": true, "brown": true, "hazel": true,
	"gray": true, "grey": true, "amber": true, "black": true,
}

var hairColors = map[string]bool{
	"blond": true, "blonde": true, "brunette": true, "auburn": true,
	"redhead": true, "red": true, "black": true, "brown": true,
	"dark": true, "light": true,
}

var ethnicityWords = map[string]bool{
	"caucasian": true, "white": true, "black": true, "african": true,
	"asian": true, "latina": true, "latino": true, "latin": true,
	"hispanic": true, "mixed": true, "multiracial": true, "indian": true,
	"middle": true, "eastern": true, "arab": true, "pacific": true,
	"islander": true, "native": true, "american": true, // "Native American"
	"european": true, "ebony": true,
}

var nationalityWords = map[string]bool{
	"american": true, "canadian": true, "mexican": true, "brazilian": true,
	"british": true, "english": true, "scottish": true, "irish": true,
	"french": true, "german": true, "italian": true, "spanish": true,
	"portuguese": true, "dutch": true, "polish": true, "czech": true,
	"hungarian": true, "russian": true, "ukrainian": true, "romanian": true,
	"swedish": true, "norwegian": true, "danish": true, "finnish": true,
	"australian": true, "japanese": true, "chinese": true, "korean": true,
	"thai": true, "vietnamese": true, "filipino": true, "indian": true,
	"colombian": true, "argentine": true, "cuban": true, "puerto": true,
	"lebanese": true, "israeli": true, "turkish": true, "greek": true,
}

// DisplayStats are body/identity fields after correcting Funplay/IAFD column shifts.
type DisplayStats struct {
	Ethnicity    string
	Nationality  string
	HairColor    string
	EyeColor     string
	Height       string
	Weight       string
	Measurements string
	ShoeSize     string
	Tattoos      string
	Piercings    string
}

type fieldKind int

const (
	kindUnknown fieldKind = iota
	kindURL
	kindHeight
	kindWeight
	kindMeasurements
	kindShoe
	kindEye
	kindHair
	kindEthnicity
	kindNationality
	kindTattoo
	kindPiercing
	kindAward
	kindNone
)

// NormalizeMetaStats reclassifies ethnicity/height/measurements when Funplay
// stuffed the wrong IAFD columns into those keys. Mutates m in place for the
// three primary slots and returns extra display fields.
func NormalizeMetaStats(m *Meta) DisplayStats {
	if m == nil {
		return DisplayStats{}
	}
	raw := []string{m.Ethnicity, m.Height, m.Measurements}
	m.Ethnicity, m.Height, m.Measurements = "", "", ""

	var out DisplayStats
	for _, v := range raw {
		v = strings.TrimSpace(strings.ReplaceAll(v, "\u00a0", " "))
		if v == "" {
			continue
		}
		switch classifyActorField(v) {
		case kindNone:
			continue
		case kindURL:
			ensureLink(m, v)
		case kindHeight:
			if out.Height == "" {
				out.Height = v
			}
		case kindWeight:
			if out.Weight == "" {
				out.Weight = v
			}
		case kindMeasurements:
			if out.Measurements == "" {
				out.Measurements = v
			}
		case kindShoe:
			if out.ShoeSize == "" {
				out.ShoeSize = v
			}
		case kindEye:
			if out.EyeColor == "" {
				out.EyeColor = titleCaseWord(v)
			}
		case kindHair:
			if out.HairColor == "" {
				out.HairColor = titleCaseWord(v)
			}
		case kindEthnicity:
			if out.Ethnicity == "" {
				out.Ethnicity = v
			}
		case kindNationality:
			if out.Nationality == "" {
				out.Nationality = titleCaseWord(v)
			}
		case kindTattoo:
			if out.Tattoos == "" {
				out.Tattoos = v
			}
		case kindPiercing:
			if out.Piercings == "" {
				out.Piercings = v
			}
		case kindAward:
			// Awards are noise on the overview details card — drop.
		default:
			// Keep unknown only if it looks like a short identity label.
			if out.Ethnicity == "" && len(v) <= 40 && !strings.Contains(v, ";") {
				out.Ethnicity = v
			}
		}
	}

	m.Ethnicity = out.Ethnicity
	m.Height = out.Height
	m.Measurements = out.Measurements
	m.Nationality = out.Nationality
	m.HairColor = out.HairColor
	m.EyeColor = out.EyeColor
	m.Weight = out.Weight
	m.ShoeSize = out.ShoeSize
	m.Tattoos = out.Tattoos
	m.Piercings = out.Piercings
	return out
}

func classifyActorField(v string) fieldKind {
	low := strings.ToLower(strings.TrimSpace(v))
	if low == "" || low == "none" || low == "n/a" || low == "-" {
		return kindNone
	}
	if reURL.MatchString(low) {
		return kindURL
	}
	if reHeight.MatchString(v) {
		return kindHeight
	}
	if reWeight.MatchString(v) {
		return kindWeight
	}
	if reMeas.MatchString(v) {
		return kindMeasurements
	}
	if reShoe.MatchString(v) {
		return kindShoe
	}
	if strings.HasPrefix(low, "nominee:") || strings.HasPrefix(low, "winner:") || strings.Contains(low, "award:") {
		return kindAward
	}
	if looksPiercing(low) {
		return kindPiercing
	}
	if looksTattoo(v, low) {
		return kindTattoo
	}
	if looksEye(low) {
		return kindEye
	}
	if looksHair(low) {
		return kindHair
	}
	if looksEthnicity(low) {
		return kindEthnicity
	}
	if looksNationality(low) {
		return kindNationality
	}
	if low == "uncircumcised" || low == "circumcised" {
		return kindNone
	}
	return kindUnknown
}

func looksEye(low string) bool {
	parts := splitIdentity(low)
	if len(parts) == 0 || len(parts) > 3 {
		return false
	}
	for _, p := range parts {
		if !eyeColors[p] {
			return false
		}
	}
	// Single "black"/"brown" alone is ambiguous — treat as eye only when
	// multi-token eye combo (brown/grey) or explicit known eye-only words.
	if len(parts) == 1 {
		switch parts[0] {
		case "blue", "green", "hazel", "gray", "grey", "amber", "brown":
			return true
		case "black":
			return false // usually ethnicity in this dataset
		}
	}
	return true
}

func looksHair(low string) bool {
	parts := splitIdentity(low)
	if len(parts) == 0 || len(parts) > 3 {
		return false
	}
	if strings.Contains(low, "hair") {
		return true
	}
	for _, p := range parts {
		if p == "blond" || p == "blonde" || p == "brunette" || p == "auburn" || p == "redhead" {
			return true
		}
	}
	return false
}

func looksEthnicity(low string) bool {
	parts := splitIdentity(low)
	if len(parts) == 0 || len(parts) > 4 {
		return false
	}
	hit := false
	for _, p := range parts {
		if ethnicityWords[p] {
			hit = true
			continue
		}
		// allow connectors already stripped
		return false
	}
	return hit
}

func looksNationality(low string) bool {
	parts := splitIdentity(low)
	if len(parts) != 1 {
		// "Puerto Rican" etc.
		if len(parts) == 2 && parts[0] == "puerto" && parts[1] == "rican" {
			return true
		}
		return false
	}
	return nationalityWords[parts[0]]
}

func looksTattoo(v, low string) bool {
	if strings.Contains(low, "tattoo") {
		return true
	}
	if strings.Count(v, `"`) >= 2 {
		return true
	}
	body := []string{"wrist", "forearm", "shoulder", "ankle", "hip", "back", "neck", "thigh", "rib", "buttock", "heel", "foot", "elbow", "pectoral", "armpit", "groin"}
	hits := 0
	for _, b := range body {
		if strings.Contains(low, b) {
			hits++
		}
	}
	return hits >= 1 && (strings.Contains(low, ";") || len(strings.TrimSpace(v)) >= 14 || hits >= 2)
}

func looksPiercing(low string) bool {
	switch low {
	case "navel", "nipples", "nipple", "left nostril", "right nostril", "nose", "septum", "tongue", "navel piercing":
		return true
	}
	return strings.Contains(low, "piercing") || strings.Contains(low, "nostril")
}

func splitIdentity(low string) []string {
	low = strings.ReplaceAll(low, "/", " ")
	low = strings.ReplaceAll(low, "-", " ")
	fields := strings.Fields(low)
	out := make([]string, 0, len(fields))
	for _, f := range fields {
		f = strings.Trim(f, ".,()")
		if f != "" {
			out = append(out, f)
		}
	}
	return out
}

func ensureLink(m *Meta, raw string) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return
	}
	if m.Links == nil {
		m.Links = map[string]string{}
	}
	key := "web"
	if u, err := url.Parse(raw); err == nil && u.Host != "" {
		host := strings.TrimPrefix(strings.ToLower(u.Host), "www.")
		if i := strings.IndexByte(host, '.'); i > 0 {
			key = host[:i]
		} else {
			key = host
		}
	}
	if _, ok := m.Links[key]; !ok {
		m.Links[key] = raw
	}
}

func titleCaseWord(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return s
	}
	parts := strings.FieldsFunc(s, func(r rune) bool {
		return r == '/' || r == '-' || r == ' '
	})
	for i, p := range parts {
		if p == "" {
			continue
		}
		parts[i] = strings.ToUpper(p[:1]) + strings.ToLower(p[1:])
	}
	// preserve slash style for Brown/Grey
	if strings.Contains(s, "/") {
		return strings.Join(parts, "/")
	}
	return strings.Join(parts, " ")
}
