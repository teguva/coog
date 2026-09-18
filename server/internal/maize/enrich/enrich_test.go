package enrich

import (
	"testing"

	"coog/internal/maize"
)

func TestNormalizeIAFDTitleURL(t *testing.T) {
	got := NormalizeIAFDTitleURL("https://www.iafd.com/title.rme/id=e1940e3e-a3aa-4f56-8d41-a846cf7d53e5")
	if got == "" {
		t.Fatal("expected canonical URL")
	}
	if NormalizeIAFDTitleURL("https://example.com/foo") != "" {
		t.Fatal("expected reject")
	}
}

func TestParseTitlePage(t *testing.T) {
	html := `
	<html><body>
	<h1>Church of Desires (2021)</h1>
	<p class="bioheading">Studio</p><p class="biodata">Deeper</p>
	<p class="bioheading">Director</p><p class="biodata">Kayden Kross</p>
	<p class="bioheading">Minutes</p><p class="biodata">42</p>
	<div class="panel">
	  <div class="panel-heading"><h3>Performers</h3></div>
	  <a href="/person.rme/perfid=1">Riley Reid</a>
	  <a href="/person.rme/perfid=2">Lana Rhoades</a>
	</div>
	<div class="panel">
	  <div class="panel-heading"><h3>Synopsis</h3></div>
	  <div class="padded-panel">A plot about desires.</div>
	</div>
	<div class="panel">
	  <div class="panel-heading"><h3>Categories</h3></div>
	  <div class="padded-panel">Lesbian, Softcore</div>
	</div>
	</body></html>`
	data := ParseTitlePage(html, "https://www.iafd.com/title.rme/id=abc")
	if data.Title != "Church of Desires" || data.Year != 2021 {
		t.Fatalf("title/year: %+v", data)
	}
	if data.Studio != "Deeper" || data.Director != "Kayden Kross" || data.Duration != "42 min" {
		t.Fatalf("studio/director/duration: %+v", data)
	}
	if len(data.Performers) != 2 || data.Description == "" || len(data.Tags) != 2 {
		t.Fatalf("cast/synopsis/tags: %+v", data)
	}
}

func TestMergeIAFDIntoScene(t *testing.T) {
	cur := maize.SceneMeta{Title: "Old", Locked: false}
	merged := MergeIAFDIntoScene(cur, TitleData{
		Link: "https://www.iafd.com/title.rme/id=x",
		Title: "New",
		Year: 2020,
		Performers: []string{"A"},
	}, "folder-name")
	if merged.Title != "New" || merged.Year != 2020 || merged.Links["iafd"] == "" {
		t.Fatalf("merge failed: %+v", merged)
	}
	if len(merged.Aliases) == 0 || merged.Aliases[0] != "folder-name" {
		t.Fatalf("aliases: %+v", merged.Aliases)
	}
}

func TestNormalizePornhubProfileURL(t *testing.T) {
	got := NormalizePornhubProfileURL("www.pornhub.com/model/riley-reid/")
	if got != "https://www.pornhub.com/model/riley-reid" {
		t.Fatalf("got %q", got)
	}
	if NormalizePornhubProfileURL("https://example.com/x") != "" {
		t.Fatal("expected reject")
	}
}

func TestParsePornhubHeadshot(t *testing.T) {
	html := `<img id="getAvatar" src="https://di.phncdn.com/pics/users/avatar/riley.jpg" />`
	if parsePornhubHeadshot(html) == "" {
		t.Fatal("expected avatar")
	}
}

func TestParseBabehubHeadshot(t *testing.T) {
	html := `<img src="https://cdn.babehub.com/models_ret/foo_w400.jpg" />`
	got := parseBabehubHeadshot(html)
	if got == "" || got == "https://cdn.babehub.com/models_ret/foo_w400.jpg" {
		t.Fatalf("expected upscaled headshot, got %q", got)
	}
}
