package tv.coog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import tv.coog.app.data.CatalogGenre
import tv.coog.app.data.CoogApi
import tv.coog.app.data.JobItem
import tv.coog.app.data.MediaItem
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogTextMuted
import tv.coog.app.ui.theme.CoogType

private data class SortChip(val id: String, val label: String)

private val SortChips = listOf(
    SortChip("trending", "Recommended"),
    SortChip("popular", "Popular"),
    SortChip("new", "New"),
)

@Composable
fun CatalogBrowseScreen(
    kind: String,
    jobs: List<JobItem>,
    library: List<MediaItem> = emptyList(),
    onOpen: (MediaItem) -> Unit,
) {
    val server = LocalCoogServer.current
    val title = if (kind == "series") "Series" else "Movies"
    val inset = catalogInset()
    val firstFocus = LocalBrowseContentFocus.current ?: remember { FocusRequester() }
    val shelfFocus = remember { FocusRequester() }
    val enterRail = LocalEnterRail.current
    val browseActive = LocalBrowseActive.current
    var browseWasActive by remember { mutableStateOf(browseActive) }
    var sort by remember(kind) { mutableStateOf("trending") }
    var genreId by remember(kind) { mutableIntStateOf(0) }
    var genres by remember(kind) { mutableStateOf(listOf(CatalogGenre(0, "All"))) }
    var items by remember(kind) { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember(kind) { mutableStateOf(true) }
    var error by remember(kind) { mutableStateOf<String?>(null) }
    var loadedOnce by remember(kind) { mutableStateOf(false) }

    LaunchedEffect(browseActive, items.isNotEmpty()) {
        val returning = browseActive && !browseWasActive
        browseWasActive = browseActive
        if (!returning || items.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.yield()
        runCatching { shelfFocus.requestFocus() }
    }

    LaunchedEffect(kind, server.url, server.token) {
        genres = listOf(CatalogGenre(0, "All")) +
            runCatching { CoogApi(server.url, server.token).catalogGenres(kind) }.getOrDefault(emptyList())
    }

    LaunchedEffect(kind, sort, genreId, server.url, server.token) {
        loading = true
        error = null
        items = emptyList()
        try {
            items = CoogApi(server.url, server.token).catalogBrowse(kind, sort, genreId)
        } catch (e: Exception) {
            error = e.message ?: "Could not load $title"
        } finally {
            loading = false
            if (loadedOnce && items.isNotEmpty()) {
                runCatching { shelfFocus.requestFocus() }
            }
            loadedOnce = true
        }
    }

    val chips = SortChips
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CoogBgDeep)
            .padding(top = topBarHeight(), bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = CoogType.screenTitle,
            modifier = Modifier.padding(start = inset, end = inset, top = 4.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(start = inset, end = inset),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(chips, key = { _, chip -> "sort-${chip.id}" }) { index, chip ->
                FilterChip(
                    label = chip.label,
                    selected = sort == chip.id,
                    onClick = { sort = chip.id },
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        .onPreviewKeyEvent { event ->
                            if (event.key != Key.DirectionUp) return@onPreviewKeyEvent false
                            if (event.type == KeyEventType.KeyDown) enterRail()
                            event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
                        },
                )
            }
            itemsIndexed(genres, key = { _, genre -> "genre-${genre.id}" }) { _, genre ->
                FilterChip(
                    label = genre.name.ifBlank { "All" },
                    selected = genreId == genre.id,
                    onClick = { genreId = genre.id },
                    modifier = Modifier.onPreviewKeyEvent { event ->
                        if (event.key != Key.DirectionUp) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown) enterRail()
                        event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
                    },
                )
            }
        }
        when {
            error != null && items.isEmpty() -> {
                Text(
                    error.orEmpty(),
                    style = CoogType.heroPlot,
                    color = CoogTextMuted,
                    modifier = Modifier.padding(start = inset, end = inset, top = 12.dp),
                )
            }
            loading && items.isEmpty() -> {
                Text(
                    "One moment.",
                    style = CoogType.heroTagline,
                    modifier = Modifier.padding(start = inset, end = inset, top = 12.dp),
                )
            }
            items.isEmpty() -> {
                Text(
                    "Nothing matches these filters.",
                    style = CoogType.heroPlot,
                    color = CoogTextMuted,
                    modifier = Modifier.padding(start = inset, end = inset, top = 12.dp),
                )
            }
            else -> {
                val contentWidth = LocalConfiguration.current.screenWidthDp.dp - inset * 2
                val cardMetrics = rememberShelfCardMetrics(contentWidth)
                FeaturedCarousel(
                    items = items,
                    onOpen = onOpen,
                    jobs = jobs,
                    library = library,
                    expanded = true,
                    active = browseActive,
                    heroCardHeight = cardMetrics.heroHeight,
                    peekCardHeight = cardMetrics.peekHeight,
                    cardMetrics = cardMetrics,
                    firstFocus = shelfFocus,
                    exitUp = false,
                    insetStart = inset,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null,
    mark: CardMark? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when {
                selected && focused -> Color.White
                selected -> Color.White.copy(alpha = 0.22f)
                focused -> Color.White.copy(alpha = 0.16f)
                else -> Color.White.copy(alpha = 0.08f)
            },
            contentColor = if (selected && focused) Color(0xFF121214) else Color.White,
            focusedContainerColor = if (selected) Color.White else Color.White.copy(alpha = 0.16f),
            focusedContentColor = if (selected) Color(0xFF121214) else Color.White,
            pressedContainerColor = Color.White.copy(alpha = 0.24f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = modifier
            .height(32.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            },
    ) {
        Box(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    label,
                    style = CoogType.chip.merge(
                        TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    ),
                    color = if (selected && focused) Color(0xFF121214) else Color.White.copy(alpha = 0.92f),
                )
                if (mark != null) {
                    StatusMark(mark = mark, size = MarkSize.Compact)
                }
            }
        }
    }
}
