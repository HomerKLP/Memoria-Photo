package app.memoria

import android.net.Uri
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

/** Viewer details: Place names the nearest town and never shows coordinates (mockup "Viewer, swiped up"). */
class DetailsPlaceTest {

    @get:Rule val rule = createComposeRule()

    private val tile = Tile(1, Uri.parse("content://media/external/images/media/1"), 1_700_000_000_000L, null)

    @Test fun place_showsTownName() {
        rule.setContent { MemoriaTheme { Surface { DetailsContent(tile, PhotoInfo("a.jpg", "DCIM/Camera", 0, 0, 0, "Riverton", null, null), emptyList(), emptyList()) } } }
        rule.onNodeWithText("Place").assertIsDisplayed()
        rule.onNodeWithText("Riverton").assertIsDisplayed()
    }

    @Test fun noGps_hidesPlace() {
        rule.setContent { MemoriaTheme { Surface { DetailsContent(tile, PhotoInfo("a.jpg", "DCIM/Camera", 0, 0, 0, null, null, null), emptyList(), emptyList()) } } }
        rule.onNodeWithText("Place").assertDoesNotExist()
    }
}
