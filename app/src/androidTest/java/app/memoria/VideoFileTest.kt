package app.memoria

import android.media.MediaCodecList
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * Real video files through the app's player and its own page, the way `PhotoFileTest` does it for
 * photos: the shape a file reports decides how it fits the screen (a portrait clip was stretched in
 * the viewer on 2026-11-12), and a file the decoder refuses must reach the "cannot play" card.
 *
 * The clips in `assets/videos/` are the ones a library actually holds: H.264 and HEVC from a camera,
 * a portrait camera video (stored landscape, turned by the file's display matrix), an iPhone .mov, a
 * .mkv download, VP9, AV1 and 10-bit HEVC. Files are made by `uv run bench/videofix.py` and committed
 * (a few KB each). The emulator has no hardware decoders, so a clip its software decoder refuses fails
 * here: only the phone proves every codec. AV1 and 10-bit HEVC skip when the device has no decoder at
 * all. Runs with the other androidTests (`make layout`).
 */
class VideoFileTest {

    @get:Rule val rule = createComposeRule()

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The asset copied out of the test APK to a plain file the player can open. */
    private fun clipFile(asset: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, asset)
        instrumentation.context.assets.open("videos/$asset").use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun clipTile(asset: String) = Tile(1, Uri.fromFile(clipFile(asset)), 0L, 60_000L)

    private data class Played(val aspect: Float, val error: PlaybackException?)

    /** What the player's callbacks say; the test thread reads it while the main thread writes. */
    private class Probe {
        @Volatile var width = 0
        @Volatile var height = 0
        @Volatile var error: PlaybackException? = null
    }

    /** Plays the asset in the real viewer page and reports the shape the player says, or its error. */
    private fun play(asset: String): Played {
        val tile = clipTile(asset)
        val probe = Probe()
        var player: ExoPlayer? = null
        rule.runOnUiThread {
            player = ExoPlayer.Builder(context()).build().also { p ->
                p.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(size: VideoSize) {
                        probe.width = size.width
                        probe.height = size.height
                    }

                    override fun onPlayerError(e: PlaybackException) {
                        probe.error = e
                    }
                })
                p.setMediaItem(MediaItem.fromUri(tile.uri))
                p.prepare()
                p.play()
            }
        }
        rule.setContent {
            MemoriaTheme { Surface { ViewerPage(tile, onZoomed = {}, player = player, current = true) } }
        }
        rule.waitUntil(20_000) { probe.error != null || (probe.width > 0 && probe.height > 0) }
        rule.runOnUiThread { player?.release() }
        return Played(videoAspectOf(probe.width, probe.height), probe.error)
    }

    private fun shape(asset: String, aspect: Float) {
        val played = play(asset)
        assertNull("$asset: ${played.error?.errorCodeName}", played.error)
        assertEquals("$asset shape", aspect, played.aspect, 0.01f)
    }

    /** True when this phone has any decoder for [mime]: the emulator has none for some codecs. */
    private fun hasDecoder(mime: String): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }

    @Test fun landscapeClip_reportsItsShape() = shape("video_land.mp4", 320f / 180f)

    @Test fun portraitClip_reportsItsShape() = shape("video_port.mp4", 180f / 320f)

    @Test fun hevcClip_reportsItsShape() = shape("video_hevc.mp4", 320f / 180f)

    @Test fun vp9Clip_reportsItsShape() = shape("video_vp9.webm", 320f / 180f)

    @Test fun movClip_reportsItsShape() = shape("video_mov.mov", 320f / 180f)

    @Test fun mkvClip_reportsItsShape() = shape("video_mkv.mkv", 320f / 180f)

    /** A camera's portrait video is stored landscape and turned by the file's display matrix, not by its pixels. */
    @Test fun rotatedClip_reportsTheUprightShape() = shape("video_rot90.mp4", 180f / 320f)

    @Test fun av1Clip_reportsItsShape() {
        assumeTrue("no AV1 decoder on this device", hasDecoder("video/av01"))
        shape("video_av1.mp4", 320f / 180f)
    }

    /**
     * 10-bit HEVC, the way a phone records HDR. The emulator's decoder takes the format and then fails the
     * frame, so both outcomes are right here: the upright shape, or the "cannot play" card (which the case
     * above covers). The phone plays it, and its own check says so.
     */
    @Test fun tenBitHevcClip_playsOrShowsTheCard() {
        val played = play("video_hevc10.mp4")
        if (played.error == null) assertEquals("video_hevc10.mp4 shape", 16f / 9f, played.aspect, 0.01f)
    }

    /**
     * The poster is the still the open and close motion moves, and the page is sized by it until the
     * player knows the video's own size. A poster of another shape (a turned video's stored frame, a
     * sideways decode) makes the video jump as it starts, so every file's still must match its video.
     */
    @Test fun poster_frameMatchesTheVideo() {
        val shapes = mapOf(
            "video_land.mp4" to 16f / 9f, "video_port.mp4" to 9f / 16f, "video_hevc.mp4" to 16f / 9f,
            "video_vp9.webm" to 16f / 9f, "video_rot90.mp4" to 9f / 16f, "video_mov.mov" to 16f / 9f,
            "video_mkv.mkv" to 16f / 9f, "video_av1.mp4" to 16f / 9f,
        )
        for ((asset, aspect) in shapes) {
            val poster = videoPoster(context(), clipTile(asset))
            assertNotNull("$asset: no poster frame to show while it opens", poster)
            assertEquals("$asset poster shape", aspect, poster!!.width.toFloat() / poster.height, 0.03f)
        }
        // 10-bit HEVC: the emulator's retriever cannot read it at all, so no still is fine; one that comes back must be upright
        videoPoster(context(), clipTile("video_hevc10.mp4"))?.let {
            assertEquals("video_hevc10.mp4 poster shape", 16f / 9f, it.width.toFloat() / it.height, 0.03f)
        }
    }

    /** The one fallback: a file the decoder refuses shows the card, and its button hands it to the phone's player. */
    @Test fun brokenFile_showsTheCard_andItsButtonOpensThePhonePlayer() {
        var opened = 0
        rule.setContent {
            MemoriaTheme { Surface { PlayerFailed(Tile(1, Uri.parse("content://media/external/video/media/1"), 0, 0L)) { opened++ } } }
        }
        rule.onNodeWithText("This video can't play here").assertIsDisplayed()
        rule.onNodeWithText("Open in the phone's player").performClick()
        assertEquals(1, opened)
    }

    /** A portrait clip must letterbox, not fill: the bug was a TextureView stretched to the screen. */
    @Test fun portraitClip_keepsItsShapeOnScreen() {
        val played = play("video_port.mp4")
        assertNull("video_port.mp4: ${played.error?.errorCodeName}", played.error)
        assertTrue("a portrait clip is taller than wide, not stretched", played.aspect < 1f)
    }

    /**
     * While the page moves (opening, closing, the details), the video is a still. A TextureView is a real
     * view and ignores the page's motion, so it stayed full screen over the grid while the rest grew back
     * into its tile (seen on the phone 2026-11-12). The texture may only exist once the page is in place.
     */
    @Test fun videoPage_textureOnlyWhileThePageIsInPlace() {
        var shown by mutableStateOf(1f)
        var player: ExoPlayer? = null
        rule.runOnUiThread { player = ExoPlayer.Builder(context()).build() }
        rule.setContent {
            MemoriaTheme { Surface { Box(Modifier.size(360.dp, 480.dp)) {
                ViewerPage(clipTile("video_land.mp4"), onZoomed = {}, shown = { shown }, current = true, player = player)
            } } }
        }
        rule.onNodeWithTag("videoTexture").assertExists()
        rule.runOnIdle { shown = 0.5f }
        rule.onNodeWithTag("videoTexture").assertDoesNotExist()
        rule.runOnIdle { shown = 1f }
        rule.onNodeWithTag("videoTexture").assertExists()
        rule.runOnUiThread { player?.release() }
    }
}
