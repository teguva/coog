package tv.coog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import tv.coog.app.data.ActorSummary
import tv.coog.app.data.CastMember
import tv.coog.app.data.CoogApi
import tv.coog.app.data.MediaItem
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogTextMuted
import tv.coog.app.ui.theme.CoogType

@Composable
fun AdultActorsScreen(
    onOpenActor: (String) -> Unit,
) {
    val server = LocalCoogServer.current
    val firstFocus = LocalBrowseContentFocus.current ?: remember { FocusRequester() }
    val railFocused = LocalNavBarFocused.current
    var actors by remember { mutableStateOf<List<ActorSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val inset = catalogInset()

    LaunchedEffect(server.url, server.adultSession) {
        if (server.url.isBlank() || server.adultSession.isBlank()) return@LaunchedEffect
        loading = true
        error = null
        runCatching { CoogApi(server.url, server.token, server.adultSession).maizeActors() }
            .onSuccess { actors = it }
            .onFailure { error = it.message ?: "Could not load actors" }
        loading = false
    }

    LaunchedEffect(railFocused, loading, actors.size) {
        if (railFocused || loading || actors.isEmpty()) return@LaunchedEffect
        runCatching { firstFocus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(CoogBgDeep)
            .padding(top = topBarHeight() + 6.dp),
    ) {
        when {
            error != null && actors.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(inset), contentAlignment = Alignment.Center) {
                    Text(error!!, color = Color(0xFFFF8A80))
                }
            }
            loading && actors.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(inset), contentAlignment = Alignment.Center) {
                    Text("Loading actors…", color = Color.White.copy(alpha = 0.7f))
                }
            }
            actors.isEmpty() -> {
                Column(Modifier.padding(horizontal = inset, vertical = 12.dp)) {
                    Text("Actors", style = CoogType.shelfTitle, color = Color.White)
                    Text(
                        "No actors found. Add performers to scene metadata to populate this list.",
                        color = CoogTextMuted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = inset),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.padding(bottom = 4.dp)) {
                            Text("Actors", style = CoogType.shelfTitle, color = Color.White)
                            Text(
                                "${actors.size} performer${if (actors.size == 1) "" else "s"} across your library.",
                                style = CoogType.cardYear,
                                color = CoogTextMuted,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    itemsIndexed(actors, key = { _, a -> a.slug.ifBlank { a.name } }) { index, actor ->
                        ActorGridCard(
                            actor = actor,
                            onClick = { onOpenActor(actor.slug) },
                            exitUp = index < 6,
                            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActorGridCard(
    actor: ActorSummary,
    onClick: () -> Unit,
    exitUp: Boolean,
    modifier: Modifier = Modifier,
) {
    val server = LocalCoogServer.current
    var focused by remember { mutableStateOf(false) }
    val headshotUrl = remember(actor.slug, actor.hasHeadshot, server.url, server.adultSession) {
        if (actor.hasHeadshot && actor.slug.isNotBlank()) {
            CoogApi(server.url, server.token, server.adultSession).maizeActorHeadshotUrl(actor.slug)
        } else {
            ""
        }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                pressedContainerColor = Color.Transparent,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .aspectRatio(1f)
                .then(if (exitUp) Modifier.exitToRailOnUp(location = "AdultActors.card") else Modifier)
                .onFocusChanged { focused = it.isFocused },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(
                        width = if (focused) 3.dp else 1.dp,
                        color = if (focused) Color.White else Color.White.copy(alpha = 0.12f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (headshotUrl.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(headshotUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = actor.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = actor.name.take(1).uppercase(),
                        style = CoogType.shelfTitle,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
        }
        Text(
            actor.name,
            style = CoogType.cardTitle,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${actor.sceneCount} scene${if (actor.sceneCount == 1) "" else "s"}",
            style = CoogType.cardYear,
            color = CoogTextMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/** Client-side slugify matching server actors.Slugify (ASCII fold best-effort). */
fun actorSlugify(name: String): String {
    if (name.isBlank()) return ""
    val folded = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
    return folded.replace(Regex("[^a-z0-9]+"), "-").trim('-')
}

/** Prefer catalog cast; else maize performers. Optionally fill People headshots by slug. */
fun MediaItem.overviewCastMembers(headshotForSlug: ((String) -> String)? = null): List<CastMember> {
    val base = cast.filter { it.name.isNotBlank() }.ifEmpty {
        performers.mapNotNull { name ->
            name.trim().takeIf { it.isNotEmpty() }?.let { CastMember(name = it) }
        }
    }
    if (headshotForSlug == null) return base
    return base.map { it.withMaizeHeadshot(headshotForSlug) }
}

fun CastMember.withMaizeHeadshot(headshotForSlug: (String) -> String): CastMember {
    if (profileUrl.isNotBlank() || name.isBlank()) return this
    val slug = actorSlugify(name)
    if (slug.isBlank()) return this
    return copy(profileUrl = headshotForSlug(slug))
}
