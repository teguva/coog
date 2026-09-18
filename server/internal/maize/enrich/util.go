package enrich

import (
	"net/url"
	"regexp"
	"strings"

	"coog/internal/maize/actors"
	"github.com/PuerkitoBio/goquery"
)

var (
	imgExtRE   = regexp.MustCompile(`(?i)\.(jpe?g|png|webp)(?:\?|$)`)
	skipImgRE  = regexp.MustCompile(`(?i)(logo|sprite|icon|favicon|banner|\.svg|placeholder|blank|pixel)`)
	nonAlnumRE = regexp.MustCompile(`[^a-z0-9]+`)
)

func nameTokens(name string) []string {
	parts := nonAlnumRE.Split(strings.ToLower(name), -1)
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if len(p) >= 2 {
			out = append(out, p)
		}
	}
	return out
}

func urlMatchesName(u string, tokens []string) bool {
	if len(tokens) == 0 {
		return false
	}
	low := strings.ToLower(u)
	for _, t := range tokens {
		if !strings.Contains(low, t) {
			return false
		}
	}
	return true
}

func looksLikePhoto(u string) bool {
	return imgExtRE.MatchString(u) && !skipImgRE.MatchString(u)
}

func absURL(base, href string) string {
	href = strings.TrimSpace(href)
	if href == "" {
		return ""
	}
	ref, err := url.Parse(href)
	if err != nil {
		return ""
	}
	if ref.IsAbs() {
		return ref.String()
	}
	b, err := url.Parse(base)
	if err != nil {
		return ""
	}
	return b.ResolveReference(ref).String()
}

func docFromHTML(html string) *goquery.Document {
	doc, err := goquery.NewDocumentFromReader(strings.NewReader(html))
	if err != nil {
		return nil
	}
	return doc
}

func hrefsMatching(html, base, pattern string) []string {
	re := regexp.MustCompile("(?i)" + pattern)
	doc := docFromHTML(html)
	seen := map[string]bool{}
	var out []string
	add := func(href string) {
		full := absURL(base, href)
		if full == "" || seen[full] || !re.MatchString(full) {
			return
		}
		seen[full] = true
		out = append(out, full)
	}
	if doc != nil {
		doc.Find("a[href]").Each(func(_ int, s *goquery.Selection) {
			href, _ := s.Attr("href")
			add(href)
		})
	}
	if len(out) == 0 {
		for _, m := range regexp.MustCompile(`(?i)href=["']([^"']*` + pattern + `[^"']*)["']`).FindAllStringSubmatch(html, -1) {
			add(m[1])
		}
	}
	return out
}

func imgSrcs(html, base string) []string {
	doc := docFromHTML(html)
	seen := map[string]bool{}
	var out []string
	add := func(src string) {
		full := absURL(base, src)
		if full == "" || seen[full] {
			return
		}
		seen[full] = true
		out = append(out, full)
	}
	if doc != nil {
		doc.Find("img").Each(func(_ int, s *goquery.Selection) {
			if src, ok := s.Attr("src"); ok {
				add(src)
			}
			if src, ok := s.Attr("data-src"); ok {
				add(src)
			}
		})
	}
	return out
}

func slugify(name string) string {
	return actors.Slugify(name)
}

func cleanSkipValue(v string) string {
	v = strings.TrimSpace(v)
	switch strings.ToLower(v) {
	case "", "no data", "unknown", "none":
		return ""
	default:
		return v
	}
}

func collapseSpace(s string) string {
	return strings.Join(strings.Fields(strings.ReplaceAll(s, "\u00a0", " ")), " ")
}
