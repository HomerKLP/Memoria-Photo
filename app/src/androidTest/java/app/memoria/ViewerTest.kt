package app.memoria

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
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
}
