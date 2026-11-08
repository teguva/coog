package tv.coog.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * User-chosen accent for chrome (nav focus, primary pills). Semantic status colors
 * (local / soon / cinema / download) stay fixed.
 */
data class CoogAccent(
    val id: String,
    val label: String,
    val primary: Color,
    val onPrimary: Color,
    /** Soft fill for selected-but-unfocused chrome. */
    val soft: Color,
    /** Stronger soft for “active tab” idle. */
    val softActive: Color,
) {
    companion object {
        val DefaultId = "frost"

        val Presets: List<CoogAccent> = listOf(
            frost(),
            seed("ice", "Ice", Color(0xFF6EC8FF)),
            seed("mint", "Mint", Color(0xFF5EE09A)),
            seed("violet", "Violet", Color(0xFFB9A6FF)),
            seed("amber", "Amber", Color(0xFFFFC46B)),
            seed("coral", "Coral", Color(0xFFFF8B8B)),
            seed("aqua", "Aqua", Color(0xFF7DFFCE)),
        )

        fun fromId(id: String?): CoogAccent {
            val key = normalizeId(id)
            return Presets.firstOrNull { it.id == key } ?: frost()
        }

        fun normalizeId(id: String?): String {
            val raw = id?.trim()?.lowercase().orEmpty()
            return Presets.firstOrNull { it.id == raw }?.id ?: DefaultId
        }

        private fun frost(): CoogAccent = CoogAccent(
            id = DefaultId,
            label = "Frost",
            primary = Color.White,
            onPrimary = Color(0xFF121214),
            soft = Color.White.copy(alpha = 0.10f),
            softActive = Color.White.copy(alpha = 0.22f),
        )

        private fun seed(id: String, label: String, primary: Color): CoogAccent {
            val on = if (primary.luminance() > 0.55f) {
                Color(0xFF121214)
            } else {
                Color.White
            }
            return CoogAccent(
                id = id,
                label = label,
                primary = primary,
                onPrimary = on,
                soft = primary.copy(alpha = 0.16f),
                softActive = primary.copy(alpha = 0.28f),
            )
        }
    }
}

val LocalCoogAccent = staticCompositionLocalOf { CoogAccent.fromId(CoogAccent.DefaultId) }
