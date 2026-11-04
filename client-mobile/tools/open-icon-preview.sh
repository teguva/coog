#!/usr/bin/env bash
set -euo pipefail
systemctl --user start coog-icon-preview.service 2>/dev/null || true
if ! curl -sf -o /dev/null http://127.0.0.1:8765/; then
  systemctl --user start coog-icon-preview.service || \
    python3 -u /home/priit/Projects/coog/client-mobile/tools/icon_preview_server.py &
  sleep 1
fi
xdg-open http://127.0.0.1:8765/
