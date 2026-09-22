package tv.coog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
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
import kotlinx.coroutines.launch
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
    onManageLibrary: ((MediaItem) -> Unit)? = null,
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
                    CatalogRow(label = "Movies", items = movies, onOpen = onOpenTitle, jobs = jobs, library = library, insetStart = 0.dp, onCardMenu = onManageLibrary)
                }
            }
            if (series.isNotEmpty()) {
                item(key = "series") {
                    CatalogRow(label = "Series", items = series, onOpen = onOpenTitle, jobs = jobs, library = library, insetStart = 0.dp, onCardMenu = onManageLibrary)
                }
            }
            if (people.isNotEmpty()) {
                item(key = "people") {
                    val firstPersonFocus = remember { FocusRequester() }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("People", style = CoogType.shelfTitle)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.focusRestorer(firstPersonFocus),
                        ) {
                            itemsIndexed(people, key = { index, person -> "${person.tmdbId}-$index" }) { index, person ->
                                PersonChip(
                                    person = person,
                                    onClick = { onOpenPerson(person) },
                                    modifier = if (index == 0) {
                                        Modifier.focusRequester(firstPersonFocus)
                                    } else {
                                        Modifier
                                    },
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

@Composable
fun PersonScreen(
    person: PersonSummary,
    jobs: List<JobItem>,
    library: List<MediaItem> = emptyList(),
    onBack: () -> Unit,
    onOpenTitle: (MediaItem) -> Unit,
    onManageLibrary: ((MediaItem) -> Unit)? = null,
) {
    val server = LocalCoogServer.current
    var details by remember(person.tmdbId) { mutableStateOf(person) }
    var error by remember(person.tmdbId) { mutableStateOf<String?>(null) }
    var loading by remember(person.tmdbId) { mutableStateOf(person.credits.isEmpty()) }
    val backFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
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

    fun scrollToTop() {
        scope.launch { runCatching { listState.scrollToItem(0) } }
    }

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
    }
    val dept = details.knownForDepartment.ifBlank { person.knownForDepartment }
    val counts = listOfNotNull(
        movies.size.takeIf { it > 0 }?.let { if (it == 1) "1 movie" else "$it movies" },
        series.size.takeIf { it > 0 }?.let { if (it == 1) "1 series" else "$it series" },
    ).joinToString("  ·  ")
    val metaRows = remember(details.birthday, details.placeOfBirth, dept) {
        personMetaRows(
            birthday = details.birthday,
            birthplace = details.placeOfBirth,
            department = dept,
        )
    }

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
            val hasLowerShelves = movies.isNotEmpty() || series.isNotEmpty()
            // First page = hero + Known for; lower shelves scroll underneath.
            val heroBlockHeight = if (hasLowerShelves && knownFor.isNotEmpty()) {
                pageHeight * 0.62f
            } else if (knownFor.isNotEmpty()) {
                pageHeight * 0.62f
            } else {
                pageHeight
            }
            val posterHeight = minOf(248.dp, (heroBlockHeight - 48.dp) * 0.72f)
            val posterWidth = posterHeight * (248f / 372f)

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = false,
            ) {
                item(key = "page0") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(pageHeight),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(heroBlockHeight)
                                .padding(start = 72.dp, end = 40.dp, top = 28.dp, bottom = 6.dp),
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
                                    modifier = Modifier
                                        .focusRequester(backFocus)
                                        .onFocusChanged { if (it.isFocused) scrollToTop() },
                                )
                            }
                            if (metaRows.isNotEmpty()) {
                                PersonMetaSideCard(
                                    rows = metaRows,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    fillHeight = true,
                                )
                            }
                        }
                        if (knownFor.isNotEmpty()) {
                            CatalogRow(
                                label = "Known for",
                                items = knownFor,
                                onOpen = onOpenTitle,
                                jobs = jobs,
                                library = library,
                                insetStart = 72.dp,
                                compact = true,
                                onFocused = { scrollToTop() },
                                onCardMenu = onManageLibrary,
                            )
                        }
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
                            onCardMenu = onManageLibrary,
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
                            onCardMenu = onManageLibrary,
                        )
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
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            focusedContainerColor = Color.White.copy(alpha = 0.14f),
            focusedContentColor = Color.White,
            pressedContainerColor = Color.White.copy(alpha = 0.18f),
            pressedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        modifier = modifier
            .width(132.dp)
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (focused) {
                    Modifier.border(2.dp, Color.White, RoundedCornerShape(16.dp))
                } else {
                    Modifier
                },
            ),
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
                modifier = Modifier
                    .size(108.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f)),
            )
            Text(
                person.name,
                style = CoogType.cardTitle,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
