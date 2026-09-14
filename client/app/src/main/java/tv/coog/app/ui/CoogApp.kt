package tv.coog.app.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import tv.coog.app.data.ApiException
import tv.coog.app.data.CoogApi
import tv.coog.app.data.InteractiveDevice
import tv.coog.app.data.InteractiveEngineState
import tv.coog.app.data.JobItem
import tv.coog.app.data.MediaItem
import tv.coog.app.data.PlaybackSession
import tv.coog.app.data.PersonSummary
import tv.coog.app.data.ServerStats
import tv.coog.app.data.SettingsRepository
import tv.coog.app.data.StreamCandidate
import tv.coog.app.data.StreamingSettings
import tv.coog.app.update.AppUpdater
import tv.coog.app.ui.theme.CoogType

private sealed interface Screen {
    data object Browse : Screen
    data class Movie(val item: MediaItem) : Screen
    data class Show(val show: ShowRow) : Screen
    data class Folder(val folder: FolderRow) : Screen
    data class Streams(val item: MediaItem) : Screen
    data class Person(val person: PersonSummary) : Screen
    data class Actor(val slug: String) : Screen
    data class Player(
        val session: PlaybackSession?,
        val title: String,
        val item: MediaItem? = null,
        val nextItem: MediaItem? = null,
        val previousItem: MediaItem? = null,
    ) : Screen
}

