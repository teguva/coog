package enrich

import (
	"net/url"
	"regexp"
	"strings"
)

const pornhubBase = "https://www.pornhub.com"

var (
	pornhubProfileRE = regexp.MustCompile(`(?i)^https?://(?:www\.)?pornhub\.com/(?P<kind>model|pornstar)/(?P<slug>[A-Za-z0-9][A-Za-z0-9_-]*)/?`)
	pornhubAvatarRE  = regexp.MustCompile(`(?is)id=["']getAvatar["'][^>]*\bsrc=["']([^"']+)["']|\bsrc=["']([^"']+)["'][^>]*\bid=["']getAvatar["']`)
	pornhubCDNRE     = regexp.MustCompile(`(?i)https?://[^"'\s]+phncdn\.com/[^"'\s]*/avatar[^"'\s]+\.(?:jpe?g|png|webp)`)
)

// NormalizePornhubProfileURL returns a canonical /model/… or /pornstar/… URL.
func NormalizePornhubProfileURL(raw string) string {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return ""
	}
	low := strings.ToLower(raw)
	if !strings.HasPrefix(low, "http://") && !strings.HasPrefix(low, "https://") {
		raw = "https://" + strings.TrimLeft(raw, "/")
	}
	raw = strings.Split(strings.Split(raw, "#")[0], "?")[0]
	m := pornhubProfileRE.FindStringSubmatch(raw)
	if m == nil {
		return ""
	}
	kind := strings.ToLower(m[1])
	slug := strings.Trim(m[2], "-_")
	if slug == "" {
		return ""
	}
	if u, err := url.PathUnescape(slug); err == nil {
		slug = u
	}
	return pornhubBase + "/" + kind + "/" + slug
}

func parsePornhubHeadshot(html string) string {
	if html == "" {
		return ""
	}
	doc := docFromHTML(html)
	if doc != nil {
		node := doc.Find("img#getAvatar").First()
		if node.Length() > 0 {
			src, _ := node.Attr("src")
			if src == "" {
				src, _ = node.Attr("data-src")
			}
			if strings.TrimSpace(src) != "" {
				return strings.TrimSpace(src)
			}
		}
	}
	if m := pornhubAvatarRE.FindStringSubmatch(html); len(m) > 0 {
		src := strings.TrimSpace(m[1])
		if src == "" {
			src = strings.TrimSpace(m[2])
		}
		if src != "" {
			return src
		}
	}
	if m := pornhubCDNRE.FindString(html); m != "" {
		return m
	}
	return ""
}

func (c *Client) FetchPornhubHeadshotURL(profileURL string) (string, error) {
	canon := NormalizePornhubProfileURL(profileURL)
	if canon == "" {
		return "", nil
	}
	html, err := c.GetText(canon)
	if err != nil {
		return "", err
	}
	if shot := parsePornhubHeadshot(html); shot != "" {
		return shot, nil
	}
	u, err := url.Parse(canon)
	if err != nil {
		return "", nil
	}
	parts := strings.Split(strings.Trim(u.Path, "/"), "/")
	if len(parts) < 2 {
		return "", nil
	}
	var alt string
	switch strings.ToLower(parts[0]) {
	case "model":
		alt = pornhubBase + "/pornstar/" + parts[1]
	case "pornstar":
		alt = pornhubBase + "/model/" + parts[1]
	default:
		return "", nil
	}
	html2, err := c.GetText(alt)
	if err != nil {
		return "", err
	}
	return parsePornhubHeadshot(html2), nil
}
