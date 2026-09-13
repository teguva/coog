package tv.coog.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import tv.coog.app.data.CoogApi
import tv.coog.app.data.JobItem
import tv.coog.app.data.MediaItem
import tv.coog.app.data.PersonSummary
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogTextMuted
import tv.coog.app.ui.theme.CoogTextSecondary
import tv.coog.app.ui.theme.CoogType

@Composable
fun SearchScreen(
    jobs: List<JobItem>,
    library: List<MediaItem> = emptyList(),
    onOpenTitle: (MediaItem) -> Unit,
    onOpenPerson: (PersonSummary) -> Unit,
) {
    val server = LocalCoogServer.current
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<tv.coog.app.data.SearchResponse?>(null) }
    val fieldFocus = LocalBrowseContentFocus.current ?: remember { FocusRequester() }
    val voice = rememberVoiceSearch(
        onResult = { spoken ->
            voiceError = null
            query = spoken
        },
        onError = { message -> voiceError = message },
    )
    LaunchedEffect(query, server.url, server.token) {
        val q = query.trim()
        if (q.length < 2) {
            result = null
            error = null
            loading = false
            return@LaunchedEffect
        }
        delay(350)
        loading = true
        error = null
        try {
            val got = CoogApi(server.url, server.token).catalogSearch(q)
            result = got
            error = got.error.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            error = e.message ?: "Search failed"
        } finally {
            loading = false
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(CoogBgDeep)
            .padding(start = catalogInset(), top = topBarHeight() + 6.dp, end = catalogInset(), bottom = 32.dp)
            .onPreviewKeyEvent { event ->
                val voiceKey = event.key == Key.Search ||
                    event.key == Key.VoiceAssist ||
                    event.key == Key.Assist
                if (!voiceKey || !voice.available) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    voiceError = null
                    if (voice.listening) voice.stop() else voice.start()
                }
                true
            },
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Search", style = CoogType.screenTitle)
                Text(
                    when {
                        voice.listening -> "Listening… speak now with the remote mic."
                        voice.available ->
                            "Movies, series, and people. Type, press the mic, or use the remote voice button."
                        else -> "Movies, series, and people. Type with the TV keyboard."
                    },
                    style = CoogType.heroPlot,
                    color = CoogTextSecondary,
                )
            }
        }
        item(key = "field") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TvTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = when {
                        voice.listening && voice.partial.isNotBlank() -> voice.partial
                        voice.listening -> "Listening…"
                        else -> "Search titles or actors"
                    },
                    exitUp = true,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(fieldFocus),
                )
                if (voice.available) {
                    VoiceSearchButton(
                        listening = voice.listening,
                        onClick = {
                            voiceError = null
                            if (voice.listening) voice.stop() else voice.start()
                        },
                    )
                }
            }
        }
        if (voice.listening && voice.partial.isNotBlank()) {
            item(key = "voice-partial") {
                Text(
                    voice.partial,
                    style = CoogType.heroPlot,
                    color = Color.White.copy(alpha = 0.88f),
                )
            }
        }
        if (voiceError != null && !voice.listening) {
            item(key = "voice-error") {
                Text(voiceError ?: "", color = Color(0xFFFF8B8B))
            }
        }
        if (query.trim().length < 2 && result == null && !voice.listening) {
            item(key = "hint") {
                Text(
                    if (voice.available) {
                        "Start typing, press the mic button, or use the remote voice key."
                    } else {
                        "Start typing a title or actor name."
                    },
                    style = CoogType.heroPlot,
                    color = CoogTextSecondary,
                )
            }
        }
        if (loading && result == null) {
            item(key = "loading") { Text("Searching…", style = CoogType.heroPlot) }
        }
        if (error != null && result == null) {
            item(key = "error") { Text(error ?: "", color = Color(0xFFFF8B8B)) }
        }
        result?.let { hit ->
            val movies = hit.movies
            val series = hit.series
            val people = hit.people
            if (error != null) {
                item(key = "warn") { Text(error ?: "", color = Color(0xFFFF8B8B)) }
            }
            if (loading) {
                item(key = "refreshing") {
                    Text("Updating…", style = CoogType.cardYear, color = CoogTextMuted)
                }
            }
            if (movies.isEmpty() && series.isEmpty() && people.isEmpty() && error == null && !loading) {
                item(key = "empty") { Text("No matches.", style = CoogType.heroPlot) }
            }
            if (movies.isNotEmpty()) {
                item(key = "movies") {
                    CatalogRow(label = "Movies", items = movies, onOpen = onOpenTitle, jobs = jobs, library = library, insetStart = 0.dp)
                }
            }
            if (series.isNotEmpty()) {
                item(key = "series") {
                    CatalogRow(label = "Series", items = series, onOpen = onOpenTitle, jobs = jobs, library = library, insetStart = 0.dp)
                }
            }
            if (people.isNotEmpty()) {
                item(key = "people") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("People", style = CoogType.shelfTitle)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            itemsIndexed(people, key = { index, person -> "${person.tmdbId}-$index" }) { _, person ->
                                PersonChip(
                                    person = person,
                                    onClick = { onOpenPerson(person) },
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
private fun VoiceSearchButton(
    listening: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (listening) {
                Color(0xFF6EC8FF).copy(alpha = 0.28f)
            } else {
                Color.White.copy(alpha = 0.08f)
            },
            contentColor = if (listening) Color(0xFF6EC8FF) else Color.White.copy(alpha = 0.88f),
            focusedContainerColor = if (listening) Color(0xFF6EC8FF) else Color.White,
            focusedContentColor = Color(0xFF121214),
            pressedContainerColor = Color.White.copy(alpha = 0.92f),
            pressedContentColor = Color(0xFF121214),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        modifier = Modifier.size(56.dp),
    ) {
        Icon(
            Icons.Outlined.Mic,
            contentDescription = if (listening) "Stop voice search" else "Voice search",
            tint = LocalContentColor.current,
            modifier = Modifier
                .size(26.dp)
                .align(Alignment.Center),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PersonScreen(
    person: PersonSummary,
    jobs: List<JobItem>,
    library: List<MediaItem> = emptyList(),
    onBack: () -> Unit,
    onOpenTitle: (MediaItem) -> Unit,
) {
    val server = LocalCoogServer.current
    var details by remember(person.tmdbId) { mutableStateOf(person) }
    var error by remember(person.tmdbId) { mutableStateOf<String?>(null) }
    var loading by remember(person.tmdbId) { mutableStateOf(person.credits.isEmpty()) }
    val backFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val movies = remember(details.credits) {
        details.credits.filter { it.kind != "series" && it.kind != "episode" }
            .sortedByDescending { it.year }
    }
    val series = remember(details.credits) {
        details.credits.filter { it.kind == "series" || it.kind == "episode" }
            .sortedByDescending { it.year }
    }
    val knownFor = remember(details.credits) { details.credits.take(8) }
    val hero = knownFor.firstOrNull { it.backdropUrl.isNotBlank() } ?: knownFor.firstOrNull()

    LaunchedEffect(person.tmdbId, server.url) {
        if (person.tmdbId == 0) return@LaunchedEffect
        loading = details.credits.isEmpty()
        try {
            details = CoogApi(server.url, server.token).catalogPerson(person.tmdbId)
            error = null
        } catch (e: Exception) {
            error = e.message ?: "Could not load credits"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(person.tmdbId) {
        listState.scrollToItem(0)
        runCatching { backFocus.requestFocus() }
        // #region agent log
        coogDebug(
            "H",
            "PersonScreen.kt:enter",
            "focus back",
            mapOf("id" to person.tmdbId),
            runId = "post-fix",
        )
        delay(450)
        coogDebug(
            "H",
            "PersonScreen.kt:enter",
            "scroll after settle",
            mapOf(
                "index" to listState.firstVisibleItemIndex,
                "offset" to listState.firstVisibleItemScrollOffset,
            ),
            runId = "post-fix",
        )
        // #endregion
    }
    val dept = details.knownForDepartment.ifBlank { person.knownForDepartment }
    val born = personBornLine(details.birthday, details.placeOfBirth)
    val counts = listOfNotNull(
        movies.size.takeIf { it > 0 }?.let { if (it == 1) "1 movie" else "$it movies" },
        series.size.takeIf { it > 0 }?.let { if (it == 1) "1 series" else "$it series" },
    ).joinToString("  ·  ")

    Box(modifier = Modifier.fillMaxSize().background(CoogBgDeep)) {
        if (hero != null) {
            PosterArt(item = hero, kind = ArtKind.Backdrop, preferDisplay = true, modifier = Modifier.fillMaxSize())
        }
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to CoogBgDeep.copy(alpha = 0.96f),
                    0.48f to CoogBgDeep.copy(alpha = 0.78f),
                    0.82f to CoogBgDeep.copy(alpha = 0.42f),
                ),
            ),
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to CoogBgDeep.copy(alpha = 0.18f),
                    0.55f to Color.Transparent,
                    1f to CoogBgDeep,
                ),
            ),
        )
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val pageHeight = maxHeight
            val hasShelves = knownFor.isNotEmpty() || movies.isNotEmpty() || series.isNotEmpty()
            val heroHeight = if (hasShelves) pageHeight * 0.70f else pageHeight
            val posterHeight = minOf(248.dp, (heroHeight - 48.dp) * 0.68f)
            val posterWidth = posterHeight * (248f / 372f)
            val stayOnScreen = remember {
                object : BringIntoViewSpec {
                    override fun calculateScrollDistance(
                        offset: Float,
                        size: Float,
                        containerSize: Float,
                    ): Float {
                        if (size >= containerSize * 0.55f) return 0f
                        val trailing = offset + size
                        if (offset >= 0f && trailing <= containerSize) return 0f
                        if (offset < 0f) return offset
                        if (trailing > containerSize) return trailing - containerSize
                        return 0f
                    }
                }
            }
            // #region agent log
            LaunchedEffect(pageHeight, knownFor.size, heroHeight) {
                coogDebug(
                    "J",
                    "PersonScreen.kt:layout",
                    "viewport",
                    mapOf(
                        "pageH" to pageHeight.value.toInt(),
                        "heroH" to heroHeight.value.toInt(),
                        "known" to knownFor.size,
                        "movies" to movies.size,
                        "series" to series.size,
                    ),
                    runId = "post-fix",
                )
            }
            LaunchedEffect(listState) {
                snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                    .collect { (idx, off) ->
                        if (idx != 0 || off != 0) {
                            coogDebug(
                                "I",
                                "PersonScreen.kt:scroll",
                                "scrolled",
                                mapOf("index" to idx, "offset" to off),
                                runId = "post-fix",
                            )
                        }
                    }
            }
            // #endregion
            CompositionLocalProvider(LocalBringIntoViewSpec provides stayOnScreen) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = false,
                ) {
                    item(key = "hero") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(heroHeight)
                                .padding(start = 72.dp, end = 40.dp, top = 28.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(32.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(posterWidth)
                                    .height(posterHeight)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.08f)),
                            ) {
                                val photo = details.profileUrl.ifBlank { person.profileUrl }
                                if (photo.isNotBlank()) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(photo)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = details.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Text(
                                        details.name.take(1).uppercase(),
                                        style = CoogType.heroTitle,
                                        modifier = Modifier.align(Alignment.Center),
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    details.name.ifBlank { person.name },
                                    style = CoogType.heroTitle.copy(fontSize = 30.sp, lineHeight = 34.sp),
                                    maxLines = 2,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (dept.isNotBlank()) {
                                        Text(
                                            dept,
                                            style = CoogType.chip,
                                            modifier = Modifier
                                                .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                                                .padding(horizontal = 10.dp, vertical = 5.dp),
                                        )
                                    }
                                    if (counts.isNotBlank()) {
                                        Text(counts, style = CoogType.heroTagline, color = CoogTextSecondary)
                                    }
                                }
                                if (born.isNotBlank()) {
                                    Text(born, style = CoogType.cardYear, color = CoogTextMuted)
                                }
                                if (details.biography.isNotBlank()) {
                                    Text(
                                        details.biography,
                                        style = CoogType.heroPlot.copy(fontSize = 14.sp, lineHeight = 20.sp),
                                        maxLines = 5,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                } else if (loading) {
                                    Text("Loading filmography…", style = CoogType.heroPlot, color = CoogTextMuted)
                                }
                                if (error != null) {
                                    Text(error ?: "", color = Color(0xFFFF8B8B))
                                }
                                GhostButton(
                                    label = "Back",
                                    onClick = onBack,
                                    modifier = Modifier.focusRequester(backFocus),
                                )
                            }
                        }
                    }
                    if (knownFor.isNotEmpty()) {
                        item(key = "known") {
                            CatalogRow(
                                label = "Known for",
                                items = knownFor,
                                onOpen = onOpenTitle,
                                jobs = jobs,
                                library = library,
                                insetStart = 72.dp,
                                compact = true,
                                onFocused = {
                                    // #region agent log
                                    coogDebug(
                                        "I",
                                        "PersonScreen.kt:knownFor",
                                        "known-for focus",
                                        mapOf(
                                            "index" to listState.firstVisibleItemIndex,
                                            "offset" to listState.firstVisibleItemScrollOffset,
                                        ),
                                        runId = "post-fix",
                                    )
                                    // #endregion
                                },
                            )
                        }
                    }
                    if (movies.isNotEmpty()) {
                        item(key = "movies") {
                            CatalogRow(
                                label = "Movies",
                                items = movies,
                                onOpen = onOpenTitle,
                                jobs = jobs,
                                library = library,
                                insetStart = 72.dp,
                                compact = true,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                    }
                    if (series.isNotEmpty()) {
                        item(key = "series") {
                            CatalogRow(
                                label = "Series",
                                items = series,
                                onOpen = onOpenTitle,
                                jobs = jobs,
                                library = library,
                                insetStart = 72.dp,
                                compact = true,
                                modifier = Modifier.padding(bottom = 28.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonChip(
    person: PersonSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF121214),
        ),
        modifier = modifier.width(132.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(person.profileUrl.ifBlank { null })
                    .crossfade(true)
                    .build(),
                contentDescription = person.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(108.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)),
            )
            Text(person.name, style = CoogType.cardTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun personBornLine(birthday: String, place: String): String {
    val date = formatPersonBirthday(birthday)
    return listOfNotNull(
        date?.let { "Born $it" },
        place.trim().takeIf { it.isNotBlank() },
    ).joinToString("  ·  ")
}

private fun formatPersonBirthday(raw: String): String? {
    val parts = raw.trim().split("-")
    if (parts.size != 3) return raw.trim().takeIf { it.isNotBlank() }
    val year = parts[0]
    val month = parts[1].toIntOrNull() ?: return raw
    val day = parts[2].toIntOrNull() ?: return raw
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val label = months.getOrNull(month - 1) ?: return raw
    return "$day $label $year"
}
