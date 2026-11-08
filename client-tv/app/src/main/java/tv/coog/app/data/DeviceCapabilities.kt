package tv.coog.app.data

import android.content.Context
import android.media.MediaCodecList
import android.os.Build

/**
 * Probe MediaCodec for what this device can decode.
 *
 * Leanback TVs (TCL etc.) decode or HDMI-passthrough awkward audio — TrueHD often
 * never appears in MediaCodecList, so we still advertise it and skip server AAC
 * remux. Runtime decode failures retry once with forceAudioRemux.
 * Mobile uses the FFmpeg extension instead; TV stays MediaCodec / passthrough.
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
                                // Advertise both spellings — server normalizes dts-hd ↔ dtshd.
                                audio += "dts"
                                audio += "dtshd"
                                audio += "dts-hd"
                            }
                            else -> audio += "dts"
                        }
                    }
                    "audio/flac" -> audio += "flac"
                }
            }
        }

        // TV SoCs / HDMI eARC: claim Blu-ray awkward audio even when MediaCodec omits
        // TrueHD (passthrough-only). Avoids progressive AAC sidecar merge on TCL.
        audio += "ac3"
        audio += "eac3"
        audio += "truehd"
        audio += "dts"
        audio += "dtshd"
        audio += "dts-hd"

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
}
