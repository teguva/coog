package store

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	_ "modernc.org/sqlite"
)

var ErrNotFound = errors.New("not found")

type MediaItem struct {
	ID           string          `json:"id"`
	Kind         string          `json:"kind"`
	Title        string          `json:"title"`
	Year         int             `json:"year,omitempty"`
	Season       int             `json:"season,omitempty"`
	Episode      int             `json:"episode,omitempty"`
	ShowTitle    string          `json:"showTitle,omitempty"`
	Path         string          `json:"path"`
	RelativePath string          `json:"relativePath"`
	SizeBytes    int64           `json:"sizeBytes"`
	MtimeUnix    int64           `json:"mtimeUnix"`
	DurationMs   int64           `json:"durationMs,omitempty"`
	Probe        json.RawMessage `json:"probe,omitempty"`
	CodecVideo   string          `json:"codecVideo,omitempty"`
	CodecAudio   string          `json:"codecAudio,omitempty"`
	Width        int             `json:"width,omitempty"`
	Height       int             `json:"height,omitempty"`
	HDR          string          `json:"hdr,omitempty"`
	ContentType  string          `json:"contentType,omitempty"`
	UpdatedAt    int64           `json:"updatedAt"`
}

type Store struct {
	db *sql.DB
}

func Open(path string) (*Store, error) {
	db, err := sql.Open("sqlite", path+"?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)&_pragma=foreign_keys(ON)")
	if err != nil {
		return nil, err
	}
	s := &Store{db: db}
	if err := s.migrate(); err != nil {
		_ = db.Close()
		return nil, err
	}
	return s, nil
}

func (s *Store) Close() error {
	return s.db.Close()
}

