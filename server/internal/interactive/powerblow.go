package interactive

import (
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"time"

	"github.com/godbus/dbus/v5"
)

// PowerBlow protocol (FeelConnect / denialtek sniff):
//
//	BLE name: PowerBlow R1
//	Init  0x1902 ← 00 32 00 00 00
//	Suck  0x1401 ← XX YY  (intensity 0x80–0xFF, duration ticks ≈50ms)
//	Release 0x1402 ← same XX YY as last suction
//
// Suction holds after the timed pulse until release is written.

const (
	powerblowStableID   = "name:powerblow-r1"
	powerblowIndexBase  = 9100
	powerblowNameExact  = "PowerBlow R1"
	uuidInitTX          = "00001902-0000-1000-8000-00805f9b34fb"
	uuidSuctionTX       = "00001401-0000-1000-8000-00805f9b34fb"
	uuidReleaseTX       = "00001402-0000-1000-8000-00805f9b34fb"
	uuidColorTX         = "00001601-0000-1000-8000-00805f9b34fb"
	uuidBattery         = "00002a19-0000-1000-8000-00805f9b34fb"
	powerblowInitPayload = "\x00\x32\x00\x00\x00"
)

func IsPowerBlowName(name string) bool {
	n := strings.ToLower(strings.TrimSpace(name))
	return strings.Contains(n, "powerblow") || n == "powerblow r1" || n == "kpb"
}

func IsPowerBlowStableID(deviceID string) bool {
	sid := strings.ToLower(strings.TrimSpace(deviceID))
	return sid == powerblowStableID || strings.Contains(sid, "powerblow")
}

// PowerBlowHub owns one native BlueZ GATT connection (Intiface has no protocol).
type PowerBlowHub struct {
	mu        sync.Mutex
	conn      *dbus.Conn
	mac       string
	devPath   string
	chars     map[string]string // uuid -> char object path
	connected bool
	inited    bool
	lastXX    byte
	lastYY    byte
	battery   *int
	onChange  func()

	// Command serialization — device cannot meaningfully pump and vent together.
	// cmdMu is held across waits so suck/vent never overlap on the wire.
	cmdMu         sync.Mutex
	vacuumOn      bool
	pumping       bool
	pumpUntil     time.Time
	ventUntil     time.Time
	lastReleaseAt time.Time
	lastSuckAt    time.Time
}

func NewPowerBlowHub() *PowerBlowHub {
	return &PowerBlowHub{
		chars:  map[string]string{},
		lastXX: 0xC0,
		lastYY: 0x28, // ~2s pulse — FeelConnect mid default-ish
	}
}

func (h *PowerBlowHub) SetOnChange(fn func()) {
	h.mu.Lock()
	h.onChange = fn
	h.mu.Unlock()
}

func (h *PowerBlowHub) Connected() bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.connected && h.inited
}

func (h *PowerBlowHub) Live() (LiveDevice, bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if !h.connected || !h.inited {
		return LiveDevice{}, false
	}
	d := LiveDevice{
		Index:    powerblowIndexBase,
		DeviceID: powerblowStableID,
		Name:     powerblowNameExact,
		Kind:     "constrict",
	}
	if h.battery != nil {
		pct := *h.battery
		d.BatteryPercent = &pct
		idx := 0
		d.BatterySensorIndex = &idx
	}
	return d, true
}

func (h *PowerBlowHub) MAC() string {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.mac
}

func (h *PowerBlowHub) notify() {
	h.mu.Lock()
	fn := h.onChange
	h.mu.Unlock()
	if fn != nil {
		fn()
	}
}

// TryConnect finds PowerBlow R1 on BlueZ and brings up GATT + FeelConnect init.
func (h *PowerBlowHub) TryConnect() error {
	mac := bluezFindMAC("PowerBlow R1", "PowerBlow", "powerblow")
	if mac == "" {
		return fmt.Errorf("powerblow not advertising")
	}
	return h.ConnectMAC(mac)
}

