package interactive

import (
	"embed"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
)

//go:embed all:devicons
var embeddedDeviceIcons embed.FS

var (
	nonSlugRE = regexp.MustCompile(`[^a-z0-9]+`)
	lvsGushRE = regexp.MustCompile(`(?i)^lvs-g\d+$`)
)

var nameModelMarkers = [][2]string{
	{"gush", "gush"},
	{"solace", "solace-pro"},
	{"ferri", "ferri"},
	{"lush", "lush"},
	{"nora", "nora"},
	{"domi", "domi"},
	{"edge", "edge"},
	{"hannes-neo", "hannes-neo"},
	{"hannes", "hannes-neo"},
	{"vortex", "vortex"},
	{"funplay vortex", "vortex"},
}

var lvsModelSlugs = map[string]string{
	"lvs-x02": "ferri",
	"lvs-x01": "ferri",
	"lvs-l001": "lush",
	"lvs-l002": "lush",
	"lvs-s001": "solace-pro",
	"lvs-s002": "solace-pro",
}

var brandOnly = map[string]bool{
	"lovense": true, "svakom": true, "kiiroo": true, "handy": true,
	"satisfyer": true, "we-vibe": true, "wevibe": true, "funplay": true,
}

var skipFileSlugs = map[string]bool{
	"cutout": true, "device-icon": true, "icon": true, "pasted-image": true, "pasted": true,
}

var skipPathParts = map[string]bool{
	"icons": true, "images": true, "photos": true, "assets": true,
}

type deviceIconIndex struct {
	mu       sync.Mutex
	roots    []string
	bySlug   map[string]string
	priority map[string]int
}

var iconIndex = &deviceIconIndex{}

func slugify(text string) string {
	s := nonSlugRE.ReplaceAllString(strings.ToLower(strings.TrimSpace(text)), "-")
	return strings.Trim(s, "-")
}

// InstallEmbeddedDeviceIcons writes bundled cutouts into dest (used in Docker/release).
func InstallEmbeddedDeviceIcons(dest string) (string, error) {
	dest = filepath.Clean(strings.TrimSpace(dest))
	if dest == "" || dest == "." {
		return "", nil
	}
	sub, err := fs.Sub(embeddedDeviceIcons, "devicons")
	if err != nil {
		return "", err
	}
	if err := os.RemoveAll(dest); err != nil {
		return "", err
	}
	if err := os.MkdirAll(dest, 0o755); err != nil {
		return "", err
	}
	if err := os.CopyFS(dest, sub); err != nil {
		return "", err
	}
	RefreshDeviceIconIndex()
	return dest, nil
}

func discoverIconRoots(explicit string) []string {
	seen := map[string]bool{}
	var roots []string
	add := func(path string) {
		path = filepath.Clean(strings.TrimSpace(path))
		if path == "" || path == "." {
			return
		}
		st, err := os.Stat(path)
		if err != nil || !st.IsDir() {
			return
		}
		abs, err := filepath.Abs(path)
		if err != nil {
			return
		}
		if seen[abs] {
			return
		}
		seen[abs] = true
		roots = append(roots, abs)
	}
	if explicit != "" {
		add(explicit)
	}
	add(os.Getenv("COOG_DEVICE_ICONS"))
	add(os.Getenv("FUNPLAY_DEVICE_ICONS"))
	home, _ := os.UserHomeDir()
	candidates := []string{
		filepath.Join(home, "Projects", "interacter", "devices"),
		filepath.Join(home, "Projects", "interacter", "funplay-client", "images", "devices"),
		filepath.Join(home, "Projects", "funplay", "devices"),
		"/home/priit/Projects/interacter/devices",
		"/home/priit/Projects/interacter/funplay-client/images/devices",
	}
	for _, c := range candidates {
		add(c)
	}
	cwd, _ := os.Getwd()
	walk := cwd
	for i := 0; i < 6 && walk != "" && walk != "/"; i++ {
		add(filepath.Join(walk, "devices"))
		add(filepath.Join(walk, "interacter", "devices"))
		add(filepath.Join(walk, "funplay-client", "images", "devices"))
		parent := filepath.Dir(walk)
		if parent == walk {
			break
		}
		walk = parent
	}
	return roots
}

