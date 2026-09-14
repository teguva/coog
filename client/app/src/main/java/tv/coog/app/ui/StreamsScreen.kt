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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import tv.coog.app.data.CoogApi
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
) {
    val server = LocalCoogServer.current
    var loading by remember(item.id) { mutableStateOf(true) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var items by remember(item.id) { mutableStateOf<List<StreamCandidate>>(emptyList()) }
    var typeFilter by remember(item.id) { mutableStateOf(StreamTypeFilter.All) }
    var sort by remember(item.id) { mutableStateOf(StreamSort.Best) }
    val filterFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(item.id, item.imdbId, item.season, item.episode, server.url) {
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

    val counts = remember(items) {
        mapOf(
            StreamTypeFilter.All to items.size,
            StreamTypeFilter.Cached to items.count { it.isCachedRd() },
            StreamTypeFilter.Torrent to items.count { it.isLocalTorrent() },
            StreamTypeFilter.Web to items.count { it.isWeb() },
        )
    }
    val visible = remember(items, typeFilter, sort) {
        items.filter { typeFilter.matches(it) }.let { sortStreams(it, sort) }
    }

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
                        itemsIndexed(StreamTypeFilter.entries.toList()) { index, filter ->
                            val n = counts[filter] ?: 0
                            FilterChip(
                                label = if (filter == StreamTypeFilter.All) filter.label else "${filter.label} · $n",
                                selected = typeFilter == filter,
                                onClick = { typeFilter = filter },
                                modifier = if (index == 0) Modifier.focusRequester(filterFocus) else Modifier,
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
                        itemsIndexed(StreamSort.entries.toList()) { _, option ->
                            FilterChip(
                                label = option.label,
                                selected = sort == option,
                                onClick = { sort = option },
                            )
                        }
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
                            itemsIndexed(visible, key = { _, row -> row.stableKey() }) { index, row ->
                                StreamRow(
                                    candidate = row,
                                    onClick = { onPick(row) },
                                    modifier = if (index == 0) Modifier.focusRequester(listFocus) else Modifier,
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val tags = remember(candidate) {
        candidate.tags.ifEmpty { candidate.releaseTags() }
    }
    val headline = remember(candidate) { candidate.structuredHeadline(tags) }
    val subtitle = remember(candidate) {
        candidate.title.ifBlank { candidate.name }.ifBlank { candidate.infoHash }
    }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.07f),
            focusedContainerColor = Color.White.copy(alpha = 0.16f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
            .fillMaxWidth()
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
            QualityChip(candidate.quality.ifBlank { qualityFromTitle(candidate) }.ifBlank { "—" })
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
                if (tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.take(5).forEach { tag ->
                            MiniTag(tag)
                        }
                    }
                }
                Text(
                    listOfNotNull(
                        candidate.packLabel(),
                        candidate.channelLabel(),
                        candidate.seeders.takeIf { it > 0 }?.let { "$it seeders" },
                        candidate.provider.ifBlank { null },
                        subtitle.takeIf { it.isNotBlank() },
                    ).joinToString("  ·  "),
                    style = CoogType.cardYear,
                    color = if (candidate.isCachedRd()) CoogCached else CoogTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ChannelBadge(candidate)
        }
    }
}

@Composable
private fun MiniTag(label: String) {
    Text(
        label,
        style = CoogType.chip,
        color = Color.White.copy(alpha = 0.88f),
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        maxLines = 1,
    )
}

@Composable
private fun ChannelBadge(candidate: StreamCandidate) {
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

@Composable
private fun QualityChip(label: String) {
    Text(
        label,
        style = CoogType.chip,
        modifier = Modifier
            .width(64.dp)
            .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        color = Color.White,
        maxLines = 1,
    )
}

private fun StreamTypeFilter.matches(c: StreamCandidate): Boolean = when (this) {
    StreamTypeFilter.All -> true
    StreamTypeFilter.Cached -> c.isCachedRd()
    StreamTypeFilter.Torrent -> c.isLocalTorrent()
    StreamTypeFilter.Web -> c.isWeb()
}

private fun StreamCandidate.isWeb(): Boolean =
    kind.equals("web", ignoreCase = true) || source.equals("web", ignoreCase = true)

private fun StreamCandidate.isCachedRd(): Boolean =
    cached || source.equals("rdcatalog", ignoreCase = true)

private fun StreamCandidate.isLocalTorrent(): Boolean =
    !isWeb() && !isCachedRd()

private fun StreamCandidate.channelLabel(): String = when {
    isWeb() -> "Web-DL"
    source.equals("rdcatalog", ignoreCase = true) -> "RD library"
    cached -> "Cached on Real-Debrid"
    else -> "Torrent"
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
    if (isCachedRd()) parts += "RD+"
    else if (isWeb()) parts += "Web"
    return parts.joinToString(" · ").ifBlank {
        title.ifBlank { name }.ifBlank { "Source" }
    }
}

private fun StreamCandidate.stableKey(): String =
    infoHash.ifBlank { url }.ifBlank { title }.ifBlank { name } + ":" + size + ":" + source + ":" + provider

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

private fun sortStreams(items: List<StreamCandidate>, sort: StreamSort): List<StreamCandidate> {
    val qualityOf = { c: StreamCandidate -> qualityRank(c.quality.ifBlank { c.title.ifBlank { c.name } }) }
    return when (sort) {
        StreamSort.Best -> items.sortedWith(
            compareByDescending<StreamCandidate> { it.isCachedRd() }
                .thenByDescending(qualityOf)
                .thenByDescending { it.seeders }
                .thenByDescending { it.size },
        )
        StreamSort.Quality -> items.sortedWith(
            compareByDescending(qualityOf)
                .thenByDescending { it.isCachedRd() }
                .thenByDescending { it.size }
                .thenByDescending { it.seeders },
        )
        StreamSort.Size -> items.sortedWith(
            compareByDescending<StreamCandidate> { it.size }
                .thenByDescending { it.isCachedRd() }
                .thenByDescending(qualityOf),
        )
        StreamSort.Seeders -> items.sortedWith(
            compareByDescending<StreamCandidate> { it.seeders }
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
