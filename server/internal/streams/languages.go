package streams

import (
	"strings"
	"unicode"
)

// languageHint maps a release token onto a Torrentio-style language code.
type languageHint struct {
	code   string
	tokens []string
}

// Order matches Torrentio's languageMapping so flags scan the same way.
var languageHints = []languageHint{
	{code: "dubbed", tokens: []string{"dubbed", "dub"}},
	{code: "multi", tokens: []string{"multi audio", "multi-audio", "multiaudio", "multi"}},
	{code: "subs", tokens: []string{"multi subs", "multi-subs", "multisubs"}},
	{code: "dual", tokens: []string{"dual audio", "dual-audio", "dualaudio", "dual"}},
	{code: "en", tokens: []string{"english", "eng", "en"}},
	{code: "ja", tokens: []string{"japanese", "nihongo", "jpn", "jap", "ja"}},
	{code: "ru", tokens: []string{"russian", "rus"}},
	{code: "it", tokens: []string{"italian", "ita"}},
	{code: "pt", tokens: []string{"portuguese", "brazilian", "brazil", "ptbr", "pt br", "por", "pt"}},
	{code: "es", tokens: []string{"spanish", "castellano", "espanol", "español", "spa", "esp", "es"}},
	{code: "latino", tokens: []string{"latino", "latin", "es la", "esla"}},
	{code: "ko", tokens: []string{"korean", "kor", "ko"}},
	{code: "zh", tokens: []string{"chinese", "mandarin", "cantonese", "chi", "zho"}},
	{code: "tw", tokens: []string{"taiwanese", "taiwan"}},
	{code: "fr", tokens: []string{"french", "francais", "français", "truefrench", "vostfr", "vfq", "vff", "vf", "fra", "fre", "fr"}},
	{code: "de", tokens: []string{"german", "deutsch", "ger", "deu"}},
	{code: "nl", tokens: []string{"dutch", "nederlands"}},
	{code: "hi", tokens: []string{"hindi", "hin", "hi"}},
	{code: "te", tokens: []string{"telugu", "tel"}},
	{code: "ta", tokens: []string{"tamil", "tam"}},
	{code: "pl", tokens: []string{"polish", "pol"}},
	{code: "lt", tokens: []string{"lithuanian", "lit"}},
	{code: "lv", tokens: []string{"latvian", "lav"}},
	{code: "et", tokens: []string{"estonian"}},
	{code: "cs", tokens: []string{"czech", "cze", "ces"}},
	{code: "sk", tokens: []string{"slovakian", "slovak", "slk"}},
	{code: "sl", tokens: []string{"slovenian", "slovene", "slv"}},
	{code: "hu", tokens: []string{"hungarian", "hun"}},
	{code: "ro", tokens: []string{"romanian", "rum", "ron"}},
	{code: "bg", tokens: []string{"bulgarian", "bul"}},
	{code: "sr", tokens: []string{"serbian", "srp"}},
	{code: "hr", tokens: []string{"croatian", "hrv"}},
	{code: "uk", tokens: []string{"ukrainian", "ukr"}},
	{code: "el", tokens: []string{"greek", "gre", "ell"}},
	{code: "da", tokens: []string{"danish", "dan"}},
	{code: "fi", tokens: []string{"finnish", "fin"}},
	{code: "sv", tokens: []string{"swedish", "swe"}},
	{code: "no", tokens: []string{"norwegian", "nor"}},
	{code: "tr", tokens: []string{"turkish", "tur"}},
	{code: "ar", tokens: []string{"arabic", "ara"}},
	{code: "fa", tokens: []string{"persian", "farsi", "fas"}},
	{code: "he", tokens: []string{"hebrew", "heb"}},
	{code: "vi", tokens: []string{"vietnamese", "vie"}},
	{code: "id", tokens: []string{"indonesian", "ind"}},
	{code: "ms", tokens: []string{"malay"}},
	{code: "th", tokens: []string{"thai", "tha"}},
	{code: "nordic", tokens: []string{"nordic", "scandinavian"}},
}

