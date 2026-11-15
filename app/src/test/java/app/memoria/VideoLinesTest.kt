package app.memoria

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A video's camera line: size, speed, HDR kind; then codec, bitrate, sound. */
class VideoLinesTest {

    private fun lines(
        width: Int = 0, height: Int = 0, fps: Float? = null, captureFps: Float? = null,
        videoMime: String? = null, profile: Int? = null, transfer: Int? = null, dolby: Boolean = false,
        bytes: Long = 0, durationMs: Long = 0,
        audioMime: String? = null, channels: Int? = null, sampleRate: Int? = null,
    ) = videoLines(width, height, fps, captureFps, videoMime, profile, transfer, dolby, bytes, durationMs, audioMime, channels, sampleRate)

    /** The mockup's clip, docs/design.html "viewer-video-details". */
    @Test fun dolbyVisionClip() = assertEquals(
        "4K · 30 fps · HDR Dolby Vision" to "HEVC 10-bit · 50 Mbps · AAC stereo 48 kHz",
        lines(
            3840, 2160, 29.97f, 30f, "video/hevc", CodecProfileLevel.HEVCProfileMain10,
            MediaFormat.COLOR_TRANSFER_HLG, dolby = true, bytes = 366_000_000, durationMs = 58_000,
            audioMime = "audio/mp4a-latm", channels = 2, sampleRate = 48000,
        ),
    )

    @Test fun plainFullHd() = assertEquals(
        "1080p · 30 fps" to "H.264 · 17 Mbps · AAC mono 44.1 kHz",
        lines(
            1920, 1080, 29.97f, null, "video/avc", CodecProfileLevel.AVCProfileHigh,
            MediaFormat.COLOR_TRANSFER_SDR_VIDEO, bytes = 100_000_000, durationMs = 47_000,
            audioMime = "audio/mp4a-latm", channels = 1, sampleRate = 44100,
        ),
    )

    @Test fun hlgWithoutDolby() = assertEquals(
        "4K · 60 fps · HDR HLG" to "HEVC 10-bit · No sound",
        lines(3840, 2160, 60f, null, "video/hevc", CodecProfileLevel.HEVCProfileMain10, MediaFormat.COLOR_TRANSFER_HLG),
    )

    @Test fun pqIsHdr10() = assertEquals(
        "4K · 24 fps · HDR10" to "AV1 10-bit · No sound",
        lines(3840, 2160, 24f, null, "video/av01", CodecProfileLevel.AV1ProfileMain10, MediaFormat.COLOR_TRANSFER_ST2084),
    )

    @Test fun slowMotion() = assertEquals(
        "1080p · Slow motion · 240 fps" to "HEVC · No sound",
        lines(1920, 1080, 30f, 240f, "video/hevc", CodecProfileLevel.HEVCProfileMain),
    )

    @Test fun timeLapse() = assertEquals(
        "1080p · Time-lapse" to "H.264 · No sound",
        lines(1920, 1080, 30f, 1f, "video/avc", CodecProfileLevel.AVCProfileHigh),
    )

    @Test fun noAudio() = assertEquals(
        "4K · 30 fps" to "HEVC · No sound",
        lines(3840, 2160, 30f, null, "video/hevc", CodecProfileLevel.HEVCProfileMain),
    )

    @Test fun eightK() = assertEquals("8K" to "No sound", lines(7680, 4320))

    @Test fun portraitUsesTheShortSide() = assertEquals("1080p" to "No sound", lines(1080, 1920))

    @Test fun unknownCodecLeavesItOut() = assertEquals(null to "800 kbps · No sound", lines(videoMime = "video/x-junk", bytes = 100_000, durationMs = 1_000))

    @Test fun bitrateBetweenOneAndTenMbpsHasOneDecimal() = assertEquals(null to "2.5 Mbps · No sound", lines(bytes = 3_125_000, durationMs = 10_000))

    @Test fun smallBitrateInKbps() = assertEquals(null to "VP8 · 800 kbps · No sound", lines(videoMime = "video/x-vnd.on2.vp8", bytes = 100_000, durationMs = 1_000))

    @Test fun surroundSound() = assertEquals(
        null to "E-AC-3 5.1 48 kHz",
        lines(audioMime = "audio/eac3", channels = 6, sampleRate = 48000),
    )

    @Test fun nothingKnownStillSaysThereIsNoSound() {
        val (title, subtitle) = lines()
        assertNull(title)
        assertEquals("No sound", subtitle)
    }
}
