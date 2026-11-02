package webui

import (
	"embed"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

//go:embed all:fs
var embedded embed.FS

// Resolve picks the watch SPA files. COOG_WEB_DIR wins, then nearby web/dist,
// then files embedded in the binary.
func Resolve(explicit string) (http.FileSystem, string) {
	for _, dir := range candidateDirs(explicit) {
		if hasIndex(dir) {
			return http.Dir(dir), dir
		}
	}
	sub, err := fs.Sub(embedded, "fs")
	if err != nil {
		return http.FS(embedded), "embed"
	}
	return http.FS(sub), "embed"
}

func candidateDirs(explicit string) []string {
	var out []string
	if dir := strings.TrimSpace(explicit); dir != "" {
		out = append(out, dir)
	}
	if wd, err := os.Getwd(); err == nil {
		out = append(out,
			filepath.Join(wd, "web", "dist"),
			filepath.Join(wd, "..", "web", "dist"),
		)
	}
	if exe, err := os.Executable(); err == nil {
		dir := filepath.Dir(exe)
		out = append(out,
			filepath.Join(dir, "web"),
			filepath.Join(dir, "share", "coog", "web"),
			filepath.Join(dir, "..", "share", "coog", "web"),
			filepath.Join(dir, "..", "web", "dist"),
		)
	}
	if home, err := os.UserHomeDir(); err == nil {
		out = append(out, filepath.Join(home, ".local", "share", "coog", "web"))
	}
	out = append(out, "/usr/local/share/coog/web", "/usr/share/coog/web")
	return out
}

func hasIndex(dir string) bool {
	st, err := os.Stat(filepath.Join(dir, "index.html"))
	return err == nil && !st.IsDir()
}

// Handler serves the watch SPA under a stripped prefix (e.g. /app/).
func Handler(root http.FileSystem) http.Handler {
	files := http.FileServer(root)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			http.NotFound(w, r)
			return
		}
		path := strings.TrimPrefix(r.URL.Path, "/")
		if path == "" || path == "." {
			files.ServeHTTP(w, r)
			return
		}
		f, err := root.Open(path)
		if err == nil {
			_ = f.Close()
			files.ServeHTTP(w, r)
			return
		}
		if filepath.Ext(path) != "" {
			http.NotFound(w, r)
			return
		}
		r2 := r.Clone(r.Context())
		r2.URL.Path = "/"
		files.ServeHTTP(w, r2)
	})
}
