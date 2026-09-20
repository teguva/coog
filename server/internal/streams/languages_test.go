package streams

import (
	"reflect"
	"testing"
)

func TestDetectLanguagesTorrentioStyle(t *testing.T) {
	cases := []struct {
		in   string
		want []string
	}{
		{"Show.S01E01.1080p.WEB-DL.DDP5.1.H.264-GROUP", nil},
		{"Movie.2024.1080p.BluRay.x264.DTS.ENG", []string{"en"}},
		{"Movie.2024.ITA.ENG.1080p", []string{"en", "it"}},
		{"Film.MULTi.FRENCH.1080p", []string{"multi", "fr"}},
		{"Movie Dual Audio Hindi English 1080p", []string{"dual", "en", "hi"}},
		{"Pelicula.LATINO.SPANISH.1080p", []string{"es", "latino"}},
		{"🇬🇧 🇯🇵 1080p WEB", []string{"en", "ja"}},
		{"Spider-Man No Way Home 1080p", nil},
		{"It Chapter Two 2019", nil},
		{"Show.S01.COMPLETE.1080p", nil},
	}
	for _, tc := range cases {
		got := DetectLanguages(tc.in)
		if tc.want == nil {
			if len(got) != 0 {
				t.Errorf("%q: got %v want none", tc.in, got)
			}
			continue
		}
		if !sameStrings(got, tc.want) {
			t.Errorf("%q: got %v want %v", tc.in, got, tc.want)
		}
	}
}

func TestLanguageFlagsUnique(t *testing.T) {
	got := LanguageFlags([]string{"en", "hi", "te", "ta", "dual"})
	want := []string{"🇬🇧", "🇮🇳"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v want %v", got, want)
	}
}

func sameStrings(got, want []string) bool {
	if len(got) != len(want) {
		return false
	}
	have := map[string]int{}
	for _, s := range got {
		have[s]++
	}
	for _, s := range want {
		have[s]--
		if have[s] < 0 {
			return false
		}
	}
	return true
}
