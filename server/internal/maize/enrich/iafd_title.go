package enrich

import (
	"fmt"
	"net/url"
	"regexp"
	"strconv"
	"strings"

	"github.com/PuerkitoBio/goquery"
)

const iafdBase = "https://www.iafd.com"

var (
	iafdTitleURLRE = regexp.MustCompile(`(?i)^https?://(?:www\.)?iafd\.com/title\.rme/(?:id=[0-9a-f-]{36}|.+)$`)
	iafdYearParen  = regexp.MustCompile(`^(.*)\((\d{4})\)\s*$`)
	iafdYearWord   = regexp.MustCompile(`\b((?:19|20)\d{2})\b`)
	iafdDigits     = regexp.MustCompile(`\d+`)
)

var iafdTitleLabelMap = map[string]string{
	"studio":       "studio",
	"distributor":  "studio",
	"director":     "director",
	"minutes":      "duration",
	"release date": "_release_date",
}

// TitleData is scraped IAFD title-page metadata.
type TitleData struct {
	Link        string
	Title       string
	Description string
	Studio      string
	Director    string
	Duration    string
	Year        int
	Performers  []string
	Tags        []string
}

// NormalizeIAFDTitleURL returns a canonical IAFD title URL, or "" if invalid.
func NormalizeIAFDTitleURL(raw string) string {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return ""
	}
	if !strings.HasPrefix(strings.ToLower(raw), "http") {
		raw = iafdBase + "/" + strings.TrimLeft(raw, "/")
	}
	u, err := url.Parse(raw)
	if err != nil {
		return ""
	}
	host := strings.ToLower(strings.TrimPrefix(u.Host, "www."))
	if host != "iafd.com" {
		return ""
	}
	if !strings.Contains(strings.ToLower(u.Path), "/title.rme/") {
		return ""
	}
	out := "https://www.iafd.com" + u.Path
	if u.RawQuery != "" {
		out += "?" + u.RawQuery
	}
	if iafdTitleURLRE.MatchString(out) {
		return out
	}
	low := strings.ToLower(out)
	if strings.Contains(low, "id=") || strings.Contains(low, "title=") {
		return out
	}
	return ""
}

func parseH1Title(titleText string) (string, int) {
	text := collapseSpace(titleText)
	if m := iafdYearParen.FindStringSubmatch(text); len(m) == 3 {
		y, _ := strconv.Atoi(m[2])
		return strings.TrimSpace(m[1]), y
	}
	return text, 0
}

func yearFromRelease(value string) int {
	if m := iafdYearWord.FindStringSubmatch(value); len(m) > 1 {
		y, _ := strconv.Atoi(m[1])
		return y
	}
	return 0
}

func panelByHeading(doc *goquery.Document, heading string) string {
	if doc == nil {
		return ""
	}
	want := strings.ToLower(heading)
	var out string
	doc.Find(".panel").EachWithBreak(func(_ int, panel *goquery.Selection) bool {
		head := panel.Find(".panel-heading h3, .panel-heading h4").First()
		if head.Length() == 0 {
			return true
		}
		if strings.ToLower(collapseSpace(head.Text())) != want {
			return true
		}
		body := panel.Find(".padded-panel").First()
		if body.Length() == 0 {
			body = panel
		}
		out = collapseSpace(body.Text())
		return false
	})
	return out
}

func performersFromTitleHTML(doc *goquery.Document) []string {
	if doc == nil {
		return nil
	}
	seen := map[string]bool{}
	var out []string
	doc.Find(".panel").Each(func(_ int, panel *goquery.Selection) {
		head := panel.Find(".panel-heading h3, .panel-heading h4").First()
		if head.Length() == 0 {
			return
		}
		ht := strings.ToLower(collapseSpace(head.Text()))
		if ht != "performers" && ht != "cast" {
			return
		}
		panel.Find("a").Each(func(_ int, a *goquery.Selection) {
			href, _ := a.Attr("href")
			if !strings.Contains(strings.ToLower(href), "person.rme") {
				return
			}
			name := collapseSpace(a.Text())
			if name == "" || seen[name] {
				return
			}
			seen[name] = true
			out = append(out, name)
		})
	})
	return out
}

// ParseTitlePage extracts FunPlay scene fields from an IAFD title HTML page.
func ParseTitlePage(html, pageURL string) TitleData {
	out := TitleData{Link: pageURL}
	doc := docFromHTML(html)
	if doc == nil {
		return out
	}
	if h1 := doc.Find("h1").First(); h1.Length() > 0 {
		title, year := parseH1Title(h1.Text())
		if title != "" {
			out.Title = title
		}
		if year > 0 {
			out.Year = year
		}
	}
	headings := doc.Find(".bioheading")
	data := doc.Find(".biodata")
	n := headings.Length()
	if data.Length() < n {
		n = data.Length()
	}
	for i := 0; i < n; i++ {
		label := strings.ToLower(collapseSpace(headings.Eq(i).Text()))
		value := collapseSpace(data.Eq(i).Text())
		key := iafdTitleLabelMap[label]
		if key == "" || value == "" {
			continue
		}
		switch key {
		case "_release_date":
			if yr := yearFromRelease(value); yr > 0 && out.Year == 0 {
				out.Year = yr
			}
		case "duration":
			if mins := iafdDigits.FindString(value); mins != "" {
				out.Duration = mins + " min"
			}
		case "studio":
			if out.Studio == "" {
				out.Studio = value
			}
		case "director":
			out.Director = value
		}
	}
	if performers := performersFromTitleHTML(doc); len(performers) > 0 {
		out.Performers = performers
	}
	if synopsis := panelByHeading(doc, "synopsis"); synopsis != "" {
		out.Description = synopsis
	}
	if categories := panelByHeading(doc, "categories"); categories != "" {
		parts := regexp.MustCompile(`[,/\n]+`).Split(categories, -1)
		var tags []string
		for _, t := range parts {
			t = strings.TrimSpace(t)
			if t != "" {
				tags = append(tags, t)
			}
		}
		if len(tags) > 0 {
			out.Tags = tags
		}
	}
	return out
}

// FetchTitle scrapes an IAFD title URL.
func (c *Client) FetchTitle(iafdURL string) (TitleData, error) {
	canon := NormalizeIAFDTitleURL(iafdURL)
	if canon == "" {
		return TitleData{}, fmt.Errorf("invalid IAFD title URL")
	}
	html, err := c.GetText(canon)
	if err != nil {
		return TitleData{}, err
	}
	if strings.TrimSpace(html) == "" {
		return TitleData{}, fmt.Errorf("IAFD title page unavailable")
	}
	if strings.Contains(strings.ToLower(html), "invalid or outdated page") {
		return TitleData{}, fmt.Errorf("IAFD title page not found")
	}
	data := ParseTitlePage(html, canon)
	if data.Title == "" && len(data.Performers) == 0 {
		return TitleData{}, fmt.Errorf("IAFD page did not contain title metadata")
	}
	return data, nil
}
