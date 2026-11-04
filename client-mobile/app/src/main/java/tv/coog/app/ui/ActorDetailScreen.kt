package tv.coog.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import tv.coog.app.BuildConfig
import tv.coog.app.data.ActorProfile
import tv.coog.app.data.ActorSimilar
import tv.coog.app.data.CoogApi
import tv.coog.app.data.MediaItem
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogTextMuted
import tv.coog.app.ui.theme.CoogType

@Composable
fun ActorDetailScreen(
    slug: String,
    onBack: () -> Unit,
    onOpenScene: (MediaItem) -> Unit,
    onOpenActor: (String) -> Unit,
) {
    val touch = BuildConfig.TOUCH_CLIENT
    val server = LocalCoogServer.current
    var profile by remember(slug) { mutableStateOf<ActorProfile?>(null) }
    var loading by remember(slug) { mutableStateOf(true) }
    var error by remember(slug) { mutableStateOf<String?>(null) }
    val backFocus = remember { FocusRequester() }
    val galleryReturnFocus = remember { FocusRequester() }
    var galleryIndex by remember(slug) { mutableStateOf<Int?>(null) }
    val inset = catalogInset()
    val bottomPad = if (touch) mobileDockClearance() + 8.dp else 40.dp

    BackHandler {
        if (galleryIndex != null) galleryIndex = null else onBack()
    }

    LaunchedEffect(slug, server.url, server.adultSession) {
        if (server.url.isBlank() || server.adultSession.isBlank() || slug.isBlank()) return@LaunchedEffect
        loading = true
        error = null
        runCatching { CoogApi(server.url, server.token, server.adultSession).maizeActor(slug) }
            .onSuccess { profile = it }
            .onFailure { error = it.message ?: "Could not load actor" }
        loading = false
    }
    LaunchedEffect(profile?.slug, error, loading, touch) {
        if (touch || loading) return@LaunchedEffect
        if (profile == null && error == null) return@LaunchedEffect
        runCatching { backFocus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(CoogBgDeep),
    ) {
        when {
            loading && profile == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading…", color = Color.White.copy(alpha = 0.7f))
                }
            }
            error != null && profile == null -> {
                Column(
                    Modifier.padding(inset).padding(top = topBarHeight()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BackChip(focus = backFocus, onBack = onBack, touch = touch)
                    Text(error!!, color = Color(0xFFFF8A80))
                }
            }
            else -> {
                val p = profile ?: return@Box
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = inset,
                        end = inset,
                        top = topBarHeight() + if (touch) 4.dp else 8.dp,
                        bottom = bottomPad,
                    ),
                    verticalArrangement = Arrangement.spacedBy(if (touch) 14.dp else 18.dp),
                ) {
                    item {
                        BackChip(focus = backFocus, onBack = onBack, touch = touch)
                    }
                    item {
                        ActorHero(profile = p, touch = touch)
                    }
                    if (p.galleryCount > 0) {
                        item {
                            Text("Gallery", style = CoogType.shelfTitle, color = Color.White)
                        }
                        item {
                            ActorGalleryRow(
                                slug = p.slug,
                                count = p.galleryCount,
                                returnFocus = galleryReturnFocus,
                                onOpen = { galleryIndex = it },
                                touch = touch,
                            )
                        }
                    }
                    if (p.scenes.isNotEmpty()) {
                        item {
                            CatalogRow(
                                label = "Scenes with ${p.name}",
                                items = p.scenes,
                                onOpen = onOpenScene,
                                featured = false,
                                exitUp = false,
                                insetStart = 0.dp,
                            )
                        }
                    }
                    if (p.similar.isNotEmpty()) {
                        item {
                            Text("Frequent co-stars", style = CoogType.shelfTitle, color = Color.White)
                        }
                        item {
                            CostarRow(similar = p.similar, onOpenActor = onOpenActor, touch = touch)
                        }
                    }
                }
                galleryIndex?.let { index ->
                    ActorGalleryFullscreen(
                        slug = p.slug,
                        count = p.galleryCount.coerceAtMost(24),
                        index = index,
                        onIndexChange = { galleryIndex = it },
                        onClose = {
                            galleryIndex = null
                            if (!touch) {
                                runCatching { galleryReturnFocus.requestFocus() }
                            }
                        },
                        touch = touch,
                    )
                }
            }
        }
    }
}

