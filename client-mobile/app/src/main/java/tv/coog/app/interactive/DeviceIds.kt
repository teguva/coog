package tv.coog.app.interactive

internal fun stableDeviceId(name: String): String {
    val slug = name.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "device" }
    return "name:$slug"
}

internal fun isVortexName(name: String): Boolean {
    val n = name.trim().lowercase()
    return n.contains("funplay vortex") ||
        n.contains("vacuumctrl") ||
        n == "sexverse device" ||
        n.contains("funplay vacuum")
}

internal fun clampIntensity(v: Int): Int = v.coerceIn(10, 200)
