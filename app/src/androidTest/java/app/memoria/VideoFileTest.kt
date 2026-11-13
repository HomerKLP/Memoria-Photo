package app.memoria

import android.media.MediaCodecList
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * .mkv download, VP9, AV1, 10-bit HEVC and an old phone's H.263 .3gp; the camera-like ones have sound. Files are made by `uv run bench/videofix.py` and committed
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

    private fun clipTile(asset: String, id: Long = 1) = Tile(id, Uri.fromFile(clipFile(asset)), 0L, 60_000L)

    private data class Played(val aspect: Float, val error: PlaybackException?, val sound: Boolean)

    /** What the player's callbacks say; the test thread reads it while the main thread writes. */
    private class Probe {
        @Volatile var width = 0
        @Volatile var height = 0
        @Volatile var error: PlaybackException? = null
        @Volatile var sound = false // an audio track was picked: one the phone cannot decode plays silent, no error
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

                    override fun onTracksChanged(tracks: Tracks) {
                        probe.sound = tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)
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
        return Played(videoAspectOf(probe.width, probe.height), probe.error, probe.sound)
    }

    private fun shape(asset: String, aspect: Float, sound: Boolean = false) {
        val played = play(asset)
        assertNull("$asset: ${played.error?.errorCodeName}", played.error)
        assertEquals("$asset shape", aspect, played.aspect, 0.01f)
        assertEquals("$asset plays its sound", sound, played.sound)
    }

    /** True when this phone has any decoder for [mime]: the emulator has none for some codecs. */
    private fun hasDecoder(mime: String): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }

    @Test fun landscapeClip_reportsItsShape() = shape("video_land.mp4", 320f / 180f, sound = true)

    @Test fun portraitClip_reportsItsShape() = shape("video_port.mp4", 180f / 320f)

    @Test fun hevcClip_reportsItsShape() = shape("video_hevc.mp4", 320f / 180f)

    @Test fun vp9Clip_reportsItsShape() = shape("video_vp9.webm", 320f / 180f, sound = true)

    @Test fun movClip_reportsItsShape() = shape("video_mov.mov", 320f / 180f, sound = true)

    /** An old phone's clip: H.263 with AAC in a .3gp, 176x144. */
    @Test fun threeGpClip_reportsItsShape() = shape("video_3gp.3gp", 176f / 144f, sound = true)

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
        val error = played.error
        if (error == null) assertEquals("video_hevc10.mp4 shape", 16f / 9f, played.aspect, 0.01f)
        // only the decoder may refuse it (the 4xxx codes); a file or container error is a real bug
        else assertTrue("video_hevc10.mp4: ${error.errorCodeName}", error.errorCode in 4000..4999)
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
            "video_mkv.mkv" to 16f / 9f, "video_av1.mp4" to 16f / 9f, "video_3gp.3gp" to 176f / 144f,
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
     * The video moves with its page like a photo: Compose draws a TextureView inside its own layers. The
     * 2026-11-12 fix believed it did not, and showed a still while the page moved, so the picture swapped
     * frames on every drag and on close. Pull the page down by half its height: the middle must empty.
     */
    @Test fun videoPage_pictureMovesWithThePage() {
        var dragY by mutableFloatStateOf(0f)
        val tile = clipTile("video_land.mp4")
        val probe = Probe() // written on the main thread, read here
        var player: ExoPlayer? = null
        rule.runOnUiThread {
            // paused: the first frame (red) stays on the texture
            player = ExoPlayer.Builder(context()).build().also { p ->
                p.addListener(object : Player.Listener { override fun onRenderedFirstFrame() { probe.width = 1 } })
                p.setMediaItem(MediaItem.fromUri(tile.uri))
                p.prepare()
            }
        }
        rule.setContent {
            MemoriaTheme { Surface { Box(Modifier.size(360.dp, 480.dp).testTag("box")) {
                ViewerPage(tile, onZoomed = {}, dragY = { dragY }, current = true, player = player)
            } } }
        }
        rule.waitUntil(20_000) { probe.width == 1 }
        rule.onNodeWithTag("videoTexture").assertExists()
        fun middleIsVideo(): Boolean {
            val pixels = rule.onNodeWithTag("box").captureToImage().toPixelMap()
            val c = pixels[pixels.width / 2, pixels.height / 2]
            return maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue) > 0.4f // a clip colour, not the plain background
        }
        assertTrue("the frame shows in the middle", middleIsVideo())
        rule.runOnIdle { dragY = with(rule.density) { 240.dp.toPx() } }
        rule.onNodeWithTag("videoTexture").assertExists()
        assertFalse("pulled down by half the page, the video left the middle", middleIsVideo())
        rule.runOnUiThread { player?.release() }
    }

    /** The real viewer plays the broken file, reaches the card, and keeps the top bar over it (mockup "Video, cannot play"). */
    @Test fun brokenFile_inTheViewer_showsTheCard() {
        val tile = clipTile("video_broken.mp4")
        rule.setContent { MemoriaTheme { Viewer(tile.id, listOf(tile), onBack = {}) } }
        rule.waitUntil(20_000) { rule.onAllNodesWithText("This video can't play here").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Open in the phone's player").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").assertIsDisplayed()
        rule.onNodeWithContentDescription("Play").assertDoesNotExist() // the card replaces the player line
    }

    /**
     * A neighbour video keeps its own shape: the player's size was handed to every page, so a portrait clip
     * next to a playing landscape one slid in as a small landscape box, then jumped (2026-11-13).
     */
    @Test fun viewer_neighbourVideo_keepsItsOwnShape() {
        val land = clipTile("video_land.mp4", id = 1)
        val port = clipTile("video_port.mp4", id = 2)
        rule.setContent { MemoriaTheme { Viewer(land.id, listOf(land, port), onBack = {}) } }
        fun shapes() = rule.onAllNodesWithTag("videoBox").fetchSemanticsNodes().map { it.size.width.toFloat() / it.size.height }.sorted()
        rule.waitUntil(20_000) { rule.onAllNodesWithTag("videoTexture").fetchSemanticsNodes().size == 1 }
        rule.waitUntil(20_000) { shapes().size == 2 && shapes()[0] < 1f } // the portrait poster is in
        Thread.sleep(1_500) // the player reports the landscape clip's size
        rule.waitForIdle()
        assertEquals("the portrait neighbour", 9f / 16f, shapes()[0], 0.03f)
        assertEquals("the playing landscape clip", 16f / 9f, shapes()[1], 0.03f)
    }
}
