package interactive

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func TestStableDeviceID(t *testing.T) {
	got := StableDeviceID("Lovense Gush")
	if got != "name:lovense-gush" {
		t.Fatalf("got %q", got)
	}
}

func TestClampIntensity(t *testing.T) {
	if ClampIntensity(5) != 10 {
		t.Fatal("min")
	}
	if ClampIntensity(250) != 200 {
		t.Fatal("max")
	}
}

func TestIsVortexName(t *testing.T) {
	if !IsVortexName("Funplay Vortex") {
		t.Fatal("expected vortex")
	}
}

func TestIsVortexStableID(t *testing.T) {
	if !IsVortexStableID("name:funplay-vortex") {
		t.Fatal("expected vortex id")
	}
}

func TestIsPowerBlowName(t *testing.T) {
	if !IsPowerBlowName("PowerBlow R1") || !IsPowerBlowStableID("name:powerblow-r1") {
		t.Fatal("expected powerblow")
	}
}

func TestMacFromObjectPath(t *testing.T) {
	got := MacFromObjectPath(`/org/bluez/hci0/dev_44_9F_DA_EB_C6_43`)
	if got != "44:9F:DA:EB:C6:43" {
		t.Fatalf("got %q", got)
	}
}

func TestParseEngineLogLine(t *testing.T) {
	line := `Device Added: 4 - Lovense Gush 2 - UserDeviceIdentifier { address: None, name: Some("…"), object_path: Path("/org/bluez/hci0/dev_54_73_A0_45_FB_84") }`
	name, path, ok := parseEngineLogLine(line)
	if !ok || name != "Lovense Gush 2" || !strings.Contains(path, "dev_54_73") {
		t.Fatalf("name=%q path=%q ok=%v", name, path, ok)
	}
	found := `Device Lovense Gush 2 (PeripheralId(DeviceId { object_path: Path("/org/bluez/hci0/dev_54_73_A0_45_FB_84\0") })) found.`
	name, path, ok = parseEngineLogLine(found)
	if !ok || name != "Lovense Gush 2" || MacFromObjectPath(path) != "54:73:A0:45:FB:84" {
		t.Fatalf("found name=%q path=%q ok=%v mac=%q", name, path, ok, MacFromObjectPath(path))
	}
}

func TestDeviceListDoesNotPruneMissing(t *testing.T) {
	c := NewButtplugClient("", 0)
	devA, _ := json.Marshal(map[string]any{
		"DeviceIndex":    1,
		"DeviceName":     "Toy A",
		"DeviceMessages": map[string]any{},
	})
	devB, _ := json.Marshal(map[string]any{
		"DeviceIndex":    2,
		"DeviceName":     "Toy B",
		"DeviceMessages": map[string]any{},
	})
	listBoth, _ := json.Marshal(map[string]any{
		"Devices": []json.RawMessage{devA, devB},
	})
	changed, removed := c.applyMessages([]map[string]json.RawMessage{{"DeviceList": listBoth}})
	if !changed || len(removed) != 0 {
		t.Fatalf("upsert both: changed=%v removed=%v", changed, removed)
	}
	if len(c.Devices()) != 2 {
		t.Fatalf("want 2 devices, got %d", len(c.Devices()))
	}

	// Partial DeviceList must not drop Toy B (Funplay rule).
	listOnlyA, _ := json.Marshal(map[string]any{
		"Devices": []json.RawMessage{devA},
	})
	changed, removed = c.applyMessages([]map[string]json.RawMessage{{"DeviceList": listOnlyA}})
	if changed {
		t.Fatal("partial list should not change existing set")
	}
	if len(removed) != 0 {
		t.Fatalf("unexpected removed %v", removed)
	}
	if len(c.Devices()) != 2 {
		t.Fatalf("DeviceList prune leaked: got %d devices", len(c.Devices()))
	}

	rem, _ := json.Marshal(map[string]any{"DeviceIndex": 2})
	changed, removed = c.applyMessages([]map[string]json.RawMessage{{"DeviceRemoved": rem}})
	if !changed || len(removed) != 1 || removed[0] != StableDeviceID("Toy B") {
		t.Fatalf("DeviceRemoved: changed=%v removed=%v", changed, removed)
	}
	if len(c.Devices()) != 1 {
		t.Fatalf("want 1 after remove, got %d", len(c.Devices()))
	}
}

func TestDiscoveryBudget(t *testing.T) {
	s := &Service{wanted: map[string]struct{}{}, bp: NewButtplugClient("", 0)}
	d := newDiscovery(s)
	if d.DiscoveryActive() {
		t.Fatal("expected inactive")
	}
	d.BeginDiscovery(200*time.Millisecond, "test")
	if !d.DiscoveryActive() {
		t.Fatal("expected active")
	}
	if d.DiscoveryReason() != "test" {
		t.Fatalf("reason %q", d.DiscoveryReason())
	}
	time.Sleep(250 * time.Millisecond)
	// tick clears expired budget
	d.tick()
	if d.DiscoveryActive() {
		t.Fatal("expected budget ended")
	}
}

func TestMissingWantedCount(t *testing.T) {
	s := &Service{
		wanted: map[string]struct{}{
			"name:toy-a": {},
			"name:toy-b": {},
		},
		bp: NewButtplugClient("", 0),
	}
	if s.missingWantedCount() != 2 {
		t.Fatalf("want 2 missing, got %d", s.missingWantedCount())
	}
	dev, _ := json.Marshal(map[string]any{
		"DeviceIndex":    1,
		"DeviceName":     "Toy A",
		"DeviceMessages": map[string]any{},
	})
	_, _ = s.bp.applyMessages([]map[string]json.RawMessage{{
		"DeviceAdded": dev,
	}})
	if s.missingWantedCount() != 1 {
		t.Fatalf("want 1 missing, got %d", s.missingWantedCount())
	}
}

func TestParseRouteGateway(t *testing.T) {
	if got := parseRouteGateway("010013AC"); got != "172.19.0.1" {
		t.Fatalf("got %q", got)
	}
}

func TestNextLinearStrokeTip(t *testing.T) {
	// Triangle: bottom → top → bottom. From near start, tip should be the peak.
	actions := []Action{
		{At: 0, Pos: 10},
		{At: 400, Pos: 90},
		{At: 800, Pos: 10},
		{At: 1200, Pos: 90},
	}
	tip, ok := nextLinearStrokeTip(actions, 50, HandyMinStrokeDelta, HandyLinearStrokeInset)
	if !ok {
		t.Fatal("expected tip")
	}
	if tip.Idx != 1 {
		t.Fatalf("want peak idx 1, got %d pos=%.1f at=%.0f", tip.Idx, tip.Pos, tip.At)
	}
	if tip.DurMs < HandyMinStrokeDurMs || tip.At <= 50 {
		t.Fatalf("bad dur/at: %+v", tip)
	}
	// Past the peak: next tip is the valley.
	tip2, ok := nextLinearStrokeTip(actions, 420, HandyMinStrokeDelta, HandyLinearStrokeInset)
	if !ok || tip2.Idx != 2 {
		t.Fatalf("want valley idx 2, got ok=%v tip=%+v", ok, tip2)
	}
}
