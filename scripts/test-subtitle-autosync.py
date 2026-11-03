#!/usr/bin/env python3
"""Test Coog subtitle autosync offline (mirrors client SubtitleSync.kt).

Default: Shrek the Third embedded Greek SubRip (#14) as the *local* timing
reference, vs an Estonian OpenSubtitles file as the *external* track to align.

  Display uses: position - delay
  delay = local.start - external.start   (positive → external is early)

Examples:
  ./scripts/test-subtitle-autosync.py
  ./scripts/test-subtitle-autosync.py --list-streams
  ./scripts/test-subtitle-autosync.py --external /path/to/et.srt
  ./scripts/test-subtitle-autosync.py --external os:447077
  ./scripts/test-subtitle-autosync.py --all-cached
"""

from __future__ import annotations

import argparse
import collections
import json
import math
import os
import re
import statistics
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path

DEFAULT_VIDEO = Path(
    "/home/priit/Videos/Movies/Shrek the Third (2007)/Shrek the Third (2007).mkv"
)
DEFAULT_EL_STREAM = 14  # Greek SubRip inside the MKV (no language tag in ffprobe)
DEFAULT_ET = Path(
    "/home/priit/.local/share/coog/subtitles/447077_Shrek.The.Third.TS.XViD-maVen.srt"
)
SUB_CACHE = Path.home() / ".local/share/coog/subtitles"
API = os.environ.get("COOG_API", "http://127.0.0.1:8090")

TIME_RE = re.compile(
    r"(?:(\d{1,2}):)?(\d{1,2}):(\d{1,2})[.,](\d{1,3})\s*-->\s*"
    r"(?:(\d{1,2}):)?(\d{1,2}):(\d{1,2})[.,](\d{1,3})"
)
NAME_RE = re.compile(r"[\wÀ-ÿΑ-ωΆ-Ώ][\wÀ-ÿΑ-ωΆ-Ώ'\-]{2,}", re.UNICODE)
# Latin proper names still dominate cross-language anchors (Shrek, Fiona, …).
LATIN_NAME_RE = re.compile(r"[A-Za-z][A-Za-z'\-]{2,}")

NAME_STOPWORDS = {
    "the", "and", "but", "for", "you", "your", "with", "from", "this", "that",
    "what", "when", "where", "which", "while", "who", "will", "would", "could",
    "should", "about", "into", "over", "under", "then", "than", "them", "they",
    "their", "there", "here", "have", "has", "had", "were", "was", "are", "been",
    "not", "yes", "yeah", "okay", "hey", "hello", "thanks", "thank", "please",
    "mister", "miss", "sir", "madam", "doctor", "captain", "subtitle", "subtitles",
    "www", "http", "https", "com", "opensubtitles", "webrip", "bluray", "hdtv",
}


@dataclass
class Cue:
    start_ms: int
    end_ms: int
    text: str


@dataclass
class SyncResult:
    delay_ms: int
    confidence: float
    source: str = ""


def ts(h, m, s, ms) -> int:
    h = int(h or 0)
    m = int(m)
    s = int(s)
    ms = int(ms.ljust(3, "0")[:3])
    return ((h * 60 + m) * 60 + s) * 1000 + ms


def parse_srt(raw: str) -> list[Cue]:
    text = raw.lstrip("\ufeff").replace("\r\n", "\n").replace("\r", "\n")
    if text.startswith("WEBVTT"):
        text = text.split("\n", 1)[-1]
    out: list[Cue] = []
    for block in re.split(r"\n\n+", text):
        lines = [ln.strip() for ln in block.split("\n") if ln.strip()]
        if not lines:
            continue
        i = 0
        if lines[0].isdigit():
            i = 1
        if i >= len(lines):
            continue
        m = TIME_RE.search(lines[i])
        if not m:
            continue
        start = ts(*m.group(1, 2, 3, 4))
        end = ts(*m.group(5, 6, 7, 8))
        payload = re.sub(r"<[^>]+>", "", "\n".join(lines[i + 1:])).strip()
        if payload and end > start:
            out.append(Cue(start, end, payload))
    return sorted(out, key=lambda c: c.start_ms)


def decode_bytes(data: bytes) -> str:
    for enc in ("utf-8-sig", "utf-8", "cp1252", "iso-8859-1", "latin1"):
        try:
            return data.decode(enc)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="replace")


def load_srt_path(path: Path) -> list[Cue]:
    return parse_srt(decode_bytes(path.read_bytes()))


def fetch_api_subtitle(sub_id: str) -> list[Cue]:
    url = f"{API}/api/v1/subtitles/file?id={urllib.parse.quote(sub_id, safe='')}"
    with urllib.request.urlopen(url, timeout=120) as res:
        return parse_srt(decode_bytes(res.read()))


