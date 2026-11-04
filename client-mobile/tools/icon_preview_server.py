#!/usr/bin/env python3
"""Precise Coog icon preview — same SVG raster path as gen-android-icons.py.

  python3 client-mobile/tools/icon_preview_server.py

Opens http://127.0.0.1:8765 with live previews that call place() from
gen-android-icons.py (rsvg-convert → full artboard → fill), then apply
Android adaptive masks (circle / Material squircle) and splash proportions.
"""

from __future__ import annotations

import importlib.util
import io
import json
import threading
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from PIL import Image, ImageDraw

TOOLS = Path(__file__).resolve().parent
HOST, PORT = "127.0.0.1", 8765
BG = (7, 7, 8, 255)

# 108dp adaptive canvas @ 4x for sharp on-screen preview
ADAPTIVE_PX = 432
# Android 12 splash icon slot ~240dp @ 3x
SPLASH_SLOT_PX = 720
SPLASH_ICON_PX = 480  # icon plate inside splash (approx system circle)


def load_gen():
    spec = importlib.util.spec_from_file_location("gen_android_icons", TOOLS / "gen-android-icons.py")
    mod = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(mod)
    return mod


GEN = load_gen()


def circle_mask(size: int) -> Image.Image:
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse((0, 0, size - 1, size - 1), fill=255)
    return m


def squircle_mask(size: int) -> Image.Image:
    """Material-ish squircle (continuous corner) used by many launchers."""
    # Superellipse |x/a|^n + |y/a|^n = 1 with n≈5, rasterized.
    import numpy as np

    a = (size - 1) / 2.0
    n = 5.0
    ys, xs = np.mgrid[0:size, 0:size]
    nx = (xs - a) / a
    ny = (ys - a) / a
    inside = (np.abs(nx) ** n + np.abs(ny) ** n) <= 1.0
    return Image.fromarray((inside.astype(np.uint8) * 255), mode="L")


