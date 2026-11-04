package tv.coog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import tv.coog.app.data.CoogApi
import tv.coog.app.data.JobItem
import tv.coog.app.data.MediaItem
import tv.coog.app.data.StreamCandidate
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogCached
import tv.coog.app.ui.theme.CoogDanger
import tv.coog.app.ui.theme.CoogTextMuted
import tv.coog.app.ui.theme.CoogTextSecondary
import tv.coog.app.ui.theme.CoogType

private enum class StreamTypeFilter(val label: String) {
    All("All"),
    Local("Local"),
    Cached("RD+"),
    Torrent("Torrent"),
    Web("Web"),
}

private enum class StreamSort(val label: String) {
    Best("Best"),
    Quality("Quality"),
    Size("Size"),
    Seeders("Seeders"),
}

@Composable
fun StreamsScreen(
    item: MediaItem,
    playError: String?,
    onBack: () -> Unit,
    onPick: (StreamCandidate) -> Unit,
    onResumeJob: (JobItem) -> Unit = {},
    jobs: List<JobItem> = emptyList(),
    onPlayLocal: (mediaId: String) -> Unit = {},
    onManageLocal: (mediaId: String) -> Unit = {},
    onKeepBestLocal: (keepId: String, deleteIds: List<String>, onDone: () -> Unit) -> Unit = { _, _, onDone -> onDone() },
    reloadToken: Int = 0,
) {
    val server = LocalCoogServer.current
    var loading by remember(item.id) { mutableStateOf(true) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var items by remember(item.id) { mutableStateOf<List<StreamCandidate>>(emptyList()) }
    var typeFilter by remember(item.id) { mutableStateOf(StreamTypeFilter.All) }
    var sort by remember(item.id) { mutableStateOf(StreamSort.Best) }
    var refreshNonce by remember(item.id) { mutableStateOf(0) }
    val filterFocus = remember { FocusRequester() }
    val sortFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(item.id, item.imdbId, item.season, item.episode, server.url, refreshNonce, reloadToken) {
        loading = true
        error = null
        try {
            val kind = item.kind.ifBlank { "movie" }
            items = CoogApi(server.url, server.token).catalogStreams(
                imdbId = item.imdbId,
                kind = kind,
                season = item.season,
                episode = item.episode,
                title = item.headline(),
                year = item.year,
            ).items
            if (items.isEmpty()) {
                error = "No sources found."
            }
        } catch (e: Exception) {
            error = e.message ?: "Could not load sources"
        } finally {
            loading = false
        }
    }

    val localFiles = remember(items) {
        items.filter { it.isLibraryFile() }.distinctBy { it.mediaId.ifBlank { it.stableKey() } }
    }
    val counts = remember(items) {
        mapOf(
            StreamTypeFilter.All to items.size,
            StreamTypeFilter.Local to localFiles.size,
            StreamTypeFilter.Cached to items.count { it.isCachedRd() && !it.isLibraryOnly() },
            StreamTypeFilter.Torrent to items.count { it.isLocalTorrent() },
            StreamTypeFilter.Web to items.count { it.isWeb() },
        )
    }
    val typeFilters = remember(counts) {
        StreamTypeFilter.entries.filter { it == StreamTypeFilter.All || (counts[it] ?: 0) > 0 }
    }
    LaunchedEffect(counts) {
        if (typeFilter != StreamTypeFilter.All && (counts[typeFilter] ?: 0) == 0) {
            typeFilter = StreamTypeFilter.All
        }
    }
    val jobsByHash = remember(jobs, item.imdbId, item.season, item.episode) {
        jobs.filter { jobMatchesTitle(it, item) }
            .filter { it.infoHash.isNotBlank() && it.isActive() }
            .associateBy { it.infoHash.lowercase() }
    }
    val visible = remember(items, typeFilter, sort, jobsByHash.keys) {
        items.filter { typeFilter.matches(it) }.let { sortStreams(it, sort, jobsByHash) }
    }
    val keepBestId = remember(localFiles) { pickBestLocalMediaId(localFiles) }

    LaunchedEffect(loading, typeFilter, sort, visible.firstOrNull()?.stableKey()) {
        if (loading) {
            runCatching { filterFocus.requestFocus() }
        } else if (visible.isNotEmpty()) {
            listState.scrollToItem(0)
            runCatching { listFocus.requestFocus() }
        } else {
            runCatching { filterFocus.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CoogBgDeep),
    ) {
        PosterArt(
            item = item,
            kind = ArtKind.Backdrop,
            preferDisplay = true,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to CoogBgDeep.copy(alpha = 0.72f),
                        0.18f to CoogBgDeep.copy(alpha = 0.88f),
                        1f to CoogBgDeep,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Sources", style = CoogType.screenTitle)
                    Text(
                        item.headline(),
                        style = CoogType.heroTagline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                GhostButton(label = "Back", onClick = onBack)
            }

            if (playError != null) {
                Text(friendlyPlayError(playError), color = CoogDanger, style = CoogType.heroPlot)
            }

            when {
                loading -> Text(
                    "Looking up Real-Debrid, torrents, and web sources…",
                    style = CoogType.heroPlot,
                    color = CoogTextSecondary,
                )
                error != null && items.isEmpty() -> Text(error ?: "", color = CoogDanger)
                else -> {
                    Text(
                        "${visible.size} of ${items.size} sources",
                        style = CoogType.cardYear,
                        color = CoogTextMuted,
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item {
                            Text("Type", style = CoogType.chip, color = CoogTextMuted)
                        }
                        itemsIndexed(typeFilters) { index, filter ->
                            val n = counts[filter] ?: 0
                            FilterChip(
                                label = if (filter == StreamTypeFilter.All) filter.label else "${filter.label} · $n",
                                selected = typeFilter == filter,
                                onClick = { typeFilter = filter },
                                modifier = when (index) {
                                    0 -> Modifier
                                        .focusRequester(filterFocus)
                                        .focusProperties { down = sortFocus }
                                    else -> Modifier.focusProperties { down = sortFocus }
                                },
                            )
                        }
                    }
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item {
                            Text("Sort", style = CoogType.chip, color = CoogTextMuted)
                        }
                        itemsIndexed(StreamSort.entries.toList()) { index, option ->
                            FilterChip(
                                label = option.label,
                                selected = sort == option,
                                onClick = { sort = option },
                                modifier = when (index) {
                                    0 -> Modifier
                                        .focusRequester(sortFocus)
                                        .focusProperties {
                                            up = filterFocus
                                            down = listFocus
                                        }
                                    else -> Modifier.focusProperties {
                                        up = filterFocus
                                        down = listFocus
                                    }
                                },
                            )
                        }
                        if (localFiles.size >= 2 && keepBestId.isNotBlank()) {
                            item {
                                FilterChip(
                                    label = "Keep best local",
                                    selected = false,
                                    onClick = {
                                        val deleteIds = localFiles.mapNotNull { it.mediaId.takeIf { id -> id.isNotBlank() && id != keepBestId } }
                                        if (deleteIds.isNotEmpty()) {
                                            onKeepBestLocal(keepBestId, deleteIds) { refreshNonce++ }
                                        }
                                    },
                                    modifier = Modifier.focusProperties {
                                        up = filterFocus
                                        down = listFocus
                                    },
                                )
                            }
                        }
                    }
                    if (sort == StreamSort.Best) {
                        Text(
                            "Best sorts Local, then RD+, then quality — not a type filter",
                            style = CoogType.cardYear,
                            color = CoogTextMuted,
                        )
                    }
                    if (localFiles.isNotEmpty()) {
                        Text(
                            when {
                                localFiles.size == 1 -> "1 local file · open Manage to delete"
                                else -> "${localFiles.size} local files · Keep best local removes worse copies of this title"
                            },
                            style = CoogType.cardYear,
                            color = CoogTextMuted,
                        )
                    }
                    if (visible.isEmpty()) {
                        Text(
                            "No sources match this filter.",
                            style = CoogType.heroPlot,
                            color = CoogTextSecondary,
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
                        ) {
                            itemsIndexed(visible, key = { index, row -> "${row.stableKey()}#$index" }) { index, row ->
                                val transfer = row.infoHash.takeIf { it.isNotBlank() }
                                    ?.let { jobsByHash[it.lowercase()] }
                                StreamRow(
                                    candidate = row,
                                    transferJob = transfer,
                                    onClick = {
                                        when {
                                            row.isLibraryFile() && row.mediaId.isNotBlank() && row.isPlayableLocal() ->
                                                onPlayLocal(row.mediaId)
                                            row.isLibraryFile() && row.mediaId.isNotBlank() ->
                                                onManageLocal(row.mediaId)
                                            transfer != null -> onResumeJob(transfer)
                                            else -> onPick(row)
                                        }
                                    },
                                    onManage = row.mediaId.takeIf { row.isLibraryFile() && it.isNotBlank() }?.let { id ->
                                        { onManageLocal(id) }
                                    },
                                    listFocus = if (index == 0) listFocus else null,
                                    upFocus = if (index == 0) sortFocus else null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamRow(
    candidate: StreamCandidate,
    transferJob: JobItem? = null,
    onClick: () -> Unit,
    onManage: (() -> Unit)? = null,
    listFocus: FocusRequester? = null,
    upFocus: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val tags = remember(candidate) {
        (candidate.tags.ifEmpty { candidate.releaseTags() } + languageExtraLabels(candidate.languages)).distinct()
    }
    val headline = remember(candidate) { candidate.structuredHeadline(tags) }
    val subtitle = remember(candidate) {
        when {
            candidate.probeError.isNotBlank() -> candidate.probeError
            candidate.isLibraryOnly() -> candidate.name.ifBlank { candidate.title }
            else -> candidate.title.ifBlank { candidate.name }.ifBlank { candidate.infoHash }
        }
    }
    val secondary = remember(candidate, subtitle) {
        listOfNotNull(
            candidate.seeders.takeIf { it > 0 }?.let { "$it seeders" },
            candidate.packLabel(),
            subtitle.takeIf { it.isNotBlank() && it != headline },
        ).joinToString("  ·  ")
    }
    val flags = remember(candidate) { candidate.audioLanguageFlags() }
    val hasTransfer = transferJob != null
    val exitUp = if (upFocus != null) {
        Modifier.focusProperties { up = upFocus }
    } else {
        Modifier
    }
    val enterFocus = if (listFocus != null) {
        Modifier.focusRequester(listFocus)
    } else {
        Modifier
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CoogClickable(
            onClick = onClick,
            shape = RoundedCornerShape(10.dp),
            color = when {
                candidate.isLibraryFile() && candidate.isPlayableLocal() -> Color(0xFF1F6B3A).copy(alpha = 0.28f)
                candidate.isLibraryFile() -> Color.White.copy(alpha = 0.10f)
                transferJob?.status == "paused" -> Color(0xFF6B5A1F).copy(alpha = 0.28f)
                hasTransfer -> Color(0xFF1F4A6B).copy(alpha = 0.28f)
                else -> Color.White.copy(alpha = 0.07f)
            },
            modifier = Modifier
                .weight(1f)
                .then(enterFocus)
                .then(exitUp)
                .onFocusChanged { focused = it.isFocused }
                .then(
                    if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(10.dp))
                    else Modifier,
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        headline,
                        style = CoogType.cardTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (secondary.isNotBlank()) {
                        Text(
                            secondary,
                            style = CoogType.cardYear,
                            color = when {
                                candidate.probeError.isNotBlank() -> CoogDanger
                                candidate.isCachedRd() || candidate.isLibraryFile() || hasTransfer -> CoogCached
                                else -> CoogTextSecondary
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (flags.isNotBlank()) {
                    Text(
                        flags,
                        style = CoogType.cardTitle.copy(fontSize = 18.sp),
                        maxLines = 1,
                        color = Color.White,
                    )
                }
                SourceOccupancyBadge(candidate = candidate, transferJob = transferJob)
                ChannelBadge(candidate)
            }
        }
        if (onManage != null) {
            CoogClickable(
                onClick = onManage,
                shape = RoundedCornerShape(10.dp),
                color = Color.White.copy(alpha = 0.12f),
                modifier = exitUp,
            ) {
                Box(
                    modifier = Modifier
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Manage", style = CoogType.chip, maxLines = 1, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun SourceOccupancyBadge(candidate: StreamCandidate, transferJob: JobItem?) {
    val pct = transferJob?.let { (it.progress * 100).toInt().coerceIn(0, 99) }
    val (label, bg) = when {
        candidate.isLibraryFile() -> "In library" to Color(0xFF1F6B3A)
        transferJob?.status == "paused" ->
            (if (pct != null) "Paused $pct%" else "Paused") to Color(0xFF6B5A1F)
        transferJob?.status == "queued" -> "Queued" to Color(0xFF1F4A6B)
        transferJob != null ->
            (if (pct != null) "Downloading $pct%" else "Downloading") to Color(0xFF1F4A6B)
        else -> return
    }
    Box(
        modifier = Modifier
            .widthIn(min = 88.dp)
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = CoogType.chip, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun ChannelBadge(candidate: StreamCandidate) {
    if (candidate.isLibraryFile()) return
    val label = when {
        candidate.isWeb() -> "Web"
        candidate.source.equals("rdcatalog", ignoreCase = true) -> "RD lib"
        candidate.isCachedRd() -> "RD+"
        else -> "Torrent"
    }
    val bg = when {
        candidate.isCachedRd() -> Color(0xFF1F6B3A)
        candidate.isWeb() -> Color(0xFF2A4A6E)
        else -> Color.White.copy(alpha = 0.10f)
    }
    Box(
        modifier = Modifier
            .widthIn(min = 72.dp)
            .width(88.dp)
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = CoogType.chip, color = Color.White, maxLines = 1)
    }
}

private fun StreamTypeFilter.matches(c: StreamCandidate): Boolean = when (this) {
    StreamTypeFilter.All -> true
    StreamTypeFilter.Local -> c.isLibraryFile()
    StreamTypeFilter.Cached -> c.isCachedRd() && !c.isLibraryOnly()
    StreamTypeFilter.Torrent -> c.isLocalTorrent()
    StreamTypeFilter.Web -> c.isWeb()
}

private fun StreamCandidate.isLibraryOnly(): Boolean =
    kind.equals("local", ignoreCase = true) || source.equals("local", ignoreCase = true)

private fun StreamCandidate.isLibraryFile(): Boolean =
    isLibraryOnly() || (inLibrary && mediaId.isNotBlank())

private fun StreamCandidate.isPlayableLocal(): Boolean =
    isLibraryFile() && playable && probeError.isBlank()

private fun StreamCandidate.isWeb(): Boolean =
    kind.equals("web", ignoreCase = true) || source.equals("web", ignoreCase = true)

private fun StreamCandidate.isCachedRd(): Boolean =
    !isLibraryOnly() && (cached || source.equals("rdcatalog", ignoreCase = true))

private fun StreamCandidate.isLocalTorrent(): Boolean =
    !isWeb() && !isCachedRd() && !isLibraryFile()

/** Prefer playable, higher quality, larger size when merging duplicate local files. */
private fun pickBestLocalMediaId(locals: List<StreamCandidate>): String {
    if (locals.isEmpty()) return ""
    val best = locals.maxWithOrNull(
        compareBy<StreamCandidate> { it.isPlayableLocal() }
            .thenBy { qualityRank(it.quality.ifBlank { it.title.ifBlank { it.name } }) }
            .thenBy { it.size },
    ) ?: return ""
    return best.mediaId
}

private fun StreamCandidate.packLabel(): String? = when (pack.lowercase()) {
    "season" -> "Season pack"
    "series" -> "Series pack"
    "multi" -> "Multi-episode"
    "single" -> "Single episode"
    else -> null
}

private fun StreamCandidate.structuredHeadline(tags: List<String>): String {
    val parts = mutableListOf<String>()
    val q = quality.ifBlank { qualityFromTitle(this) }
    if (q.isNotBlank()) parts += q
    if (sizeLabel.isNotBlank()) parts += sizeLabel
    tags.filter { it in setOf("Remux", "BluRay", "WEB", "HEVC", "AVC", "DV", "HDR", "HDR10+", "Atmos") }
        .take(3)
        .forEach { parts += it }
    when {
        isLibraryFile() -> parts += "Local"
        isCachedRd() -> parts += "RD+"
        isWeb() -> parts += "Web"
    }
    return parts.joinToString(" · ").ifBlank {
        title.ifBlank { name }.ifBlank { "Source" }
    }
}

private fun StreamCandidate.stableKey(): String =
    listOf(
        mediaId,
        infoHash,
        url,
        source,
        provider,
        kind,
        size.toString(),
        quality,
        title,
        name,
        probeError,
        playable.toString(),
    ).joinToString("|")

private fun qualityRank(text: String): Int {
    val s = text.lowercase()
    return when {
        "2160" in s || "4k" in s || "uhd" in s -> 4
        "1080" in s -> 3
        "720" in s -> 2
        "480" in s -> 1
        else -> 0
    }
}

private fun qualityFromTitle(c: StreamCandidate): String {
    val s = (c.title.ifBlank { c.name }).lowercase()
    return when {
        "2160" in s || "4k" in s || "uhd" in s -> "2160p"
        "1080" in s -> "1080p"
        "720" in s -> "720p"
        "480" in s -> "480p"
        else -> ""
    }
}

private fun jobMatchesTitle(job: JobItem, item: MediaItem): Boolean {
    val imdb = item.imdbId.trim()
    if (imdb.isBlank() || !job.imdbId.equals(imdb, ignoreCase = true)) return false
    if (item.kind.equals("episode", true) || item.kind.equals("series", true) || item.season > 0 || item.episode > 0) {
        val markers = Regex("""(?i)(?:^|[^a-z0-9])s(\d{1,2})e(\d{1,3})(?:[^a-z0-9]|$)""")
            .find(job.title)?.destructured
        val jobSeason = markers?.component1()?.toIntOrNull()
            ?: Regex(""":(\d+):(\d+)$""").find(job.url)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: 0
        val jobEpisode = markers?.component2()?.toIntOrNull()
            ?: Regex(""":(\d+):(\d+)$""").find(job.url)?.groupValues?.getOrNull(2)?.toIntOrNull()
            ?: 0
        val wantS = item.season.coerceAtLeast(1)
        val wantE = item.episode.coerceAtLeast(1)
        return jobSeason == wantS && jobEpisode == wantE
    }
    return !Regex("""(?i)s\d{1,2}e\d{1,3}""").containsMatchIn(job.title) &&
        !Regex(""":\d+:\d+$""").containsMatchIn(job.url)
}

private fun sortStreams(
    items: List<StreamCandidate>,
    sort: StreamSort,
    jobsByHash: Map<String, JobItem> = emptyMap(),
): List<StreamCandidate> {
    val qualityOf = { c: StreamCandidate -> qualityRank(c.quality.ifBlank { c.title.ifBlank { c.name } }) }
    val hasJob = { c: StreamCandidate ->
        c.infoHash.isNotBlank() && jobsByHash.containsKey(c.infoHash.lowercase())
    }
    return when (sort) {
        StreamSort.Best -> items.sortedWith(
            compareByDescending<StreamCandidate> { it.isLibraryFile() && it.isPlayableLocal() }
                .thenByDescending { it.isLibraryFile() }
                .thenByDescending { hasJob(it) }
                .thenByDescending { it.isCachedRd() }
                .thenByDescending(qualityOf)
                .thenByDescending { it.seeders }
                .thenByDescending { it.size },
        )
        StreamSort.Quality -> items.sortedWith(
            compareByDescending(qualityOf)
                .thenByDescending { it.isLibraryFile() }
                .thenByDescending { hasJob(it) }
                .thenByDescending { it.isCachedRd() }
                .thenByDescending { it.size }
                .thenByDescending { it.seeders },
        )
        StreamSort.Size -> items.sortedWith(
            compareByDescending<StreamCandidate> { it.size }
                .thenByDescending { it.isLibraryFile() }
                .thenByDescending { hasJob(it) }
                .thenByDescending { it.isCachedRd() }
                .thenByDescending(qualityOf),
        )
        StreamSort.Seeders -> items.sortedWith(
            compareByDescending<StreamCandidate> { it.seeders }
                .thenByDescending { hasJob(it) }
                .thenByDescending { it.isCachedRd() }
                .thenByDescending(qualityOf)
                .thenByDescending { it.size },
        )
    }
}

/** Encode / release flags parsed from the torrent/web title for at-a-glance scanning. */
internal fun StreamCandidate.releaseTags(): List<String> {
    val raw = "${title} ${name} ${quality}".lowercase()
    val out = mutableListOf<String>()
    fun add(tag: String, vararg needles: String) {
        if (needles.any { it in raw } && tag !in out) out += tag
    }
    add("DV", "dolby vision", " dovi", ".dv.", " dv ", "dvhe")
    add("HDR10+", "hdr10+")
    if ("HDR10+" !in out && "DV" !in out) add("HDR", "hdr10", " hdr", ".hdr")
    add("Atmos", "atmos")
    add("DTS-HD", "dts-hd", "dtshd", "dts:x", "dtsx")
    add("TrueHD", "truehd")
    add("Remux", "remux")
    add("BluRay", "bluray", "blu-ray", "bdrip", "bdremux")
    add("WEB", "web-dl", "webdl", "webrip")
    add("HEVC", "x265", "hevc", "h.265", "h265")
    if ("HEVC" !in out) add("AVC", "x264", "h.264", "h264", "avc")
    add("Hybrid", "hybrid")
    add("Proper", "proper", "repack")
    return out
}
