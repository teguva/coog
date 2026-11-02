# Coog — Build Handoff Plan

**Product name:** Coog  
**Goal:** Jellyfin-style split — Google TV client + Linux server (native and/or Docker) + Svelte admin.  
**Predecessor:** `linux-tv-interface` (Debian kiosk appliance; reference for features & acquire logic, not the runtime).

This document is the **source of truth for the next agent**. Read it fully before writing code. Do not continue building the Qt/Sway kiosk as the primary product path unless the user asks.

---

## 1. Product definition

### What Coog is
- **Hybrid:** Jellyfin-style library + Stremio/Torrentio-style acquire.
- **Server (Linux):** native Go binary and/or Docker — library, metadata, auth, download/acquire jobs, playback session URLs (direct play → rare remux → transcode).
- **Google TV client:** native Android TV app — Jetpack Compose for TV + Media3 ExoPlayer.
- **Admin:** Svelte web UI for settings, library, jobs, users — not the living-room client.
- **Play while downloading (hard requirement):** download the **original** to the NAS; TV plays that local file via **HTTP Range** once enough is buffered; seek limited to downloaded range; HUD shows full expected length + buffer. **No HLS** on this path; **no quality tampering**.

### What Coog is not (v1)
- Not Qt / WebEngine / Sway / embedded mpv on the TV.
- Not live Real-Debrid streaming to the TV (Stremio lag path) — buffer locally first.
- Not a rewrite of all `linux-tv-interface` UI pixel-perfect on day one.

### Target living-room device (reference)
- **TCL 75C8K** (Google TV, MediaTek Pentonic 700): strong direct play for H.264/HEVC/VP9/AV1, HDR10/DV; prefer direct play; remux/transcode mainly for awkward audio (TrueHD etc.).

---

## 2. Agreed architecture

| Layer | Stack | Responsibility |
|--------|--------|----------------|
| TV client | Kotlin, Compose for TV (`androidx.tv:tv-material`), Media3 ExoPlayer + `PlayerSurface` | Browse, details, player HUD, enqueue jobs, play streams |
| API | **Go**, single static binary preferred | REST + WebSocket, auth, library, playback negotiation |
| Media | **FFmpeg** (probe / remux / transcode) | Called by Go, not a second app framework |
| Workers | Separate process or container | yt-dlp, torrents, Real-Debrid resolve (out of process) |
| Admin | **Svelte** (SvelteKit or Vite+Svelte) | Ops UI against same API |
| Deploy | **Native Linux** and/or **Docker Compose** | First-class bare-metal (`coog-api` systemd); Compose optional for NAS appliances |

### Playback ladder (server must implement)
1. **Direct play** — HTTP Range on original/finished file when client capabilities match.  
2. **Progressive play (required)** — same Range path on the **growing local original** at `/api/v1/jobs/{id}/file` (**not HLS**).
3. **Remux** — rare capability fallback for finished titles the TV cannot decode.
4. **Transcode** — last resort; cap concurrent sessions.

### Progressive play design (non-negotiable product behavior)

- Early-play gate: enough **bytes on disk** (+ estimated bufferedMs from size/total × duration) before `ready`.
- Trusted duration: TMDB/runtime for seek bar full length while file is still growing.
- Buffer / seek clamp: only seek within downloaded range.
- Job progress monotonic; WebSocket progress events to client + admin.
- Acquire is a **byte copy** of the RD/HTTP/torrent/yt-dlp original into `jobs/{id}/source{ext}`; finalize **moves** that original into `Videos/` (no routine AAC/MP4 rewrite).

**Server responsibilities:**
- Worker downloads original bytes to disk.
- `GET /api/v1/jobs/{id}/file` serves Accept-Ranges, clamps to bytes present, advertises total when known.
- `POST /playback/sessions` for an active ready job returns `{ method: progressive, url: …/file, contentType, expectedDurationMs, bufferedMs }` and keeps updating via WS.
- When job finishes, hand off to normal direct session on the library file.

**Client responsibilities:**
- Buffering/ready UI until early-play gate passes.
- Media3 **progressive** source on the file URL (MIME from `contentType`).
- Player HUD: position, **expected** duration, buffer bar; Left/Right seek clamped.
- Do not treat demux EOF as “episode finished” while `job.status` is still downloading (false EOF guard).

