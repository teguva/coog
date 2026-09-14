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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
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
import tv.coog.app.data.CatalogMood
import tv.coog.app.data.CoogApi
import tv.coog.app.data.JobItem
import tv.coog.app.data.MediaItem
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogTextMuted
import tv.coog.app.ui.theme.CoogType

private data class SortChip(val id: String, val label: String)

private data class DecadeChip(val label: String, val yearMin: Int, val yearMax: Int)

private data class RatingChip(val label: String, val min: Double)

private val SortChips = listOf(
    SortChip("trending", "Recommended"),
    SortChip("popular", "Popular"),
    SortChip("new", "New"),
    SortChip("rating", "Top rated"),
)

private val DecadeChips = listOf(
    DecadeChip("Any year", 0, 0),
    DecadeChip("2020s", 2020, 2029),
    DecadeChip("2010s", 2010, 2019),
    DecadeChip("2000s", 2000, 2009),
    DecadeChip("1990s", 1990, 1999),
    DecadeChip("1980s", 1980, 1989),
)

private val RatingChips = listOf(
    RatingChip("Any rating", 0.0),
    RatingChip("6+", 6.0),
    RatingChip("7+", 7.0),
    RatingChip("8+", 8.0),
)

private enum class BrowseFocusRow { Sort, Genre, Facet, Shelf }

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
    val sortFocus = LocalBrowseContentFocus.current ?: remember { FocusRequester() }
    val genreFocus = remember { FocusRequester() }
    val facetFocus = remember { FocusRequester() }
    val shelfFocus = remember { FocusRequester() }
    val enterRail = LocalEnterRail.current
    val browseActive = LocalBrowseActive.current
    var browseWasActive by remember { mutableStateOf(browseActive) }
    var focusRow by remember(kind) { mutableStateOf(BrowseFocusRow.Sort) }
    var sort by remember(kind) { mutableStateOf("trending") }
    var selectedGenres by remember(kind) { mutableStateOf(setOf<Int>()) }
    var genres by remember(kind) { mutableStateOf(listOf(CatalogGenre(0, "All"))) }
    var moods by remember(kind) { mutableStateOf<List<CatalogMood>>(emptyList()) }
    var mood by remember(kind) { mutableStateOf("") }
    var yearMin by remember(kind) { mutableStateOf(0) }
    var yearMax by remember(kind) { mutableStateOf(0) }
    var minRating by remember(kind) { mutableStateOf(0.0) }
    var items by remember(kind) { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember(kind) { mutableStateOf(true) }
    var error by remember(kind) { mutableStateOf<String?>(null) }
    var shelfReady by remember(kind) { mutableStateOf(false) }

    LaunchedEffect(items.isNotEmpty()) {
        shelfReady = items.isNotEmpty()
    }

    fun goRail(event: KeyEvent): Boolean {
        if (event.key != Key.DirectionUp) return false
        if (event.type == KeyEventType.KeyDown) enterRail()
        return event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
    }

    fun moveVertical(from: BrowseFocusRow, event: KeyEvent): Boolean {
        val up = event.key == Key.DirectionUp
        val down = event.key == Key.DirectionDown
        if (!up && !down) return false
        if (event.type != KeyEventType.KeyDown && event.type != KeyEventType.KeyUp) return false
        if (event.type == KeyEventType.KeyUp) return true
        if (event.nativeKeyEvent.repeatCount > 0) return true
        val target = when {
            up && from == BrowseFocusRow.Sort -> {
                enterRail()
                return true
            }
            up && from == BrowseFocusRow.Genre -> BrowseFocusRow.Sort
            up && from == BrowseFocusRow.Facet -> BrowseFocusRow.Genre
            up && from == BrowseFocusRow.Shelf -> BrowseFocusRow.Facet
            down && from == BrowseFocusRow.Sort -> BrowseFocusRow.Genre
            down && from == BrowseFocusRow.Genre -> BrowseFocusRow.Facet
            down && from == BrowseFocusRow.Facet -> {
                if (!shelfReady || items.isEmpty()) return true
                BrowseFocusRow.Shelf
            }
            else -> return false
        }
        val ok = when (target) {
            BrowseFocusRow.Sort -> runCatching { sortFocus.requestFocus() }.getOrDefault(false)
            BrowseFocusRow.Genre -> runCatching { genreFocus.requestFocus() }.getOrDefault(false)
            BrowseFocusRow.Facet -> runCatching { facetFocus.requestFocus() }.getOrDefault(false)
            BrowseFocusRow.Shelf -> runCatching { shelfFocus.requestFocus() }.getOrDefault(false)
        }
        if (ok) focusRow = target
        return true
    }

    LaunchedEffect(browseActive, items.isNotEmpty()) {
        val returning = browseActive && !browseWasActive
        browseWasActive = browseActive
        if (!returning || items.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.yield()
        if (runCatching { shelfFocus.requestFocus() }.getOrDefault(false)) {
            focusRow = BrowseFocusRow.Shelf
        }
    }

    LaunchedEffect(kind, server.url, server.token) {
        genres = listOf(CatalogGenre(0, "All")) +
            runCatching { CoogApi(server.url, server.token).catalogGenres(kind) }.getOrDefault(emptyList())
        moods = runCatching { CoogApi(server.url, server.token).catalogMoods(kind) }.getOrDefault(emptyList())
    }

    LaunchedEffect(
        kind, sort, selectedGenres, yearMin, yearMax, mood, minRating,
        server.url, server.token,
    ) {
        loading = true
        error = null
        try {
            items = CoogApi(server.url, server.token).catalogBrowse(
                kind = kind,
                sort = sort,
                genreIds = selectedGenres.toList().sorted(),
                yearMin = yearMin,
                yearMax = yearMax,
                mood = mood,
                minRating = minRating,
            )
        } catch (e: Exception) {
            error = e.message ?: "Could not load $title"
        } finally {
            loading = false
        }
    }

    fun toggleGenre(id: Int) {
        if (id <= 0) {
            selectedGenres = emptySet()
            return
        }
        selectedGenres = selectedGenres.toMutableSet().also { set ->
            if (id in set) set.remove(id)
            else if (set.size < 3) set.add(id)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CoogBgDeep)
            .padding(top = topBarHeight(), bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
            itemsIndexed(SortChips, key = { _, chip -> "sort-${chip.id}" }) { index, chip ->
                FilterChip(
                    label = chip.label,
                    selected = sort == chip.id,
                    onClick = { sort = chip.id },
                    onFocused = { focusRow = BrowseFocusRow.Sort },
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(sortFocus) else Modifier)
                        .focusProperties {
                            up = FocusRequester.Cancel
                            down = FocusRequester.Cancel
                        }
                        .onPreviewKeyEvent { event ->
                            moveVertical(BrowseFocusRow.Sort, event) || goRail(event)
                        },
                )
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(start = inset, end = inset),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(genres, key = { _, genre -> "genre-${genre.id}" }) { index, genre ->
                val selected = if (genre.id == 0) selectedGenres.isEmpty() else genre.id in selectedGenres
                FilterChip(
                    label = genre.name.ifBlank { "All" },
                    selected = selected,
                    onClick = { toggleGenre(genre.id) },
                    onFocused = { focusRow = BrowseFocusRow.Genre },
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(genreFocus) else Modifier)
                        .focusProperties {
                            up = FocusRequester.Cancel
                            down = FocusRequester.Cancel
                        }
                        .onPreviewKeyEvent { event -> moveVertical(BrowseFocusRow.Genre, event) },
                )
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(start = inset, end = inset),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(DecadeChips, key = { _, chip -> "decade-${chip.label}" }) { index, chip ->
                FilterChip(
                    label = chip.label,
                    selected = yearMin == chip.yearMin && yearMax == chip.yearMax,
                    onClick = {
                        yearMin = chip.yearMin
                        yearMax = chip.yearMax
                    },
                    onFocused = { focusRow = BrowseFocusRow.Facet },
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(facetFocus) else Modifier)
                        .focusProperties {
                            up = FocusRequester.Cancel
                            down = FocusRequester.Cancel
                        }
                        .onPreviewKeyEvent { event -> moveVertical(BrowseFocusRow.Facet, event) },
                )
            }
            if (moods.isNotEmpty()) {
                item(key = "mood-any") {
                    FilterChip(
                        label = "Any mood",
                        selected = mood.isBlank(),
                        onClick = { mood = "" },
                        onFocused = { focusRow = BrowseFocusRow.Facet },
                        modifier = Modifier
                            .focusProperties {
                                up = FocusRequester.Cancel
                                down = FocusRequester.Cancel
                            }
                            .onPreviewKeyEvent { event -> moveVertical(BrowseFocusRow.Facet, event) },
                    )
                }
                itemsIndexed(moods, key = { _, m -> "mood-${m.id}" }) { _, m ->
                    FilterChip(
                        label = m.label.ifBlank { m.id },
                        selected = mood == m.id,
                        onClick = { mood = if (mood == m.id) "" else m.id },
                        onFocused = { focusRow = BrowseFocusRow.Facet },
                        modifier = Modifier
                            .focusProperties {
                                up = FocusRequester.Cancel
                                down = FocusRequester.Cancel
                            }
                            .onPreviewKeyEvent { event -> moveVertical(BrowseFocusRow.Facet, event) },
                    )
                }
            }
            itemsIndexed(RatingChips, key = { _, chip -> "rating-${chip.label}" }) { _, chip ->
                FilterChip(
                    label = chip.label,
                    selected = minRating == chip.min,
                    onClick = { minRating = chip.min },
                    onFocused = { focusRow = BrowseFocusRow.Facet },
                    modifier = Modifier
                        .focusProperties {
                            up = FocusRequester.Cancel
                            down = FocusRequester.Cancel
                        }
                        .onPreviewKeyEvent { event -> moveVertical(BrowseFocusRow.Facet, event) },
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
                    active = browseActive && focusRow == BrowseFocusRow.Shelf,
                    heroCardHeight = cardMetrics.heroHeight,
                    peekCardHeight = cardMetrics.peekHeight,
                    cardMetrics = cardMetrics,
                    firstFocus = shelfFocus,
                    exitUp = false,
                    onRowFocused = { focusRow = BrowseFocusRow.Shelf },
                    onVerticalMove = { delta ->
                        if (delta >= 0) return@FeaturedCarousel false
                        val ok = runCatching { facetFocus.requestFocus() }.getOrDefault(false)
                        if (ok) focusRow = BrowseFocusRow.Facet
                        ok
                    },
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
