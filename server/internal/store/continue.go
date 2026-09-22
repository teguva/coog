package store

import (
	"strings"
	"time"
)

type ContinueEntry struct {
	Key        string `json:"key"`
	ImdbID     string `json:"imdbId"`
	TmdbID     int    `json:"tmdbId"`
	Kind       string `json:"kind"`
	Title      string `json:"title"`
	Year       int    `json:"year"`
	Season     int    `json:"season"`
	Episode    int    `json:"episode"`
	PositionMs int64  `json:"positionMs"`
	DurationMs int64  `json:"durationMs"`
	MediaID    string `json:"mediaId"`
	UpdatedAt  int64  `json:"updatedAt"`
}

func (s *Store) UpsertContinue(entry ContinueEntry) error {
	if entry.UpdatedAt == 0 {
		entry.UpdatedAt = time.Now().Unix()
	}
	_, err := s.db.Exec(`
INSERT INTO continue_watching (
  entry_key, imdb_id, tmdb_id, kind, title, year, season, episode,
  position_ms, duration_ms, media_id, updated_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(entry_key) DO UPDATE SET
  imdb_id = excluded.imdb_id,
  tmdb_id = excluded.tmdb_id,
  kind = excluded.kind,
  title = excluded.title,
  year = excluded.year,
  season = excluded.season,
  episode = excluded.episode,
  position_ms = excluded.position_ms,
  duration_ms = excluded.duration_ms,
  media_id = excluded.media_id,
  updated_at = excluded.updated_at
`, entry.Key, entry.ImdbID, entry.TmdbID, entry.Kind, entry.Title, entry.Year, entry.Season, entry.Episode,
		entry.PositionMs, entry.DurationMs, entry.MediaID, entry.UpdatedAt)
	if err != nil {
		return err
	}
	_, _ = s.db.Exec(`DELETE FROM continue_watching WHERE entry_key NOT IN (
		SELECT entry_key FROM continue_watching ORDER BY updated_at DESC LIMIT 40
	)`)
	return nil
}

func (s *Store) DeleteContinue(key string) error {
	_, err := s.db.Exec(`DELETE FROM continue_watching WHERE entry_key = ?`, key)
	return err
}

func (s *Store) DeleteContinueByMediaIDs(ids []string) error {
	if len(ids) == 0 {
		return nil
	}
	placeholders := strings.Repeat("?,", len(ids))
	placeholders = placeholders[:len(placeholders)-1]
	args := make([]any, len(ids))
	for i, id := range ids {
		args[i] = id
	}
	_, err := s.db.Exec(`DELETE FROM continue_watching WHERE media_id IN (`+placeholders+`)`, args...)
	return err
}

func (s *Store) ListContinue() ([]ContinueEntry, error) {
	rows, err := s.db.Query(`
SELECT entry_key, imdb_id, tmdb_id, kind, title, year, season, episode,
       position_ms, duration_ms, media_id, updated_at
FROM continue_watching
ORDER BY updated_at DESC
LIMIT 40`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []ContinueEntry{}
	for rows.Next() {
		var e ContinueEntry
		if err := rows.Scan(
			&e.Key, &e.ImdbID, &e.TmdbID, &e.Kind, &e.Title, &e.Year, &e.Season, &e.Episode,
			&e.PositionMs, &e.DurationMs, &e.MediaID, &e.UpdatedAt,
		); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}