def extract_embedded(video: Path, stream_index: int, out: Path | None = None) -> list[Cue]:
    if out is None:
        out = Path(tempfile.gettempdir()) / f"coog_autosync_emb_{stream_index}.srt"
    # Prefer server extract (same path the app uses) when API is up.
    emb_id = f"emb:{stream_index}:{video}"
    try:
        cues = fetch_api_subtitle(emb_id)
        if len(cues) >= 6:
            out.write_text(
                "\n\n".join(
                    f"{i}\n{format_clock(c.start_ms)} --> {format_clock(c.end_ms)}\n{c.text}"
                    for i, c in enumerate(cues, 1)
                ),
                encoding="utf-8",
            )
            return cues
    except Exception as exc:  # noqa: BLE001
        print(f"note: API extract failed ({exc}); using ffmpeg", file=sys.stderr)

    cmd = [
        "ffmpeg", "-y", "-i", str(video),
        "-map", f"0:{stream_index}",
        "-f", "srt",
        str(out),
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0 or not out.exists() or out.stat().st_size < 8:
        raise RuntimeError(
            f"ffmpeg extract failed for stream {stream_index}:\n{proc.stderr[-400:]}"
        )
    return load_srt_path(out)


def format_clock(ms: int) -> str:
    if ms < 0:
        ms = 0
    h, rem = divmod(ms, 3_600_000)
    m, rem = divmod(rem, 60_000)
    s, milli = divmod(rem, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{milli:03d}"


def slice_for_sync(cues: list[Cue]) -> list[Cue]:
    if len(cues) <= 16:
        return cues
    drop = max(2, int(len(cues) * 0.10))
    return cues[drop:-drop]


def index_names(cues: list[Cue]) -> dict[str, list[int]]:
    idx: dict[str, list[int]] = collections.defaultdict(list)
    for cue in cues:
        cleaned = re.sub(r"<[^>]+>", " ", cue.text)
        seen: set[str] = set()
        # Prefer Latin tokens (character names) — works across el/et/en.
        for m in LATIN_NAME_RE.finditer(cleaned):
            raw = m.group(0)
            if not any(ch.isupper() for ch in raw):
                continue
            key = raw.lower()
            if len(key) < 3 or key in NAME_STOPWORDS or key in seen:
                continue
            if len(raw) <= 4 and raw.isupper():
                continue
            seen.add(key)
            idx[key].append(cue.start_ms)
    return idx


def median_int(values: list[int]) -> int:
    if not values:
        return 0
    s = sorted(values)
    mid = len(s) // 2
    if len(s) % 2 == 0:
        return int((s[mid - 1] + s[mid]) / 2)
    return s[mid]


def align_by_shared_names(
    local: list[Cue], external: list[Cue], search_range_ms: int
) -> SyncResult | None:
    local_idx = index_names(local)
    ext_idx = index_names(external)
    if not local_idx or not ext_idx:
        return None
    votes: list[int] = []
    rare_pairs = 0
    shared_detail: list[tuple[str, int, int, int]] = []
    for name, local_hits in local_idx.items():
        ext_hits = ext_idx.get(name)
        if not ext_hits:
            continue
        if len(local_hits) > 12 or len(ext_hits) > 12:
            continue
        n = min(len(local_hits), len(ext_hits))
        rare = len(local_hits) <= 2 and len(ext_hits) <= 2
        weight = 4 if len(local_hits) == 1 and len(ext_hits) == 1 else (2 if rare else 1)
        for i in range(n):
            d = int(local_hits[i] - ext_hits[i])
            if d < -search_range_ms or d > search_range_ms:
                continue
            votes.extend([d] * weight)
            if rare:
                rare_pairs += 1
            if len(shared_detail) < 12:
                shared_detail.append((name, local_hits[i], ext_hits[i], d))
    if len(votes) < 4:
        return None
    delay = median_int(votes)
    inliers = [v for v in votes if abs(v - delay) <= 220]
    if len(inliers) < 4:
        return None
    refined = median_int(inliers)
    base = len(inliers) / len(votes)
    boost = min(0.25, rare_pairs / 12)
    confidence = min(1.0, base * 0.85 + boost)
    # Stash sample pairs on the result via source string for printing.
    sample = "; ".join(
        f"{n} L={format_clock(ls)} E={format_clock(es)} d={d}"
        for n, ls, es, d in shared_detail[:6]
    )
    return SyncResult(refined, confidence, sample)


def gap_vector(cues: list[Cue]) -> list[float] | None:
    if len(cues) < 6:
        return None
    return [
        float(max(200, min(12_000, cues[i + 1].start_ms - cues[i].start_ms)))
        for i in range(len(cues) - 1)
    ]


def pearson(a: list[float], b: list[float]) -> float:
    if len(a) != len(b) or not a:
        return 0.0
    n = len(a)
    mean_a = sum(a) / n
    mean_b = sum(b) / n
    num = den_a = den_b = 0.0
    for i in range(n):
        da = a[i] - mean_a
        db = b[i] - mean_b
        num += da * db
        den_a += da * da
        den_b += db * db
    den = math.sqrt(den_a * den_b)
    return 0.0 if den < 1e-9 else num / den


def align_by_gap_windows(local: list[Cue], external: list[Cue]) -> int | None:
    local_gaps = gap_vector(local)
    ext_gaps = gap_vector(external)
    if not local_gaps or not ext_gaps:
        return None
    window = min(12, len(local_gaps), len(ext_gaps))
    if window < 6:
        return None
    hits: list[tuple[float, int]] = []
    local_step = 2 if len(local_gaps) > 400 else 1
    ext_step = 2 if len(ext_gaps) > 400 else 1
    for li in range(0, len(local_gaps) - window + 1, local_step):
        local_win = local_gaps[li : li + window]
        for ei in range(0, len(ext_gaps) - window + 1, ext_step):
            corr = pearson(local_win, ext_gaps[ei : ei + window])
            if corr >= 0.94:
                hits.append((corr, int(local[li].start_ms - external[ei].start_ms)))
    if not hits:
        return None
    hits.sort(reverse=True)
    top = [d for _, d in hits[: min(9, len(hits))]]
    return median_int(top)


def nearest_index(starts: list[int], target: int) -> int:
    lo, hi = 0, len(starts) - 1
    while lo <= hi:
        mid = (lo + hi) // 2
        v = starts[mid]
        if v < target:
            lo = mid + 1
        elif v > target:
            hi = mid - 1
        else:
            return mid
    if lo <= 0:
        return 0
    if lo >= len(starts):
        return len(starts) - 1
    before = lo - 1
    return before if abs(starts[before] - target) <= abs(starts[lo] - target) else lo


def collect_delay_votes(
    local: list[Cue], external: list[Cue], delay: int, same_language: bool
) -> list[int]:
    starts = [c.start_ms for c in external]
    votes: list[int] = []
    for loc in local:
        want = loc.start_ms - delay
        j = nearest_index(starts, want)
        ext = external[j]
        dt = abs(ext.start_ms - want)
        if dt > 800:
            continue
        dur_l = max(1, loc.end_ms - loc.start_ms)
        dur_e = max(1, ext.end_ms - ext.start_ms)
        dur_ratio = min(dur_l, dur_e) / max(dur_l, dur_e)
        if dur_ratio < 0.4:
            continue
        if same_language:
            len_l = max(1, len(loc.text.replace("\n", "")))
            len_e = max(1, len(ext.text.replace("\n", "")))
            if min(len_l, len_e) / max(len_l, len_e) < 0.4:
                continue
        votes.append(int(loc.start_ms - ext.start_ms))
    return votes


def refine_by_median_votes(
    local: list[Cue],
    external: list[Cue],
    seed: int,
    same_language: bool,
    search_range_ms: int,
) -> SyncResult:
    delay = max(-search_range_ms, min(search_range_ms, seed))
    votes: list[int] = []
    for _ in range(4):
        votes = collect_delay_votes(local, external, delay, same_language)
        if len(votes) < 5:
            return SyncResult(0, len(votes) / max(1, len(local)), "timing-weak")
        delay = max(-search_range_ms, min(search_range_ms, median_int(votes)))
    inliers = [v for v in votes if abs(v - delay) <= 180]
    if len(inliers) < 5:
        return SyncResult(0, len(inliers) / max(1, len(local)), "timing-weak")
    delay = max(-search_range_ms, min(search_range_ms, median_int(inliers)))
    conf = min(1.0, len(inliers) / max(1, len(local)))
    return SyncResult(delay, conf, "timing")


def is_better(candidate: SyncResult, current: SyncResult) -> bool:
    if candidate.confidence > current.confidence + 0.03:
        return True
    if (
        abs(candidate.confidence - current.confidence) <= 0.03
        and candidate.confidence >= current.confidence
        and abs(candidate.delay_ms) < abs(current.delay_ms)
    ):
        return True
    return False


def estimate_subtitle_delay_ms(
    local: list[Cue],
    external: list[Cue],
    same_language: bool = False,
    search_range_ms: int = 45_000,
) -> SyncResult:
    if len(local) < 6 or len(external) < 6:
        return SyncResult(0, 0.0, "too-few-cues")
    local_use = slice_for_sync(local)
    external_use = slice_for_sync(external)
    if len(local_use) < 6 or len(external_use) < 6:
        return SyncResult(0, 0.0, "too-few-cues")

    name_align = align_by_shared_names(local_use, external_use, search_range_ms)
    seeds = {0}
    if name_align is not None:
        seeds.add(name_align.delay_ms)
    gap = align_by_gap_windows(local_use, external_use)
    if gap is not None:
        seeds.add(gap)
    for d in range(-search_range_ms, search_range_ms + 1, 2500):
        seeds.add(d)

    best = SyncResult(0, 0.0)
    if name_align is not None and name_align.confidence >= 0.35:
        best = name_align

    for seed in seeds:
        if seed < -search_range_ms or seed > search_range_ms:
            continue
        got = refine_by_median_votes(
            local_use, external_use, seed, same_language, search_range_ms
        )
        if is_better(got, best):
            best = got

    if (
        name_align is not None
        and name_align.confidence >= 0.4
        and (best.confidence < 0.45 or abs(best.delay_ms - name_align.delay_ms) <= 250)
    ):
        if is_better(name_align, best) or best.confidence < 0.45:
            best = name_align

    if best.confidence < 0.40:
        return SyncResult(0, best.confidence, best.source or "rejected-low-conf")
    if abs(best.delay_ms) < 80:
        return SyncResult(0, best.confidence, "near-zero")
    return SyncResult(
        max(-30_000, min(30_000, best.delay_ms)),
        best.confidence,
        best.source,
    )


TEXT_CODECS = {"subrip", "srt", "ass", "ssa", "webvtt", "mov_text", "ttml"}


def probe_subtitle_streams(video: Path) -> list[dict]:
    cmd = [
        "ffprobe", "-v", "error",
        "-select_streams", "s",
        "-show_entries", "stream=index,codec_name:stream_tags=language,title",
        "-of", "json",
        str(video),
    ]
    data = json.loads(subprocess.check_output(cmd))
    return list(data.get("streams") or [])


def list_streams(video: Path) -> None:
    streams = probe_subtitle_streams(video)
    print(f"Subtitle streams in {video}:")
    text_n = 0
    for n, s in enumerate(streams, 1):
        tags = s.get("tags") or {}
        codec = (s.get("codec_name") or "").lower()
        text = codec in TEXT_CODECS
        if text:
            text_n += 1
            mark = f"  text-track={text_n}"
        else:
            mark = "  (bitmap — not usable for autosync)"
        print(
            f"  #{n:<2} stream={s.get('index'):<3} codec={s.get('codec_name'):<18} "
            f"lang={tags.get('language') or '-':<5} title={tags.get('title') or '-'}{mark}"
        )


def resolve_reference_stream(video: Path, args: argparse.Namespace) -> tuple[int, str]:
    """Return (ffprobe_stream_index, label)."""
    streams = probe_subtitle_streams(video)
    if not streams:
        raise RuntimeError("no subtitle streams in video")

    if args.sub_track is not None:
        if args.sub_track < 1 or args.sub_track > len(streams):
            raise RuntimeError(
                f"--sub-track {args.sub_track} out of range (1..{len(streams)})"
            )
        s = streams[args.sub_track - 1]
        codec = (s.get("codec_name") or "").lower()
        if codec not in TEXT_CODECS:
            raise RuntimeError(
                f"subtitle #{args.sub_track} is {s.get('codec_name')} (bitmap). "
                "Autosync needs text (SubRip/ASS). "
                "Use --text-track 1 for the first SubRip, or --list-streams."
            )
        tags = s.get("tags") or {}
        lang = tags.get("language") or "?"
        return int(s["index"]), f"subtitle #{args.sub_track} (stream {s['index']}, {lang})"

    if args.text_track is not None:
        text_streams = [
            s for s in streams if (s.get("codec_name") or "").lower() in TEXT_CODECS
        ]
        if args.text_track < 1 or args.text_track > len(text_streams):
            raise RuntimeError(
                f"--text-track {args.text_track} out of range (1..{len(text_streams)})"
            )
        s = text_streams[args.text_track - 1]
        tags = s.get("tags") or {}
        lang = tags.get("language") or "?"
        return int(s["index"]), f"text-track #{args.text_track} (stream {s['index']}, {lang})"

    if args.el_stream is not None:
        return args.el_stream, f"stream {args.el_stream}"

    # Default: first text SubRip (Shrek: stream 14, Greek).
    for s in streams:
        if (s.get("codec_name") or "").lower() in TEXT_CODECS:
            return int(s["index"]), f"first text stream {s['index']}"
    raise RuntimeError("no text subtitle streams in video")


def resolve_external(spec: str) -> tuple[str, list[Cue]]:
    if spec.startswith("os:") or spec.startswith("emb:") or spec.startswith("file:"):
        return spec, fetch_api_subtitle(spec)
    path = Path(spec).expanduser()
    if not path.is_file():
        raise FileNotFoundError(path)
    return str(path), load_srt_path(path)


def summarize(label: str, local: list[Cue], external: list[Cue], result: SyncResult) -> None:
    apply = result.confidence >= 0.40 and abs(result.delay_ms) >= 80
    print()
    print("=" * 72)
    print(label)
    print("-" * 72)
    print(f"  local (ref) cues : {len(local)}")
    print(f"  external cues    : {len(external)}")
    if local:
        print(f"  local first      : {format_clock(local[0].start_ms)}  {local[0].text.splitlines()[0][:60]!r}")
    if external:
        print(f"  external first   : {format_clock(external[0].start_ms)}  {external[0].text.splitlines()[0][:60]!r}")
    print(f"  delay            : {result.delay_ms:+d} ms  ({result.delay_ms / 1000:+.2f}s)")
    print(f"  confidence       : {result.confidence:.3f}  ({result.source})")
    print(f"  app would apply  : {'YES → Sync {:+d} ms'.format(result.delay_ms) if apply else 'NO (keep 0)'}")
    if result.source and "L=" in result.source:
        print(f"  name samples     : {result.source}")
    # Show how first few external cues shift under delay.
    if apply and external:
        print("  external after delay (first 3):")
        for c in external[:3]:
            shifted = c.start_ms + result.delay_ms
            print(f"    {format_clock(c.start_ms)} → {format_clock(shifted)}  {c.text.splitlines()[0][:50]!r}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--video", type=Path, default=DEFAULT_VIDEO, help="MKV with embedded subs")
    ap.add_argument(
        "--el-stream",
        type=int,
        default=None,
        help="ffprobe absolute stream index for the embedded reference (default: first SubRip, usually 14)",
    )
    ap.add_argument(
        "--sub-track",
        type=int,
        metavar="N",
        help="1-based subtitle track number in the file (1 = first subtitle stream). "
        "Overrides --el-stream. Text (SubRip/ASS) only — PGS/bitmap cannot be used.",
    )
    ap.add_argument(
        "--text-track",
        type=int,
        metavar="N",
        help="1-based among text subtitle streams only (1 = first SubRip/ASS in the file).",
    )
    ap.add_argument(
        "--external",
        default=str(DEFAULT_ET),
        help="External SRT path, or os:/emb:/file: API id (default: cached ET Shrek)",
    )
    ap.add_argument("--same-language", action="store_true", help="Enable same-language length gate")
    ap.add_argument("--list-streams", action="store_true", help="List subtitle streams and exit")
    ap.add_argument(
        "--all-cached",
        action="store_true",
        help="Compare EL reference against every cached *Shrek* SRT in the Coog subtitle cache",
    )
    ap.add_argument("--keep-el", type=Path, help="Write extracted EL SRT here")
    args = ap.parse_args()

    if not args.video.is_file():
        print(f"video not found: {args.video}", file=sys.stderr)
        return 1

    if args.list_streams:
        list_streams(args.video)
        return 0

    try:
        stream_index, ref_label = resolve_reference_stream(args.video, args)
    except RuntimeError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    print(f"Reference: {ref_label}  from {args.video.name}")
    local = extract_embedded(args.video, stream_index, args.keep_el)
    print(f"  → {len(local)} cues")
    if local:
        print(f"  sample: {local[0].text.splitlines()[0]!r}")

    if args.all_cached:
        paths = sorted(SUB_CACHE.glob("*[Ss]hrek*.srt"))
        if not paths:
            print(f"no Shrek SRTs in {SUB_CACHE}", file=sys.stderr)
            return 1
        for path in paths:
            try:
                external = load_srt_path(path)
            except Exception as exc:  # noqa: BLE001
                print(f"skip {path.name}: {exc}")
                continue
            result = estimate_subtitle_delay_ms(local, external, same_language=args.same_language)
            summarize(f"{ref_label}  vs  {path.name}", local, external, result)
        return 0

    label, external = resolve_external(args.external)
    result = estimate_subtitle_delay_ms(local, external, same_language=args.same_language)
    summarize(f"{ref_label}  vs  {label}", local, external, result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
