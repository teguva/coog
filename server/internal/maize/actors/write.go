package actors

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// WriteMeta writes actor.meta.json (FunPlay-compatible). Creates the folder if needed.
func WriteMeta(actorDir string, meta Meta) error {
	actorDir = strings.TrimSpace(actorDir)
	if actorDir == "" {
		return fmt.Errorf("actor dir required")
	}
	if err := os.MkdirAll(actorDir, 0o755); err != nil {
		return err
	}
	meta = NormalizeMetaForWrite(meta)
	if n := len(GalleryImagePaths(actorDir)); n > 0 {
		meta.GalleryCount = n
	}
	b, err := json.MarshalIndent(meta, "", "  ")
	if err != nil {
		return err
	}
	b = append(b, '\n')
	return os.WriteFile(filepath.Join(actorDir, MetaFilename), b, 0o644)
}

// NormalizeMetaForWrite trims strings and drops empty list/map entries for persistence.
func NormalizeMetaForWrite(meta Meta) Meta {
	meta.Name = strings.TrimSpace(meta.Name)
	meta.Slug = strings.TrimSpace(meta.Slug)
	if meta.Slug == "" {
		meta.Slug = Slugify(meta.Name)
	}
	meta.Bio = strings.TrimSpace(meta.Bio)
	meta.Birthday = strings.TrimSpace(meta.Birthday)
	meta.Birthplace = strings.TrimSpace(meta.Birthplace)
	meta.Ethnicity = strings.TrimSpace(meta.Ethnicity)
	meta.Height = strings.TrimSpace(meta.Height)
	meta.Measurements = strings.TrimSpace(meta.Measurements)
	meta.YearsActive = strings.TrimSpace(meta.YearsActive)
	meta.Aliases = cleanStringList(meta.Aliases)
	if len(meta.Links) > 0 {
		links := map[string]string{}
		for k, v := range meta.Links {
			k = strings.TrimSpace(strings.ToLower(k))
			v = strings.TrimSpace(v)
			if k != "" && v != "" {
				links[k] = v
			}
		}
		if len(links) == 0 {
			meta.Links = map[string]string{}
		} else {
			meta.Links = links
		}
	} else {
		meta.Links = map[string]string{}
	}
	if meta.Aliases == nil {
		meta.Aliases = []string{}
	}
	// Do not persist derived display-only fields.
	meta.Nationality = ""
	meta.HairColor = ""
	meta.EyeColor = ""
	meta.Weight = ""
	meta.ShoeSize = ""
	meta.Tattoos = ""
	meta.Piercings = ""
	return meta
}

func cleanStringList(in []string) []string {
	if len(in) == 0 {
		return []string{}
	}
	out := make([]string, 0, len(in))
	seen := map[string]bool{}
	for _, s := range in {
		s = strings.TrimSpace(s)
		if s == "" || seen[strings.ToLower(s)] {
			continue
		}
		seen[strings.ToLower(s)] = true
		out = append(out, s)
	}
	return out
}

// EnsureActorDir returns an existing dir for slug, or creates a new folder for name.
func EnsureActorDir(peopleDir, slug, name string) (string, Meta, error) {
	peopleDir = strings.TrimSpace(peopleDir)
	if peopleDir == "" {
		return "", Meta{}, fmt.Errorf("people dir required")
	}
	if dir := FindActorDir(peopleDir, slug); dir != "" {
		return dir, ReadMeta(dir, ""), nil
	}
	display := strings.TrimSpace(name)
	if display == "" {
		display = strings.TrimSpace(slug)
	}
	if display == "" {
		return "", Meta{}, fmt.Errorf("name required")
	}
	dir := ActorDirForName(peopleDir, display)
	meta := DefaultMeta(display)
	if want := Slugify(slug); want != "" {
		meta.Slug = want
	}
	if err := WriteMeta(dir, meta); err != nil {
		return "", Meta{}, err
	}
	return dir, ReadMeta(dir, display), nil
}

// RemoveHeadshots deletes all known headshot filename variants.
func RemoveHeadshots(actorDir string) {
	for _, name := range headshotNames {
		for _, ext := range imgExts {
			_ = os.Remove(filepath.Join(actorDir, name+ext))
		}
	}
}

// SaveHeadshot writes Jellyfin-style folder.{ext} and removes other headshot variants.
func SaveHeadshot(actorDir string, data []byte, ext string) (string, error) {
	if len(data) < 32 {
		return "", fmt.Errorf("empty image")
	}
	if err := os.MkdirAll(actorDir, 0o755); err != nil {
		return "", err
	}
	ext = strings.ToLower(strings.TrimSpace(ext))
	ok := false
	for _, want := range imgExts {
		if ext == want {
			ok = true
			break
		}
	}
	if !ok {
		ext = ".jpg"
	}
	RemoveHeadshots(actorDir)
	dest := filepath.Join(actorDir, "folder"+ext)
	if err := os.WriteFile(dest, data, 0o644); err != nil {
		return "", err
	}
	return dest, nil
}

// ClearGallery removes all images under gallery/.
func ClearGallery(actorDir string) error {
	gdir := filepath.Join(actorDir, GalleryDirname)
	entries, err := os.ReadDir(gdir)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		_ = os.Remove(filepath.Join(gdir, e.Name()))
	}
	return nil
}

// SaveGalleryImage writes gallery/NNN.{ext} using the next free index.
func SaveGalleryImage(actorDir string, data []byte, ext string, index int) (string, error) {
	if len(data) < 32 {
		return "", fmt.Errorf("empty image")
	}
	if index < 1 {
		index = len(GalleryImagePaths(actorDir)) + 1
	}
	ext = strings.ToLower(strings.TrimSpace(ext))
	ok := false
	for _, want := range imgExts {
		if ext == want {
			ok = true
			break
		}
	}
	if !ok {
		ext = ".jpg"
	}
	gdir := filepath.Join(actorDir, GalleryDirname)
	if err := os.MkdirAll(gdir, 0o755); err != nil {
		return "", err
	}
	dest := filepath.Join(gdir, fmt.Sprintf("%03d%s", index, ext))
	if err := os.WriteFile(dest, data, 0o644); err != nil {
		return "", err
	}
	return dest, nil
}
