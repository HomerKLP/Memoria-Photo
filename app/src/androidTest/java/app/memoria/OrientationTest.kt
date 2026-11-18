package app.memoria

import android.app.UiAutomation
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The viewer turns with the phone, every other screen stays portrait (ROADMAP, "Turn with the phone").
 * The test activity is locked portrait like `MainActivity`, so only the viewer's own request can turn it.
 * Runs on an emulator like `ViewerTest`, never on the phone.
 */
class OrientationTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    private val tiles = (1..2).map { n ->
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "orientationtest$n-${System.nanoTime()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/OrientationTest")
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        Tile(ContentUris.parseId(uri), uri, 0L, null)
    }

    @After fun upright() {
        ui.setRotation(UiAutomation.ROTATION_FREEZE_0)
        ui.setRotation(UiAutomation.ROTATION_UNFREEZE)
        // unfreeze leaves auto-rotate off on the emulator, which would surprise the next session
        ui.executeShellCommand("settings put system accelerometer_rotation 1").use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        rule.waitForIdle()
        tiles.forEach { resolver.delete(it.uri, null, null) }
    }

    private fun orientation() = rule.activity.resources.configuration.orientation

    private fun turn() {
        ui.setRotation(UiAutomation.ROTATION_FREEZE_90)
        rule.waitUntil(10_000) { orientation() == Configuration.ORIENTATION_LANDSCAPE }
        rule.waitForIdle()
    }

    private fun uprightAgain() {
        ui.setRotation(UiAutomation.ROTATION_FREEZE_0)
        rule.waitUntil(10_000) { orientation() == Configuration.ORIENTATION_PORTRAIT }
        rule.waitForIdle()
    }

    /** Turning keeps the viewer, the photo and its bars: the activity is not rebuilt. */
    @Test fun turn_keepsTheSamePhoto() {
        viewerTile = null
        rule.setContent { MemoriaTheme { Viewer(tiles[0].id, tiles, onBack = {}) } }
        rule.waitUntil(10_000) { viewerTile == tiles[0].id }

        turn()
        assertEquals(tiles[0].id, viewerTile)
        rule.onNodeWithContentDescription("Back").assertIsDisplayed()

        uprightAgain()
        assertEquals(tiles[0].id, viewerTile)
    }

    /** Sideways the details are a sheet over the photo: 74% of the width, 44% of the height, on the bottom edge. */
    @Test fun sideways_detailsAreASheetOnTheBottomEdge() {
        rule.setContent { MemoriaTheme { Viewer(tiles[0].id, tiles, onBack = {}) } }
        rule.waitUntil(10_000) { viewerTile == tiles[0].id }
        turn()

        rule.onNodeWithContentDescription("Details").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("detailsSheet").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        val sheet = rule.onNodeWithTag("detailsSheet", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val frame = rule.onRoot().getUnclippedBoundsInRoot()
        assertEquals(landscapeSheetWidth, sheet.width.value / frame.width.value, 0.01f)
        assertEquals(landscapeSheetHeight, sheet.height.value / frame.height.value, 0.01f)
        assertEquals(frame.bottom.value, sheet.bottom.value, 1f)
    }

    /** A playing video keeps its picture through the turn: the activity and its player are the same ones. */
    @Test fun turn_whileAVideoPlays_keepsThePicture() {
        viewerTile = null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "orientation_turn.mp4")
        instrumentation.context.assets.open("videos/video_land.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        val video = Tile(9_002, Uri.fromFile(file), 0L, 60_000L)
        rule.setContent { MemoriaTheme { Viewer(video.id, listOf(video), onBack = {}) } }
        rule.waitUntil(20_000) { rule.onAllNodesWithTag("videoTexture").fetchSemanticsNodes().size == 1 }

        turn()
        assertEquals(1, rule.onAllNodesWithTag("videoTexture").fetchSemanticsNodes().size)
    }

    /** Sideways a swipe up carries the sheet over the photo to the very top, and the top bar steps aside. */
    @Test fun sideways_swipeUp_givesTheSheetTheWholeScreen() {
        rule.setContent { MemoriaTheme { Viewer(tiles[0].id, tiles, onBack = {}) } }
        rule.waitUntil(10_000) { viewerTile == tiles[0].id }
        turn()

        rule.onNodeWithContentDescription("Details").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("detailsSheet").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.onNodeWithTag("detailsSheet").performTouchInput { swipeUp() }
        rule.waitForIdle()

        val frame = rule.onRoot().getUnclippedBoundsInRoot()
        val sheet = rule.onNodeWithTag("detailsSheet", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(1f, sheet.height.value / frame.height.value, 0.01f)
        rule.onNodeWithContentDescription("Back").assertIsNotDisplayed()

        // a pull down takes the whole sheet away in one gesture, at either stop
        rule.onNodeWithTag("detailsSheet").performTouchInput { swipeDown(startY = height * 0.1f, endY = height * 0.6f) }
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag("detailsSheet").fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    /** Closing the viewer while sideways brings the app back upright, as the mockup says. */
    @Test fun close_sideways_returnsTheAppToPortrait() {
        var backs = 0
        rule.setContent {
            MemoriaTheme {
                var open by remember { mutableStateOf(true) }
                if (open) Viewer(tiles[0].id, tiles, onBack = { open = false; backs++ })
            }
        }
        rule.waitUntil(10_000) { viewerTile == tiles[0].id }
        turn()

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(10_000) { backs == 1 }
        // the viewer left the screen, so its dispose put the app back to portrait
        rule.waitUntil(10_000) { rule.activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        uprightAgain()
        assertEquals(Configuration.ORIENTATION_PORTRAIT, orientation())
    }
}
