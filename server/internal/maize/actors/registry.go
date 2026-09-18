package actors

import (
	"sort"
	"strings"
)

// Summary is one row in the actors list.
type Summary struct {
	Slug         string `json:"slug"`
	Name         string `json:"name"`
	SceneCount   int    `json:"sceneCount"`
	HasHeadshot  bool   `json:"hasHeadshot"`
	GalleryCount int    `json:"galleryCount"`
	Enriched     bool   `json:"enriched"`
	Locked       bool   `json:"locked"`
}

// Similar is a co-star entry.
type Similar struct {
	Slug   string `json:"slug"`
	Name   string `json:"name"`
	Shared int    `json:"shared"`
}

// SceneCredit is a maize scene with performers for registry matching.
type SceneCredit struct {
	ID         string
	Performers []string
	View       map[string]any // maizeView payload returned in profile scenes
}

// AliasMap maps alias slug → canonical slug from stored actor metadata.
func AliasMap(peopleDir string) map[string]string {
	out := map[string]string{}
	for _, actorDir := range IterActorDirs(peopleDir) {
		meta := ReadMeta(actorDir, "")
		canon := meta.Slug
		if canon == "" {
			canon = Slugify(meta.Name)
		}
		if canon == "" {
			continue
		}
		for _, alias := range meta.Aliases {
			as := Slugify(alias)
			if as != "" && as != canon {
				out[as] = canon
			}
		}
	}
	return out
}

// CanonicalSlug resolves a display name through the alias map.
func CanonicalSlug(name string, aliases map[string]string) string {
	slug := Slugify(name)
	if aliases != nil {
		if c, ok := aliases[slug]; ok {
			return c
		}
	}
	return slug
}

// BuildActorList merges scene performer counts with People folders.
func BuildActorList(peopleDir string, performerCounts map[string]int) []Summary {
	aliases := AliasMap(peopleDir)
	records := map[string]*Summary{}

	ensure := func(slug, name string) *Summary {
		rec := records[slug]
		if rec == nil {
			rec = &Summary{Slug: slug, Name: name}
			records[slug] = rec
		}
		return rec
	}

	for name, count := range performerCounts {
		name = strings.TrimSpace(name)
		if name == "" {
			continue
		}
		slug := CanonicalSlug(name, aliases)
		if slug == "" {
			continue
		}
		rec := ensure(slug, name)
		rec.SceneCount += count
		if len(name) > len(rec.Name) {
			rec.Name = name
		}
	}

	for _, actorDir := range IterActorDirs(peopleDir) {
		meta := ReadMeta(actorDir, "")
		slug := meta.Slug
		if slug == "" {
			slug = Slugify(meta.Name)
		}
		if slug == "" {
			continue
		}
		if c, ok := aliases[slug]; ok {
			slug = c
		}
		rec := ensure(slug, meta.Name)
		if meta.Name != "" {
			rec.Name = meta.Name
		}
		rec.HasHeadshot = HeadshotPath(actorDir) != ""
		gallery := GalleryImagePaths(actorDir)
		if len(gallery) > 0 {
			rec.GalleryCount = len(gallery)
		} else {
			rec.GalleryCount = meta.GalleryCount
		}
		rec.Enriched = meta.EnrichedAt > 0
		rec.Locked = meta.Locked
	}

	out := make([]Summary, 0, len(records))
	for _, rec := range records {
		out = append(out, *rec)
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].SceneCount != out[j].SceneCount {
			return out[i].SceneCount > out[j].SceneCount
		}
		return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name)
	})
	return out
}

// ScenesForActor returns scenes whose performers include the actor.
func ScenesForActor(slug string, scenes []SceneCredit, aliases map[string]string) []SceneCredit {
	want := Slugify(slug)
	if aliases != nil {
		if c, ok := aliases[want]; ok {
			want = c
		}
	}
	var matched []SceneCredit
	for _, scene := range scenes {
		for _, performer := range scene.Performers {
			pslug := CanonicalSlug(performer, aliases)
			if pslug == want {
				matched = append(matched, scene)
				break
			}
		}
	}
	return matched
}

// CostarsForActor counts co-performers sharing scenes with the actor.
func CostarsForActor(slug string, scenes []SceneCredit, aliases map[string]string) map[string]int {
	want := Slugify(slug)
	if aliases != nil {
		if c, ok := aliases[want]; ok {
			want = c
		}
	}
	counts := map[string]int{}
	for _, scene := range ScenesForActor(slug, scenes, aliases) {
		for _, performer := range scene.Performers {
			pslug := CanonicalSlug(performer, aliases)
			if pslug == "" || pslug == want {
				continue
			}
			counts[performer]++
		}
	}
	return counts
}

// TopSimilar returns up to limit co-stars sorted by shared scene count.
func TopSimilar(costars map[string]int, aliases map[string]string, limit int) []Similar {
	type pair struct {
		name  string
		count int
	}
	pairs := make([]pair, 0, len(costars))
	for name, c := range costars {
		pairs = append(pairs, pair{name, c})
	}
	sort.SliceStable(pairs, func(i, j int) bool {
		if pairs[i].count != pairs[j].count {
			return pairs[i].count > pairs[j].count
		}
		return pairs[i].name < pairs[j].name
	})
	if limit > 0 && len(pairs) > limit {
		pairs = pairs[:limit]
	}
	out := make([]Similar, 0, len(pairs))
	seen := map[string]bool{}
	for _, p := range pairs {
		slug := CanonicalSlug(p.name, aliases)
		if slug == "" || seen[slug] {
			continue
		}
		seen[slug] = true
		out = append(out, Similar{Slug: slug, Name: p.name, Shared: p.count})
	}
	return out
}