func (h *PowerBlowHub) ConnectMAC(mac string) error {
	mac = NormalizeMAC(mac)
	if mac == "" {
		return fmt.Errorf("empty mac")
	}
	h.mu.Lock()
	if h.connected && h.inited && h.mac == mac {
		h.mu.Unlock()
		return nil
	}
	h.mu.Unlock()
	_ = h.Close()

	conn, err := dbus.ConnectSystemBus()
	if err != nil {
		return err
	}
	devPath, obj, err := findBluezDevice(conn, mac)
	if err != nil {
		conn.Close()
		return err
	}
	if !bluezPropBool(obj, "org.bluez.Device1", "Connected") {
		if call := obj.Call("org.bluez.Device1.Connect", 0); call.Err != nil {
			conn.Close()
			return fmt.Errorf("bluez connect: %w", call.Err)
		}
	}
	deadline := time.Now().Add(12 * time.Second)
	for time.Now().Before(deadline) {
		if bluezPropBool(obj, "org.bluez.Device1", "ServicesResolved") {
			break
		}
		time.Sleep(150 * time.Millisecond)
	}
	chars, err := mapGattChars(conn, devPath)
	if err != nil {
		_ = obj.Call("org.bluez.Device1.Disconnect", 0)
		conn.Close()
		return err
	}
	for _, need := range []string{uuidInitTX, uuidSuctionTX, uuidReleaseTX} {
		if chars[need] == "" {
			_ = obj.Call("org.bluez.Device1.Disconnect", 0)
			conn.Close()
			return fmt.Errorf("missing gatt char %s", need)
		}
	}

	h.mu.Lock()
	h.conn = conn
	h.mac = mac
	h.devPath = devPath
	h.chars = chars
	h.connected = true
	h.inited = false
	h.mu.Unlock()

	if err := h.writeUUID(uuidInitTX, []byte(powerblowInitPayload)); err != nil {
		_ = h.Close()
		return fmt.Errorf("powerblow init: %w", err)
	}
	// Soft hello: brief mid suction then release (FeelConnect connect cue).
	_ = h.Suction(0.55, 800*time.Millisecond)
	time.Sleep(200 * time.Millisecond)
	_ = h.Release()
	_ = h.SetColor(0x00, 0xC8, 0xFF, 0xA0)

	h.mu.Lock()
	h.inited = true
	h.mu.Unlock()
	h.refreshBattery()
	slog.Info("powerblow connected", "mac", mac)
	h.notify()
	return nil
}

func bluezPropBool(obj dbus.BusObject, iface, prop string) bool {
	var v dbus.Variant
	if err := obj.Call("org.freedesktop.DBus.Properties.Get", 0, iface, prop).Store(&v); err != nil {
		return false
	}
	b, _ := v.Value().(bool)
	return b
}

func findBluezDevice(conn *dbus.Conn, mac string) (string, dbus.BusObject, error) {
	suffix := "dev_" + strings.ReplaceAll(NormalizeMAC(mac), ":", "_")
	var managed map[dbus.ObjectPath]map[string]map[string]dbus.Variant
	obj := conn.Object("org.bluez", "/")
	if err := obj.Call("org.freedesktop.DBus.ObjectManager.GetManagedObjects", 0).Store(&managed); err != nil {
		return "", nil, err
	}
	for path, ifaces := range managed {
		if _, ok := ifaces["org.bluez.Device1"]; !ok {
			continue
		}
		if strings.HasSuffix(string(path), suffix) {
			return string(path), conn.Object("org.bluez", path), nil
		}
	}
	return "", nil, fmt.Errorf("bluez device %s not found — put PowerBlow in pairing mode near the server", mac)
}

func mapGattChars(conn *dbus.Conn, devPath string) (map[string]string, error) {
	var managed map[dbus.ObjectPath]map[string]map[string]dbus.Variant
	obj := conn.Object("org.bluez", "/")
	if err := obj.Call("org.freedesktop.DBus.ObjectManager.GetManagedObjects", 0).Store(&managed); err != nil {
		return nil, err
	}
	out := map[string]string{}
	prefix := devPath + "/"
	for path, ifaces := range managed {
		g, ok := ifaces["org.bluez.GattCharacteristic1"]
		if !ok {
			continue
		}
		if !strings.HasPrefix(string(path), prefix) {
			continue
		}
		uuidVar, ok := g["UUID"]
		if !ok {
			continue
		}
		uuid, _ := uuidVar.Value().(string)
		uuid = strings.ToLower(uuid)
		out[uuid] = string(path)
	}
	return out, nil
}

func (h *PowerBlowHub) writeUUID(uuid string, data []byte) error {
	h.mu.Lock()
	conn := h.conn
	path := h.chars[strings.ToLower(uuid)]
	h.mu.Unlock()
	if conn == nil || path == "" {
		return fmt.Errorf("not connected")
	}
	obj := conn.Object("org.bluez", dbus.ObjectPath(path))
	payload := make([]byte, len(data))
	copy(payload, data)
	// Write Command (no response) — matches FeelConnect / Web Bluetooth timing.
	opts := map[string]dbus.Variant{"type": dbus.MakeVariant("command")}
	call := obj.Call("org.bluez.GattCharacteristic1.WriteValue", 0, payload, opts)
	return call.Err
}

const (
	pbVentSettle = 40 * time.Millisecond  // brief valve settle before next suck
	pbPumpPulse  = 280 * time.Millisecond // on-wire pump YY cap
	pbVentPulse  = 120 * time.Millisecond // on-wire vent YY cap; suck waits this out
	pbWriteGap   = 30 * time.Millisecond  // gap so GATT writes cannot stack
)

