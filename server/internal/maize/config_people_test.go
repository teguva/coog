package maize

import (
	"os"
	"path/filepath"
	"testing"
)

func TestResolvedPeopleDirDefaultsToData(t *testing.T) {
	cfg := Config{}
	got := cfg.ResolvedPeopleDir("/data")
	if got != "/data/people" {
		t.Fatalf("got %q", got)
	}
	cfg.PeopleDir = "/custom/People"
	if cfg.ResolvedPeopleDir("/data") != "/custom/People" {
		t.Fatalf("override: %q", cfg.ResolvedPeopleDir("/data"))
	}
}

func TestMigratePeopleDirCopiesLegacyOnce(t *testing.T) {
	root := t.TempDir()
	t.Setenv("HOME", root)
	people := filepath.Join(root, ".cache", "funplay", "People", "R", "Riley Reid")
	if err := os.MkdirAll(people, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(people, "actor.meta.json"), []byte(`{"name":"Riley Reid"}`), 0o644); err != nil {
		t.Fatal(err)
	}

	data := filepath.Join(root, "coog-data")
	from, to, copied, err := MigratePeopleDir(data)
	if err != nil {
		t.Fatal(err)
	}
	if !copied {
		t.Fatalf("expected copy from %s to %s", from, to)
	}
	meta := filepath.Join(data, "people", "R", "Riley Reid", "actor.meta.json")
	if _, err := os.Stat(meta); err != nil {
		t.Fatal(err)
	}
	_, _, copied, err = MigratePeopleDir(data)
	if err != nil {
		t.Fatal(err)
	}
	if copied {
		t.Fatal("second migrate should be a no-op")
	}
}
