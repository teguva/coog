package tv.coog.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backspace
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
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
import tv.coog.app.ui.theme.CoogGlass
import tv.coog.app.ui.theme.CoogText
import tv.coog.app.ui.theme.CoogTextMuted
import tv.coog.app.ui.theme.CoogTextSecondary
import tv.coog.app.ui.theme.CoogType

private fun Modifier.consumeDpad(
    key: Key,
    onDown: () -> Boolean,
): Modifier = onPreviewKeyEvent { event ->
    if (event.key != key) return@onPreviewKeyEvent false
    // Only consume when the handler claims the move. Returning true on an unhandled
    // KeyDown (old else-branch) trapped focus on Search people chips — Up never left.
    when (event.type) {
        KeyEventType.KeyDown -> onDown()
        else -> false
    }
}

/**
 * Alder-style search: remote keyboard on the left, result shelves on the right.
 * System IME stays closed — typing is owned by the on-screen keys (and hardware).
 */
@Composable
fun SearchScreen(
    jobs: List<JobItem>,
    library: List<MediaItem> = emptyList(),
    onOpenTitle: (MediaItem) -> Unit,
    onOpenPerson: (PersonSummary) -> Unit,
    onManageLibrary: ((MediaItem) -> Unit)? = null,
) {
    val server = LocalCoogServer.current
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var numeric by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<tv.coog.app.data.SearchResponse?>(null) }
    var recent by remember { mutableStateOf(loadRecentSearches(context)) }
    val queryBarFocus = remember { FocusRequester() }
    val clearFocus = remember { FocusRequester() }
    val firstKeyFocus = remember { FocusRequester() }
    val voiceFocus = remember { FocusRequester() }
    val firstResultFocus = remember { FocusRequester() }
    var enterResultsToken by remember { mutableIntStateOf(0) }
    val enterRail = LocalEnterRail.current

    fun focusLeadResults(): Boolean {
        enterResultsToken += 1
        return runCatching { firstResultFocus.requestFocus() }.getOrDefault(false)
    }
    val voice = rememberVoiceSearch(
        onResult = { spoken ->
            query = spoken
        },
        onError = { },
    )
    // Prefer mic on open / leaveRail; fall back to Q when voice isn't available.
    // Reuse AppShell content focus so Down-from-nav hits the same control.
    val shellContentFocus = LocalBrowseContentFocus.current
    val openFocus = when {
        voice.available && shellContentFocus != null -> shellContentFocus
        voice.available -> voiceFocus
        shellContentFocus != null -> shellContentFocus
        else -> firstKeyFocus
    }

    fun focusKeyboard(): Boolean =
        runCatching { openFocus.requestFocus() }.getOrDefault(false) ||
            runCatching { firstKeyFocus.requestFocus() }.getOrDefault(false)

    fun focusQueryBar(): Boolean =
        runCatching { queryBarFocus.requestFocus() }.getOrDefault(false)

    // Do not autofocus the keyboard/mic when Search mounts — stay on the nav pill until
    // Down / OK (AppShell leaveRail / enterTab → LocalBrowseContentFocus / openFocus).

    LaunchedEffect(query, server.url, server.token) {
        val q = query.trim()
        if (q.isEmpty()) {
            result = null
            error = null
            loading = false
            return@LaunchedEffect
        }
        if (q.length < 2) {
            result = null
            error = null
            loading = false
            return@LaunchedEffect
        }
        delay(500)
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

    fun append(ch: String) {
        query += ch
    }

    fun delete() {
        if (query.isNotEmpty()) query = query.dropLast(1)
    }

    fun clearQuery() {
        query = ""
        result = null
    }

    fun rememberQuery(q: String) {
        val trimmed = q.trim()
        if (trimmed.isEmpty()) return
        recent = (listOf(trimmed) + recent.filterNot { it.equals(trimmed, ignoreCase = true) })
            .take(6)
        saveRecentSearches(context, recent)
    }

    val letterRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    val letters = letterRows.joinToString("")
    val symbols = "1234567890@#\$%&*()!?.,:/+"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CoogBgDeep)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.Backspace || event.key == Key.Delete -> {
                        delete()
                        true
                    }
                    event.key == Key.Spacebar -> {
                        append(" ")
                        true
                    }
                    else -> {
                        val code = event.utf16CodePoint
                        if (code in 32..126) {
                            append(code.toChar().toString())
                            true
                        } else {
                            false
                        }
                    }
                }
            },
    ) {
        // Full-bleed results under the keyboard; focused card sits past shelfInset.
        val railInset = catalogInset()
        val shelfInset = SearchKeyboardWidth + SearchRailGap
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = railInset),
        ) {
            SearchResultsPane(
                result = result,
                query = query,
                loading = loading,
                error = error,
                jobs = jobs,
                library = library,
                firstFocus = firstResultFocus,
                enterLeadToken = enterResultsToken,
                shelfInsetStart = shelfInset,
                onExitLeft = { focusKeyboard() },
                onOpenTitle = onOpenTitle,
                onOpenPerson = onOpenPerson,
                onManageLibrary = onManageLibrary,
                onRememberQuery = { rememberQuery(query) },
                modifier = Modifier.fillMaxSize(),
            )

            // Previous-card veil: opaque at left screen margin → clear before focused card.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(shelfInset)
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(
                            0f to CoogBgDeep,
                            0.28f to CoogBgDeep.copy(alpha = 0.88f),
                            0.58f to CoogBgDeep.copy(alpha = 0.45f),
                            0.82f to CoogBgDeep.copy(alpha = 0.14f),
                            1f to Color.Transparent,
                        ),
                    ),
            )

            // Keyboard UI over the veil (no solid fill — peeks show through the fade).
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(SearchKeyboardWidth)
                    .fillMaxHeight()
                    .padding(top = topBarHeight() + 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SearchQueryBar(
                    query = query,
                    resultCount = result?.let { it.movies.size + it.series.size + it.people.size }
                        ?.takeIf { query.trim().length >= 2 && it > 0 },
                    queryFocus = queryBarFocus,
                    clearFocus = clearFocus,
                    onExitUp = {
                        enterRail()
                        true
                    },
                    onExitDown = { focusKeyboard() },
                    onClear = {
                        clearQuery()
                        focusKeyboard()
                    },
                )

                SearchKeyboard(
                    numeric = numeric,
                    letterRows = letterRows,
                    letters = letters,
                    symbols = symbols,
                    firstKeyFocus = firstKeyFocus,
                    openFocus = openFocus,
                    onExitUp = { focusQueryBar() },
                    onExitRight = {
                        val hit = result ?: return@SearchKeyboard false
                        if (hit.movies.isEmpty() && hit.series.isEmpty() && hit.people.isEmpty()) {
                            return@SearchKeyboard false
                        }
                        focusLeadResults()
                    },
                    onToggleMode = { numeric = !numeric },
                    onAppend = { append(it) },
                    onDelete = { delete() },
                    onSpace = { append(" ") },
                    voiceAvailable = voice.available,
                    voiceListening = voice.listening,
                    onVoice = {
                        if (voice.listening) voice.stop() else voice.start()
                    },
                )

                if (recent.isNotEmpty()) {
                    Text("RECENT", style = CoogType.shelfTitle, color = CoogTextMuted)
                    recent.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEachIndexed { chipIndex, entry ->
                                val isRightEdge = chipIndex == pair.lastIndex
                                SearchChip(
                                    label = entry,
                                    onClick = {
                                        query = entry
                                        rememberQuery(entry)
                                    },
                                    onLongClick = {
                                        recent = recent.filterNot { it == entry }
                                        saveRecentSearches(context, recent)
                                    },
                                    onExitRight = if (isRightEdge) {
                                        {
                                            val hit = result
                                            if (hit == null ||
                                                (hit.movies.isEmpty() &&
                                                    hit.series.isEmpty() &&
                                                    hit.people.isEmpty())
                                            ) {
                                                false
                                            } else {
                                                focusLeadResults()
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                    Text(
                        "Hold OK on a chip to remove",
                        style = CoogType.cardYear,
                        color = CoogTextMuted,
                    )
                }
            }
        }
    }
}

private val SearchFieldShape = RoundedCornerShape(10.dp)
private val SearchKeyShape = RoundedCornerShape(7.dp)
private val SearchFocusRing = Color.White.copy(alpha = 0.9f)
/** Left rail width — shelves peek under this; fade spans to the focused card. */
internal val SearchKeyboardWidth = 300.dp
private val SearchRailGap = 24.dp

@Composable
private fun SearchQueryBar(
    query: String,
    resultCount: Int?,
    queryFocus: FocusRequester,
    clearFocus: FocusRequester,
    onExitUp: () -> Boolean,
    onExitDown: () -> Boolean,
    onClear: () -> Unit,
) {
    var fieldFocused by remember { mutableStateOf(false) }
    var clearFocused by remember { mutableStateOf(false) }
    val showClear = query.isNotEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(CoogGlass)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            tint = CoogTextMuted,
            modifier = Modifier.size(18.dp),
        )
        // Focusable query field (Alder): Up → nav Search pill, Down → keyboard, Right → clear.
        CoogClickable(
            onClick = { /* caret / field only — typing is on-screen keys */ },
            shape = SearchFieldShape,
            color = if (fieldFocused) Color.White.copy(alpha = 0.10f) else Color.Transparent,
            focusedScale = 1f,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusRequester(queryFocus)
                .onFocusChanged { fieldFocused = it.isFocused }
                .consumeDpad(Key.DirectionUp, onDown = onExitUp)
                .consumeDpad(Key.DirectionDown, onDown = onExitDown)
                .consumeDpad(Key.DirectionRight) {
                    if (showClear) {
                        runCatching { clearFocus.requestFocus() }.getOrDefault(false)
                    } else {
                        false
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(SearchFieldShape)
                    .then(
                        if (fieldFocused) {
                            Modifier.border(1.dp, SearchFocusRing, SearchFieldShape)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text("Search", style = CoogType.heroPlot, color = CoogTextMuted)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            query,
                            style = CoogType.heroPlot.copy(color = CoogText),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // Standing caret while the field owns focus (Alder).
                        if (fieldFocused) {
                            Box(
                                Modifier
                                    .padding(start = 2.dp)
                                    .width(2.dp)
                                    .height(16.dp)
                                    .background(Color.White.copy(alpha = 0.85f)),
                            )
                        }
                    }
                }
            }
        }
        if (resultCount != null) {
            Text("$resultCount", style = CoogType.cardYear, color = CoogTextMuted)
        }
        if (showClear) {
            CoogClickable(
                onClick = onClear,
                shape = CircleShape,
                color = Color.Transparent,
                focusedScale = 1f,
                modifier = Modifier
                    .size(28.dp)
                    .focusRequester(clearFocus)
                    .onFocusChanged { clearFocused = it.isFocused }
                    .consumeDpad(Key.DirectionLeft) {
                        runCatching { queryFocus.requestFocus() }.getOrDefault(false)
                    }
                    .consumeDpad(Key.DirectionUp, onDown = onExitUp)
                    .consumeDpad(Key.DirectionDown, onDown = onExitDown),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .then(
                            if (clearFocused) Modifier.border(1.dp, SearchFocusRing, CircleShape)
                            else Modifier,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Clear",
                        tint = CoogTextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchKeyboard(
    numeric: Boolean,
    letterRows: List<String>,
    letters: String,
    symbols: String,
    firstKeyFocus: FocusRequester,
    openFocus: FocusRequester,
    onExitUp: () -> Boolean,
    onExitRight: () -> Boolean,
    onToggleMode: () -> Unit,
    onAppend: (String) -> Unit,
    onDelete: () -> Unit,
    onSpace: () -> Unit,
    voiceAvailable: Boolean,
    voiceListening: Boolean,
    onVoice: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CoogGlass)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        var keyIndex = 0
        letterRows.forEachIndexed { rowIndex, rowLetters ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                rowLetters.forEachIndexed { colIndex, letter ->
                    val index = keyIndex++
                    val label = if (numeric) {
                        symbols.getOrNull(index)?.toString() ?: "·"
                    } else {
                        letter.toString()
                    }
                    val rightEdge = colIndex == rowLetters.lastIndex
                    SearchKey(
                        label = label,
                        rightEdge = rightEdge,
                        onExitRight = onExitRight,
                        onExitUp = if (rowIndex == 0) onExitUp else null,
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .then(
                                if (rowIndex == 0 && colIndex == 0) {
                                    Modifier
                                        .focusRequester(firstKeyFocus)
                                        .then(
                                            // No mic: open / leaveRail lands on Q.
                                            if (!voiceAvailable) Modifier.focusRequester(openFocus)
                                            else Modifier,
                                        )
                                } else {
                                    Modifier
                                },
                            ),
                        onClick = { onAppend(label) },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            SearchKey(
                label = if (numeric) "ABC" else "123",
                modifier = Modifier.weight(3f).height(34.dp),
                onClick = onToggleMode,
            )
            SearchKey(
                label = "−",
                modifier = Modifier.weight(2f).height(34.dp),
                onClick = { onAppend("-") },
            )
            SearchKey(
                label = "⌫",
                rightEdge = true,
                onExitRight = onExitRight,
                modifier = Modifier.weight(5f).height(34.dp),
                onClick = onDelete,
                icon = {
                    Icon(
                        Icons.Outlined.Backspace,
                        contentDescription = "Delete",
                        tint = CoogTextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchKey(
                label = "SPACE",
                rightEdge = !voiceAvailable,
                onExitRight = onExitRight,
                modifier = Modifier.weight(8f).height(34.dp),
                onClick = onSpace,
            )
            if (voiceAvailable) {
                var micFocused by remember { mutableStateOf(false) }
                CoogClickable(
                    onClick = onVoice,
                    shape = SearchKeyShape,
                    color = if (voiceListening) {
                        Color(0xFF6EC8FF).copy(alpha = 0.28f)
                    } else {
                        Color.White.copy(alpha = 0.06f)
                    },
                    focusedScale = 1f,
                    modifier = Modifier
                        .weight(2f)
                        .height(34.dp)
                        .focusRequester(openFocus)
                        .onFocusChanged { micFocused = it.isFocused }
                        .consumeDpad(Key.DirectionRight, onDown = onExitRight)
                        .consumeDpad(Key.DirectionUp, onDown = onExitUp),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(SearchKeyShape)
                            .then(
                                if (micFocused) Modifier.border(1.dp, SearchFocusRing, SearchKeyShape)
                                else Modifier,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Mic,
                            contentDescription = "Voice search",
                            tint = if (voiceListening) Color(0xFF6EC8FF) else CoogTextMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            } else {
                Spacer(Modifier.weight(2f).height(34.dp))
            }
        }
    }
}

@Composable
private fun SearchKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rightEdge: Boolean = false,
    onExitRight: (() -> Boolean)? = null,
    onExitUp: (() -> Boolean)? = null,
    icon: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    CoogClickable(
        onClick = onClick,
        shape = SearchKeyShape,
        color = if (focused) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f),
        focusedScale = 1f,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (rightEdge && onExitRight != null) {
                    Modifier.consumeDpad(Key.DirectionRight, onDown = onExitRight)
                } else {
                    Modifier
                },
            )
            .then(
                if (onExitUp != null) {
                    Modifier.consumeDpad(Key.DirectionUp, onDown = onExitUp)
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(SearchKeyShape)
                .then(
                    if (focused) Modifier.border(1.dp, SearchFocusRing, SearchKeyShape)
                    else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null && label == "⌫") {
                icon()
            } else {
                Text(
                    label,
                    style = if (label.length > 1) CoogType.cardYear else CoogType.chip,
                    color = if (focused) CoogText else CoogTextSecondary,
                )
            }
        }
    }
}

@Composable
private fun SearchChip(
    label: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onExitRight: (() -> Boolean)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    CoogClickable(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.08f),
        modifier = Modifier
            .height(28.dp)
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (onExitRight != null) {
                    Modifier.consumeDpad(Key.DirectionRight, onDown = onExitRight)
                } else {
                    Modifier
                },
            )
            .then(
                if (focused) Modifier.border(1.dp, Color.White, RoundedCornerShape(50))
                else Modifier,
            ),
    ) {
        Text(
            label,
            style = CoogType.cardYear,
            color = CoogText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private fun loadRecentSearches(context: Context): List<String> =
    context.getSharedPreferences("coog_search", Context.MODE_PRIVATE)
        .getString("recent", "")
        .orEmpty()
        .split("\n")
        .filter { it.isNotBlank() }

private fun saveRecentSearches(context: Context, values: List<String>) {
    context.getSharedPreferences("coog_search", Context.MODE_PRIVATE)
        .edit()
        .putString("recent", values.joinToString("\n"))
        .apply()
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
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(key = "hero") {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .padding(start = 72.dp, end = 48.dp, top = topBarHeight() + 12.dp),
                ) {
                    val posterWidth = 148.dp
                    val posterHeight = 222.dp
                    Row(
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

