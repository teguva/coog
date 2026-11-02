package tv.coog.app.data

import android.content.Context
import android.media.MediaCodecList
import android.os.Build

/**
 * Probe what this device can decode for playback negotiation.
 *
 * Mobile ships Media3's FFmpeg extension (ac3/eac3) as MediaCodec fallback, so we
 * advertise those when the native lib loaded — avoids silent video on phones
 * without Dolby licenses. TV stays MediaCodec-only (+ server remux retry).
 * Runtime MediaCodec failures still retry once with forceAudioRemux.
 */
object DeviceCapabilities {
    @Volatile
    private var cached: ClientCapabilities? = null

    fun probe(context: Context): ClientCapabilities {
        cached?.let { return it }
        val video = linkedSetOf("h264")
        val audio = linkedSetOf("aac", "opus", "mp3")
        val containers = linkedSetOf("mp4", "mkv", "webm", "ts")
        val hdr = linkedSetOf<String>()

        // Bundled FFmpeg (EXTENSION_RENDERER_MODE_ON) covers unlicensed mobile SoCs.
        if (ffmpegAudioAvailable()) {
            audio += "ac3"
            audio += "eac3"
        }

        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            for (type in info.supportedTypes) {
                when (type.lowercase()) {
                    "video/hevc", "video/x-vnd.on2.hevc" -> video += "hevc"
                    "video/x-vnd.on2.vp9" -> video += "vp9"
                    "video/av01" -> video += "av1"
                    "video/dolby-vision" -> {
                        video += "hevc"
                        hdr += "dolbyvision"
                    }
                    "audio/ac3", "audio/eac3", "audio/eac3-joc" -> {
                        if (type.contains("eac3", ignoreCase = true)) audio += "eac3"
                        else audio += "ac3"
                    }
                    "audio/true-hd", "audio/vnd.dts.hd", "audio/vnd.dts" -> {
                        when {
                            type.contains("true", ignoreCase = true) -> audio += "truehd"
                            type.contains("hd", ignoreCase = true) -> {
                                audio += "dts"
                                audio += "dtshd"
                            }
                            else -> audio += "dts"
                        }
                    }
                    "audio/flac" -> audio += "flac"
                }
            }
        }

        // HDR signaling is best-effort; many devices decode HDR10 without a dedicated MIME.
        if (Build.VERSION.SDK_INT >= 24) {
            hdr += "hdr10"
            hdr += "hlg"
        }

        val caps = ClientCapabilities(
            videoCodecs = video.toList(),
            audioCodecs = audio.toList(),
            containers = containers.toList(),
            hdr = hdr.toList(),
        )
        cached = caps
        return caps
    }

    /** True when the official Media3 FFmpeg JNI (ac3/eac3) loaded successfully. */
    private fun ffmpegAudioAvailable(): Boolean {
        return try {
            val clazz = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary")
            clazz.getMethod("isAvailable").invoke(null) as? Boolean == true
        } catch (_: Throwable) {
            false
        }
    }
}