def safe_zone_overlay(size: int) -> Image.Image:
    """66dp safe zone on 108dp canvas → diameter = size * (66/108)."""
    overlay = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(overlay)
    diam = size * 66 / 108
    pad = (size - diam) / 2
    bbox = (pad, pad, pad + diam, pad + diam)
    d.ellipse(bbox, outline=(255, 64, 64, 160), width=max(2, size // 120))
    return overlay


def adaptive_preview(fill: float, shape: str, show_safe: bool) -> Image.Image:
    fg = GEN.place(ADAPTIVE_PX, fill)
    plate = Image.new("RGBA", (ADAPTIVE_PX, ADAPTIVE_PX), BG)
    plate = Image.alpha_composite(plate, fg)
    mask = circle_mask(ADAPTIVE_PX) if shape == "circle" else squircle_mask(ADAPTIVE_PX)
    out = Image.new("RGBA", (ADAPTIVE_PX, ADAPTIVE_PX), (0, 0, 0, 0))
    out.paste(plate, (0, 0), mask)
    if show_safe:
        out = Image.alpha_composite(out, safe_zone_overlay(ADAPTIVE_PX))
    return out


def splash_preview(fill: float) -> Image.Image:
    # Full splash window (phone-ish) + centered icon circle using same splash asset logic.
    w, h = 480, 960
    screen = Image.new("RGBA", (w, h), BG)
    icon = GEN.place(SPLASH_ICON_PX, fill)
    plate = Image.new("RGBA", (SPLASH_ICON_PX, SPLASH_ICON_PX), BG)
    plate = Image.alpha_composite(plate, icon)
    plate.putalpha(circle_mask(SPLASH_ICON_PX))
    # Scale icon circle to ~240dp equivalent on this preview width (≈ half width).
    target = int(w * 0.42)
    plate = plate.resize((target, target), Image.Resampling.LANCZOS)
    x = (w - target) // 2
    y = int(h * 0.38) - target // 2
    screen.alpha_composite(plate, (x, y))
    return screen


def in_app_preview(fill: float) -> Image.Image:
    # Top chrome strip with logo at Nav-ish size using exact place().
    bar_h, logo = 96, 64
    bar = Image.new("RGBA", (480, bar_h), (0, 0, 0, 180))
    mark = GEN.place(logo, fill)
    bar.alpha_composite(mark, (24, (bar_h - logo) // 2))
    return bar


def png_bytes(im: Image.Image) -> bytes:
    buf = io.BytesIO()
    im.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<title>Coog icon preview (exact raster)</title>
<style>
:root { color-scheme: dark; }
body { margin:0; font:14px/1.4 system-ui,sans-serif; background:#070708; color:#eee; padding:20px; }
h1 { font-size:18px; margin:0 0 6px; }
p { color:#9a9a9e; margin:0 0 16px; max-width:720px; }
.controls { display:grid; gap:12px; grid-template-columns:repeat(auto-fit,minmax(240px,1fr));
  background:#141416; padding:14px; border-radius:12px; margin-bottom:18px; }
label { display:flex; flex-direction:column; gap:6px; }
label span { display:flex; justify-content:space-between; }
.row { display:flex; flex-wrap:wrap; gap:24px; align-items:flex-start; }
.card h2 { font-size:11px; letter-spacing:.05em; text-transform:uppercase; color:#9a9a9e; margin:0 0 8px; }
.card img { background:#222; display:block; }
.card.splash img { background:#070708; height:420px; width:auto; }
code { display:block; background:#0c0c0e; padding:12px; border-radius:8px; margin-top:16px; white-space:pre; }
button { margin-top:10px; margin-right:8px; padding:10px 14px; border:0; border-radius:8px;
  background:#fff; color:#111; font-weight:600; cursor:pointer; }
.chk { flex-direction:row; align-items:center; gap:8px; }
</style>
</head>
<body>
<h1>Exact icon preview</h1>
<p>
Uses the same <code style="display:inline;padding:2px 6px">rsvg-convert</code> +
<code style="display:inline;padding:2px 6px">place()</code> path as
<code style="display:inline;padding:2px 6px">gen-android-icons.py</code>, then masks with
Pixel circle / Material squircle. Red ring = Android 66dp safe zone on 108dp.
</p>
<div class="controls">
  <label><span>Launcher fill <strong id="lv">1.10</strong></span>
    <input id="launcher" type="range" min="0.50" max="1.30" step="0.01" value="1.10"/></label>
  <label><span>Splash fill <strong id="sv">1.00</strong></span>
    <input id="splash" type="range" min="0.50" max="1.30" step="0.01" value="1.00"/></label>
  <label><span>In-app fill <strong id="iv">1.20</strong></span>
    <input id="inapp" type="range" min="0.50" max="1.40" step="0.01" value="1.20"/></label>
  <label class="chk"><input id="safe" type="checkbox" checked/> Show safe-zone ring</label>
</div>
<div class="row">
  <div class="card"><h2>Pixel circle</h2><img id="c" width="216" height="216" alt="circle"/></div>
  <div class="card"><h2>Squircle</h2><img id="q" width="216" height="216" alt="squircle"/></div>
  <div class="card splash"><h2>Launch splash</h2><img id="s" alt="splash"/></div>
  <div class="card"><h2>In-app chrome</h2><img id="a" width="360" alt="app"/></div>
</div>
<code id="out"></code>
<button id="copy">Copy constants</button>
<button id="apply">Write constants + regenerate APK icons</button>
<script>
const launcher=document.getElementById('launcher');
const splash=document.getElementById('splash');
const inapp=document.getElementById('inapp');
const safe=document.getElementById('safe');
let t=null;
function vals(){return{
  launcher:Number(launcher.value).toFixed(2),
  splash:Number(splash.value).toFixed(2),
  inapp:Number(inapp.value).toFixed(2),
  safe:safe.checked?'1':'0'
};}
function sync(){
  const v=vals();
  document.getElementById('lv').textContent=v.launcher;
  document.getElementById('sv').textContent=v.splash;
  document.getElementById('iv').textContent=v.inapp;
  document.getElementById('out').textContent=
`LAUNCHER_FG_FILL = ${v.launcher}
LEGACY_ICON_FILL = ${v.launcher}
SPLASH_FG_FILL = ${v.splash}
IN_APP_LOGO_FILL = ${v.inapp}`;
  const q=`l=${v.launcher}&s=${v.splash}&a=${v.inapp}&safe=${v.safe}&t=${Date.now()}`;
  document.getElementById('c').src='/preview/circle?'+q;
  document.getElementById('q').src='/preview/squircle?'+q;
  document.getElementById('s').src='/preview/splash?'+q;
  document.getElementById('a').src='/preview/inapp?'+q;
}
function schedule(){clearTimeout(t); t=setTimeout(sync, 80);}
for (const el of [launcher,splash,inapp,safe]) el.addEventListener('input', schedule);
document.getElementById('copy').onclick=async()=>{
  await navigator.clipboard.writeText(document.getElementById('out').textContent);
};
document.getElementById('apply').onclick=async()=>{
  const v=vals();
  const r=await fetch('/apply',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({launcher:v.launcher,splash:v.splash,inapp:v.inapp})});
  const j=await r.json();
  document.getElementById('apply').textContent=j.ok?'Icons regenerated':'Failed';
  setTimeout(()=>document.getElementById('apply').textContent='Write constants + regenerate APK icons',1500);
  sync();
};
sync();
</script>
</body>
</html>
"""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        u = urlparse(self.path)
        if u.path in ("/", "/index.html"):
            body = HTML.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if u.path.startswith("/preview/"):
            qs = parse_qs(u.query)
            kind = u.path.rsplit("/", 1)[-1]
            fill_l = float(qs.get("l", ["1.10"])[0])
            fill_s = float(qs.get("s", ["1.00"])[0])
            fill_a = float(qs.get("a", ["1.20"])[0])
            show_safe = qs.get("safe", ["1"])[0] != "0"
            if kind == "circle":
                im = adaptive_preview(fill_l, "circle", show_safe)
            elif kind == "squircle":
                im = adaptive_preview(fill_l, "squircle", show_safe)
            elif kind == "splash":
                im = splash_preview(fill_s)
            elif kind == "inapp":
                im = in_app_preview(fill_a)
            else:
                self.send_error(404)
                return
            data = png_bytes(im)
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        self.send_error(404)

    def do_POST(self):
        if self.path != "/apply":
            self.send_error(404)
            return
        n = int(self.headers.get("Content-Length", "0"))
        payload = json.loads(self.rfile.read(n).decode())
        launcher = float(payload["launcher"])
        splash = float(payload["splash"])
        inapp = float(payload["inapp"])
        path = TOOLS / "gen-android-icons.py"
        text = path.read_text()
        for name, val in (
            ("LAUNCHER_FG_FILL", launcher),
            ("LEGACY_ICON_FILL", launcher),
            ("SPLASH_FG_FILL", splash),
            ("IN_APP_LOGO_FILL", inapp),
        ):
            import re

            text = re.sub(
                rf"^{name} = .*$",
                f"{name} = {val:.2f}",
                text,
                count=1,
                flags=re.M,
            )
        path.write_text(text)
        # reload module constants + regenerate
        global GEN
        GEN = load_gen()
        GEN.main()
        body = json.dumps({"ok": True}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    url = f"http://{HOST}:{PORT}/"
    print(f"Precise icon preview: {url}")
    print("Same place() as gen-android-icons.py + adaptive masks.")
    threading.Timer(0.4, lambda: webbrowser.open(url)).start()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