// Suction writes FeelConnect-style intensity+duration to 0x1401.
// level is 0–1; duration clamped to protocol ticks (≈50ms each).
// Exclusive with Release — pump pulse completes before any vent can run.
func (h *PowerBlowHub) Suction(level float64, dur time.Duration) error {
	h.cmdMu.Lock()
	defer h.cmdMu.Unlock()

	if level < 0 {
		level = 0
	}
	if level > 1 {
		level = 1
	}
	// FeelConnect suction band 0x80–0xFF, linear with stroke level.
	xx := byte(0x80 + level*float64(0x7F))
	pump := dur
	if pump > pbPumpPulse {
		pump = pbPumpPulse
	}
	if pump < 50*time.Millisecond {
		pump = 50 * time.Millisecond
	}
	ticks := int(pump / (50 * time.Millisecond))
	if ticks < 1 {
		ticks = 1
	}
	if ticks > 255 {
		ticks = 255
	}
	yy := byte(ticks)

	h.mu.Lock()
	if h.pumping || h.vacuumOn {
		h.mu.Unlock()
		return nil
	}
	// Wait out on-device release pulse before pumping.
	waitVent := time.Until(h.ventUntil)
	waitSettle := pbVentSettle - time.Since(h.lastReleaseAt)
	h.mu.Unlock()
	if waitVent > 0 {
		time.Sleep(waitVent)
	}
	if waitSettle > 0 {
		time.Sleep(waitSettle)
	}
	h.mu.Lock()
	if h.pumping || h.vacuumOn {
		h.mu.Unlock()
		return nil
	}
	h.lastXX, h.lastYY = xx, yy
	h.vacuumOn = true
	h.pumping = true
	h.ventUntil = time.Time{}
	h.lastSuckAt = time.Now()
	h.pumpUntil = time.Now().Add(pump)
	h.mu.Unlock()

	err := h.writeUUID(uuidSuctionTX, []byte{xx, yy})
	h.mu.Lock()
	h.pumping = false
	h.mu.Unlock()
	time.Sleep(pbWriteGap)
	return err
}

func (h *PowerBlowHub) Release() error {
	return h.ReleaseFor(100 * time.Millisecond)
}

// ReleaseFor vents with YY derived from dur (capped), XX from last suck.
func (h *PowerBlowHub) ReleaseFor(dur time.Duration) error {
	h.cmdMu.Lock()
	defer h.cmdMu.Unlock()

	h.mu.Lock()
	if wait := time.Until(h.pumpUntil); wait > 0 {
		h.mu.Unlock()
		time.Sleep(wait)
		h.mu.Lock()
	}
	h.pumping = false
	h.pumpUntil = time.Time{}
	if !h.vacuumOn {
		h.mu.Unlock()
		return nil
	}
	xx := h.lastXX
	h.vacuumOn = false
	h.lastReleaseAt = time.Now()
	h.mu.Unlock()

	vent := dur
	if vent > pbVentPulse {
		vent = pbVentPulse
	}
	if vent < 50*time.Millisecond {
		vent = 50 * time.Millisecond
	}
	ticks := int(vent / (50 * time.Millisecond))
	if ticks < 1 {
		ticks = 1
	}
	if ticks > 255 {
		ticks = 255
	}
	h.mu.Lock()
	h.ventUntil = time.Now().Add(vent)
	h.mu.Unlock()
	err := h.writeUUID(uuidReleaseTX, []byte{xx, byte(ticks)})
	time.Sleep(pbWriteGap)
	return err
}

// ApplyStroke sends one FeelConnect-style suction or release event.
func (h *PowerBlowHub) ApplyStroke(st PowerBlowStroke) error {
	if !st.Suck {
		return h.ReleaseFor(st.Duration)
	}
	return h.Suction(st.Level, st.Duration)
}

// Constrict is used by Test / Halt: >0 short suck pulse, 0 release.
func (h *PowerBlowHub) Constrict(level float64) error {
	if level <= 0.02 {
		return h.Release()
	}
	return h.Suction(level, 900*time.Millisecond)
}

func (h *PowerBlowHub) SetColor(r, g, b, w byte) error {
	return h.writeUUID(uuidColorTX, []byte{r, g, b, w})
}

func (h *PowerBlowHub) refreshBattery() {
	h.mu.Lock()
	conn := h.conn
	path := h.chars[uuidBattery]
	h.mu.Unlock()
	if conn == nil || path == "" {
		return
	}
	obj := conn.Object("org.bluez", dbus.ObjectPath(path))
	var raw []byte
	if err := obj.Call("org.bluez.GattCharacteristic1.ReadValue", 0, map[string]dbus.Variant{}).Store(&raw); err != nil || len(raw) == 0 {
		return
	}
	pct := int(raw[0])
	h.mu.Lock()
	h.battery = &pct
	h.mu.Unlock()
}

func (h *PowerBlowHub) Battery() *int {
	h.refreshBattery()
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.battery == nil {
		return nil
	}
	pct := *h.battery
	return &pct
}

func (h *PowerBlowHub) Close() error {
	h.mu.Lock()
	conn := h.conn
	devPath := h.devPath
	was := h.connected
	h.conn = nil
	h.chars = map[string]string{}
	h.connected = false
	h.inited = false
	h.devPath = ""
	mac := h.mac
	h.mu.Unlock()
	if conn != nil && devPath != "" {
		obj := conn.Object("org.bluez", dbus.ObjectPath(devPath))
		_ = obj.Call("org.bluez.Device1.Disconnect", 0)
		_ = conn.Close()
	}
	if was {
		slog.Info("powerblow disconnected", "mac", mac)
		h.notify()
	}
	return nil
}
