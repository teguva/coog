package tv.coog.app.ui

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.coog.app.data.CoogApi
import tv.coog.app.data.JobItem
import tv.coog.app.data.MediaItem
import tv.coog.app.data.PersonSummary
import tv.coog.app.data.SearchResponse
import tv.coog.app.ui.alder.ViewBackedShelf
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogTextSecondary
import tv.coog.app.ui.theme.CoogType

private data class SearchShelf(
    val id: String,
    val label: String,
    val items: List<MediaItem>,
)

private const val PersonShelfId = "people"
private const val PersonMediaPrefix = "person:"

/** Prefer sharp TMDB profile tiers over search w185 thumbs. */
private fun sharpProfileUrl(url: String): String {
    if (url.isBlank()) return url
    return url
        .replace("/w45/", "/h632/")
        .replace("/w185/", "/h632/")
        .replace("/w342/", "/h632/")
        .replace("/w500/", "/h632/")
}

/** Stage hero — original profile, never a known-for title still. */
private fun stageProfileUrl(url: String): String {
    if (url.isBlank()) return url
    return sharpProfileUrl(url)
        .replace("/h632/", "/original/")
        .replace("/w780/", "/original/")
}

private fun PersonSummary.toShelfMedia(): MediaItem = MediaItem(
    id = "$PersonMediaPrefix$tmdbId",
    kind = "person",
    title = name,
    // Profile only — cards stay 1:1 (no billboard morph).
    posterUrl = sharpProfileUrl(profileUrl),
    backdropUrl = "",
    plot = knownForDepartment,
)

/** Stage plate for People — actor profile as backdrop (not known-for title art). */
private fun PersonSummary.toStageMedia(): MediaItem {
    val profile = stageProfileUrl(profileUrl)
    val birthYear = birthday.take(4).toIntOrNull()?.takeIf { it in 1800..2100 } ?: 0
    val knownFor = credits.map { it.title.trim() }.filter { it.isNotBlank() }.distinct().take(4)
    return MediaItem(
        id = "$PersonMediaPrefix$tmdbId",
        kind = "person",
        title = name,
        plot = biography,
        tagline = knownFor.joinToString(" · "),
        genres = listOfNotNull(knownForDepartment.takeIf { it.isNotBlank() }),
        country = placeOfBirth,
        year = birthYear,
        posterUrl = profile,
        backdropUrl = profile,
        matchStatus = "matched",
        tmdbId = tmdbId,
    )
}

private fun MediaItem.personTmdbIdOrNull(): Int? =
    id.removePrefix(PersonMediaPrefix).toIntOrNull()?.takeIf {
        id.startsWith(PersonMediaPrefix)
    }

private fun personIdTmdbOrNull(id: String): Int? =
    id.removePrefix(PersonMediaPrefix).toIntOrNull()?.takeIf {
        id.startsWith(PersonMediaPrefix)
    }

/**
 * Home-shaped results: Movies/Series are Continue-style (poster → 16:9 billboard + logo)
 * with [ImmersiveListStage] bleeding to the screen edge. People are fixed 1:1 tiles with
 * actor meta in the same stage band.
 */
