package interactive

import (
	"context"
	"log/slog"
	"os/exec"
	"regexp"
	"strings"
	"time"
)

var (
	reBluezObjectPath   = regexp.MustCompile(`/org/bluez/hci\d+/dev_([0-9A-Fa-f]{2}(?:_[0-9A-Fa-f]{2}){5})`)
	reEngineDeviceAdded = regexp.MustCompile(`Device Added:\s*\d+\s*-\s*(.+?)\s*-\s*UserDeviceIdentifier`)
	// e.g. Device Lovense Gush 2 (PeripheralId(DeviceId { object_path: Path("/org/bluez/hci0/dev_54_73_A0_45_FB_84\0") })) found.
	reEngineBleFound    = regexp.MustCompile(`Device\s+(.+?)\s+\(PeripheralId\(DeviceId\s*\{\s*object_path:\s*Path\("(/org/bluez/hci\d+/dev_[^"\\]+)`)
	reEngineDeviceName  = regexp.MustCompile(`device creation\{name=([^ \}]+)`)
	reDeviceConnectFail = regexp.MustCompile(`(?i)Device errored while trying to connect|HardwareSpecificError|DeviceSpecificError`)
	reBluetoothctlDev   = regexp.MustCompile(`(?i)^Device\s+([0-9A-F:]{17})\s+(.+)$`)
)

// MacFromObjectPath turns a BlueZ path into AA:BB:CC:DD:EE:FF.
func MacFromObjectPath(text string) string {
	m := reBluezObjectPath.FindStringSubmatch(text)
	if m == nil {
		return ""
	}
	return strings.ToUpper(strings.ReplaceAll(m[1], "_", ":"))
}

func NormalizeMAC(mac string) string {
	mac = strings.TrimSpace(mac)
	if mac == "" {
		return ""
	}
	if pathMac := MacFromObjectPath(mac); pathMac != "" {
		return pathMac
	}
	return strings.ToUpper(mac)
}

func bluezDisconnect(mac string) {
	mac = NormalizeMAC(mac)
	if mac == "" {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "bluetoothctl", "disconnect", mac)
	_ = cmd.Run()
	slog.Info("bluez disconnect", "mac", mac)
}

func bluezRemove(mac string) {
	mac = NormalizeMAC(mac)
	if mac == "" {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "bluetoothctl", "remove", mac)
	_ = cmd.Run()
	slog.Info("bluez remove", "mac", mac)
}

// bluezFindMAC looks up a device MAC via `bluetoothctl devices` using name hints.
func bluezFindMAC(hints ...string) string {
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, "bluetoothctl", "devices").Output()
	if err != nil {
		return ""
	}
	normalized := make([]string, 0, len(hints))
	for _, h := range hints {
		h = strings.ToLower(strings.TrimSpace(h))
		if h != "" {
			normalized = append(normalized, h)
		}
	}
	for _, line := range strings.Split(string(out), "\n") {
		m := reBluetoothctlDev.FindStringSubmatch(strings.TrimSpace(line))
		if m == nil {
			continue
		}
		mac, name := m[1], m[2]
		nameLower := strings.ToLower(name)
		for _, h := range normalized {
			if h == "lovense" {
				continue
			}
			if nameLower == h {
				return strings.ToUpper(mac)
			}
			if bleNameMatchesDevice(name, StableDeviceID(h), h, "") {
				return strings.ToUpper(mac)
			}
		}
	}
	return ""
}

func parseEngineLogLine(line string) (bleName, objectPath string, ok bool) {
	if m := reEngineDeviceAdded.FindStringSubmatch(line); m != nil {
		name := strings.TrimSpace(m[1])
		obj := reBluezObjectPath.FindString(line)
		if name != "" && obj != "" {
			return name, obj, true
		}
	}
	if m := reEngineBleFound.FindStringSubmatch(line); m != nil {
		return strings.TrimSpace(m[1]), m[2], true
	}
	return "", "", false
}

func engineDeviceNameFromLog(line string) string {
	if m := reEngineDeviceName.FindStringSubmatch(line); m != nil {
		return strings.TrimSpace(m[1])
	}
	return ""
}
