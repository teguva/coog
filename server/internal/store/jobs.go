package store

import (
	"database/sql"
	"encoding/json"
	"errors"
	"regexp"
	"strconv"
	"strings"
	"time"
)

type Job struct {
	ID                 string   `json:"id"`
	Type               string   `json:"type"`
	URL                string   `json:"url"`
	Title              string   `json:"title"`
	Status             string   `json:"status"`
	Progress           float64  `json:"progress"`
	Ready              bool     `json:"ready"`
	ExpectedDurationMs int64    `json:"expectedDurationMs,omitempty"`
	BufferedMs         int64    `json:"bufferedMs,omitempty"`
	Error              string   `json:"error,omitempty"`
	WorkDir            string   `json:"workDir,omitempty"`
	OutputPath         string   `json:"outputPath,omitempty"`
	MediaID            string   `json:"mediaId,omitempty"`
	ImdbID             string   `json:"imdbId,omitempty"`
	Year               int      `json:"year,omitempty"`
	LogTail            string   `json:"logTail,omitempty"`
	InfoHash           string   `json:"infoHash,omitempty"`
	Quality            string   `json:"quality,omitempty"`
	SizeBytes          int64    `json:"sizeBytes,omitempty"`
	SizeLabel          string   `json:"sizeLabel,omitempty"`
	Pack               string   `json:"pack,omitempty"`
	Tags               []string `json:"tags,omitempty"`
	Languages          []string `json:"languages,omitempty"`
	ReleaseTitle       string   `json:"releaseTitle,omitempty"`
	CreatedAt          int64    `json:"createdAt"`
	UpdatedAt          int64    `json:"updatedAt"`
}

const jobCols = `id, type, url, title, status, progress, ready, expected_duration_ms, buffered_ms,
  error, work_dir, output_path, media_id, imdb_id, year, log_tail, info_hash,
  quality, size_bytes, size_label, pack, tags_json, languages_json, release_title,
  created_at, updated_at`

