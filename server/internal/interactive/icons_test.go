package interactive

import (
	"os"
	"path/filepath"
	"testing"
)

func TestResolveDeviceIconPathGush(t *testing.T) {
	RefreshDeviceIconIndex()
	home, _ := os.UserHomeDir()
	root := filepath.Join(home, "Projects", "interacter", "devices")
	if st, err := os.Stat(root); err != nil || !st.IsDir() {
		t.Skip("interacter devices tree not present")
	}
	path := resolveDeviceIconPath("Lovense Gush", "", root)
	if path == "" {
		t.Fatal("expected gush icon path")
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("icon missing: %s (%v)", path, err)
	}
}
