package interactive

import (
	"encoding/json"
	"os"
	"path/filepath"
)

const (
	vortexConfigID         = "fe5abad1-978b-439a-a649-7698205613cb"
	vortexFeatureID        = "84d728dc-d71b-49da-ac77-ad94632807a2"
	vortexBatteryFeatureID = "b7e91c4a-2f55-4d8e-9c3a-1a0f6e8d2b41"
)

// EnsureUDCF writes a minimal user device config with Funplay Vortex constrict mapping.
func EnsureUDCF(path string) error {
	if path == "" {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	var root map[string]any
	if b, err := os.ReadFile(path); err == nil {
		_ = json.Unmarshal(b, &root)
	}
	if root == nil {
		root = map[string]any{}
	}
	if _, ok := root["version"]; !ok {
		root["version"] = map[string]any{"major": 4, "minor": 0}
	}
	user, _ := root["user_configs"].(map[string]any)
	if user == nil {
		user = map[string]any{}
		root["user_configs"] = user
	}
	protocols, _ := user["protocols"].(map[string]any)
	if protocols == nil {
		protocols = map[string]any{}
		user["protocols"] = protocols
	}
	protocols["sexverse-v1"] = vortexProto()
	b, err := json.MarshalIndent(root, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, b, 0o644)
}

func vortexProto() map[string]any {
	return map[string]any{
		"communication": []map[string]any{{
			"btle": map[string]any{
				"names": []string{"Funplay Vortex"},
				"services": map[string]any{
					"0000ffe0-0000-1000-8000-00805f9b34fb": map[string]any{
						"rx": "0000ffe2-0000-1000-8000-00805f9b34fb",
						"tx": "0000ffe1-0000-1000-8000-00805f9b34fb",
					},
					"0000180f-0000-1000-8000-00805f9b34fb": map[string]any{
						"rxblebattery": "00002a19-0000-1000-8000-00805f9b34fb",
					},
				},
			},
		}},
		"configurations": []map[string]any{{
			"id":         vortexConfigID,
			"identifier": []string{"Funplay Vortex"},
			"name":       "Funplay Vortex",
			"features": []map[string]any{
				{
					"id":          vortexFeatureID,
					"index":       0,
					"description": "Air Pump",
					"output":      map[string]any{"constrict": map[string]any{"value": []int{0, 100}}},
				},
				{
					"id":          vortexBatteryFeatureID,
					"index":       1,
					"description": "Battery Level",
					"input": map[string]any{
						"battery": map[string]any{
							"value":   [][]int{{0, 100}},
							"command": []string{"Read"},
						},
					},
				},
			},
		}},
	}
}
