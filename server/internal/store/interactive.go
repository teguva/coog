package store

import (
	"database/sql"
	"time"
)

type InteractiveDevice struct {
	DeviceID         string `json:"deviceId"`
	Name             string `json:"name"`
	Kind             string `json:"kind"`
	Paired           bool   `json:"paired"`
	Profile          string `json:"profile"`
	Favorite         bool   `json:"favorite"`
	OffsetMs         int    `json:"offsetMs"`
	OffsetLinearMs   int    `json:"offsetLinearMs"`
	Intensity        int    `json:"intensity"`
	LastConnectedAt  int64  `json:"lastConnectedAt"`
	BatterySupported bool   `json:"batterySupported"`
	BatteryPercent   int    `json:"batteryPercent"`
	BatteryUpdatedAt int64  `json:"batteryUpdatedAt"`
	BleName          string `json:"bleName"`
	BleAddress       string `json:"bleAddress"`
	UpdatedAt        int64  `json:"updatedAt"`
}

type InteractiveSyncSession struct {
	ID         string `json:"id"`
	MediaID    string `json:"mediaId"`
	StartedAt  int64  `json:"startedAt"`
	EndedAt    int64  `json:"endedAt"`
	LastPosMs  int64  `json:"lastPosMs"`
	ParamsJSON string `json:"paramsJson"`
}

type FunscriptCacheRow struct {
	MediaID     string
	SourcePath  string
	MtimeUnix   int64
	DurationMs  int
	ActionCount int
	Intensity   float64
	PreviewJSON string
	ActionsBlob []byte
}