@Composable
private fun BackChip(focus: FocusRequester, onBack: () -> Unit, touch: Boolean) {
    if (touch) {
        GhostButton(label = "Back", onClick = onBack)
    } else {
        Surface(
            onClick = onBack,
            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(20.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.10f),
                focusedContainerColor = Color.White,
                focusedContentColor = CoogBgDeep,
                contentColor = Color.White,
            ),
            modifier = Modifier.focusRequester(focus),
        ) {
            Text(
                "Back",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = CoogType.cardYear,
            )
        }
    }
}

@Composable
private fun ActorHero(profile: ActorProfile, touch: Boolean) {
    val server = LocalCoogServer.current
    val tablet = LocalConfiguration.current.screenWidthDp >= 600
    val headshotUrl = remember(profile.slug, profile.hasHeadshot, server.url, server.adultSession) {
        if (profile.hasHeadshot) {
            CoogApi(server.url, server.token, server.adultSession).maizeActorHeadshotUrl(profile.slug)
        } else {
            ""
        }
    }
    val metaRows = remember(profile) {
        personMetaRows(
            birthday = profile.birthday,
            age = profile.age,
            birthplace = profile.birthplace,
            ethnicity = profile.ethnicity,
            nationality = profile.nationality,
            hairColor = profile.hairColor,
            eyeColor = profile.eyeColor,
            height = profile.height,
            weight = profile.weight,
            measurements = profile.measurements,
            shoeSize = profile.shoeSize,
            tattoos = profile.tattoos,
            piercings = profile.piercings,
            yearsActive = profile.yearsActive,
        )
    }
    val portraitWidth = when {
        touch && !tablet -> 120.dp
        touch && tablet -> 168.dp
        else -> 160.dp
    }
    val stackPhone = touch && !tablet

    @Composable
    fun Portrait() {
        Box(
            modifier = Modifier
                .width(portraitWidth)
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (headshotUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(headshotUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = profile.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    profile.name.take(1).uppercase(),
                    style = CoogType.heroTitle,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
    }

    @Composable
    fun Identity() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(profile.name, style = CoogType.heroTitle, color = Color.White, maxLines = 2)
            if (profile.aliases.isNotEmpty()) {
                Text(
                    "aka ${profile.aliases.take(4).joinToString(", ")}",
                    style = CoogType.cardYear,
                    color = CoogTextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip("${profile.sceneCount} scenes")
                if (profile.galleryCount > 0) {
                    StatChip("${profile.galleryCount} photos")
                }
            }
            if (profile.bio.isNotBlank()) {
                Text(
                    profile.bio,
                    style = CoogType.heroPlot,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = if (stackPhone) 6 else 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (stackPhone) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Portrait()
                Identity()
            }
            if (metaRows.isNotEmpty()) {
                val contentW = LocalConfiguration.current.screenWidthDp.dp - catalogInset() * 2
                PersonMetaSideCard(rows = metaRows, width = contentW)
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (touch) 16.dp else 20.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Portrait()
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Identity()
            }
            if (metaRows.isNotEmpty()) {
                PersonMetaSideCard(
                    rows = metaRows,
                    width = if (touch && tablet) 268.dp else 200.dp,
                )
            }
        }
    }
}

@Composable
private fun StatChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, style = CoogType.cardYear, color = Color.White)
    }
}