@Composable
fun CoogApp() {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context.applicationContext) }
    val serverUrl by settings.serverUrl.collectAsState(initial = "")
    val token by settings.token.collectAsState(initial = "")
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Browse)) }
    var tab by remember { mutableStateOf(BrowseTab.Home) }
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var jobs by remember { mutableStateOf<List<JobItem>>(emptyList()) }
    var trendingMovies by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var trendingSeries by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var continueWatching by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var forYou by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var tasteColdStart by remember { mutableStateOf(false) }
    var streaming by remember { mutableStateOf(StreamingSettings()) }
    var serverStats by remember { mutableStateOf<ServerStats?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var playError by remember { mutableStateOf<String?>(null) }
    var queueMessage by remember { mutableStateOf<String?>(null) }
    var loadingShowSeason by remember { mutableStateOf<Int?>(null) }
    var downloadNotice by remember { mutableStateOf<Pair<String, String>?>(null) }
    var adultMode by remember { mutableStateOf(false) }
    var adultSession by remember { mutableStateOf("") }
    var adultContinue by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var adultRecent by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var adultItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var adultIdleMinutes by remember { mutableStateOf(20) }
    var showAdultPin by remember { mutableStateOf(false) }
    var showAdultExitConfirm by remember { mutableStateOf(false) }
    var enterRailRequest by remember { mutableIntStateOf(0) }
    var adultPinError by remember { mutableStateOf<String?>(null) }
    var adultPinBusy by remember { mutableStateOf(false) }
    var adultLoading by remember { mutableStateOf(false) }
    var adultError by remember { mutableStateOf<String?>(null) }
    var adultActivityNonce by remember { mutableIntStateOf(0) }
    var connectedDevices by remember { mutableStateOf<List<InteractiveDevice>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val updater = remember { AppUpdater(context.applicationContext) }
    val updateState by updater.state.collectAsState()
    val current = stack.last()

    fun push(screen: Screen) {
        stack = stack + screen
    }

    fun replaceTop(screen: Screen) {
        stack = if (stack.isEmpty()) listOf(screen) else stack.dropLast(1) + screen
    }

    fun exitApp() {
        (context as? Activity)?.finish()
    }

    fun lockAdult() {
        val session = adultSession
        adultMode = false
        adultSession = ""
        adultContinue = emptyList()
        adultRecent = emptyList()
        adultItems = emptyList()
        connectedDevices = emptyList()
        showAdultPin = false
        adultPinError = null
        stack = listOf(Screen.Browse)
        tab = BrowseTab.Home
        if (session.isNotBlank() && serverUrl.isNotBlank()) {
            scope.launch {
                runCatching { CoogApi(serverUrl, token, session).maizeLock() }
            }
        }
    }

    fun enterAdult(session: String, idleMinutes: Int) {
        adultSession = session
        adultIdleMinutes = idleMinutes.coerceAtLeast(5)
        adultMode = true
        showAdultPin = false
        adultPinError = null
        adultActivityNonce++
        stack = listOf(Screen.Browse)
        tab = BrowseTab.Home
        scope.launch {
            adultLoading = true
            adultError = null
            try {
                val home = CoogApi(serverUrl, token, session).maizeHome()
                adultContinue = home.continueWatching
                adultRecent = home.recentlyAdded
                adultItems = home.library
            } catch (e: Exception) {
                adultError = e.message ?: "Could not load Maize"
            } finally {
                adultLoading = false
            }
        }
    }

    fun pop() {
        playError = null
        if (stack.size > 1) {
            stack = stack.dropLast(1)
            return
        }
        if (adultMode) {
            showAdultExitConfirm = false
            if (tab != BrowseTab.Home) {
                tab = BrowseTab.Home
                return
            }
            // Content-focused Back moves to the main menu; exit only from the rail + confirm.
            enterRailRequest++
            return
        }
        if (tab != BrowseTab.Home) {
            tab = BrowseTab.Home
            return
        }
        // At browse Home with nothing left to pop — leave the app (TV Back).
        exitApp()
    }

    LaunchedEffect(Unit) {
        updater.check()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, updater, adultMode) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { updater.onAppResumed() }
            }
            if (event == Lifecycle.Event.ON_PAUSE && adultMode) {
                lockAdult()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(adultMode, adultIdleMinutes, adultActivityNonce) {
        if (!adultMode) return@LaunchedEffect
        delay(adultIdleMinutes * 60_000L)
        lockAdult()
    }

    LaunchedEffect(adultMode, serverUrl, token, adultSession) {
        if (!adultMode || serverUrl.isBlank() || adultSession.isBlank()) {
            connectedDevices = emptyList()
            return@LaunchedEffect
        }
        while (true) {
            val engine = runCatching {
                CoogApi(serverUrl, token, adultSession).interactiveEngine()
            }.getOrNull()
            connectedDevices = chromeDevices(engine)
            delay(2_000)
        }
    }

    LaunchedEffect(serverUrl, token) {
        if (serverUrl.isBlank()) return@LaunchedEffect
        while (true) {
            delay(12_000)
            serverStats = runCatching { CoogApi(serverUrl, token).serverStats() }.getOrNull()
            streaming = runCatching { CoogApi(serverUrl, token).streamingSettings() }.getOrDefault(streaming)
        }
    }

    LaunchedEffect(serverUrl, token) {
        if (serverUrl.isBlank()) return@LaunchedEffect
        loading = true
        error = null
        try {
            val api = CoogApi(serverUrl, token)
            api.health()
            items = api.library()
            jobs = runCatching { api.jobs() }.getOrDefault(emptyList())
            val home = runCatching { api.catalogHome() }.getOrNull()
            trendingMovies = home?.trendingMovies.orEmpty()
            trendingSeries = home?.trendingSeries.orEmpty()
            forYou = home?.forYou.orEmpty()
            tasteColdStart = home?.coldStart == true
            continueWatching = runCatching { api.catalogContinue() }.getOrDefault(emptyList())
            streaming = runCatching { api.streamingSettings() }.getOrDefault(StreamingSettings())
            serverStats = runCatching { api.serverStats() }.getOrNull()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            items = emptyList()
            trendingMovies = emptyList()
            trendingSeries = emptyList()
            forYou = emptyList()
            continueWatching = emptyList()
            serverStats = null
            error = e.message ?: "Could not reach $serverUrl"
        } finally {
            loading = false
        }
        while (true) {
            delay(2000)
            try {
                val api = CoogApi(serverUrl, token)
                val nextJobs = api.jobs()
                if (nextJobs != jobs) {
                    val removed = jobs.any { old -> nextJobs.none { it.id == old.id } }
                    jobs = nextJobs
                    if (removed) {
                        items = api.library()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun reportClient(
        type: String,
        message: String,
        mediaId: String = "",
        jobId: String = "",
        sessionId: String = "",
    ) {
        val url = serverUrl
        val tok = token
        scope.launch {
            runCatching {
                CoogApi(url, tok).reportEvent(
                    type = type,
                    message = message,
                    mediaId = mediaId,
                    jobId = jobId,
                    sessionId = sessionId,
                )
            }
        }
    }

    fun episodeNeighbors(item: MediaItem): Pair<MediaItem?, MediaItem?> {
        if (item.kind != "episode") return null to null
        val show = stack.filterIsInstance<Screen.Show>().lastOrNull()?.show
        val fromShow = show?.episodes.orEmpty()
        val eps = fromShow.ifEmpty {
            items.filter {
                it.kind == "episode" && (
                    (item.imdbId.isNotBlank() && it.imdbId.equals(item.imdbId, true)) ||
                        (item.showTitle.isNotBlank() && it.showTitle.equals(item.showTitle, true))
                    )
            }
        }.sortedWith(compareBy({ it.season }, { it.episode }))
        if (eps.isEmpty()) return null to null
        val idx = eps.indexOfFirst {
            it.id == item.id ||
                (it.season == item.season && it.episode == item.episode && it.season > 0)
        }
        if (idx < 0) return null to null
        val prev = eps.getOrNull(idx - 1)
        var next = eps.getOrNull(idx + 1)
        // Cross-season Next: if this is the last loaded ep of its season, peek the season index.
        if (next == null && show != null) {
            val seasonNums = show.seasons.map { it.number }.ifEmpty {
                eps.map { it.season }.distinct()
            }.sortedWith(compareBy { if (it <= 0) Int.MAX_VALUE else it })
            val pos = seasonNums.indexOf(item.season)
            val nextSeason = seasonNums.getOrNull(pos + 1)
            if (nextSeason != null) {
                next = eps.firstOrNull { it.season == nextSeason }
                    ?: show.episodes.firstOrNull { it.season == nextSeason }
            }
        }
        return prev to next
    }

    fun playerScreen(session: PlaybackSession?, title: String, item: MediaItem?): Screen.Player {
        val (prev, next) = item?.let { episodeNeighbors(it) } ?: (null to null)
        return Screen.Player(session, title, item, nextItem = next, previousItem = prev)
    }

    suspend fun fetchCatalogShow(show: ShowRow, season: Int? = null): ShowRow? {
        val api = CoogApi(serverUrl, token)
        var seed = show.header ?: show.cover
        if (seed.imdbId.isBlank()) {
            seed = show.episodes.firstOrNull { it.imdbId.isNotBlank() } ?: seed
        }
        if (seed.imdbId.isBlank() && seed.tmdbId != 0) {
            seed = api.catalogTmdb("series", seed.tmdbId)
        }
        if (seed.imdbId.isBlank()) {
            return if (show.episodes.isNotEmpty()) show else null
        }
        val remote = api.catalogShow(seed.imdbId, season)
        val local = (show.episodes + items.filter {
            it.kind == "episode" && it.imdbId.equals(seed.imdbId, ignoreCase = true)
        }).filter { it.diskMediaId().isNotBlank() || it.path.isNotBlank() }
            .distinctBy { "${it.season}:${it.episode}:${it.playableId()}" }
        val seasonNum = remote.season
        val localForSeason = local.filter { it.season == seasonNum }
        val seasonEps = mergeShowEpisodes(remote.episodes, localForSeason)
            .ifEmpty { localForSeason.ifEmpty { remote.episodes } }
        val seasons = remote.seasons.ifEmpty { show.seasons }
        val episodes = mergeSeasonIntoShow(show.episodes, seasonEps, seasonNum)
            .ifEmpty { seasonEps }
        if (episodes.isEmpty() && seasons.isEmpty()) return null
        return ShowRow(
            name = remote.item.title.ifBlank { show.name },
            episodes = episodes,
            header = remote.item,
            seasons = seasons,
        )
    }

    fun replaceShowOnStack(localName: String, full: ShowRow) {
        val last = stack.lastOrNull()
        if (last is Screen.Show && last.show.name.equals(localName, ignoreCase = true)) {
            stack = stack.dropLast(1) + Screen.Show(full)
        } else if (last is Screen.Browse && full.episodes.isNotEmpty()) {
            push(Screen.Show(full))
        }
    }

    /** Load the next season if missing so player Next works across season boundaries. */
    suspend fun prefetchNeighborSeason(item: MediaItem) {
        if (item.kind != "episode") return
        val show = stack.filterIsInstance<Screen.Show>().lastOrNull()?.show ?: return
        if (show.seasons.isEmpty()) return
        val seasonNums = show.seasons.map { it.number }.sortedWith(
            compareBy { if (it <= 0) Int.MAX_VALUE else it },
        )
        val pos = seasonNums.indexOf(item.season)
        val nextSeason = seasonNums.getOrNull(pos + 1) ?: return
        if (show.episodes.any { it.season == nextSeason }) return
        val full = fetchCatalogShow(show, nextSeason) ?: return
        replaceShowOnStack(show.name, full)
    }

    fun playJob(job: JobItem, art: MediaItem? = null) {
        val item = art ?: items.firstOrNull { it.playableId() == job.mediaId || it.id == job.mediaId }
            ?: job.asArtItem()
        val title = job.headline()
        if (stack.lastOrNull() !is Screen.Player) {
            push(playerScreen(null, title, item))
        }
        scope.launch {
            playError = null
            if (!job.ready && job.status != "finished") {
                return@launch
            }
            try {
                // Prefer library media once finished — avoid treating synthetic library:* ids as jobs.
                val session = CoogApi(serverUrl, token).playbackSession(
                    mediaId = job.mediaId,
                    jobId = if (job.mediaId.isNotBlank() &&
                        (job.status == "finished" || job.type == "library")
                    ) {
                        ""
                    } else {
                        job.id
                    },
                )
                if (session.error.isNotBlank() && session.url.isBlank()) {
                    playError = session.error
                    reportClient("session.error", session.error, mediaId = job.mediaId, jobId = job.id)
                    if (stack.lastOrNull() is Screen.Player) pop()
                    return@launch
                }
                replaceTop(playerScreen(session, title, item))
            } catch (e: ApiException) {
                playError = e.message
                reportClient("session.error", e.message ?: "playback failed", mediaId = job.mediaId, jobId = job.id)
                if (stack.lastOrNull() is Screen.Player) pop()
            } catch (e: Exception) {
                playError = e.message
                reportClient("play.error", e.message ?: "playback failed", mediaId = job.mediaId, jobId = job.id)
                if (stack.lastOrNull() is Screen.Player) pop()
            }
        }
    }

    fun playLocal(item: MediaItem) {
        val playerTitle = if (item.kind == "episode") {
            "${item.seriesName()}  ·  ${item.episodeHeadline()}"
        } else {
            item.headline()
        }
        scope.launch {
            playError = null
            if (item.kind == "episode") {
                runCatching { prefetchNeighborSeason(item) }
            }
            if (stack.lastOrNull() !is Screen.Player) {
                push(playerScreen(null, playerTitle, item))
            }
            try {
                val api = CoogApi(serverUrl, token, adultSession)
                val session = api.playbackSession(
                    mediaId = item.diskMediaId(),
                    imdbId = item.imdbId,
                    kind = item.kind,
                    title = item.headline().ifBlank { item.seriesName() },
                    year = item.year,
                    season = item.season,
                    episode = item.episode,
                )
                if (session.error.isNotBlank()) {
                    playError = session.error
                    reportClient("session.error", session.error, mediaId = item.id, jobId = session.jobId, sessionId = session.id)
                    if (stack.lastOrNull() is Screen.Player) pop()
                    return@launch
                }
                replaceTop(playerScreen(session, playerTitle, item))
            } catch (e: ApiException) {
                playError = e.message
                reportClient("session.error", e.message ?: "playback failed", mediaId = item.id)
                if (stack.lastOrNull() is Screen.Player) pop()
            } catch (e: Exception) {
                playError = e.message
                reportClient("play.error", e.message ?: "playback failed", mediaId = item.id)
                if (stack.lastOrNull() is Screen.Player) pop()
            }
        }
    }

    fun playTrailer(item: MediaItem) {
        if (!item.canPlayTrailer()) {
            playError = "No trailer available."
            return
        }
        val mediaId = item.trailerMediaId()
        val url = CoogServer(serverUrl, token, adultSession).trailerUrl(mediaId)
        val title = "${item.headline()}  ·  Trailer"
        push(playerScreen(null, title, item))
        scope.launch {
            playError = null
            val api = CoogApi(serverUrl, token, adultSession)
            val exists = runCatching { api.trailerExists(mediaId) }.getOrDefault(false)
            if (!exists || url.isBlank()) {
                playError = "No trailer available."
                if (stack.lastOrNull() is Screen.Player) pop()
                return@launch
            }
            replaceTop(
                playerScreen(
                    PlaybackSession(
                        id = "trailer:$mediaId",
                        method = "direct",
                        url = url,
                        mediaId = mediaId,
                    ),
                    title,
                    item,
                ),
            )
        }
    }

    fun waitForJob(api: CoogApi, jobId: String, item: MediaItem) {
        scope.launch {
            playError = null
            if (stack.lastOrNull() !is Screen.Player) {
                push(playerScreen(null, item.headline(), item))
            }
            repeat(180) {
                delay(1500)
                val job = runCatching { api.job(jobId) }.getOrNull()
                if (job == null) {
                    // Cancel deletes the job; finish may too after library ingest.
                    val library = runCatching { api.library() }.getOrDefault(items)
                    items = library
                    val local = library.firstOrNull {
                        it.imdbId.isNotBlank() && it.imdbId.equals(item.imdbId, true) &&
                            (item.season <= 0 || it.season == item.season) &&
                            (item.episode <= 0 || it.episode == item.episode)
                    }
                    if (local != null) {
                        playLocal(local)
                    } else {
                        playError = "Download was cancelled"
                        if (stack.lastOrNull() is Screen.Player) pop()
                    }
                    return@launch
                }
                if (job.status == "error" || job.status == "cancelled") {
                    val msg = friendlyPlayError(job.error.ifBlank { "Download failed" })
                    playError = msg
                    reportClient("play.error", msg, mediaId = item.id, jobId = job.id)
                    if (stack.lastOrNull() is Screen.Player) pop()
                    return@launch
                }
                if (job.ready || job.status == "finished") {
                    playError = null
                    playJob(job, item)
                    return@launch
                }
            }
        }
    }


    suspend fun startStreamJob(item: MediaItem, candidate: StreamCandidate, forceNew: Boolean = false) {
        playError = null
        try {
            val api = CoogApi(serverUrl, token)
            val title = item.headline().ifBlank { candidate.title }
            val taggedTitle = if (item.season > 0 && item.episode > 0) {
                "%s S%02dE%02d".format(title, item.season, item.episode)
            } else {
                title
            }
            val releaseTitle = candidate.title.ifBlank { candidate.name }
            val meta = candidate.fileMetaLine()
            val job = when {
                candidate.kind.equals("web", ignoreCase = true) ||
                    candidate.source.equals("web", ignoreCase = true) -> api.enqueueWeb(
                    url = candidate.url,
                    title = taggedTitle.ifBlank { candidate.name },
                    imdbId = item.imdbId,
                    kind = item.kind.ifBlank { "movie" },
                    season = item.season,
                    episode = item.episode,
                    year = item.year,
                    quality = candidate.quality,
                    sizeBytes = candidate.size,
                    sizeLabel = candidate.sizeLabel,
                    pack = candidate.pack,
                    tags = candidate.tags,
                    languages = candidate.languages,
                    releaseTitle = releaseTitle,
                    force = forceNew,
                )
                candidate.cached -> api.enqueueDebrid(
                    imdbId = item.imdbId,
                    infoHash = candidate.infoHash,
                    title = taggedTitle.ifBlank { candidate.name },
                    kind = item.kind.ifBlank { "movie" },
                    season = item.season,
                    episode = item.episode,
                    year = item.year,
                    quality = candidate.quality,
                    sizeBytes = candidate.size,
                    sizeLabel = candidate.sizeLabel,
                    pack = candidate.pack,
                    tags = candidate.tags,
                    languages = candidate.languages,
                    releaseTitle = releaseTitle,
                    force = forceNew,
                )
                else -> api.enqueueTorrent(
                    imdbId = item.imdbId,
                    infoHash = candidate.infoHash,
                    title = taggedTitle.ifBlank { candidate.name },
                    kind = item.kind.ifBlank { "movie" },
                    season = item.season,
                    episode = item.episode,
                    year = item.year,
                    quality = candidate.quality,
                    sizeBytes = candidate.size,
                    sizeLabel = candidate.sizeLabel,
                    pack = candidate.pack,
                    tags = candidate.tags,
                    languages = candidate.languages,
                    releaseTitle = releaseTitle,
                    force = forceNew,
                )
            }
            // Server may return the existing library copy when force=false.
            if (job.mediaId.isNotBlank() && (job.status == "finished" || job.type == "library")) {
                playJob(job, item.copy(inLibrary = true, libraryId = job.mediaId))
                return
            }
            val noticeMeta = job.fileMetaLine().ifBlank { meta }
            downloadNotice = (job.headline().ifBlank { taggedTitle }) to
                listOfNotNull("Downloading", noticeMeta.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (job.ready || job.status == "finished") {
                playJob(job, item)
            } else {
                waitForJob(api, job.id, item)
            }
        } catch (e: Exception) {
            val msg = friendlyPlayError(e.message ?: "Could not start download")
            playError = msg
            reportClient("play.error", msg, mediaId = item.id)
            if (stack.lastOrNull() is Screen.Player) pop()
        }
    }

    fun pickStream(item: MediaItem, candidate: StreamCandidate) {
        if (stack.lastOrNull() !is Screen.Player) {
            push(playerScreen(null, item.headline(), item))
        }
        // Explicit Sources choice — download beside any existing library file.
        scope.launch { startStreamJob(item, candidate, forceNew = true) }
    }

    fun openSources(item: MediaItem) {
        playError = null
        if (item.playBlocked() && !item.isLocal()) {
            playError = "This title is not released yet."
            return
        }
        if (item.imdbId.isBlank()) {
            playError = "No IMDB id for this title, so sources cannot be listed."
            return
        }
        // Always open the picker — never auto-select or play local from Sources.
        push(
            Screen.Streams(
                item.copy(
                    path = "",
                    inLibrary = false,
                    libraryId = "",
                    // Avoid diskMediaId() treating library UUIDs as playable when id is local.
                    id = when {
                        item.id.startsWith("catalog:") -> item.id
                        item.imdbId.isNotBlank() && (item.kind == "episode" || item.season > 0) ->
                            "catalog:${item.imdbId}:${item.season.coerceAtLeast(1)}:${item.episode.coerceAtLeast(1)}"
                        item.imdbId.isNotBlank() -> "catalog:${item.imdbId}"
                        else -> item.id
                    },
                ),
            ),
        )
    }

    fun resolveLibraryItem(item: MediaItem): MediaItem? {
        val diskId = item.diskMediaId()
        val pool = items + adultItems
        if (diskId.isNotBlank()) {
            return pool.firstOrNull { it.id == diskId || it.diskMediaId() == diskId } ?: item
        }
        val imdb = item.imdbId.trim()
        if (imdb.isBlank()) return null
        val wantEpisode = item.kind == "episode" || item.episode > 0
        return pool.firstOrNull { lib ->
            if (!lib.imdbId.equals(imdb, ignoreCase = true)) return@firstOrNull false
            if (wantEpisode) {
                lib.kind == "episode" &&
                    lib.season == item.season.coerceAtLeast(1) &&
                    lib.episode == item.episode
            } else {
                lib.kind == "movie"
            }
        }
    }

    fun playOrPick(item: MediaItem) {
        playError = null
        if (item.playBlocked()) {
            playError = "This title is not released yet."
            return
        }
        resolveLibraryItem(item)?.let { local ->
            playLocal(local)
            return
        }
        if (item.imdbId.isBlank()) {
            playError = "No IMDB id for this title, so sources cannot be listed."
            return
        }
        // Prefer auto-select from server prefs; fall back to Sources when nothing matches.
        if (!streaming.autoSelectSource) {
            push(Screen.Streams(item))
            return
        }
        scope.launch {
            if (item.kind == "episode") {
                runCatching { prefetchNeighborSeason(item) }
            }
            push(playerScreen(null, item.headline(), item))
            try {
                val api = CoogApi(serverUrl, token)
                val res = api.catalogStreams(
                    imdbId = item.imdbId,
                    kind = item.kind.ifBlank { "movie" },
                    season = item.season,
                    episode = item.episode,
                    title = item.headline(),
                    year = item.year,
                )
                val pick = res.pick
                if (pick?.ok == true && pick.candidate != null) {
                    // force=false: server plays existing library file instead of a second download.
                    startStreamJob(item, pick.candidate, forceNew = false)
                } else {
                    if (stack.lastOrNull() is Screen.Player) pop()
                    push(Screen.Streams(item))
                }
            } catch (_: Exception) {
                if (stack.lastOrNull() is Screen.Player) pop()
                push(Screen.Streams(item))
            }
        }
    }

    fun prefetchItem(item: MediaItem) {
        if (item.imdbId.isBlank()) return
        scope.launch {
            runCatching {
                CoogApi(serverUrl, token).prefetchNextEpisode(
                    imdbId = item.imdbId,
                    season = item.season,
                    episode = item.episode,
                    title = item.seriesName().ifBlank { item.headline() },
                    year = item.year,
                )
            }
        }
    }

    fun openShow(show: ShowRow) {
        playError = null
        loadingShowSeason = null
        val localName = show.name
        if (show.episodes.isNotEmpty() || show.seasons.isNotEmpty()) {
            push(Screen.Show(show))
        }
        scope.launch {
            try {
                val full = fetchCatalogShow(show) ?: return@launch
                replaceShowOnStack(localName, full)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (show.episodes.isEmpty()) {
                    playError = e.message
                    reportClient("play.error", e.message ?: "could not load series", mediaId = show.cover.id)
                    push(Screen.Movie(show.cover))
                }
            }
        }
    }

    fun loadShowSeason(show: ShowRow, season: Int) {
        if (show.episodes.any { it.season == season }) return
        loadingShowSeason = season
        scope.launch {
            try {
                val full = fetchCatalogShow(show, season) ?: return@launch
                replaceShowOnStack(show.name, full)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep chips usable; shelf shows empty until retry.
            } finally {
                if (loadingShowSeason == season) loadingShowSeason = null
            }
        }
    }

    fun openTitle(item: MediaItem) {
        if (item.kind == "movie") {
            val localShow = items.showRows().firstOrNull { matchesShowTitle(item.headline(), it.name) }
            if (localShow != null) {
                openShow(localShow)
                return
            }
            push(Screen.Movie(item))
            return
        }
        if (item.kind == "series" || item.kind == "episode") {
            val local = items.showRows().firstOrNull { row ->
                showMatches(row, item)
            }
            openShow(
                ShowRow(
                    name = local?.name ?: item.headline().ifBlank { item.seriesName() },
                    episodes = local?.episodes.orEmpty(),
                    header = when {
                        item.kind == "series" -> item
                        local?.header != null -> local.header
                        else -> item
                    },
                ),
            )
            return
        }
        push(Screen.Movie(item))
    }

    val hasLocalLibrary = remember(items) { items.isNotEmpty() }
    LaunchedEffect(hasLocalLibrary, tab) {
        if (tab == BrowseTab.Folders && !hasLocalLibrary) {
            tab = BrowseTab.Home
        }
    }
    LaunchedEffect(current, serverUrl, token) {
        if (current !is Screen.Browse || serverUrl.isBlank()) return@LaunchedEffect
        continueWatching = runCatching { CoogApi(serverUrl, token).catalogContinue() }.getOrDefault(continueWatching)
    }

    BackHandler(enabled = current !is Screen.Player) { pop() }

    LaunchedEffect(downloadNotice) {
        if (downloadNotice == null) return@LaunchedEffect
        delay(4000)
        downloadNotice = null
    }

    val browseActive = current is Screen.Browse
    CompositionLocalProvider(
        LocalCoogServer provides CoogServer(
            serverUrl,
            token,
            adultSession,
            backdropDisplayMaxFromPref(streaming.preferredBackdropMax),
        ),
        LocalBrowseActive provides browseActive,
    ) {
        // Keep Browse composed under overlays so shelf/card position survives Movie/Player.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent {
                    if (adultMode) adultActivityNonce++
                    false
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusProperties { canFocus = browseActive },
            ) {
                if (adultMode) {
                    AppShell(
                        tab = tab,
                        onTab = { next ->
                            tab = when (next) {
                                BrowseTab.Home, BrowseTab.Folders, BrowseTab.Actors, BrowseTab.Devices, BrowseTab.Settings -> next
                                else -> BrowseTab.Home
                            }
                        },
                        adultMode = true,
                        enterRailRequest = enterRailRequest,
                        connectedDevices = connectedDevices,
                        onRootBack = { showAdultExitConfirm = true },
                        onAdultLock = { lockAdult() },
                    ) {
                        when (tab) {
                            BrowseTab.Folders -> AdultLibraryScreen(
                                library = adultItems,
                                jobs = jobs,
                                loading = adultLoading,
                                error = adultError,
                                onOpen = { openTitle(it) },
                                onLibraryQuery = { filter, sort ->
                                    CoogApi(serverUrl, token, adultSession).maizeLibrary(filter, sort)
                                },
                            )
                            BrowseTab.Actors -> AdultActorsScreen(
                                onOpenActor = { push(Screen.Actor(it)) },
                            )
                            BrowseTab.Devices -> DevicesScreen(
                                serverUrl = serverUrl,
                                token = token,
                                adultSession = adultSession,
                            )
                            BrowseTab.Settings -> SettingsScreen(
                                serverUrl = serverUrl,
                                token = token,
                                update = updateState,
                                health = serverHealthLine(serverStats),
                                streaming = streaming,
                                onStreamingSaved = { streaming = it },
                                onSave = { url, tok ->
                                    scope.launch {
                                        settings.setServerUrl(url)
                                        settings.setToken(tok)
                                        tab = BrowseTab.Home
                                    }
                                },
                                onCheckUpdate = { scope.launch { updater.check() } },
                                onInstallUpdate = { scope.launch { updater.installLatest() } },
                                queueMessage = queueMessage,
                                onQueueDownload = { source ->
                                    scope.launch {
                                        queueMessage = null
                                        try {
                                            CoogApi(serverUrl, token).enqueueJob(source)
                                            queueMessage = null
                                            tab = BrowseTab.Home
                                        } catch (e: Exception) {
                                            queueMessage = e.message ?: "Could not queue download"
                                        }
                                    }
                                },
                            )
                            else -> AdultHomeScreen(
                                continueWatching = adultContinue,
                                recentlyAdded = adultRecent,
                                library = adultItems,
                                jobs = jobs,
                                loading = adultLoading,
                                error = adultError,
                                onOpen = { openTitle(it) },
                                onPlayContinue = { playOrPick(it) },
                                onClearContinue = { item ->
                                    adultContinue = adultContinue.filterNot { other ->
                                        continueSame(other, item)
                                    }
                                    scope.launch {
                                        runCatching {
                                            CoogApi(serverUrl, token, adultSession).clearContinue(item)
                                        }
                                        adultContinue = runCatching {
                                            CoogApi(serverUrl, token, adultSession).maizeHome().continueWatching
                                        }.getOrDefault(adultContinue)
                                    }
                                },
                            )
                        }
                    }
                } else {
                    AppShell(
                        tab = tab,
                        onTab = { tab = it },
                        showFolders = hasLocalLibrary,
                        activeDownloads = jobs.any { it.isActive() },
                        onRootBack = { exitApp() },
                        onAdultUnlockGesture = {
                            showAdultPin = true
                            adultPinError = null
                        },
                    ) {
                    when (tab) {
                        BrowseTab.Settings -> SettingsScreen(
                            serverUrl = serverUrl,
                            token = token,
                            update = updateState,
                            health = serverHealthLine(serverStats),
                            streaming = streaming,
                            onStreamingSaved = { streaming = it },
                            onSave = { url, tok ->
                                scope.launch {
                                    settings.setServerUrl(url)
                                    settings.setToken(tok)
                                    tab = BrowseTab.Home
                                }
                            },
                            onCheckUpdate = { scope.launch { updater.check() } },
                            onInstallUpdate = { scope.launch { updater.installLatest() } },
                            queueMessage = queueMessage,
                            onQueueDownload = { source ->
                                scope.launch {
                                    queueMessage = null
                                    try {
                                        CoogApi(serverUrl, token).enqueueJob(source)
                                        queueMessage = null
                                        tab = BrowseTab.Home
                                    } catch (e: Exception) {
                                        queueMessage = e.message ?: "Could not queue download"
                                    }
                                }
                            },
                        )
                        BrowseTab.Search -> SearchScreen(
                            jobs = jobs,
                            library = items,
                            onOpenTitle = { openTitle(it) },
                            onOpenPerson = { push(Screen.Person(it)) },
                        )
                        BrowseTab.Downloads -> DownloadsScreen(
                            jobs = jobs,
                            library = items,
                            error = playError,
                            onPlayJob = { playJob(it) },
                            onPauseJob = { job ->
                                scope.launch {
                                    runCatching { CoogApi(serverUrl, token).pauseJob(job.id) }
                                        .onSuccess { jobs = CoogApi(serverUrl, token).jobs() }
                                        .onFailure { playError = it.message }
                                }
                            },
                            onResumeJob = { job ->
                                scope.launch {
                                    runCatching { CoogApi(serverUrl, token).retryJob(job.id) }
                                        .onSuccess { jobs = CoogApi(serverUrl, token).jobs() }
                                        .onFailure { playError = it.message }
                                }
                            },
                            onCancelJob = { job ->
                                scope.launch {
                                    runCatching { CoogApi(serverUrl, token).cancelJob(job.id) }
                                        .onSuccess { jobs = CoogApi(serverUrl, token).jobs() }
                                        .onFailure { playError = it.message }
                                }
                            },
                        )
                        BrowseTab.Movies -> CatalogBrowseScreen(
                            kind = "movie",
                            jobs = jobs,
                            library = items,
                            onOpen = { openTitle(it) },
                        )
                        BrowseTab.Series -> CatalogBrowseScreen(
                            kind = "series",
                            jobs = jobs,
                            library = items,
                            onOpen = { openTitle(it) },
                        )
                        else -> HomeScreen(
                            tab = tab,
                            loading = loading,
                            error = error,
                            items = items,
                            jobs = jobs,
                            continueWatching = continueWatching,
                            forYou = forYou,
                            tasteColdStart = tasteColdStart,
                            trendingMovies = trendingMovies,
                            trendingSeries = trendingSeries,
                            onOpenMovie = { openTitle(it) },
                            onOpenFolder = { push(Screen.Folder(it)) },
                            onPlayContinue = { playOrPick(it) },
                            onClearContinue = { item ->
                                continueWatching = continueWatching.filterNot { other ->
                                    continueSame(other, item)
                                }
                                scope.launch {
                                    runCatching { CoogApi(serverUrl, token).clearContinue(item) }
                                    continueWatching = runCatching {
                                        CoogApi(serverUrl, token).catalogContinue()
                                    }.getOrDefault(continueWatching)
                                }
                            },
                            onOpenSettings = { tab = BrowseTab.Settings },
                        )
                    }
                    }
                }
            }
            if (!browseActive) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (val screen = current) {
                        Screen.Browse -> Unit
                        is Screen.Movie -> MovieDetailsScreen(
                            item = screen.item,
                            playError = playError,
                            onBack = { pop() },
                            onPlay = { playOrPick(it) },
                            onSources = { openSources(it) },
                            onTrailer = { playTrailer(it) },
                            onOpenPerson = { person ->
                                if (adultMode && person.tmdbId == 0 && person.name.isNotBlank()) {
                                    push(Screen.Actor(actorSlugify(person.name)))
                                } else {
                                    push(Screen.Person(person))
                                }
                            },
                            onOpenSimilar = { openTitle(it) },
                        )
                        is Screen.Show -> ShowDetailsScreen(
                            show = overlayContinueProgress(
                                overlayShowLibrary(screen.show, items),
                                continueWatching,
                            ),
                            playError = playError,
                            jobs = jobs,
                            loadingSeason = loadingShowSeason,
                            onSeasonSelected = { season -> loadShowSeason(screen.show, season) },
                            onBack = { pop() },
                            onPlay = { playOrPick(it) },
                            onSources = { openSources(it) },
                            onTrailer = { playTrailer(it) },
                            onOpenPerson = { push(Screen.Person(it)) },
                            onOpenSimilar = { openTitle(it) },
                        )
                        is Screen.Folder -> FolderBrowseScreen(
                            folder = screen.folder,
                            playError = playError,
                            onBack = { pop() },
                            onOpen = { push(Screen.Movie(it)) },
                        )
                        is Screen.Streams -> StreamsScreen(
                            item = screen.item,
                            playError = playError,
                            onBack = { pop() },
                            onPick = { pickStream(screen.item, it) },
                        )
                        is Screen.Person -> PersonScreen(
                            person = screen.person,
                            jobs = jobs,
                            library = items,
                            onBack = { pop() },
                            onOpenTitle = { openTitle(it) },
                        )
                        is Screen.Actor -> ActorDetailScreen(
                            slug = screen.slug,
                            onBack = { pop() },
                            onOpenScene = { openTitle(it) },
                            onOpenActor = { replaceTop(Screen.Actor(it)) },
                        )
                        is Screen.Player -> PlayerScreen(
                            session = screen.session,
                            title = screen.title,
                            item = screen.item,
                            nextItem = screen.nextItem,
                            previousItem = screen.previousItem,
                            autoplayNext = streaming.autoplayNextEpisode,
                            prefetchNext = streaming.autoDownloadNextEpisode,
                            prefetchBeforeEndMinutes = streaming.prefetchBeforeEndMinutes,
                            token = token,
                            serverUrl = serverUrl,
                            adultSession = adultSession,
                            onBack = { pop() },
                            onPlayNeighbor = { neighbor ->
                                val current = (stack.lastOrNull() as? Screen.Player)?.item
                                if (stack.lastOrNull() is Screen.Player) pop()
                                // Don't replay the same on-disk file when next is mis-tagged local.
                                val next = if (
                                    current != null &&
                                    neighbor.diskMediaId().isNotBlank() &&
                                    neighbor.diskMediaId() == current.diskMediaId() &&
                                    (neighbor.season != current.season || neighbor.episode != current.episode)
                                ) {
                                    neighbor.copy(path = "", inLibrary = false, libraryId = "", id = "catalog:${neighbor.imdbId}:${neighbor.season}:${neighbor.episode}")
                                } else {
                                    neighbor
                                }
                                playOrPick(next)
                            },
                            onPrefetchNeighbor = { neighbor ->
                                if (streaming.autoDownloadNextEpisode) prefetchItem(neighbor)
                            },
                        )
                    }
                }
            }
            if (showAdultExitConfirm) {
                AdultExitConfirmDialog(
                    onConfirm = {
                        showAdultExitConfirm = false
                        lockAdult()
                    },
                    onDismiss = { showAdultExitConfirm = false },
                )
            }
            if (showAdultPin) {
                AdultPinDialog(
                    error = adultPinError,
                    busy = adultPinBusy,
                    onDismiss = {
                        showAdultPin = false
                        adultPinError = null
                    },
                    onSubmit = { pin ->
                        scope.launch {
                            adultPinBusy = true
                            adultPinError = null
                            try {
                                val res = CoogApi(serverUrl, token).maizeUnlock(pin)
                                enterAdult(res.session, res.idleMinutes)
                            } catch (e: ApiException) {
                                adultPinError = when (e.code) {
                                    401 -> "Incorrect"
                                    429 -> "Too many attempts"
                                    400 -> "PIN not configured"
                                    else -> e.message.ifBlank { "Unlock failed" }
                                }
                            } catch (e: Exception) {
                                adultPinError = e.message ?: "Unlock failed"
                            } finally {
                                adultPinBusy = false
                            }
                        }
                    },
                )
            }
            downloadNotice?.let { (title, detail) ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 28.dp, end = 40.dp),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xE61C1C22))
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                    ) {
                        Text(title, style = CoogType.cardTitle, color = Color.White, maxLines = 1)
                        if (detail.isNotBlank()) {
                            Text(
                                detail,
                                style = CoogType.cardYear,
                                color = Color.White.copy(alpha = 0.75f),
                                maxLines = 2,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun continueSame(a: MediaItem, b: MediaItem): Boolean {
    val imdb = a.imdbId.trim()
    if (imdb.isNotBlank() && imdb.equals(b.imdbId.trim(), ignoreCase = true)) return true
    if (a.tmdbId != 0 && a.tmdbId == b.tmdbId) {
        val aKind = if (a.kind == "episode") "series" else a.kind
        val bKind = if (b.kind == "episode") "series" else b.kind
        if (aKind.equals(bKind, ignoreCase = true)) return true
    }
    val aMedia = a.diskMediaId()
    val bMedia = b.diskMediaId()
    if (aMedia.isNotBlank() && aMedia == bMedia) return true
    return a.id == b.id
}

private fun overlayShowLibrary(show: ShowRow, library: List<MediaItem>): ShowRow {
    val local = library.filter { it.kind == "episode" && showMatches(show, it) }
    if (local.isEmpty()) return show
    val episodes = mergeShowEpisodes(show.episodes, local)
    if (episodes == show.episodes) return show
    return show.copy(episodes = episodes)
}

private fun overlayContinueProgress(show: ShowRow, continueWatching: List<MediaItem>): ShowRow {
    val imdb = show.cover.imdbId.ifBlank { show.header?.imdbId.orEmpty() }
    val hit = continueWatching.firstOrNull { cw ->
        imdb.isNotBlank() && cw.imdbId.equals(imdb, ignoreCase = true) &&
            (cw.season > 0 || cw.episode > 0) && cw.positionMs > 0
    } ?: show.header?.takeIf { it.positionMs > 0 && (it.season > 0 || it.episode > 0) }
    if (hit == null) return show
    val episodes = show.episodes.map { ep ->
        if (ep.season == hit.season && ep.episode == hit.episode) {
            ep.copy(
                positionMs = hit.positionMs,
                durationMs = hit.durationMs.takeIf { it > 0 } ?: ep.durationMs,
            )
        } else {
            ep
        }
    }
    return show.copy(episodes = episodes)
}

private fun showMatches(row: ShowRow, item: MediaItem): Boolean {
    val imdb = item.imdbId.trim()
    if (imdb.isNotBlank()) {
        if (row.cover.imdbId.equals(imdb, ignoreCase = true)) return true
        if (row.episodes.any { it.imdbId.equals(imdb, ignoreCase = true) }) return true
    }
    val name = item.seriesName().ifBlank { item.headline() }
    return name.isNotBlank() && row.name.equals(name, ignoreCase = true)
}

private fun JobItem.asArtItem(): MediaItem {
    val se = seasonEpisode()
    val season = se?.first ?: 0
    val episode = se?.second ?: 0
    val kind = when {
        season > 0 || episode > 0 -> "episode"
        else -> "movie"
    }
    return MediaItem(
        id = mediaId.ifBlank { id },
        kind = kind,
        title = title,
        imdbId = imdbId,
        season = season,
        episode = episode,
        showTitle = if (kind == "episode") {
            title.replace(Regex("""(?i)\s*S\d{1,2}E\d{1,3}\s*"""), " ").trim().trim('-', '·', ' ')
        } else {
            ""
        },
    )
}

/** Successfully connected toys for Maize chrome — never show connecting/offline. */
private fun chromeDevices(engine: InteractiveEngineState?): List<InteractiveDevice> {
    if (engine == null) return emptyList()
    fun key(d: InteractiveDevice) = d.deviceId.ifBlank { d.name }
    fun isConnected(d: InteractiveDevice): Boolean {
        if (d.connected) return true
        return d.status.equals("connected", ignoreCase = true)
    }
    val storedByKey = (engine.trustedDevices + engine.knownDevices + engine.devices)
        .associateBy(::key)
    fun withBattery(d: InteractiveDevice): InteractiveDevice {
        if (d.batterySupported && d.batteryPercent >= 0) return d
        val stored = storedByKey[key(d)] ?: return d
        val pct = when {
            d.batteryPercent >= 0 -> d.batteryPercent
            stored.batteryPercent >= 0 -> stored.batteryPercent
            else -> -1
        }
        return d.copy(
            batterySupported = d.batterySupported || stored.batterySupported || pct >= 0,
            batteryPercent = pct,
        )
    }
    val live = engine.devices.filter(::isConnected).map(::withBattery)
    val connected = if (live.isNotEmpty()) {
        live
    } else {
        (engine.trustedDevices + engine.knownDevices)
            .filter(::isConnected)
            .map(::withBattery)
    }
    return connected
        .distinctBy(::key)
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { key(it) })
}

private fun serverHealthLine(stats: ServerStats?): String? {
    if (stats == null) return null
    val parts = mutableListOf<String>()
    if (stats.worker.stale) parts += "Worker offline"
    when {
        !stats.realDebrid.configured -> parts += "Real-Debrid not configured"
        stats.realDebrid.error.isNotBlank() -> parts += "Real-Debrid: ${stats.realDebrid.error}"
        stats.realDebrid.configured && !stats.realDebrid.premium -> parts += "Real-Debrid not premium"
    }
    val free = stats.disk?.freeBytes ?: 0L
    val total = stats.disk?.totalBytes ?: 0L
    if (total > 0 && free > 0 && free.toDouble() / total.toDouble() < 0.08) {
        parts += "Disk low"
    }
    if (stats.catalogError.isNotBlank()) parts += "Catalog: ${stats.catalogError}"
    return parts.joinToString(" · ").ifBlank { "Server healthy · ${stats.mediaCount} titles" }
}
