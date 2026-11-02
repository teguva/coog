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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import tv.coog.app.BuildConfig
import tv.coog.app.data.CatalogGenre
import tv.coog.app.data.CatalogMood
import tv.coog.app.data.CoogApi
import tv.coog.app.data.JobItem
import tv.coog.app.data.MediaItem
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogTextMuted
import tv.coog.app.ui.theme.CoogType

private data class BrowseCacheEntry(
    val items: List<MediaItem>,
    val page: Int,
    val endReached: Boolean,
)

private data class SortChip(val id: String, val label: String)

private data class DecadeChip(val label: String, val yearMin: Int, val yearMax: Int)

private data class RatingChip(val label: String, val min: Double)

private data class FilterOption(val id: String, val label: String)

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

private val TypeChips = listOf(
    FilterOption("all", "All"),
    FilterOption("movie", "Movies"),
    FilterOption("series", "Series"),
)

private val SourceChips = listOf(
    FilterOption("all", "All"),
    FilterOption("local", "Local"),
    FilterOption("stream", "Stream"),
)

private enum class BrowseFocusRow { Sort, Genre, Facet, Shelf }

@Composable
fun CatalogBrowseScreen(
    kind: String,
    jobs: List<JobItem>,
    library: List<MediaItem> = emptyList(),
    onOpen: (MediaItem) -> Unit,
    onManageLibrary: ((MediaItem) -> Unit)? = null,
    libraryMenuOpen: Boolean = false,
) {
    val touch = BuildConfig.TOUCH_CLIENT
    val server = LocalCoogServer.current
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
    var typeFilter by remember { mutableStateOf(if (touch) "all" else kind.ifBlank { "movie" }) }
    var sourceFilter by remember { mutableStateOf("all") }
    var selectedGenres by remember(kind) { mutableStateOf(setOf<Int>()) }
    var genres by remember { mutableStateOf(listOf(CatalogGenre(0, "All"))) }
    var moods by remember { mutableStateOf<List<CatalogMood>>(emptyList()) }
    var mood by remember(kind) { mutableStateOf("") }
    var yearMin by remember(kind) { mutableStateOf(0) }
    var yearMax by remember(kind) { mutableStateOf(0) }
    var minRating by remember(kind) { mutableStateOf(0.0) }
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var browsePage by remember { mutableIntStateOf(1) }
    var endReached by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var shelfReady by remember { mutableStateOf(false) }
    var browseGen by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val title = when {
        touch -> "Browse"
        kind == "series" -> "Series"
        else -> "Movies"
    }
    val genreKind = when {
        !touch -> kind.ifBlank { "movie" }
        typeFilter == "series" -> "series"
        else -> "movie"
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
        if (touch) return@LaunchedEffect
        val returning = browseActive && !browseWasActive
        browseWasActive = browseActive
        if (!returning || items.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.yield()
        if (runCatching { shelfFocus.requestFocus() }.getOrDefault(false)) {
            focusRow = BrowseFocusRow.Shelf
        }
    }

    var prevGenreKind by remember { mutableStateOf(genreKind) }
    LaunchedEffect(genreKind, server.url, server.token) {
        val kindChanged = prevGenreKind != genreKind
        prevGenreKind = genreKind
        genres = listOf(CatalogGenre(0, "All")) +
            runCatching { CoogApi(server.url, server.token).catalogGenres(genreKind) }.getOrDefault(emptyList())
        moods = runCatching { CoogApi(server.url, server.token).catalogMoods(genreKind) }.getOrDefault(emptyList())
        if (kindChanged) {
            selectedGenres = emptySet()
            mood = ""
        }
    }

    // Avoid refetching catalog every time the parent replaces `library` with the same content.
    val libraryRev = remember(library) {
        var h = library.size
        for (item in library) h = 31 * h + item.playableId().hashCode()
        h
    }
    val libraryDep = if (sourceFilter == "local" || sourceFilter == "stream") libraryRev else 0
    val browseCache = remember { LinkedHashMap<String, BrowseCacheEntry>(24, 0.75f, true) }
    // Movie/TV genre id spaces differ — only offer genres when a single kind is selected.
    val genresEnabled = !touch || typeFilter == "movie" || typeFilter == "series"

    // Local source is pure client filter — update immediately (no network / debounce).
    val localItems = remember(library, typeFilter, yearMin, yearMax, minRating) {
        filterLocalLibrary(library, typeFilter, yearMin, yearMax, minRating)
    }

    fun filterCacheKey(genreIds: List<Int>): String = listOf(
        if (touch) typeFilter else kind,
        sourceFilter,
        sort,
        genreIds.joinToString(","),
        yearMin,
        yearMax,
        mood,
        minRating,
        if (sourceFilter == "stream") libraryDep else 0,
    ).joinToString("|")

    fun putBrowseCache(key: String, entry: BrowseCacheEntry) {
        if (browseCache.size >= 24 && !browseCache.containsKey(key)) {
            val eldest = browseCache.entries.iterator()
            if (eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            }
        }
        browseCache[key] = entry
    }

    suspend fun fetchBrowsePage(page: Int, genreIds: List<Int>): List<MediaItem> =
        loadBrowseItems(
            touch = touch,
            kind = kind,
            typeFilter = typeFilter,
            sourceFilter = sourceFilter,
            sort = sort,
            genreIds = genreIds,
            yearMin = yearMin,
            yearMax = yearMax,
            mood = mood,
            minRating = minRating,
            library = library,
            api = CoogApi(server.url, server.token),
            page = page,
        )

    LaunchedEffect(typeFilter) {
        if (touch && typeFilter == "all" && selectedGenres.isNotEmpty()) {
            selectedGenres = emptySet()
        }
    }

    LaunchedEffect(
        touch, kind, typeFilter, sourceFilter, sort, selectedGenres, yearMin, yearMax, mood, minRating,
        libraryDep, server.url, server.token,
    ) {
        val gen = ++browseGen
        if (touch && sourceFilter == "local") {
            items = localItems
            browsePage = 1
            endReached = true
            loading = false
            loadingMore = false
            error = null
            return@LaunchedEffect
        }
        error = null
        browsePage = 1
        endReached = false
        loadingMore = false
        if (items.isEmpty()) loading = true
        delay(90)
        if (gen != browseGen) return@LaunchedEffect
        val genreIds = if (genresEnabled) selectedGenres.toList().sorted() else emptyList()
        val cacheKey = filterCacheKey(genreIds)
        browseCache[cacheKey]?.let { cached ->
            items = cached.items
            browsePage = cached.page
            endReached = cached.endReached
            loading = false
            return@LaunchedEffect
        }
        try {
            // Batch 1 paints quickly (~2 TMDB pages); prefetch batch 2 while user scrolls.
            val first = fetchBrowsePage(1, genreIds)
            if (gen != browseGen) return@LaunchedEffect
            items = first
            browsePage = 1
            endReached = first.isEmpty()
            loading = false
            if (!touch || endReached) {
                putBrowseCache(cacheKey, BrowseCacheEntry(first, 1, endReached))
                return@LaunchedEffect
            }
            val second = fetchBrowsePage(2, genreIds)
            if (gen != browseGen) return@LaunchedEffect
            if (second.isEmpty()) {
                endReached = true
            } else {
                val merged = (items + second).dedupeBrowseKeys()
                items = merged
                browsePage = 2
                // Stop only when the batch adds nothing new (filtered/exhausted).
                endReached = merged.size <= first.size
            }
            putBrowseCache(cacheKey, BrowseCacheEntry(items, browsePage, endReached))
        } catch (e: Exception) {
            if (gen == browseGen && items.isEmpty()) error = e.message ?: "Could not load $title"
        } finally {
            if (gen == browseGen) loading = false
        }
    }

    fun loadMoreBrowse() {
        if (!touch || sourceFilter == "local" || endReached || loadingMore || loading) return
        val genreIds = if (genresEnabled) selectedGenres.toList().sorted() else emptyList()
        val nextPage = browsePage + 1
        if (nextPage > 20) {
            endReached = true
            return
        }
        val gen = browseGen
        scope.launch {
            loadingMore = true
            try {
                val batch = fetchBrowsePage(nextPage, genreIds)
                if (gen != browseGen) return@launch
                if (batch.isEmpty()) {
                    endReached = true
                } else {
                    val merged = (items + batch).dedupeBrowseKeys()
                    val grew = merged.size > items.size
                    items = merged
                    browsePage = nextPage
                    endReached = !grew
                    putBrowseCache(
                        filterCacheKey(genreIds),
                        BrowseCacheEntry(items, browsePage, endReached),
                    )
                }
            } catch (_: Exception) {
                // Keep current posters; user can scroll again to retry.
            } finally {
                if (gen == browseGen) loadingMore = false
            }
        }
    }

    val shownItems = if (touch && sourceFilter == "local") localItems else items
    LaunchedEffect(shownItems.isNotEmpty()) {
        shelfReady = shownItems.isNotEmpty()
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
            // Touch: no outer bottom pad — that left a black slab under the glass dock.
            // Clearance lives in BrowsePosterGrid contentPadding so posters scroll under glass.
            .padding(top = topBarHeight(), bottom = if (touch) 0.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (touch) 4.dp else 6.dp),
    ) {
        if (!touch) {
            Text(
                title,
                style = CoogType.screenTitle,
                modifier = Modifier.padding(start = inset, end = inset, top = 4.dp),
            )
        }
        if (touch) {
            // One compact scroller: type · source · sort
            ChipRow(inset = inset) {
                itemsIndexed(TypeChips, key = { _, chip -> "type-${chip.id}" }) { _, chip ->
                    FilterChip(
                        label = chip.label,
                        selected = typeFilter == chip.id,
                        onClick = { typeFilter = chip.id },
                    )
                }
                item(key = "sep-type-source") { ChipRowDivider() }
                itemsIndexed(SourceChips, key = { _, chip -> "source-${chip.id}" }) { _, chip ->
                    FilterChip(
                        label = chip.label,
                        selected = sourceFilter == chip.id,
                        onClick = { sourceFilter = chip.id },
                    )
                }
                item(key = "sep-source-sort") { ChipRowDivider() }
                itemsIndexed(SortChips, key = { _, chip -> "sort-${chip.id}" }) { index, chip ->
                    FilterChip(
                        label = chip.label,
                        selected = sort == chip.id,
                        onClick = { sort = chip.id },
                        onFocused = { focusRow = BrowseFocusRow.Sort },
                        modifier = Modifier
                            .then(if (index == 0) Modifier.focusRequester(sortFocus) else Modifier),
                    )
                }
            }
            // Genres + year/mood/rating in a single second row
            ChipRow(inset = inset) {
                if (sourceFilter != "local" && genresEnabled) {
                    itemsIndexed(genres, key = { _, genre -> "genre-${genre.id}" }) { index, genre ->
                        val selected = if (genre.id == 0) selectedGenres.isEmpty() else genre.id in selectedGenres
                        FilterChip(
                            label = genre.name.ifBlank { "All" },
                            selected = selected,
                            onClick = { toggleGenre(genre.id) },
                            onFocused = { focusRow = BrowseFocusRow.Genre },
                            modifier = Modifier
                                .then(if (index == 0) Modifier.focusRequester(genreFocus) else Modifier),
                        )
                    }
                    item(key = "sep-genre-facet") { ChipRowDivider() }
                }
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
                            .then(if (index == 0) Modifier.focusRequester(facetFocus) else Modifier),
                    )
                }
                if (moods.isNotEmpty() && sourceFilter != "local") {
                    item(key = "mood-any") {
                        FilterChip(
                            label = "Any mood",
                            selected = mood.isBlank(),
                            onClick = { mood = "" },
                            onFocused = { focusRow = BrowseFocusRow.Facet },
                        )
                    }
                    itemsIndexed(moods, key = { _, m -> "mood-${m.id}" }) { _, m ->
                        FilterChip(
                            label = m.label.ifBlank { m.id },
                            selected = mood == m.id,
                            onClick = { mood = if (mood == m.id) "" else m.id },
                            onFocused = { focusRow = BrowseFocusRow.Facet },
                        )
                    }
                }
                itemsIndexed(RatingChips, key = { _, chip -> "rating-${chip.label}" }) { _, chip ->
                    FilterChip(
                        label = chip.label,
                        selected = minRating == chip.min,
                        onClick = { minRating = chip.min },
                        onFocused = { focusRow = BrowseFocusRow.Facet },
                    )
                }
            }
        } else {
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
        }
        when {
            error != null && shownItems.isEmpty() -> {
                Text(
                    error.orEmpty(),
                    style = CoogType.heroPlot,
                    color = CoogTextMuted,
                    modifier = Modifier.padding(start = inset, end = inset, top = 12.dp),
                )
            }
            loading && shownItems.isEmpty() -> {
                Text(
                    "One moment.",
                    style = CoogType.heroTagline,
                    modifier = Modifier.padding(start = inset, end = inset, top = 12.dp),
                )
            }
            shownItems.isEmpty() -> {
                Text(
                    "Nothing matches these filters.",
                    style = CoogType.heroPlot,
                    color = CoogTextMuted,
                    modifier = Modifier.padding(start = inset, end = inset, top = 12.dp),
                )
            }
            touch -> {
                BrowsePosterGrid(
                    items = shownItems,
                    jobs = jobs,
                    library = library,
                    inset = inset,
                    onOpen = onOpen,
                    onManageLibrary = onManageLibrary,
                    loadingMore = loadingMore,
                    onNearEnd = { loadMoreBrowse() },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
            else -> {
                val contentWidth = LocalConfiguration.current.screenWidthDp.dp - inset * 2
                val cardMetrics = rememberShelfCardMetrics(contentWidth)
                FeaturedCarousel(
                    items = shownItems,
                    onOpen = onOpen,
                    jobs = jobs,
                    library = library,
                    expanded = true,
                    active = browseActive && focusRow == BrowseFocusRow.Shelf && !libraryMenuOpen,
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
                    onCardMenu = onManageLibrary?.let { manage ->
                        { item ->
                            if (item.canManageLibrary()) manage(item)
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ChipRow(
    inset: androidx.compose.ui.unit.Dp,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(if (BuildConfig.TOUCH_CLIENT) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(start = inset, end = inset),
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
private fun ChipRowDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .height(18.dp)
            .width(1.dp)
            .background(Color.White.copy(alpha = 0.22f)),
    )
}

@Composable
private fun BrowsePosterGrid(
    items: List<MediaItem>,
    jobs: List<JobItem>,
    library: List<MediaItem>,
    inset: androidx.compose.ui.unit.Dp,
    onOpen: (MediaItem) -> Unit,
    onManageLibrary: ((MediaItem) -> Unit)?,
    loadingMore: Boolean = false,
    onNearEnd: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val widthDp = LocalConfiguration.current.screenWidthDp
    // Dense grid (not carousel peek metrics) so Browse shows many titles at once.
    val cardWidth = when {
        widthDp < 600 -> 112.dp
        widthDp < 1000 -> 120.dp
        else -> 128.dp
    }
    val cardHeight = cardWidth * 1.5f
    val gap = 10.dp
    val gridState = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = info.totalItemsCount
            total > 0 && last >= total - 6
        }
    }
    LaunchedEffect(nearEnd, items.size) {
        if (nearEnd) onNearEnd()
    }
    val bottomPad = if (BuildConfig.TOUCH_CLIENT) mobileDockClearance() + 8.dp else 12.dp
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = cardWidth),
        state = gridState,
        modifier = modifier.padding(horizontal = inset),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = bottomPad),
    ) {
        if (items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Nothing matches these filters.",
                    style = CoogType.heroPlot,
                    color = CoogTextMuted,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        } else {
            items(
                count = items.size,
                key = { index ->
                    val item = items[index]
                    val base = item.playableId().ifBlank { item.id }.ifBlank { "item" }
                    "$base#$index"
                },
            ) { index ->
                val item = items[index]
                PosterCard(
                    item = item,
                    title = item.headline(),
                    subtitle = item.year.takeIf { it > 0 }?.toString().orEmpty(),
                    onClick = { onOpen(item) },
                    onLongClick = onManageLibrary
                        ?.takeIf { item.canManageLibrary() }
                        ?.let { manage -> { manage(item) } },
                    mark = item.cardMark(jobs, library),
                    compact = false,
                    width = cardWidth,
                    height = cardHeight,
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
}

private suspend fun loadBrowseItems(
    touch: Boolean,
    kind: String,
    typeFilter: String,
    sourceFilter: String,
    sort: String,
    genreIds: List<Int>,
    yearMin: Int,
    yearMax: Int,
    mood: String,
    minRating: Double,
    library: List<MediaItem>,
    api: CoogApi,
    page: Int = 1,
): List<MediaItem> {
    if (touch && sourceFilter == "local") {
        return filterLocalLibrary(library, typeFilter, yearMin, yearMax, minRating)
    }

    val pageCount = if (touch) 2 else 1
    val catalog = if (!touch) {
        api.catalogBrowse(
            kind = kind.ifBlank { "movie" },
            sort = sort,
            genreIds = genreIds,
            yearMin = yearMin,
            yearMax = yearMax,
            mood = mood,
            minRating = minRating,
            page = page,
            pageCount = pageCount,
        )
    } else {
        when (typeFilter) {
            "movie", "series" -> api.catalogBrowse(
                kind = typeFilter,
                sort = sort,
                genreIds = genreIds,
                yearMin = yearMin,
                yearMax = yearMax,
                mood = mood,
                minRating = minRating,
                page = page,
                pageCount = pageCount,
            )
            else -> coroutineScope {
                val movies = async {
                    api.catalogBrowse(
                        kind = "movie",
                        sort = sort,
                        genreIds = genreIds,
                        yearMin = yearMin,
                        yearMax = yearMax,
                        mood = mood,
                        minRating = minRating,
                        page = page,
                        pageCount = pageCount,
                    )
                }
                val series = async {
                    // Same facets as movies — dropping genres/mood filled the grid with
                    // unfiltered series whenever type = All.
                    api.catalogBrowse(
                        kind = "series",
                        sort = sort,
                        genreIds = genreIds,
                        yearMin = yearMin,
                        yearMax = yearMax,
                        mood = mood,
                        minRating = minRating,
                        page = page,
                        pageCount = pageCount,
                    )
                }
                zipBrowseResults(movies.await(), series.await())
            }
        }
    }

    val filtered = if (!touch || sourceFilter != "stream") {
        catalog
    } else {
        val localKeys = library.localCatalogKeys()
        catalog.filter { item ->
            !item.isLocal() && item.catalogMatchKey() !in localKeys
        }
    }
    return filtered.dedupeBrowseKeys()
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

/** Drop duplicate catalog/library rows (imdb/tmdb/playable) so LazyGrid keys stay unique. */
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
    val chipHeight = if (BuildConfig.TOUCH_CLIENT) 34.dp else 32.dp
    val container = when {
        selected && focused -> Color.White
        selected -> Color.White.copy(alpha = 0.22f)
        focused -> Color.White.copy(alpha = 0.16f)
        else -> Color.White.copy(alpha = 0.08f)
    }
    val labelColor =
        if (selected && focused) Color(0xFF121214) else Color.White.copy(alpha = 0.92f)
    val chipMod = modifier
        .height(chipHeight)
        .onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocused?.invoke()
        }
    @Composable
    fun ChipLabel() {
        Box(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 14.dp),
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
                    color = labelColor,
                )
                if (mark != null) {
                    StatusMark(mark = mark, size = MarkSize.Compact)
                }
            }
        }
    }
    if (BuildConfig.TOUCH_CLIENT) {
        CoogClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            shape = RoundedCornerShape(50),
            color = container,
            modifier = chipMod,
        ) {
            ChipLabel()
        }
    } else {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = container,
                contentColor = if (selected && focused) Color(0xFF121214) else Color.White,
                focusedContainerColor = if (selected) Color.White else Color.White.copy(alpha = 0.16f),
                focusedContentColor = if (selected) Color(0xFF121214) else Color.White,
                pressedContainerColor = Color.White.copy(alpha = 0.24f),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
            modifier = chipMod,
        ) {
            ChipLabel()
        }
    }
}
