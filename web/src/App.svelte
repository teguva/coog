<script>
  import Hls from 'hls.js';
  import {
    api,
    getToken,
    setToken,
    mediaArtUrl,
    streamUrl,
    headline,
    trailerMediaId,
    trailerUrl,
    waitForTrailer,
    canPlayTrailer,
    clientCapabilities,
    isHlsSession,
  } from './lib/api.js';

  let tokenDraft = $state(getToken());
  let me = $state(null);
  let authError = $state('');
  let page = $state('home'); // home | search | title | settings | player
  let loading = $state(false);
  let error = $state('');
  let settingsMsg = $state('');
  let settingsBusy = $state(false);

  let home = $state({ trendingMovies: [], trendingSeries: [], forYou: [], coldStart: true });
  let cont = $state([]);
  let query = $state('');
  let searchItems = $state([]);
  let title = $state(null);
  let streams = $state([]);
  let streamError = $state('');
  let session = $state(null);
  let playerTitle = $state('');

  let heroTrailerSrc = $state('');
  let heroTrailerReady = $state(false);
  let heroMuted = $state(true);
  let heroVideoEl = $state(null);
  let playerVideoEl = $state(null);
  let playerHls = null;

  const canOps = $derived(!!me?.ops);

  const featured = $derived(
    cont[0] || home.forYou?.[0] || home.trendingMovies?.[0] || home.trendingSeries?.[0] || null,
  );
  const featuredResume = $derived(!!cont[0] && featured && cont[0] === featured);
  const featuredKey = $derived(featured ? `${featured.id || ''}:${featured.imdbId || ''}` : '');

  const preferredStream = $derived(
    streams.find((s) => s.inLibrary || s.mediaId) || streams.find((s) => s.cached) || streams[0] || null,
  );

  function titleKind(item) {
    const k = String(item?.kind || '').toLowerCase();
    if (k === 'series' || k === 'episode' || k === 'tv') return 'series';
    return 'movie';
  }

  function tmdbIdOf(item) {
    const n = Number(item?.tmdbId || 0);
    if (n > 0) return n;
    const id = String(item?.id || '');
    const m = id.match(/^catalog:tmdb:(\d+)$/i);
    return m ? Number(m[1]) : 0;
  }

  function plotText(item) {
    return String(item?.plot || item?.overview || '').trim();
  }

  function streamTone(stream) {
    if (stream?.inLibrary || stream?.mediaId) return 'local';
    if (stream?.cached) return 'cached';
    return '';
  }

  function streamMeta(stream) {
    return [
      stream.source || stream.kind,
      stream.quality,
      stream.sizeLabel,
      stream.cached ? 'RD+' : '',
      stream.inLibrary ? 'local' : '',
    ]
      .filter(Boolean)
      .join(' · ');
  }

  async function refreshMe() {
    authError = '';
    try {
      me = await api('/api/v1/me');
    } catch (e) {
      me = null;
      if (e.status === 401) authError = 'Unauthorized — check your token';
      else authError = String(e.message || e);
      throw e;
    }
  }

  async function signIn() {
    setToken(tokenDraft.trim());
    loading = true;
    try {
      await refreshMe();
      await loadHome();
      page = 'home';
    } catch {
      setToken('');
    } finally {
      loading = false;
    }
  }

  async function saveToken() {
    settingsBusy = true;
    settingsMsg = '';
    try {
      setToken(tokenDraft.trim());
      await refreshMe();
      settingsMsg = 'Token saved';
    } catch (e) {
      settingsMsg = String(e.message || e);
    } finally {
      settingsBusy = false;
    }
  }

  function signOut() {
    setToken('');
    tokenDraft = '';
    me = null;
    page = 'home';
  }

  function go(next) {
    page = next;
    if (next === 'home') loadHome();
  }

  async function loadHome() {
    loading = true;
    error = '';
    try {
      const [h, c] = await Promise.all([
        api('/api/v1/catalog/home'),
        api('/api/v1/catalog/continue').catch(() => ({ items: [] })),
      ]);
      home = {
        trendingMovies: h.trendingMovies || [],
        trendingSeries: h.trendingSeries || [],
        forYou: h.forYou || [],
        coldStart: !!h.coldStart,
      };
      cont = c.items || c.continueWatching || [];
    } catch (e) {
      error = String(e.message || e);
    } finally {
      loading = false;
    }
  }

  async function runSearch() {
    const q = query.trim();
    if (!q) {
      searchItems = [];
      return;
    }
    loading = true;
    error = '';
    try {
      const res = await api(`/api/v1/catalog/search?q=${encodeURIComponent(q)}`);
      if (Array.isArray(res.items) && res.items.length) {
        searchItems = res.items;
      } else {
        searchItems = [...(res.movies || []), ...(res.series || [])];
      }
    } catch (e) {
      error = String(e.message || e);
    } finally {
      loading = false;
    }
  }

  async function openTitle(item) {
    title = item;
    streams = [];
    streamError = '';
    page = 'title';
    loading = true;
    try {
      let next = { ...item };
      let imdb = String(next.imdbId || '').trim();
      const kind = titleKind(next);
      if (!imdb) {
        const tmdbId = tmdbIdOf(next);
        if (tmdbId > 0) {
          const detail = await api(`/api/v1/catalog/tmdb/${encodeURIComponent(kind)}/${tmdbId}`);
          next = { ...next, ...(detail.item || detail) };
          imdb = String(next.imdbId || '').trim();
        }
      }
      if (imdb) {
        const detail = await api(`/api/v1/catalog/title/${encodeURIComponent(imdb)}?kind=${encodeURIComponent(kind)}`);
        next = { ...next, ...(detail.item || detail) };
      }
      title = next;
      await loadStreams(title);
    } catch (e) {
      streamError = String(e.message || e);
    } finally {
      loading = false;
    }
  }

  async function loadStreams(item) {
    streamError = '';
    const imdb = String(item?.imdbId || '').trim();
    if (!imdb) {
      if (item?.id && !String(item.id).startsWith('catalog:')) {
        streams = [{
          title: 'Library file',
          source: 'local',
          kind: 'local',
          mediaId: item.id,
          inLibrary: true,
        }];
        return;
      }
      streamError = 'No IMDB id for sources';
      return;
    }
    const params = new URLSearchParams({
      imdb,
      kind: titleKind(item),
    });
    if (item.season > 0) params.set('season', String(item.season));
    if (item.episode > 0) params.set('episode', String(item.episode));
    if (item.title) params.set('title', item.title);
    if (item.year > 0) params.set('year', String(item.year));
    const res = await api(`/api/v1/catalog/streams?${params}`);
    streams = res.items || [];
    if (!streams.length) streamError = 'No sources found';
  }

  async function playStream(stream) {
    loading = true;
    streamError = '';
    try {
      const body = {};
      if (stream.mediaId) body.mediaId = stream.mediaId;
      if (stream.url) body.url = stream.url;
      if (stream.infoHash) body.infoHash = stream.infoHash;
      if (stream.magnet) body.magnet = stream.magnet;
      if (title?.imdbId) body.imdbId = title.imdbId;
      if (title?.kind) body.kind = title.kind === 'episode' ? 'series' : title.kind;
      if (title?.season > 0) body.season = title.season;
      if (title?.episode > 0) body.episode = title.episode;
      if (title?.title) body.title = title.title;
      if (stream.quality) body.quality = stream.quality;
      body.clientCapabilities = clientCapabilities();
      session = await api('/api/v1/playback/sessions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (session.error) throw new Error(session.error);
      playerTitle = headline(title) || stream.title || 'Playback';
      page = 'player';
    } catch (e) {
      streamError = String(e.message || e);
    } finally {
      loading = false;
    }
  }

  function destroyPlayerHls() {
    if (playerHls) {
      try {
        playerHls.destroy();
      } catch {
        /* ignore */
      }
      playerHls = null;
    }
  }

  function closePlayer() {
    destroyPlayerHls();
    page = title ? 'title' : 'home';
    session = null;
  }

  async function playContinue(item) {
    title = item;
    await openTitle(item);
    if (item.mediaId || (item.id && !String(item.id).startsWith('catalog:'))) {
      await playStream({ mediaId: item.mediaId || item.id, title: item.title, source: 'local' });
    }
  }

  async function heroAction() {
    if (!featured) return;
    if (featuredResume) await playContinue(featured);
    else await openTitle(featured);
  }

  function clearHeroTrailer() {
    heroTrailerSrc = '';
    heroTrailerReady = false;
    if (heroVideoEl) {
      try {
        heroVideoEl.pause();
        heroVideoEl.removeAttribute('src');
        heroVideoEl.load();
      } catch {
        /* ignore */
      }
    }
  }

  $effect(() => {
    // Open installs (no token) auto-enter; draft-only waits for Continue.
    if (!getToken() && tokenDraft.trim()) return;
    refreshMe()
      .then(() => loadHome())
      .catch(() => {});
  });

  // Billboard trailer: backdrop first, then muted looping video when still on home.
  $effect(() => {
    const onHome = page === 'home';
    const item = featured;
    const key = featuredKey;
    if (!onHome || !item || !canPlayTrailer(item)) {
      clearHeroTrailer();
      return;
    }
    let cancelled = false;
    heroTrailerReady = false;
    heroTrailerSrc = '';
    const id = trailerMediaId(item);
    const timer = setTimeout(async () => {
      if (cancelled) return;
      const ok = await waitForTrailer(id);
      if (cancelled || !ok) return;
      heroTrailerSrc = trailerUrl(id);
    }, 1000);
    const onVis = () => {
      if (document.hidden) {
        heroVideoEl?.pause();
      } else if (heroTrailerSrc && heroVideoEl) {
        heroVideoEl.muted = heroMuted;
        heroVideoEl.play().catch(() => {});
      }
    };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      cancelled = true;
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVis);
      clearHeroTrailer();
      void key;
    };
  });

  $effect(() => {
    if (!heroVideoEl || !heroTrailerSrc) return;
    heroVideoEl.muted = heroMuted;
    heroVideoEl.play().catch(() => {});
  });

  $effect(() => {
    const el = playerVideoEl;
    const s = session;
    const onPlayer = page === 'player';
    destroyPlayerHls();
    if (!onPlayer || !el || !s) return;
    const url = streamUrl(s);
    if (!url) return;
    if (isHlsSession(s)) {
      if (el.canPlayType('application/vnd.apple.mpegurl')) {
        el.src = url;
      } else if (Hls.isSupported()) {
        const hls = new Hls({ enableWorker: true });
        playerHls = hls;
        hls.loadSource(url);
        hls.attachMedia(el);
      } else {
        el.src = url;
      }
    } else {
      el.src = url;
    }
    el.play().catch(() => {});
    return () => destroyPlayerHls();
  });
