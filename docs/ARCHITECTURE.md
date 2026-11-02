# Coog architecture

```
 Google TV (Compose + Media3)     Svelte admin
        |                               |
        +----------- REST/WS -----------+
                        |
                   coog-api (Go)
                    |        \
              SQLite+fs    ffmpeg (probe / rare remux)
                    |
              coog-worker
                    |
     raw download → NAS original (RD / torrent / yt-dlp)
```

Coog is a **Jellyfin library + Stremio/Torrentio acquire** hybrid. Acquire copies original bytes onto the NAS; the TV plays that local file via HTTP Range (including while the file is still growing). There is **no HLS** on the progressive path.

## Run modes

Both are first-class. The API **does not require Docker**.

- **Native:** `go run ./cmd/coog-api` and `go run ./cmd/coog-worker`, or both under systemd. FFmpeg and yt-dlp on the host PATH.
- **Compose:** `deploy/docker-compose.yml` builds the same binary and mounts `COOG_LIBRARY_PATH`.

## Library layout

`COOG_LIBRARY_PATH` defaults to `~/Videos`. Keep titles in these top-level buckets:

```
Videos/
  Movies/
    Title (Year)/
      Title (Year).mkv
      poster.jpg | fanart.jpg | logo.png | coog.json | *.nfo   # optional
  Series/
    Show Name/                    # clean show title only — no SxxExx
      poster.jpg | tvshow.nfo | coog.json
      Season 01/
        Show Name S01E01.mkv
        Show Name S01E02.mkv
      Season 02/
        …
  Maize/                          # adult; skipped by public scan (PIN unlock)
    Scene Or Studio Title/
      video.mp4
      movie.meta.json             # preferred
      video.funscript             # optional
      trailer.mp4                 # skipped as sidecar
```

Rules:

- Movies: one title per folder; year in the folder name when known. No `Season` / `SxxExx` under `Movies/`.
- Series: show folder is the clean show name; season dirs are `Season NN`; episode filenames include `SxxExx` (or `NxNN`). Artwork lives on the show folder.
- Maize: flat scene/studio folders only — no season nesting. Actor profiles live under `$COOG_DATA_PATH/people` (FunPlay `People/` layout), not under `Maize/`. An existing `~/.cache/funplay/People` tree is copied there once. Bucket name defaults to `Maize` (configurable in `maize.json`).

Downloads write into the same Movies/Series layout; episode job titles are stripped to a clean show folder before save.

Trailers directories, dotfiles, `*.incompatible*`, and files under 64 KiB are skipped.

## Playback

`POST /api/v1/playback/sessions` returns `{ method, url, mediaId, jobId, contentType?, expectedDurationMs, bufferedMs }`.

| method | When | URL |
|--------|------|-----|
| `direct` | Finished library item | `/api/v1/media/{id}/stream` (HTTP Range on the original) |
| `progressive` | Acquire job ready / still downloading | `/api/v1/jobs/{id}/file` (HTTP Range on the growing original) |
| `remux` | Finished title the client cannot decode | rare negotiate fallback (not the progressive default) |

`ready` means enough bytes are on disk for Range play. Finished jobs with a `mediaId` return a `direct` session. Legacy job dirs that only have HLS may still be served from `/jobs/{id}/progressive/…`; new jobs never create HLS. Logs include `playback_method=direct|remux|transcode|progressive`.

Metadata: IMDB id only from `coog.json`, NFO, or `tt…` in the path — never from a title search. On a confirmed match, posters, fanart, and logos are stored beside the media file. Unmatched files keep the folder/file name and a local still; they do not get movie-database taglines or plots. `/poster`, `/backdrop`, and `/logo` prefer those sidecar images.

The Svelte admin is an ops console (Overview, Activity, Downloads, Library, Streaming) on the same API. `/ws` carries live job updates and activity events.

## Auth

v1: shared `COOG_AUTH_TOKEN` as `Authorization: Bearer …`. Empty token = open (dev). Multi-user comes later.

## See also

- [HANDOFF.md](HANDOFF.md) — product source of truth
- [API.md](API.md) — endpoint list
- `openapi/coog.yaml`
