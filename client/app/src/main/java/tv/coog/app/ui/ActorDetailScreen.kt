package tv.coog.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
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
    val server = LocalCoogServer.current
    var profile by remember(slug) { mutableStateOf<ActorProfile?>(null) }
    var loading by remember(slug) { mutableStateOf(true) }
    var error by remember(slug) { mutableStateOf<String?>(null) }
    val backFocus = remember { FocusRequester() }
    val inset = catalogInset()

    BackHandler { onBack() }

    LaunchedEffect(slug, server.url, server.adultSession) {
        if (server.url.isBlank() || server.adultSession.isBlank() || slug.isBlank()) return@LaunchedEffect
        loading = true
        error = null
        runCatching { CoogApi(server.url, server.token, server.adultSession).maizeActor(slug) }
            .onSuccess { profile = it }
            .onFailure { error = it.message ?: "Could not load actor" }
        loading = false
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
                    BackChip(focus = backFocus, onBack = onBack)
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
                        top = topBarHeight() + 8.dp,
                        bottom = 40.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    item {
                        BackChip(focus = backFocus, onBack = onBack)
                    }
                    item {
                        ActorHero(profile = p)
                    }
                    if (p.galleryCount > 0) {
                        item {
                            Text("Gallery", style = CoogType.shelfTitle, color = Color.White)
                        }
                        item {
                            ActorGalleryRow(slug = p.slug, count = p.galleryCount)
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
                            CostarRow(similar = p.similar, onOpenActor = onOpenActor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackChip(focus: FocusRequester, onBack: () -> Unit) {
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

@Composable
private fun ActorHero(profile: ActorProfile) {
    val server = LocalCoogServer.current
    val headshotUrl = remember(profile.slug, profile.hasHeadshot, server.url, server.adultSession) {
        if (profile.hasHeadshot) {
            CoogApi(server.url, server.token, server.adultSession).maizeActorHeadshotUrl(profile.slug)
        } else {
            ""
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(
            modifier = Modifier
                .width(160.dp)
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
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
            val stats = listOfNotNull(
                profile.birthday.takeIf { it.isNotBlank() }?.let { "Born $it" },
                profile.birthplace.takeIf { it.isNotBlank() },
                profile.ethnicity.takeIf { it.isNotBlank() },
                profile.height.takeIf { it.isNotBlank() },
                profile.measurements.takeIf { it.isNotBlank() },
                profile.yearsActive.takeIf { it.isNotBlank() }?.let { "Active $it" },
            )
            if (stats.isNotEmpty()) {
                Text(
                    stats.joinToString("  ·  "),
                    style = CoogType.cardYear,
                    color = CoogTextMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (profile.bio.isNotBlank()) {
                Text(
                    profile.bio,
                    style = CoogType.heroPlot,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
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
private fun ActorGalleryRow(slug: String, count: Int) {
    val server = LocalCoogServer.current
    val api = remember(server.url, server.token, server.adultSession) {
        CoogApi(server.url, server.token, server.adultSession)
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(count.coerceAtMost(24)) { index ->
            val url = api.maizeActorGalleryUrl(slug, index)
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 140.dp, height = 100.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.06f)),
            )
        }
    }
}

@Composable
private fun CostarRow(
    similar: List<ActorSimilar>,
    onOpenActor: (String) -> Unit,
) {
    val server = LocalCoogServer.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(similar, key = { _, s -> s.slug }) { _, costar ->
            var focused by remember { mutableStateOf(false) }
            val url = remember(costar.slug, server.url, server.adultSession) {
                CoogApi(server.url, server.token, server.adultSession).maizeActorHeadshotUrl(costar.slug)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.width(96.dp),
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
                        .size(80.dp)
                        .onFocusChanged { focused = it.isFocused },
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
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
                Text(
                    costar.name,
                    style = CoogType.cardYear,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