**Explicitly out of scope:** live RD-to-TV streaming; quality-lowering progressive encodes; ABR.

### Why this stack (context for agent)
- Compose + Media3 = best Google TV UX/playback path.  
- Go = lightweight, multi-client-friendly control plane.  
- Svelte = admin + reuse of team UI skills from `linux-tv-interface`.  
- Acquire workers stay isolated so torrents/yt-dlp don’t block the API.

---

## 3. Repository layout (create new repo)

**Create:** `/home/priit/Projects/coog` (or GitHub `coog` / `coog-media` — ask user for remote if needed).

Do **not** dump Coog inside `linux-tv-interface` as the long-term home. Optional: keep a short pointer README in the old repo later.

```text
coog/
├── README.md                 # Vision, quick start
├── docs/
│   ├── ARCHITECTURE.md       # This plan condensed + diagrams
│   ├── API.md                # OpenAPI-oriented endpoint list
│   └── HANDOFF.md            # Copy/link of this handoff
├── server/                   # Go module
│   ├── cmd/coog-api/
│   ├── cmd/coog-worker/      # optional split binary
│   ├── internal/
│   │   ├── api/              # HTTP handlers, middleware
│   │   ├── auth/
│   │   ├── library/
│   │   ├── playback/         # capability match, ffmpeg jobs
│   │   ├── jobs/             # download job state machine
│   │   └── store/            # sqlite/postgres
│   ├── go.mod
│   └── Dockerfile
├── worker/                   # If not same Go module: scripts/sidecars
│   └── Dockerfile            # yt-dlp, ffmpeg, python-libtorrent as needed
├── admin/                    # Svelte admin
│   ├── package.json
│   └── Dockerfile            # nginx or vite preview for prod
├── client/                   # Android TV / Google TV
│   └── app/                  # Gradle Kotlin project
├── deploy/
│   ├── docker-compose.yml    # Optional appliance path
│   ├── systemd/              # Native Linux: coog-api.service, coog-worker.service
│   └── install-linux.sh      # Install binaries + unit files to /usr/local or ~/.local
└── openapi/
    └── coog.yaml             # Grow with implementation
```

### Run modes (both first-class)

| Mode | How | Needs on host |
|------|-----|----------------|
| **Native Linux** | `coog-api` (+ optional `coog-worker`) under systemd/user service | `ffmpeg`, later `yt-dlp` / libtorrent tools on PATH or configured absolute paths |
| **Docker Compose** | `deploy/docker-compose.yml` | Docker engine; images bundle or mount tools |

Design the Go server so it **does not require Docker** — config via env/flags, library on a filesystem path, admin as static files embedded or served from disk. Docker is packaging convenience, not a runtime dependency.

---

## 4. Phased delivery (execute in order)

### Phase 0 — Bootstrap (day 1)
**Outcome:** Empty but runnable skeleton.

1. Create repo `coog`, git init, LICENSE (user preference; default MIT unless told otherwise).
2. `server/`: Hello `GET /health` on `:8090`.
3. **Native run:** `go run ./cmd/coog-api` works on the host without Docker.
4. `deploy/docker-compose.yml`: optional `api` service (same binary).
5. `deploy/systemd/coog-api.service` example unit + short install notes.
6. `admin/`: Svelte app with one page calling `/health`.
7. `client/`: Android TV project (min SDK per Compose for TV docs), blank home “Coog”, Compose for TV dependency, runs on emulator or device.
8. README: native quick start **and** `docker compose up`.

**Do not** port torrents yet.

### Phase 1 — Core API + library (MVP server)
**Outcome:** Scan a folder of videos; list; stream file with HTTP range.

1. Config via env: `COOG_LIBRARY_PATH`, `COOG_DATA_PATH`, `COOG_FFMPEG`, `COOG_AUTH_TOKEN` (simple shared secret for v1).
2. SQLite in data path: tables `media_items`, `users`/`tokens` (minimal).
3. Library scanner: walk `Movies/` / `Series/` (match linux-tv-interface layout if easy).
4. Endpoints (minimum):
   - `GET /health`
   - `GET /api/v1/library` (list)
   - `GET /api/v1/library/{id}`
   - `GET /api/v1/media/{id}/stream` — **Range** support, correct `Content-Type`
5. FFmpeg probe on ingest: codec, width, height, hdr, duration → store JSON.
6. Admin: library list + “rescan” button.

