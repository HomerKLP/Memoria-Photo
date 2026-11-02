package app.memoria

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** [Thumb] that gets another tile in the same slot, as Library rows do when a scan reorders them. */
class ThumbTest {

    @get:Rule val rule = createComposeRule()

    private fun center(): Color = rule.onNodeWithTag("thumb").captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }

    @Test fun newTile_showsItsOwnPhoto() {
        thumbs.put(-1L, Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) })
        thumbs.put(-2L, Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) })
        val shown = mutableStateOf(Tile(-1L, Uri.parse("content://media/external/images/media/1"), 0L, null))
        rule.setContent { Thumb(shown.value, Modifier.size(40.dp).testTag("thumb")) }
        assertEquals(Color.Red, center())

        shown.value = Tile(-2L, Uri.parse("content://media/external/images/media/2"), 0L, null)
        rule.waitForIdle()
        assertEquals(Color.Blue, center())
    }
}
