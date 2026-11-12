package app.memoria

import android.net.Uri
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Real video files through the app's player and its own page, the way `PhotoFileTest` does it for
 * photos: the shape a file reports decides how it fits the screen (a portrait clip was stretched in
 * the viewer on 2026-11-12), and a file the decoder refuses must reach the "cannot play" card.
 *
 * A video only decodes with a surface, so each case renders the real `ViewerPage`. Files are made by
 * `uv run bench/videofix.py` and committed under `assets/videos/` (a few KB each). The emulator has no
 * hardware decoders, so a clip its software decoder refuses fails here: only the phone proves every
 * codec. Runs with the other androidTests (`make layout`).
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

    private data class Played(val aspect: Float, val error: PlaybackException?)

    /** What the player's callbacks say; the test thread reads it while the main thread writes. */
    private class Probe {
        @Volatile var width = 0
        @Volatile var height = 0
        @Volatile var error: PlaybackException? = null
    }

    /** Plays the asset in the real viewer page and reports the shape the player says, or its error. */
    private fun play(asset: String): Played {
        val file = clipFile(asset)
        val tile = Tile(1, Uri.fromFile(file), 0L, 60_000L)
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

    @Test fun landscapeClip_reportsItsShape() = shape("video_land.mp4", 320f / 180f)

    @Test fun portraitClip_reportsItsShape() = shape("video_port.mp4", 180f / 320f)

    @Test fun hevcClip_reportsItsShape() = shape("video_hevc.mp4", 320f / 180f)

    @Test fun vp9Clip_reportsItsShape() = shape("video_vp9.webm", 320f / 180f)

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
}
