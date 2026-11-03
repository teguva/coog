package tv.coog.app.interactive

import android.util.Log
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.coog.app.data.CoogApi

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
        scope.launch(Dispatchers.IO) {
            val resp = runCatching { api.maizeFunscriptActions(mediaId, script) }
                .onFailure { Log.w(TAG, "funscript actions failed: ${it.message}") }
                .getOrNull()
            val actions = resp?.actions.orEmpty().map { FunAction(it.at, it.pos) }
            if (!running) return@launch
            intiface.sync.load(actions, SyncParams(), resumeMs.toDouble())
            Log.i(TAG, "loaded ${actions.size} actions for $mediaId")
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

    companion object {
        private const val TAG = "CoogLocalSync"
    }
}
