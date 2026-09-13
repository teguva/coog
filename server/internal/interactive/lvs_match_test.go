package interactive

import "testing"

func TestBleNameMatchesLushC18(t *testing.T) {
	id := StableDeviceID("Lovense Lush")
	if !bleNameMatchesDevice("LVS-C18", id, "Lovense Lush", "") {
		t.Fatal("LVS-C18 should match Lovense Lush")
	}
	if bleNameMatchesDevice("LVS-Gush", id, "Lovense Lush", "") {
		t.Fatal("LVS-Gush must not match Lovense Lush")
	}
	if bleNameMatchesDevice("LVS-C18", StableDeviceID("Lovense Gush"), "Lovense Gush", "") {
		t.Fatal("LVS-C18 must not match Lovense Gush")
	}
}

func TestBleNameMatchesGush(t *testing.T) {
	id := StableDeviceID("Lovense Gush 2")
	if !bleNameMatchesDevice("LVS-Gush", id, "Lovense Gush 2", "") {
		t.Fatal("expected gush match")
	}
	if bleNameMatchesDevice("LVS-C18", id, "Lovense Gush 2", "") {
		t.Fatal("lush advert must not match gush")
	}
}

func TestBleNameNeverMatchesBareLovense(t *testing.T) {
	// Any LVS-* must not bind to a device whose only keyword is the brand.
	if bleNameMatchesDevice("LVS-C18", "name:lovense", "Lovense", "") {
		t.Fatal("bare Lovense must not claim LVS-C18")
	}
}

func TestBleNameStoredExact(t *testing.T) {
	if !bleNameMatchesDevice("Svakom", "name:other", "Other", "Svakom") {
		t.Fatal("stored non-LVS ble_name exact match")
	}
	// Mis-saved LVS-Gush on Lush must not keep matching via storedBle alone.
	if bleNameMatchesDevice("LVS-Gush", StableDeviceID("Lovense Lush"), "Lovense Lush", "LVS-Gush") {
		t.Fatal("stale LVS-Gush on Lush must not match")
	}
	if !bleNameMatchesDevice("LVS-C18", StableDeviceID("Lovense Lush"), "Lovense Lush", "LVS-C18") {
		t.Fatal("correct LVS-C18 on Lush should match via patterns")
	}
}

func TestEngineConnectFailRegex(t *testing.T) {
	line := `Device errored while trying to connect: InvalidEndpoint("rx")`
	if !reDeviceConnectFail.MatchString(line) {
		t.Fatal("expected connect fail match")
	}
	nameLine := `device creation{name=LVS-Gush address=PeripheralId(...)}: ... InvalidEndpoint`
	if engineDeviceNameFromLog(nameLine) != "LVS-Gush" {
		t.Fatalf("got %q", engineDeviceNameFromLog(nameLine))
	}
	mac := MacFromObjectPath(`/org/bluez/hci0/dev_44_9F_DA_EB_C6_43`)
	if mac != "44:9F:DA:EB:C6:43" {
		t.Fatalf("mac %q", mac)
	}
}

func TestDeviceSlugKeywordsSkipBrand(t *testing.T) {
	kws := deviceSlugKeywords("name:lovense-lush", "Lovense Lush")
	if _, ok := kws["lovense"]; ok {
		t.Fatal("lovense brand should be skipped")
	}
	if _, ok := kws["lush"]; !ok {
		t.Fatal("expected lush keyword")
	}
}