func (idx *deviceIconIndex) ensure(explicit string) {
	idx.mu.Lock()
	defer idx.mu.Unlock()
	if len(idx.bySlug) > 0 && len(idx.roots) > 0 {
		return
	}
	idx.roots = discoverIconRoots(explicit)
	idx.bySlug = map[string]string{}
	idx.priority = map[string]int{}
	for _, root := range idx.roots {
		_ = filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
			if err != nil || d.IsDir() {
				return nil
			}
			ext := strings.ToLower(filepath.Ext(path))
			switch ext {
			case ".png", ".jpg", ".jpeg", ".webp", ".svg":
			default:
				return nil
			}
			rel, err := filepath.Rel(root, path)
			if err != nil {
				return nil
			}
			parts := strings.Split(filepath.ToSlash(rel), "/")
			dirParts := parts[:len(parts)-1]
			base := strings.ToLower(strings.TrimSuffix(filepath.Base(path), filepath.Ext(path)))
			priority := 0
			if base == "cutout" || base == "device-icon" {
				priority += 20
			}
			for _, p := range dirParts {
				if strings.EqualFold(p, "icons") {
					priority += 10
				}
			}
			if len(dirParts) >= 2 {
				priority += 30
			}
			if strings.HasPrefix(base, "pasted") {
				priority -= 15
			}
			idx.indexFile(path, dirParts, base, priority)
			return nil
		})
	}
}

func (idx *deviceIconIndex) register(slug, path string, priority int) {
	if slug == "" || skipFileSlugs[slug] {
		return
	}
	cur, ok := idx.priority[slug]
	if !ok || priority >= cur {
		idx.bySlug[slug] = path
		idx.priority[slug] = priority
	}
}

func (idx *deviceIconIndex) indexFile(path string, dirParts []string, base string, priority int) {
	var meaningful []string
	for _, part := range dirParts {
		s := slugify(part)
		if s == "" || skipPathParts[s] {
			continue
		}
		meaningful = append(meaningful, s)
	}
	for _, slug := range meaningful {
		if len(meaningful) >= 2 && brandOnly[slug] {
			continue
		}
		idx.register(slug, path, priority)
	}
	for i := 1; i < len(meaningful); i++ {
		idx.register(meaningful[i-1]+"-"+meaningful[i], path, priority)
	}
	if len(meaningful) >= 2 {
		idx.register(meaningful[0]+"-"+meaningful[len(meaningful)-1], path, priority)
	}
	fileSlug := slugify(base)
	if fileSlug != "" && !skipFileSlugs[fileSlug] {
		idx.register(fileSlug, path, priority)
	}
}

func (idx *deviceIconIndex) lookup(key string) string {
	slug := slugify(key)
	if slug == "" {
		return ""
	}
	if p := idx.bySlug[slug]; p != "" {
		return p
	}
	if strings.HasPrefix(slug, "lovense-") {
		if p := idx.bySlug[slug[8:]]; p != "" {
			return p
		}
	}
	if p := idx.lookupParts(slug); p != "" {
		return p
	}
	if strings.HasPrefix(slug, "lvs-") {
		if strings.Contains(slug, "gush") || lvsGushRE.MatchString(slug) {
			if p := idx.bySlug["gush"]; p != "" {
				return p
			}
		}
		for prefix, model := range lvsModelSlugs {
			if slug == prefix || strings.HasPrefix(slug, prefix) {
				if p := idx.bySlug[model]; p != "" {
					return p
				}
			}
		}
	}
	return idx.lookupFuzzy(slug)
}

