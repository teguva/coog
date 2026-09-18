package enrich

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"coog/internal/maize/actors"
)

func imageReferer(u string) string {
	low := strings.ToLower(u)
	switch {
	case strings.Contains(low, "babehub.com"):
		return babehubBase
	case strings.Contains(low, "pornpics.com"):
		return pornpicsBase
	case strings.Contains(low, "pornhub.com"), strings.Contains(low, "phncdn.com"):
		return pornhubBase
	default:
		return iafdBase
	}
}

func hasAnyBio(meta actors.Meta) bool {
	return meta.Bio != "" || meta.Birthday != "" || meta.Birthplace != "" ||
		meta.Ethnicity != "" || meta.Height != "" || meta.Measurements != "" || meta.YearsActive != ""
}

func ensureLinks(meta *actors.Meta) map[string]string {
	if meta.Links == nil {
		meta.Links = map[string]string{}
	}
	return meta.Links
}

func ensureSources(meta *actors.Meta) map[string]any {
	if meta.Sources == nil {
		meta.Sources = map[string]any{}
	}
	return meta.Sources
}

func saveHeadshotFromURL(c *Client, actorDir, imageURL, referer string) (bool, error) {
	raw, err := c.GetBytes(imageURL, referer)
	if err != nil {
		return false, err
	}
	if len(raw) < minImageBytes {
		return false, nil
	}
	if _, err := actors.SaveHeadshot(actorDir, raw, extFromURL(imageURL, ".jpg")); err != nil {
		return false, err
	}
	return true, nil
}