func (s *Store) InsertJob(job Job) error {
	now := time.Now().Unix()
	if job.CreatedAt == 0 {
		job.CreatedAt = now
	}
	job.UpdatedAt = now
	_, err := s.db.Exec(`
INSERT INTO jobs (
  id, type, url, title, status, progress, ready, expected_duration_ms, buffered_ms,
  error, work_dir, output_path, media_id, imdb_id, year, log_tail, info_hash,
  quality, size_bytes, size_label, pack, tags_json, languages_json, release_title,
  created_at, updated_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		job.ID, job.Type, job.URL, job.Title, job.Status, job.Progress, boolToInt(job.Ready),
		job.ExpectedDurationMs, job.BufferedMs, job.Error, job.WorkDir, job.OutputPath,
		job.MediaID, job.ImdbID, job.Year, job.LogTail, job.InfoHash,
		job.Quality, job.SizeBytes, job.SizeLabel, job.Pack, encodeStringList(job.Tags), encodeStringList(job.Languages), job.ReleaseTitle,
		job.CreatedAt, job.UpdatedAt,
	)
	return err
}

func (s *Store) GetJob(id string) (Job, error) {
	return scanJob(s.db.QueryRow(`SELECT `+jobCols+` FROM jobs WHERE id = ?`, id))
}

func (s *Store) ListJobs() ([]Job, error) {
	rows, err := s.db.Query(`SELECT ` + jobCols + ` FROM jobs ORDER BY created_at DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Job{}
	for rows.Next() {
		job, err := scanJob(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, job)
	}
	return out, rows.Err()
}

func (s *Store) DeleteJob(id string) error {
	res, err := s.db.Exec(`DELETE FROM jobs WHERE id = ?`, id)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *Store) FindActiveJobByIMDB(imdb string, season, episode int) (Job, error) {
	imdb = strings.ToLower(strings.TrimSpace(imdb))
	if imdb == "" {
		return Job{}, ErrNotFound
	}
	rows, err := s.db.Query(
		`SELECT `+jobCols+` FROM jobs WHERE lower(imdb_id) = ? AND status IN ('queued','downloading','ready','paused') ORDER BY created_at DESC`,
		imdb,
	)
	if err != nil {
		return Job{}, err
	}
	defer rows.Close()
	for rows.Next() {
		job, err := scanJob(rows)
		if err != nil {
			return Job{}, err
		}
		js, je := JobSeasonEpisode(job)
		if season > 0 || episode > 0 {
			if js == season && je == episode {
				return job, nil
			}
			continue
		}
		// Movie / bare title: only match jobs that are not episode-scoped.
		if js == 0 && je == 0 {
			return job, nil
		}
	}
	if err := rows.Err(); err != nil {
		return Job{}, err
	}
	return Job{}, ErrNotFound
}

// JobSeasonEpisode reads S/E from imdb:tt:season:episode job URLs, then title markers.
func JobSeasonEpisode(job Job) (season, episode int) {
	ref := strings.TrimSpace(job.URL)
	if rest, ok := strings.CutPrefix(strings.ToLower(ref), "imdb:"); ok {
		parts := strings.Split(rest, ":")
		if len(parts) >= 3 {
			season, _ = strconv.Atoi(strings.TrimSpace(parts[1]))
			episode, _ = strconv.Atoi(strings.TrimSpace(parts[2]))
			if season > 0 || episode > 0 {
				return season, episode
			}
		}
	}
	return episodeMarkersFromText(job.Title)
}

func episodeMarkersFromText(text string) (season, episode int) {
	re := regexp.MustCompile(`(?i)(?:^|[^a-z0-9])s(\d{1,2})e(\d{1,3})(?:[^a-z0-9]|$)`)
	if m := re.FindStringSubmatch(text); len(m) == 3 {
		season, _ = strconv.Atoi(m[1])
		episode, _ = strconv.Atoi(m[2])
	}
	return season, episode
}

func (s *Store) FindActiveJobByHash(hash string) (Job, error) {
	hash = strings.ToLower(strings.TrimSpace(hash))
	if hash == "" {
		return Job{}, ErrNotFound
	}
	return scanJob(s.db.QueryRow(`SELECT `+jobCols+` FROM jobs WHERE lower(info_hash) = ? AND status IN ('queued','downloading','ready','paused') ORDER BY created_at DESC LIMIT 1`, hash))
}

func (s *Store) UpdateJob(job Job) error {
	job.UpdatedAt = time.Now().Unix()
	query := `
UPDATE jobs SET
  type=?, url=?, title=?, status=?, progress=?, ready=?, expected_duration_ms=?, buffered_ms=?,
  error=?, work_dir=?, output_path=?, media_id=?, imdb_id=?, year=?, log_tail=?, info_hash=?,
  quality=?, size_bytes=?, size_label=?, pack=?, tags_json=?, languages_json=?, release_title=?,
  updated_at=?
WHERE id=?`
	args := []any{
		job.Type, job.URL, job.Title, job.Status, job.Progress, boolToInt(job.Ready),
		job.ExpectedDurationMs, job.BufferedMs, job.Error, job.WorkDir, job.OutputPath,
		job.MediaID, job.ImdbID, job.Year, job.LogTail, job.InfoHash,
		job.Quality, job.SizeBytes, job.SizeLabel, job.Pack, encodeStringList(job.Tags), encodeStringList(job.Languages), job.ReleaseTitle,
		job.UpdatedAt, job.ID,
	}
	// Ignore worker progress writes after cancel/pause, but allow retry (queued) and those states themselves.
	if job.Status != "queued" && job.Status != "cancelled" && job.Status != "paused" {
		query += ` AND status NOT IN ('cancelled','paused')`
	}
	res, err := s.db.Exec(query, args...)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		cur, getErr := s.GetJob(job.ID)
		if getErr != nil {
			return ErrNotFound
		}
		if cur.Status == "cancelled" || cur.Status == "paused" {
			return nil
		}
		return ErrNotFound
	}
	return nil
}

func (s *Store) ClaimNextJob() (Job, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return Job{}, err
	}
	defer func() { _ = tx.Rollback() }()

	var id string
	err = tx.QueryRow(`SELECT id FROM jobs WHERE status = 'queued' ORDER BY created_at ASC LIMIT 1`).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return Job{}, ErrNotFound
	}
	if err != nil {
		return Job{}, err
	}
	now := time.Now().Unix()
	res, err := tx.Exec(`UPDATE jobs SET status = 'downloading', updated_at = ? WHERE id = ? AND status = 'queued'`, now, id)
	if err != nil {
		return Job{}, err
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return Job{}, ErrNotFound
	}
	if err := tx.Commit(); err != nil {
		return Job{}, err
	}
	return s.GetJob(id)
}

