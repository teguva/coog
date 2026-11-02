package tv.coog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import tv.coog.app.data.MediaItem
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogType

@Composable
fun HeroBanner(
    item: MediaItem?,
    rowLabel: String = "Movies",
    jobs: List<tv.coog.app.data.JobItem> = emptyList(),
    library: List<MediaItem> = emptyList(),
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth().fillMaxSize().background(CoogBgDeep)) {
        if (item != null) {
            PosterArt(
                item = item,
                kind = ArtKind.Backdrop,
                contentScale = ContentScale.Crop,
                alignment = Alignment.CenterEnd,
                preferDisplay = true,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0.00f to CoogBgDeep,
                            0.22f to CoogBgDeep.copy(alpha = 0.88f),
                            0.42f to CoogBgDeep.copy(alpha = 0.38f),
                            0.68f to Color.Transparent,
                            1.00f to Color.Transparent,
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to CoogBgDeep.copy(alpha = 0.18f),
                            0.18f to Color.Transparent,
                            0.55f to Color.Transparent,
                            0.78f to CoogBgDeep.copy(alpha = 0.62f),
                            1.00f to CoogBgDeep,
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(0.46f)
                    .padding(start = RailWidth + 12.dp, end = 12.dp, top = 36.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TitleLockup(item)
                val tagline = item.heroSubtitle()
                if (tagline.isNotBlank()) {
                    Text(
                        tagline,
                        style = CoogType.heroTagline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val plot = item.heroDescription()
                if (plot.isNotBlank()) {
                    Text(
                        plot,
                        style = CoogType.heroPlot,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item.cardMark(jobs, library)?.let { StatusMark(mark = it, size = MarkSize.Comfort) }
                    item.matchPercent()?.let { MatchMark(percent = it, size = MarkSize.Comfort) }
                    item.heroChips(rowLabel, jobs).take(4).forEach { chip ->
                        Text(
                            chip,
                            style = CoogType.chip,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.38f))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TitleLockup(
    item: MediaItem,
    modifier: Modifier = Modifier,
    logoHeight: Dp = 72.dp,
    titleStyle: TextStyle = CoogType.heroTitle,
) {
    val server = LocalCoogServer.current
    val logo = remember(item.id, item.logoUrl, item.imdbId, item.libraryId, server.url) {
        resolvedLogoUrl(item, server)
    }
    var logoFailed by remember(logo) { mutableStateOf(false) }
    if (logo.isNotBlank() && !logoFailed) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(logo)
                .apply {
                    if (server.token.isNotBlank()) {
                        addHeader("Authorization", "Bearer ${server.token}")
                    }
                }
                .crossfade(200)
                .build(),
            contentDescription = item.headline(),
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onError = { logoFailed = true },
            modifier = modifier.fillMaxWidth().height(logoHeight),
        )
        return
    }
    Text(
        item.headline(),
        style = titleStyle.copy(
            shadow = Shadow(Color.Black.copy(alpha = 0.65f), Offset.Zero, 16f),
        ),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