func (s *Store) migrate() error {
	_, err := s.db.Exec(`
CREATE TABLE IF NOT EXISTS media_items (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  title TEXT NOT NULL,
  year INTEGER NOT NULL DEFAULT 0,
  season INTEGER NOT NULL DEFAULT 0,
  episode INTEGER NOT NULL DEFAULT 0,
  show_title TEXT NOT NULL DEFAULT '',
  path TEXT NOT NULL UNIQUE,
  relative_path TEXT NOT NULL,
  size_bytes INTEGER NOT NULL DEFAULT 0,
  mtime_unix INTEGER NOT NULL DEFAULT 0,
  duration_ms INTEGER NOT NULL DEFAULT 0,
  probe_json TEXT NOT NULL DEFAULT '{}',
  codec_video TEXT NOT NULL DEFAULT '',
  codec_audio TEXT NOT NULL DEFAULT '',
  width INTEGER NOT NULL DEFAULT 0,
  height INTEGER NOT NULL DEFAULT 0,
  hdr TEXT NOT NULL DEFAULT '',
  content_type TEXT NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_media_kind ON media_items(kind);
CREATE INDEX IF NOT EXISTS idx_media_title ON media_items(title);

CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS tokens (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT,
  label TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS jobs (
  id TEXT PRIMARY KEY,
  type TEXT NOT NULL DEFAULT 'ytdlp',
  url TEXT NOT NULL DEFAULT '',
  title TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'queued',
  progress REAL NOT NULL DEFAULT 0,
  ready INTEGER NOT NULL DEFAULT 0,
  expected_duration_ms INTEGER NOT NULL DEFAULT 0,
  buffered_ms INTEGER NOT NULL DEFAULT 0,
  error TEXT NOT NULL DEFAULT '',
  work_dir TEXT NOT NULL DEFAULT '',
  output_path TEXT NOT NULL DEFAULT '',
  media_id TEXT NOT NULL DEFAULT '',
  year INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_jobs_status ON jobs(status, created_at);
`)
	if err != nil {
		return err
	}
	_, _ = s.db.Exec(`ALTER TABLE jobs ADD COLUMN imdb_id TEXT NOT NULL DEFAULT ''`)
	_, _ = s.db.Exec(`ALTER TABLE jobs ADD COLUMN log_tail TEXT NOT NULL DEFAULT ''`)
	_, _ = s.db.Exec(`ALTER TABLE jobs ADD COLUMN info_hash TEXT NOT NULL DEFAULT ''`)
	_, err = s.db.Exec(`
CREATE TABLE IF NOT EXISTS worker_heartbeat (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  updated_at INTEGER NOT NULL,
  pid INTEGER NOT NULL DEFAULT 0
);`)
	if err != nil {
		return err
	}
	_, err = s.db.Exec(`
CREATE TABLE IF NOT EXISTS continue_watching (
  entry_key TEXT PRIMARY KEY,
  imdb_id TEXT NOT NULL DEFAULT '',
  tmdb_id INTEGER NOT NULL DEFAULT 0,
  kind TEXT NOT NULL,
  title TEXT NOT NULL DEFAULT '',
  year INTEGER NOT NULL DEFAULT 0,
  season INTEGER NOT NULL DEFAULT 0,
  episode INTEGER NOT NULL DEFAULT 0,
  position_ms INTEGER NOT NULL DEFAULT 0,
  duration_ms INTEGER NOT NULL DEFAULT 0,
  media_id TEXT NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_continue_updated ON continue_watching(updated_at DESC);
`)
	if err != nil {
		return err
	}
	_, err = s.db.Exec(`
CREATE TABLE IF NOT EXISTS taste_profile (
  id TEXT PRIMARY KEY,
  fingerprint TEXT NOT NULL DEFAULT '',
  titles INTEGER NOT NULL DEFAULT 0,
  payload TEXT NOT NULL DEFAULT '{}',
  updated_at INTEGER NOT NULL
);
`)
	if err != nil {
		return err
	}
	_, err = s.db.Exec(`
CREATE TABLE IF NOT EXISTS interactive_devices (
  device_id TEXT PRIMARY KEY,
  name TEXT NOT NULL DEFAULT '',
  kind TEXT NOT NULL DEFAULT 'scalar',
  paired INTEGER NOT NULL DEFAULT 0,
  profile TEXT NOT NULL DEFAULT 'other',
  favorite INTEGER NOT NULL DEFAULT 0,
  offset_ms INTEGER NOT NULL DEFAULT 350,
  offset_linear_ms INTEGER NOT NULL DEFAULT 350,
  intensity INTEGER NOT NULL DEFAULT 100,
  last_connected_at INTEGER NOT NULL DEFAULT 0,
  battery_supported INTEGER NOT NULL DEFAULT 0,
  battery_percent INTEGER NOT NULL DEFAULT -1,
  battery_updated_at INTEGER NOT NULL DEFAULT 0,
  ble_name TEXT NOT NULL DEFAULT '',
  ble_address TEXT NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS interactive_sync_sessions (
  id TEXT PRIMARY KEY,
  media_id TEXT NOT NULL,
  started_at INTEGER NOT NULL,
  ended_at INTEGER NOT NULL DEFAULT 0,
  last_pos_ms INTEGER NOT NULL DEFAULT 0,
  params_json TEXT NOT NULL DEFAULT '{}'
);
CREATE TABLE IF NOT EXISTS funscript_cache (
  media_id TEXT PRIMARY KEY,
  source_path TEXT NOT NULL,
  mtime_unix INTEGER NOT NULL,
  duration_ms INTEGER NOT NULL DEFAULT 0,
  action_count INTEGER NOT NULL DEFAULT 0,
  intensity REAL NOT NULL DEFAULT 0,
  preview_json TEXT NOT NULL DEFAULT '',
  actions_blob BLOB
);
`)
	return err
}

