package tv.coog.app.interactive

import android.util.Log
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tv.coog.app.data.CoogApi
import tv.coog.app.data.FunscriptActionDto

/**
 * Loads funscript actions from the Coog API and drives [LocalIntiface.sync]
 * from ExoPlayer position — no server /ws/v1/sync.
 */
class LocalPlaybackDriver(
    private val intiface: LocalIntiface,
    private val api: CoogApi,
) {
    private var clockJob: Job? = null
    private var tickJob: Job? = null
    private var lastPos = -1L
    @Volatile private var running = false

    fun start(
        mediaId: String,
        resumeMs: Long,
        scope: CoroutineScope,
        position: () -> Long,
        player: Player,
        script: String = "",
    ) {
        stop()
        running = true
        // BLE scan starves toy writes (same as server holdScanForSync).
        runCatching { intiface.scanStop() }
        scope.launch(Dispatchers.IO) {
            val actions = loadActions(mediaId, script)
            if (!running) return@launch
            if (actions.isEmpty()) {
                Log.w(TAG, "no funscript actions for $mediaId script=${script.ifBlank { "(default)" }} — toy will stay idle")
            }
            intiface.sync.load(actions, SyncParams(), resumeMs.toDouble())
            Log.i(TAG, "loaded ${actions.size} actions for $mediaId script=${script.ifBlank { "(default)" }} ready=${intiface.isReady()} devices=${intiface.engine.value.devices.size}")
        }
        tickJob = scope.launch(Dispatchers.Default) {
            while (isActive && running) {
                if (intiface.isReady()) intiface.sync.tick()
                delay(50)
            }
        }
        clockJob = scope.launch(Dispatchers.Main.immediate) {
            while (isActive && running) {
                val pos = runCatching { position() }.getOrDefault(lastPos.coerceAtLeast(0L))
                val isPlaying = runCatching {
                    player.playWhenReady &&
                        player.playbackState != Player.STATE_ENDED &&
                        player.playbackState != Player.STATE_IDLE
                }.getOrDefault(false)
                val seek = lastPos >= 0 && kotlin.math.abs(pos - lastPos) > 500
                lastPos = pos
                intiface.sync.setClock(pos.toDouble(), isPlaying, seek)
                delay(50)
            }
        }
    }

    fun stop() {
        running = false
        clockJob?.cancel()
        clockJob = null
        tickJob?.cancel()
        tickJob = null
        intiface.sync.clear()
        lastPos = -1L
    }

    private suspend fun loadActions(mediaId: String, script: String): List<FunAction> {
        val fromApi = runCatching { api.maizeFunscriptActions(mediaId, script) }
            .onFailure { Log.w(TAG, "funscript actions failed: ${it.message}") }
            .getOrNull()
        if (fromApi != null) {
            Log.i(TAG, "actions api actionCount=${fromApi.actionCount} parsed=${fromApi.actions.size}")
            if (fromApi.actions.isNotEmpty()) {
                return fromApi.actions.map { FunAction(it.at, it.pos) }
            }
            // Old server returns preview JSON (points, no actions) for unknown format=.
            if (fromApi.actionCount > 0) {
                Log.w(TAG, "server returned actionCount=${fromApi.actionCount} but empty actions — trying raw file")
            }
        }
        val raw = runCatching { api.maizeFunscriptRaw(mediaId, script) }
            .onFailure { Log.w(TAG, "funscript raw failed: ${it.message}") }
            .getOrNull()
            ?: return emptyList()
        return parseRawFunscript(raw)
    }

    private fun parseRawFunscript(text: String): List<FunAction> {
        // Preview JSON from old servers has "points" and no useful drive data.
        if (text.contains("\"points\"") && !text.contains("\"actions\"")) {
            Log.w(TAG, "raw response looks like preview JSON — update coog-api for format=raw/actions")
            return emptyList()
        }
        val file = runCatching { rawJson.decodeFromString(FunscriptFile.serializer(), text) }
            .onFailure { Log.w(TAG, "raw funscript parse failed: ${it.message}") }
            .getOrNull()
            ?: return emptyList()
        return file.actions.map { FunAction(it.at, it.pos) }
    }

    @Serializable
    private data class FunscriptFile(
        val actions: List<FunscriptActionDto> = emptyList(),
    )

    companion object {
        private const val TAG = "CoogLocalSync"
        private val rawJson = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }
}