</script>

{#if !me}
  <div class="app login-shell">
    <main class="login-main">
      <form class="login" onsubmit={(e) => { e.preventDefault(); signIn(); }}>
        <div class="login-brand">Coog</div>
        <p class="login-tag">Watch from your phone, tablet, or laptop.</p>
        <label class="field">
          <span>Access token</span>
          <input
            bind:value={tokenDraft}
            type="password"
            autocomplete="current-password"
            placeholder="Leave blank if the server is open"
          />
        </label>
        {#if authError}<p class="error">{authError}</p>{/if}
        <button class="primary" type="submit" disabled={loading}>
          {loading ? 'Connecting…' : 'Continue'}
        </button>
        <p class="hint">Viewer token for watch-only. Ops/admin lives at the server root.</p>
      </form>
    </main>
  </div>
{:else}
  <div class="app" class:player-mode={page === 'player'}>
    {#if page !== 'player'}
      <header class="top">
        <button class="brand" type="button" onclick={() => go('home')}>Coog</button>
        <nav class="tabs desktop-tabs" aria-label="Primary">
          <button type="button" class:on={page === 'home'} onclick={() => go('home')}>Home</button>
          <button type="button" class:on={page === 'search'} onclick={() => go('search')}>Search</button>
          <button type="button" class:on={page === 'settings'} onclick={() => go('settings')}>Settings</button>
        </nav>
        <div class="spacer"></div>
        <span class="pill" class:ops={canOps}>{canOps ? 'ops' : 'viewer'}</span>
      </header>
    {/if}

    <main class="stage" class:flush={page === 'home' || page === 'title'}>
      {#if error && page !== 'title'}<p class="error banner-error">{error}</p>{/if}

      {#if page === 'home'}
        <div class="home page-enter">
          {#if featured}
            <section class="feature-hero">
              <div class="feature-art">
                {#if mediaArtUrl(featured, 'backdrop') || mediaArtUrl(featured)}
                  <img
                    src={mediaArtUrl(featured, 'backdrop') || mediaArtUrl(featured)}
                    alt=""
                    class="feature-bg"
                    class:dim={heroTrailerReady}
                  />
                {/if}
                {#if heroTrailerSrc}
                  <video
                    class="feature-trailer"
                    class:on={heroTrailerReady}
                    bind:this={heroVideoEl}
                    src={heroTrailerSrc}
                    muted={heroMuted}
                    loop
                    playsinline
                    autoplay
                    preload="auto"
                    oncanplay={() => {
                      heroTrailerReady = true;
                    }}
                    onerror={() => {
                      heroTrailerReady = false;
                      heroTrailerSrc = '';
                    }}
                  ></video>
                {/if}
                <div class="feature-scrim"></div>
              </div>
              {#if heroTrailerReady}
                <button
                  class="feature-mute"
                  type="button"
                  aria-label={heroMuted ? 'Unmute trailer' : 'Mute trailer'}
                  onclick={() => {
                    heroMuted = !heroMuted;
                    if (heroVideoEl) heroVideoEl.muted = heroMuted;
                  }}
                >
                  {heroMuted ? 'Muted' : 'Sound'}
                </button>
              {/if}
              <div class="feature-copy">
                {#if mediaArtUrl(featured, 'logo')}
                  <img class="feature-logo" src={mediaArtUrl(featured, 'logo')} alt={headline(featured)} />
                {:else}
                  <h1 class="feature-title">{headline(featured)}</h1>
                {/if}
                <p class="feature-meta">
                  {[featured.year, featured.kind, featuredResume ? 'Continue' : ''].filter(Boolean).join(' · ')}
                </p>
                {#if plotText(featured)}
                  <p class="feature-plot">{plotText(featured)}</p>
                {/if}
                <div class="row">
                  <button class="primary" type="button" disabled={loading} onclick={heroAction}>
                    {featuredResume ? 'Resume' : 'Open'}
                  </button>
                  <button class="ghost" type="button" disabled={loading} onclick={() => openTitle(featured)}>
                    Details
                  </button>
                </div>
              </div>
            </section>
          {:else if loading}
            <div class="empty-hero"><p class="muted">Loading catalog…</p></div>
          {:else}
            <div class="empty-hero">
              <p class="empty-title">Nothing to show yet</p>
              <p class="muted">Trending appears once the server can reach TMDB.</p>
            </div>
          {/if}

          <div class="shelves">
            {#if cont.length}
              <section class="shelf">
                <div class="shelf-head"><h2>Continue</h2></div>
                <div class="rail">
                  {#each cont as item}
                    <button class="card" type="button" onclick={() => playContinue(item)}>
                      <div class="poster">
                        {#if mediaArtUrl(item)}<img src={mediaArtUrl(item)} alt="" loading="lazy" />{/if}
                      </div>
                      <strong>{headline(item)}</strong>
                      <span class="sub">Resume</span>
                    </button>
                  {/each}
                </div>
              </section>
            {/if}

            {#if home.forYou?.length}
              <section class="shelf">
                <div class="shelf-head"><h2>For you</h2></div>
                <div class="rail">
                  {#each home.forYou as item}
                    <button class="card" type="button" onclick={() => openTitle(item)}>
                      <div class="poster">
                        {#if mediaArtUrl(item)}<img src={mediaArtUrl(item)} alt="" loading="lazy" />{/if}
                      </div>
                      <strong>{item.title}</strong>
                      <span class="sub">{item.year || ''}</span>
                    </button>
                  {/each}
                </div>
              </section>
            {/if}

            <section class="shelf">
              <div class="shelf-head"><h2>Movies</h2></div>
              <div class="rail">
                {#each home.trendingMovies as item}
                  <button class="card" type="button" onclick={() => openTitle(item)}>
                    <div class="poster">
                      {#if mediaArtUrl(item)}<img src={mediaArtUrl(item)} alt="" loading="lazy" />{/if}
                    </div>
                    <strong>{item.title}</strong>
                    <span class="sub">{item.year || ''}</span>
                  </button>
                {:else}
                  <p class="empty-inline">{loading ? 'Loading…' : 'No movies yet'}</p>
                {/each}
              </div>
            </section>

            <section class="shelf">
              <div class="shelf-head"><h2>Series</h2></div>
              <div class="rail">
                {#each home.trendingSeries as item}
                  <button class="card" type="button" onclick={() => openTitle(item)}>
                    <div class="poster">
                      {#if mediaArtUrl(item)}<img src={mediaArtUrl(item)} alt="" loading="lazy" />{/if}
                    </div>
                    <strong>{item.title}</strong>
                    <span class="sub">{item.year || ''}</span>
                  </button>
                {:else}
                  <p class="empty-inline">{loading ? 'Loading…' : 'No series yet'}</p>
                {/each}
              </div>
            </section>
          </div>
        </div>
      {/if}

      {#if page === 'search'}
        <div class="page page-enter search-page">
          <h1 class="screen-title">Search</h1>
          <form class="search-bar" onsubmit={(e) => { e.preventDefault(); runSearch(); }}>
            <input bind:value={query} placeholder="Title or IMDB id" />
            <button class="primary" type="submit" disabled={loading}>Search</button>
          </form>
          <div class="poster-grid">
            {#each searchItems as item}
              <button class="card" type="button" onclick={() => openTitle(item)}>
                <div class="poster">
                  {#if mediaArtUrl(item)}<img src={mediaArtUrl(item)} alt="" loading="lazy" />{/if}
                </div>
                <strong>{item.title}</strong>
                <span class="sub">{item.year || item.kind || ''}</span>
              </button>
            {:else}
              <p class="empty">{query.trim() ? (loading ? 'Searching…' : 'No results') : 'Type a title to search'}</p>
            {/each}
          </div>
        </div>
      {/if}

      {#if page === 'title' && title}
        <div class="title-page page-enter">
          <section class="title-hero">
            <div class="title-art">
              {#if mediaArtUrl(title, 'backdrop') || mediaArtUrl(title)}
                <img
                  src={mediaArtUrl(title, 'backdrop') || mediaArtUrl(title)}
                  alt=""
                  class="title-bg"
                />
              {/if}
              <div class="title-scrim"></div>
            </div>
            <div class="title-body">
              <button class="ghost back" type="button" onclick={() => go('home')}>Back</button>
              <div class="title-lockup">
                <div class="title-poster">
                  {#if mediaArtUrl(title)}<img src={mediaArtUrl(title)} alt="" />{/if}
                </div>
                <div class="title-copy">
                  {#if mediaArtUrl(title, 'logo')}
                    <img class="title-logo" src={mediaArtUrl(title, 'logo')} alt={headline(title)} />
                  {:else}
                    <h1>{headline(title)}</h1>
                  {/if}
                  <div class="chips">
                    {#if title.year}<span class="chip">{title.year}</span>{/if}
                    {#if title.kind}<span class="chip">{title.kind}</span>{/if}
                    {#if title.imdbId}<span class="chip">{title.imdbId}</span>{/if}
                    {#if title.inLibrary}<span class="chip local">In library</span>{/if}
                  </div>
                  {#if plotText(title)}
                    <p class="title-plot">{plotText(title)}</p>
                  {/if}
                  <div class="row">
                    {#if preferredStream}
                      <button class="primary" type="button" disabled={loading} onclick={() => playStream(preferredStream)}>
                        Play
                      </button>
                    {/if}
                    <button class="ghost" type="button" disabled={loading} onclick={() => loadStreams(title)}>
                      Refresh sources
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </section>

          <section class="sources page">
            <h2 class="shelf-head-inline">Sources</h2>
            {#if streamError}<p class="error">{streamError}</p>{/if}
            {#each streams as stream}
              <button
                class="stream"
                class:local={streamTone(stream) === 'local'}
                class:cached={streamTone(stream) === 'cached'}
                type="button"
                disabled={loading}
                onclick={() => playStream(stream)}
              >
                <strong>{stream.title || stream.releaseTitle || stream.source || 'Source'}</strong>
                <span class="meta">{streamMeta(stream)}</span>
              </button>
            {:else}
              <p class="muted">{loading ? 'Looking up sources…' : 'No sources'}</p>
            {/each}
          </section>
        </div>
      {/if}

      {#if page === 'settings'}
        <div class="page page-enter settings-page">
          <h1 class="screen-title">Settings</h1>
          <p class="muted lead">App preferences for this device. Server OPS stays on the admin root.</p>

          <div class="panel">
            <h3>Account</h3>
            <p class="muted">Signed in as <span class="pill" class:ops={canOps}>{canOps ? 'ops' : 'viewer'}</span></p>
            <label class="field">
              <span>Access token</span>
              <input
                bind:value={tokenDraft}
                type="password"
                autocomplete="current-password"
                placeholder="Leave blank if the server is open"
              />
            </label>
            {#if settingsMsg}
              <p class={settingsMsg.includes('saved') ? 'muted' : 'error'}>{settingsMsg}</p>
            {/if}
            <div class="row">
              <button class="primary" type="button" disabled={settingsBusy} onclick={saveToken}>Save token</button>
              <button class="ghost" type="button" onclick={signOut}>Sign out</button>
            </div>
          </div>

          {#if canOps}
            <div class="panel">
              <h3>OPS admin</h3>
              <p class="muted">Streaming, library, subtitles, match, and Maize live in the desktop OPS UI.</p>
              <a class="primary link-btn" href="/" target="_blank" rel="noopener">Open OPS admin</a>
            </div>
          {/if}

          <div class="panel">
            <h3>About</h3>
            <p class="muted">Coog Watch · server {me.version || '—'}</p>
          </div>
        </div>
      {/if}
    </main>

    {#if page === 'player' && session}
      <div class="player-shell">
        <video bind:this={playerVideoEl} controls autoplay playsinline>
          <track kind="captions" />
        </video>
        <div class="player-bar">
          <div class="title">{playerTitle}</div>
          <button class="ghost" type="button" onclick={closePlayer}>
            Close
          </button>
        </div>
      </div>
    {/if}

    {#if page !== 'player'}
      <nav class="nav mobile-nav" aria-label="Primary">
        <button type="button" class:on={page === 'home'} onclick={() => go('home')}>Home</button>
        <button type="button" class:on={page === 'search'} onclick={() => go('search')}>Search</button>
        <button type="button" class:on={page === 'settings'} onclick={() => go('settings')}>Settings</button>
      </nav>
    {/if}
  </div>
{/if}
