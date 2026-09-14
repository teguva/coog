# Coog API (v1)

Base: `/api/v1`  
Auth: `Authorization: Bearer <COOG_AUTH_TOKEN>` when the token is set. `GET /health` is public. WebSocket clients may pass `?token=` on `/ws`.

Machine-readable: [`../openapi/coog.yaml`](../openapi/coog.yaml)

| Method | Path | Status |
|--------|------|--------|
| GET | `/health` | implemented |
| GET | `/api/v1/library` | implemented (includes metadata + artwork URLs) |
| GET | `/api/v1/library/{id}` | implemented |
| DELETE | `/api/v1/library/{id}` | implemented (deletes the file, stem sidecars, and an empty movie folder; `?scope=series` on an episode removes the show folder) |
| POST | `/api/v1/library/rescan` | implemented |
| POST | `/api/v1/library/{id}/ignore` | implemented (sets `matchStatus` to `ignored` in `coog.json`) |
| POST | `/api/v1/library/{id}/rematch` | implemented (optional `{ "imdbId": "tt…" }`; clears blocking status and re-enriches) |
| GET | `/api/v1/media/{id}/stream` | implemented (HTTP Range) |
| GET | `/api/v1/media/{id}/poster` | implemented |
| GET | `/api/v1/media/{id}/backdrop` | implemented |
| GET | `/api/v1/media/{id}/logo` | implemented (sidecar `logo.png` / clearlogo, else matched Metahub/Cinemeta) |
| GET | `/api/v1/media/{id}/artwork` | implemented (backdrop alias) |
| GET | `/api/v1/media/{id}/trailer` | implemented (sidecar, library `Trailers/` by IMDB, else TMDB YouTube via yt-dlp; catalog ids `catalog:tt…` accepted) |
| POST | `/api/v1/playback/sessions` | implemented (`direct` or `progressive`) |
| GET | `/api/v1/catalog/home` | implemented (TMDB trending, Cinemeta fallback; overlapping library titles stay and set `inLibrary`) |
| GET | `/api/v1/catalog/series/{imdb}` | implemented (`?season=` optional; returns `item`, `seasons[]`, selected `season`, and that season’s `episodes` with stills) |
| GET | `/api/v1/catalog/title/{imdb}` | implemented (`?kind=movie\|series`; plot, cast, runtime, certification, country, director, `releasePhase`, `releaseDate`) |
| GET | `/api/v1/catalog/title/{imdb}/similar` | implemented (`?kind=`; scored blend of director/creator, cast, genres, TMDB recommendations) |
| GET | `/api/v1/catalog/tmdb/{kind}/{id}` | implemented (resolve TMDB id to a catalog title) |
| GET | `/api/v1/catalog/search` | implemented (`?q=`; movies, series, people via TMDB) |
| GET | `/api/v1/catalog/browse` | implemented (`?kind=movie\|series`; composable discovery — see below) |
| GET | `/api/v1/catalog/genres` | implemented (`?kind=`; TMDB genre list) |
| GET | `/api/v1/catalog/moods` | implemented (`?kind=`; curated mood/keyword packs) |
| GET | `/api/v1/catalog/person/{id}` | implemented (TMDB combined credits) |
| GET | `/api/v1/catalog/streams` | implemented (`?imdb=&kind=&season=&episode=`; Torrentio/RD candidates) |
| GET/PUT | `/api/v1/settings/streaming` | implemented |
| GET/POST | `/api/v1/jobs` | implemented (POST accepts optional `quality`, `sizeBytes`, `sizeLabel`, `pack`, `tags`, `languages`, `releaseTitle`) |
| GET | `/api/v1/jobs/{id}` | implemented |
| POST | `/api/v1/jobs/{id}/cancel` | implemented (stops worker, deletes job + temp workdir; library files untouched) |
| POST | `/api/v1/jobs/{id}/pause` | implemented (stops the worker; files stay; resume with retry) |
| POST | `/api/v1/jobs/{id}/retry` | implemented (re-queues `error` / `paused`) |
| GET | `/api/v1/jobs/{id}/progressive/{file}` | growing HLS playlist and segments |
| GET | `/api/v1/server/stats` | implemented (disk, ffmpeg, jobs, worker heartbeat, Real-Debrid user, catalog error) |
| GET | `/api/v1/server/activity` | in-memory ring buffer (last 500 client/server events) |
| POST | `/api/v1/client/events` | TV play/session/player errors |
| WS | `/ws` | `job.progress`, `job.ready`, `job.finished`, `library.changed`, `activity` |

## Library item extras

List and detail responses include `matchStatus` (`matched` / `unmatched` / `ignored` / `suggested`). Catalog headings (`tagline`, `plot`, `imdbId`, rating, genres) are only set when identity is **explicit**: `coog.json` with an IMDB id, a Kodi NFO, or `tt…` in the path. Title search is never applied. Matched titles also get `logoUrl`, plus `cast`, `director`, `runtimeMinutes`, `certification`, `country`, and `tmdbId` when enrich has them. Artwork is stored beside the file (`poster.jpg`, `fanart.jpg`, `logo.png`) so it is not re-fetched after a cache wipe.

