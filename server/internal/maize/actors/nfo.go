package actors

import (
	"encoding/xml"
	"os"
	"strings"
)

type personNFO struct {
	XMLName     xml.Name `xml:"person"`
	Name        string   `xml:"name"`
	Title       string   `xml:"title"`
	Biography   string   `xml:"biography"`
	Plot        string   `xml:"plot"`
	Overview    string   `xml:"overview"`
	Birthdate   string   `xml:"birthdate"`
	Birthday    string   `xml:"birthday"`
	Born        string   `xml:"born"`
	Birthplace  string   `xml:"birthplace"`
	PlaceOfBirth string  `xml:"placeofbirth"`
	Aliases     []string `xml:"alias"`
}

// Also accept <actor> root used by some Jellyfin exports.
type actorNFO struct {
	XMLName      xml.Name `xml:"actor"`
	Name         string   `xml:"name"`
	Title        string   `xml:"title"`
	Biography    string   `xml:"biography"`
	Plot         string   `xml:"plot"`
	Overview     string   `xml:"overview"`
	Birthdate    string   `xml:"birthdate"`
	Birthday     string   `xml:"birthday"`
	Born         string   `xml:"born"`
	Birthplace   string   `xml:"birthplace"`
	PlaceOfBirth string   `xml:"placeofbirth"`
	Aliases      []string `xml:"alias"`
}

func parsePersonNFO(path string) *Meta {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil
	}
	var p personNFO
	if err := xml.Unmarshal(b, &p); err == nil && (p.Name != "" || p.Title != "" || p.Biography != "") {
		return nfoToMeta(p.Name, p.Title, p.Biography, p.Plot, p.Overview, p.Birthdate, p.Birthday, p.Born, p.Birthplace, p.PlaceOfBirth, p.Aliases)
	}
	var a actorNFO
	if err := xml.Unmarshal(b, &a); err == nil {
		return nfoToMeta(a.Name, a.Title, a.Biography, a.Plot, a.Overview, a.Birthdate, a.Birthday, a.Born, a.Birthplace, a.PlaceOfBirth, a.Aliases)
	}
	return nil
}

func nfoToMeta(name, title, bio, plot, overview, birthdate, birthday, born, birthplace, placeOfBirth string, aliases []string) *Meta {
	m := &Meta{Links: map[string]string{}}
	if strings.TrimSpace(name) != "" {
		m.Name = strings.TrimSpace(name)
	} else if strings.TrimSpace(title) != "" {
		m.Name = strings.TrimSpace(title)
	}
	for _, cand := range []string{bio, plot, overview} {
		if strings.TrimSpace(cand) != "" {
			m.Bio = strings.TrimSpace(cand)
			break
		}
	}
	for _, cand := range []string{birthday, birthdate, born} {
		if strings.TrimSpace(cand) != "" {
			m.Birthday = strings.TrimSpace(cand)
			break
		}
	}
	for _, cand := range []string{birthplace, placeOfBirth} {
		if strings.TrimSpace(cand) != "" {
			m.Birthplace = strings.TrimSpace(cand)
			break
		}
	}
	clean := make([]string, 0, len(aliases))
	for _, a := range aliases {
		if s := strings.TrimSpace(a); s != "" {
			clean = append(clean, s)
		}
	}
	m.Aliases = clean
	if m.Name == "" && m.Bio == "" && m.Birthday == "" && len(m.Aliases) == 0 {
		return nil
	}
	return m
}
