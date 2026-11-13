package app.memoria

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The real [Viewer] with Android's trash confirm faked as "OK", so the photo list shrinks under the pager.
 * Android refuses to trash photos that do not exist, so the test adds three small ones to MediaStore.
 * Runs on an emulator like `BehaviorTest`, never on the phone.
 */
class ViewerTest {

    @get:Rule val rule = createComposeRule()

    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    private val tiles = (1..3).map { n ->
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "viewertest$n.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ViewerTest")
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        Tile(ContentUris.parseId(uri), uri, 0L, null)
    }

    @After fun removeTestPhotos() {
        tiles.forEach { resolver.delete(it.uri, null, null) }
    }

    private val confirmOk = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, Activity.RESULT_OK, Intent())
            }
        }
    }

    private fun trash(start: Tile) {
        rule.setContent {
            MemoriaTheme { CompositionLocalProvider(LocalActivityResultRegistryOwner provides confirmOk) {
                Viewer(start.id, tiles, onBack = {})
            } }
        }
        rule.onNodeWithContentDescription("Delete").performClick()
        rule.waitForIdle()
    }

    @Test fun trash_lastPhoto_showsThePreviousOne() {
        trash(tiles[2])
        assertEquals(tiles[1].id, viewerTile)
    }

    @Test fun trash_secondToLastPhoto_showsTheLastOne() {
        trash(tiles[1])
        assertEquals(tiles[2].id, viewerTile)
    }

    /** A real clip from the test APK (the one `VideoFileTest` uses), copied out, so the viewer's player has a file it can prepare. */
    private fun videoTile(): Tile {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "viewer_swipe.mp4")
        instrumentation.context.assets.open("videos/video_land.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        return Tile(9_001, Uri.fromFile(file), 0L, 60_000L)
    }

    /** How many pages hold the player's view. */
    private fun textures() = rule.onAllNodesWithTag("videoTexture").fetchSemanticsNodes().size

    /**
     * Swiping from a video to the next photo and back: the page that leaves must give up the texture and the
     * video page must get it back once it is in view. Two textures at once played the wrong clip (2026-11-12).
     */
    @Test fun swipe_videoToPhotoAndBack_movesTheTexture() {
        viewerTile = null
        val video = videoTile()
        rule.setContent { MemoriaTheme { Viewer(video.id, listOf(video, tiles[0], tiles[1]), onBack = {}) } }
        rule.waitUntil(20_000) { viewerTile == video.id }
        rule.waitUntil(10_000) { textures() == 1 }

        rule.onRoot().performTouchInput { swipeLeft() }
        rule.waitUntil(20_000) { viewerTile == tiles[0].id }
        rule.waitForIdle()
        rule.waitUntil(10_000) { textures() == 0 }

        rule.onRoot().performTouchInput { swipeRight() }
        rule.waitUntil(20_000) { viewerTile == video.id }
        rule.waitUntil(10_000) { textures() == 1 }
    }
}
