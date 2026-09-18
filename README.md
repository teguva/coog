# Coog

Google TV client + Linux media server + Svelte admin. Jellyfin-style split: the TV app browses and plays; the server owns the library, auth, acquire jobs, and playback URLs.

Predecessor: [`linux-tv-interface`](https://github.com/) (Debian kiosk). That repo is a **feature reference**, not the runtime. Coog does not ship Qt, Sway, or embedded mpv on the TV.

**Play while downloading is a product requirement** (Phase 5). Home shows trending movies/series (TMDB or Cinemeta) plus local library rows. Remote titles stream through Real-Debrid + Torrentio; the worker keeps buffering after the TV leaves and saves into `Videos/` unless admin settings say otherwise.

## Layout

| Path | What |
|------|------|
| `server/` | Go module — `coog-api` and `coog-worker` (yt-dlp sidecar) |
| `admin/` | Vite + Svelte 5 ops UI |
| `client/` | Android TV / Google TV app (`tv.coog.app`) |
| `deploy/` | systemd units, `install-linux.sh`, optional Compose |
| `docs/` | Architecture, API, full handoff |
| `openapi/coog.yaml` | Endpoints as they land |

## Native quick start (no Docker)

Needs: Go 1.24+, FFmpeg/ffprobe on `PATH`. Acquire jobs also need `yt-dlp` on `PATH` and a running `coog-worker`.

```bash
# API
export COOG_LIBRARY_PATH="$HOME/Videos"   # Movies/ + Series/ (+ Maize/ adult) layout
export COOG_DATA_PATH="$HOME/.local/share/coog"
# export COOG_AUTH_TOKEN="change-me"      # optional in v1; set this on LAN installs
cd server
go run ./cmd/coog-api
# another terminal: go run ./cmd/coog-worker
# GET http://127.0.0.1:8090/health
```

On a LAN install (TV clients on the same network), set `COOG_AUTH_TOKEN` and paste the same value in the admin sidebar and the TV app Settings. Leaving it empty leaves `/api/*` open to anyone who can reach the host.

Scan and stream:

```bash
curl -X POST http://127.0.0.1:8090/api/v1/library/rescan
curl http://127.0.0.1:8090/api/v1/library
# Play with VLC / mpv / curl Range:
# curl -H 'Range: bytes=0-1023' http://127.0.0.1:8090/api/v1/media/<id>/stream
```

Admin (proxies `/health` and `/api` to the API):

```bash
cd admin
npm install
npm run dev
# http://127.0.0.1:5173
```

Install as a user systemd service:

```bash
./deploy/install-linux.sh
systemctl --user enable --now coog-api coog-worker
```

## Docker Compose (optional)

Published images: [`ghcr.io/teguva/coog`](https://github.com/teguva/coog/pkgs/container/coog) (`api` and `worker` share one image).

```bash
cd deploy
cp compose.env.example compose.env   # set library path + tokens
docker compose --env-file compose.env pull
docker compose --env-file compose.env up -d
```

Build locally instead of pulling:

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/compose.env up -d --build
```

Admin UI is served by the API on port 8090 (`/`). The TV app Settings URL is `http://<host-lan-ip>:8090`.

## Google TV client

Package id: `tv.coog.app`. minSdk **23** (Compose for TV is 21; Media3 HLS requires 23). Open `client/` in Android Studio or:

```bash
cd client
./gradlew :app:assembleDebug
```

### Install from GitHub Releases

Each `v*` tag builds a signed APK (`coog-tv-vc{versionCode}-{versionName}.apk`) and attaches it to a [GitHub Release](https://github.com/teguva/coog/releases). Sideload that APK once (`adb install` or a TV downloader). After that, the app checks GitHub on launch; **Settings → Update** downloads the new APK and installs it. Android may ask once to allow Coog to install unknown apps, and may show a system confirm on each update.

Studio debug builds are signed with a different key. Uninstall the debug build before switching to the GitHub APK, or in-place updates will fail.

**Server / worker updates:** pull `ghcr.io/teguva/coog:0.1.17` and recreate Compose, or pull the repo and re-run `./deploy/install-linux.sh`, then `systemctl --user restart coog-api coog-worker`.

On the emulator, the default server URL is `http://10.0.2.2:8090`. On a TCL / Google TV on LAN, set **Settings → Server URL** to `http://<host-lan-ip>:8090`. If `COOG_AUTH_TOKEN` is set, paste the same token there.

## Environment

| Variable | Default | Purpose |
|----------|---------|---------|
| `COOG_LISTEN` | `:8090` | HTTP bind |
| `COOG_LIBRARY_PATH` | `~/Videos` | Library root (`Movies/`, `Series/`, optional `Maize/`) |
| `COOG_DATA_PATH` | `~/.local/share/coog` | SQLite, artwork cache, job work dirs |
| `COOG_FFMPEG` | `ffmpeg` | Binary on PATH or absolute |
| `COOG_FFPROBE` | `ffprobe` | Probe on ingest |
| `COOG_YTDLP` | `yt-dlp` | Worker acquire binary |
| `COOG_AUTH_TOKEN` | empty (open) | Shared bearer token for `/api/*` |
| `COOG_ADMIN_DIR` | empty | Serve a built admin SPA from this directory |
| `COOG_TMDB_API_KEY` | empty | Optional TMDB overlay after IMDB/Cinemeta |
| `REALDEBRID_API_TOKEN` | empty | Optional Real-Debrid token for catalog streams |

`GET /health` is always unauthenticated.

## Playback ladder

1. **direct** — HTTP Range on the original file (implemented).
2. **remux** — stream-copy to fMP4/HLS (stub; API returns a clear error).
3. **transcode** — last resort, cap later (documented as 2 concurrent).
4. **progressive** — in-progress yt-dlp jobs as growing HLS for Media3 (implemented). Real-Debrid and torrents are later.

Library JSON includes `imdbId`, `tagline`, `plot`, `genres`, `rating`, `posterUrl`, and `backdropUrl`. Artwork: `GET /api/v1/media/{id}/poster` and `/backdrop` (`/artwork` aliases backdrop).

## What is not done yet

- Real-Debrid and torrent acquire
- Remux/transcode pipeline
- Search, continue watching, next-episode, enqueue-from-TV

See [docs/HANDOFF.md](docs/HANDOFF.md) for the full plan and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the condensed map.

License: MIT.
