package subtitles

import (
	"os"
	"path/filepath"
	"testing"
)

func TestFindSidecars(t *testing.T) {
	dir := t.TempDir()
	video := filepath.Join(dir, "Movie.Name.2020.mkv")
	if err := os.WriteFile(video, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	srt := filepath.Join(dir, "Movie.Name.2020.en.srt")
	if err := os.WriteFile(srt, []byte("1\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	rows := FindSidecars(video)
	if len(rows) != 1 {
		t.Fatalf("got %d rows: %+v", len(rows), rows)
	}
	if rows[0].Language != "en" || rows[0].Source != "sidecar" {
		t.Fatalf("%+v", rows[0])
	}
}

