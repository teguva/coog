<script>
  const NAV = [
    { id: 'overview', label: 'Overview' },
    { id: 'activity', label: 'Activity' },
    { id: 'downloads', label: 'Downloads' },
    { id: 'library', label: 'Library' },
    { id: 'cache', label: 'Cache' },
    { id: 'maize', label: 'Maize' },
    { id: 'interactive', label: 'Interactive' },
    { id: 'taste', label: 'Match' },
    { id: 'subtitles', label: 'Subtitles' },
    { id: 'streaming', label: 'Streaming' },
  ];
  const PROVIDERS = [
    'yts', 'eztv', 'rarbg', '1337x', 'thepiratebay', 'kickasstorrents',
    'torrentgalaxy', 'magnetdl', 'rutor', 'rutracker', 'nyaasi', 'limetorrents',
  ];
  const QUALITIES = ['threed', 'cam', 'scr', '480p', '720p', '1080p', '4k', 'hdr', 'dolbyvision'];

  let page = $state(localStorage.getItem('coog-admin-page') || 'overview');
  let token = $state(localStorage.getItem('coog-token') || '');
  let authStatus = $state('unknown'); // unknown | ok | unauthorized | error
  let health = $state(null);
  let healthError = $state('');
  let stats = $state(null);
  let statsError = $state('');
  let activity = $state([]);
  let jobs = $state([]);
  let jobUrl = $state('');
  let jobBusy = $state(false);
  let jobError = $state('');
  let expanded = $state({});
  let items = $state([]);
  let selected = $state(null);
  let rematchImdb = $state('');
  let matchBusy = $state(false);
  let matchError = $state('');
  let scanning = $state(false);
  let scanResult = $state(null);
  let loadError = $state('');
  let libQuery = $state('');
  let libKind = $state('all');
  let streaming = $state(null);
  let streamingSaved = $state(null);
  let rdToken = $state('');
  let streamBusy = $state(false);
  let streamError = $state('');
  let deleting = $state(false);
  let deleteError = $state('');
  let taste = $state(null);
  let tasteError = $state('');
  let tasteBusy = $state(false);
  let subSettings = $state(null);
  let subSaved = $state(null);
  let subError = $state('');
  let subBusy = $state(false);
  let subApiKey = $state('');
  let subPassword = $state('');
  let subLangs = $state('en');
  let continueItems = $state([]);
  let continueError = $state('');
  let continueBusy = $state('');
  let cacheStats = $state(null);
  let cacheError = $state('');
  let cacheBusy = $state('');
  let maize = $state(null);
  let maizeError = $state('');
  let maizeBusy = $state(false);
  let maizePin = $state('');
  let maizePin2 = $state('');
  let interactiveEngine = $state(null);
  let interactiveError = $state('');
  let interactiveBusy = $state(false);
  let toasts = $state([]);
  let toastSeq = 0;

  const headers = () => {
    const h = { Accept: 'application/json' };
    if (token) h.Authorization = `Bearer ${token}`;
    return h;
  };

  function go(id) {
    page = id;
    localStorage.setItem('coog-admin-page', id);
  }

  function toast(message, kind = 'ok') {
    const id = ++toastSeq;
    toasts = [...toasts, { id, message, kind }];
    setTimeout(() => {
      toasts = toasts.filter((t) => t.id !== id);
    }, 4200);
  }

  function saveToken() {
    localStorage.setItem('coog-token', token);
    refreshAll();
  }

  async function refreshHealth() {
    try {
      const res = await fetch('/health');
      health = await res.json();
      healthError = '';
    } catch (err) {
      health = null;
      healthError = String(err);
    }
  }

  async function refreshStats() {
    try {
      const res = await fetch('/api/v1/server/stats', { headers: headers() });
      if (res.status === 401) {
        authStatus = 'unauthorized';
        stats = null;
        statsError = '401 unauthorized';
        return;
      }
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      stats = await res.json();
      statsError = '';
      authStatus = 'ok';
    } catch (err) {
      statsError = String(err);
      if (authStatus !== 'unauthorized') authStatus = 'error';
    }
  }

  async function refreshActivity() {
    try {
      const res = await fetch('/api/v1/server/activity', { headers: headers() });
      if (!res.ok) throw new Error(`${res.status}`);
      const data = await res.json();
      activity = data.items || [];
    } catch {
      /* keep last */
    }
  }

  async function refreshJobs() {
    try {
      const res = await fetch('/api/v1/jobs', { headers: headers() });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      const data = await res.json();
      jobs = data.items || [];
      jobError = '';
    } catch (err) {
      jobError = String(err);
    }
  }

  async function refreshLibrary() {
    loadError = '';
    try {
      const res = await fetch('/api/v1/library', { headers: headers() });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      const data = await res.json();
      items = data.items || [];
    } catch (err) {
      loadError = String(err);
    }
  }

  async function refreshContinue() {
    try {
      const res = await fetch('/api/v1/catalog/continue', { headers: headers() });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      const data = await res.json();
      continueItems = data.items || [];
      continueError = '';
    } catch (err) {
      continueError = String(err);
    }
  }

  async function clearContinueItem(item) {
    const key = item.id || `${item.imdbId}:${item.mediaId}`;
    continueBusy = key;
    continueError = '';
    try {
      const res = await fetch('/api/v1/playback/progress/clear', {
        method: 'POST',
        headers: { ...headers(), 'Content-Type': 'application/json' },
        body: JSON.stringify({
          imdbId: item.imdbId || '',
          tmdbId: item.tmdbId || 0,
          kind: item.kind || 'movie',
          title: item.title || item.showTitle || '',
          year: item.year || 0,
          season: item.season || 0,
          episode: item.episode || 0,
          mediaId: item.mediaId || '',
        }),
      });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      toast('Cleared continue watching');
      await refreshContinue();
    } catch (err) {
      continueError = String(err);
      toast(String(err), 'error');
    } finally {
      continueBusy = '';
    }
  }

  function snapshotStreaming(cfg) {
    if (!cfg) return null;
    return JSON.stringify({
      saveToLibrary: !!cfg.saveToLibrary,
      autoplayNextEpisode: !!cfg.autoplayNextEpisode,
      autoDownloadNextEpisode: !!cfg.autoDownloadNextEpisode,
      includeWebStreams: !!cfg.includeWebStreams,
      prefetchBeforeEndMinutes: cfg.prefetchBeforeEndMinutes,
      prefetchCount: cfg.prefetchCount,
      continueOverlaySeconds: cfg.continueOverlaySeconds,
      torrentioProviders: [...(cfg.torrentioProviders || [])].sort(),
      excludeQualities: [...(cfg.excludeQualities || [])].sort(),
    });
  }

  function snapshotSubtitles(cfg, langs) {
    if (!cfg) return null;
    return JSON.stringify({
      enabled: !!cfg.enabled,
      autoLoad: !!cfg.autoLoad,
      preferEmbedded: !!cfg.preferEmbedded,
      username: cfg.username || '',
      userAgent: cfg.userAgent || '',
      languages: langs,
    });
  }

  async function refreshStreaming() {
    try {
      const res = await fetch('/api/v1/settings/streaming', { headers: headers() });
      if (!res.ok) throw new Error(`${res.status}`);
      streaming = await res.json();
      streamingSaved = snapshotStreaming(streaming);
      rdToken = '';
      streamError = '';
    } catch (err) {
      streamError = String(err);
    }
  }

  async function refreshTaste() {
    try {
      const res = await fetch('/api/v1/taste', { headers: headers() });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      taste = await res.json();
      tasteError = '';
    } catch (err) {
      tasteError = String(err);
    }
  }

  async function saveTaste(patch) {
    tasteBusy = true;
    tasteError = '';
    try {
      const res = await fetch('/api/v1/taste', {
        method: 'PUT',
        headers: { ...headers(), 'Content-Type': 'application/json' },
        body: JSON.stringify(patch),
      });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      taste = await res.json();
      toast('Match settings saved');
    } catch (err) {
      tasteError = String(err);
      toast(String(err), 'error');
    } finally {
      tasteBusy = false;
    }
  }

  async function rebuildTaste() {
    tasteBusy = true;
    tasteError = '';
    try {
      const res = await fetch('/api/v1/taste/rebuild', { method: 'POST', headers: headers() });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      taste = await res.json();
      toast('Taste profile rebuilt');
    } catch (err) {
      tasteError = String(err);
      toast(String(err), 'error');
    } finally {
      tasteBusy = false;
    }
  }

  async function refreshSubtitles() {
    try {
      const res = await fetch('/api/v1/settings/subtitles', { headers: headers() });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      const data = await res.json();
      subSettings = data.settings || null;
      subLangs = (subSettings?.languages || ['en']).join(', ');
      subSaved = snapshotSubtitles(subSettings, subLangs);
      subApiKey = '';
      subPassword = '';
      subError = '';
    } catch (err) {
      subError = String(err);
    }
  }

  async function saveSubtitles() {
    subBusy = true;
    subError = '';
    try {
      const body = {
        enabled: !!subSettings?.enabled,
        autoLoad: !!subSettings?.autoLoad,
        preferEmbedded: !!subSettings?.preferEmbedded,
        username: subSettings?.username || '',
        userAgent: subSettings?.userAgent || '',
        languages: subLangs,
      };
      if (subApiKey.trim()) body.apiKey = subApiKey.trim();
      if (subPassword.trim()) body.password = subPassword.trim();
      const res = await fetch('/api/v1/settings/subtitles', {
        method: 'PUT',
        headers: { ...headers(), 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      const data = await res.json();
      subSettings = data.settings || null;
      subLangs = (subSettings?.languages || ['en']).join(', ');
      subSaved = snapshotSubtitles(subSettings, subLangs);
      subApiKey = '';
      subPassword = '';
      toast('Subtitles settings saved');
    } catch (err) {
      subError = String(err);
      toast(String(err), 'error');
    } finally {
      subBusy = false;
    }
  }

  async function refreshCache() {
    cacheError = '';
    try {
      const res = await fetch('/api/v1/catalog/cache/stats', { headers: headers() });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      cacheStats = await res.json();
    } catch (err) {
      cacheError = String(err);
    }
  }

  async function refreshCatalogCache() {
    cacheBusy = 'refresh';
    cacheError = '';
    try {
      const res = await fetch('/api/v1/catalog/cache/refresh', { method: 'POST', headers: headers() });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      const body = await res.json();
      cacheStats = body.stats || cacheStats;
      toast(`Refreshed ${body.refreshed ?? 0} stale titles`);
    } catch (err) {
      cacheError = String(err);
      toast(String(err), 'error');
    } finally {
      cacheBusy = '';
    }
  }

  async function clearCatalogCache() {
    if (!confirm('Clear catalog meta, artwork, and trailer caches?')) return;
    cacheBusy = 'clear';
    cacheError = '';
    try {
      const res = await fetch('/api/v1/catalog/cache/clear', { method: 'POST', headers: headers() });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      const body = await res.json();
      cacheStats = body.stats || null;
      toast('Catalog cache cleared');
    } catch (err) {
      cacheError = String(err);
      toast(String(err), 'error');
    } finally {
      cacheBusy = '';
    }
  }

  async function refreshMaize() {
    maizeError = '';
    try {
      const res = await fetch('/api/v1/settings/maize', { headers: headers() });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      maize = await res.json();
    } catch (err) {
      maizeError = String(err);
    }
  }

  async function saveMaizePin() {
    if (!maizePin.trim()) {
      toast('Enter a PIN', 'error');
      return;
    }
    if (maizePin !== maizePin2) {
      toast('PIN confirmation does not match', 'error');
      return;
    }
    maizeBusy = true;
    maizeError = '';
    try {
      const res = await fetch('/api/v1/settings/maize', {
        method: 'PUT',
        headers: { ...headers(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ pin: maizePin.trim() }),
      });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      maize = await res.json();
      maizePin = '';
      maizePin2 = '';
      toast('Maize PIN saved');
    } catch (err) {
      maizeError = String(err);
      toast(String(err), 'error');
    } finally {
      maizeBusy = false;
    }
  }

  async function clearMaizePin() {
    if (!confirm('Clear the Maize PIN? Adult unlock will stop working until a new PIN is set.')) return;
    maizeBusy = true;
    try {
      const res = await fetch('/api/v1/settings/maize', {
        method: 'PUT',
        headers: { ...headers(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ clearPin: true, lockAll: true }),
      });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      maize = await res.json();
      toast('Maize PIN cleared');
    } catch (err) {
      maizeError = String(err);
      toast(String(err), 'error');
    } finally {
      maizeBusy = false;
    }
  }

  async function lockAllMaize() {
    maizeBusy = true;
    try {
      const res = await fetch('/api/v1/settings/maize', {
        method: 'PUT',
        headers: { ...headers(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ lockAll: true }),
      });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      maize = await res.json();
      toast('All adult sessions locked');
    } catch (err) {
      maizeError = String(err);
      toast(String(err), 'error');
    } finally {
      maizeBusy = false;
    }
  }

  async function refreshInteractive() {
    interactiveError = '';
    try {
      const res = await fetch('/api/v1/interactive/engine', { headers: headers() });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      interactiveEngine = await res.json();
    } catch (err) {
      interactiveError = String(err);
    }
  }

  async function interactiveAction(path) {
    interactiveBusy = true;
    interactiveError = '';
    try {
      const res = await fetch(path, { method: 'POST', headers: headers(), body: '{}' });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      const data = await res.json();
      if (data.engine) interactiveEngine = data.engine;
      else await refreshInteractive();
      toast('OK');
    } catch (err) {
      interactiveError = String(err);
      toast(String(err), 'error');
    } finally {
      interactiveBusy = false;
    }
  }

  async function interactivePatch(idx, body) {
    interactiveBusy = true;
    try {
      const res = await fetch(`/api/v1/interactive/devices/${idx}`, {
        method: 'PATCH',
        headers: { ...headers(), 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      await refreshInteractive();
    } catch (err) {
      interactiveError = String(err);
      toast(String(err), 'error');
    } finally {
      interactiveBusy = false;
    }
  }

  async function interactiveForget(id) {
    if (!confirm(`Forget device ${id}?`)) return;
    interactiveBusy = true;
    try {
      const res = await fetch(`/api/v1/interactive/devices/id/${encodeURIComponent(id)}`, {
        method: 'DELETE',
        headers: headers(),
      });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      await refreshInteractive();
      toast('Device removed');
    } catch (err) {
      interactiveError = String(err);
      toast(String(err), 'error');
    } finally {
      interactiveBusy = false;
    }
  }

  async function refreshAll() {
    await Promise.all([
      refreshHealth(),
      refreshStats(),
      refreshActivity(),
      refreshJobs(),
      refreshLibrary(),
      refreshStreaming(),
      refreshTaste(),
      refreshSubtitles(),
      refreshContinue(),
      refreshCache(),
      refreshMaize(),
      refreshInteractive(),
    ]);
  }

  async function rescan() {
    scanning = true;
    loadError = '';
    try {
      const res = await fetch('/api/v1/library/rescan', { method: 'POST', headers: headers() });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      scanResult = await res.json();
      await refreshLibrary();
      await refreshStats();
    } catch (err) {
      loadError = String(err);
    } finally {
      scanning = false;
    }
  }

  async function openItem(id) {
    const res = await fetch(`/api/v1/library/${id}`, { headers: headers() });
    selected = await res.json();
    deleteError = '';
    matchError = '';
    rematchImdb = selected?.imdbId || '';
  }

  async function removeSelected(scope) {
    if (!selected?.id || deleting) return;
    const series = scope === 'series';
    const msg = series
      ? `Remove the entire series folder and every episode file for “${selected.showTitle || selected.title}”? This cannot be undone.`
      : selected.kind === 'episode'
        ? `Delete the episode file “${selected.title}”? Show artwork is kept.`
        : `Delete “${selected.title}” from disk, including its folder and metadata? This cannot be undone.`;
    if (!window.confirm(msg)) return;
    deleting = true;
    deleteError = '';
    try {
      const q = series ? '?scope=series' : '';
      const res = await fetch(`/api/v1/library/${selected.id}${q}`, { method: 'DELETE', headers: headers() });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      selected = null;
      await refreshLibrary();
    } catch (err) {
      deleteError = String(err);
    } finally {
      deleting = false;
    }
  }

  async function ignoreSelected() {
    if (!selected?.id || matchBusy) return;
    matchBusy = true;
    matchError = '';
    try {
      const res = await fetch(`/api/v1/library/${selected.id}/ignore`, { method: 'POST', headers: headers() });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      selected = await res.json();
      toast('Marked as ignored');
      await refreshLibrary();
    } catch (err) {
      matchError = String(err);
      toast(String(err), 'error');
    } finally {
      matchBusy = false;
    }
  }

  async function rematchSelected(withImdb) {
    if (!selected?.id || matchBusy) return;
    matchBusy = true;
    matchError = '';
    try {
      const body = {};
      if (withImdb) {
        const id = rematchImdb.trim();
        if (!id) throw new Error('Enter an IMDB id (tt…)');
        body.imdbId = id;
      }
      const res = await fetch(`/api/v1/library/${selected.id}/rematch`, {
        method: 'POST',
        headers: { ...headers(), 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      selected = await res.json();
      rematchImdb = selected?.imdbId || rematchImdb;
      toast(withImdb ? 'Matched to IMDB id' : 'Rematch finished');
      await refreshLibrary();
    } catch (err) {
      matchError = String(err);
      toast(String(err), 'error');
    } finally {
      matchBusy = false;
    }
  }

  async function enqueue() {
    const url = jobUrl.trim();
    if (!url || jobBusy) return;
    jobBusy = true;
    jobError = '';
    try {
      const res = await fetch('/api/v1/jobs', {
        method: 'POST',
        headers: { ...headers(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ type: 'ytdlp', url }),
      });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      jobUrl = '';
      await refreshJobs();
    } catch (err) {
      jobError = String(err);
      toast(String(err), 'error');
    } finally {
      jobBusy = false;
    }
  }

  async function jobAction(id, action) {
    jobError = '';
    try {
      const res = await fetch(`/api/v1/jobs/${id}/${action}`, { method: 'POST', headers: headers() });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      await refreshJobs();
    } catch (err) {
      jobError = String(err);
      toast(`${action} failed: ${err}`, 'error');
    }
  }

  async function cancelJob(id) { await jobAction(id, 'cancel'); }
  async function pauseJob(id) { await jobAction(id, 'pause'); }
  async function retryJob(id) { await jobAction(id, 'retry'); }

  async function saveStreaming() {
    streamBusy = true;
    streamError = '';
    try {
      const body = { ...streaming };
      if (rdToken.trim()) body.realDebridToken = rdToken.trim();
      const res = await fetch('/api/v1/settings/streaming', {
        method: 'PUT',
        headers: { ...headers(), 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      streaming = await res.json();
      streamingSaved = snapshotStreaming(streaming);
      rdToken = '';
      toast('Streaming settings saved');
    } catch (err) {
      streamError = String(err);
      toast(String(err), 'error');
    } finally {
      streamBusy = false;
    }
  }

  function upsertJob(job) {
    if (!job?.id) return;
    const next = jobs.filter((j) => j.id !== job.id);
    next.unshift(job);
    next.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
    jobs = next;
  }

  function jobLabel(job) {
    if (job.ready && job.status !== 'finished') return 'ready';
    return job.status || 'queued';
  }

  function fmtAgo(unix) {
    if (!unix || unix < 0) return 'never';
    const s = Math.max(0, Math.floor(Date.now() / 1000) - unix);
    if (s < 5) return 'just now';
    if (s < 60) return `${s}s ago`;
    if (s < 3600) return `${Math.floor(s / 60)}m ago`;
    return `${Math.floor(s / 3600)}h ago`;
  }

  function fmtTs(ms) {
    if (!ms) return '';
    const d = new Date(ms > 1e12 ? ms : ms * 1000);
    return d.toLocaleTimeString();
  }

  function fmtClock(ms) {
    if (!ms || ms < 0) return '';
    const total = Math.floor(ms / 1000);
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = total % 60;
    if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
    return `${m}:${String(s).padStart(2, '0')}`;
  }

  function bytes(n) {
    if (!n) return '—';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let v = n;
    let i = 0;
    while (v >= 1024 && i < units.length - 1) {
      v /= 1024;
      i += 1;
    }
    return `${v.toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
  }

  function toggleChip(listName, value) {
    const cur = streaming?.[listName] || [];
    streaming = {
      ...streaming,
      [listName]: cur.includes(value) ? cur.filter((x) => x !== value) : [...cur, value],
    };
  }

  const filteredItems = $derived(
    items.filter((item) => {
      if (libKind !== 'all' && item.kind !== libKind) return false;
      const q = libQuery.trim().toLowerCase();
      if (!q) return true;
      const hay = `${item.title} ${item.showTitle || ''} ${item.path || ''}`.toLowerCase();
      return hay.includes(q);
    }),
  );

  const streamDirty = $derived(
    !!streaming && (
      snapshotStreaming(streaming) !== streamingSaved || !!rdToken.trim()
    ),
  );

  const subDirty = $derived(
    !!subSettings && (
      snapshotSubtitles(subSettings, subLangs) !== subSaved
      || !!subApiKey.trim()
      || !!subPassword.trim()
    ),
  );

  const tokenPresent = $derived(!!(token || localStorage.getItem('coog-token')));

  const needsMatchActions = $derived(
    ['unmatched', 'suggested'].includes(String(selected?.matchStatus || '').toLowerCase()),
  );

  $effect(() => {
    refreshAll();
    const tick = setInterval(() => {
      refreshHealth();
      refreshStats();
      if (page === 'activity') refreshActivity();
      if (page === 'downloads' || page === 'overview') refreshJobs();
      if (page === 'overview') refreshContinue();
      if (page === 'cache') refreshCache();
      if (page === 'maize') refreshMaize();
      if (page === 'interactive') refreshInteractive();
    }, 8000);
    return () => clearInterval(tick);
  });

  $effect(() => {
    const tok = token;
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = `${proto}//${location.host}/ws${tok ? `?token=${encodeURIComponent(tok)}` : ''}`;
    const ws = new WebSocket(url);
    ws.onmessage = (ev) => {
      try {
        const msg = JSON.parse(ev.data);
        if (msg.type === 'activity' && msg.activity) {
          activity = [msg.activity, ...activity.filter((a) => a.ts !== msg.activity.ts)].slice(0, 500);
        }
        if (msg.job?.id && String(msg.type || '').startsWith('job.')) {
          upsertJob(msg.job);
        }
        if (msg.type === 'library.changed') refreshLibrary();
      } catch {
        /* ignore */
      }
    };
    return () => ws.close();
  });
</script>

<div class="shell">
  <aside>
    <div class="brand">
      <h1>Coog</h1>
      <p>Ops console</p>
    </div>
    <nav>
      {#each NAV as item}
        <button class:active={page === item.id} onclick={() => go(item.id)}>{item.label}</button>
      {/each}
    </nav>
    <label class="token">
      Token
      <input bind:value={token} placeholder="COOG_AUTH_TOKEN" onchange={saveToken} />
    </label>
    <div class="auth-status" class:ok={authStatus === 'ok'} class:bad={authStatus === 'unauthorized' || authStatus === 'error'}>
      {#if authStatus === 'ok'}
        <span class="dot ok"></span>
        <span>authorized</span>
      {:else if authStatus === 'unauthorized'}
        <span class="dot bad"></span>
        <span>401 — set COOG_AUTH_TOKEN on the server and paste it here</span>
      {:else if authStatus === 'error'}
        <span class="dot bad"></span>
        <span>API unreachable</span>
      {:else}
        <span class="dot"></span>
        <span>checking auth…</span>
      {/if}
    </div>
    <div class="health">
      <span class="dot" class:ok={health?.status === 'ok'} class:bad={!!healthError}></span>
      {#if health}
        <span>{health.status} · {health.version}</span>
      {:else}
        <span>{healthError || 'checking…'}</span>
      {/if}
    </div>
  </aside>

  <main class:has-savebar={(page === 'streaming' && streamDirty) || (page === 'subtitles' && subDirty)}>
    {#if page === 'overview'}
      <header>
        <div>
          <h2>Overview</h2>
          <p class="muted">Worker, Real-Debrid, jobs, and catalog health in one place.</p>
        </div>
        <button class="ghost" onclick={() => { refreshStats(); refreshContinue(); }}>Refresh</button>
      </header>
      {#if statsError}<p class="error">{statsError}</p>{/if}
      {#if authStatus === 'unauthorized'}
        <div class="banner warn">
          <strong>Unauthorized</strong>
          <span>The API returned 401. Export <code>COOG_AUTH_TOKEN</code> on the host and paste the same value in the sidebar Token field.</span>
        </div>
      {/if}
      <div class="cards">
        <article class="card">
          <h3>API</h3>
          <p class="stat">{stats?.version || health?.version || '—'}</p>
          <p class="muted">{stats?.ffmpeg || 'ffmpeg unknown'}</p>
        </article>
        <article class="card">
          <h3>Worker</h3>
          {#if stats?.worker?.updatedAt}
            <p class="stat" class:bad={stats.worker.stale}>{stats.worker.stale ? 'stale' : 'alive'}</p>
            <p class="muted">pid {stats.worker.pid} · {fmtAgo(stats.worker.updatedAt)}</p>
          {:else}
            <p class="stat bad">offline</p>
            <p class="muted">no heartbeat yet</p>
          {/if}
        </article>
        <article class="card">
          <h3>Library</h3>
          <p class="stat">{stats?.mediaCount ?? items.length}</p>
          <p class="muted">
            {#if stats?.disk}
              {bytes(stats.disk.freeBytes)} free of {bytes(stats.disk.totalBytes)}
            {:else}
              {stats?.libraryPath || '—'}
            {/if}
          </p>
        </article>
        <article class="card">
          <h3>Jobs</h3>
          <p class="stat">{stats?.jobs?.active ?? 0} active</p>
          <p class="muted">{stats?.jobs?.error ?? 0} failed · {stats?.jobs?.finished ?? 0} finished</p>
        </article>
        <article class="card">
          <h3>Real-Debrid</h3>
          {#if stats?.realDebrid?.configured}
            <p class="stat">{stats.realDebrid.username || 'connected'}</p>
            <p class="muted">
              {#if stats.realDebrid.error}
                {stats.realDebrid.error}
              {:else}
                {stats.realDebrid.premium ? 'premium' : stats.realDebrid.type || 'account'}
                {#if stats.realDebrid.expiration} · expires {new Date(stats.realDebrid.expiration).toLocaleDateString()}{/if}
              {/if}
            </p>
          {:else}
            <p class="stat">not set</p>
            <p class="muted">Add a token under Streaming.</p>
          {/if}
        </article>
        <article class="card">
          <h3>Catalog</h3>
          {#if stats?.catalogError}
            <p class="stat bad">error</p>
            <p class="muted">{stats.catalogError}</p>
          {:else if stats?.catalogErrorAt}
            <p class="stat">ok</p>
            <p class="muted">last trending fetch succeeded</p>
          {:else}
            <p class="stat">idle</p>
            <p class="muted">no catalog fetch yet</p>
          {/if}
        </article>
      </div>

      <h3>Config</h3>
      <article class="card config-card">
        <dl class="facts">
          <div><dt>Library path</dt><dd><code>{stats?.libraryPath || '—'}</code></dd></div>
          <div><dt>Data path</dt><dd><code>{stats?.dataPath || '—'}</code></dd></div>
          <div><dt>Admin token</dt><dd>{tokenPresent ? 'present in localStorage' : 'not set in browser'}</dd></div>
          <div><dt>Auth probe</dt><dd class:bad={authStatus === 'unauthorized'}>{authStatus}</dd></div>
          <div><dt>Catalog / TMDB</dt><dd class:bad={!!stats?.catalogError}>{stats?.catalogError || 'ok'}</dd></div>
        </dl>
      </article>

      <h3>Continue watching</h3>
      {#if continueError}<p class="error">{continueError}</p>{/if}
      <div class="transfers">
        {#each continueItems as item}
          <article class="transfer">
            <div class="transfer-main">
              <strong>{item.showTitle ? `${item.showTitle} — ${item.title}` : item.title}</strong>
              <div class="transfer-meta">
                <span class="pill">{item.kind}</span>
                {#if item.season || item.episode}
                  <span class="muted">S{item.season}E{item.episode}</span>
                {/if}
                {#if item.positionMs}
                  <span class="muted">{fmtClock(item.positionMs)}{#if item.durationMs} / {fmtClock(item.durationMs)}{/if}</span>
                {/if}
                {#if item.imdbId}<span class="muted">{item.imdbId}</span>{/if}
              </div>
            </div>
            <div class="transfer-actions">
              <button
                class="ghost danger"
                disabled={continueBusy === (item.id || `${item.imdbId}:${item.mediaId}`)}
                onclick={() => clearContinueItem(item)}
              >Clear</button>
            </div>
          </article>
        {:else}
          <p class="muted">No continue-watching entries.</p>
        {/each}
      </div>
    {/if}

    {#if page === 'activity'}
      <header>
        <div>
          <h2>Activity</h2>
          <p class="muted">Client, API, and worker failures in one feed. Tokens are redacted.</p>
        </div>
        <button class="ghost" onclick={refreshActivity}>Refresh</button>
      </header>
      <div class="log">
        {#each activity as ev}
          <article class="log-row" class:error={ev.level === 'error'} class:warn={ev.level === 'warn'}>
            <span class="log-meta">{fmtTs(ev.ts)} · {ev.source} · {ev.type}</span>
            <p>{ev.message}</p>
            {#if ev.jobId || ev.mediaId || ev.sessionId}
              <p class="muted">
                {#if ev.jobId}job {ev.jobId}{/if}
                {#if ev.mediaId} · media {ev.mediaId}{/if}
                {#if ev.sessionId} · session {ev.sessionId}{/if}
              </p>
            {/if}
          </article>
        {:else}
          <p class="muted">No events yet. Play something on the TV or queue a download.</p>
        {/each}
      </div>
    {/if}

    {#if page === 'downloads'}
      <header>
        <div>
          <h2>Downloads</h2>
          <p class="muted">Live over WebSocket. Cancel removes the transfer and its temp files. Finished downloads leave the library and drop out of this list.</p>
        </div>
        <button class="ghost" onclick={refreshJobs}>Refresh</button>
      </header>
      <div class="enqueue">
        <input bind:value={jobUrl} placeholder="https://…  (yt-dlp web stream)" onkeydown={(e) => e.key === 'Enter' && enqueue()} />
        <button onclick={enqueue} disabled={jobBusy || !jobUrl.trim()}>{jobBusy ? 'Queuing…' : 'Download'}</button>
      </div>
      {#if jobError}<p class="error">{jobError}</p>{/if}
      <div class="transfers">
        {#each jobs as job}
          <article class="transfer">
            <div class="transfer-main">
              <button class="title-btn" onclick={() => expanded = { ...expanded, [job.id]: !expanded[job.id] }}>
                <strong>{job.title || 'Untitled'}</strong>
                <span class="muted">{expanded[job.id] ? 'Hide' : 'Details'}</span>
              </button>
              <div class="transfer-meta">
                <span class="pill" class:ready={job.ready && job.status !== 'finished'} class:done={job.status === 'finished'} class:bad={job.status === 'error' || job.status === 'cancelled'}>
                  {jobLabel(job)}
                </span>
                <span class="pill">{job.type}</span>
                {#if job.type === 'debrid' || job.type === 'http'}
                  <span class="pill rd">Real-Debrid</span>
                {/if}
                <span class="muted">{Math.round((job.progress || 0) * 100)}%</span>
                {#if job.bufferedMs || job.expectedDurationMs}
                  <span class="muted">{fmtClock(job.bufferedMs)} / {fmtClock(job.expectedDurationMs) || '—'}</span>
                {/if}
                {#if job.expectedDurationMs && job.bufferedMs && job.bufferedMs < job.expectedDurationMs}
                  <span class="muted">{fmtClock(job.expectedDurationMs - job.bufferedMs)} left</span>
                {/if}
              </div>
              <div class="bar"><div class="bar-fill" style={`width: ${Math.min(100, Math.round((job.progress || 0) * 100))}%`}></div></div>
              {#if job.error}<p class="error">{job.error}</p>{/if}
              {#if expanded[job.id]}
                <dl class="facts">
                  <div><dt>IMDB</dt><dd>{job.imdbId || '—'}</dd></div>
                  <div><dt>Job</dt><dd><code>{job.id}</code></dd></div>
                  {#if job.mediaId}<div><dt>Media</dt><dd><code>{job.mediaId}</code></dd></div>{/if}
                </dl>
                {#if job.logTail}
                  <pre class="log-tail">{job.logTail}</pre>
                {:else}
                  <p class="muted">No stderr captured yet.</p>
                {/if}
              {/if}
            </div>
            <div class="transfer-actions">
              {#if job.status === 'error' || job.status === 'paused'}
                <button class="ghost" onclick={() => retryJob(job.id)}>{job.status === 'paused' ? 'Resume' : 'Retry'}</button>
              {/if}
              {#if job.status === 'queued' || job.status === 'downloading' || job.status === 'ready'}
                <button class="ghost" onclick={() => pauseJob(job.id)}>Pause</button>
              {/if}
              {#if job.status !== 'finished' && job.status !== 'cancelled' && job.status !== 'error'}
                <button class="ghost danger" onclick={() => cancelJob(job.id)}>Cancel</button>
              {/if}
            </div>
          </article>
        {:else}
          <p class="muted">No transfers. Play a trending title on the TV, or paste a URL above.</p>
        {/each}
      </div>
    {/if}

    {#if page === 'cache'}
      <header>
        <div>
          <h2>Catalog cache</h2>
          <p class="muted">On-disk metadata, multi-res artwork, and trailer files under the data path. Soft refresh re-fetches stale titles (default 30 days).</p>
        </div>
        <div class="toolbar">
          <button class="ghost" onclick={refreshCache}>Refresh stats</button>
          <button onclick={refreshCatalogCache} disabled={!!cacheBusy}>
            {cacheBusy === 'refresh' ? 'Refreshing…' : 'Refresh stale'}
          </button>
          <button class="ghost" onclick={clearCatalogCache} disabled={!!cacheBusy}>
            {cacheBusy === 'clear' ? 'Clearing…' : 'Clear cache'}
          </button>
        </div>
      </header>
      {#if cacheError}<p class="error">{cacheError}</p>{/if}
      <div class="cards">
        <article class="card">
          <h3>Titles</h3>
          <p class="stat">{cacheStats?.titles ?? '—'}</p>
          <p class="muted">catalog + show JSON files</p>
        </article>
        <article class="card">
          <h3>People</h3>
          <p class="stat">{cacheStats?.people ?? '—'}</p>
          <p class="muted">person credit caches</p>
        </article>
        <article class="card">
          <h3>Trailers</h3>
          <p class="stat">{cacheStats?.trailers ?? '—'}</p>
          <p class="muted">downloaded mp4 files</p>
        </article>
        <article class="card">
          <h3>Disk</h3>
          <p class="stat">{cacheStats?.bytes != null ? bytes(cacheStats.bytes) : '—'}</p>
          <p class="muted">meta + art + trailers · TTL {cacheStats?.ttlDays ?? 30}d</p>
        </article>
      </div>
    {/if}

    {#if page === 'maize'}
      <header>
        <div>
          <h2>Maize</h2>
          <p class="muted">Adult library lock. The TV unlocks with a long OK on the profile avatar. Maize/ is excluded from the public library until unlocked.</p>
        </div>
        <div class="toolbar">
          <button class="ghost" onclick={refreshMaize}>Refresh</button>
          <button class="ghost" onclick={lockAllMaize} disabled={maizeBusy}>Lock all sessions</button>
        </div>
      </header>
      {#if maizeError}<p class="error">{maizeError}</p>{/if}
      <div class="cards">
        <article class="card">
          <h3>PIN</h3>
          <p class="stat">{maize?.configured ? 'set' : 'not set'}</p>
          <p class="muted">argon2id · 4–12 digits</p>
        </article>
        <article class="card">
          <h3>Bucket</h3>
          <p class="stat"><code>{maize?.bucket || 'Maize'}</code></p>
          <p class="muted">under library root</p>
        </article>
        <article class="card">
          <h3>Idle lock</h3>
          <p class="stat">{maize?.idleMinutes ?? 20}m</p>
          <p class="muted">client timeout after unlock</p>
        </article>
      </div>
      <article class="card">
        <h3>Set PIN</h3>
        <div class="toolbar">
          <input type="password" inputmode="numeric" autocomplete="new-password" bind:value={maizePin} placeholder="New PIN" />
          <input type="password" inputmode="numeric" autocomplete="new-password" bind:value={maizePin2} placeholder="Confirm PIN" />
          <button onclick={saveMaizePin} disabled={maizeBusy}>Save PIN</button>
          <button class="ghost" onclick={clearMaizePin} disabled={maizeBusy || !maize?.configured}>Clear PIN</button>
        </div>
      </article>
    {/if}

    {#if page === 'interactive'}
      <header>
        <div>
          <h2>Interactive</h2>
          <p class="muted">Intiface / Buttplug engine hosted by coog-api. Pair toys here or on the TV Devices tab. Do not run Funplay’s engine against the same adapter at the same time.</p>
        </div>
        <div class="toolbar">
          <button class="ghost" onclick={refreshInteractive}>Refresh</button>
          <button onclick={() => interactiveAction('/api/v1/interactive/engine/scan/start')} disabled={interactiveBusy}>Scan</button>
          <button onclick={() => interactiveAction('/api/v1/interactive/engine/scan/pair')} disabled={interactiveBusy}>Pair</button>
          <button class="ghost" onclick={() => interactiveAction('/api/v1/interactive/engine/restart')} disabled={interactiveBusy}>Restart engine</button>
          <button class="ghost" onclick={() => interactiveAction('/api/v1/interactive/devices/forget-offline')} disabled={interactiveBusy}>Clear offline</button>
        </div>
      </header>
      {#if interactiveError}<p class="error">{interactiveError}</p>{/if}
      <div class="cards">
        <article class="card">
          <h3>Engine</h3>
          <p class="stat">{interactiveEngine?.running ? 'running' : 'stopped'}</p>
          <p class="muted">{interactiveEngine?.connected ? 'buttplug linked' : 'not linked'}</p>
        </article>
        <article class="card">
          <h3>Scan</h3>
          <p class="stat">{interactiveEngine?.scanning ? 'scanning' : interactiveEngine?.pairing ? `pairing ${interactiveEngine?.pairingSecondsLeft || 0}s` : 'idle'}</p>
          <p class="muted">
            phase {interactiveEngine?.reconnectPhase || 'idle'}
            {#if interactiveEngine?.reconnectReason} · {interactiveEngine.reconnectReason}{/if}
            {#if interactiveEngine?.reconnectAttempts} · attempts {interactiveEngine.reconnectAttempts}{/if}
            {#if interactiveEngine?.missingPaired} · missing {interactiveEngine.missingPaired}{/if}
          </p>
        </article>
        <article class="card">
          <h3>Devices</h3>
          <p class="stat">{(interactiveEngine?.trustedDevices || interactiveEngine?.devices || []).length}</p>
          <p class="muted">trusted / live</p>
        </article>
      </div>
      {#if interactiveEngine?.lastError}
        <p class="error">{interactiveEngine.lastError}</p>
      {/if}
      <article class="card">
        <h3>Device list</h3>
        {#each (interactiveEngine?.trustedDevices?.length ? interactiveEngine.trustedDevices : [...(interactiveEngine?.devices || []), ...(interactiveEngine?.knownDevices || [])]) as d}
          <div class="toolbar" style="margin-bottom:0.75rem;flex-wrap:wrap;gap:0.5rem;align-items:center">
            <strong>{d.name || d.deviceId}</strong>
            <span class="muted">{d.kind} · {d.status || (d.connected ? 'connected' : 'offline')}{#if d.batterySupported && d.batteryPercent >= 0} · {d.batteryPercent}%{/if} · int {d.intensity}% · off {d.offsetMs}ms</span>
            {#if d.connected && d.index >= 0}
              <button class="ghost" disabled={interactiveBusy} onclick={() => interactivePatch(d.index, { intensity: Math.max(10, (d.intensity || 100) - 10) })}>Int −</button>
              <button class="ghost" disabled={interactiveBusy} onclick={() => interactivePatch(d.index, { intensity: Math.min(200, (d.intensity || 100) + 10) })}>Int +</button>
              <button class="ghost" disabled={interactiveBusy} onclick={() => interactivePatch(d.index, { offsetMs: (d.offsetMs || 350) - 50 })}>Off −</button>
              <button class="ghost" disabled={interactiveBusy} onclick={() => interactivePatch(d.index, { offsetMs: (d.offsetMs || 350) + 50 })}>Off +</button>
              <button class="ghost" disabled={interactiveBusy} onclick={() => interactiveAction(`/api/v1/interactive/devices/${d.index}/test`)}>Test</button>
            {:else if d.deviceId}
              <button class="ghost" disabled={interactiveBusy} onclick={() => interactiveAction(`/api/v1/interactive/devices/id/${encodeURIComponent(d.deviceId)}/connect`)}>Connect</button>
            {/if}
            {#if d.deviceId}
              <button class="ghost" disabled={interactiveBusy} onclick={() => interactiveForget(d.deviceId)}>Remove</button>
            {/if}
          </div>
        {:else}
          <p class="muted">No devices yet. Click Pair and power on a toy.</p>
        {/each}
      </article>
    {/if}

    {#if page === 'library'}
      <header>
        <div>
          <h2>Library</h2>
          <p class="muted">Search and filter the on-disk catalog. Stream links are HTTP Range URLs the TV already uses.</p>
        </div>
        <div class="toolbar">
          <button onclick={rescan} disabled={scanning}>{scanning ? 'Scanning…' : 'Rescan'}</button>
          <button class="ghost" onclick={refreshLibrary}>Refresh</button>
        </div>
      </header>
      {#if scanResult}
        <p class="muted">indexed {scanResult.indexed}, probed {scanResult.probed}, removed {scanResult.removed}</p>
      {/if}
      {#if loadError}<p class="error">{loadError}</p>{/if}
      <div class="toolbar">
        <input bind:value={libQuery} placeholder="Search title or path" />
        <select bind:value={libKind}>
          <option value="all">All kinds</option>
          <option value="movie">Movies</option>
          <option value="episode">Episodes</option>
        </select>
      </div>
      <table>
        <thead>
          <tr>
            <th>Title</th>
            <th>Kind</th>
            <th>Match</th>
            <th>Codec</th>
            <th>Resolution</th>
            <th>Probe</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {#each filteredItems as item}
            <tr class="clickable" onclick={() => openItem(item.id)}>
              <td>{item.showTitle ? `${item.showTitle} — ${item.title}` : item.title}</td>
              <td>{item.kind}</td>
              <td>{item.matchStatus || '—'}</td>
              <td>{item.codecVideo || '—'} {item.hdr ? `· ${item.hdr}` : ''}</td>
              <td>{item.width && item.height ? `${item.width}×${item.height}` : '—'}</td>
              <td>{item.probeError || 'ok'}</td>
              <td><a href={item.streamUrl || `/api/v1/media/${item.id}/stream`} onclick={(e) => e.stopPropagation()}>stream</a></td>
            </tr>
          {:else}
            <tr><td colspan="7" class="muted">No items. Point COOG_LIBRARY_PATH at Videos and rescan.</td></tr>
          {/each}
        </tbody>
      </table>
      {#if selected}
        <section class="detail">
          <h3>{selected.title}</h3>
          <p class="muted"><code>{selected.path}</code></p>
          <p>match {selected.matchStatus || 'unmatched'}{selected.imdbId ? ` · ${selected.imdbId}` : ''}</p>
          {#if selected.tagline}<p>{selected.tagline}</p>{/if}
          <p>video {selected.codecVideo || '—'} · audio {selected.codecAudio || '—'} · {selected.contentType || '—'}</p>
          {#if selected.probeError}<p class="error">{selected.probeError}</p>{/if}
          <p><a href={selected.streamUrl || `/api/v1/media/${selected.id}/stream`}>playable stream</a></p>
          {#if matchError}<p class="error">{matchError}</p>{/if}
          {#if needsMatchActions}
            <div class="match-box">
              <p class="muted">This title is {selected.matchStatus}. Ignore hides it from catalog identity, or rematch against path/NFO / an IMDB id.</p>
              <div class="toolbar">
                <button class="ghost" onclick={() => rematchSelected(false)} disabled={matchBusy}>Rematch</button>
                <button class="ghost" onclick={ignoreSelected} disabled={matchBusy}>Ignore</button>
              </div>
              <div class="enqueue">
                <input bind:value={rematchImdb} placeholder="tt0123456" />
                <button onclick={() => rematchSelected(true)} disabled={matchBusy || !rematchImdb.trim()}>
                  {matchBusy ? 'Working…' : 'Set IMDB'}
                </button>
              </div>
            </div>
          {/if}
          {#if deleteError}<p class="error">{deleteError}</p>{/if}
          <div class="toolbar">
            <button class="ghost danger" onclick={() => removeSelected()} disabled={deleting}>
              {selected.kind === 'episode' ? 'Delete episode file' : 'Remove from library'}
            </button>
            {#if selected.kind === 'episode'}
              <button class="ghost danger" onclick={() => removeSelected('series')} disabled={deleting}>
                Remove entire series
              </button>
            {/if}
          </div>
        </section>
      {/if}
    {/if}

    {#if page === 'taste'}
      <header>
        <div>
          <h2>Match</h2>
          <p class="muted">The household taste profile the TV uses to score every card. Learned from your confirmed library and paused shows.</p>
        </div>
        <div class="toolbar">
          <button onclick={rebuildTaste} disabled={tasteBusy}>{tasteBusy ? 'Working…' : 'Rebuild'}</button>
          <button class="ghost" onclick={refreshTaste}>Refresh</button>
        </div>
      </header>
      {#if tasteError}<p class="error">{tasteError}</p>{/if}
      {#if taste}
        {#if taste.coldStart}
          <div class="banner">
            <strong>Cold start</strong>
            <span>Only {taste.titles} title{taste.titles === 1 ? '' : 's'} learned — Match falls back to the public rating until {taste.config?.minTitles} are confirmed{taste.config?.enabled ? '' : ' (personalization is off)'}.</span>
          </div>
        {/if}
        <div class="cards">
          <article class="card">
            <h3>Titles learned</h3>
            <p class="stat" class:accent={!taste.coldStart}>{taste.titles}</p>
            <p class="muted">need {taste.config?.minTitles} to personalize</p>
          </article>
          <article class="card">
            <h3>Sweet-spot year</h3>
            <p class="stat">{taste.meanYear || '—'}</p>
            <p class="muted">weighted mean of what you watch</p>
          </article>
          <article class="card">
            <h3>Personalization</h3>
            <p class="stat" class:accent={taste.config?.enabled} class:bad={!taste.config?.enabled}>{taste.config?.enabled ? 'on' : 'off'}</p>
            <p class="muted">{Math.round((taste.config?.personalWeight ?? 0) * 100)}% taste · {Math.round((1 - (taste.config?.personalWeight ?? 0)) * 100)}% crowd</p>
          </article>
          <article class="card">
            <h3>Last built</h3>
            <p class="stat">{taste.updatedAt ? fmtAgo(taste.updatedAt) : '—'}</p>
            <p class="muted">rebuilds automatically as the library changes</p>
          </article>
        </div>

        <h3>Top genres</h3>
        {#if taste.topGenres?.length}
          <div class="bars">
            {#each taste.topGenres as tag}
              <div class="bar-row">
                <span class="bar-label">{tag.name}</span>
                <div class="bar wide"><div class="bar-fill accent" style={`width: ${Math.round(tag.share * 100)}%`}></div></div>
                <span class="bar-val">{Math.round(tag.share * 100)}%</span>
              </div>
            {/each}
          </div>
        {:else}
          <p class="muted">No genres learned yet.</p>
        {/if}

        {#if taste.topCountries?.length}
          <h3>Top countries</h3>
          <div class="chips">
            {#each taste.topCountries as tag}
              <span class="chip on">{tag.name} · {Math.round(tag.share * 100)}%</span>
            {/each}
          </div>
        {/if}

        <h3>Tuning</h3>
        <div class="tuning">
          <label class="check">
            <input type="checkbox" checked={taste.config?.enabled} onchange={(e) => saveTaste({ enabled: e.target.checked })} disabled={tasteBusy} />
            Personalize Match (off = everyone sees the public rating)
          </label>
          <label class="slider">
            <span>Blend: <strong>{Math.round((taste.config?.personalWeight ?? 0) * 100)}%</strong> your taste vs {Math.round((1 - (taste.config?.personalWeight ?? 0)) * 100)}% crowd</span>
            <input
              type="range" min="0" max="100" step="5"
              value={Math.round((taste.config?.personalWeight ?? 0) * 100)}
              oninput={(e) => taste = { ...taste, config: { ...taste.config, personalWeight: Number(e.target.value) / 100 } }}
              onchange={(e) => saveTaste({ personalWeight: Number(e.target.value) / 100 })}
              disabled={tasteBusy || !taste.config?.enabled}
            />
          </label>
          <label class="numfield">
            <span>Titles before personalizing</span>
            <input
              type="number" min="1" value={taste.config?.minTitles}
              onchange={(e) => saveTaste({ minTitles: Number(e.target.value) })}
              disabled={tasteBusy}
            />
          </label>
        </div>
        {#if taste.fingerprint}<p class="muted mono">fingerprint {taste.fingerprint}</p>{/if}
      {:else if !tasteError}
        <p class="muted">Loading taste profile…</p>
      {/if}
    {/if}

    {#if page === 'subtitles'}
      <header>
        <div>
          <h2>Subtitles</h2>
          <p class="muted">OpenSubtitles.com credentials and preferred languages. Downloads require a free account login as well as an API key.</p>
        </div>
        <button class="ghost" onclick={refreshSubtitles}>Refresh</button>
      </header>
      {#if subError}<p class="error">{subError}</p>{/if}
      {#if subSettings}
        <div class="settings-grid">
          <label class="check">
            <input type="checkbox" bind:checked={subSettings.enabled} /> Enable OpenSubtitles search
          </label>
          <label class="check">
            <input type="checkbox" bind:checked={subSettings.autoLoad} /> Auto-load preferred language on play
          </label>
          <label class="check">
            <input type="checkbox" bind:checked={subSettings.preferEmbedded} /> Prefer embedded / sidecar over online
          </label>
          <label>Languages (comma-separated ISO codes)
            <input bind:value={subLangs} placeholder="en, et" />
          </label>
          <label>User-Agent (must match your OpenSubtitles consumer app name)
            <input bind:value={subSettings.userAgent} placeholder="Coog v0.1.1" />
          </label>
          <label>Username
            <input bind:value={subSettings.username} placeholder="opensubtitles.com username" autocomplete="username" />
          </label>
          <label>Password
            <input bind:value={subPassword} type="password" placeholder={subSettings.hasPassword ? '•••• saved — paste to replace' : 'opensubtitles.com password'} autocomplete="current-password" />
          </label>
          <label>API key
            <input bind:value={subApiKey} type="password" placeholder={subSettings.hasApiKey ? `${subSettings.apiKeyMasked} — paste to replace` : 'from opensubtitles.com API consumers'} />
          </label>
        </div>
        <p class="muted">Register an API consumer at opensubtitles.com, then set the User-Agent to the exact Application Name (e.g. Coog v0.1.1).</p>
      {:else if !subError}
        <p class="muted">Loading…</p>
      {/if}
    {/if}

    {#if page === 'streaming'}
      <header>
        <div>
          <h2>Streaming</h2>
          <p class="muted">Real-Debrid, keep-on-disk, binge knobs, and Torrentio filters. The worker keeps downloading after the TV leaves.</p>
        </div>
      </header>
      {#if streaming}
        <div class="settings-grid">
          <label class="check"><input type="checkbox" bind:checked={streaming.saveToLibrary} /> Save finished streams to the local library</label>
          <label class="check"><input type="checkbox" bind:checked={streaming.autoplayNextEpisode} /> Auto-play next episode</label>
          <label class="check"><input type="checkbox" bind:checked={streaming.autoDownloadNextEpisode} /> Auto-download next episode</label>
          <label class="check"><input type="checkbox" bind:checked={streaming.includeWebStreams} /> Include web streams when searching</label>
          <label>Prefetch before end (minutes)
            <input type="number" bind:value={streaming.prefetchBeforeEndMinutes} min="0" />
          </label>
          <label>Prefetch count
            <input type="number" bind:value={streaming.prefetchCount} min="1" />
          </label>
          <label>Continue overlay (seconds)
            <input type="number" bind:value={streaming.continueOverlaySeconds} min="3" />
          </label>
        </div>
        <h3>Torrentio providers</h3>
        <div class="chips">
          {#each PROVIDERS as name}
            <button class="chip" class:on={(streaming.torrentioProviders || []).includes(name)} onclick={() => toggleChip('torrentioProviders', name)}>{name}</button>
          {/each}
        </div>
        <h3>Exclude qualities</h3>
        <div class="chips">
          {#each QUALITIES as name}
            <button class="chip" class:on={(streaming.excludeQualities || []).includes(name)} onclick={() => toggleChip('excludeQualities', name)}>{name}</button>
          {/each}
        </div>
        <p class="muted">
          Real-Debrid
          {#if streaming.realDebridConfigured}
            connected · {streaming.realDebridTokenMasked}
          {:else}
            not configured — set <code>REALDEBRID_API_TOKEN</code> or paste a token.
          {/if}
        </p>
        <div class="enqueue">
          <input bind:value={rdToken} placeholder="Real-Debrid API token" type="password" />
        </div>
        {#if streamError}<p class="error">{streamError}</p>{/if}
      {:else if streamError}
        <p class="error">{streamError}</p>
      {/if}
    {/if}
  </main>
</div>

{#if (page === 'streaming' && streamDirty) || (page === 'subtitles' && subDirty)}
  <div class="save-bar">
    <span>Unsaved changes</span>
    {#if page === 'streaming'}
      <button onclick={saveStreaming} disabled={streamBusy}>{streamBusy ? 'Saving…' : 'Save streaming'}</button>
    {:else}
      <button onclick={saveSubtitles} disabled={subBusy}>{subBusy ? 'Saving…' : 'Save subtitles'}</button>
    {/if}
  </div>
{/if}

<div class="toasts" aria-live="polite">
  {#each toasts as t (t.id)}
    <div class="toast" class:error={t.kind === 'error'}>{t.message}</div>
  {/each}
</div>
