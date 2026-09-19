package interactive

import (
	"os"
	"path/filepath"
	"testing"
)

func TestResolveDeviceIconPathGush(t *testing.T) {
	RefreshDeviceIconIndex()
	root := t.TempDir()
	if _, err := InstallEmbeddedDeviceIcons(root); err != nil {
		t.Fatal(err)
	}
	path := resolveDeviceIconPath("Lovense Gush", "", root)
	if path == "" {
		t.Fatal("expected gush icon path")
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("icon missing: %s (%v)", path, err)
	}
	if filepath.Base(path) == "" {
		t.Fatal("empty path")
	}
}
