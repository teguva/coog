package enrich

import (
	"net/url"
	"regexp"
	"strings"

	"github.com/PuerkitoBio/goquery"
)

var (
	iafdPersonRE = regexp.MustCompile(`(?i)/person\.rme/`)
	iafdBRRE     = regexp.MustCompile(`(?i)<br\s*/?>`)
	iafdTagRE    = regexp.MustCompile(`<[^>]+>`)
)

var iafdPersonLabelMap = map[string]string{
	"aka":                         "_aka",
	"birthday":                    "birthday",
	"birthplace":                  "birthplace",
	"ethnicity":                   "ethnicity",
	"height":                      "height",
	"measurements":                "measurements",
	"years active as performer":   "years_active",
	"years active":                "years_active",
}

// PersonData is scraped IAFD performer metadata.
type PersonData struct {
	Link         string
	Name         string
	Aliases      []string
	Birthday     string
	Birthplace   string
	Ethnicity    string
	Height       string
	Measurements string
	YearsActive  string
	HeadshotURL  string
}

func iafdSearchURL(name string) string {
	return iafdBase + "/results.asp?searchtype=comprehensive&searchstring=" + url.QueryEscape(name)
}

func pickPersonLink(links []string, name string) string {
	if len(links) == 0 {
		return ""
	}
	want := slugify(name)
	if want != "" {
		compact := strings.ReplaceAll(want, "-", "")
		for _, u := range links {
			path := strings.ToLower(strings.ReplaceAll(u, "-", ""))
			if compact != "" && strings.Contains(path, compact) {
				return u
			}
		}
	}
	return links[0]
}

func (c *Client) findPersonURL(name string) (string, error) {
	html, err := c.GetText(iafdSearchURL(name))
	if err != nil {
		return "", err
	}
	if html == "" {
		return "", nil
	}
	links := hrefsMatching(html, iafdBase, `/person\.rme/`)
	if len(links) == 0 {
		re := regexp.MustCompile(`(?i)href=["']([^"']*person\.rme[^"']*)["']`)
		for _, m := range re.FindAllStringSubmatch(html, -1) {
			full := absURL(iafdBase, m[1])
			if full != "" {
				links = append(links, full)
			}
		}
	}
	return pickPersonLink(links, name), nil
}

func nodeTextWithBR(s *goquery.Selection) string {
	html, err := s.Html()
	if err != nil || html == "" {
		return collapseSpace(s.Text())
	}
	raw := iafdBRRE.ReplaceAllString(html, "\n")
	text := iafdTagRE.ReplaceAllString(raw, "")
	text = strings.ReplaceAll(text, "\u00a0", " ")
	text = strings.ReplaceAll(text, "&nbsp;", " ")
	lines := strings.Split(text, "\n")
	for i, line := range lines {
		lines[i] = strings.TrimSpace(line)
	}
	return strings.TrimSpace(strings.Join(lines, "\n"))
}

func applyPersonLabel(out *PersonData, label, value string) {
	value = cleanSkipValue(value)
	if value == "" {
		return
	}
	key := iafdPersonLabelMap[label]
	switch key {
	case "":
		return
	case "_aka":
		parts := regexp.MustCompile(`[\n,/]+`).Split(value, -1)
		var aliases []string
		for _, a := range parts {
			a = strings.TrimSpace(a)
			if a != "" {
				aliases = append(aliases, a)
			}
		}
		if len(aliases) > 0 {
			out.Aliases = aliases
		}
	case "birthday":
		out.Birthday = value
	case "birthplace":
		out.Birthplace = value
	case "ethnicity":
		out.Ethnicity = value
	case "height":
		out.Height = value
	case "measurements":
		out.Measurements = value
	case "years_active":
		out.YearsActive = value
	}
}

func parsePersonHTML(html string) PersonData {
	out := PersonData{}
	doc := docFromHTML(html)
	if doc != nil {
		doc.Find(".bioheading").Each(func(_ int, heading *goquery.Selection) {
			label := strings.ToLower(collapseSpace(heading.Text()))
			biodata := heading.Next()
			for biodata.Length() > 0 {
				cls, _ := biodata.Attr("class")
				for _, part := range strings.Fields(cls) {
					if part == "biodata" {
						applyPersonLabel(&out, label, nodeTextWithBR(biodata))
						return
					}
				}
				biodata = biodata.Next()
			}
		})
		shot := doc.Find("#headshot img").First()
		if shot.Length() == 0 {
			shot = doc.Find("img#headshot").First()
		}
		if shot.Length() > 0 {
			src, _ := shot.Attr("src")
			if src == "" {
				src, _ = shot.Attr("data-src")
			}
			if src != "" {
				out.HeadshotURL = absURL(iafdBase, src)
			}
		}
		if title := doc.Find("h1").First(); title.Length() > 0 {
			if t := collapseSpace(title.Text()); t != "" {
				out.Name = t
			}
		}
		return out
	}
	re := regexp.MustCompile(`(?is)<p[^>]*class="bioheading"[^>]*>\s*([^<]+?)\s*</p>\s*(?:<p[^>]*class="biodata"[^>]*>(.*?)</p>|<div[^>]*class="biodata"[^>]*>(.*?)</div>)`)
	for _, m := range re.FindAllStringSubmatch(html, -1) {
		label := strings.ToLower(collapseSpace(m[1]))
		raw := m[2]
		if raw == "" {
			raw = m[3]
		}
		raw = iafdBRRE.ReplaceAllString(raw, "\n")
		value := strings.TrimSpace(iafdTagRE.ReplaceAllString(raw, ""))
		applyPersonLabel(&out, label, value)
	}
	if m := regexp.MustCompile(`(?i)id=["']headshot["'][^>]*>\s*<img[^>]+src=["']([^"']+)`).FindStringSubmatch(html); len(m) > 1 {
		out.HeadshotURL = absURL(iafdBase, m[1])
	}
	return out
}

// FetchPerson scrapes IAFD bio/stats for a performer name (or known person URL).
func (c *Client) FetchPerson(name, personURL string) (PersonData, error) {
	url := strings.TrimSpace(personURL)
	if url != "" && !iafdPersonRE.MatchString(url) {
		url = ""
	}
	if url == "" {
		found, err := c.findPersonURL(name)
		if err != nil {
			return PersonData{}, err
		}
		url = found
	}
	if url == "" {
		return PersonData{}, nil
	}
	html, err := c.GetText(url)
	if err != nil {
		return PersonData{}, err
	}
	if html == "" {
		return PersonData{}, nil
	}
	data := parsePersonHTML(html)
	if data.Name == "" && data.Birthday == "" && data.HeadshotURL == "" && len(data.Aliases) == 0 {
		return PersonData{}, nil
	}
	data.Link = url
	nslug := slugify(data.Name)
	if nslug == "" {
		nslug = slugify(name)
	}
	filtered := make([]string, 0, len(data.Aliases))
	for _, a := range data.Aliases {
		as := slugify(a)
		if as != "" && as != nslug {
			filtered = append(filtered, a)
		}
	}
	data.Aliases = filtered
	return data, nil
}