`DELETE /api/v1/library/{id}` removes the video and stem sidecars. Movies also drop the movie folder when it becomes empty. Episode deletes never wipe show artwork; pass `?scope=series` to delete the show folder. Paths outside `COOG_LIBRARY_PATH` return 403. The admin Library detail confirms before calling this.

## Playback session

Request:

```json
{
  "mediaId": "…",
  "jobId": null,
  "clientCapabilities": {
    "videoCodecs": ["h264", "hevc", "vp9", "av1"],
    "audioCodecs": ["aac", "ac3", "eac3"],
    "containers": ["mp4", "mkv"],
    "hdr": ["hdr10", "dolbyvision"]
  }
}
```

Pass `jobId` (and omit `mediaId` if the file is not in the library yet) for an in-progress acquire. Pass `imdbId` (and optional `kind`, `season`, `episode`, `title`) to play a catalog title: if it is already on disk, you get a **direct** session; otherwise the server queues a Real-Debrid job and returns **409** with `jobId` while it keeps buffering. When the job is `ready` or still downloading with HLS, the session `method` is `progressive` and `url` points at `…/jobs/{id}/progressive/index.m3u8`. After the job finishes, the same `jobId` returns a **direct** session on the new library item unless `saveToLibrary` is off.

Success:

```json
{
  "id": "…",
  "method": "direct",
  "url": "http://host:8090/api/v1/media/{id}/stream",
  "mediaId": "…",
  "jobId": "",
  "expectedDurationMs": 7200000,
  "bufferedMs": 7200000
}
```

If the profile cannot direct-play a finished file, the server returns **409** with `method: transcode` and an error string. Remux/transcode are not implemented yet.

Concurrent transcode cap (Phase 6, not enforced): **2**.

## Acquire jobs

`POST /api/v1/jobs`:

```json
{ "type": "ytdlp", "url": "https://…" }
```

Debrid from the TV source picker:

```json
{ "type": "debrid", "imdbId": "tt123", "infoHash": "…", "kind": "movie", "title": "…" }
```

When `infoHash` is set the worker resolves that torrent through Real-Debrid and skips Torrentio auto-pick. An active job with the same hash is reused.

Statuses: `queued`, `downloading`, `ready`, `finished`, `error`, `cancelled`, `paused`. `GET /jobs` only returns active queue rows (`queued` / `downloading` / `ready` / `paused` / `error`). Cancel deletes the job and its temp workdir under `data/jobs/{id}` (same as discarding the download). When a job finishes into the library, it is removed from the queue after `job.finished`; library files stay. `ready` means Media3 can open the progressive HLS URL while the download continues. Failed jobs include `error` plus a short `logTail` (last stderr from yt-dlp/ffmpeg). Local torrent jobs include a live `transfer` object (`downloadBps`, `uploadBps`, `peers`, `seeders`, `totalPeers`, `health`: `excellent` / `good` / `fair` / `poor` / `dead`) updated on `job.progress`. Real-Debrid tokens are redacted in activity logs and job URLs. `coog-worker` must be running and `yt-dlp` must be on `PATH`.

`PUT /api/v1/settings/streaming` accepts `torrentioProviders` and `excludeQualities` arrays in addition to the save-to-library / binge / Real-Debrid token fields. `preferredBackdropMax` (`1080p` | `1440p` | `2160p`, default `1080p`) caps catalog/library `size=display` backdrops used for heroes and focused cards; `thumb` stays small for rows and `orig` keeps the full master.

## Catalog extras

`GET /api/v1/catalog/browse` accepts stackable discovery filters: `sort` (`trending` / `popular` / `new` / `rating`), `genres` (comma AND, max 3; legacy `genre=` still works), `yearMin` / `yearMax`, `mood` (from `/catalog/moods`), and `minRating`. With any facet set, Recommended uses TMDB discover + taste re-rank instead of daily trending alone. Library-only titles are kept when they match year/genre/rating; mood filters drop library-only rows (keywords are TMDB-only).

Home/search/title items may include `inLibrary`, `mediaId` (library id when overlapping), `releasePhase` (`coming_soon` | `theatrical` | `released`), `releaseDate` (ISO `YYYY-MM-DD` when known), `tmdbId`, and `cast` (`name`, `character`, `profileUrl`, `tmdbId`). Remote Play is expected to open `GET /catalog/streams` rather than auto-queue a debrid job. `coming_soon` titles show the release date and trailer only — Play/Sources are hidden unless a local file exists.

`GET /api/v1/catalog/streams` returns `{ items: [{ infoHash, title, quality, cached, seeders, size, sizeLabel, source, provider }] }` sorted cached-first, capped at 40.

Activity events: `{ ts, level, source, type, message, mediaId?, jobId?, sessionId? }` with `source` = `api` | `worker` | `tv`.
