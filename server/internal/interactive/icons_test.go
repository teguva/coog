package interactive

import (
	"os"
	"path/filepath"
	"strings"
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

func TestResolveDeviceIconPathPowerShot(t *testing.T) {
	RefreshDeviceIconIndex()
	root := t.TempDir()
	if _, err := InstallEmbeddedDeviceIcons(root); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"Kiiroo PowerShot", "PowerShot", "kiiroo-powershot"} {
		path := resolveDeviceIconPath(name, "name:kiiroo-powershot", root)
		if path == "" {
			t.Fatalf("expected powershot icon for %q", name)
		}
		if !strings.Contains(path, "powershot") {
			t.Fatalf("path %s should be powershot cutout for %q", path, name)
		}
	}
}

func TestResolveDeviceIconPathHandy2Pro(t *testing.T) {
	RefreshDeviceIconIndex()
	root := t.TempDir()
	if _, err := InstallEmbeddedDeviceIcons(root); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"The Handy 2 Pro", "Handy 2 Pro", "handy-2-pro"} {
		path := resolveDeviceIconPath(name, "name:the-handy-2-pro", root)
		if path == "" {
			t.Fatalf("expected handy 2 pro icon for %q", name)
		}
		if !strings.Contains(path, "handy-2-pro") {
			t.Fatalf("path %s should be handy-2-pro cutout for %q", path, name)
		}
	}
}

func TestResolveDeviceIconPathPowerBlow(t *testing.T) {
	RefreshDeviceIconIndex()
	root := t.TempDir()
	if _, err := InstallEmbeddedDeviceIcons(root); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"PowerBlow R1", "Kiiroo PowerBlow", "powerblow"} {
		path := resolveDeviceIconPath(name, "name:powerblow-r1", root)
		if path == "" {
			t.Fatalf("expected powerblow icon for %q", name)
		}
		if !strings.Contains(path, "powerblow") {
			t.Fatalf("path %s should be powerblow cutout for %q", path, name)
		}
	}
}
