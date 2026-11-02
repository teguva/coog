const TOKEN_KEY = 'coog-web-token';

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || '';
}

export function setToken(token) {
  if (token) localStorage.setItem(TOKEN_KEY, token);
  else localStorage.removeItem(TOKEN_KEY);
}

export function headers(extra = {}) {
  const h = { ...extra };
  const tok = getToken();
  if (tok) h.Authorization = `Bearer ${tok}`;
  return h;
}

export async function api(path, opts = {}) {
  const res = await fetch(path, {
    ...opts,
    headers: headers(opts.headers || {}),
  });
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = { error: text || res.statusText };
  }
  if (!res.ok) {
    const err = new Error(data?.error || data?.detail || `${res.status}`);
    err.status = res.status;
    err.data = data;
    throw err;
  }
  return data;
}

export function mediaArtUrl(item, kind = 'poster') {
  const key = kind === 'backdrop' ? 'backdropUrl' : kind === 'logo' ? 'logoUrl' : 'posterUrl';
  let url = item?.[key] || '';
  if (!url && item?.id) {
    url = `/api/v1/media/${encodeURIComponent(item.id)}/${kind}`;
  }
  if (!url) return '';
  const tok = getToken();
  if (!tok) return url;
  const u = new URL(url, location.origin);
  u.searchParams.set('token', tok);
  return u.pathname + u.search;
}

export function streamUrl(session) {
  let url = session?.url || '';
  if (!url && session?.mediaId) {
    url = `/api/v1/media/${encodeURIComponent(session.mediaId)}/stream`;
  }
  if (!url) return '';
  const tok = getToken();
  if (!tok) return url;
  const u = new URL(url, location.origin);
  u.searchParams.set('token', tok);
  return u.pathname + u.search + (u.hash || '');
}

export function headline(item) {
  if (!item) return '';
  if (item.showTitle && item.kind === 'episode') {
    const se =
      item.season > 0
        ? `S${String(item.season).padStart(2, '0')}E${String(item.episode).padStart(2, '0')}`
        : '';
    return [item.showTitle, se, item.title].filter(Boolean).join(' · ');
  }
  return item.title || item.name || 'Untitled';
}

/** Mirror Android MediaItem.trailerMediaId() for GET /api/v1/media/{id}/trailer. */
export function trailerMediaId(item) {
  if (!item) return '';
  const imdb = String(item.imdbId || '').trim();
  const kind = String(item.kind || '').toLowerCase();
  const series = kind === 'series' || kind === 'episode' || kind === 'tv';
  if (imdb.startsWith('tt')) {
    return series ? `catalog:${imdb}:1:1` : `catalog:${imdb}`;
  }
  const id = String(item.id || '');
  if (id.startsWith('catalog:')) return id;
  return id;
}

export function trailerUrl(itemOrId) {
  const id = typeof itemOrId === 'string' ? itemOrId : trailerMediaId(itemOrId);
  if (!id) return '';
  let url = `/api/v1/media/${encodeURIComponent(id)}/trailer`;
  const tok = getToken();
  if (!tok) return url;
  const u = new URL(url, location.origin);
  u.searchParams.set('token', tok);
  return u.pathname + u.search;
}

export async function trailerStatus(id) {
  const path = `/api/v1/media/${encodeURIComponent(id)}/trailer`;
  const res = await fetch(path, { method: 'HEAD', headers: headers() });
  return res.status;
}

/** Wait until the MP4 cache is ready (HEAD 200). 404 means no trailer. */
export async function waitForTrailer(id, timeoutMs = 25_000) {
  if (!id) return false;
  const deadline = Date.now() + timeoutMs;
  while (true) {
    let code = 0;
    try {
      code = await trailerStatus(id);
    } catch {
      code = 0;
    }
    if (code === 200) return true;
    if (code === 404) return false;
    if (Date.now() >= deadline) {
      try {
        return (await trailerStatus(id)) === 200;
      } catch {
        return false;
      }
    }
    await new Promise((r) => setTimeout(r, 400));
  }
}

export function canPlayTrailer(item) {
  if (!item) return false;
  const imdb = String(item.imdbId || '').trim();
  if (imdb.startsWith('tt')) return true;
  const id = String(item.id || '');
  return id.startsWith('catalog:tt');
}

/** Browser-honest caps so awkward audio (E-AC3) remuxes to AAC instead of silent direct play. */
export function clientCapabilities() {
  const v = typeof document !== 'undefined' ? document.createElement('video') : null;
  const can = (type) => {
    if (!v) return false;
    const r = v.canPlayType(type);
    return r === 'probably' || r === 'maybe';
  };
  // Include hevc so awkward-audio remux (video copy + AAC) is allowed; without a
  // transcoder, omitting hevc only yields a hard "transcode required" error.
  const videoCodecs = ['h264', 'hevc'];
  if (can('video/webm; codecs="vp9"')) videoCodecs.push('vp9');
  if (can('video/mp4; codecs="av01.0.05M.08"')) videoCodecs.push('av1');
  // Never advertise AC3/E-AC3 — browsers rarely decode them from MKV/HLS.
  return {
    videoCodecs,
    audioCodecs: ['aac', 'mp3', 'opus'],
    containers: ['mp4', 'webm'],
    hdr: [],
  };
}

export function isHlsSession(session) {
  const method = String(session?.method || '').toLowerCase();
  if (method === 'remux' || method === 'progressive') return true;
  const url = String(session?.url || '');
  const ctype = String(session?.contentType || '').toLowerCase();
  return (
    url.includes('.m3u8') ||
    url.includes('/remux/') ||
    url.includes('/progressive/') ||
    ctype.includes('mpegurl')
  );
}