func (s *Store) RequeueDownloading() error {
	_, err := s.db.Exec(`UPDATE jobs SET status = 'queued', updated_at = ? WHERE status = 'downloading'`, time.Now().Unix())
	return err
}

func scanJob(row rowScanner) (Job, error) {
	var job Job
	var ready int
	var tagsJSON, langsJSON string
	err := row.Scan(
		&job.ID, &job.Type, &job.URL, &job.Title, &job.Status, &job.Progress, &ready,
		&job.ExpectedDurationMs, &job.BufferedMs, &job.Error, &job.WorkDir, &job.OutputPath,
		&job.MediaID, &job.ImdbID, &job.Year, &job.LogTail, &job.InfoHash,
		&job.Quality, &job.SizeBytes, &job.SizeLabel, &job.Pack, &tagsJSON, &langsJSON, &job.ReleaseTitle,
		&job.CreatedAt, &job.UpdatedAt,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return Job{}, ErrNotFound
	}
	if err != nil {
		return Job{}, err
	}
	job.Ready = ready != 0
	job.Tags = decodeStringList(tagsJSON)
	job.Languages = decodeStringList(langsJSON)
	return job, nil
}

func encodeStringList(v []string) string {
	if len(v) == 0 {
		return "[]"
	}
	b, err := json.Marshal(v)
	if err != nil {
		return "[]"
	}
	return string(b)
}

func decodeStringList(raw string) []string {
	raw = strings.TrimSpace(raw)
	if raw == "" || raw == "[]" {
		return nil
	}
	var out []string
	if json.Unmarshal([]byte(raw), &out) != nil {
		return nil
	}
	return out
}

func boolToInt(v bool) int {
	if v {
		return 1
	}
	return 0
}

type JobCounts struct {
	Queued      int `json:"queued"`
	Downloading int `json:"downloading"`
	Ready       int `json:"ready"`
	Finished    int `json:"finished"`
	Error       int `json:"error"`
	Cancelled   int `json:"cancelled"`
	Paused      int `json:"paused"`
}

func (c JobCounts) Active() int {
	return c.Queued + c.Downloading + c.Ready + c.Paused
}

func (s *Store) JobCounts() (JobCounts, error) {
	rows, err := s.db.Query(`SELECT status, COUNT(*) FROM jobs GROUP BY status`)
	if err != nil {
		return JobCounts{}, err
	}
	defer rows.Close()
	var out JobCounts
	for rows.Next() {
		var status string
		var n int
		if err := rows.Scan(&status, &n); err != nil {
			return JobCounts{}, err
		}
		switch status {
		case "queued":
			out.Queued = n
		case "downloading":
			out.Downloading = n
		case "ready":
			out.Ready = n
		case "finished":
			out.Finished = n
		case "error":
			out.Error = n
		case "cancelled":
			out.Cancelled = n
		case "paused":
			out.Paused = n
		}
	}
	return out, rows.Err()
}

type WorkerHeartbeat struct {
	UpdatedAt int64 `json:"updatedAt"`
	PID       int   `json:"pid"`
}

func (s *Store) TouchWorkerHeartbeat(pid int) error {
	_, err := s.db.Exec(`
INSERT INTO worker_heartbeat (id, updated_at, pid) VALUES (1, ?, ?)
ON CONFLICT(id) DO UPDATE SET updated_at = excluded.updated_at, pid = excluded.pid`,
		time.Now().Unix(), pid)
	return err
}

func (s *Store) WorkerHeartbeat() (WorkerHeartbeat, error) {
	var hb WorkerHeartbeat
	err := s.db.QueryRow(`SELECT updated_at, pid FROM worker_heartbeat WHERE id = 1`).Scan(&hb.UpdatedAt, &hb.PID)
	if errors.Is(err, sql.ErrNoRows) {
		return WorkerHeartbeat{}, nil
	}
	return hb, err
}
