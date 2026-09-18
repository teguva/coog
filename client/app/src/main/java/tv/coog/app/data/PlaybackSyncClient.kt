package tv.coog.app.data

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

/** Pushes playback clock to coog-api /ws/v1/sync for funscript drive. */
class PlaybackSyncClient(
    private val api: CoogApi,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    private var job: Job? = null
    private val open = AtomicBoolean(false)
    private var lastPos = -1L

    fun start(
        mediaId: String,
        resumeMs: Long,
        scope: CoroutineScope,
        position: () -> Long,
        playing: () -> Boolean,
        script: String = "",
    ) {
        stop()
        scope.launch(Dispatchers.IO) {
            runCatching {
                api.interactiveLoad(
                    InteractiveLoadRequest(
                        mediaId = mediaId,
                        script = script,
                        resumeMs = resumeMs.toDouble(),
                    ),
                )
            }
        }
        val req = Request.Builder().url(api.interactiveSyncWsUrl()).build()
        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open.set(true)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                open.set(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                open.set(false)
            }
        })
        // ExoPlayer getters must run on the application thread.
        job = scope.launch(Dispatchers.Main.immediate) {
            var tick = 0
            while (isActive) {
                val pos = runCatching { position() }.getOrDefault(lastPos.coerceAtLeast(0L))
                val isPlaying = runCatching { playing() }.getOrDefault(false)
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

    fun pause() {
        if (open.get()) {
            runCatching { socket?.send(JSONObject().put("type", "pause").toString()) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        pause()
        socket?.close(1000, null)
        socket = null
        open.set(false)
        lastPos = -1L
    }
}
