package actors

import "testing"

func TestNormalizeMetaStatsEvaElfieShift(t *testing.T) {
	m := Meta{
		Ethnicity:    "https://www.cameo.com/evaelfie",
		Height:       "Hazel",
		Measurements: "99 lbs (45 kg)",
		Links:        map[string]string{"iafd": "https://www.iafd.com/x"},
	}
	NormalizeMetaStats(&m)
	if m.Ethnicity != "" {
		t.Fatalf("ethnicity=%q", m.Ethnicity)
	}
	if m.EyeColor != "Hazel" {
		t.Fatalf("eye=%q", m.EyeColor)
	}
	if m.Weight != "99 lbs (45 kg)" {
		t.Fatalf("weight=%q", m.Weight)
	}
	if m.Height != "" || m.Measurements != "" {
		t.Fatalf("height/meas should clear: %q %q", m.Height, m.Measurements)
	}
	if m.Links["cameo"] == "" {
		t.Fatalf("expected cameo link, got %#v", m.Links)
	}
}

func TestNormalizeMetaStatsPiperPerriShift(t *testing.T) {
	m := Meta{
		Ethnicity:    "Blond",
		Height:       "32A-26-36",
		Measurements: `"Carpe Diem" inside left wrist; "Carpe Noctem" inside right wrist`,
	}
	NormalizeMetaStats(&m)
	if m.HairColor != "Blond" {
		t.Fatalf("hair=%q", m.HairColor)
	}
	if m.Measurements != "32A-26-36" {
		t.Fatalf("meas=%q", m.Measurements)
	}
	if m.Tattoos == "" {
		t.Fatal("expected tattoos")
	}
	if m.Ethnicity != "" || m.Height != "" {
		t.Fatalf("eth/height=%q/%q", m.Ethnicity, m.Height)
	}
}

func TestNormalizeMetaStatsGabbieCarterShift(t *testing.T) {
	m := Meta{Ethnicity: "Green", Height: "US 8", Measurements: "None"}
	NormalizeMetaStats(&m)
	if m.EyeColor != "Green" {
		t.Fatalf("eye=%q", m.EyeColor)
	}
	if m.ShoeSize != "US 8" {
		t.Fatalf("shoe=%q", m.ShoeSize)
	}
	if m.Ethnicity != "" || m.Height != "" || m.Measurements != "" {
		t.Fatalf("primary=%q/%q/%q", m.Ethnicity, m.Height, m.Measurements)
	}
}

func TestNormalizeMetaStatsCorrectPassthrough(t *testing.T) {
	m := Meta{
		Ethnicity:    "Caucasian/Latin",
		Height:       "5 feet, 4 inches (162 cm)",
		Measurements: "32C-28-33",
	}
	NormalizeMetaStats(&m)
	if m.Ethnicity != "Caucasian/Latin" || m.Height == "" || m.Measurements != "32C-28-33" {
		t.Fatalf("got eth=%q h=%q m=%q", m.Ethnicity, m.Height, m.Measurements)
	}
}

func TestNormalizeMetaStatsAutumnFallsHeightInEthnicity(t *testing.T) {
	m := Meta{
		Ethnicity:    "5 feet, 3 inches (160 cm)",
		Height:       "Leaf on left heel",
		Measurements: "Nominee: Best All-Girl Group Sex Scene",
	}
	NormalizeMetaStats(&m)
	if m.Height != "5 feet, 3 inches (160 cm)" {
		t.Fatalf("height=%q", m.Height)
	}
	if m.Tattoos == "" {
		t.Fatal("expected tattoo from height slot")
	}
	if m.Measurements != "" {
		t.Fatalf("award should not stay in measurements: %q", m.Measurements)
	}
}
