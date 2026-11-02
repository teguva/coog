package subtitles

import "testing"

func TestParseEmbeddedID(t *testing.T) {
	idx, path, err := parseEmbeddedID("emb:14:/home/priit/Videos/Movies/Shrek the Third (2007)/Shrek the Third (2007).mkv")
	if err != nil {
		t.Fatal(err)
	}
	if idx != 14 {
		t.Fatalf("index=%d", idx)
	}
	if path == "" || path[0] != '/' {
		t.Fatalf("path=%q", path)
	}
	if _, _, err := parseEmbeddedID("emb:nope"); err == nil {
		t.Fatal("expected error")
	}
}