func (idx *deviceIconIndex) lookupParts(slug string) string {
	parts := strings.Split(slug, "-")
	for _, part := range parts {
		if part == "" || brandOnly[part] || isDigits(part) {
			continue
		}
		if p := idx.bySlug[part]; p != "" {
			return p
		}
	}
	for i := 0; i+1 < len(parts); i++ {
		compound := parts[i] + "-" + parts[i+1]
		if p := idx.bySlug[compound]; p != "" {
			return p
		}
	}
	return ""
}

func (idx *deviceIconIndex) lookupFuzzy(slug string) string {
	best := ""
	bestPath := ""
	for candidate, path := range idx.bySlug {
		if brandOnly[candidate] || len(candidate) < 4 {
			continue
		}
		if strings.Contains(slug, candidate) && len(candidate) > len(best) {
			best = candidate
			bestPath = path
		}
	}
	return bestPath
}

func isDigits(s string) bool {
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return s != ""
}

func resolveDeviceIconPath(name, deviceID, iconsRoot string) string {
	iconIndex.ensure(iconsRoot)
	iconIndex.mu.Lock()
	defer iconIndex.mu.Unlock()

	pushKeys := []string{}
	lower := strings.ToLower(strings.TrimSpace(name))
	for _, pair := range nameModelMarkers {
		if strings.Contains(lower, pair[0]) {
			pushKeys = append(pushKeys, pair[1], "lovense-"+pair[1])
		}
	}
	pushKeys = append(pushKeys, name)
	for _, word := range nonSlugRE.Split(name, -1) {
		if len(word) >= 3 {
			pushKeys = append(pushKeys, word)
		}
	}
	deviceID = strings.TrimSpace(deviceID)
	if strings.HasPrefix(deviceID, "name:") {
		pushKeys = append(pushKeys, strings.TrimPrefix(deviceID, "name:"))
	} else if deviceID != "" {
		pushKeys = append(pushKeys, deviceID)
	}
	if strings.HasPrefix(lower, "lvs") {
		ble := slugify(name)
		pushKeys = append(pushKeys, ble)
		if strings.Contains(ble, "gush") || lvsGushRE.MatchString(ble) {
			pushKeys = append(pushKeys, "gush", "lovense-gush")
		}
		for prefix, model := range lvsModelSlugs {
			if ble == prefix || strings.HasPrefix(ble, prefix) {
				pushKeys = append(pushKeys, model, "lovense-"+model)
			}
		}
		pushKeys = append(pushKeys, "lovense")
	} else if strings.Contains(lower, "lovense") {
		pushKeys = append(pushKeys, "lovense")
	}
	if strings.Contains(lower, "ferri") {
		pushKeys = append(pushKeys, "ferri", "lvs-x02")
	}
	if strings.Contains(lower, "handy") {
		pushKeys = append(pushKeys, "handy")
	}
	if strings.Contains(lower, "kiiro") {
		pushKeys = append(pushKeys, "kiiroo")
	}

	seen := map[string]bool{}
	for _, key := range pushKeys {
		path := iconIndex.lookup(key)
		if path == "" || seen[path] {
			continue
		}
		return path
	}
	for _, root := range iconIndex.roots {
		for _, slug := range []string{"default", "lovense", "linear"} {
			for _, ext := range []string{".svg", ".png"} {
				p := filepath.Join(root, slug+ext)
				if st, err := os.Stat(p); err == nil && !st.IsDir() {
					return p
				}
			}
		}
	}
	return ""
}

// ResolveDeviceIcon serves a PNG/SVG from COOG_DEVICE_ICONS / Funplay devices trees.
func ResolveDeviceIcon(w http.ResponseWriter, r *http.Request, name, deviceID, iconsRoot string) {
	path := resolveDeviceIconPath(name, deviceID, iconsRoot)
	if path == "" {
		http.NotFound(w, r)
		return
	}
	http.ServeFile(w, r, path)
}

// RefreshDeviceIconIndex clears the cached icon index (admin/tests).
func RefreshDeviceIconIndex() {
	iconIndex.mu.Lock()
	defer iconIndex.mu.Unlock()
	iconIndex.bySlug = nil
	iconIndex.priority = nil
	iconIndex.roots = nil
}