@Composable
internal fun SearchResultsPane(
    result: SearchResponse?,
    query: String,
    loading: Boolean,
    error: String?,
    jobs: List<JobItem>,
    library: List<MediaItem>,
    firstFocus: FocusRequester,
    /** Bumped when keyboard/chips ask to land on the lead Movies/Series shelf. */
    enterLeadToken: Int = 0,
    /** Leading inset so the focused card clears the keyboard; peeks draw under it. */
    shelfInsetStart: Dp = 0.dp,
    onExitLeft: () -> Unit,
    onOpenTitle: (MediaItem) -> Unit,
    onOpenPerson: (PersonSummary) -> Unit,
    onManageLibrary: ((MediaItem) -> Unit)?,
    onRememberQuery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trimmed = query.trim()
    val movies = result?.movies.orEmpty()
    val series = result?.series.orEmpty()
    val people = result?.people.orEmpty()
    val peopleByTmdb = remember(people) { people.associateBy { it.tmdbId } }
    val shelves = remember(movies, series, people) {
        buildList {
            if (movies.isNotEmpty()) add(SearchShelf("movies", "Movies", movies))
            if (series.isNotEmpty()) add(SearchShelf("series", "Series", series))
            if (people.isNotEmpty()) {
                add(SearchShelf(PersonShelfId, "People", people.map { it.toShelfMedia() }))
            }
        }
    }
    val rowCount = shelves.size
    val server = LocalCoogServer.current

    val shelfFocus = remember(shelves.map { it.id }) {
        List(shelves.size) { i -> if (i == 0) firstFocus else FocusRequester() }
    }
    var focusedRow by remember { mutableIntStateOf(0) }
    var selectedIds by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var shelfHolding by remember { mutableStateOf(false) }
    var stageSelected by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var stageSettled by remember { mutableStateOf(false) }
    var personDetails by remember { mutableStateOf<Map<Int, PersonSummary>>(emptyMap()) }
    // Keyboard owns focus while typing; only steal when Right / chips enter results.
    var contentFocusHeld by remember { mutableStateOf(false) }
    var hydratedById by remember { mutableStateOf<Map<String, MediaItem>>(emptyMap()) }
    val railFocused = LocalNavBarFocused.current
    val browseActive = LocalBrowseActive.current
    val reportContentFocusRow = LocalReportContentFocusRow.current

    fun leadTitleRow(): Int =
        shelves.indexOfFirst { it.id != PersonShelfId }.let { if (it >= 0) it else 0 }

    LaunchedEffect(focusedRow) {
        reportContentFocusRow(focusedRow)
    }

    LaunchedEffect(rowCount) {
        if (rowCount == 0) contentFocusHeld = false
    }

    // Keyboard / recent chips → first title shelf (Movies/Series), never People.
    // Re-run when rowCount appears so a Right pressed during load still lands correctly.
    LaunchedEffect(enterLeadToken, rowCount) {
        if (enterLeadToken <= 0 || rowCount == 0) return@LaunchedEffect
        contentFocusHeld = true
        focusedRow = leadTitleRow()
        kotlinx.coroutines.yield()
        val target = shelfFocus.getOrNull(focusedRow) ?: firstFocus
        runCatching { target.requestFocus() }
        delay(60)
        runCatching { target.requestFocus() }
    }

    LaunchedEffect(shelves.map { it.id to it.items.map { m -> m.id } }) {
        selectedIds = selectedIds.filterKeys { id -> shelves.any { it.id == id } }
        hydratedById = hydratedById.filterKeys { id -> shelves.any { s -> s.items.any { it.id == id } } }
        focusedRow = focusedRow.coerceIn(0, (rowCount - 1).coerceAtLeast(0))
    }

    fun focusRequesterFor(row: Int): FocusRequester? = shelfFocus.getOrNull(row)

    fun leaveResultsToKeyboard() {
        contentFocusHeld = false
        onExitLeft()
    }

    LaunchedEffect(focusedRow, rowCount, railFocused, browseActive, contentFocusHeld) {
        if (!contentFocusHeld || !browseActive || railFocused || rowCount == 0) return@LaunchedEffect
        if (focusedRow > rowCount - 1) focusedRow = rowCount - 1
        val target = focusRequesterFor(focusedRow) ?: return@LaunchedEffect
        kotlinx.coroutines.yield()
        runCatching { target.requestFocus() }
    }

    val emptyMessage = when {
        trimmed.isEmpty() -> "Type with the on-screen keyboard. Results show here."
        trimmed.length == 1 -> "Keep typing…"
        loading && result == null -> null
        error != null && result == null -> error
        result != null && rowCount == 0 && !loading -> "No matches."
        else -> null
    }

    if (rowCount == 0) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(CoogBgDeep)
                .padding(top = topBarHeight() + 8.dp, end = 8.dp),
        ) {
            Text(
                emptyMessage ?: if (loading) "Searching…" else "",
                style = CoogType.heroPlot,
                color = CoogTextSecondary,
            )
        }
        return
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(CoogBgDeep)
            .clipToBounds(),
    ) {
        val viewport = maxHeight
        // Metrics from the clear content band (past the keyboard peek underlay).
        val contentWidth = (maxWidth - shelfInsetStart).coerceAtLeast(240.dp)
        val metrics = rememberContinueCardMetrics(contentWidth)
        val rowBandH = HomeShelfTitleBlock + metrics.height + 8.dp
        val gap = 10.dp

        val activeShelf = shelves.getOrNull(focusedRow)
        val onPeople = activeShelf?.id == PersonShelfId
        val trailerStageKey = when {
            activeShelf != null ->
                "row$focusedRow:${selectedIds[activeShelf.id].orEmpty()}"
            else -> "none"
        }
        LaunchedEffect(trailerStageKey, shelfHolding) {
            stageSettled = false
            if (shelfHolding) return@LaunchedEffect
            delay(HomeStageCommitMs)
            stageSelected = selectedIds
            stageSettled = true
        }

        val selectedPersonTmdb = if (onPeople) {
            val sid = stageSelected[PersonShelfId]
                ?: selectedIds[PersonShelfId]
                ?: activeShelf?.items?.firstOrNull()?.id.orEmpty()
            personIdTmdbOrNull(sid)
                ?: activeShelf?.items?.firstOrNull()?.personTmdbIdOrNull()
        } else {
            null
        }
        // Search hits omit bio — load person detail after settle for the stage meta block.
        LaunchedEffect(selectedPersonTmdb, stageSettled, server.url, server.token) {
            val tmdb = selectedPersonTmdb ?: return@LaunchedEffect
            if (!stageSettled || personDetails.containsKey(tmdb)) return@LaunchedEffect
            if (server.url.isBlank()) return@LaunchedEffect
            val got = runCatching {
                CoogApi(server.url, server.token).catalogPerson(tmdb)
            }.getOrNull() ?: return@LaunchedEffect
            personDetails = personDetails + (tmdb to got)
        }
        // Hydrate focused (±1) title rows so billboard logos + trailers get imdbId.
        LaunchedEffect(
            focusedRow,
            selectedIds,
            stageSettled,
            contentFocusHeld,
            shelves.map { it.id to it.items.map { m -> m.id } },
            server.url,
            server.token,
        ) {
            if (!contentFocusHeld || !stageSettled || server.url.isBlank()) return@LaunchedEffect
            val api = CoogApi(server.url, server.token)
            val focus = focusedRow.coerceIn(0, (rowCount - 1).coerceAtLeast(0))
            val order = buildList {
                add(focus)
                if (focus - 1 >= 0) add(focus - 1)
                if (focus + 1 < rowCount) add(focus + 1)
            }
            for (row in order) {
                val shelf = shelves.getOrNull(row) ?: continue
                if (shelf.id == PersonShelfId) continue
                val sel = selectedIds[shelf.id] ?: shelf.items.firstOrNull()?.id ?: continue
                val around = shelf.items.indexOfFirst { it.id == sel }.coerceAtLeast(0)
                val from = (around - 1).coerceAtLeast(0)
                val to = (around + 1).coerceAtMost(shelf.items.lastIndex)
                for (j in from..to) {
                    val raw = shelf.items[j]
                    val cur = hydratedById[raw.id] ?: raw
                    if (cur.imdbId.startsWith("tt") && cur.logoUrl.isNotBlank() && cur.canPlayTrailer()) {
                        continue
                    }
                    val remote = resolveImmersiveArt(api, raw) ?: continue
                    hydratedById = hydratedById + (raw.id to mergeDetails(raw, remote))
                }
            }
        }
        fun resolved(item: MediaItem): MediaItem = hydratedById[item.id] ?: item
        val stageItem = when {
            onPeople -> {
                val tmdb = selectedPersonTmdb
                val person = tmdb?.let { personDetails[it] ?: peopleByTmdb[it] }
                person?.toStageMedia()
            }
            else -> activeShelf?.let { shelf ->
                val id = stageSelected[shelf.id] ?: selectedIds[shelf.id] ?: shelf.items.first().id
                val raw = shelf.items.firstOrNull { it.id == id } ?: shelf.items.firstOrNull()
                raw?.let(::resolved)
            }
        }
        val showTrailerStage = stageItem != null

        val row0Clearance = topBarHeight() + 2.dp
        val cardH = metrics.height
        val cardPeek = cardH * HomeNeighbourCardFrac
        val rowBottomPad = 8.dp
        val prevPeek = rowBottomPad + cardPeek
        val nextPeek = HomeShelfTitleBlock + cardPeek
        val heights = List(rowCount) { rowBandH }
        val focusedH = heights.getOrElse(focusedRow) { rowBandH }
        val nextReserve = gap + nextPeek

        fun stageGapFor(available: Dp): Dp = when {
            available >= HomeTrailerStageHeight -> HomeTrailerStageHeight
            else -> available.coerceAtLeast(120.dp)
        }
        val topStageGap = stageGapFor(
            viewport - row0Clearance - focusedH - nextReserve,
        )
        val discoveryStageGap = stageGapFor(
            viewport - prevPeek - focusedH - nextReserve,
        )

        fun spacingBeforeFor(focus: Int, index: Int): Dp = when {
            index == 0 -> 0.dp
            focus > 0 && index == focus -> discoveryStageGap
            else -> gap
        }

        fun stageSlotFor(focus: Int): Int = when {
            focus > 0 -> focus
            else -> -1
        }

        fun offsetYFor(focus: Int): Dp = when {
            rowCount == 0 -> 0.dp
            focus <= 0 -> row0Clearance
            else -> {
                val yPrevEnd = (0 until focus).fold(0.dp) { acc, i ->
                    val space = if (i == 0) 0.dp else spacingBeforeFor(focus, i)
                    acc + space + heights.getOrElse(i) { 0.dp }
                }
                prevPeek - yPrevEnd
            }
        }

        val rowTransition = updateTransition(focusedRow, label = "search-row")
        val rowAnimSpec = tween<Dp>(HomeRowScrollMs, easing = EaseInOut)
        val offsetY by rowTransition.animateDp(
            transitionSpec = { rowAnimSpec },
            label = "search-row-scroll",
        ) { focus -> offsetYFor(focus) }
        val topStageH by rowTransition.animateDp(
            transitionSpec = { rowAnimSpec },
            label = "search-top-stage",
        ) { focus ->
            if (focus == 0) topStageGap else 0.dp
        }
        val animatedSpaces = List(8) { index ->
            rowTransition.animateDp(
                transitionSpec = { rowAnimSpec },
                label = "search-space-$index",
            ) { focus ->
                if (index < rowCount) spacingBeforeFor(focus, index) else 0.dp
            }.value
        }
        val stageSlot = stageSlotFor(focusedRow)

        val shelfActive = { row: Int ->
            contentFocusHeld && focusedRow == row && !railFocused && browseActive
        }
        fun moveRow(delta: Int): Boolean {
            val next = (focusedRow + delta).coerceIn(0, (rowCount - 1).coerceAtLeast(0))
            if (next != focusedRow) focusedRow = next
            return true
        }

        // Movies + Series both play trailers after settle; People never.
        // Hold off while the keyboard still owns focus (typing).
        val playTrailers = contentFocusHeld && browseActive && stageSettled && !onPeople
        val trailerPaused = shelfHolding
        val personMeta: (@Composable BoxScope.(MediaItem) -> Unit)? = if (onPeople) {
            { item -> SearchPersonStageMeta(item) }
        } else {
            null
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(viewport)
                .clipToBounds(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    .offset(y = offsetY),
            ) {
                if (showTrailerStage && topStageH > 0.dp) {
                    ImmersiveListStage(
                        item = stageItem,
                        inset = shelfInsetStart,
                        playTrailer = playTrailers,
                        trailerPaused = trailerPaused,
                        trailerDelayMs = HomeTrailerDelayMs,
                        loopTrailer = true,
                        showContentBlock = !onPeople,
                        metaContent = personMeta,
                        edgeFadeTop = HomeTrailerEdgeFade,
                        edgeFadeBottom = HomeTrailerEdgeFade,
                        cropBiasX = HomeTrailerCropBiasX,
                        cropBiasY = HomeTrailerCropBiasY,
                        metaInsetStart = shelfInsetStart,
                        metaInsetEnd = 24.dp,
                        metaWidthFraction = 0.72f,
                        metaMaxWidth = 720.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(topStageH),
                    )
                }
                shelves.forEachIndexed { i, shelf ->
                    val space = animatedSpaces.getOrElse(i) {
                        spacingBeforeFor(focusedRow, i)
                    }
                    if (space > 0.dp) {
                        if (showTrailerStage && i == stageSlot) {
                            ImmersiveListStage(
                                item = stageItem,
                                inset = shelfInsetStart,
                                playTrailer = playTrailers,
                                trailerPaused = trailerPaused,
                                trailerDelayMs = HomeTrailerDelayMs,
                                loopTrailer = true,
                                showContentBlock = !onPeople,
                                metaContent = personMeta,
                                edgeFadeTop = HomeTrailerEdgeFade,
                                edgeFadeBottom = HomeTrailerEdgeFade,
                                cropBiasX = HomeTrailerCropBiasX,
                                cropBiasY = HomeTrailerCropBiasY,
                                metaInsetStart = shelfInsetStart,
                                metaInsetEnd = 24.dp,
                                metaWidthFraction = 0.72f,
                                metaMaxWidth = 720.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(space),
                            )
                        } else {
                            Box(Modifier.fillMaxWidth().height(space))
                        }
                    }
                    val isPeople = shelf.id == PersonShelfId
                    // People: square 1:1 tiles, no focus widen / backdrop plate.
                    val peekW = if (isPeople) metrics.height else metrics.width
                    val focusW = if (isPeople) metrics.height else metrics.focusedWidth
                    val shelfItems = shelf.items.map(::resolved)
                    ViewBackedShelf(
                        items = shelfItems,
                        label = shelf.label,
                        inset = shelfInsetStart,
                        peekWidth = peekW,
                        focusedWidth = focusW,
                        height = metrics.height,
                        gap = metrics.gap,
                        continueBillboard = !isPeople,
                        active = shelfActive(i),
                        firstFocus = shelfFocus[i],
                        exitUp = i == 0,
                        jobs = if (isPeople) emptyList() else jobs,
                        library = if (isPeople) emptyList() else library,
                        initialSelectedId = selectedIds[shelf.id] ?: shelfItems.first().id,
                        onSettledSelectedId = { id ->
                            selectedIds = selectedIds + (shelf.id to id)
                        },
                        onHoldingChanged = { shelfHolding = it },
                        onOpen = { item ->
                            val tmdb = item.personTmdbIdOrNull()
                            if (tmdb != null) {
                                val person = peopleByTmdb[tmdb] ?: PersonSummary(
                                    tmdbId = tmdb,
                                    name = item.title,
                                    profileUrl = item.posterUrl,
                                )
                                onRememberQuery()
                                onOpenPerson(person)
                            } else {
                                onRememberQuery()
                                onOpenTitle(item)
                            }
                        },
                        onCardMenu = if (isPeople) null else onManageLibrary,
                        onRowFocused = { focusedRow = i },
                        onVerticalMove = ::moveRow,
                        onExitLeft = ::leaveResultsToKeyboard,
                        focusEnabled = contentFocusHeld && focusedRow == i,
                        modifier = Modifier
                            .fillMaxWidth()
                            .requiredHeight(rowBandH),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchPersonStageMeta(item: MediaItem) {
    val facts = buildList {
        item.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let { add(it) }
        item.country.takeIf { it.isNotBlank() }?.let { add(it) }
        item.year.takeIf { it > 0 }?.let { add(it.toString()) }
    }.joinToString("  ·  ")
    val knownFor = item.tagline.trim()
    val bio = item.plot.trim()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (facts.isNotBlank()) {
            Text(
                facts,
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            item.title,
            style = CoogType.heroTitle.copy(fontSize = 34.sp, lineHeight = 40.sp),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (knownFor.isNotBlank()) {
            Text(
                "Known for  ·  $knownFor",
                style = CoogType.heroTagline,
                color = Color.White.copy(alpha = 0.88f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (bio.isNotBlank()) {
            Text(
                bio,
                style = CoogType.heroPlot,
                color = Color.White.copy(alpha = 0.82f),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
