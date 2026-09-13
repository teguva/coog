package interactive

import (
	"encoding/json"
	"log/slog"
	"os"
	"strings"
	"time"

	"coog/internal/store"
)

// MigrateFunplayState imports device prefs from Funplay library_state.json once.
func MigrateFunplayState(st *store.Store, path string) (int, error) {
	n, err := st.CountInteractiveDevices()
	if err != nil || n > 0 {
		return 0, err
	}
	b, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return 0, nil
		}
		return 0, err
	}
	var root struct {
		Devices map[string]map[string]any `json:"devices"`
	}
	if err := json.Unmarshal(b, &root); err != nil {
		return 0, err
	}
	imported := 0
	now := time.Now().Unix()
	for id, raw := range root.Devices {
		if !strings.HasPrefix(id, "name:") {
			name := strAny(raw["name"])
			if name == "" {
				continue
			}
			id = StableDeviceID(name)
		}
		d := store.InteractiveDevice{
			DeviceID:         id,
			Name:             strAny(raw["name"]),
			Kind:             strAny(raw["kind"]),
			Paired:           boolAny(raw["paired"]),
			Profile:          SanitizeProfile(strAny(raw["profile"])),
			Favorite:         boolAny(raw["favorite"]),
			OffsetMs:         intAny(raw["offset_ms"], 350),
			OffsetLinearMs:   intAny(raw["offset_linear_ms"], 350),
			Intensity:        ClampIntensity(intAny(raw["intensity"], 100)),
			LastConnectedAt:  int64(intAny(raw["last_connected_at"], 0)),
			BatterySupported: boolAny(raw["battery_supported"]),
			BatteryPercent:   intAny(raw["battery_percent"], -1),
			BatteryUpdatedAt: int64(intAny(raw["battery_updated_at"], 0)),
			BleName:          strAny(raw["ble_name"]),
			BleAddress:       strAny(raw["ble_address"]),
			UpdatedAt:        now,
		}
		if d.DeviceID == "" {
			continue
		}
		if d.Name == "" {
			d.Name = d.DeviceID
		}
		if d.Kind == "" {
			d.Kind = "scalar"
		}
		if err := st.UpsertInteractiveDevice(d); err != nil {
			slog.Warn("migrate device", "id", id, "err", err)
			continue
		}
		imported++
	}
	if imported > 0 {
		slog.Info("migrated funplay devices", "count", imported, "from", path)
	}
	return imported, nil
}

func strAny(v any) string {
	s, _ := v.(string)
	return s
}

func boolAny(v any) bool {
	b, ok := v.(bool)
	if ok {
		return b
	}
	return false
}

func intAny(v any, fallback int) int {
	switch n := v.(type) {
	case float64:
		return int(n)
	case int:
		return n
	case int64:
		return int(n)
	default:
		return fallback
	}
}
