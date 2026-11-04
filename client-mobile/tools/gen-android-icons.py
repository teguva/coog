#!/usr/bin/env python3
"""Generate Coog launcher / splash / in-app logos from the SVG master.

  python3 client-mobile/tools/gen-android-icons.py

Source: logos/logo-app.svg

The SVG already includes an invisible square border for centering — render the
full artboard and scale it. Do NOT crop to opaque pixels or re-center by COM.
"""

from __future__ import annotations

import shutil
import subprocess
import tempfile
from pathlib import Path

from PIL import Image

# --- tune these (scale of full SVG artboard inside the Android canvas) ---
# SVG already has an invisible centering box / padding — prefer 1.0 so we don't
# shrink the mark twice. Lower only if adaptive masks clip the soft edges.
LAUNCHER_FG_FILL = 0.80
LEGACY_ICON_FILL = 0.80
SPLASH_FG_FILL = 0.60
IN_APP_LOGO_FILL = 1.25

ROOT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"
REPO = Path(__file__).resolve().parents[2]  # coog/
SVG = REPO / "logos" / "logo-app.svg"


def render_svg(px: int) -> Image.Image:
    """Rasterize the full SVG viewBox (keeps invisible centering box)."""
    if not SVG.is_file():
        raise SystemExit(f"missing SVG: {SVG}")
    rsvg = shutil.which("rsvg-convert")
    if not rsvg:
        raise SystemExit("rsvg-convert not on PATH (install librsvg)")
    with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as tmp:
        out = Path(tmp.name)
    try:
        subprocess.run(
            [rsvg, "-w", str(px), "-h", str(px), str(SVG), "-o", str(out)],
            check=True,
        )
        return Image.open(out).convert("RGBA")
    finally:
        out.unlink(missing_ok=True)


def place(size: int, fill: float, bg: tuple[int, int, int, int] | None = None) -> Image.Image:
    """Scale the full SVG artboard into a size×size canvas (centered)."""
    canvas = Image.new("RGBA", (size, size), bg if bg is not None else (0, 0, 0, 0))
    art = max(1, int(round(size * fill)))
    # Render SVG directly at target pixel size — sharp, no crop/COM shift.
    mark = render_svg(art)
    if mark.size != (art, art):
        mark = mark.resize((art, art), Image.Resampling.LANCZOS)
    x = (size - art) // 2
    y = (size - art) // 2
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    layer.paste(mark, (x, y))
    return Image.alpha_composite(canvas, layer)


def main() -> None:
    if not SVG.is_file():
        raise SystemExit(f"missing {SVG}")
    bg = (7, 7, 8, 255)

    fg_sizes = {
        "mipmap-mdpi": 108,
        "mipmap-hdpi": 162,
        "mipmap-xhdpi": 216,
        "mipmap-xxhdpi": 324,
        "mipmap-xxxhdpi": 432,
    }
    # Denser splash buckets — system splash slot is ~240dp; avoid upscaling soft marks.
    splash_sizes = {
        "mipmap-mdpi": 192,
        "mipmap-hdpi": 288,
        "mipmap-xhdpi": 384,
        "mipmap-xxhdpi": 576,
        "mipmap-xxxhdpi": 768,
    }
    legacy_sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    drawable_sizes = {
        "drawable-xhdpi": 128,
        "drawable-xxhdpi": 192,
        "drawable-xxxhdpi": 256,
    }

    for folder, size in fg_sizes.items():
        d = ROOT / folder
        d.mkdir(parents=True, exist_ok=True)
        place(size, LAUNCHER_FG_FILL).save(d / "ic_launcher_foreground.png", optimize=True)

    for folder, size in splash_sizes.items():
        d = ROOT / folder
        d.mkdir(parents=True, exist_ok=True)
        place(size, SPLASH_FG_FILL).save(d / "ic_splash_logo.png", optimize=True)

    for folder, size in legacy_sizes.items():
        d = ROOT / folder
        d.mkdir(parents=True, exist_ok=True)
        icon = place(size, LEGACY_ICON_FILL, bg=bg)
        icon.save(d / "ic_launcher.png", optimize=True)
        icon.save(d / "ic_launcher_round.png", optimize=True)

    for folder, size in drawable_sizes.items():
        d = ROOT / folder
        d.mkdir(parents=True, exist_ok=True)
        place(size, LEGACY_ICON_FILL, bg=bg).save(d / "ic_launcher.png", optimize=True)
        place(size, IN_APP_LOGO_FILL).save(d / "ic_coog_logo.png", optimize=True)

    print(
        "ok",
        f"svg={SVG.name}",
        f"full_artboard=yes",
        f"launcher_fg={LAUNCHER_FG_FILL}",
        f"splash={SPLASH_FG_FILL}",
        f"legacy={LEGACY_ICON_FILL}",
        f"in_app={IN_APP_LOGO_FILL}",
    )


if __name__ == "__main__":
    main()
