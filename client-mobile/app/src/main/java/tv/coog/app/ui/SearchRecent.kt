package tv.coog.app.ui

import android.content.Context

private const val PrefsName = "coog_search"
private const val PrefsKey = "recent_queries"
private const val PrefsKeyMaize = "recent_queries_maize"
private const val MaxRecent = 8

fun loadRecentSearches(context: Context, maize: Boolean = false): List<String> {
    val raw = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        .getString(if (maize) PrefsKeyMaize else PrefsKey, "")
        .orEmpty()
    if (raw.isBlank()) return emptyList()
    return raw.split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MaxRecent)
}

fun rememberRecentSearch(context: Context, query: String, maize: Boolean = false) {
    val q = query.trim()
    if (q.length < 2) return
    val key = if (maize) PrefsKeyMaize else PrefsKey
    val next = (listOf(q) + loadRecentSearches(context, maize).filterNot { it.equals(q, ignoreCase = true) })
        .take(MaxRecent)
    context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        .edit()
        .putString(key, next.joinToString("\n"))
        .apply()
}

fun clearRecentSearches(context: Context, maize: Boolean = false) {
    context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        .edit()
        .remove(if (maize) PrefsKeyMaize else PrefsKey)
        .apply()
}