**Success:** VLC or curl can play a streamed file; admin shows titles.

### Phase 2 — Playback sessions (direct play first)
**Outcome:** Client asks “can I play?”; server returns a play method.

1. Client sends device capability profile (Media3 codec query summary).
2. `POST /api/v1/playback/sessions` → `{ method: direct|remux|transcode, url, mediaId }`.
3. Implement **direct** only first; stub remux/transcode with clear errors.
4. Cap: document concurrent transcode limit for later.

**Success:** Google TV / emulator ExoPlayer plays a library file via session URL.

### Phase 3 — Google TV client MVP
**Outcome:** Real living-room browse + play (finished files first).

1. Screens: Home (rows), Details, Player (PlayerSurface + basic HUD: play/pause, seek ±10s, auto-hide).
2. D-pad focus with TV Material components.
3. Settings: server URL + token.
4. ExoPlayer in ViewModel/scoped owner — **never** recreate on recomposition.
5. HUD must be ready to show **duration + buffer** (buffer = full for finished media).
6. Use JetStream / Compose for TV samples as structural reference (don’t copy branding).

**Success:** Browse library on TCL 75C8K (or emulator) and play a 1080p/4K H.264 or HEVC file with direct play.

### Phase 4 — Admin parity (ops)
1. Login (token), library rescan, item detail (codecs), job list (empty OK), server stats (disk, ffmpeg version).
2. Polish enough to configure a headless NAS without SSH for daily ops.

### Phase 5 — Acquire + play-while-downloading (**required**)
**Outcome:** Enqueue download → early play on TV while job still runs → finish into library.

**Status:** Implemented as **local original + HTTP Range** (not HLS). RD/HTTP/torrent/yt-dlp pull originals; `GET /jobs/{id}/file` for progressive; finalize moves the original into the library.

**Success criteria for Phase 5:**
- Start an acquire job from admin or TV.
- Within early-play threshold, TV plays the local file via Range while download continues.
- Seek only within buffered range; full length shows expected runtime when known.
- After finish, item is in library as the original file and replayable via direct play.

### Phase 6 — Remux / transcode + multi-client
1. Remux path for finished-file container mismatches.
2. Transcode with queue + max concurrent (e.g. 1–2).
3. Test multi-client: mix of direct play + one progressive job.
4. Optional: bandwidth / session limits.

### Phase 7 — Product depth (after progressive MVP stable)
- TMDB metadata enrichment, posters, episode structure.
- Binge / next-episode (port rules from `svelte-ui/src/lib/nextEpisode.ts`).
- OpenSubtitles.
- Point old `tv-shell` at Coog API as optional Linux client (later).
- Polish progressive edge cases (seek near live edge, reconnect, job fallback URLs).

---

## 5. API sketch (v1)