// EnrichActor scrapes IAFD + Babehub + PornPics (+ optional Pornhub) into a People folder.
func EnrichActor(c *Client, peopleDir, name, slug string, force bool, galleryLimit int) (actors.Meta, error) {
	if galleryLimit <= 0 {
		galleryLimit = defaultGalleryN
	}
	peopleDir = strings.TrimSpace(peopleDir)
	if peopleDir == "" {
		return actors.Meta{}, fmt.Errorf("people dir required")
	}
	display := strings.TrimSpace(name)
	if display == "" {
		display = strings.TrimSpace(slug)
	}
	actorDir, meta, err := actors.EnsureActorDir(peopleDir, slug, display)
	if err != nil {
		return actors.Meta{}, err
	}
	if meta.Name == "" {
		meta.Name = display
	}
	if meta.Locked && !force {
		return meta, nil
	}

	changed := false
	sources := ensureSources(&meta)
	var iafdHeadshotURL string

	// 1. IAFD bio/stats
	needBio := force || !hasAnyBio(meta)
	if needBio {
		iafdURL := ""
		if meta.Links != nil {
			iafdURL = meta.Links["iafd"]
		}
		data, err := c.FetchPerson(meta.Name, iafdURL)
		if err != nil {
			return meta, err
		}
		if data.Link != "" || data.Birthday != "" || data.HeadshotURL != "" || len(data.Aliases) > 0 {
			iafdHeadshotURL = data.HeadshotURL
			if data.Birthday != "" && (force || meta.Birthday == "") {
				meta.Birthday = data.Birthday
				changed = true
			}
			if data.Birthplace != "" && (force || meta.Birthplace == "") {
				meta.Birthplace = data.Birthplace
				changed = true
			}
			if data.Ethnicity != "" && (force || meta.Ethnicity == "") {
				meta.Ethnicity = data.Ethnicity
				changed = true
			}
			if data.Height != "" && (force || meta.Height == "") {
				meta.Height = data.Height
				changed = true
			}
			if data.Measurements != "" && (force || meta.Measurements == "") {
				meta.Measurements = data.Measurements
				changed = true
			}
			if data.YearsActive != "" && (force || meta.YearsActive == "") {
				meta.YearsActive = data.YearsActive
				changed = true
			}
			if data.Link != "" {
				links := ensureLinks(&meta)
				links["iafd"] = data.Link
				changed = true
			}
			if len(data.Aliases) > 0 {
				merged := append([]string{}, meta.Aliases...)
				seen := map[string]bool{}
				for _, a := range merged {
					seen[strings.ToLower(a)] = true
				}
				for _, a := range data.Aliases {
					if !seen[strings.ToLower(a)] {
						merged = append(merged, a)
						seen[strings.ToLower(a)] = true
						changed = true
					}
				}
				meta.Aliases = merged
			}
			sources["iafd"] = map[string]any{"ok": true, "at": float64(time.Now().Unix())}
		} else if _, ok := sources["iafd"]; !ok {
			sources["iafd"] = map[string]any{"ok": false, "at": float64(time.Now().Unix())}
		}
	}

	// 2. Babehub headshot + gallery; IAFD/Pornhub headshot fallbacks; PornPics gallery fill.
	displayName := meta.Name
	if displayName == "" {
		displayName = display
	}
	needHeadshot := force || actors.HeadshotPath(actorDir) == ""
	existing := actors.GalleryImagePaths(actorDir)
	needGallery := force || len(existing) == 0

	if needHeadshot || needGallery {
		if modelLink, err := c.FetchBabehubModelLink(displayName); err != nil {
			return meta, err
		} else if modelLink != "" {
			links := ensureLinks(&meta)
			links["babehub"] = modelLink
			changed = true
		}

		if needHeadshot {
			saved := false
			if shotURL, err := c.FetchBabehubHeadshotURL(displayName); err != nil {
				return meta, err
			} else if shotURL != "" {
				ok, err := saveHeadshotFromURL(c, actorDir, shotURL, babehubBase)
				if err != nil {
					return meta, err
				}
				if ok {
					saved = true
					sources["headshot"] = map[string]any{"ok": true, "provider": "babehub", "at": float64(time.Now().Unix())}
					changed = true
				}
			}
			if !saved {
				fallback := iafdHeadshotURL
				if fallback == "" {
					iafdURL := ""
					if meta.Links != nil {
						iafdURL = meta.Links["iafd"]
					}
					data, err := c.FetchPerson(displayName, iafdURL)
					if err != nil {
						return meta, err
					}
					if data.Link != "" {
						links := ensureLinks(&meta)
						if links["iafd"] == "" {
							links["iafd"] = data.Link
							changed = true
						}
					}
					fallback = data.HeadshotURL
				}
				if fallback != "" {
					ok, err := saveHeadshotFromURL(c, actorDir, fallback, iafdBase)
					if err != nil {
						return meta, err
					}
					if ok {
						saved = true
						sources["headshot"] = map[string]any{"ok": true, "provider": "iafd", "at": float64(time.Now().Unix())}
						changed = true
					}
				}
			}
			if !saved {
				phLink := ""
				if meta.Links != nil {
					phLink = meta.Links["pornhub"]
				}
				if phLink != "" {
					phShot, err := c.FetchPornhubHeadshotURL(phLink)
					if err != nil {
						return meta, err
					}
					if phShot != "" {
						ok, err := saveHeadshotFromURL(c, actorDir, phShot, pornhubBase)
						if err != nil {
							return meta, err
						}
						if ok {
							saved = true
							if canon := NormalizePornhubProfileURL(phLink); canon != "" && canon != phLink {
								links := ensureLinks(&meta)
								links["pornhub"] = canon
							}
							sources["headshot"] = map[string]any{"ok": true, "provider": "pornhub", "at": float64(time.Now().Unix())}
							changed = true
						}
					}
				}
			}
			if !saved {
				if _, ok := sources["headshot"]; !ok {
					sources["headshot"] = map[string]any{"ok": false, "provider": "babehub", "at": float64(time.Now().Unix())}
				}
			}
		}

		if needGallery {
			babehubURLs, err := c.FetchBabehubImageURLs(displayName, galleryLimit)
			if err != nil {
				return meta, err
			}
			pornpicsURLs, err := c.FetchPornpicsImageURLs(displayName, galleryLimit)
			if err != nil {
				return meta, err
			}
			seen := map[string]bool{}
			var urls []string
			for _, u := range append(append([]string{}, babehubURLs...), pornpicsURLs...) {
				if seen[u] {
					continue
				}
				seen[u] = true
				urls = append(urls, u)
				if len(urls) >= galleryLimit {
					break
				}
			}

			links := ensureLinks(&meta)
			if len(pornpicsURLs) > 0 {
				if ppLink, err := c.FetchPornpicsModelLink(displayName); err != nil {
					return meta, err
				} else if ppLink != "" {
					if links["pornpics"] != ppLink {
						links["pornpics"] = ppLink
						changed = true
					}
				}
			} else if _, ok := links["pornpics"]; ok {
				delete(links, "pornpics")
				changed = true
			}

			if force && len(existing) > 0 {
				_ = actors.ClearGallery(actorDir)
				existing = nil
			}

			saved, babehubSaved, pornpicsSaved := 0, 0, 0
			if len(urls) > 0 {
				_ = os.MkdirAll(filepath.Join(actorDir, actors.GalleryDirname), 0o755)
				for _, u := range urls {
					raw, err := c.GetBytes(u, imageReferer(u))
					if err != nil {
						return meta, err
					}
					if len(raw) < minImageBytes {
						continue
					}
					saved++
					if strings.Contains(strings.ToLower(u), "pornpics.com") {
						pornpicsSaved++
					} else {
						babehubSaved++
					}
					if _, err := actors.SaveGalleryImage(actorDir, raw, extFromURL(u, ".jpg"), saved); err != nil {
						return meta, err
					}
				}
				meta.GalleryCount = len(actors.GalleryImagePaths(actorDir))
				providers := []string{}
				if babehubSaved > 0 {
					providers = append(providers, "babehub")
				}
				if pornpicsSaved > 0 {
					providers = append(providers, "pornpics")
				}
				provider := "none"
				if len(providers) > 0 {
					provider = strings.Join(providers, "+")
				}
				sources["gallery"] = map[string]any{
					"ok":       saved > 0,
					"provider": provider,
					"count":    meta.GalleryCount,
					"at":       float64(time.Now().Unix()),
				}
				changed = true
			} else if force && len(existing) == 0 {
				// cleared earlier; mark miss
				meta.GalleryCount = 0
				sources["gallery"] = map[string]any{
					"ok": false, "provider": "babehub+pornpics", "count": 0, "at": float64(time.Now().Unix()),
				}
				changed = true
			} else if _, ok := sources["gallery"]; !ok {
				sources["gallery"] = map[string]any{"ok": false, "provider": "babehub+pornpics", "at": float64(time.Now().Unix())}
			}
		}
	}

	_ = changed // meta always rewritten with enriched_at
	meta.Sources = sources
	meta.EnrichedAt = float64(time.Now().Unix())
	if err := actors.WriteMeta(actorDir, meta); err != nil {
		return meta, err
	}
	return actors.ReadMeta(actorDir, meta.Name), nil
}
