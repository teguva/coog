package api

import (
	"path/filepath"
	"sort"
	"strings"

	"coog/internal/library"
	"coog/internal/maize"
	"coog/internal/store"
)

// preferMediaItem picks the highest-quality video among siblings.
func preferMediaItem(items []store.MediaItem) store.MediaItem {
	if len(items) == 0 {
		return store.MediaItem{}
	}
	best := items[0]
	bestRank := maize.QualityRank(best.Height, best.SizeBytes, best.Path)
	for _, it := range items[1:] {
		r := maize.QualityRank(it.Height, it.SizeBytes, it.Path)
		if r > bestRank {
			best = it
			bestRank = r
		}
	}
	return best
}

// collapseMaizeByFolder returns one preferred media item per scene folder.
func collapseMaizeByFolder(items []store.MediaItem) []store.MediaItem {
	groups := map[string][]store.MediaItem{}
	order := make([]string, 0)
	for _, it := range items {
		dir := filepath.Clean(filepath.Dir(it.Path))
		if _, ok := groups[dir]; !ok {
			order = append(order, dir)
		}
		groups[dir] = append(groups[dir], it)
	}
	out := make([]store.MediaItem, 0, len(order))
	for _, dir := range order {
		out = append(out, preferMediaItem(groups[dir]))
	}
	return out
}

func (s *Server) mediaSiblings(item store.MediaItem) []store.MediaItem {
	dir := filepath.Clean(filepath.Dir(item.Path))
	all, err := s.store.ListMedia()
	if err != nil {
		return []store.MediaItem{item}
	}
	var sibs []store.MediaItem
	for _, it := range all {
		if library.IsSidecarVideo(it.Path) {
			continue
		}
		if filepath.Clean(filepath.Dir(it.Path)) != dir {
			continue
		}
		sibs = append(sibs, it)
	}
	if len(sibs) == 0 {
		return []store.MediaItem{item}
	}
	sort.SliceStable(sibs, func(i, j int) bool {
		ri := maize.QualityRank(sibs[i].Height, sibs[i].SizeBytes, sibs[i].Path)
		rj := maize.QualityRank(sibs[j].Height, sibs[j].SizeBytes, sibs[j].Path)
		if ri != rj {
			return ri > rj
		}
		return strings.ToLower(sibs[i].Path) < strings.ToLower(sibs[j].Path)
	})
	return sibs
}

func videoCandidates(sibs []store.MediaItem) []maize.VideoCandidate {
	if len(sibs) <= 1 {
		return nil
	}
	pref := preferMediaItem(sibs)
	out := make([]maize.VideoCandidate, 0, len(sibs))
	for _, it := range sibs {
		name := filepath.Base(it.Path)
		out = append(out, maize.VideoCandidate{
			ID:        it.ID,
			Label:     maize.QualityLabel(it.Height, name),
			Filename:  name,
			Width:     it.Width,
			Height:    it.Height,
			SizeBytes: it.SizeBytes,
			Preferred: it.ID == pref.ID,
		})
	}
	return out
}
