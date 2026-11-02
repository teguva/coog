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
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.util.ArrayList

/**
 * Xiaomi (and similar) boxes often advertise **no** HDR types on the Android
 * Display while still decoding PQ / Dolby Vision. ExoPlayer then outputs HDR
 * buffers onto an SDR path → inverted / washed colors.
 *
 * When the display reports no HDR support:
 * - API 31+: request MediaCodec KEY_COLOR_TRANSFER_REQUEST → SDR
 * - API ≤30: OpenGL tone-map via PlaybackVideoGraphWrapper (needs setVideoEffects)
 *
 * Dolby Vision Profile 8 is still decoded as HEVC (see PlayerViewModel).
 */
@OptIn(UnstableApi::class)
internal class CoogRenderersFactory(
    context: Context,
    private val toneMapHdrToSdr: Boolean = !displayAdvertisesHdr(context),
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

    companion object {
        internal const val TAG = "CoogPlayer"
        const val MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY = 50

        fun displayAdvertisesHdr(context: Context): Boolean {
            // Application contexts on some Android TV builds throw from Context.display.
            return try {
                val display = resolveDisplay(context) ?: return false
                val types = display.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
                types.isNotEmpty()
            } catch (t: Throwable) {
                Log.w(TAG, "HDR capability probe failed; assuming SDR display", t)
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

    override fun createPlaybackVideoGraphWrapper(
        context: Context,
        videoFrameReleaseControl: VideoFrameReleaseControl,
    ): PlaybackVideoGraphWrapper {
        val wrapper = super.createPlaybackVideoGraphWrapper(context, videoFrameReleaseControl)
        // OpenGL tone-map for API ≤30 (MediaCodec KEY_COLOR_TRANSFER_REQUEST needs 31+).
        if (toneMapHdrToSdr && Build.VERSION.SDK_INT < 31) {
            wrapper.setRequestOpenGlToneMapping(true)
            Log.i(CoogRenderersFactory.TAG, "tone-map HDR→SDR via OpenGL (API ${Build.VERSION.SDK_INT})")
        }
        return wrapper
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
