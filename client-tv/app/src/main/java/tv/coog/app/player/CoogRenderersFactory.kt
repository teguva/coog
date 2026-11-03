package tv.coog.app.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import android.os.Looper
import java.util.ArrayList

/**
 * Stremio / Jellyfin ATV style: ExoPlayer → MediaCodec → display. No client OpenGL
 * tone-map. Real HDR TVs (TCL) show PQ/DV natively.
 *
 * On API 31+ devices that advertise **no** HDR types, request MediaCodec
 * KEY_COLOR_TRANSFER_REQUEST → SDR (platform tone-map). API ≤30 has no such API;
 * those boxes need a different source or server-side tonemap (Jellyfin path).
 *
 * Dolby Vision Profile 8 may still be decoded as HEVC (see PlayerViewModel).
 */
@OptIn(UnstableApi::class)
internal class CoogRenderersFactory(
    context: Context,
    private val toneMapHdrToSdr: Boolean =
        Build.VERSION.SDK_INT >= 31 && !displayAdvertisesHdr(context),
) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            HdrToneMapVideoRenderer(
                context,
                getCodecAdapterFactory(),
                mediaCodecSelector,
                allowedVideoJoiningTimeMs,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
                toneMapHdrToSdr,
            ),
        )
        // Intentionally skip libvpx / libgav1 extensions — not packaged with Coog.
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        // Required when Matroska emits raw PGS (see PlayerViewModel extractors).
        // Without legacy decoding, TextRenderer rejects application/pgs samples.
        val textRenderer = TextRenderer(output, outputLooper)
        textRenderer.experimentalSetLegacyDecodingEnabled(true)
        out.add(textRenderer)
    }

    companion object {
        internal const val TAG = "CoogPlayer"
        const val MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY = 50

        fun displayAdvertisesHdr(context: Context): Boolean {
            return try {
                val display = resolveDisplay(context) ?: return false
                val types = display.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
                types.isNotEmpty()
            } catch (t: Throwable) {
                Log.w(TAG, "HDR capability probe failed; assuming no Android HDR types", t)
                false
            }
        }

        private fun resolveDisplay(context: Context): Display? {
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    context.display?.let { return it }
                } catch (_: Throwable) {
                    // fall through
                }
            }
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            dm?.getDisplay(Display.DEFAULT_DISPLAY)?.let { return it }
            @Suppress("DEPRECATION")
            return (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        }
    }
}

@OptIn(UnstableApi::class)
private class HdrToneMapVideoRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int,
    private val toneMapHdrToSdr: Boolean,
) : MediaCodecVideoRenderer(
    context,
    codecAdapterFactory,
    mediaCodecSelector,
    allowedJoiningTimeMs,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    maxDroppedFramesToNotify,
) {
    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxValues: CodecMaxValues,
        codecOperatingRate: Float,
        deviceNeedsNoPostProcessWorkaround: Boolean,
        tunnelingAudioSessionId: Int,
    ): MediaFormat {
        val mediaFormat = super.getMediaFormat(
            format,
            codecMimeType,
            codecMaxValues,
            codecOperatingRate,
            deviceNeedsNoPostProcessWorkaround,
            tunnelingAudioSessionId,
        )
        if (
            toneMapHdrToSdr &&
            Build.VERSION.SDK_INT >= 31 &&
            isHdrVideoFormat(format)
        ) {
            mediaFormat.setInteger(
                MediaFormat.KEY_COLOR_TRANSFER_REQUEST,
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            )
            Log.i(
                CoogRenderersFactory.TAG,
                "tone-map HDR→SDR via MediaCodec mime=$codecMimeType transfer=${format.colorInfo?.colorTransfer}",
            )
        }
        return mediaFormat
    }
}

@OptIn(UnstableApi::class)
internal fun isHdrVideoFormat(format: Format): Boolean {
    if (MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType, ignoreCase = true)) {
        return true
    }
    val color = format.colorInfo ?: return false
    return color.colorTransfer == C.COLOR_TRANSFER_ST2084 ||
        color.colorTransfer == C.COLOR_TRANSFER_HLG
}
