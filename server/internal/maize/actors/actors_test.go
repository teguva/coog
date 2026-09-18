package actors

import (
	"os"
	"path/filepath"
	"testing"
)

func TestWriteMetaAndHeadshot(t *testing.T) {
	dir := t.TempDir()
	people := filepath.Join(dir, "People")
	actorDir, meta, err := EnsureActorDir(people, "riley-reid", "Riley Reid")
	if err != nil {
		t.Fatal(err)
	}
	if meta.Name != "Riley Reid" || meta.Slug != "riley-reid" {
		t.Fatalf("meta: %+v", meta)
	}
	meta.Bio = "Bio"
	meta.Aliases = []string{" Riley R ", "Riley R"}
	meta.Links = map[string]string{"iafd": " https://iafd.com/x "}
	meta.Locked = true
	if err := WriteMeta(actorDir, meta); err != nil {
		t.Fatal(err)
	}
	got := ReadMeta(actorDir, "")
	if got.Bio != "Bio" || !got.Locked || len(got.Aliases) != 1 || got.Links["iafd"] == "" {
		t.Fatalf("read back: %+v", got)
	}
	path, err := SaveHeadshot(actorDir, make([]byte, 64), ".png")
	if err != nil {
		t.Fatal(err)
	}
	if HeadshotPath(actorDir) != path {
		t.Fatalf("headshot path %s vs %s", HeadshotPath(actorDir), path)
	}
}

func TestSlugify(t *testing.T) {
	cases := map[string]string{
		"Riley  Reid": "riley-reid",
		"riley reid":  "riley-reid",
		"Riley Réid":  "riley-reid",
		"":            "",
	}
	for in, want := range cases {
		if got := Slugify(in); got != want {
			t.Fatalf("Slugify(%q)=%q want %q", in, got, want)
		}
	}
}

func TestBuildActorListMergeAndSort(t *testing.T) {
	dir := t.TempDir()
	actorDir := filepath.Join(dir, "R", "Riley Reid")
	if err := os.MkdirAll(actorDir, 0o755); err != nil {
		t.Fatal(err)
	}
	meta := `{"name":"Riley Reid","slug":"riley-reid","aliases":["Riley R"],"enriched_at":1,"gallery_count":0}`
	if err := os.WriteFile(filepath.Join(actorDir, MetaFilename), []byte(meta), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(actorDir, "folder.jpg"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}

	counts := map[string]int{
		"Riley R":    2,
		"Other Star": 5,
		"riley reid": 1,
	}
	list := BuildActorList(dir, counts)
	if len(list) < 2 {
		t.Fatalf("expected at least 2 actors, got %#v", list)
	}
	// Other Star has 5; Riley collapsed aliases should have 2+1=3.
	if list[0].Name != "Other Star" || list[0].SceneCount != 5 {
		t.Fatalf("expected Other Star first, got %#v", list[0])
	}
	var riley *Summary
	for i := range list {
		if list[i].Slug == "riley-reid" {
			riley = &list[i]
			break
		}
	}
	if riley == nil {
		t.Fatalf("riley missing: %#v", list)
	}
	if riley.SceneCount != 3 {
		t.Fatalf("riley scene count=%d want 3", riley.SceneCount)
	}
	if !riley.HasHeadshot {
		t.Fatal("expected headshot")
	}
	if !riley.Enriched {
		t.Fatal("expected enriched")
	}
}

func TestAliasCollapseScenes(t *testing.T) {
	dir := t.TempDir()
	actorDir := filepath.Join(dir, "R", "Riley Reid")
	if err := os.MkdirAll(actorDir, 0o755); err != nil {
		t.Fatal(err)
	}
	meta := `{"name":"Riley Reid","slug":"riley-reid","aliases":["Riley R"]}`
	if err := os.WriteFile(filepath.Join(actorDir, MetaFilename), []byte(meta), 0o644); err != nil {
		t.Fatal(err)
	}
	aliases := AliasMap(dir)
	scenes := []SceneCredit{
		{ID: "1", Performers: []string{"Riley R", "Other"}, View: map[string]any{"id": "1"}},
		{ID: "2", Performers: []string{"Nobody"}, View: map[string]any{"id": "2"}},
	}
	matched := ScenesForActor("riley-reid", scenes, aliases)
	if len(matched) != 1 || matched[0].ID != "1" {
		t.Fatalf("matched=%#v", matched)
	}
	costars := CostarsForActor("riley-reid", scenes, aliases)
	if costars["Other"] != 1 {
		t.Fatalf("costars=%#v", costars)
	}
}
