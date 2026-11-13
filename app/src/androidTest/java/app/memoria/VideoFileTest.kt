package app.memoria

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
import androidx.compose.ui.test.junit4.ComposeContentTestRule
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
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/*
 * Real video files through the app's player and its own page, the way `PhotoFileTest` does it for
 * photos: the shape a file reports decides how it fits the screen (a portrait clip was stretched in
 * the viewer on 2026-11-12), and a file the decoder refuses must reach the "cannot play" card.
 *
 * The clips in `assets/videos/` are the ones a library actually holds; `media.tsv` lists each with what
 * it must do. Files are made by `uv run bench/videofix.py` and committed (a few KB each). The emulator
 * has no hardware decoders, so a clip its software decoder refuses fails here: only the phone proves
 * every codec. A clip whose codec has no decoder at all on the device skips. Runs with the other
 * androidTests (`make layout`).
 */

private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

private val videoRows get() = mediaTable().filter { it["file"]!!.startsWith("videos/") }

private fun shapeOf(row: Map<String, String>) = row["shape"]!!.split(':').let { it[0].toFloat() / it[1].toFloat() }

/** The asset (a `media.tsv` path) copied out of the test APK to a plain file the player can open. */
private fun clipFile(asset: String): File {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val file = File(instrumentation.targetContext.cacheDir, asset.substringAfterLast('/'))
    instrumentation.context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
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
private fun play(rule: ComposeContentTestRule, asset: String): Played {
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

/** Every video row of `media.tsv` plays in the real viewer page the way its row says. A new row is a new case. */
@RunWith(Parameterized::class)
class VideoPlayTest(private val name: String, private val row: Map<String, String>) {

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun rows(): List<Array<Any>> = videoRows.map { arrayOf(it["file"]!!, it) }
    }

    @get:Rule val rule = createComposeRule()

    @Test fun plays_asItsRowSays() {
        videoMimes[row["codec"]]?.let { assumeTrue("no ${row["codec"]} decoder on this device", hasDecoder(it)) }
        val played = play(rule, name)
        val error = played.error
        when (row["expect"]) {
            "card" -> assertNotNull("$name must fail to play, so the viewer shows the card", error)
            // only the decoder may refuse it (the 4xxx codes); a file or container error is a real bug
            "plays or card" -> if (error != null) assertTrue("$name: ${error.errorCodeName}", error.errorCode in 4000..4999)
                else assertEquals("$name shape", shapeOf(row), played.aspect, 0.01f)
            else -> {
                assertNull("$name: ${error?.errorCodeName}", error)
                assertEquals("$name shape", shapeOf(row), played.aspect, 0.01f)
                assertEquals("$name plays its sound", row["sound"] == "yes", played.sound)
            }
        }
    }
}

class VideoFileTest {

    @get:Rule val rule = createComposeRule()

    /**
     * The poster is the still the open and close motion moves, and the page is sized by it until the
     * player knows the video's own size. A poster of another shape (a turned video's stored frame, a
     * sideways decode) makes the video jump as it starts, so every file's still must match its video.
     */
    @Test fun poster_frameMatchesTheVideo() {
        for (row in videoRows.filter { it["expect"] != "card" }) {
            val asset = row["file"]!!
            val poster = videoPoster(context(), clipTile(asset))
            // "plays or card": the emulator's retriever cannot read 10-bit HEVC at all, so no still is fine; one that comes back must be upright
            if (poster == null && row["expect"] == "plays or card") continue
            assertNotNull("$asset: no poster frame to show while it opens", poster)
            assertEquals("$asset poster shape", shapeOf(row), poster!!.width.toFloat() / poster.height, 0.03f)
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

    /**
     * The video moves with its page like a photo: Compose draws a TextureView inside its own layers. The
     * 2026-11-12 fix believed it did not, and showed a still while the page moved, so the picture swapped
     * frames on every drag and on close. Pull the page down by half its height: the middle must empty.
     */
    @Test fun videoPage_pictureMovesWithThePage() {
        var dragY by mutableFloatStateOf(0f)
        val tile = clipTile("videos/video_land.mp4")
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
        val tile = clipTile("videos/video_broken.mp4")
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
        val land = clipTile("videos/video_land.mp4", id = 1)
        val port = clipTile("videos/video_port.mp4", id = 2)
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