@Composable
private fun ActorGalleryRow(
    slug: String,
    count: Int,
    returnFocus: FocusRequester,
    onOpen: (Int) -> Unit,
    touch: Boolean,
) {
    val server = LocalCoogServer.current
    val api = remember(server.url, server.token, server.adultSession) {
        CoogApi(server.url, server.token, server.adultSession)
    }
    val thumbW = if (touch) 156.dp else 140.dp
    val thumbH = if (touch) 112.dp else 100.dp
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(if (touch) 8.dp else 10.dp),
        userScrollEnabled = true,
    ) {
        items(count.coerceAtMost(24)) { index ->
            val url = api.maizeActorGalleryUrl(slug, index)
            var focused by remember { mutableStateOf(false) }
            val thumbMod = Modifier
                .size(width = thumbW, height = thumbH)
                .then(if (!touch && index == 0) Modifier.focusRequester(returnFocus) else Modifier)
                .onFocusChanged { focused = it.isFocused }
            val content: @Composable () -> Unit = {
                Box(Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(url)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    if (focused) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .border(2.dp, Color.White, RoundedCornerShape(10.dp)),
                        )
                    }
                }
            }
            if (touch) {
                CoogClickable(
                    onClick = { onOpen(index) },
                    shape = RoundedCornerShape(10.dp),
                    color = Color.White.copy(alpha = 0.06f),
                    modifier = thumbMod,
                ) {
                    content()
                }
            } else {
                Surface(
                    onClick = { onOpen(index) },
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.06f),
                        focusedContainerColor = Color.White.copy(alpha = 0.10f),
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                    modifier = thumbMod,
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun ActorGalleryFullscreen(
    slug: String,
    count: Int,
    index: Int,
    onIndexChange: (Int) -> Unit,
    onClose: () -> Unit,
    touch: Boolean,
) {
    val server = LocalCoogServer.current
    val api = remember(server.url, server.token, server.adultSession) {
        CoogApi(server.url, server.token, server.adultSession)
    }
    val viewerFocus = remember { FocusRequester() }
    val safeIndex = index.coerceIn(0, (count - 1).coerceAtLeast(0))
    var dragAccum by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(safeIndex, touch) {
        if (!touch) runCatching { viewerFocus.requestFocus() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
            .then(
                if (touch) {
                    Modifier.pointerInput(safeIndex, count) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                when {
                                    dragAccum < -80f && safeIndex < count - 1 -> onIndexChange(safeIndex + 1)
                                    dragAccum > 80f && safeIndex > 0 -> onIndexChange(safeIndex - 1)
                                }
                                dragAccum = 0f
                            },
                            onHorizontalDrag = { _, amount ->
                                dragAccum += amount
                            },
                        )
                    }
                } else {
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> {
                                if (safeIndex > 0) onIndexChange(safeIndex - 1)
                                true
                            }
                            Key.DirectionRight -> {
                                if (safeIndex < count - 1) onIndexChange(safeIndex + 1)
                                true
                            }
                            Key.Back, Key.Escape -> {
                                onClose()
                                true
                            }
                            else -> false
                        }
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (touch) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(api.maizeActorGalleryUrl(slug, safeIndex))
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .padding(20.dp),
                )
                GhostButton(
                    label = "Close",
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = topBarHeight() + 8.dp, end = 16.dp),
                )
                Text(
                    "${safeIndex + 1} / $count · swipe for more",
                    style = CoogType.cardYear,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 36.dp),
                )
            }
        } else {
            Surface(
                onClick = onClose,
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(0.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(viewerFocus),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(api.maizeActorGalleryUrl(slug, safeIndex))
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .padding(24.dp),
                    )
                    Text(
                        "${safeIndex + 1} / $count",
                        style = CoogType.cardYear,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 28.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CostarRow(
    similar: List<ActorSimilar>,
    onOpenActor: (String) -> Unit,
    touch: Boolean,
) {
    val server = LocalCoogServer.current
    val cardW = if (touch) 108.dp else 96.dp
    val avatar = if (touch) 88.dp else 80.dp
    LazyRow(horizontalArrangement = Arrangement.spacedBy(if (touch) 10.dp else 14.dp)) {
        itemsIndexed(similar, key = { _, s -> s.slug }) { _, costar ->
            var focused by remember { mutableStateOf(false) }
            val url = remember(costar.slug, server.url, server.adultSession) {
                CoogApi(server.url, server.token, server.adultSession).maizeActorHeadshotUrl(costar.slug)
            }
            val labels: @Composable () -> Unit = {
                Text(
                    costar.name,
                    style = CoogType.cardYear,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val avatarBox: @Composable (Modifier) -> Unit = { mod ->
                Box(
                    mod
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(
                            if (focused) 3.dp else 1.dp,
                            if (focused) Color.White else Color.White.copy(alpha = 0.12f),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        costar.name.take(1).uppercase(),
                        style = CoogType.cardTitle,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(url)
                            .crossfade(true)
                            .build(),
                        contentDescription = costar.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (touch) {
                CoogClickable(
                    onClick = { onOpenActor(costar.slug) },
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.06f),
                    modifier = Modifier.width(cardW),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                    ) {
                        avatarBox(Modifier.size(avatar))
                        labels()
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.width(cardW),
                ) {
                    Surface(
                        onClick = { onOpenActor(costar.slug) },
                        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
                        modifier = Modifier
                            .size(avatar)
                            .onFocusChanged { focused = it.isFocused },
                    ) {
                        avatarBox(Modifier.fillMaxSize())
                    }
                    labels()
                }
            }
        }
    }
}