func (s *Store) UpsertMedia(item MediaItem) error {
	if item.UpdatedAt == 0 {
		item.UpdatedAt = time.Now().Unix()
	}
	probe := string(item.Probe)
	if probe == "" {
		probe = "{}"
	}
	_, err := s.db.Exec(`
INSERT INTO media_items (
  id, kind, title, year, season, episode, show_title, path, relative_path,
  size_bytes, mtime_unix, duration_ms, probe_json, codec_video, codec_audio,
  width, height, hdr, content_type, updated_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(id) DO UPDATE SET
  kind=excluded.kind, title=excluded.title, year=excluded.year,
  season=excluded.season, episode=excluded.episode, show_title=excluded.show_title,
  path=excluded.path, relative_path=excluded.relative_path,
  size_bytes=excluded.size_bytes, mtime_unix=excluded.mtime_unix,
  duration_ms=excluded.duration_ms, probe_json=excluded.probe_json,
  codec_video=excluded.codec_video, codec_audio=excluded.codec_audio,
  width=excluded.width, height=excluded.height, hdr=excluded.hdr,
  content_type=excluded.content_type, updated_at=excluded.updated_at
`,
		item.ID, item.Kind, item.Title, item.Year, item.Season, item.Episode, item.ShowTitle,
		item.Path, item.RelativePath, item.SizeBytes, item.MtimeUnix, item.DurationMs, probe,
		item.CodecVideo, item.CodecAudio, item.Width, item.Height, item.HDR, item.ContentType, item.UpdatedAt,
	)
	return err
}

func (s *Store) GetMedia(id string) (MediaItem, error) {
	return scanItem(s.db.QueryRow(`SELECT `+itemCols+` FROM media_items WHERE id = ?`, id))
}

func (s *Store) ListMedia() ([]MediaItem, error) {
	rows, err := s.db.Query(`SELECT ` + itemCols + ` FROM media_items ORDER BY kind, show_title, season, episode, title`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []MediaItem
	for rows.Next() {
		item, err := scanItem(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, item)
	}
	if out == nil {
		out = []MediaItem{}
	}
	return out, rows.Err()
}

func (s *Store) KnownByPath() (map[string]MediaItem, error) {
	rows, err := s.db.Query(`SELECT ` + itemCols + ` FROM media_items`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]MediaItem{}
	for rows.Next() {
		item, err := scanItem(rows)
		if err != nil {
			return nil, err
		}
		out[item.Path] = item
	}
	return out, rows.Err()
}

func (s *Store) DeleteMedia(id string) error {
	res, err := s.db.Exec(`DELETE FROM media_items WHERE id = ?`, id)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *Store) DeleteMissing(keepIDs []string) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	if _, err := tx.Exec(`DROP TABLE IF EXISTS keep_ids`); err != nil {
		return err
	}
	if _, err := tx.Exec(`CREATE TEMP TABLE keep_ids (id TEXT PRIMARY KEY)`); err != nil {
		return err
	}
	for _, id := range keepIDs {
		if _, err := tx.Exec(`INSERT OR IGNORE INTO keep_ids(id) VALUES (?)`, id); err != nil {
			return err
		}
	}
	if _, err := tx.Exec(`DELETE FROM media_items WHERE id NOT IN (SELECT id FROM keep_ids)`); err != nil {
		return err
	}
	if _, err := tx.Exec(`DROP TABLE keep_ids`); err != nil {
		return err
	}
	return tx.Commit()
}

const itemCols = `id, kind, title, year, season, episode, show_title, path, relative_path,
  size_bytes, mtime_unix, duration_ms, probe_json, codec_video, codec_audio,
  width, height, hdr, content_type, updated_at`

type rowScanner interface {
	Scan(dest ...any) error
}

func scanItem(row rowScanner) (MediaItem, error) {
	var item MediaItem
	var probe string
	err := row.Scan(
		&item.ID, &item.Kind, &item.Title, &item.Year, &item.Season, &item.Episode, &item.ShowTitle,
		&item.Path, &item.RelativePath, &item.SizeBytes, &item.MtimeUnix, &item.DurationMs, &probe,
		&item.CodecVideo, &item.CodecAudio, &item.Width, &item.Height, &item.HDR, &item.ContentType, &item.UpdatedAt,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return MediaItem{}, ErrNotFound
	}
	if err != nil {
		return MediaItem{}, err
	}
	if probe != "" {
		item.Probe = json.RawMessage(probe)
	}
	return item, nil
}

func (s *Store) Ping() error {
	return s.db.Ping()
}

func (s *Store) Stats() (mediaCount int, err error) {
	err = s.db.QueryRow(`SELECT COUNT(*) FROM media_items`).Scan(&mediaCount)
	if err != nil {
		return 0, fmt.Errorf("count media: %w", err)
	}
	return mediaCount, nil
}
