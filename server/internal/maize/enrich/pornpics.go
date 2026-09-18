package enrich

import (
	"net/url"
	"regexp"
	"strings"
)

const pornpicsBase = "https://www.pornpics.com"

var (
	pornpicsThumbSizeRE = regexp.MustCompile(`(?i)(cdni\.pornpics\.com/)460/`)
	pornpicsModelURLRE  = regexp.MustCompile(`(?i)https?://cdni\.pornpics\.com/models/[^"'\s>]+\.(?:jpe?g|png|webp)`)
	pornpicsSkipRE      = regexp.MustCompile(`(?i)(logo|sprite|icon|favicon|banner|\.svg|placeholder|blank|pixel|/models/)`)
)

func pornpicsModelSlug(name string) string {
	return strings.ToLower(strings.ReplaceAll(slugify(name), "-", "_"))
}

func pornpicsUpscale(u string) string {
	return pornpicsThumbSizeRE.ReplaceAllString(u, `${1}1280/`)
}

func pornpicsLooksLikePhoto(u string) bool {
	return imgExtRE.MatchString(u) && !pornpicsSkipRE.MatchString(u)
}

func parsePornpicsProfileModelURL(html, name string) string {
	modelSlug := pornpicsModelSlug(name)
	for _, raw := range pornpicsModelURLRE.FindAllString(html, -1) {
		if strings.Contains(strings.ToLower(raw), modelSlug) {
			return raw
		}
	}
	return ""
}

func parsePornpicsGalleryPages(html string, tokens []string) []string {
	seen := map[string]bool{}
	var out []string
	for _, href := range hrefsMatching(html, pornpicsBase, `/galleries/`) {
		if seen[href] {
			continue
		}
		if len(tokens) > 0 && !urlMatchesName(href, tokens) {
			continue
		}
		seen[href] = true
		out = append(out, href)
	}
	return out
}

func isVerifiedPornstarPage(html, name, pageURL string) bool {
	if html == "" {
		return false
	}
	if parsePornpicsProfileModelURL(html, name) != "" {
		return true
	}
	tokens := nameTokens(name)
	slug := slugify(name)
	if len(tokens) == 0 || slug == "" {
		return false
	}
	if pageURL != "" && !strings.Contains(strings.ToLower(pageURL), slug) {
		return false
	}
	matched := 0
	for _, u := range parsePornpicsGalleryPages(html, tokens) {
		if urlMatchesName(u, tokens) {
			matched++
		}
	}
	return matched >= 3
}

func parsePornpicsGalleryImages(html string) []string {
	seen := map[string]bool{}
	var out []string
	for _, raw := range imgSrcs(html, pornpicsBase) {
		if !strings.Contains(strings.ToLower(raw), "cdni.pornpics.com") {
			continue
		}
		if !pornpicsLooksLikePhoto(raw) {
			continue
		}
		u := pornpicsUpscale(raw)
		if seen[u] {
			continue
		}
		seen[u] = true
		out = append(out, u)
	}
	return out
}

func (c *Client) resolvePornpicsPage(name string) (pageURL, html string, err error) {
	slug := slugify(name)
	if slug == "" {
		return "", "", nil
	}
	tokens := nameTokens(name)
	pageURL = pornpicsBase + "/pornstars/" + slug + "/"
	html, err = c.GetText(pageURL)
	if err != nil {
		return "", "", err
	}
	if html != "" && isVerifiedPornstarPage(html, name, pageURL) {
		return pageURL, html, nil
	}
	searchHTML, err := c.GetText(pornpicsBase + "/?q=" + url.QueryEscape(name))
	if err != nil {
		return "", "", err
	}
	if searchHTML != "" {
		n := 0
		for _, starURL := range hrefsMatching(searchHTML, pornpicsBase, `/pornstars/`) {
			if !urlMatchesName(starURL, tokens) {
				continue
			}
			page, err := c.GetText(starURL)
			if err != nil {
				return "", "", err
			}
			if page != "" && isVerifiedPornstarPage(page, name, starURL) {
				return starURL, page, nil
			}
			n++
			if n >= 3 {
				break
			}
		}
	}
	return "", "", nil
}

func (c *Client) FetchPornpicsModelLink(name string) (string, error) {
	u, _, err := c.resolvePornpicsPage(name)
	return u, err
}

func (c *Client) FetchPornpicsImageURLs(name string, limit int) ([]string, error) {
	if limit <= 0 {
		return nil, nil
	}
	_, html, err := c.resolvePornpicsPage(name)
	if err != nil || html == "" {
		return nil, err
	}
	galleryURLs := parsePornpicsGalleryPages(html, nil)
	seen := map[string]bool{}
	var collected []string
	for _, galleryURL := range galleryURLs {
		page, err := c.GetText(galleryURL)
		if err != nil {
			return collected, err
		}
		if page == "" {
			continue
		}
		for _, u := range parsePornpicsGalleryImages(page) {
			if seen[u] {
				continue
			}
			seen[u] = true
			collected = append(collected, u)
			if len(collected) >= limit {
				return collected[:limit], nil
			}
		}
	}
	return collected, nil
}