// Torrentio uses country flags for audio languages.
var languageFlagByCode = map[string]string{
	"en":     "🇬🇧",
	"ja":     "🇯🇵",
	"ru":     "🇷🇺",
	"it":     "🇮🇹",
	"pt":     "🇵🇹",
	"es":     "🇪🇸",
	"latino": "🇲🇽",
	"ko":     "🇰🇷",
	"zh":     "🇨🇳",
	"tw":     "🇹🇼",
	"fr":     "🇫🇷",
	"de":     "🇩🇪",
	"nl":     "🇳🇱",
	"hi":     "🇮🇳",
	"te":     "🇮🇳",
	"ta":     "🇮🇳",
	"pl":     "🇵🇱",
	"lt":     "🇱🇹",
	"lv":     "🇱🇻",
	"et":     "🇪🇪",
	"cs":     "🇨🇿",
	"sk":     "🇸🇰",
	"sl":     "🇸🇮",
	"hu":     "🇭🇺",
	"ro":     "🇷🇴",
	"bg":     "🇧🇬",
	"sr":     "🇷🇸",
	"hr":     "🇭🇷",
	"uk":     "🇺🇦",
	"el":     "🇬🇷",
	"da":     "🇩🇰",
	"fi":     "🇫🇮",
	"sv":     "🇸🇪",
	"no":     "🇳🇴",
	"tr":     "🇹🇷",
	"ar":     "🇸🇦",
	"fa":     "🇮🇷",
	"he":     "🇮🇱",
	"vi":     "🇻🇳",
	"id":     "🇮🇩",
	"ms":     "🇲🇾",
	"th":     "🇹🇭",
	"nordic": "🇸🇪",
}

var flagEmojiToCode = map[string]string{
	"🇬🇧": "en", "🇺🇸": "en", "🇦🇺": "en", "🇨🇦": "en",
	"🇯🇵": "ja",
	"🇷🇺": "ru",
	"🇮🇹": "it",
	"🇵🇹": "pt", "🇧🇷": "pt",
	"🇪🇸": "es",
	"🇲🇽": "latino", "🇦🇷": "latino",
	"🇰🇷": "ko",
	"🇨🇳": "zh",
	"🇹🇼": "tw",
	"🇫🇷": "fr",
	"🇩🇪": "de",
	"🇳🇱": "nl",
	"🇮🇳": "hi",
	"🇵🇱": "pl",
	"🇱🇹": "lt",
	"🇱🇻": "lv",
	"🇪🇪": "et",
	"🇨🇿": "cs",
	"🇸🇰": "sk",
	"🇸🇮": "sl",
	"🇭🇺": "hu",
	"🇷🇴": "ro",
	"🇧🇬": "bg",
	"🇷🇸": "sr",
	"🇭🇷": "hr",
	"🇺🇦": "uk",
	"🇬🇷": "el",
	"🇩🇰": "da",
	"🇫🇮": "fi",
	"🇸🇪": "sv",
	"🇳🇴": "no",
	"🇹🇷": "tr",
	"🇸🇦": "ar",
	"🇮🇷": "fa",
	"🇮🇱": "he",
	"🇻🇳": "vi",
	"🇮🇩": "id",
	"🇲🇾": "ms",
	"🇹🇭": "th",
}

// DetectLanguages returns Torrentio-style audio language codes from a release title.
func DetectLanguages(title string) []string {
	var out []string
	out = append(out, languagesFromFlagEmojis(title)...)
	padded := " " + normalizeReleaseTokens(title) + " "
	for _, hint := range languageHints {
		for _, tok := range hint.tokens {
			needle := " " + tok + " "
			if strings.Contains(padded, needle) {
				out = append(out, hint.code)
				break
			}
		}
	}
	return uniqueStrings(out)
}

// LanguageFlags returns Torrentio flag emojis for detected audio languages.
func LanguageFlags(codes []string) []string {
	var out []string
	seen := map[string]bool{}
	for _, code := range codes {
		flag := languageFlagByCode[strings.ToLower(strings.TrimSpace(code))]
		if flag == "" || seen[flag] {
			continue
		}
		seen[flag] = true
		out = append(out, flag)
	}
	return out
}

func languagesFromFlagEmojis(title string) []string {
	runes := []rune(title)
	var out []string
	for i := 0; i+1 < len(runes); i++ {
		if !isRegionalIndicator(runes[i]) || !isRegionalIndicator(runes[i+1]) {
			continue
		}
		flag := string([]rune{runes[i], runes[i+1]})
		if code := flagEmojiToCode[flag]; code != "" {
			out = append(out, code)
		}
		i++
	}
	return out
}

func isRegionalIndicator(r rune) bool {
	return r >= 0x1F1E6 && r <= 0x1F1FF
}

func normalizeReleaseTokens(title string) string {
	var b strings.Builder
	b.Grow(len(title) + 2)
	prevSpace := true
	for _, r := range strings.ToLower(title) {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(r)
			prevSpace = false
			continue
		}
		if !prevSpace {
			b.WriteByte(' ')
			prevSpace = true
		}
	}
	return strings.TrimSpace(b.String())
}