Base: `/api/v1`  
Auth: `Authorization: Bearer <token>` (v1 shared admin token OK).

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/health` | Liveness |
| GET | `/library` | List media |
| GET | `/library/{id}` | Detail + probe |
| POST | `/library/rescan` | Rescan disk |
| POST | `/playback/sessions` | Body: `{ mediaId? , jobId?, clientCapabilities }` → play URL + method (`direct\|remux\|transcode\|progressive`) |
| GET | `/media/{id}/stream` | Byte-range stream (finished) |
| GET | `/jobs/{id}/file` | Growing original file (HTTP Range) for progressive play |
| GET | `/jobs/{id}/progressive/*` | Legacy HLS only |
| GET | `/jobs` | List jobs |
| POST | `/jobs` | Enqueue |
| GET | `/jobs/{id}` | Job detail (`progress`, `ready`, `expectedDurationMs`, `bufferedMs`) |
| WS | `/ws` | Events: `job.progress`, `job.ready`, `job.finished`, `library.changed` |

Maintain `openapi/coog.yaml` as endpoints land.

---

## 6. Client requirements (Google TV)

- Package name suggestion: `tv.coog.app` (confirm with user).
- Compose for TV Material — **not** mobile Material as primary.
- Media3 ExoPlayer + `media3-ui-compose` PlayerSurface; progressive **file** play (HTTP Range) required for play-while-downloading.
- Player HUD: show on Down/OK; hide on idle / Up; seek on Left/Right; **buffer bar + trusted duration** for progressive sessions.
- False-EOF: do not advance binge / mark ended while job still downloading.
- Server URL + token in settings (DataStore).
- Graceful errors: unreachable server, unsupported codec → show remux/transcode needed if API says so.

---

## 7. What to reuse from `linux-tv-interface`

**Reuse as reference / port (Phase 5 progressive is required):**
- Library folder conventions (`Videos/Movies`, `Videos/Series`, naming).
- yt-dlp concurrent HLS, early-play gates, completeness checks, monotonic progress.
- Trusted duration + buffer/seek clamp + false growing-file EOF.
- Feature inventory from `qtBridge.ts` invoke commands (mental API catalog).

**Do not reuse as Coog runtime:**
- `tv-shell` Qt, QWebChannel, Sway, D-Bus, embedded mpv, LayerShell overlays.

Path to predecessor: `/home/priit/Projects/linux-tv-interface`.

---

## 8. Engineering norms for the next agent

1. **Ship vertical slices** — health → finished stream → TV play → **acquire + progressive play** (required).  
2. **No drive-by refactors** of unrelated predecessor code.  
3. **Both run paths:** `go run` / systemd **and** Docker Compose must stay valid; never make Docker mandatory for development or production.  
4. **Document env vars** in README as you add them.  
5. **Ask the user** before: creating GitHub remotes, choosing license if unsure, or rewriting workers in pure Go when wrapping yt-dlp is enough.  
6. **Do not defer play-while-downloading** past Phase 5 — it is a product requirement, not a stretch goal. Prefer local original + HTTP Range (Jellyfin/Stremio-style), not live RD and not HLS-by-default.  
7. **Commits:** only when user asks; otherwise leave working tree ready.  
8. Log `playback_method=direct|remux|transcode|progressive` for debugging TCL quality.

---

## 9. First session checklist (next agent)

Copy this and execute top to bottom:

- [ ] Confirm with user: repo path `/home/priit/Projects/coog`, package id, license.  
- [ ] Create monorepo skeleton (server, admin, client, deploy).  
- [ ] Go `/health` via **native** `go run` (no Docker required).  
- [ ] Optional: Docker Compose up for the same binary.  
- [ ] Example systemd unit checked in under `deploy/systemd/`.  
- [ ] Svelte admin hits health.  
- [ ] Android TV empty Coog app builds.  
- [ ] Implement library scan + range stream (Phase 1).  
- [ ] Wire ExoPlayer to stream URL (Phase 2–3 start).  
- [ ] Stop and demo finished-file play on TCL/emulator before Phase 5.  
- [ ] Phase 5 incomplete until **play-while-downloading** works on Media3 (not only finished library ingest).

---

## 10. Success criteria (MVP “Coog works”)

1. `coog-api` runs natively on Linux (`go run` or installed binary + systemd) **or** via `docker compose up`.  
2. Admin shows scanned library.  
3. Google TV app direct-plays a **finished** movie.  
4. **Play while downloading:** enqueue yt-dlp (or equivalent) job → TV plays mid-download with buffer/seek clamp → job completes into library.  
5. README explains native install **and** Docker, plus server URL setup on the TV.

---

## 11. Open decisions (resolve when blocking)

| Topic | Default if user unavailable |
|--------|-----------------------------|
| Progressive play | **Required** — local original + HTTP Range (no HLS default) |
| Auth | Single bearer token → multi-user later |
| Worker language | Sidecar with yt-dlp binary; Go orchestrates |
| Admin framework | SvelteKit static adapter or Vite SPA — pick one and stick |
| App id | `tv.coog.app` |
| DB | SQLite first |

---

## 12. Conversation context summary

Prior decisions in this thread:
- Architecture like Jellyfin (server + clients), more acquire features than stock Jellyfin.
- Google TV: Compose for TV + Media3 (not WebView of old Svelte; not mpv-as-default).
- Stremio/Torrentio inspire **acquire** (RD resolve, source picker); playback is Jellyfin-style **local file Range**, not live RD to the TV.
- Server language: Go.
- Admin: Svelte.
- Server deploy: **native Linux (systemd + Go binary) and Docker Compose — both first-class**; Docker not required.
- **Play while downloading is a hard requirement** (local buffered original + Media3 progressive; not deferred; not HLS).
- TCL 75C8K: good direct-play target; don’t blanket-transcode.

**Product name: Coog.**

---

*End of handoff. Next agent: start at §9 First session checklist.*
