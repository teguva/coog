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
    private val gate = DriveGate()

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
        // Autostart may already have wantPlay=true — allow drive without a prior false edge.
        gate.armForPlay()
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
                val wantPlay = runCatching {
                    player.playWhenReady &&
                        player.playbackState != Player.STATE_ENDED &&
                        player.playbackState != Player.STATE_IDLE
                }.getOrDefault(false)
                val isPlaying = gate.onWantPlay(wantPlay)
                val seek = lastPos >= 0 && kotlin.math.abs(pos - lastPos) > 500
                lastPos = pos
                val enteredPause = intiface.sync.setClock(pos.toDouble(), isPlaying, seek)
                if (enteredPause) {
                    intiface.parkIdle(pos, clearScript = false, reason = "clock-pause")
                }
                delay(50)
            }
        }
    }

    /** Immediate pause edge (don't wait for the next 50ms clock poll). */
    fun forcePaused(positionMs: Long = lastPos.coerceAtLeast(0L)) {
        gate.suppress()
        intiface.parkIdle(positionMs, clearScript = false, reason = "force-paused")
    }

    fun stop() {
        running = false
        gate.suppress()
        clockJob?.cancel()
        clockJob = null
        tickJob?.cancel()
        tickJob = null
        intiface.onPlaybackEnded(lastPos.coerceAtLeast(0L))
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

    /**
     * Blocks Linear/Scalar until a real Play: wantPlay must go false at least once
     * after [suppress], then true again. [armForPlay] opens the gate for autostart.
     */
    private class DriveGate {
        @Volatile private var suppressed = true
        @Volatile private var sawWantPlayFalseSinceSuppress = false

        fun armForPlay() {
            suppressed = false
            sawWantPlayFalseSinceSuppress = true
        }

        fun suppress() {
            suppressed = true
            sawWantPlayFalseSinceSuppress = false
        }

        /** Update from clock; returns whether funscript drive may run this tick. */
        fun onWantPlay(wantPlay: Boolean): Boolean {
            if (!wantPlay) {
                suppressed = true
                sawWantPlayFalseSinceSuppress = true
                return false
            }
            if (suppressed && sawWantPlayFalseSinceSuppress) {
                suppressed = false
            }
            return !suppressed
        }
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
