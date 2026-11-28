package app.memoria

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/**
 * Open with from another app: [ViewActivity] gets ACTION_VIEW like a file manager or a chat app sends it
 * (mockup "Viewer, from another app"). Runs on an emulator, never on the phone.
 */
class OpenFromAppTest {

    @get:Rule val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val otherApp = "content://${instrumentation.context.packageName}.otherapp"

    private fun open(uri: Uri, type: String): ActivityScenario<ViewActivity> =
        ActivityScenario.launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).setClass(context, ViewActivity::class.java))

    @Test fun libraryPhoto_opensWithTheWholeBar_andBackCloses() {
        val values = ContentValues().apply {
            // a new name each run: the emulator keeps MediaStore rows across runs
            put(MediaStore.MediaColumns.DISPLAY_NAME, "openfromapp-${System.nanoTime()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/OpenFromAppTest")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        try {
            context.contentResolver.openOutputStream(uri)!!.use { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
            val scenario = open(uri, "image/jpeg")
            rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("Add to favorites").fetchSemanticsNodes().size == 1 }
            rule.onNodeWithContentDescription("Delete").assertIsDisplayed()
            rule.onNodeWithContentDescription("More").performClick()
            rule.onNodeWithText("Move to album").assertIsDisplayed()
            // no album screen behind this viewer
            rule.onNodeWithText("Show in album").assertDoesNotExist()
            Espresso.pressBackUnconditionally() // the menu
            rule.waitUntil(5_000) { rule.onAllNodesWithText("Move to album").fetchSemanticsNodes().isEmpty() }
            Espresso.pressBackUnconditionally() // the viewer, and the screen with it
            rule.waitUntil(5_000) { scenario.state == Lifecycle.State.DESTROYED }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun otherAppsPhoto_isReadOnly_withItsName() {
        open(Uri.parse("$otherApp/fixtures/jpg_none.jpg"), "image/jpeg").use {
            // the fixture has no EXIF date
            rule.waitUntil(5_000) { rule.onAllNodesWithText("No date").fetchSemanticsNodes().size == 1 }
            rule.onNodeWithContentDescription("Share").assertIsDisplayed()
            listOf("Add to favorites", "Delete", "More").forEach { rule.onNodeWithContentDescription(it).assertDoesNotExist() }
            rule.onNodeWithContentDescription("Details").performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithText("jpg_none.jpg").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun otherAppsVideo_plays() {
        open(Uri.parse("$otherApp/videos/video_port.mp4"), "video/mp4").use {
            // the player holds this page's video: its picture is on the page
            rule.waitUntil(20_000) { rule.onAllNodesWithTag("videoTexture").fetchSemanticsNodes().size == 1 }
            rule.onNodeWithContentDescription("More").assertDoesNotExist()
        }
    }
}