func (s *Store) CountInteractiveDevices() (int, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM interactive_devices`).Scan(&n)
	return n, err
}

func (s *Store) ListInteractiveDevices() ([]InteractiveDevice, error) {
	rows, err := s.db.Query(`
SELECT device_id, name, kind, paired, profile, favorite, offset_ms, offset_linear_ms,
  intensity, last_connected_at, battery_supported, battery_percent, battery_updated_at,
  ble_name, ble_address, updated_at
FROM interactive_devices ORDER BY name`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]InteractiveDevice, 0)
	for rows.Next() {
		d, err := scanInteractiveDevice(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

func (s *Store) GetInteractiveDevice(id string) (InteractiveDevice, error) {
	row := s.db.QueryRow(`
SELECT device_id, name, kind, paired, profile, favorite, offset_ms, offset_linear_ms,
  intensity, last_connected_at, battery_supported, battery_percent, battery_updated_at,
  ble_name, ble_address, updated_at
FROM interactive_devices WHERE device_id = ?`, id)
	d, err := scanInteractiveDevice(row)
	if err == sql.ErrNoRows {
		return InteractiveDevice{}, ErrNotFound
	}
	return d, err
}

func (s *Store) UpsertInteractiveDevice(d InteractiveDevice) error {
	if d.UpdatedAt == 0 {
		d.UpdatedAt = time.Now().Unix()
	}
	if d.Kind == "" {
		d.Kind = "scalar"
	}
	if d.Profile == "" {
		d.Profile = "other"
	}
	if d.Intensity == 0 {
		d.Intensity = 100
	}
	if d.OffsetMs == 0 {
		d.OffsetMs = 350
	}
	if d.OffsetLinearMs == 0 {
		d.OffsetLinearMs = d.OffsetMs
	}
	_, err := s.db.Exec(`
INSERT INTO interactive_devices (
  device_id, name, kind, paired, profile, favorite, offset_ms, offset_linear_ms,
  intensity, last_connected_at, battery_supported, battery_percent, battery_updated_at,
  ble_name, ble_address, updated_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(device_id) DO UPDATE SET
  name=excluded.name, kind=excluded.kind, paired=excluded.paired, profile=excluded.profile,
  favorite=excluded.favorite, offset_ms=excluded.offset_ms, offset_linear_ms=excluded.offset_linear_ms,
  intensity=excluded.intensity, last_connected_at=excluded.last_connected_at,
  battery_supported=excluded.battery_supported, battery_percent=excluded.battery_percent,
  battery_updated_at=excluded.battery_updated_at, ble_name=excluded.ble_name,
  ble_address=excluded.ble_address, updated_at=excluded.updated_at
`, d.DeviceID, d.Name, d.Kind, boolInt(d.Paired), d.Profile, boolInt(d.Favorite),
		d.OffsetMs, d.OffsetLinearMs, d.Intensity, d.LastConnectedAt, boolInt(d.BatterySupported),
		d.BatteryPercent, d.BatteryUpdatedAt, d.BleName, d.BleAddress, d.UpdatedAt)
	return err
}

func (s *Store) DeleteInteractiveDevice(id string) error {
	_, err := s.db.Exec(`DELETE FROM interactive_devices WHERE device_id = ?`, id)
	return err
}

func (s *Store) DeleteOfflineInteractiveDevices(connectedIDs []string) (int64, error) {
	if len(connectedIDs) == 0 {
		res, err := s.db.Exec(`DELETE FROM interactive_devices WHERE paired = 1`)
		if err != nil {
			return 0, err
		}
		return res.RowsAffected()
	}
	// Delete paired devices not in connected set — done in Go for simplicity.
	all, err := s.ListInteractiveDevices()
	if err != nil {
		return 0, err
	}
	live := map[string]struct{}{}
	for _, id := range connectedIDs {
		live[id] = struct{}{}
	}
	var n int64
	for _, d := range all {
		if !d.Paired {
			continue
		}
		if _, ok := live[d.DeviceID]; ok {
			continue
		}
		if err := s.DeleteInteractiveDevice(d.DeviceID); err != nil {
			return n, err
		}
		n++
	}
	return n, nil
}

func (s *Store) InsertSyncSession(sess InteractiveSyncSession) error {
	_, err := s.db.Exec(`
INSERT INTO interactive_sync_sessions (id, media_id, started_at, ended_at, last_pos_ms, params_json)
VALUES (?, ?, ?, ?, ?, ?)`,
		sess.ID, sess.MediaID, sess.StartedAt, sess.EndedAt, sess.LastPosMs, sess.ParamsJSON)
	return err
}

func (s *Store) EndSyncSession(id string, lastPosMs int64) error {
	_, err := s.db.Exec(`
UPDATE interactive_sync_sessions SET ended_at = ?, last_pos_ms = ? WHERE id = ?`,
		time.Now().Unix(), lastPosMs, id)
	return err
}

func (s *Store) GetFunscriptCache(mediaID string) (FunscriptCacheRow, error) {
	var r FunscriptCacheRow
	err := s.db.QueryRow(`
SELECT media_id, source_path, mtime_unix, duration_ms, action_count, intensity, preview_json, actions_blob
FROM funscript_cache WHERE media_id = ?`, mediaID).Scan(
		&r.MediaID, &r.SourcePath, &r.MtimeUnix, &r.DurationMs, &r.ActionCount, &r.Intensity, &r.PreviewJSON, &r.ActionsBlob,
	)
	if err == sql.ErrNoRows {
		return FunscriptCacheRow{}, ErrNotFound
	}
	return r, err
}

func (s *Store) UpsertFunscriptCache(r FunscriptCacheRow) error {
	_, err := s.db.Exec(`
INSERT INTO funscript_cache (
  media_id, source_path, mtime_unix, duration_ms, action_count, intensity, preview_json, actions_blob
) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(media_id) DO UPDATE SET
  source_path=excluded.source_path, mtime_unix=excluded.mtime_unix,
  duration_ms=excluded.duration_ms, action_count=excluded.action_count,
  intensity=excluded.intensity, preview_json=excluded.preview_json,
  actions_blob=excluded.actions_blob
`, r.MediaID, r.SourcePath, r.MtimeUnix, r.DurationMs, r.ActionCount, r.Intensity, r.PreviewJSON, r.ActionsBlob)
	return err
}

type scannable interface {
	Scan(dest ...any) error
}

func scanInteractiveDevice(row scannable) (InteractiveDevice, error) {
	var d InteractiveDevice
	var paired, fav, battSupp int
	err := row.Scan(
		&d.DeviceID, &d.Name, &d.Kind, &paired, &d.Profile, &fav, &d.OffsetMs, &d.OffsetLinearMs,
		&d.Intensity, &d.LastConnectedAt, &battSupp, &d.BatteryPercent, &d.BatteryUpdatedAt,
		&d.BleName, &d.BleAddress, &d.UpdatedAt,
	)
	d.Paired = paired != 0
	d.Favorite = fav != 0
	d.BatterySupported = battSupp != 0
	return d, err
}

func boolInt(v bool) int {
	if v {
		return 1
	}
	return 0
}
