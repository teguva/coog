package enrich

import (
	"net/url"
	"regexp"
	"strings"

	"github.com/PuerkitoBio/goquery"
)

const babehubBase = "https://www.babehub.com"

var babehubSizeSuffixRE = regexp.MustCompile(`(?i)(_w400|_400|_masonry_400)\.(jpe?g|png|webp)`)

func babehubUpscale(u string) string {
	return babehubSizeSuffixRE.ReplaceAllString(u, ".$2")
}

func babehubIsContentPhoto(u string) bool {
	if !looksLikePhoto(u) {
		return false
	}
	low := strings.ToLower(u)
	return strings.Contains(low, "cdn.babehub.com/content/") && !strings.Contains(low, "models_ret")
}

func parseBabehubHeadshot(html string) string {
	doc := docFromHTML(html)
	if doc != nil {
		var found string
		doc.Find("img").EachWithBreak(func(_ int, img *goquery.Selection) bool {
			src, _ := img.Attr("src")
			if src == "" {
				src, _ = img.Attr("data-src")
			}
			if strings.Contains(src, "models_ret") && looksLikePhoto(src) {
				found = babehubUpscale(src)
				return false
			}
			return true
		})
		if found != "" {
			return found
		}
	}
	m := regexp.MustCompile(`(?i)(?:src|data-src)=["']([^"']*models_ret[^"']+)["']`).FindStringSubmatch(html)
	if len(m) > 1 {
		return babehubUpscale(m[1])
	}
	return ""
}

func parseBabehubSetPages(html string, tokens []string) []string {
	doc := docFromHTML(html)
	seen := map[string]bool{}
	var out []string
	if doc != nil {
		doc.Find("a").Each(func(_ int, a *goquery.Selection) {
			img := a.Find("img").First()
			if img.Length() == 0 {
				return
			}
			src, _ := img.Attr("src")
			if src == "" {
				src, _ = img.Attr("data-src")
			}
			low := strings.ToLower(src)
			if !strings.Contains(low, "cdn.babehub.com/content/") || strings.Contains(low, "models_ret") {
				return
			}
			href, _ := a.Attr("href")
			full := absURL(babehubBase, href)
			if full == "" || seen[full] || !urlMatchesName(full, tokens) {
				return
			}
			seen[full] = true
			out = append(out, full)
		})
	}
	if len(out) > 0 {
		return out
	}
	re := regexp.MustCompile(`(?is)href=["']([^"']+)["'][^>]*>.*?(?:src|data-src)=["']([^"']*cdn\.babehub\.com/content/[^"']+)["']`)
	for _, m := range re.FindAllStringSubmatch(html, -1) {
		if strings.Contains(m[2], "models_ret") {
			continue
		}
		full := absURL(babehubBase, m[1])
		if full != "" && !seen[full] && urlMatchesName(full, tokens) {
			seen[full] = true
			out = append(out, full)
		}
	}
	return out
}

func parseBabehubSetImages(html string) []string {
	seen := map[string]bool{}
	var out []string
	for _, raw := range imgSrcs(html, babehubBase) {
		if !babehubIsContentPhoto(raw) {
			continue
		}
		u := babehubUpscale(raw)
		if seen[u] {
			continue
		}
		seen[u] = true
		out = append(out, u)
	}
	return out
}

func (c *Client) resolveBabehubModel(name string) (pageURL, html string, err error) {
	slug := slugify(name)
	if slug == "" {
		return "", "", nil
	}
	tokens := nameTokens(name)
	modelURL := babehubBase + "/model/" + slug + "/"
	html, err = c.GetText(modelURL)
	if err != nil {
		return "", "", err
	}
	if html != "" && parseBabehubHeadshot(html) != "" {
		return modelURL, html, nil
	}
	searchHTML, err := c.GetText(babehubBase + "/search/model/" + url.QueryEscape(name) + "/")
	if err != nil {
		return "", "", err
	}
	if searchHTML != "" {
		n := 0
		for _, starURL := range hrefsMatching(searchHTML, babehubBase, `/model/`) {
			if !urlMatchesName(starURL, tokens) {
				continue
			}
			page, err := c.GetText(starURL)
			if err != nil {
				return "", "", err
			}
			if page != "" {
				return starURL, page, nil
			}
			n++
			if n >= 3 {
				break
			}
		}
	}
	if html != "" {
		return modelURL, html, nil
	}
	return "", "", nil
}

func (c *Client) FetchBabehubHeadshotURL(name string) (string, error) {
	_, html, err := c.resolveBabehubModel(name)
	if err != nil || html == "" {
		return "", err
	}
	return parseBabehubHeadshot(html), nil
}

func (c *Client) FetchBabehubModelLink(name string) (string, error) {
	u, _, err := c.resolveBabehubModel(name)
	return u, err
}

func (c *Client) FetchBabehubImageURLs(name string, limit int) ([]string, error) {
	_, html, err := c.resolveBabehubModel(name)
	if err != nil || html == "" {
		return nil, err
	}
	tokens := nameTokens(name)
	setURLs := parseBabehubSetPages(html, tokens)
	seen := map[string]bool{}
	var collected []string
	for _, setURL := range setURLs {
		page, err := c.GetText(setURL)
		if err != nil {
			return collected, err
		}
		if page == "" {
			continue
		}
		for _, u := range parseBabehubSetImages(page) {
			if seen[u] {
				continue
			}
			seen[u] = true
			collected = append(collected, u)
			if limit > 0 && len(collected) >= limit {
				return collected[:limit], nil
			}
		}
	}
	if limit > 0 && len(collected) > limit {
		return collected[:limit], nil
	}
	return collected, nil
}
