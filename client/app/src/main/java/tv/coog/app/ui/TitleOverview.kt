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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import tv.coog.app.data.CastMember
import tv.coog.app.data.CoogApi
import tv.coog.app.data.JobItem
import tv.coog.app.data.MediaItem
import tv.coog.app.data.PersonSummary
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogCached
import tv.coog.app.ui.theme.CoogTextMuted
import tv.coog.app.ui.theme.CoogTextSecondary
import tv.coog.app.ui.theme.CoogType

@Composable
fun TitleOverview(
    item: MediaItem,
    jobs: List<JobItem> = emptyList(),
    onPlay: (MediaItem) -> Unit,
    onSources: ((MediaItem) -> Unit)? = null,
    onOpenPerson: (PersonSummary) -> Unit = {},
    onBack: (() -> Unit)? = null,
    playFocus: FocusRequester? = null,
    pinPlayLeftToRail: Boolean = false,
    similar: List<MediaItem> = emptyList(),
    similarLabel: String = "Similar movies",
    onOpenSimilar: ((MediaItem) -> Unit)? = null,
    playError: String? = null,
    bottomShelf: (@Composable () -> Unit)? = null,
    extraShelf: (@Composable () -> Unit)? = null,
    episodes: List<MediaItem> = emptyList(),
    library: List<MediaItem> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val server = LocalCoogServer.current
    val maizeHeadshot: ((String) -> String)? = remember(server.url, server.token, server.adultSession) {
        if (server.adultSession.isBlank()) {
            null
        } else {
            val api = CoogApi(server.url, server.token, server.adultSession)
            val shot: (String) -> String = { slug -> api.maizeActorHeadshotUrl(slug) }
            shot
        }
    }
    val railFocus = LocalRailFocus.current
    val genres = item.heroGenres()
    val meta = item.heroMetaLine()
    val plot = item.heroDescription()
    val showSources = onSources != null && (!item.isLocal() || item.imdbId.isNotBlank()) &&
        (!item.playBlocked() || item.isLocal())
    val shelf = bottomShelf ?: if (similar.isNotEmpty() && onOpenSimilar != null) {
        {
            CatalogRow(
                label = similarLabel,
                items = similar,
                onOpen = onOpenSimilar,
                jobs = jobs,
                insetStart = 72.dp,
                compact = true,
            )
        }
    } else {
        null
    }
    Box(modifier = modifier.fillMaxSize().background(CoogBgDeep)) {
        PosterArt(
            item = item,
            kind = ArtKind.Backdrop,
            contentScale = ContentScale.Crop,
            alignment = Alignment.CenterEnd,
            preferDisplay = true,
            modifier = Modifier.fillMaxSize(),
        )
        // Overall dim so the raw backdrop never dominates the hero copy.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CoogBgDeep.copy(alpha = 0.40f)),
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0.00f to CoogBgDeep.copy(alpha = 0.92f),
                    0.32f to CoogBgDeep.copy(alpha = 0.55f),
                    0.62f to CoogBgDeep.copy(alpha = 0.22f),
                    1.00f to CoogBgDeep.copy(alpha = 0.18f),
                ),
            ),
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.00f to CoogBgDeep.copy(alpha = 0.20f),
                    0.55f to Color.Transparent,
                    1.00f to CoogBgDeep.copy(alpha = 0.88f),
                ),
            ),
        )
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val heroHeight = if (shelf != null || extraShelf != null) maxHeight * 0.70f else maxHeight
            val posterHeight = minOf(248.dp, (heroHeight - 48.dp) * 0.68f)
            val posterWidth = posterHeight * (228f / 342f)
            val firstCastFocus = remember { FocusRequester() }
            val people = remember(item.cast, item.performers, maizeHeadshot) {
                item.overviewCastMembers(maizeHeadshot)
            }
            val enterCast = people.isNotEmpty() || item.director.name.isNotBlank()
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item(key = "hero") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(heroHeight)
                            .padding(start = 72.dp, end = 40.dp, top = 28.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(posterWidth)
                                .height(posterHeight)
                                .clip(RoundedCornerShape(12.dp)),
                        ) {
                            PosterArt(
                                item = item,
                                kind = ArtKind.Poster,
                                mark = item.cardMark(jobs, library = library, episodes = episodes),
                                marksSize = MarkSize.Comfort,
                                preferDisplay = true,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            if (genres.isNotEmpty()) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(bottom = 8.dp),
                                ) {
                                    genres.take(3).forEach { chip ->
                                        Text(
                                            chip,
                                            style = CoogType.chip.copy(fontSize = 11.sp),
                                            modifier = Modifier
                                                .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(50))
                                                .padding(horizontal = 10.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                            }
                            TitleLockup(
                                item,
                                logoHeight = 48.dp,
                                titleStyle = CoogType.heroTitle.copy(
                                    fontSize = 30.sp,
                                    lineHeight = 34.sp,
                                    letterSpacing = (-0.4).sp,
                                ),
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            if (meta.isNotBlank() || item.matchPercent() != null) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                ) {
                                    item.matchPercent()?.let { MatchMark(percent = it, size = MarkSize.Comfort) }
                                    if (meta.isNotBlank()) {
                                        Text(
                                            meta,
                                            style = CoogType.heroTagline.copy(fontSize = 14.sp),
                                            color = CoogTextSecondary,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                            if (plot.isNotBlank()) {
                                Text(
                                    plot,
                                    style = CoogType.heroPlot.copy(fontSize = 14.sp, lineHeight = 20.sp),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(bottom = 16.dp).fillMaxWidth(0.90f),
                                )
                            }
                            if (playError != null) {
                                Text(
                                    friendlyPlayError(playError),
                                    color = Color(0xFFFF8B8B),
                                    modifier = Modifier.padding(bottom = 12.dp),
                                )
                            } else if (item.playBlocked()) {
                                Text(
                                    "Not released yet. Play is available when it comes out, or if you already have a local file.",
                                    color = Color(0xFFFF8B8B),
                                    modifier = Modifier.padding(bottom = 12.dp),
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                WhitePill(
                                    label = if (item.positionMs > 0) "Resume" else "Play",
                                    icon = Icons.Filled.PlayArrow,
                                    onClick = { onPlay(item) },
                                    modifier = Modifier
                                        .then(if (playFocus != null) Modifier.focusRequester(playFocus) else Modifier)
                                        .focusProperties {
                                            if (pinPlayLeftToRail && railFocus != null) {
                                                left = railFocus
                                            }
                                            if (!showSources && enterCast) {
                                                right = firstCastFocus
                                            }
                                        }
                                        .then(
                                            if (!showSources && enterCast) {
                                                Modifier.onPreviewKeyEvent { event ->
                                                    if (event.key != Key.DirectionRight) return@onPreviewKeyEvent false
                                                    if (event.type == KeyEventType.KeyDown) {
                                                        runCatching { firstCastFocus.requestFocus() }
                                                    }
                                                    event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
                                                }
                                            } else {
                                                Modifier
                                            },
                                        ),
                                )
                                if (showSources) {
                                    var sourcesBounds by remember { mutableStateOf("") }
                                    GhostButton(
                                        label = "Sources",
                                        onClick = { onSources?.invoke(item) },
                                        modifier = Modifier
                                            .then(
                                                if (enterCast) Modifier.focusProperties { right = firstCastFocus } else Modifier,
                                            )
                                            // #region agent log
                                            .onGloballyPositioned { coords ->
                                                val pos = coords.positionInWindow()
                                                sourcesBounds = "${pos.x.toInt()},${pos.y.toInt()},${coords.size.width},${coords.size.height}"
                                            }
                                            .onFocusChanged { state ->
                                                coogDebug(
                                                    "A",
                                                    "TitleOverview.kt:Sources",
                                                    "sources focus",
                                                    mapOf(
                                                        "focused" to state.isFocused,
                                                        "hasFocus" to state.hasFocus,
                                                        "bounds" to sourcesBounds,
                                                    ),
                                                )
                                            }
                                            .onPreviewKeyEvent { event ->
                                                if (event.key == Key.DirectionRight && event.type == KeyEventType.KeyDown) {
                                                    coogDebug(
                                                        "E",
                                                        "TitleOverview.kt:Sources.right",
                                                        "sources right",
                                                        mapOf("bounds" to sourcesBounds),
                                                    )
                                                }
                                                if (!enterCast || event.key != Key.DirectionRight) {
                                                    return@onPreviewKeyEvent false
                                                }
                                                if (event.type == KeyEventType.KeyDown) {
                                                    runCatching { firstCastFocus.requestFocus() }
                                                }
                                                event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
                                            },
                                            // #endregion
                                    )
                                }
                            }
                        }
                        OverviewSideCard(
                            item = item,
                            firstCastFocus = firstCastFocus,
                            maizeHeadshot = maizeHeadshot,
                            onOpenPerson = onOpenPerson,
                        )
                    }
                }
                if (shelf != null) {
                    item(key = "shelf") {
                        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            shelf()
                        }
                    }
                }
                if (extraShelf != null) {
                    item(key = "extra-shelf") {
                        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                            extraShelf()
                        }
                    }
                }
            }
        }
        if (onBack != null) {
            GlassCircleButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                label = "Back",
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 20.dp)
                    .then(
                        if (playFocus != null) {
                            Modifier
                                .focusProperties {
                                    down = playFocus
                                    right = playFocus
                                }
                                .onPreviewKeyEvent { event ->
                                    val toPlay = event.key == Key.DirectionDown || event.key == Key.DirectionRight
                                    if (!toPlay) return@onPreviewKeyEvent false
                                    if (event.type == KeyEventType.KeyDown) {
                                        runCatching { playFocus.requestFocus() }
                                    }
                                    true
                                }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

@Composable
private fun OverviewSideCard(
    item: MediaItem,
    onOpenPerson: (PersonSummary) -> Unit,
    firstCastFocus: FocusRequester,
    maizeHeadshot: ((String) -> String)? = null,
) {
    val people = remember(item.cast, item.performers, maizeHeadshot) {
        item.overviewCastMembers(maizeHeadshot)
    }
    val director = remember(item.director, maizeHeadshot) {
        if (maizeHeadshot == null) item.director else item.director.withMaizeHeadshot(maizeHeadshot)
    }
    // Maize scenes often have performers without TMDB / hasOfficialMeta.
    val showSide = item.rating > 0 ||
        director.name.isNotBlank() ||
        people.isNotEmpty() ||
        item.hasMeta
    if (!showSide) return
    Column(
        modifier = Modifier
            .width(200.dp)
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.End,
    ) {
        if (item.rating > 0) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    String.format("%.1f", item.rating),
                    color = CoogCached,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 32.sp,
                )
                Text(
                    "/10",
                    style = CoogType.heroTagline.copy(fontSize = 13.sp),
                    color = CoogTextMuted,
                    modifier = Modifier.padding(bottom = 4.dp, start = 3.dp),
                )
            }
        }
        if (director.name.isNotBlank() || people.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xD91C1C22))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (director.name.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("DIRECTOR", style = CoogType.cardYear, color = CoogTextMuted)
                        CastMini(
                            person = director,
                            debugIndex = -1,
                            debugRole = "director",
                            modifier = if (people.isEmpty()) Modifier.focusRequester(firstCastFocus) else Modifier,
                            onClick = {
                            onOpenPerson(
                                PersonSummary(
                                    tmdbId = director.tmdbId,
                                    name = director.name,
                                    profileUrl = director.profileUrl,
                                ),
                            )
                        })
                    }
                }
                if (people.isNotEmpty()) {
                    Text("CAST", style = CoogType.cardYear, color = CoogTextMuted)
                    people.forEachIndexed { index, person ->
                        CastMini(
                            person = person,
                            debugIndex = index,
                            debugRole = "cast",
                            modifier = if (index == 0) Modifier.focusRequester(firstCastFocus) else Modifier,
                            onClick = {
                            onOpenPerson(
                                PersonSummary(
                                    tmdbId = person.tmdbId,
                                    name = person.name,
                                    profileUrl = person.profileUrl,
                                ),
                            )
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun CastMini(
    person: CastMember,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    debugIndex: Int = 0,
    debugRole: String = "cast",
) {
    var bounds by remember { mutableStateOf("") }
    var loggedPos by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.16f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
            // #region agent log
            .onGloballyPositioned { coords ->
                val pos = coords.positionInWindow()
                bounds = "${pos.x.toInt()},${pos.y.toInt()},${coords.size.width},${coords.size.height}"
                if (!loggedPos && debugIndex <= 0) {
                    loggedPos = true
                    coogDebug(
                        "B",
                        "TitleOverview.kt:CastMini.pos",
                        "cast bounds",
                        mapOf(
                            "index" to debugIndex,
                            "role" to debugRole,
                            "name" to person.name,
                            "bounds" to bounds,
                        ),
                    )
                }
            }
            .onFocusChanged { state ->
                if (state.isFocused) {
                    coogDebug(
                        if (debugIndex == 0 && debugRole == "cast") "D" else "A",
                        "TitleOverview.kt:CastMini",
                        "cast focus",
                        mapOf(
                            "index" to debugIndex,
                            "role" to debugRole,
                            "name" to person.name,
                            "focused" to state.isFocused,
                            "hasFocus" to state.hasFocus,
                            "bounds" to bounds,
                        ),
                    )
                }
            },
            // #endregion
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f)),
            ) {
                if (person.profileUrl.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(person.profileUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = person.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Text(person.name, style = CoogType.cardTitle.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun GlassCircleButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.10f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF121214),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
            .size(32.dp)
            .border(
                1.dp,
                if (focused) Color.Transparent else Color.White.copy(alpha = 0.22f),
                CircleShape,
            )
            .onFocusChanged { focused = it.isFocused },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = LocalContentColor.current, modifier = Modifier.size(16.dp))
        }
    }
}
