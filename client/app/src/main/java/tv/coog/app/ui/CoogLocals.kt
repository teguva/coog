package tv.coog.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

data class CoogServer(
    val url: String,
    val token: String,
    val adultSession: String = "",
) {
    private fun withQuery(url: String, vararg pairs: Pair<String, String>): String {
        val extras = pairs.filter { it.second.isNotBlank() }
        if (extras.isEmpty()) return url
        val q = extras.joinToString("&") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        return if (url.contains("?")) "$url&$q" else "$url?$q"
    }

    fun mediaUrl(id: String, kind: String): String {
        val encId = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")
        val base = "${url.trimEnd('/')}/api/v1/media/$encId/$kind"
        return withQuery(base, "adult" to adultSession)
    }

    fun artworkUrl(id: String): String = mediaUrl(id, "backdrop")

    fun posterUrl(id: String, cacheKey: String = ""): String =
        withQuery(mediaUrl(id, "poster"), "v" to cacheKey)

    fun backdropUrl(id: String, cacheKey: String = ""): String =
        withQuery(mediaUrl(id, "backdrop"), "v" to cacheKey)

    fun logoUrl(id: String, cacheKey: String = ""): String =
        withQuery(mediaUrl(id, "logo"), "v" to cacheKey)

    fun trailerUrl(id: String): String = mediaUrl(id, "trailer")

    fun streamUrl(id: String): String = mediaUrl(id, "stream")

    fun deviceIconUrl(name: String, deviceId: String = ""): String {
        if (url.isBlank()) return ""
        val base = "${url.trimEnd('/')}/api/v1/interactive/device-icons/resolve"
        return withQuery(
            base,
            "name" to name,
            "device_id" to deviceId,
            "adult" to adultSession,
        )
    }
}

val LocalCoogServer = staticCompositionLocalOf { CoogServer("", "") }

val LocalBrowseContentFocus = staticCompositionLocalOf<FocusRequester?> { null }

val LocalRailFocus = staticCompositionLocalOf<FocusRequester?> { null }

val LocalNavBarFocused = staticCompositionLocalOf { false }

/** False while Movie/Player/etc cover browse so shelves keep state but pause trailers/focus. */
val LocalBrowseActive = staticCompositionLocalOf { true }

val LocalEnterRail = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun Modifier.exitToRailOnUp(enabled: Boolean = true, location: String = "exitToRailOnUp"): Modifier {
    val enterRail = LocalEnterRail.current
    if (!enabled) return this
    return onPreviewKeyEvent { event ->
        if (event.key != Key.DirectionUp) return@onPreviewKeyEvent false
        // #region agent log
        coogDebug(
            "F",
            location,
            "up to rail",
            mapOf("type" to event.type.toString()),
            runId = "post-fix",
        )
        // #endregion
        if (event.type == KeyEventType.KeyDown) enterRail()
        event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
    }
}
