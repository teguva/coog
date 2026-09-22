<script>
  const NAV_GROUPS = [
    {
      id: 'home',
      label: 'Home',
      items: [
        { id: 'overview', label: 'Overview' },
        { id: 'downloads', label: 'Downloads' },
        { id: 'library', label: 'Library' },
        { id: 'activity', label: 'Activity' },
      ],
    },
    {
      id: 'playback',
      label: 'Playback',
      items: [
        { id: 'streaming', label: 'Streaming' },
        { id: 'subtitles', label: 'Subtitles' },
        { id: 'taste', label: 'Match' },
        { id: 'cache', label: 'Cache' },
      ],
    },
    {
      id: 'adult',
      label: 'Adult',
      items: [
        { id: 'maize', label: 'Maize' },
        { id: 'interactive', label: 'Interactive' },
      ],
    },
  ];
  const NAV = NAV_GROUPS.flatMap((g) => g.items);
  const PROVIDERS = [
    'yts', 'eztv', 'rarbg', '1337x', 'thepiratebay', 'kickasstorrents',
    'torrentgalaxy', 'magnetdl', 'rutor', 'rutracker', 'nyaasi', 'limetorrents',
  ];
  const QUALITIES = ['threed', 'cam', 'scr', '480p', '720p', '1080p', '4k', 'hdr', 'dolbyvision'];
  const RULE_QUALS = ['720p', '1080p', '2160p'];
  const RULE_LANGS = ['en', 'es', 'latino', 'fr', 'de', 'it', 'pt', 'ja', 'ko', 'zh', 'hi', 'ru', 'et', 'nordic'];
  const RULE_CARDS = [
    { key: 'movies', title: 'Movies', hint: 'Catalog play and Smart Play for films.' },
    { key: 'series', title: 'Series', hint: 'Episodes, packs, and next-episode autodownload.' },
  ];
  const RANK_OPTIONS = [
    { id: 'quality', label: 'Best quality' },
    { id: 'size', label: 'Largest file' },
    { id: 'seeders', label: 'Most seeders' },
  ];

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
  let maizeItems = $state([]);
  let maizeLibError = $state('');
  let maizeUploadFiles = $state([]);
  let maizeUploadBusy = $state('');
  let maizeUploadError = $state('');
  let maizeFilter = $state('all');
  let maizeQuery = $state('');
  let maizeSelectedId = $state('');
  let maizeForm = $state({
    title: '',
    description: '',
    studio: '',
    year: '',
    releasePrecision: 'year',
    releaseMonth: '',
    releaseDay: '',
    rating: '',
    performers: [],
    tags: [],
  });
  let maizeMetaBusy = $state(false);
  let maizeMetaError = $state('');
  let maizeFormBaseline = $state('');
  let maizeSelectedPath = $state('');
  let maizeSelected = $state(null);
  let maizeArtBusy = $state('');
  let maizeVideoEl = $state(null);
  let maizeVideoTime = $state(0);
  let maizeSuggestSource = $state([]);
  let maizeStudioDraft = $state('');
  let maizePerformerDraft = $state('');
  let maizeTagDraft = $state('');
  let maizeSuggestField = $state('');
  let maizeSuggestIndex = $state(-1);
  let maizeTab = $state('scenes');
  let maizeActors = $state([]);
  let maizeActorsError = $state('');
  let maizeActorQuery = $state('');
  let maizeActorSlug = $state('');
  let maizeActor = $state(null);
  let maizeActorForm = $state(emptyMaizeActorForm());
  let maizeActorBaseline = $state('');
  let maizeActorBusy = $state(false);
  let maizeActorError = $state('');
  let maizeActorEnrichBusy = $state(false);
  let maizeIaFdUrl = $state('');
  let maizeEnrichBusy = $state(false);
  let maizeVideoChoice = $state('');
  let maizeScriptChoice = $state('');
  let maizeAliasDraft = $state('');
  let interactiveEngine = $state(null);
  let interactiveError = $state('');
  let interactiveBusy = $state(false);
  let toasts = $state([]);
  let toastSeq = 0;
  let navOpen = $state(false);

  const headers = () => {
    const h = { Accept: 'application/json' };
    if (token) h.Authorization = `Bearer ${token}`;
    return h;
  };

  function go(id) {
    page = id;
    localStorage.setItem('coog-admin-page', id);
    navOpen = false;
  }

  const pageMeta = $derived(NAV.find((item) => item.id === page) || { id: page, label: 'Admin' });
  const pageGroup = $derived(NAV_GROUPS.find((g) => g.items.some((item) => item.id === page))?.label || '');

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

  function defaultDownloadRule(series) {
    return {
      rank: 'quality',
      preferredQualities: ['1080p', '2160p'],
      preferredLanguages: ['en'],
      requireLanguage: false,
      minSizeMb: 0,
      maxSizeMb: 0,
      requireCached: false,
      allowWeb: true,
      preferRemux: true,
      preferHdr: true,
      preferAtmos: true,
      preferSingleEpisode: !!series,
      allowSeasonPacks: !!series,
    };
  }

  function withDownloadRules(cfg) {
    if (!cfg) return cfg;
    return {
      ...cfg,
      movies: { ...defaultDownloadRule(false), ...(cfg.movies || {}) },
      series: { ...defaultDownloadRule(true), ...(cfg.series || {}) },
    };
  }

  function snapshotRule(r) {
    if (!r) return null;
    return {
      rank: r.rank || 'quality',
      preferredQualities: [...(r.preferredQualities || [])].sort(),
      preferredLanguages: [...(r.preferredLanguages || [])].sort(),
      requireLanguage: !!r.requireLanguage,
      minSizeMb: Number(r.minSizeMb) || 0,
      maxSizeMb: Number(r.maxSizeMb) || 0,
      requireCached: !!r.requireCached,
      allowWeb: !!r.allowWeb,
      preferRemux: !!r.preferRemux,
      preferHdr: !!r.preferHdr,
      preferAtmos: !!r.preferAtmos,
      preferSingleEpisode: !!r.preferSingleEpisode,
      allowSeasonPacks: !!r.allowSeasonPacks,
    };
  }

  function snapshotStreaming(cfg) {
    if (!cfg) return null;
    return JSON.stringify({
      saveToLibrary: !!cfg.saveToLibrary,
      autoplayNextEpisode: !!cfg.autoplayNextEpisode,
      autoDownloadNextEpisode: !!cfg.autoDownloadNextEpisode,
      includeWebStreams: !!cfg.includeWebStreams,
      autoSelectSource: !!cfg.autoSelectSource,
      prefetchBeforeEndMinutes: cfg.prefetchBeforeEndMinutes,
      prefetchCount: cfg.prefetchCount,
      continueOverlaySeconds: cfg.continueOverlaySeconds,
      torrentioProviders: [...(cfg.torrentioProviders || [])].sort(),
      excludeQualities: [...(cfg.excludeQualities || [])].sort(),
      movies: snapshotRule(cfg.movies),
      series: snapshotRule(cfg.series),
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
      streaming = withDownloadRules(await res.json());
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

  async function refreshMaizeLibrary() {
    maizeLibError = '';
    try {
      const q = new URLSearchParams();
      if (maizeFilter && maizeFilter !== 'all') q.set('filter', maizeFilter);
      const res = await fetch(`/api/v1/maize/library?${q}`, { headers: headers() });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      const data = await res.json();
      maizeItems = data.items || [];
    } catch (err) {
      maizeLibError = String(err);
    }
  }

  async function refreshMaizeSuggestSource() {
    try {
      const res = await fetch('/api/v1/maize/library', { headers: headers() });
      if (!res.ok) return;
      const data = await res.json();
      maizeSuggestSource = data.items || [];
    } catch {
      /* keep last */
    }
  }

  function emptyMaizeActorForm() {
    return {
      name: '',
      bio: '',
      birthday: '',
      birthplace: '',
      ethnicity: '',
      height: '',
      measurements: '',
      yearsActive: '',
      aliases: [],
      links: { iafd: '', babehub: '', pornpics: '', pornhub: '' },
      locked: true,
    };
  }

  function maizeActorFormFromItem(item) {
    const links = item?.links || {};
    return {
      name: item?.name || '',
      bio: item?.bio || '',
      birthday: item?.birthday || '',
      birthplace: item?.birthplace || '',
      ethnicity: item?.ethnicity || '',
      height: item?.height || '',
      measurements: item?.measurements || '',
      yearsActive: item?.yearsActive || '',
      aliases: Array.isArray(item?.aliases) ? item.aliases.map((a) => String(a).trim()).filter(Boolean) : [],
      links: {
        iafd: links.iafd || '',
        babehub: links.babehub || '',
        pornpics: links.pornpics || '',
        pornhub: links.pornhub || '',
      },
      locked: item?.locked !== false,
    };
  }

  async function refreshMaizeActors() {
    maizeActorsError = '';
    try {
      const res = await fetch('/api/v1/maize/actors', { headers: headers() });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      const data = await res.json();
      maizeActors = data.actors || [];
    } catch (err) {
      maizeActorsError = String(err);
    }
  }

  async function selectMaizeActor(slug) {
    if (!slug) return;
    if (
      maizeActorSlug &&
      maizeActorSlug !== slug &&
      maizeActorBaseline &&
      JSON.stringify(maizeActorForm) !== maizeActorBaseline
    ) {
      if (!confirm('Discard unsaved actor changes?')) return;
    }
    maizeActorSlug = slug;
    maizeActorError = '';
    maizeAliasDraft = '';
    try {
      const res = await fetch(`/api/v1/maize/actors/${encodeURIComponent(slug)}`, { headers: headers() });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      const item = await res.json();
      maizeActor = item;
      maizeActorForm = maizeActorFormFromItem(item);
      maizeActorBaseline = JSON.stringify(maizeActorForm);
    } catch (err) {
      maizeActorError = String(err);
      toast(String(err), 'error');
    }
  }

  function clearMaizeActorSelection() {
    if (
      maizeActorSlug &&
      maizeActorBaseline &&
      JSON.stringify(maizeActorForm) !== maizeActorBaseline
    ) {
      if (!confirm('Discard unsaved actor changes?')) return;
    }
    maizeActorSlug = '';
    maizeActor = null;
    maizeActorForm = emptyMaizeActorForm();
    maizeActorBaseline = '';
    maizeActorError = '';
    maizeAliasDraft = '';
  }

  function addMaizeActorAlias(value) {
    const next = String(value || '').trim();
    if (!next) return;
    if (maizeActorForm.aliases.some((a) => a.toLowerCase() === next.toLowerCase())) return;
    maizeActorForm = { ...maizeActorForm, aliases: [...maizeActorForm.aliases, next] };
    maizeAliasDraft = '';
  }

  function removeMaizeActorAlias(index) {
    maizeActorForm = {
      ...maizeActorForm,
      aliases: maizeActorForm.aliases.filter((_, i) => i !== index),
    };
  }

  async function saveMaizeActor() {
    if (!maizeActorSlug || maizeActorBusy) return;
    if (maizeAliasDraft.trim()) addMaizeActorAlias(maizeAliasDraft);
    maizeActorBusy = true;
    maizeActorError = '';
    try {
      const res = await fetch(`/api/v1/maize/actors/${encodeURIComponent(maizeActorSlug)}`, {
        method: 'PUT',
        headers: { ...headers(), 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: maizeActorForm.name,
          bio: maizeActorForm.bio,
          birthday: maizeActorForm.birthday,
          birthplace: maizeActorForm.birthplace,
          ethnicity: maizeActorForm.ethnicity,
          height: maizeActorForm.height,
          measurements: maizeActorForm.measurements,
          yearsActive: maizeActorForm.yearsActive,
          aliases: maizeActorForm.aliases,
          links: maizeActorForm.links,
          locked: !!maizeActorForm.locked,
        }),
      });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      const updated = await res.json();
      maizeActor = updated;
      maizeActorForm = maizeActorFormFromItem(updated);
      maizeActorBaseline = JSON.stringify(maizeActorForm);
      if (updated.slug && updated.slug !== maizeActorSlug) {
        maizeActorSlug = updated.slug;
      }
      await refreshMaizeActors();
      toast('Actor metadata saved');
    } catch (err) {
      maizeActorError = String(err);
      toast(String(err), 'error');
    } finally {
      maizeActorBusy = false;
    }
  }

  function cancelMaizeActor() {
    if (!maizeActorSlug || !maizeActorBaseline) return;
    try {
      maizeActorForm = JSON.parse(maizeActorBaseline);
      maizeAliasDraft = '';
      maizeActorError = '';
    } catch {
      selectMaizeActor(maizeActorSlug);
    }
  }

  async function uploadMaizeActorHeadshot(file) {
    if (!maizeActorSlug || !file || maizeActorBusy) return;
    maizeActorBusy = true;
    maizeActorError = '';
    try {
      const body = new FormData();
      body.append('file', file);
      if (maizeActorForm.name) body.append('name', maizeActorForm.name);
      const res = await fetch(`/api/v1/maize/actors/${encodeURIComponent(maizeActorSlug)}/headshot`, {
        method: 'POST',
        headers: headers(),
        body,
      });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      const updated = await res.json();
      maizeActor = updated;
      await refreshMaizeActors();
      toast('Headshot updated');
    } catch (err) {
      maizeActorError = String(err);
      toast(String(err), 'error');
    } finally {
      maizeActorBusy = false;
    }
  }

  function maizeActorMediaUrl(base) {
    if (!base) return '';
    const u = new URL(base, location.origin);
    if (token) u.searchParams.set('token', token);
    if (maizeActor?.hasHeadshot) u.searchParams.set('v', String(Date.now()));
    return u.pathname + u.search;
  }

  function collectMaizeValues(pick) {
    const seen = new Map();
    for (const item of maizeSuggestSource) {
      for (const raw of pick(item)) {
        const v = String(raw || '').trim();
        if (!v) continue;
        const key = v.toLowerCase();
        if (!seen.has(key)) seen.set(key, v);
      }
    }
    return [...seen.values()].sort((a, b) => a.localeCompare(b));
  }

  function filterMaizeSuggestions(all, draft, selected) {
    const q = String(draft || '').trim().toLowerCase();
    if (!q) return [];
    const taken = new Set((selected || []).map((s) => String(s).trim().toLowerCase()).filter(Boolean));
    return all
      .filter((v) => {
        const key = v.toLowerCase();
        if (taken.has(key)) return false;
        return key.includes(q);
      })
      .slice(0, 8);
  }

  function maizeSuggestionsFor(field) {
    if (field === 'studio') return maizeStudioSuggestions;
    if (field === 'performers') return maizePerformerSuggestions;
    if (field === 'tags') return maizeTagSuggestions;
    return [];
  }

  function openMaizeSuggest(field) {
    maizeSuggestField = field;
    maizeSuggestIndex = -1;
  }

  function closeMaizeSuggest(field) {
    // Allow click on dropdown option before blur closes it.
    setTimeout(() => {
      if (maizeSuggestField === field) {
        maizeSuggestField = '';
        maizeSuggestIndex = -1;
      }
    }, 120);
  }

  function pickMaizeSuggestion(field, value) {
    if (field === 'studio') setMaizeStudio(value);
    else addMaizeListValue(field, value);
    maizeSuggestField = '';
    maizeSuggestIndex = -1;
  }

  function maizeFormFromItem(item) {
    const performers = Array.isArray(item?.performers)
      ? item.performers.map((p) => String(p).trim()).filter(Boolean)
      : [];
    const tags = Array.isArray(item?.tags)
      ? item.tags.map((t) => String(t).trim()).filter(Boolean)
      : Array.isArray(item?.genres)
        ? item.genres.map((t) => String(t).trim()).filter(Boolean)
        : [];
    const release = parseMaizeRelease(item);
    return {
      title: item?.title || '',
      description: item?.description || item?.plot || '',
      studio: item?.studio || '',
      year: release.year,
      releasePrecision: release.precision,
      releaseMonth: release.month,
      releaseDay: release.day,
      rating: item?.rating != null && item.rating !== '' ? String(item.rating) : '',
      performers,
      tags,
    };
  }

  function parseMaizeRelease(item) {
    const raw = String(item?.releaseDate || (item?.year ? item.year : '') || '').trim();
    const precision = item?.releasePrecision || '';
    if (!raw) {
      return { precision: 'year', year: '', month: '', day: '' };
    }
    const parts = raw.replaceAll('/', '-').split('-').map((p) => p.trim());
    const year = parts[0] || '';
    const month = parts[1] ? parts[1].padStart(2, '0') : '';
    const day = parts[2] ? parts[2].padStart(2, '0') : '';
    let prec = precision;
    if (!prec) {
      if (parts.length >= 3 && day) prec = 'day';
      else if (parts.length >= 2 && month) prec = 'month';
      else prec = 'year';
    }
    return { precision: prec, year, month, day };
  }

  function buildMaizeReleaseDate(form) {
    const y = parseInt(String(form.year || '').trim(), 10);
    if (!Number.isFinite(y) || y <= 0) return { releaseDate: '', year: 0 };
    const prec = form.releasePrecision || 'year';
    if (prec === 'year') {
      return { releaseDate: String(y), year: y };
    }
    const m = parseInt(String(form.releaseMonth || '').trim(), 10);
    if (!Number.isFinite(m) || m < 1 || m > 12) {
      return { error: 'Pick a valid month' };
    }
    if (prec === 'month') {
      return { releaseDate: `${y}-${String(m).padStart(2, '0')}`, year: y };
    }
    const d = parseInt(String(form.releaseDay || '').trim(), 10);
    if (!Number.isFinite(d) || d < 1 || d > 31) {
      return { error: 'Pick a valid day' };
    }
    return {
      releaseDate: `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`,
      year: y,
    };
  }

  function formatMaizeReleaseLabel(item) {
    const rd = String(item?.releaseDate || '').trim();
    if (rd) return rd;
    if (item?.year) return String(item.year);
    return '';
  }

  function commitMaizeForm(item) {
    maizeForm = maizeFormFromItem(item);
    maizeFormBaseline = JSON.stringify(maizeForm);
    maizeSelectedPath = item?.path || item?.relativePath || '';
    maizeSelected = item || null;
    maizeIaFdUrl = item?.links?.iafd || '';
    const preferredVideo = (item?.videos || []).find((v) => v.preferred)?.id
      || (item?.videos || [])[0]?.id
      || item?.id
      || '';
    maizeVideoChoice = preferredVideo;
    const preferredScript = (item?.funscripts || []).find((s) => s.preferred)?.name
      || item?.funscriptName
      || (item?.funscripts || [])[0]?.name
      || '';
    maizeScriptChoice = preferredScript;
    maizeVideoTime = 0;
    maizeStudioDraft = '';
    maizePerformerDraft = '';
    maizeTagDraft = '';
  }

  function setMaizeStudio(value) {
    maizeForm = { ...maizeForm, studio: String(value || '').trim() };
    maizeStudioDraft = '';
  }

  function clearMaizeStudio() {
    maizeForm = { ...maizeForm, studio: '' };
  }

  function addMaizeListValue(field, value) {
    const next = String(value || '').trim();
    if (!next) return;
    const cur = Array.isArray(maizeForm[field]) ? maizeForm[field] : [];
    if (cur.some((v) => v.toLowerCase() === next.toLowerCase())) return;
    maizeForm = { ...maizeForm, [field]: [...cur, next] };
    if (field === 'performers') maizePerformerDraft = '';
    if (field === 'tags') maizeTagDraft = '';
  }

  function removeMaizeListValue(field, index) {
    const cur = Array.isArray(maizeForm[field]) ? maizeForm[field] : [];
    maizeForm = { ...maizeForm, [field]: cur.filter((_, i) => i !== index) };
  }

  function commitMaizeChipDraft(field, draft) {
    const parts = String(draft || '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);
    if (!parts.length) return;
    if (field === 'studio') {
      setMaizeStudio(parts[parts.length - 1]);
      return;
    }
    for (const p of parts) addMaizeListValue(field, p);
  }

  function onMaizeChipKeydown(e, field) {
    const draft =
      field === 'studio' ? maizeStudioDraft : field === 'performers' ? maizePerformerDraft : maizeTagDraft;
    const suggestions = maizeSuggestionsFor(field);
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (!suggestions.length) return;
      maizeSuggestField = field;
      maizeSuggestIndex = Math.min(suggestions.length - 1, maizeSuggestIndex + 1);
      return;
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (!suggestions.length) return;
      maizeSuggestField = field;
      maizeSuggestIndex = Math.max(-1, maizeSuggestIndex - 1);
      return;
    }
    if (e.key === 'Escape') {
      if (maizeSuggestField === field) {
        e.preventDefault();
        maizeSuggestField = '';
        maizeSuggestIndex = -1;
      }
      return;
    }
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault();
      if (
        e.key === 'Enter' &&
        maizeSuggestField === field &&
        maizeSuggestIndex >= 0 &&
        suggestions[maizeSuggestIndex]
      ) {
        pickMaizeSuggestion(field, suggestions[maizeSuggestIndex]);
        return;
      }
      commitMaizeChipDraft(field, draft);
      maizeSuggestField = '';
      maizeSuggestIndex = -1;
      return;
    }
    if (e.key === 'Backspace' && !String(draft || '').length) {
      if (field === 'studio' && maizeForm.studio) {
        e.preventDefault();
        clearMaizeStudio();
      } else if (field === 'performers' && maizeForm.performers.length) {
        e.preventDefault();
        removeMaizeListValue('performers', maizeForm.performers.length - 1);
      } else if (field === 'tags' && maizeForm.tags.length) {
        e.preventDefault();
        removeMaizeListValue('tags', maizeForm.tags.length - 1);
      }
    }
  }

  function onMaizeChipInput(field) {
    maizeSuggestField = field;
    maizeSuggestIndex = -1;
  }

  function maizeMediaUrl(base) {
    if (!base) return '';
    const u = new URL(base, location.origin);
    if (token) u.searchParams.set('token', token);
    if (maizeSelected?.artRev) u.searchParams.set('v', String(maizeSelected.artRev));
    return u.pathname + u.search;
  }

  function applyMaizeArtUpdate(updated) {
    commitMaizeForm(updated);
    maizeItems = maizeItems.map((it) => (it.id === updated.id ? { ...it, ...updated } : it));
  }

  async function captureMaizeArt(kind) {
    if (!maizeSelectedId || maizeArtBusy) return;
    const positionMs = Math.max(0, Math.round((maizeVideoEl?.currentTime || maizeVideoTime || 0) * 1000));
    maizeArtBusy = kind;
    maizeMetaError = '';
    try {
      const mediaId = maizePlayId || maizeSelectedId;
      const res = await fetch(`/api/v1/maize/media/${encodeURIComponent(mediaId)}/art/frame`, {
        method: 'POST',
        headers: { ...headers(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ kind, positionMs }),
      });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      applyMaizeArtUpdate(await res.json());
      toast(`${kind} set from frame`);
    } catch (err) {
      maizeMetaError = String(err);
      toast(String(err), 'error');
    } finally {
      maizeArtBusy = '';
    }
  }

  async function uploadMaizeArt(kind, file) {
    if (!maizeSelectedId || !file || maizeArtBusy) return;
    maizeArtBusy = kind;
    maizeMetaError = '';
    try {
      const body = new FormData();
      body.append('file', file);
      const res = await fetch(
        `/api/v1/maize/media/${encodeURIComponent(maizeSelectedId)}/art/upload?kind=${encodeURIComponent(kind)}`,
        { method: 'POST', headers: headers(), body },
      );
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      applyMaizeArtUpdate(await res.json());
      toast(`${kind} uploaded`);
    } catch (err) {
      maizeMetaError = String(err);
      toast(String(err), 'error');
    } finally {
      maizeArtBusy = '';
    }
  }

  function isMaizeVideoFile(name) {
    return /\.(mp4|m4v|mkv|mov|webm|avi|wmv|ts|m2ts)$/i.test(name || '');
  }

  function isMaizeScriptFile(name) {
    return /\.funscript$/i.test(name || '');
  }

  function maizeUploadRelPath(file) {
    return String(file?.webkitRelativePath || file?.relativePath || '').replace(/\\/g, '/').replace(/^\/+/, '');
  }

  function maizeUploadDisplayName(file) {
    const rel = maizeUploadRelPath(file);
    return rel || file?.name || '';
  }

  function maizeUploadIdentity(file) {
    return `${maizeUploadDisplayName(file)}::${file?.size ?? 0}`;
  }

  /** Top-level folder when picking/dropping a directory; empty for loose files. */
  function maizeUploadFolder(file) {
    const rel = maizeUploadRelPath(file);
    if (!rel.includes('/')) return '';
    return rel.split('/').filter(Boolean)[0] || '';
  }

  function maizeUploadKey(name) {
    let stem = String(name || '').replace(/\.[^.]+$/, '');
    stem = stem.replace(/\[\s*(?:[^\]]*?-)?(2160p|1440p|1080p|720p|480p|360p|4K|2K|UHD|FHD|HD|SD)\s*\]/gi, '');
    stem = stem.replace(/[.\-_\s](2160p|1080p|720p|480p|360p|4k|uhd|hdr10|hdr|hevc|x265|x264|h265|h264|av1|bluray|webrip|web-dl|webdl|hdtv|remux)/gi, '');
    return stem.replace(/[\s._-]+$/g, '').toLowerCase() || String(name || '').toLowerCase();
  }

  function prettyMaizeTitle(name) {
    let stem = String(name || '').replace(/\.[^.]+$/, '');
    stem = stem.replace(/\[\s*(?:[^\]]*?-)?(2160p|1440p|1080p|720p|480p|360p|4K|2K|UHD|FHD|HD|SD)\s*\]/gi, '');
    stem = stem.replace(/[.\-_\s](2160p|1080p|720p|480p|360p|4k|uhd|hdr10|hdr|hevc|x265|x264|h265|h264|av1|bluray|webrip|web-dl|webdl|hdtv|remux)/gi, '');
    stem = stem.replace(/[._]+/g, ' ').replace(/\s+/g, ' ').trim();
    return stem || name;
  }

  function readDirectoryEntries(reader) {
    return new Promise((resolve, reject) => {
      const all = [];
      const pump = () => {
        reader.readEntries((batch) => {
          if (!batch.length) {
            resolve(all);
            return;
          }
          all.push(...batch);
          pump();
        }, reject);
      };
      pump();
    });
  }

  async function filesFromFileEntry(entry, pathPrefix = '') {
    if (!entry) return [];
    if (entry.isFile) {
      const file = await new Promise((resolve, reject) => entry.file(resolve, reject));
      const rel = pathPrefix ? `${pathPrefix}/${file.name}` : file.name;
      try {
        Object.defineProperty(file, 'webkitRelativePath', { configurable: true, value: rel });
      } catch {
        file.relativePath = rel;
      }
      return [file];
    }
    if (entry.isDirectory) {
      const children = await readDirectoryEntries(entry.createReader());
      const nextPrefix = pathPrefix ? `${pathPrefix}/${entry.name}` : entry.name;
      const nested = await Promise.all(children.map((child) => filesFromFileEntry(child, nextPrefix)));
      return nested.flat();
    }
    return [];
  }

  async function filesFromDataTransfer(dt) {
    if (!dt) return [];
    const items = [...(dt.items || [])];
    if (items.some((item) => typeof item.webkitGetAsEntry === 'function')) {
      const batches = await Promise.all(
        items.map(async (item) => {
          const entry = item.webkitGetAsEntry?.();
          if (entry) return filesFromFileEntry(entry);
          const file = item.getAsFile?.();
          return file ? [file] : [];
        }),
      );
      const collected = batches.flat();
      if (collected.length) return collected;
    }
    return [...(dt.files || [])];
  }

  function groupMaizeUploadFiles(fileList) {
    const byFolder = new Map();
    for (const file of fileList) {
      const folder = maizeUploadFolder(file);
      const bucket = folder || '';
      if (!byFolder.has(bucket)) byFolder.set(bucket, []);
      byFolder.get(bucket).push(file);
    }

    const titles = [];
    const leftover = [];

    for (const [folder, files] of byFolder) {
      const groups = new Map();
      for (const file of files) {
        const key = maizeUploadKey(file.name);
        if (!groups.has(key)) {
          groups.set(key, {
            key: folder ? `folder:${folder.toLowerCase()}:${key}` : key,
            title: prettyMaizeTitle(file.name),
            folder,
            videos: [],
            scripts: [],
          });
        }
        const g = groups.get(key);
        if (isMaizeScriptFile(file.name)) g.scripts.push(file);
        else if (isMaizeVideoFile(file.name)) g.videos.push(file);
      }

      const videoGroups = [];
      const scriptsOnly = [];
      for (const g of groups.values()) {
        if (g.videos.length) videoGroups.push(g);
        else scriptsOnly.push(...g.scripts);
      }

      if (folder && videoGroups.length === 1) {
        // One movie in a folder: folder name is the title; attach every funscript in it.
        videoGroups[0].title = prettyMaizeTitle(folder);
        videoGroups[0].key = `folder:${folder.toLowerCase()}`;
        videoGroups[0].scripts.push(...scriptsOnly);
        titles.push(videoGroups[0]);
        continue;
      }

      if (folder && videoGroups.length > 1) {
        for (const g of videoGroups) {
          g.title = `${prettyMaizeTitle(folder)} — ${g.title}`;
        }
      }
      titles.push(...videoGroups);
      leftover.push(...scriptsOnly);
    }

    if (leftover.length && titles.length === 1) {
      titles[0].scripts.push(...leftover);
    }
    return titles;
  }

  function onMaizeUploadPick(event) {
    const next = [...(event.currentTarget.files || [])];
    event.currentTarget.value = '';
    addMaizeUploadFiles(next);
  }

  async function onMaizeUploadDrop(event) {
    event.preventDefault();
    if (maizeUploadBusy) return;
    try {
      const next = await filesFromDataTransfer(event.dataTransfer);
      addMaizeUploadFiles(next);
    } catch (err) {
      maizeUploadError = String(err);
      toast(String(err), 'error');
    }
  }

  function addMaizeUploadFiles(next) {
    const keep = [...maizeUploadFiles];
    for (const file of next) {
      if (!isMaizeVideoFile(file.name) && !isMaizeScriptFile(file.name)) continue;
      const id = maizeUploadIdentity(file);
      if (keep.some((other) => maizeUploadIdentity(other) === id)) continue;
      keep.push(file);
    }
    maizeUploadFiles = keep;
    maizeUploadError = '';
  }

  function removeMaizeUploadGroup(key) {
    maizeUploadFiles = maizeUploadFiles.filter((file) => {
      const folder = maizeUploadFolder(file);
      if (key.startsWith('folder:') && folder) {
        const folderKey = `folder:${folder.toLowerCase()}`;
        if (key === folderKey) return false;
        if (key.startsWith(`${folderKey}:`)) {
          return maizeUploadKey(file.name) !== key.slice(folderKey.length + 1);
        }
      }
      return maizeUploadKey(file.name) !== key;
    });
  }

  function clearMaizeUpload() {
    maizeUploadFiles = [];
    maizeUploadError = '';
    maizeUploadBusy = '';
  }

  async function uploadMaizeTitles() {
    const groups = groupMaizeUploadFiles(maizeUploadFiles);
    if (!groups.length || maizeUploadBusy) return;
    maizeUploadError = '';
    let lastId = '';
    try {
      for (const group of groups) {
        maizeUploadBusy = group.title;
        const body = new FormData();
        for (const file of group.videos) body.append('files', file, file.name);
        for (const file of group.scripts) body.append('files', file, file.name);
        if (group.title) body.append('title', group.title);
        const res = await fetch('/api/v1/maize/upload', { method: 'POST', headers: headers(), body });
        if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
        const data = await res.json();
        lastId = data.items?.[0]?.id || lastId;
      }
      maizeUploadFiles = [];
      await refreshMaizeLibrary();
      await refreshMaizeSuggestSource();
      toast(groups.length === 1 ? 'Uploaded title to Maize' : `Uploaded ${groups.length} titles to Maize`);
      if (lastId) await selectMaizeScene(lastId);
    } catch (err) {
      maizeUploadError = String(err);
      toast(String(err), 'error');
    } finally {
      maizeUploadBusy = '';
    }
  }

  async function selectMaizeScene(id) {
    if (!id) return;
    const dirty =
      !!maizeSelectedId &&
      maizeFormBaseline !== '' &&
      JSON.stringify(maizeForm) !== maizeFormBaseline;
    if (id !== maizeSelectedId && dirty) {
      if (!confirm('Discard unsaved metadata changes?')) return;
    }
    maizeSelectedId = id;
    maizeMetaError = '';
    try {
      const res = await fetch(`/api/v1/maize/media/${encodeURIComponent(id)}`, { headers: headers() });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      const item = await res.json();
      commitMaizeForm(item);
      requestAnimationFrame(() => {
        document.querySelector('.maize-form-pane')?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      });
    } catch (err) {
      maizeMetaError = String(err);
      toast(String(err), 'error');
    }
  }

  function clearMaizeSelection() {
    const dirty =
      !!maizeSelectedId &&
      maizeFormBaseline !== '' &&
      JSON.stringify(maizeForm) !== maizeFormBaseline;
    if (dirty && !confirm('Discard unsaved metadata changes?')) return;
    maizeSelectedId = '';
    maizeSelectedPath = '';
    maizeSelected = null;
    maizeMetaError = '';
    maizeFormBaseline = '';
    maizeForm = maizeFormFromItem(null);
    maizeVideoEl = null;
    maizeVideoTime = 0;
    maizeStudioDraft = '';
    maizePerformerDraft = '';
    maizeTagDraft = '';
    maizeIaFdUrl = '';
    maizeVideoChoice = '';
    maizeScriptChoice = '';
  }

  async function enrichMaizeScene(force = false) {
    if (!maizeSelectedId || maizeEnrichBusy || maizeMetaBusy) return;
    const dirty =
      maizeFormBaseline !== '' && JSON.stringify(maizeForm) !== maizeFormBaseline;
    if (dirty && !confirm('Discard unsaved metadata changes and enrich from IAFD?')) return;
    const iafdUrl = String(maizeIaFdUrl || '').trim();
    if (!iafdUrl) {
      maizeMetaError = 'Paste an IAFD title URL first';
      toast(maizeMetaError, 'error');
      return;
    }
    maizeEnrichBusy = true;
    maizeMetaError = '';
    try {
      const res = await fetch(`/api/v1/maize/media/${encodeURIComponent(maizeSelectedId)}/enrich`, {
        method: 'POST',
        headers: { ...headers(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ iafdUrl, force: !!force }),
      });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      const updated = await res.json();
      commitMaizeForm(updated);
      maizeItems = maizeItems.map((it) => (it.id === updated.id ? { ...it, ...updated } : it));
      maizeSuggestSource = maizeSuggestSource.map((it) => (it.id === updated.id ? { ...it, ...updated } : it));
      toast('Scene enriched from IAFD');
    } catch (err) {
      maizeMetaError = String(err);
      toast(String(err), 'error');
    } finally {
      maizeEnrichBusy = false;
    }
  }

  async function enrichMaizeActor(force = false) {
    if (!maizeActorSlug || maizeActorEnrichBusy || maizeActorBusy) return;
    const dirty =
      maizeActorBaseline && JSON.stringify(maizeActorForm) !== maizeActorBaseline;
    if (dirty && !confirm('Discard unsaved actor changes and enrich from the web?')) return;
    if (maizeActorForm.locked && !force) {
      if (!confirm('Actor is locked. Force enrich anyway?')) return;
      force = true;
    }
    maizeActorEnrichBusy = true;
    maizeActorError = '';
    try {
      const res = await fetch(`/api/v1/maize/actors/${encodeURIComponent(maizeActorSlug)}/enrich`, {
        method: 'POST',
        headers: { ...headers(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ force: !!force }),
      });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      const updated = await res.json();
      maizeActor = updated;
      maizeActorForm = maizeActorFormFromItem(updated);
      maizeActorBaseline = JSON.stringify(maizeActorForm);
      if (updated.slug && updated.slug !== maizeActorSlug) {
        maizeActorSlug = updated.slug;
      }
      await refreshMaizeActors();
      toast('Actor enriched');
    } catch (err) {
      maizeActorError = String(err);
      toast(String(err), 'error');
    } finally {
      maizeActorEnrichBusy = false;
    }
  }

  async function saveMaizeMeta() {
    if (!maizeSelectedId || maizeMetaBusy) return;
    commitMaizeChipDraft('studio', maizeStudioDraft);
    commitMaizeChipDraft('performers', maizePerformerDraft);
    commitMaizeChipDraft('tags', maizeTagDraft);
    const release = buildMaizeReleaseDate(maizeForm);
    if (release.error) {
      maizeMetaError = release.error;
      toast(release.error, 'error');
      return;
    }
    maizeMetaBusy = true;
    maizeMetaError = '';
    try {
      const rating = parseFloat(String(maizeForm.rating).trim());
      const res = await fetch(`/api/v1/maize/media/${encodeURIComponent(maizeSelectedId)}/meta`, {
        method: 'PUT',
        headers: { ...headers(), 'Content-Type': 'application/json' },
        body: JSON.stringify({
          title: maizeForm.title,
          description: maizeForm.description,
          studio: maizeForm.studio,
          year: release.year || 0,
          releaseDate: release.releaseDate || '',
          rating: Number.isFinite(rating) ? rating : 0,
          performers: maizeForm.performers,
          tags: maizeForm.tags,
        }),
      });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      const updated = await res.json();
      commitMaizeForm(updated);
      maizeItems = maizeItems.map((it) => (it.id === updated.id ? { ...it, ...updated } : it));
      maizeSuggestSource = maizeSuggestSource.map((it) => (it.id === updated.id ? { ...it, ...updated } : it));
      if (!maizeSuggestSource.some((it) => it.id === updated.id)) {
        maizeSuggestSource = [...maizeSuggestSource, updated];
      }
      toast('Scene metadata saved');
    } catch (err) {
      maizeMetaError = String(err);
      toast(String(err), 'error');
    } finally {
      maizeMetaBusy = false;
    }
  }

  function cancelMaizeMeta() {
    if (!maizeSelectedId) return;
    if (!maizeDirty) return;
    try {
      maizeForm = JSON.parse(maizeFormBaseline);
      maizeStudioDraft = '';
      maizePerformerDraft = '';
      maizeTagDraft = '';
    } catch {
      selectMaizeScene(maizeSelectedId);
    }
    maizeMetaError = '';
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
      refreshMaizeLibrary(),
      refreshMaizeSuggestSource(),
      refreshMaizeActors(),
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

  function deleteConfirmMessage(item, scope) {
    const title = item.showTitle && item.kind === 'episode'
      ? `${item.showTitle} — ${item.title}`
      : (item.showTitle || item.title || 'this title');
    if (scope === 'series') {
      return `Remove the entire series folder and every episode file for “${item.showTitle || item.title}”? This cannot be undone.`;
    }
    if (scope === 'season') {
      const season = item.season > 0 ? `season ${item.season}` : 'this season';
      return `Delete every on-disk episode in ${season} of “${item.showTitle || item.title}”? Show artwork is kept. This cannot be undone.`;
    }
    if (item.kind === 'episode') {
      return `Delete the episode file “${title}”? Show artwork is kept.`;
    }
    return `Delete “${title}” from disk, including its folder and metadata? This cannot be undone.`;
  }

  function shouldClearSelected(item, scope) {
    if (!selected?.id) return false;
    if (selected.id === item.id) return true;
    if (scope === 'series' && selected.showTitle && selected.showTitle === item.showTitle) return true;
    if (scope === 'season' && selected.showTitle === item.showTitle && selected.season === item.season) return true;
    return false;
  }

  async function removeItem(item, scope) {
    if (!item?.id || deleting) return;
    const msg = deleteConfirmMessage(item, scope);
    if (!window.confirm(msg)) return;
    deleting = true;
    deleteError = '';
    try {
      const q = scope && scope !== 'file' ? `?scope=${encodeURIComponent(scope)}` : '';
      const res = await fetch(`/api/v1/library/${item.id}${q}`, { method: 'DELETE', headers: headers() });
      if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
      if (shouldClearSelected(item, scope)) selected = null;
      if (maizeSelectedId === item.id) clearMaizeSelection();
      await refreshLibrary();
      await refreshMaizeLibrary();
    } catch (err) {
      deleteError = String(err);
    } finally {
      deleting = false;
    }
  }

  async function removeSelected(scope) {
    if (!selected) return;
    await removeItem(selected, scope);
  }

  async function removeMaizeSelected() {
    const item = maizeSelectedItem;
    if (!item?.id) return;
    await removeItem(item, 'file');
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
      streaming = withDownloadRules(await res.json());
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

  function fmtRate(bps) {
    const n = Number(bps) || 0;
    if (n < 1024) return `${n} B/s`;
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB/s`;
    return `${(n / (1024 * 1024)).toFixed(2)} MB/s`;
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

  function toggleRuleChip(key, field, value) {
    const rule = { ...(streaming?.[key] || {}) };
    const cur = rule[field] || [];
    rule[field] = cur.includes(value) ? cur.filter((x) => x !== value) : [...cur, value];
    streaming = { ...streaming, [key]: rule };
  }

  function setRuleField(key, field, value) {
    streaming = { ...streaming, [key]: { ...(streaming?.[key] || {}), [field]: value } };
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

  const maizeUploadGroups = $derived(groupMaizeUploadFiles(maizeUploadFiles));

  const filteredMaizeItems = $derived(
    maizeItems.filter((item) => {
      const q = maizeQuery.trim().toLowerCase();
      if (!q) return true;
      const title = String(item.title || '').toLowerCase();
      const studio = String(item.studio || '').toLowerCase();
      return title.includes(q) || studio.includes(q);
    }),
  );

  const maizeStudioOptions = $derived(collectMaizeValues((it) => (it.studio ? [it.studio] : [])));
  const maizePerformerOptions = $derived(
    collectMaizeValues((it) => (Array.isArray(it.performers) ? it.performers : [])),
  );
  const maizeTagOptions = $derived(
    collectMaizeValues((it) => {
      if (Array.isArray(it.tags) && it.tags.length) return it.tags;
      if (Array.isArray(it.genres)) return it.genres;
      return [];
    }),
  );

  const maizeStudioSuggestions = $derived(
    filterMaizeSuggestions(
      maizeStudioOptions,
      maizeStudioDraft,
      maizeForm.studio ? [maizeForm.studio] : [],
    ),
  );
  const maizePerformerSuggestions = $derived(
    filterMaizeSuggestions(maizePerformerOptions, maizePerformerDraft, maizeForm.performers),
  );
  const maizeTagSuggestions = $derived(
    filterMaizeSuggestions(maizeTagOptions, maizeTagDraft, maizeForm.tags),
  );

  const maizeDirty = $derived(
    !!maizeSelectedId && maizeFormBaseline !== '' && JSON.stringify(maizeForm) !== maizeFormBaseline,
  );

  const maizeSelectedItem = $derived(
    maizeItems.find((it) => it.id === maizeSelectedId) || null,
  );
  const maizePlayId = $derived(maizeVideoChoice || maizeSelectedId);
  const maizeVideos = $derived(Array.isArray(maizeSelected?.videos) ? maizeSelected.videos : []);
  const maizeFunscripts = $derived(Array.isArray(maizeSelected?.funscripts) ? maizeSelected.funscripts : []);

  const filteredMaizeActors = $derived(
    maizeActors.filter((a) => {
      const q = maizeActorQuery.trim().toLowerCase();
      if (!q) return true;
      const hay = `${a.name || ''} ${(a.aliases || []).join(' ')}`.toLowerCase();
      return hay.includes(q);
    }),
  );

  const maizeActorDirty = $derived(
    !!maizeActorSlug &&
      maizeActorBaseline !== '' &&
      JSON.stringify(maizeActorForm) !== maizeActorBaseline,
  );

  const tokenPresent = $derived(!!(token || localStorage.getItem('coog-token')));

  $effect(() => {
    document.title = `${pageMeta.label} · Coog`;
  });

  $effect(() => {
    function onKey(e) {
      if (e.key === 'Escape') navOpen = false;
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

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
        if (msg.type === 'library.changed') {
          refreshLibrary();
          refreshMaizeLibrary();
        }
      } catch {
        /* ignore */
      }
    };
    return () => ws.close();
  });
</script>

<div class="shell" class:nav-open={navOpen}>
  <button class="scrim" aria-hidden={!navOpen} tabindex={navOpen ? 0 : -1} aria-label="Close menu" onclick={() => navOpen = false}></button>
  <aside>
    <div class="brand">
      <span class="mark" aria-hidden="true">C</span>
      <div>
        <h1>Coog</h1>
        <p>Ops console</p>
      </div>
    </div>
    <nav>
      {#each NAV_GROUPS as group}
        <p class="nav-label">{group.label}</p>
        {#each group.items as item}
          <button class:active={page === item.id} onclick={() => go(item.id)}>
            <svg class="nav-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              {#if item.id === 'overview'}
                <rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>
              {:else if item.id === 'downloads'}
                <path d="M12 3v12"/><path d="M7 11l5 5 5-5"/><path d="M5 21h14"/>
              {:else if item.id === 'library'}
                <path d="M4 19a2 2 0 0 0 2 2h12"/><path d="M6 3h12v16H6z"/><path d="M10 7h4"/>
              {:else if item.id === 'activity'}
                <path d="M4 12h3l2-6 4 12 2-6h5"/>
              {:else if item.id === 'streaming'}
                <circle cx="12" cy="12" r="9"/><path d="M10 8l6 4-6 4V8z"/>
              {:else if item.id === 'subtitles'}
                <rect x="3" y="5" width="18" height="14" rx="2"/><path d="M7 15h4M13 15h4M7 11h10"/>
              {:else if item.id === 'taste'}
                <path d="M12 3l2.4 6.6L21 12l-6.6 2.4L12 21l-2.4-6.6L3 12l6.6-2.4L12 3z"/>
              {:else if item.id === 'cache'}
                <ellipse cx="12" cy="7" rx="7" ry="3"/><path d="M5 7v5c0 1.7 3.1 3 7 3s7-1.3 7-3V7"/><path d="M5 12v5c0 1.7 3.1 3 7 3s7-1.3 7-3v-5"/>
              {:else if item.id === 'maize'}
                <path d="M12 3c4 4 4 8 0 18-4-10-4-14 0-18z"/><path d="M12 7c2.2 1.6 3 3.4 3 6"/>
              {:else}
                <rect x="7" y="4" width="10" height="16" rx="2"/><circle cx="12" cy="17" r="1"/>
              {/if}
            </svg>
            <span>{item.label}</span>
          </button>
        {/each}
      {/each}
    </nav>
    <div class="aside-foot">
      <label class="token">
        Token
        <input bind:value={token} placeholder="COOG_AUTH_TOKEN" onchange={saveToken} />
      </label>
      <div class="status-stack">
        <div class="auth-status" class:ok={authStatus === 'ok'} class:bad={authStatus === 'unauthorized' || authStatus === 'error'}>
          {#if authStatus === 'ok'}
            <span class="dot ok"></span>
            <span>Authorized</span>
          {:else if authStatus === 'unauthorized'}
            <span class="dot bad"></span>
            <span>401 — paste the server token</span>
          {:else if authStatus === 'error'}
            <span class="dot bad"></span>
            <span>API unreachable</span>
          {:else}
            <span class="dot"></span>
            <span>Checking auth…</span>
          {/if}
        </div>
        <div class="health">
          <span class="dot" class:ok={health?.status === 'ok'} class:bad={!!healthError}></span>
          {#if health}
            <span>{health.status} · {health.version}</span>
          {:else}
            <span>{healthError ? 'unreachable' : 'checking…'}</span>
          {/if}
        </div>
      </div>
    </div>
  </aside>

  <div class="workspace">
    <div class="topbar">
      <button class="icon-btn" aria-label="Open menu" onclick={() => navOpen = true}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
          <path d="M4 7h16M4 12h16M4 17h16"/>
        </svg>
      </button>
      <div class="topbar-title">
        {#if pageGroup}<span class="eyebrow">{pageGroup}</span>{/if}
        <strong>{pageMeta.label}</strong>
      </div>
    </div>
    <main
    class:has-savebar={(page === 'streaming' && streamDirty) || (page === 'subtitles' && subDirty)}
    class:wide={page === 'maize'}
  >
    {#if page === 'overview'}
      <header>
        <div>
          {#if pageGroup}<p class="eyebrow">{pageGroup}</p>{/if}
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
        <button type="button" class="card jump" onclick={() => go('library')}>
          <h3>Library</h3>
          <p class="stat">{stats?.mediaCount ?? items.length}</p>
          <p class="muted">
            {#if stats?.disk}
              {bytes(stats.disk.freeBytes)} free of {bytes(stats.disk.totalBytes)}
            {:else}
              {stats?.libraryPath || '—'}
            {/if}
          </p>
        </button>
        <button type="button" class="card jump" onclick={() => go('downloads')}>
          <h3>Jobs</h3>
          <p class="stat">{stats?.jobs?.active ?? 0} active</p>
          <p class="muted">{stats?.jobs?.error ?? 0} failed · {stats?.jobs?.finished ?? 0} finished</p>
        </button>
        <button type="button" class="card jump" onclick={() => go('streaming')}>
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
        </button>
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
      <article class="panel config-card">
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
          <div class="empty">
            <strong>Nothing in progress</strong>
            <p>Titles the TV leaves mid-play show up here.</p>
          </div>
        {/each}
      </div>
    {/if}

    {#if page === 'activity'}
      <header>
        <div>
          {#if pageGroup}<p class="eyebrow">{pageGroup}</p>{/if}
          <h2>Activity</h2>
          <p class="muted">Client, API, and worker events in one feed. Tokens are redacted.</p>
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
          <div class="empty">
            <strong>Quiet so far</strong>
            <p>Play something on the TV or queue a download and events will land here.</p>
          </div>
        {/each}
      </div>
    {/if}

    {#if page === 'downloads'}
      <header>
        <div>
          {#if pageGroup}<p class="eyebrow">{pageGroup}</p>{/if}
          <h2>Downloads</h2>
          <p class="muted">Live over WebSocket. Cancel removes the transfer and its temp files.</p>
        </div>
        <button class="ghost" onclick={refreshJobs}>Refresh</button>
      </header>
      <div class="composer">
        <input bind:value={jobUrl} placeholder="Paste a URL for yt-dlp…" onkeydown={(e) => e.key === 'Enter' && enqueue()} />
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
                {#if job.transfer}
                  <span class="muted">↓ {fmtRate(job.transfer.downloadBps)} · ↑ {fmtRate(job.transfer.uploadBps)} · {job.transfer.seeders ?? 0}S / {job.transfer.peers ?? 0}P · {job.transfer.health || '—'}</span>
                {/if}
              </div>
              <div class="bar"><div class="bar-fill" style={`width: ${Math.min(100, Math.round((job.progress || 0) * 100))}%`}></div></div>
              {#if job.error}<p class="error">{job.error}</p>{/if}
              {#if expanded[job.id]}
                <dl class="facts">
                  <div><dt>IMDB</dt><dd>{job.imdbId || '—'}</dd></div>
                  <div><dt>Job</dt><dd><code>{job.id}</code></dd></div>
                  {#if job.mediaId}<div><dt>Media</dt><dd><code>{job.mediaId}</code></dd></div>{/if}
                  {#if job.transfer}
                    <div><dt>Transfer</dt><dd>↓ {fmtRate(job.transfer.downloadBps)} · ↑ {fmtRate(job.transfer.uploadBps)}</dd></div>
                    <div><dt>Peers</dt><dd>{job.transfer.seeders ?? 0} seeders · {job.transfer.peers ?? 0} connected · {job.transfer.totalPeers ?? 0} known · {job.transfer.health || '—'}</dd></div>
                  {/if}
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
              {#if job.status === 'error'}
                <button class="ghost danger" onclick={() => cancelJob(job.id)}>Remove download</button>
              {:else if job.status !== 'finished' && job.status !== 'cancelled'}
                <button class="ghost danger" onclick={() => cancelJob(job.id)}>Cancel</button>
              {/if}
            </div>
          </article>
        {:else}
          <div class="empty">
            <strong>No transfers</strong>
            <p>Play a catalog title on the TV, or paste a URL above.</p>
          </div>
        {/each}
      </div>
    {/if}

    {#if page === 'cache'}
      <header>
        <div>
          {#if pageGroup}<p class="eyebrow">{pageGroup}</p>{/if}
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
          {#if pageGroup}<p class="eyebrow">{pageGroup}</p>{/if}
          <h2>Maize</h2>
          <p class="muted">Upload new titles, edit FunPlay-compatible scene metadata, or manage the adult lock PIN.</p>
        </div>
        <div class="toolbar">
          <button class="ghost" onclick={() => { refreshMaize(); refreshMaizeLibrary(); refreshMaizeSuggestSource(); refreshMaizeActors(); }}>Refresh</button>
          <button class="ghost" onclick={lockAllMaize} disabled={maizeBusy}>Lock all sessions</button>
        </div>
      </header>
      {#if maizeError}<p class="error">{maizeError}</p>{/if}

      <details class="maize-lock">
        <summary>
          <span>Lock &amp; PIN</span>
          <span class="maize-lock-status">
            <span class="pill" class:ready={!!maize?.configured}>{maize?.configured ? 'PIN set' : 'PIN not set'}</span>
            <span class="muted mono">{maize?.bucket || 'Maize'}</span>
            <span class="muted">{maize?.idleMinutes ?? 20}m idle</span>
          </span>
        </summary>
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
        <article class="card maize-pin-card">
          <h3>Set PIN</h3>
          <div class="toolbar">
            <input type="password" inputmode="numeric" autocomplete="new-password" bind:value={maizePin} placeholder="New PIN" />
            <input type="password" inputmode="numeric" autocomplete="new-password" bind:value={maizePin2} placeholder="Confirm PIN" />
            <button onclick={saveMaizePin} disabled={maizeBusy}>Save PIN</button>
            <button class="ghost" onclick={clearMaizePin} disabled={maizeBusy || !maize?.configured}>Clear PIN</button>
          </div>
        </article>
      </details>

      <div class="chips maize-tabs">
        <button class="chip" class:on={maizeTab === 'scenes'} onclick={() => { maizeTab = 'scenes'; }}>Scenes</button>
        <button class="chip" class:on={maizeTab === 'actors'} onclick={() => { maizeTab = 'actors'; refreshMaizeActors(); }}>Actors</button>
      </div>

      {#if maizeTab === 'scenes'}
      <section class="maize-workspace">
        <div class="maize-list-pane">
          <div class="maize-list-head">
            <div class="maize-list-title">
              <strong>Scenes</strong>
              <span class="muted">{filteredMaizeItems.length}{maizeQuery || maizeFilter !== 'all' ? ` of ${maizeItems.length}` : ''}</span>
            </div>
            <input class="maize-search" bind:value={maizeQuery} placeholder="Search title or studio" />
            <div class="chips maize-filters">
              {#each [['all', 'All'], ['scripted', 'Scripted'], ['meta', 'Has meta'], ['nometa', 'No meta']] as [id, label]}
                <button
                  class="chip"
                  class:on={maizeFilter === id}
                  onclick={() => {
                    maizeFilter = id;
                    refreshMaizeLibrary();
                  }}
                >{label}</button>
              {/each}
            </div>
            <div
              class="maize-import"
              role="group"
              aria-label="Upload Maize titles"
              ondragover={(e) => e.preventDefault()}
              ondrop={onMaizeUploadDrop}
            >
              <div class="maize-import-row">
                <label class="ghost maize-upload">
                  Add files
                  <input
                    type="file"
                    multiple
                    accept=".mp4,.m4v,.mkv,.mov,.webm,.avi,.wmv,.ts,.m2ts,.funscript,video/*,.json"
                    disabled={!!maizeUploadBusy}
                    onchange={onMaizeUploadPick}
                  />
                </label>
                <label class="ghost maize-upload">
                  Add folder
                  <input
                    type="file"
                    multiple
                    webkitdirectory
                    directory
                    disabled={!!maizeUploadBusy}
                    onchange={onMaizeUploadPick}
                  />
                </label>
                <p class="muted maize-import-hint">Drop a folder (or files). One video plus optional .funscript files become one title; the folder name is used when it fits.</p>
              </div>
              {#if maizeUploadFiles.length}
                {#if !maizeUploadGroups.length}
                  <p class="error">Add a video. Funscripts are optional and attach to a matching title or the same folder.</p>
                {/if}
                <ul class="maize-import-list">
                  {#each maizeUploadGroups as group (group.key)}
                    <li>
                      <div>
                        <strong>{group.title}</strong>
                        <span class="muted">
                          {group.videos.map((f) => maizeUploadDisplayName(f)).join(', ')}
                          {#if group.scripts.length}
                            · {group.scripts.length} funscript{group.scripts.length === 1 ? '' : 's'}
                          {:else}
                            · no funscript
                          {/if}
                        </span>
                      </div>
                      <button class="ghost" disabled={!!maizeUploadBusy} onclick={() => removeMaizeUploadGroup(group.key)}>Remove</button>
                    </li>
                  {/each}
                </ul>
                {#if maizeUploadError}<p class="error">{maizeUploadError}</p>{/if}
                <div class="toolbar maize-import-actions">
                  <button onclick={uploadMaizeTitles} disabled={!!maizeUploadBusy || !maizeUploadGroups.length}>
                    {maizeUploadBusy ? `Uploading ${maizeUploadBusy}…` : `Upload ${maizeUploadGroups.length} title${maizeUploadGroups.length === 1 ? '' : 's'}`}
                  </button>
                  <button class="ghost" onclick={clearMaizeUpload} disabled={!!maizeUploadBusy}>Clear</button>
                </div>
              {/if}
            </div>
          </div>
          {#if maizeLibError}<p class="error maize-list-error">{maizeLibError}</p>{/if}
          <div class="maize-list" role="listbox" aria-label="Maize scenes">
            {#each filteredMaizeItems as item}
              <button
                type="button"
                class="maize-row"
                class:selected={maizeSelectedId === item.id}
                class:dirty={maizeSelectedId === item.id && maizeDirty}
                role="option"
                aria-selected={maizeSelectedId === item.id}
                onclick={() => selectMaizeScene(item.id)}
              >
                <span class="maize-row-main">
                  <span class="maize-row-title">{item.title || 'Untitled'}</span>
                  <span class="maize-row-sub">
                    {#if item.studio}<span>{item.studio}</span>{/if}
                    {#if formatMaizeReleaseLabel(item)}<span>{formatMaizeReleaseLabel(item)}</span>{/if}
                  </span>
                </span>
                <span class="maize-row-badges">
                  {#if item.hasMeta}<span class="pill ready">meta</span>{/if}
                  {#if item.hasPoster}<span class="pill">art</span>{/if}
                  {#if item.hasFunscript}<span class="pill">script</span>{/if}
                  {#if !item.hasMeta}<span class="pill">no meta</span>{/if}
                </span>
              </button>
            {:else}
              <p class="maize-empty muted">No scenes match. Rescan the library after adding files under the Maize bucket.</p>
            {/each}
          </div>
        </div>

        <div class="maize-form-pane" class:active={!!maizeSelectedId}>
          {#if maizeSelectedId}
            <div class="maize-form-head">
              <div>
                <h3>Edit metadata</h3>
                <p class="maize-form-path mono" title={maizeSelectedPath}>{maizeSelectedPath || maizeSelectedItem?.path || '—'}</p>
              </div>
              <div class="toolbar">
                {#if maizeDirty}<span class="pill ready">unsaved</span>{/if}
                <button class="ghost" onclick={clearMaizeSelection} disabled={maizeMetaBusy}>Close</button>
              </div>
            </div>
            {#if maizeMetaError}<p class="error">{maizeMetaError}</p>{/if}
            <div class="maize-form-body">
              <div class="maize-player">
                {#if maizeVideos.length > 1}
                  <label class="block">Video file
                    <select bind:value={maizeVideoChoice}>
                      {#each maizeVideos as v}
                        <option value={v.id}>{v.label}{v.preferred ? ' (default)' : ''} · {v.filename || v.id}</option>
                      {/each}
                    </select>
                  </label>
                {/if}
                {#if maizeFunscripts.length > 0}
                  <label class="block">Funscript
                    <select bind:value={maizeScriptChoice}>
                      {#each maizeFunscripts as s}
                        <option value={s.name}>{s.label}{s.preferred ? ' (default)' : ''}</option>
                      {/each}
                    </select>
                  </label>
                {/if}
                {#key maizePlayId}
                  <video
                    bind:this={maizeVideoEl}
                    controls
                    preload="metadata"
                    src={maizeMediaUrl(maizePlayId ? `/api/v1/media/${maizePlayId}/stream` : (maizeSelected?.streamUrl || `/api/v1/media/${maizeSelectedId}/stream`))}
                    ontimeupdate={() => {
                      maizeVideoTime = maizeVideoEl?.currentTime || 0;
                    }}
                  >
                    <track kind="captions" />
                  </video>
                {/key}
                <p class="muted maize-player-hint">
                  Seek to a frame, then capture art below.
                  {#if maizeVideoTime > 0}
                    · {Math.floor(maizeVideoTime / 60)}:{String(Math.floor(maizeVideoTime % 60)).padStart(2, '0')}
                  {/if}
                </p>
              </div>

              <div class="maize-art-grid">
                {#each [
                  { kind: 'poster', label: 'Poster', url: maizeSelected?.posterUrl, has: maizeSelected?.hasPoster },
                  { kind: 'backdrop', label: 'Backdrop', url: maizeSelected?.backdropUrl, has: maizeSelected?.hasBackdrop },
                  { kind: 'logo', label: 'Logo', url: maizeSelected?.logoUrl, has: maizeSelected?.hasLogo },
                ] as slot}
                  <figure class="maize-art-slot" class:logo={slot.kind === 'logo'}>
                    <div class="maize-art-preview">
                      {#if slot.has || slot.url}
                        <img src={maizeMediaUrl(slot.url)} alt={slot.label} />
                      {:else}
                        <span class="muted">No {slot.label.toLowerCase()}</span>
                      {/if}
                    </div>
                    <div class="maize-art-actions">
                      <button
                        class="ghost"
                        disabled={!!maizeArtBusy || maizeMetaBusy}
                        onclick={() => captureMaizeArt(slot.kind)}
                      >{maizeArtBusy === slot.kind ? 'Working…' : 'Use frame'}</button>
                      <label class="ghost maize-upload">
                        Upload
                        <input
                          type="file"
                          accept="image/jpeg,image/png,image/webp,.jpg,.jpeg,.png,.webp"
                          disabled={!!maizeArtBusy || maizeMetaBusy}
                          onchange={(e) => {
                            const f = e.currentTarget.files?.[0];
                            e.currentTarget.value = '';
                            if (f) uploadMaizeArt(slot.kind, f);
                          }}
                        />
                      </label>
                    </div>
                    <figcaption>{slot.label}</figcaption>
                  </figure>
                {/each}
              </div>

              <h3 class="maize-meta-heading">Enrich from IAFD</h3>
              <label class="block">IAFD title URL
                <input bind:value={maizeIaFdUrl} placeholder="https://www.iafd.com/title.rme/id=…" />
              </label>
              <p class="muted maize-form-hint">Overwrites title, studio, performers, tags, description, year, and director from the title page.</p>
              <div class="toolbar" style="margin-bottom: 1rem;">
                <button
                  class="ghost"
                  disabled={maizeEnrichBusy || maizeMetaBusy || !maizeIaFdUrl.trim()}
                  onclick={() => enrichMaizeScene(false)}
                >{maizeEnrichBusy ? 'Enriching…' : 'Enrich'}</button>
                <button
                  class="ghost"
                  disabled={maizeEnrichBusy || maizeMetaBusy || !maizeIaFdUrl.trim()}
                  onclick={() => enrichMaizeScene(true)}
                >Force</button>
              </div>

              <h3 class="maize-meta-heading">Metadata</h3>
              <label class="block">Title
                <input bind:value={maizeForm.title} />
              </label>
              <div class="maize-form-grid maize-release-grid">
                <div class="block maize-chip-field">
                  <span class="maize-chip-label">Studio</span>
                  <div class="maize-chip-box">
                    {#if maizeForm.studio}
                      <span class="maize-tile">
                        {maizeForm.studio}
                        <button type="button" class="maize-tile-x" aria-label="Remove studio" onclick={clearMaizeStudio}>×</button>
                      </span>
                    {/if}
                    <input
                      bind:value={maizeStudioDraft}
                      placeholder={maizeForm.studio ? 'Replace studio…' : 'Add studio…'}
                      autocomplete="off"
                      onfocus={() => openMaizeSuggest('studio')}
                      onblur={() => closeMaizeSuggest('studio')}
                      oninput={() => onMaizeChipInput('studio')}
                      onkeydown={(e) => onMaizeChipKeydown(e, 'studio')}
                    />
                  </div>
                  {#if maizeSuggestField === 'studio' && maizeStudioSuggestions.length}
                    <ul class="maize-suggest-menu" role="listbox">
                      {#each maizeStudioSuggestions as opt, i}
                        <li>
                          <button
                            type="button"
                            class="maize-suggest-option"
                            class:active={maizeSuggestIndex === i}
                            role="option"
                            aria-selected={maizeSuggestIndex === i}
                            onmousedown={(e) => e.preventDefault()}
                            onclick={() => pickMaizeSuggestion('studio', opt)}
                          >{opt}</button>
                        </li>
                      {/each}
                    </ul>
                  {/if}
                </div>
                <div class="block">
                  <span class="maize-chip-label">Release</span>
                  <div class="maize-release">
                    <select bind:value={maizeForm.releasePrecision} aria-label="Release precision">
                      <option value="year">Year</option>
                      <option value="month">Month</option>
                      <option value="day">Day</option>
                    </select>
                    <input type="number" bind:value={maizeForm.year} placeholder="YYYY" min="1900" max="2100" />
                    {#if maizeForm.releasePrecision === 'month' || maizeForm.releasePrecision === 'day'}
                      <select bind:value={maizeForm.releaseMonth} aria-label="Month">
                        <option value="">Month</option>
                        {#each [
                          ['01', 'Jan'], ['02', 'Feb'], ['03', 'Mar'], ['04', 'Apr'],
                          ['05', 'May'], ['06', 'Jun'], ['07', 'Jul'], ['08', 'Aug'],
                          ['09', 'Sep'], ['10', 'Oct'], ['11', 'Nov'], ['12', 'Dec'],
                        ] as [val, label]}
                          <option value={val}>{label}</option>
                        {/each}
                      </select>
                    {/if}
                    {#if maizeForm.releasePrecision === 'day'}
                      <input type="number" bind:value={maizeForm.releaseDay} placeholder="DD" min="1" max="31" />
                    {/if}
                    {#if maizeForm.year}
                      <button
                        type="button"
                        class="ghost"
                        onclick={() => {
                          maizeForm = { ...maizeForm, year: '', releaseMonth: '', releaseDay: '', releasePrecision: 'year' };
                        }}
                      >Clear</button>
                    {/if}
                  </div>
                </div>
                <label class="block">Rating
                  <input type="number" step="0.1" bind:value={maizeForm.rating} placeholder="8.5" />
                </label>
              </div>
              <label class="block">Description
                <textarea rows="4" bind:value={maizeForm.description} placeholder="Plot / scene notes"></textarea>
              </label>
              <div class="block maize-chip-field">
                <span class="maize-chip-label">Performers</span>
                <div class="maize-chip-box">
                  {#each maizeForm.performers as name, i}
                    <span class="maize-tile">
                      {name}
                      <button type="button" class="maize-tile-x" aria-label={`Remove ${name}`} onclick={() => removeMaizeListValue('performers', i)}>×</button>
                    </span>
                  {/each}
                  <input
                    bind:value={maizePerformerDraft}
                    placeholder="Add performer…"
                    autocomplete="off"
                    onfocus={() => openMaizeSuggest('performers')}
                    onblur={() => closeMaizeSuggest('performers')}
                    oninput={() => onMaizeChipInput('performers')}
                    onkeydown={(e) => onMaizeChipKeydown(e, 'performers')}
                  />
                </div>
                {#if maizeSuggestField === 'performers' && maizePerformerSuggestions.length}
                  <ul class="maize-suggest-menu" role="listbox">
                    {#each maizePerformerSuggestions as opt, i}
                      <li>
                        <button
                          type="button"
                          class="maize-suggest-option"
                          class:active={maizeSuggestIndex === i}
                          role="option"
                          aria-selected={maizeSuggestIndex === i}
                          onmousedown={(e) => e.preventDefault()}
                          onclick={() => pickMaizeSuggestion('performers', opt)}
                        >{opt}</button>
                      </li>
                    {/each}
                  </ul>
                {/if}
              </div>
              <div class="block maize-chip-field">
                <span class="maize-chip-label">Tags</span>
                <div class="maize-chip-box">
                  {#each maizeForm.tags as name, i}
                    <span class="maize-tile">
                      {name}
                      <button type="button" class="maize-tile-x" aria-label={`Remove ${name}`} onclick={() => removeMaizeListValue('tags', i)}>×</button>
                    </span>
                  {/each}
                  <input
                    bind:value={maizeTagDraft}
                    placeholder="Add tag…"
                    autocomplete="off"
                    onfocus={() => openMaizeSuggest('tags')}
                    onblur={() => closeMaizeSuggest('tags')}
                    oninput={() => onMaizeChipInput('tags')}
                    onkeydown={(e) => onMaizeChipKeydown(e, 'tags')}
                  />
                </div>
                {#if maizeSuggestField === 'tags' && maizeTagSuggestions.length}
                  <ul class="maize-suggest-menu" role="listbox">
                    {#each maizeTagSuggestions as opt, i}
                      <li>
                        <button
                          type="button"
                          class="maize-suggest-option"
                          class:active={maizeSuggestIndex === i}
                          role="option"
                          aria-selected={maizeSuggestIndex === i}
                          onmousedown={(e) => e.preventDefault()}
                          onclick={() => pickMaizeSuggestion('tags', opt)}
                        >{opt}</button>
                      </li>
                    {/each}
                  </ul>
                {/if}
              </div>
              <p class="muted maize-form-hint">Metadata → <code>movie.meta.json</code>. Art → <code>poster.jpg</code> / <code>fanart.jpg</code> / <code>logo.png</code>. Type to see matching suggestions; Enter or comma adds a tile.</p>
            </div>
            <div class="maize-form-foot">
              <button onclick={saveMaizeMeta} disabled={maizeMetaBusy || !maizeDirty}>
                {maizeMetaBusy ? 'Saving…' : 'Save'}
              </button>
              <button class="ghost" onclick={cancelMaizeMeta} disabled={maizeMetaBusy || !maizeDirty}>Revert</button>
              <button class="ghost danger" onclick={removeMaizeSelected} disabled={deleting || maizeMetaBusy}>
                {deleting ? 'Deleting…' : 'Delete file'}
              </button>
            </div>
          {:else}
            <div class="maize-form-empty">
              <h3>Scene metadata</h3>
              <p class="muted">Select a scene from the list to edit title, description, studio, year, rating, performers, and tags.</p>
            </div>
          {/if}
        </div>
      </section>
      {/if}

      {#if maizeTab === 'actors'}
      <section class="maize-workspace">
        <div class="maize-list-pane">
          <div class="maize-list-head">
            <div class="maize-list-title">
              <strong>Actors</strong>
              <span class="muted">{filteredMaizeActors.length}{maizeActorQuery ? ` of ${maizeActors.length}` : ''}</span>
            </div>
            <input class="maize-search" bind:value={maizeActorQuery} placeholder="Search name or alias" />
          </div>
          {#if maizeActorsError}<p class="error maize-list-error">{maizeActorsError}</p>{/if}
          <div class="maize-list" role="listbox" aria-label="Maize actors">
            {#each filteredMaizeActors as actor}
              <button
                type="button"
                class="maize-row"
                class:selected={maizeActorSlug === actor.slug}
                class:dirty={maizeActorSlug === actor.slug && maizeActorDirty}
                role="option"
                aria-selected={maizeActorSlug === actor.slug}
                onclick={() => selectMaizeActor(actor.slug)}
              >
                <span class="maize-row-main">
                  <span class="maize-row-title">{actor.name || actor.slug}</span>
                  <span class="maize-row-sub">
                    <span>{actor.sceneCount || 0} scenes</span>
                    {#if actor.galleryCount}<span>{actor.galleryCount} gallery</span>{/if}
                  </span>
                </span>
                <span class="maize-row-badges">
                  {#if actor.hasHeadshot}<span class="pill ready">photo</span>{/if}
                  {#if actor.enriched}<span class="pill">enriched</span>{/if}
                  {#if actor.locked}<span class="pill">locked</span>{/if}
                </span>
              </button>
            {:else}
              <p class="maize-empty muted">No actors yet. Add performers on scenes, or create a profile by editing a performer name here after it appears from credits.</p>
            {/each}
          </div>
        </div>

        <div class="maize-form-pane" class:active={!!maizeActorSlug}>
          {#if maizeActorSlug}
            <div class="maize-form-head">
              <div>
                <h3>Edit actor</h3>
                <p class="maize-form-path mono">{maizeActor?.slug || maizeActorSlug}</p>
              </div>
              <div class="toolbar">
                {#if maizeActorDirty}<span class="pill ready">unsaved</span>{/if}
                <button class="ghost" onclick={clearMaizeActorSelection} disabled={maizeActorBusy}>Close</button>
              </div>
            </div>
            {#if maizeActorError}<p class="error">{maizeActorError}</p>{/if}
            <div class="maize-form-body">
              <div class="maize-actor-hero">
                <div class="maize-actor-shot">
                  {#if maizeActor?.hasHeadshot && maizeActor?.headshotUrl}
                    <img src={maizeActorMediaUrl(maizeActor.headshotUrl)} alt={maizeActorForm.name || 'Headshot'} />
                  {:else}
                    <span class="muted">No headshot</span>
                  {/if}
                </div>
                <div class="maize-actor-shot-actions">
                  <label class="ghost maize-upload">
                    Upload headshot
                    <input
                      type="file"
                      accept="image/jpeg,image/png,image/webp,.jpg,.jpeg,.png,.webp"
                      disabled={maizeActorBusy}
                      onchange={(e) => {
                        const f = e.currentTarget.files?.[0];
                        e.currentTarget.value = '';
                        if (f) uploadMaizeActorHeadshot(f);
                      }}
                    />
                  </label>
                  <label class="check">
                    <input type="checkbox" bind:checked={maizeActorForm.locked} />
                    Locked (skip FunPlay auto-enrich overwrite)
                  </label>
                </div>
              </div>

              <label class="block">Name
                <input bind:value={maizeActorForm.name} />
              </label>
              <div class="block maize-chip-field">
                <span class="maize-chip-label">Aliases</span>
                <div class="maize-chip-box">
                  {#each maizeActorForm.aliases as name, i}
                    <span class="maize-tile">
                      {name}
                      <button type="button" class="maize-tile-x" aria-label={`Remove ${name}`} onclick={() => removeMaizeActorAlias(i)}>×</button>
                    </span>
                  {/each}
                  <input
                    bind:value={maizeAliasDraft}
                    placeholder="Add alias…"
                    onkeydown={(e) => {
                      if (e.key === 'Enter' || e.key === ',') {
                        e.preventDefault();
                        addMaizeActorAlias(maizeAliasDraft);
                      }
                    }}
                  />
                </div>
              </div>
              <label class="block">Bio
                <textarea rows="4" bind:value={maizeActorForm.bio}></textarea>
              </label>
              <div class="maize-form-grid maize-actor-grid">
                <label class="block">Birthday
                  <input bind:value={maizeActorForm.birthday} placeholder="YYYY-MM-DD" />
                </label>
                <label class="block">Birthplace
                  <input bind:value={maizeActorForm.birthplace} />
                </label>
                <label class="block">Ethnicity
                  <input bind:value={maizeActorForm.ethnicity} />
                </label>
                <label class="block">Height
                  <input bind:value={maizeActorForm.height} />
                </label>
                <label class="block">Measurements
                  <input bind:value={maizeActorForm.measurements} />
                </label>
                <label class="block">Years active
                  <input bind:value={maizeActorForm.yearsActive} />
                </label>
              </div>
              <h3 class="maize-meta-heading">Links</h3>
              <div class="maize-form-grid maize-actor-grid">
                <label class="block">IAFD
                  <input bind:value={maizeActorForm.links.iafd} placeholder="https://www.iafd.com/…" />
                </label>
                <label class="block">Babehub
                  <input bind:value={maizeActorForm.links.babehub} placeholder="https://babehub.com/…" />
                </label>
                <label class="block">PornPics
                  <input bind:value={maizeActorForm.links.pornpics} />
                </label>
                <label class="block">Pornhub
                  <input bind:value={maizeActorForm.links.pornhub} placeholder="/model/… or /pornstar/…" />
                </label>
              </div>
              <p class="muted maize-form-hint">Writes <code>$COOG_DATA_PATH/people/…/actor.meta.json</code> (FunPlay People layout). Manual save sets locked unless you clear the checkbox. Enrich pulls IAFD bio, Babehub headshot/gallery, PornPics fill-in, and Pornhub avatar when <code>links.pornhub</code> is set.</p>
              <div class="toolbar" style="margin-bottom: 0.5rem;">
                <button
                  class="ghost"
                  disabled={maizeActorEnrichBusy || maizeActorBusy}
                  onclick={() => enrichMaizeActor(false)}
                >{maizeActorEnrichBusy ? 'Enriching…' : 'Enrich'}</button>
                <button
                  class="ghost"
                  disabled={maizeActorEnrichBusy || maizeActorBusy}
                  onclick={() => enrichMaizeActor(true)}
                >Force enrich</button>
              </div>
            </div>
            <div class="maize-form-foot">
              <button onclick={saveMaizeActor} disabled={maizeActorBusy || maizeActorEnrichBusy || !maizeActorDirty}>
                {maizeActorBusy ? 'Saving…' : 'Save'}
              </button>
              <button class="ghost" onclick={cancelMaizeActor} disabled={maizeActorBusy || maizeActorEnrichBusy || !maizeActorDirty}>Revert</button>
            </div>
          {:else}
            <div class="maize-form-empty">
              <h3>Actor metadata</h3>
              <p class="muted">Select an actor to edit profile fields, aliases, links, and headshot. Profiles live in Coog data (<code>people/</code>), not next to Maize videos.</p>
            </div>
          {/if}
        </div>
      </section>
      {/if}
    {/if}

    {#if page === 'interactive'}
      <header>
        <div>
          {#if pageGroup}<p class="eyebrow">{pageGroup}</p>{/if}
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
      <article class="panel">
        <div class="panel-head">
          <h3>Devices</h3>
        </div>
        {#each (interactiveEngine?.trustedDevices?.length ? interactiveEngine.trustedDevices : [...(interactiveEngine?.devices || []), ...(interactiveEngine?.knownDevices || [])]) as d}
          <div class="device">
            <div class="device-main">
              <strong>{d.name || d.deviceId}</strong>
              <p class="muted">{d.kind} · {d.status || (d.connected ? 'connected' : 'offline')}{#if d.batterySupported && d.batteryPercent >= 0} · {d.batteryPercent}%{/if} · int {d.intensity}% · off {d.offsetMs}ms</p>
            </div>
            <div class="device-actions">
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
                <button class="ghost danger" disabled={interactiveBusy} onclick={() => interactiveForget(d.deviceId)}>Remove</button>
              {/if}
            </div>
          </div>
        {:else}
          <div class="empty">
            <strong>No devices yet</strong>
            <p>Click Pair and power on a toy.</p>
          </div>
        {/each}
      </article>
    {/if}

    {#if page === 'library'}
      <header>
        <div>
          {#if pageGroup}<p class="eyebrow">{pageGroup}</p>{/if}
          <h2>Library</h2>
          <p class="muted">Search the on-disk catalog. Delete a row or open a title to remove a file, a season, or a whole series. Missing files are dropped on refresh.</p>
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
      <div class="toolbar search-bar">
        <input bind:value={libQuery} placeholder="Search title or path" />
        <select bind:value={libKind}>
          <option value="all">All kinds</option>
          <option value="movie">Movies</option>
          <option value="episode">Episodes</option>
        </select>
        <span class="muted count">{filteredItems.length}</span>
      </div>
      <div class="table-wrap">
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
              <td>
                <a href={item.streamUrl || `/api/v1/media/${item.id}/stream`} onclick={(e) => e.stopPropagation()}>stream</a>
                <button
                  class="ghost danger"
                  onclick={(e) => { e.stopPropagation(); removeItem(item); }}
                  disabled={deleting}
                >Delete</button>
              </td>
            </tr>
          {:else}
            <tr><td colspan="7" class="muted">No items. Point COOG_LIBRARY_PATH at Videos and rescan.</td></tr>
          {/each}
        </tbody>
      </table>
      </div>
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
              <button class="ghost danger" onclick={() => removeSelected('season')} disabled={deleting}>
                Delete this season
              </button>
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
          {#if pageGroup}<p class="eyebrow">{pageGroup}</p>{/if}
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
          {#if pageGroup}<p class="eyebrow">{pageGroup}</p>{/if}
          <h2>Subtitles</h2>
          <p class="muted">OpenSubtitles.com credentials and preferred languages.</p>
        </div>
        <button class="ghost" onclick={refreshSubtitles}>Refresh</button>
      </header>
      {#if subError}<p class="error">{subError}</p>{/if}
      {#if subSettings}
        <section class="panel">
          <div class="panel-head">
            <div>
              <h3>Behavior</h3>
              <p class="muted">How the TV searches and applies captions.</p>
            </div>
          </div>
          <div class="toggle-list">
            <label class="check">
              <input type="checkbox" bind:checked={subSettings.enabled} /> Enable OpenSubtitles search
            </label>
            <label class="check">
              <input type="checkbox" bind:checked={subSettings.autoLoad} /> Auto-load preferred language on play
            </label>
            <label class="check">
              <input type="checkbox" bind:checked={subSettings.preferEmbedded} /> Prefer embedded / sidecar over online
            </label>
          </div>
        </section>
        <section class="panel">
          <div class="panel-head">
            <div>
              <h3>Account</h3>
              <p class="muted">Free OpenSubtitles.com login plus an API consumer key.</p>
            </div>
          </div>
          <div class="settings-grid">
            <label class="field">Languages
              <input bind:value={subLangs} placeholder="en, et" />
            </label>
            <label class="field">User-Agent
              <input bind:value={subSettings.userAgent} placeholder="Coog v0.1.1" />
            </label>
            <label class="field">Username
              <input bind:value={subSettings.username} placeholder="opensubtitles.com username" autocomplete="username" />
            </label>
            <label class="field">Password
              <input bind:value={subPassword} type="password" placeholder={subSettings.hasPassword ? '•••• saved — paste to replace' : 'opensubtitles.com password'} autocomplete="current-password" />
            </label>
            <label class="field">API key
              <input bind:value={subApiKey} type="password" placeholder={subSettings.hasApiKey ? `${subSettings.apiKeyMasked} — paste to replace` : 'from opensubtitles.com API consumers'} />
            </label>
          </div>
          <p class="hint">Register an API consumer at opensubtitles.com, then set the User-Agent to the exact Application Name.</p>
        </section>
      {:else if !subError}
        <p class="muted">Loading…</p>
      {:else}
        <div class="empty">
          <strong>Couldn’t load subtitle settings</strong>
          <p>{subError}</p>
          <div class="toolbar">
            <button class="ghost" onclick={refreshSubtitles}>Retry</button>
          </div>
        </div>
      {/if}
    {/if}

    {#if page === 'streaming'}
      <header>
        <div>
          {#if pageGroup}<p class="eyebrow">{pageGroup}</p>{/if}
          <h2>Streaming</h2>
          <p class="muted">Real-Debrid, binge knobs, and autodownload rules. The TV uses the source the server already picked.</p>
        </div>
        <button class="ghost" onclick={refreshStreaming}>Refresh</button>
      </header>
      {#if streaming}
        <section class="panel">
          <div class="panel-head">
            <div>
              <h3>Playback</h3>
              <p class="muted">What happens after a title starts playing.</p>
            </div>
          </div>
          <div class="toggle-list">
            <label class="check"><input type="checkbox" bind:checked={streaming.saveToLibrary} /> Save finished streams to the local library</label>
            <label class="check"><input type="checkbox" bind:checked={streaming.autoplayNextEpisode} /> Auto-play next episode</label>
            <label class="check"><input type="checkbox" bind:checked={streaming.autoDownloadNextEpisode} /> Auto-download next episode</label>
            <label class="check"><input type="checkbox" bind:checked={streaming.includeWebStreams} /> Include web streams when searching</label>
            <label class="check"><input type="checkbox" bind:checked={streaming.autoSelectSource} /> Auto-select a matching source</label>
          </div>
          <div class="settings-grid">
            <label class="field">Prefetch before end
              <input type="number" bind:value={streaming.prefetchBeforeEndMinutes} min="0" />
              <span class="field-hint">minutes</span>
            </label>
            <label class="field">Prefetch count
              <input type="number" bind:value={streaming.prefetchCount} min="1" />
            </label>
            <label class="field">Continue overlay
              <input type="number" bind:value={streaming.continueOverlaySeconds} min="3" />
              <span class="field-hint">seconds</span>
            </label>
          </div>
        </section>
        <section class="panel">
          <div class="panel-head">
            <div>
              <h3>Autodownload</h3>
              <p class="muted">Rank picks the winner among sources that already pass quality, size, language, and cache filters.</p>
            </div>
          </div>
          <div class="rule-cards">
          {#each RULE_CARDS as spec (spec.key)}
            {@const rule = streaming[spec.key] || defaultDownloadRule(spec.key === 'series')}
            <section class="rule-card">
              <h3>{spec.title}</h3>
              <p class="muted">{spec.hint}</p>
              <h4>Rank by</h4>
              <div class="seg" role="group" aria-label="Rank">
                {#each RANK_OPTIONS as opt}
                  <button
                    class="rank"
                    class:on={(rule.rank || 'quality') === opt.id}
                    aria-pressed={(rule.rank || 'quality') === opt.id}
                    onclick={() => setRuleField(spec.key, 'rank', opt.id)}
                  >{opt.label}</button>
                {/each}
              </div>
              <h4>Allowed qualities</h4>
              <div class="chips">
                {#each RULE_QUALS as name}
                  <button
                    class="chip"
                    class:on={(rule.preferredQualities || []).includes(name)}
                    onclick={() => toggleRuleChip(spec.key, 'preferredQualities', name)}
                  >{name}</button>
                {/each}
              </div>
              <h4>Preferred audio</h4>
              <div class="chips">
                {#each RULE_LANGS as name}
                  <button
                    class="chip"
                    class:on={(rule.preferredLanguages || []).includes(name)}
                    onclick={() => toggleRuleChip(spec.key, 'preferredLanguages', name)}
                  >{name}</button>
                {/each}
              </div>
              <div class="settings-grid">
                <label class="field">Min size (MB)
                  <input type="number" min="0" value={rule.minSizeMb || 0} oninput={(e) => setRuleField(spec.key, 'minSizeMb', Number(e.target.value) || 0)} />
                </label>
                <label class="field">Max size (MB)
                  <input type="number" min="0" value={rule.maxSizeMb || 0} oninput={(e) => setRuleField(spec.key, 'maxSizeMb', Number(e.target.value) || 0)} />
                </label>
              </div>
              <div class="toggle-list compact">
                <label class="check"><input type="checkbox" checked={!!rule.requireLanguage} onchange={(e) => setRuleField(spec.key, 'requireLanguage', e.target.checked)} /> Require a preferred language</label>
                <label class="check"><input type="checkbox" checked={!!rule.requireCached} onchange={(e) => setRuleField(spec.key, 'requireCached', e.target.checked)} /> Cached on Real-Debrid only</label>
                <label class="check"><input type="checkbox" checked={rule.allowWeb !== false} onchange={(e) => setRuleField(spec.key, 'allowWeb', e.target.checked)} /> Allow web hosts</label>
                <label class="check"><input type="checkbox" checked={!!rule.preferRemux} onchange={(e) => setRuleField(spec.key, 'preferRemux', e.target.checked)} /> Prefer remux</label>
                <label class="check"><input type="checkbox" checked={!!rule.preferHdr} onchange={(e) => setRuleField(spec.key, 'preferHdr', e.target.checked)} /> Prefer HDR / Dolby Vision</label>
                <label class="check"><input type="checkbox" checked={!!rule.preferAtmos} onchange={(e) => setRuleField(spec.key, 'preferAtmos', e.target.checked)} /> Prefer Atmos / TrueHD</label>
                {#if spec.key === 'series'}
                  <label class="check"><input type="checkbox" checked={!!rule.preferSingleEpisode} onchange={(e) => setRuleField(spec.key, 'preferSingleEpisode', e.target.checked)} /> Prefer a single episode over a pack</label>
                  <label class="check"><input type="checkbox" checked={!!rule.allowSeasonPacks} onchange={(e) => setRuleField(spec.key, 'allowSeasonPacks', e.target.checked)} /> Allow season / series packs</label>
                {/if}
              </div>
            </section>
          {/each}
          </div>
        </section>
        <section class="panel">
          <div class="panel-head">
            <div>
              <h3>Torrentio providers</h3>
              <p class="muted">Indexers used when searching torrents.</p>
            </div>
          </div>
          <div class="chips">
            {#each PROVIDERS as name}
              <button class="chip" class:on={(streaming.torrentioProviders || []).includes(name)} onclick={() => toggleChip('torrentioProviders', name)}>{name}</button>
            {/each}
          </div>
          <h4 class="subhead">Exclude qualities</h4>
          <div class="chips">
            {#each QUALITIES as name}
              <button class="chip" class:on={(streaming.excludeQualities || []).includes(name)} onclick={() => toggleChip('excludeQualities', name)}>{name}</button>
            {/each}
          </div>
        </section>
        <section class="panel">
          <div class="panel-head">
            <div>
              <h3>Real-Debrid</h3>
              <p class="muted">
                {#if streaming.realDebridConfigured}
                  Connected · {streaming.realDebridTokenMasked}
                {:else}
                  Not configured — set <code>REALDEBRID_API_TOKEN</code> or paste a token.
                {/if}
              </p>
            </div>
            {#if streaming.realDebridConfigured}<span class="pill ready">connected</span>{/if}
          </div>
          <div class="composer">
            <input bind:value={rdToken} placeholder="Paste a Real-Debrid API token" type="password" />
          </div>
        </section>
        {#if streamError}<p class="error">{streamError}</p>{/if}
      {:else if streamError}
        <div class="empty">
          <strong>Couldn’t load streaming settings</strong>
          <p>{streamError}</p>
          <div class="toolbar">
            <button class="ghost" onclick={refreshStreaming}>Retry</button>
          </div>
        </div>
      {:else}
        <p class="muted">Loading streaming settings…</p>
      {/if}
    {/if}
  </main>
  </div>
</div>

{#if (page === 'streaming' && streamDirty) || (page === 'subtitles' && subDirty)}
  <div class="save-bar">
    <div>
      <strong>Unsaved changes</strong>
      <span>Leave this page and they will be lost.</span>
    </div>
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
