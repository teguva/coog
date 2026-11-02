package tv.coog.app.data

import android.util.Log
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Pushes playback clock to coog-api /ws/v1/sync for funscript drive. */
class PlaybackSyncClient(
    private val api: CoogApi,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private var socket: WebSocket? = null
    private var job: Job? = null
    private var reconnectJob: Job? = null
    private val open = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private var lastPos = -1L
    private val lastFail = AtomicReference<String?>(null)

    fun start(
        mediaId: String,
        resumeMs: Long,
        scope: CoroutineScope,
        position: () -> Long,
        player: Player,
        script: String = "",
    ) {
        stop()
        running.set(true)
        scope.launch(Dispatchers.IO) {
            runCatching {
                api.interactiveLoad(
                    InteractiveLoadRequest(
                        mediaId = mediaId,
                        script = script,
                        resumeMs = resumeMs.toDouble(),
                    ),
                )
            }.onFailure {
                Log.w(TAG, "interactive load failed: ${it.message}")
            }
        }
        connectSocket()
        reconnectJob = scope.launch(Dispatchers.IO) {
            while (isActive && running.get()) {
                delay(2_000)
                if (running.get() && !open.get()) {
                    Log.i(TAG, "sync ws reconnect (${lastFail.get() ?: "closed"})")
                    connectSocket()
                }
            }
        }
        // ExoPlayer getters must run on the application thread.
        job = scope.launch(Dispatchers.Main.immediate) {
            var tick = 0
            while (isActive && running.get()) {
                val pos = runCatching { position() }.getOrDefault(lastPos.coerceAtLeast(0L))
                // isPlaying flickers false while buffering; playWhenReady tracks user intent.
                val isPlaying = runCatching {
                    player.playWhenReady &&
                        player.playbackState != Player.STATE_ENDED &&
                        player.playbackState != Player.STATE_IDLE
                }.getOrDefault(false)
                val seek = lastPos >= 0 && kotlin.math.abs(pos - lastPos) > 500
                lastPos = pos
                if (open.get()) {
                    val msg = JSONObject()
                        .put("type", "pos")
                        .put("pos_ms", pos.toDouble())
                        .put("playing", isPlaying)
                        .put("seek", seek)
                        .toString()
                    runCatching { socket?.send(msg) }
                    if (tick % 40 == 0) {
                        runCatching {
                            socket?.send(
                                JSONObject()
                                    .put("type", "ping")
                                    .put("client_ts", System.currentTimeMillis())
                                    .toString(),
                            )
                        }
                    }
                }
                tick++
                delay(50)
            }
        }
    }

    private fun connectSocket() {
        runCatching { socket?.cancel() }
        socket = null
        open.set(false)
        val req = Request.Builder()
            .url(api.interactiveSyncWsUrl())
            .apply {
                api.authHeaders().forEach { (k, v) -> header(k, v) }
            }
            .build()
        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open.set(true)
                lastFail.set(null)
                Log.i(TAG, "sync ws open")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                open.set(false)
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                open.set(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                open.set(false)
                lastFail.set(t.message ?: response?.code?.toString() ?: "failure")
                Log.w(TAG, "sync ws failure: ${lastFail.get()}")
            }
        })
    }

    fun pause() {
        if (open.get()) {
            runCatching { socket?.send(JSONObject().put("type", "pause").toString()) }
        }
    }

    fun stop() {
        running.set(false)
        job?.cancel()
        job = null
        reconnectJob?.cancel()
        reconnectJob = null
        pause()
        runCatching { socket?.close(1000, null) }
        runCatching { socket?.cancel() }
        socket = null
        open.set(false)
        lastPos = -1L
    }

    companion object {
        private const val TAG = "CoogSync"
    }
}
