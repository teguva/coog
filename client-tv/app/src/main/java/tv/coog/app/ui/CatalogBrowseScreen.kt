package tv.coog.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlin.math.max
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

private data class BrowseGenreChip(
    val key: String,
    val name: String,
    val movieId: Int = 0,
    val seriesId: Int = 0,
)

private data class BrowseHudRow(
    val id: String,
    val label: String,
    val checked: Boolean,
    val checkable: Boolean = true,
    val section: Boolean = false,
)

private data class BrowseSummaryChip(
    val id: String,
    val label: String,
    val onClear: () -> Unit,
)

private val SortChips = listOf(
    SortChip("trending", "Recommended"),
    SortChip("popular", "Popular"),
    SortChip("new", "New"),
    SortChip("rating", "Top rated"),
)

private val TypeChips = listOf(
    SortChip("all", "All"),
    SortChip("movie", "Movies"),
    SortChip("series", "Series"),
)

private val SourceChips = listOf(
    SortChip("all", "All sources"),
    SortChip("stream", "Stream"),
    SortChip("local", "Library"),
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

private enum class BrowseFocusRow { Toolbar, Shelf }

private enum class BrowseMenu { None, Sort, Filters }

private val BrowseHudBg = Color(0xE6121214)
private val BrowsePosterShape = RoundedCornerShape(12.dp)

/** Mutable hub so D-pad hops don't feed focusedKey back into the grid as compose state. */
private class BrowseFocusHub {
    var index: Int = 0
    var key: String? = null
    var focusCard: (Int) -> Unit = {}
}

@Composable
fun CatalogBrowseScreen(
    jobs: List<JobItem>,
    library: List<MediaItem> = emptyList(),
    onOpen: (MediaItem) -> Unit,
    onManageLibrary: ((MediaItem) -> Unit)? = null,
    libraryMenuOpen: Boolean = false,
) {
    val server = LocalCoogServer.current
    val inset = catalogInset()
    val scope = rememberCoroutineScope()
    val sortFocus = LocalBrowseContentFocus.current ?: remember { FocusRequester() }
    val filtersFocus = remember { FocusRequester() }
    val shelfFocus = remember { FocusRequester() }
    val hudFocus = remember { FocusRequester() }
    val enterRail = LocalEnterRail.current
    val browseActive = LocalBrowseActive.current
    var browseWasActive by remember { mutableStateOf(browseActive) }
    var focusRow by remember { mutableStateOf(BrowseFocusRow.Toolbar) }
    var browseMenu by remember { mutableStateOf(BrowseMenu.None) }
    var menuSel by remember { mutableIntStateOf(0) }
    var sort by remember { mutableStateOf("trending") }
    var typeFilter by remember { mutableStateOf("all") }
    var sourceFilter by remember { mutableStateOf("all") }
    var selectedGenreKeys by remember { mutableStateOf(listOf<String>()) }
    var genreChips by remember { mutableStateOf(listOf(BrowseGenreChip("all", "All"))) }
    var moods by remember { mutableStateOf<List<CatalogMood>>(emptyList()) }
    var mood by remember { mutableStateOf("") }
    var yearMin by remember { mutableStateOf(0) }
    var yearMax by remember { mutableStateOf(0) }
    var minRating by remember { mutableStateOf(0.0) }
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var browsePage by remember { mutableIntStateOf(1) }
    var endReached by remember { mutableStateOf(false) }
    var browseGen by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusHub = remember { BrowseFocusHub() }

    fun goRail(event: KeyEvent): Boolean {
        if (event.key != Key.DirectionUp) return false
        if (event.type == KeyEventType.KeyDown) enterRail()
        return event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
    }

    fun moveVertical(from: BrowseFocusRow, event: KeyEvent): Boolean {
        if (browseMenu != BrowseMenu.None) return false
        val up = event.key == Key.DirectionUp
        val down = event.key == Key.DirectionDown
        if (!up && !down) return false
        if (event.type != KeyEventType.KeyDown && event.type != KeyEventType.KeyUp) return false
        if (event.type == KeyEventType.KeyUp) return true
        if (event.nativeKeyEvent.repeatCount > 0) return true
        val target = when {
            up && from == BrowseFocusRow.Toolbar -> {
                enterRail()
                return true
            }
            up && from == BrowseFocusRow.Shelf -> BrowseFocusRow.Toolbar
            down && from == BrowseFocusRow.Toolbar -> {
                if (items.isEmpty()) return true
                BrowseFocusRow.Shelf
            }
            else -> return false
        }
        val ok = when (target) {
            BrowseFocusRow.Toolbar -> runCatching { sortFocus.requestFocus() }.getOrDefault(false)
            BrowseFocusRow.Shelf -> {
                val idx = focusHub.key?.let { key ->
                    items.indexOfFirst { it.playableId().ifBlank { it.id } == key }
                }?.takeIf { it >= 0 } ?: focusHub.index.coerceIn(0, items.lastIndex)
                focusHub.focusCard(idx)
                true
            }
        }
        if (ok) focusRow = target
        return true
    }

    val railFocused = LocalNavBarFocused.current
    LaunchedEffect(browseActive, railFocused, items.isNotEmpty()) {
        val returning = browseActive && !browseWasActive
        browseWasActive = browseActive
        // Stay on the nav rail when the user only highlights Browse — don't steal focus.
        if (!returning || railFocused || items.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.yield()
        val idx = focusHub.key?.let { key ->
            items.indexOfFirst { it.playableId().ifBlank { it.id } == key }
        }?.takeIf { it >= 0 } ?: 0
        focusHub.focusCard(idx)
        focusRow = BrowseFocusRow.Shelf
    }
    LaunchedEffect(browseActive, railFocused) {
        if (!browseActive || railFocused) return@LaunchedEffect
        if (focusRow != BrowseFocusRow.Toolbar) return@LaunchedEffect
        if (browseMenu != BrowseMenu.None) return@LaunchedEffect
        kotlinx.coroutines.yield()
        runCatching { sortFocus.requestFocus() }
    }

    var prevType by remember { mutableStateOf(typeFilter) }
    LaunchedEffect(typeFilter, server.url, server.token) {
        val kindChanged = prevType != typeFilter
        prevType = typeFilter
        val api = CoogApi(server.url, server.token)
        genreChips = when (typeFilter) {
            "all" -> {
                val movies = runCatching { api.catalogGenres("movie") }.getOrDefault(emptyList())
                val series = runCatching { api.catalogGenres("series") }.getOrDefault(emptyList())
                mergeBrowseGenreChips(movies, series)
            }
            "series" -> {
                val series = runCatching { api.catalogGenres("series") }.getOrDefault(emptyList())
                listOf(BrowseGenreChip("all", "All")) + series.mapNotNull { g ->
                    val name = g.name.trim()
                    if (name.isEmpty() || g.id <= 0) null
                    else BrowseGenreChip(name.lowercase(), name, seriesId = g.id)
                }
            }
            else -> {
                val movies = runCatching { api.catalogGenres("movie") }.getOrDefault(emptyList())
                listOf(BrowseGenreChip("all", "All")) + movies.mapNotNull { g ->
                    val name = g.name.trim()
                    if (name.isEmpty() || g.id <= 0) null
                    else BrowseGenreChip(name.lowercase(), name, movieId = g.id)
                }
            }
        }
        moods = runCatching {
            api.catalogMoods(if (typeFilter == "series") "series" else "movie")
        }.getOrDefault(emptyList())
        if (kindChanged) {
            selectedGenreKeys = emptyList()
            mood = ""
        }
    }

    val showGenres = sourceFilter != "local"
    fun genreIdsFor(forKind: String): List<Int> {
        if (!showGenres || selectedGenreKeys.isEmpty()) return emptyList()
        return selectedGenreKeys.mapNotNull { key ->
            val chip = genreChips.find { it.key == key } ?: return@mapNotNull null
            val id = if (forKind == "series") chip.seriesId else chip.movieId
            id.takeIf { it > 0 }
        }
    }

    val libraryRev = remember(library) {
        var h = library.size
        for (item in library) h = 31 * h + item.playableId().hashCode()
        h
    }

    suspend fun fetchBrowsePage(page: Int): List<MediaItem> =
        loadBrowseItems(
            typeFilter = typeFilter,
            sourceFilter = sourceFilter,
            sort = sort,
            movieGenreIds = genreIdsFor("movie"),
            seriesGenreIds = genreIdsFor("series"),
            yearMin = yearMin,
            yearMax = yearMax,
            mood = mood,
            minRating = minRating,
            library = library,
            api = CoogApi(server.url, server.token),
            page = page,
        )

    LaunchedEffect(
        typeFilter, sourceFilter, sort, selectedGenreKeys, yearMin, yearMax, mood, minRating,
        libraryRev, server.url, server.token,
    ) {
        val gen = ++browseGen
        error = null
        browsePage = 1
        endReached = false
        loadingMore = false
        if (items.isEmpty()) loading = true
        try {
            if (sourceFilter == "local") {
                val local = fetchBrowsePage(1)
                if (gen != browseGen) return@LaunchedEffect
                items = local
                browsePage = 1
                endReached = true
                focusHub.index = 0
                focusHub.key = null
                return@LaunchedEffect
            }
            val hasFacets = selectedGenreKeys.isNotEmpty() || yearMin > 0 || yearMax > 0 ||
                mood.isNotBlank() || minRating > 0.0
            val first = fetchBrowsePage(1)
            if (gen != browseGen) return@LaunchedEffect
            items = first
            browsePage = 1
            endReached = first.isEmpty()
            focusHub.index = 0
            focusHub.key = null
            if (endReached || hasFacets) return@LaunchedEffect
            val second = fetchBrowsePage(2)
            if (gen != browseGen) return@LaunchedEffect
            if (second.isEmpty()) {
                endReached = true
            } else {
                val merged = (items + second).dedupeBrowseKeys()
                items = merged
                browsePage = 2
                endReached = merged.size <= first.size
            }
        } catch (e: Exception) {
            if (gen == browseGen && items.isEmpty()) {
                error = e.message ?: "Could not load Browse"
            }
        } finally {
            if (gen == browseGen) loading = false
        }
    }

    fun loadMoreBrowse() {
        if (sourceFilter == "local" || endReached || loadingMore || loading) return
        val nextPage = browsePage + 1
        if (nextPage > 20) {
            endReached = true
            return
        }
        val gen = browseGen
        scope.launch {
            // Debounce while the user is still flinging through the grid.
            delay(280)
            if (gen != browseGen || loadingMore || endReached) return@launch
            loadingMore = true
            try {
                val batch = fetchBrowsePage(nextPage)
                if (gen != browseGen) return@launch
                if (batch.isEmpty()) {
                    endReached = true
                } else {
                    val merged = (items + batch).dedupeBrowseKeys()
                    val grew = merged.size > items.size
                    items = merged
                    browsePage = nextPage
                    endReached = !grew
                }
            } catch (_: Exception) {
                // Keep posters; user can scroll again to retry.
            } finally {
                if (gen == browseGen) loadingMore = false
            }
        }
    }

    fun toggleGenreKey(key: String) {
        if (key == "all" || key.isBlank()) {
            selectedGenreKeys = emptyList()
            return
        }
        selectedGenreKeys = selectedGenreKeys.toMutableList().also { list ->
            if (key in list) list.remove(key)
            else if (list.size < 3) list.add(key)
        }
    }

    fun clearAllFilters() {
        typeFilter = "all"
        sourceFilter = "all"
        selectedGenreKeys = emptyList()
        yearMin = 0
        yearMax = 0
        mood = ""
        minRating = 0.0
    }

    val sortLabel = SortChips.find { it.id == sort }?.label ?: "Recommended"
    val summaryChips = buildList {
        if (typeFilter != "all") {
            val label = TypeChips.find { it.id == typeFilter }?.label ?: typeFilter
            add(BrowseSummaryChip("type", label) { typeFilter = "all" })
        }
        if (sourceFilter != "all") {
            val label = SourceChips.find { it.id == sourceFilter }?.label ?: sourceFilter
            add(BrowseSummaryChip("source", label) { sourceFilter = "all" })
        }
        selectedGenreKeys.forEach { key ->
            val name = genreChips.find { it.key == key }?.name ?: key
            add(BrowseSummaryChip("genre-$key", name) {
                selectedGenreKeys = selectedGenreKeys.filterNot { it == key }
            })
        }
        if (yearMin > 0 || yearMax > 0) {
            val label = DecadeChips.find { it.yearMin == yearMin && it.yearMax == yearMax }?.label
                ?: "$yearMin–$yearMax"
            add(BrowseSummaryChip("decade", label) {
                yearMin = 0
                yearMax = 0
            })
        }
        if (mood.isNotBlank()) {
            val label = moods.find { it.id == mood }?.label?.ifBlank { mood } ?: mood
            add(BrowseSummaryChip("mood", label) { mood = "" })
        }
        if (minRating > 0) {
            val label = RatingChips.find { it.min == minRating }?.label ?: "${minRating.toInt()}+"
            add(BrowseSummaryChip("rating", label) { minRating = 0.0 })
        }
    }
    val filterCount = summaryChips.size

    val sortRows = SortChips.map { chip ->
        BrowseHudRow(id = "sort-${chip.id}", label = chip.label, checked = sort == chip.id)
    }
    val filterRows = buildList {
        add(BrowseHudRow("sec-type", "Type", checked = false, checkable = false, section = true))
        TypeChips.forEach { chip ->
            add(BrowseHudRow("type-${chip.id}", chip.label, checked = typeFilter == chip.id))
        }
        add(BrowseHudRow("sec-source", "Source", checked = false, checkable = false, section = true))
        SourceChips.forEach { chip ->
            add(BrowseHudRow("source-${chip.id}", chip.label, checked = sourceFilter == chip.id))
        }
        if (showGenres) {
            add(BrowseHudRow("sec-genre", "Genre", checked = false, checkable = false, section = true))
            genreChips.forEach { chip ->
                val selected = if (chip.key == "all") {
                    selectedGenreKeys.isEmpty()
                } else {
                    chip.key in selectedGenreKeys
                }
                add(BrowseHudRow("genre-${chip.key}", chip.name.ifBlank { "All" }, checked = selected))
            }
        }
        add(BrowseHudRow("sec-decade", "Year", checked = false, checkable = false, section = true))
        DecadeChips.forEach { chip ->
            add(
                BrowseHudRow(
                    "decade-${chip.label}",
                    chip.label,
                    checked = yearMin == chip.yearMin && yearMax == chip.yearMax,
                ),
            )
        }
        if (showGenres && moods.isNotEmpty()) {
            add(BrowseHudRow("sec-mood", "Mood", checked = false, checkable = false, section = true))
            add(BrowseHudRow("mood-any", "Any mood", checked = mood.isBlank()))
            moods.forEach { m ->
                add(
                    BrowseHudRow(
                        "mood-${m.id}",
                        m.label.ifBlank { m.id },
                        checked = mood == m.id,
                    ),
                )
            }
        }
        add(BrowseHudRow("sec-rating", "Rating", checked = false, checkable = false, section = true))
        RatingChips.forEach { chip ->
            add(BrowseHudRow("rating-${chip.label}", chip.label, checked = minRating == chip.min))
        }
        if (filterCount > 0) {
            add(BrowseHudRow("sec-clear", "Reset", checked = false, checkable = false, section = true))
            add(BrowseHudRow("clear-all", "Clear all filters", checked = false, checkable = false))
        }
    }
    val activeRows = when (browseMenu) {
        BrowseMenu.Sort -> sortRows
        BrowseMenu.Filters -> filterRows
        BrowseMenu.None -> emptyList()
    }
    val actionableRows = activeRows.filterNot { it.section }

    fun closeMenu() {
        val restore = browseMenu
        browseMenu = BrowseMenu.None
        menuSel = 0
        focusRow = BrowseFocusRow.Toolbar
        when (restore) {
            BrowseMenu.Sort -> runCatching { sortFocus.requestFocus() }
            BrowseMenu.Filters -> runCatching { filtersFocus.requestFocus() }
            BrowseMenu.None -> Unit
        }
    }

    fun openMenu(menu: BrowseMenu) {
        browseMenu = menu
        menuSel = when (menu) {
            BrowseMenu.Sort -> SortChips.indexOfFirst { it.id == sort }.coerceAtLeast(0)
            BrowseMenu.Filters -> 0
            BrowseMenu.None -> 0
        }
        focusRow = BrowseFocusRow.Toolbar
        scope.launch {
            awaitFrame()
            runCatching { hudFocus.requestFocus() }
        }
    }

    fun activateMenuRow(index: Int) {
        val row = actionableRows.getOrNull(index) ?: return
        when (browseMenu) {
            BrowseMenu.Sort -> {
                val chip = SortChips.getOrNull(index) ?: return
                sort = chip.id
                closeMenu()
            }
            BrowseMenu.Filters -> when {
                row.id.startsWith("type-") -> typeFilter = row.id.removePrefix("type-")
                row.id.startsWith("source-") -> sourceFilter = row.id.removePrefix("source-")
                row.id.startsWith("genre-") -> toggleGenreKey(row.id.removePrefix("genre-"))
                row.id.startsWith("decade-") -> {
                    val chip = DecadeChips.find { "decade-${it.label}" == row.id } ?: return
                    yearMin = chip.yearMin
                    yearMax = chip.yearMax
                }
                row.id == "mood-any" -> mood = ""
                row.id.startsWith("mood-") -> {
                    val id = row.id.removePrefix("mood-")
                    mood = if (mood == id) "" else id
                }
                row.id.startsWith("rating-") -> {
                    val chip = RatingChips.find { "rating-${it.label}" == row.id } ?: return
                    minRating = chip.min
                }
                row.id == "clear-all" -> clearAllFilters()
            }
            BrowseMenu.None -> Unit
        }
    }

    fun moveMenu(delta: Int) {
        if (actionableRows.isEmpty()) return
        menuSel = (menuSel + delta).coerceIn(0, actionableRows.lastIndex)
    }

    LaunchedEffect(browseMenu, actionableRows.size) {
        if (browseMenu == BrowseMenu.None) return@LaunchedEffect
        if (menuSel > actionableRows.lastIndex) menuSel = actionableRows.lastIndex.coerceAtLeast(0)
    }

    BackHandler(enabled = browseMenu != BrowseMenu.None) {
        closeMenu()
    }

    fun toolbarMod(
        requester: FocusRequester? = null,
    ): Modifier = Modifier
        .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
        .focusProperties {
            up = FocusRequester.Cancel
            down = FocusRequester.Cancel
            canFocus = browseMenu == BrowseMenu.None
        }
        .onPreviewKeyEvent { event ->
            moveVertical(BrowseFocusRow.Toolbar, event) || goRail(event)
        }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CoogBgDeep)
            .onPreviewKeyEvent { event ->
                if (browseMenu == BrowseMenu.None) return@onPreviewKeyEvent false
                if (event.key == Key.Back || event.key == Key.Escape) {
                    return@onPreviewKeyEvent false
                }
                val up = event.key == Key.DirectionUp
                val down = event.key == Key.DirectionDown
                val left = event.key == Key.DirectionLeft
                val center = event.key == Key.DirectionCenter || event.key == Key.Enter
                if (event.type == KeyEventType.KeyUp) {
                    return@onPreviewKeyEvent up || down || left || center
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.nativeKeyEvent.repeatCount > 0 && (up || down)) {
                    if (up) moveMenu(-1) else moveMenu(1)
                    return@onPreviewKeyEvent true
                }
                when {
                    up -> {
                        moveMenu(-1)
                        true
                    }
                    down -> {
                        moveMenu(1)
                        true
                    }
                    left -> {
                        closeMenu()
                        true
                    }
                    center -> {
                        activateMenuRow(menuSel)
                        true
                    }
                    else -> false
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topBarHeight(), bottom = 10.dp)
                .focusProperties { canFocus = browseMenu == BrowseMenu.None },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Browse",
                style = CoogType.screenTitle,
                modifier = Modifier.padding(start = inset, end = inset, top = 4.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = inset),
            ) {
                FilterChip(
                    label = "Sort · $sortLabel",
                    selected = browseMenu == BrowseMenu.Sort,
                    onClick = {
                        if (browseMenu == BrowseMenu.Sort) closeMenu() else openMenu(BrowseMenu.Sort)
                    },
                    onFocused = { focusRow = BrowseFocusRow.Toolbar },
                    modifier = toolbarMod(
                        requester = if (browseActive) sortFocus else null,
                    ),
                )
                FilterChip(
                    label = if (filterCount > 0) "Filters · $filterCount" else "Filters",
                    selected = browseMenu == BrowseMenu.Filters || filterCount > 0,
                    onClick = {
                        if (browseMenu == BrowseMenu.Filters) closeMenu() else openMenu(BrowseMenu.Filters)
                    },
                    onFocused = { focusRow = BrowseFocusRow.Toolbar },
                    modifier = toolbarMod(requester = filtersFocus),
                )
            }
            if (summaryChips.isNotEmpty() && browseMenu == BrowseMenu.None) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = inset),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(summaryChips, key = { it.id }) { chip ->
                        FilterChip(
                            label = chip.label,
                            selected = true,
                            onClick = chip.onClear,
                            onFocused = { focusRow = BrowseFocusRow.Toolbar },
                            modifier = toolbarMod(),
                        )
                    }
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
                    BrowsePosterGrid(
                        items = items,
                        inset = inset,
                        firstFocus = shelfFocus,
                        libraryMenuOpen = libraryMenuOpen,
                        hudOpen = browseMenu != BrowseMenu.None,
                        endReached = endReached,
                        loadingMore = loadingMore,
                        onNearEnd = { loadMoreBrowse() },
                        onOpen = onOpen,
                        onManageLibrary = onManageLibrary,
                        onSelection = { index, key ->
                            focusHub.index = index
                            focusHub.key = key
                            if (focusRow != BrowseFocusRow.Shelf) {
                                focusRow = BrowseFocusRow.Shelf
                            }
                        },
                        onBindFocusCard = { focusHub.focusCard = it },
                        onExitUp = {
                            if (browseMenu != BrowseMenu.None) return@BrowsePosterGrid true
                            val ok = runCatching { sortFocus.requestFocus() }.getOrDefault(false)
                            if (ok) focusRow = BrowseFocusRow.Toolbar
                            ok
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }

        if (browseMenu != BrowseMenu.None) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .zIndex(4f)
                    .focusRequester(hudFocus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        // Catcher owns focus; outer Box also handles menu keys.
                        false
                    },
            )
            BrowseHudPanel(
                icon = if (browseMenu == BrowseMenu.Sort) Icons.Outlined.SwapVert else Icons.Outlined.FilterList,
                title = if (browseMenu == BrowseMenu.Sort) "Sort" else "Filters",
                rows = activeRows,
                menuSel = menuSel,
                onActivate = { index ->
                    menuSel = index
                    activateMenuRow(index)
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .zIndex(5f)
                    .padding(end = 36.dp, top = topBarHeight() + 24.dp, bottom = 48.dp),
            )
        }
    }
}

