package tv.coog.app.ui

/** Debug ingest used during focus/HUD work — kept as a no-op so call sites compile without noise. */
internal fun coogDebug(
    hypothesisId: String,
    location: String,
    message: String,
    data: Map<String, Any?> = emptyMap(),
    runId: String = "pre-fix",
) {
    // intentionally empty
}
