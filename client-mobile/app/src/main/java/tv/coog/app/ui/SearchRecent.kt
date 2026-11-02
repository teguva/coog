package tv.coog.app.ui

import android.content.Context

private const val PrefsName = "coog_search"
private const val PrefsKey = "recent_queries"
private const val MaxRecent = 8

fun loadRecentSearches(context: Context): List<String> {
    val raw = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        .getString(PrefsKey, "")
        .orEmpty()
    if (raw.isBlank()) return emptyList()
    return raw.split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MaxRecent)
}

fun rememberRecentSearch(context: Context, query: String) {
    val q = query.trim()
    if (q.length < 2) return
    val next = (listOf(q) + loadRecentSearches(context).filterNot { it.equals(q, ignoreCase = true) })
        .take(MaxRecent)
    context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        .edit()
        .putString(PrefsKey, next.joinToString("\n"))
        .apply()
}

fun clearRecentSearches(context: Context) {
    context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        .edit()
        .remove(PrefsKey)
        .apply()
}