@Composable
private fun BrowseHudPanel(
    icon: ImageVector,
    title: String,
    rows: List<BrowseHudRow>,
    menuSel: Int,
    onActivate: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val actionable = remember(rows) { rows.mapIndexedNotNull { i, row -> if (row.section) null else i } }
    LaunchedEffect(menuSel, rows.size) {
        val rowIndex = actionable.getOrNull(menuSel) ?: return@LaunchedEffect
        runCatching { listState.scrollToItem(rowIndex + 1) }
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .width(268.dp)
            .heightIn(max = 420.dp)
            .background(BrowseHudBg, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "browse-hud-header") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text(
                    title,
                    style = CoogType.chip.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    ),
                )
            }
        }
        itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
            if (row.section) {
                Text(
                    row.label.uppercase(),
                    color = Color.White.copy(alpha = 0.42f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 2.dp, start = 4.dp),
                )
            } else {
                val actionIndex = actionable.indexOf(index)
                BrowseHudListRow(
                    label = row.label,
                    checked = row.checked,
                    checkable = row.checkable,
                    highlighted = actionIndex == menuSel,
                    onClick = { if (actionIndex >= 0) onActivate(actionIndex) },
                )
            }
        }
    }
}

@Composable
private fun BrowseHudListRow(
    label: String,
    checked: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    checkable: Boolean = true,
) {
    val fg = when {
        highlighted -> Color.White
        checked -> Color.White.copy(alpha = 0.92f)
        else -> Color.White.copy(alpha = 0.55f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(
                if (highlighted) Color.White.copy(alpha = 0.08f) else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp)
            .focusProperties { canFocus = false },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            if (checkable && checked) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        Text(
            label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = if (highlighted || checked) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BrowsePosterGrid(
    items: List<MediaItem>,
    inset: Dp,
    firstFocus: FocusRequester,
    libraryMenuOpen: Boolean,
    hudOpen: Boolean,
    endReached: Boolean,
    loadingMore: Boolean,
    onNearEnd: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    onManageLibrary: ((MediaItem) -> Unit)?,
    onSelection: (Int, String) -> Unit,
    onBindFocusCard: ((Int) -> Unit) -> Unit,
    onExitUp: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val widthDp = LocalConfiguration.current.screenWidthDp
    val density = LocalDensity.current.density
    val cardWidth = when {
        widthDp < 600 -> 112.dp
        widthDp < 1000 -> 120.dp
        else -> 140.dp
    }
    val cardHeight = cardWidth * 1.5f
    // Tight decode — TV SoC hitch is Coil more than layout once grid state is stable.
    val decodeMaxPx = (cardWidth.value * density).toInt().coerceIn(120, 200)
    val gap = 10.dp
    val contentWidthDp = widthDp - (inset.value * 2f)
    val columns = max(
        1,
        ((contentWidthDp + gap.value) / (cardWidth.value + gap.value)).toInt(),
    )
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val itemKeys = remember(items) {
        items.map { it.playableId().ifBlank { it.id } }
    }
    val restoreFocus = remember { FocusRequester() }
    // Ref — not compose state — so D-pad hops do not recompose the whole grid.
    val focusedIndexRef = remember { intArrayOf(0) }
    var pendingRestoreIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(itemKeys) {
        focusedIndexRef[0] = 0
        pendingRestoreIndex = -1
    }
    val selectionCb = rememberUpdatedState(onSelection)
    val nearEndCb = rememberUpdatedState(onNearEnd)
    val exitUpCb = rememberUpdatedState(onExitUp)
    val openCb = rememberUpdatedState(onOpen)
    val manageCb = rememberUpdatedState(onManageLibrary)

    fun noteSelection(index: Int, key: String) {
        focusedIndexRef[0] = index
        selectionCb.value(index, key)
    }

    fun focusCard(index: Int) {
        if (index !in items.indices) return
        val key = itemKeys.getOrElse(index) { items[index].id }
        noteSelection(index, key)
        pendingRestoreIndex = index
        scope.launch {
            runCatching { gridState.scrollToItem(index) }
            awaitFrame()
            val target = if (index == 0) firstFocus else restoreFocus
            var ok = runCatching { target.requestFocus() }.getOrDefault(false)
            if (!ok) {
                awaitFrame()
                ok = runCatching { target.requestFocus() }.getOrDefault(false)
            }
            pendingRestoreIndex = -1
        }
    }

    DisposableEffect(Unit) {
        onBindFocusCard { focusCard(it) }
        onDispose { onBindFocusCard { } }
    }

    PrefetchBrowseThumbsRef(
        items = items,
        focusedIndexRef = focusedIndexRef,
        columns = columns,
        enabled = !hudOpen && items.isNotEmpty(),
        decodePx = decodeMaxPx,
    )

    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = info.totalItemsCount
            total > 0 && last >= total - (columns * 2).coerceAtLeast(6)
        }
    }
    LaunchedEffect(nearEnd, items.size, endReached, loadingMore) {
        if (nearEnd && !endReached && !loadingMore) nearEndCb.value()
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = modifier
            .padding(horizontal = inset)
            .focusProperties { canFocus = !hudOpen },
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
    ) {
        itemsIndexed(
            items,
            key = { index, item ->
                item.playableId().ifBlank { item.id }.ifBlank { "item-$index" }
            },
            contentType = { _, _ -> "browse-poster" },
        ) { index, item ->
            val key = itemKeys.getOrElse(index) { item.id }
            val lastRowStart = (items.size - 1) / columns * columns
            val onLastRow = index >= lastRowStart
            BrowsePosterTile(
                item = item,
                title = item.headline(),
                subtitle = item.year.takeIf { it > 0 }?.toString().orEmpty(),
                width = cardWidth,
                height = cardHeight,
                decodeMaxPx = decodeMaxPx,
                onClick = { openCb.value(item) },
                onLongClick = manageCb.value
                    ?.takeIf { item.canManageLibrary() }
                    ?.let { manage -> { manage(item) } },
                onFocused = { noteSelection(index, key) },
                modifier = Modifier
                    .then(
                        when {
                            index == 0 -> Modifier.focusRequester(firstFocus)
                            pendingRestoreIndex == index && index != 0 ->
                                Modifier.focusRequester(restoreFocus)
                            else -> Modifier
                        },
                    )
                    .focusProperties { canFocus = !hudOpen }
                    .onPreviewKeyEvent { event ->
                        if (libraryMenuOpen || hudOpen) return@onPreviewKeyEvent false
                        if (event.key == Key.DirectionDown && onLastRow && !endReached) {
                            if (event.type == KeyEventType.KeyDown) nearEndCb.value()
                            return@onPreviewKeyEvent event.type == KeyEventType.KeyDown ||
                                event.type == KeyEventType.KeyUp
                        }
                        if (index >= columns) return@onPreviewKeyEvent false
                        if (event.key != Key.DirectionUp) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyUp) return@onPreviewKeyEvent true
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (event.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
                        exitUpCb.value()
                    },
            )
        }
        if (loadingMore) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "loading-more") {
                Text(
                    "Loading more…",
                    style = CoogType.cardYear,
                    color = CoogTextMuted,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun BrowsePosterTile(
    item: MediaItem,
    title: String,
    subtitle: String,
    width: Dp,
    height: Dp,
    decodeMaxPx: Int,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    // TV D-pad focus requires tv.material3 Surface — foundation focusable does not light up.
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(shape = BrowsePosterShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            pressedContainerColor = Color.Transparent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
            .width(width)
            .height(height)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(BrowsePosterShape)
                .then(
                    if (focused) {
                        Modifier.border(3.dp, Color.White, BrowsePosterShape)
                    } else {
                        Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), BrowsePosterShape)
                    },
                )
                .background(Color.White.copy(alpha = 0.06f)),
        ) {
            PosterArt(
                item = item,
                kind = ArtKind.Poster,
                preferDisplay = false,
                crossfadeMs = 0,
                decodeMaxPx = decodeMaxPx,
                modifier = Modifier.fillMaxSize(),
            )
            if (focused) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                ) {
                    Text(
                        if (subtitle.isNotBlank()) "$title ($subtitle)" else title,
                        style = CoogType.cardTitle,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
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


private suspend fun loadBrowseItems(
    typeFilter: String,
    sourceFilter: String,
    sort: String,
    movieGenreIds: List<Int>,
    seriesGenreIds: List<Int>,
    yearMin: Int,
    yearMax: Int,
    mood: String,
    minRating: Double,
    library: List<MediaItem>,
    api: CoogApi,
    page: Int = 1,
): List<MediaItem> {
    if (sourceFilter == "local") {
        return filterLocalLibrary(library, typeFilter, yearMin, yearMax, minRating)
    }
    val hasFacets = movieGenreIds.isNotEmpty() || seriesGenreIds.isNotEmpty() ||
        yearMin > 0 || yearMax > 0 || mood.isNotBlank() || minRating > 0.0
    val pageCount = if (hasFacets) 1 else 2
    val catalog = when (typeFilter) {
        "movie" -> api.catalogBrowse(
            kind = "movie",
            sort = sort,
            genreIds = movieGenreIds,
            yearMin = yearMin,
            yearMax = yearMax,
            mood = mood,
            minRating = minRating,
            page = page,
            pageCount = pageCount,
        )
        "series" -> api.catalogBrowse(
            kind = "series",
            sort = sort,
            genreIds = seriesGenreIds,
            yearMin = yearMin,
            yearMax = yearMax,
            mood = mood,
            minRating = minRating,
            page = page,
            pageCount = pageCount,
        )
        else -> coroutineScope {
            val movies = async {
                if (seriesGenreIds.isNotEmpty() && movieGenreIds.isEmpty()) {
                    emptyList()
                } else {
                    api.catalogBrowse(
                        kind = "movie",
                        sort = sort,
                        genreIds = movieGenreIds,
                        yearMin = yearMin,
                        yearMax = yearMax,
                        mood = mood,
                        minRating = minRating,
                        page = page,
                        pageCount = pageCount,
                    )
                }
            }
            val series = async {
                if (movieGenreIds.isNotEmpty() && seriesGenreIds.isEmpty()) {
                    emptyList()
                } else {
                    api.catalogBrowse(
                        kind = "series",
                        sort = sort,
                        genreIds = seriesGenreIds,
                        yearMin = yearMin,
                        yearMax = yearMax,
                        mood = mood,
                        minRating = minRating,
                        page = page,
                        pageCount = pageCount,
                    )
                }
            }
            zipBrowseResults(movies.await(), series.await())
        }
    }
    val filtered = if (sourceFilter != "stream") {
        catalog
    } else {
        val localKeys = library.localCatalogKeys()
        catalog.filter { item ->
            !item.isLocal() && item.catalogMatchKey() !in localKeys
        }
    }
    return filtered.dedupeBrowseKeys()
}

private fun mergeBrowseGenreChips(
    movies: List<CatalogGenre>,
    series: List<CatalogGenre>,
): List<BrowseGenreChip> {
    val byKey = linkedMapOf<String, BrowseGenreChip>()
    for (g in movies) {
        val name = g.name.trim()
        if (name.isEmpty() || g.id <= 0) continue
        val key = name.lowercase()
        byKey[key] = BrowseGenreChip(key, name, movieId = g.id)
    }
    for (g in series) {
        val name = g.name.trim()
        if (name.isEmpty() || g.id <= 0) continue
        val key = name.lowercase()
        val prev = byKey[key]
        byKey[key] = if (prev != null) {
            prev.copy(seriesId = g.id)
        } else {
            BrowseGenreChip(key, name, seriesId = g.id)
        }
    }
    return listOf(BrowseGenreChip("all", "All")) + byKey.values.sortedBy { it.name.lowercase() }
}

private fun MediaItem.catalogMatchKey(): String {
    val imdb = imdbId.trim()
    if (imdb.isNotBlank()) return "imdb:$imdb"
    if (tmdbId > 0) return "tmdb:$kind:$tmdbId"
    return "id:${playableId().ifBlank { id }}"
}

private fun List<MediaItem>.localCatalogKeys(): Set<String> {
    val keys = HashSet<String>()
    for (item in this) {
        if (!item.isLocal()) continue
        val imdb = item.imdbId.trim()
        if (imdb.isNotBlank()) keys += "imdb:$imdb"
        if (item.tmdbId > 0) {
            val kind = when (item.kind) {
                "episode", "series" -> "series"
                else -> item.kind.ifBlank { "movie" }
            }
            keys += "tmdb:$kind:${item.tmdbId}"
        }
    }
    return keys
}

private fun filterLocalLibrary(
    library: List<MediaItem>,
    typeFilter: String,
    yearMin: Int,
    yearMax: Int,
    minRating: Double,
): List<MediaItem> {
    val browse = library.libraryBrowseItems()
    return browse.filter { item ->
        val typeOk = when (typeFilter) {
            "movie" -> item.kind == "movie"
            "series" -> item.kind == "series" || item.kind == "episode"
            else -> true
        }
        if (!typeOk) return@filter false
        if (yearMin > 0 && item.year > 0 && item.year < yearMin) return@filter false
        if (yearMax > 0 && item.year > 0 && item.year > yearMax) return@filter false
        if (minRating > 0 && item.rating > 0 && item.rating < minRating) return@filter false
        true
    }
}

private fun zipBrowseResults(movies: List<MediaItem>, series: List<MediaItem>): List<MediaItem> {
    val out = ArrayList<MediaItem>(movies.size + series.size)
    val max = maxOf(movies.size, series.size)
    for (i in 0 until max) {
        if (i < movies.size) out += movies[i]
        if (i < series.size) out += series[i]
    }
    return out.dedupeBrowseKeys()
}

private fun List<MediaItem>.dedupeBrowseKeys(): List<MediaItem> {
    if (size < 2) return this
    val seen = HashSet<String>(size * 2)
    return filter { item ->
        val keys = buildList {
            val imdb = item.imdbId.trim().lowercase()
            if (imdb.isNotBlank()) add("imdb:$imdb")
            if (item.tmdbId > 0) {
                val kind = when (item.kind) {
                    "episode", "series" -> "series"
                    else -> item.kind.ifBlank { "movie" }
                }
                add("tmdb:$kind:${item.tmdbId}")
            }
            val playable = item.playableId().ifBlank { item.id }.trim()
            if (playable.isNotBlank()) add("id:$playable")
        }
        if (keys.isEmpty()) return@filter true
        if (keys.any { it in seen }) return@filter false
        seen.addAll(keys)
        true
    }
}
