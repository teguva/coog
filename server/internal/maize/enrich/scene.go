package enrich

import (
	"path/filepath"
	"strconv"
	"time"

	"coog/internal/maize"
)

// EnrichSceneFromIAFD fetches an IAFD title page and merges into movie.meta.json fields.
func EnrichSceneFromIAFD(c *Client, current maize.SceneMeta, iafdURL, folderLabel string, force bool) (maize.SceneMeta, error) {
	if current.Locked && !force {
		return current, nil
	}
	data, err := c.FetchTitle(iafdURL)
	if err != nil {
		return current, err
	}
	return MergeIAFDIntoScene(current, data, folderLabel), nil
}

// MergeIAFDIntoScene overwrites FunPlay title fields from IAFD (same as fplay/scenes/enrich.py).
func MergeIAFDIntoScene(current maize.SceneMeta, iafd TitleData, folderLabel string) maize.SceneMeta {
	meta := current
	links := map[string]string{}
	for k, v := range meta.Links {
		links[k] = v
	}
	if iafd.Link != "" {
		links["iafd"] = iafd.Link
	}
	meta.Links = links

	if iafd.Title != "" {
		meta.Title = iafd.Title
	}
	if iafd.Description != "" {
		meta.Description = iafd.Description
	}
	if iafd.Studio != "" {
		meta.Studio = iafd.Studio
	}
	if iafd.Director != "" {
		meta.Director = iafd.Director
	}
	if iafd.Year > 0 {
		meta.Year = iafd.Year
		if meta.ReleaseDate == "" {
			meta.ReleaseDate = strconv.Itoa(iafd.Year)
		}
	}
	if iafd.Duration != "" {
		meta.Duration = iafd.Duration
	}
	if len(iafd.Performers) > 0 {
		meta.Performers = iafd.Performers
	}
	if len(iafd.Tags) > 0 {
		meta.Tags = iafd.Tags
	}

	aliases := append([]string{}, meta.Aliases...)
	folderLabel = filepath.Base(folderLabel)
	if folderLabel != "" && folderLabel != meta.Title {
		found := false
		for _, a := range aliases {
			if a == folderLabel {
				found = true
				break
			}
		}
		if !found {
			aliases = append(aliases, folderLabel)
		}
	}
	meta.Aliases = aliases

	sources := map[string]any{}
	for k, v := range meta.Sources {
		sources[k] = v
	}
	sources["iafd"] = map[string]any{"ok": true, "at": float64(time.Now().Unix())}
	meta.Sources = sources
	meta.EnrichedAt = float64(time.Now().Unix())
	return maize.NormalizeSceneMeta(meta)
}
