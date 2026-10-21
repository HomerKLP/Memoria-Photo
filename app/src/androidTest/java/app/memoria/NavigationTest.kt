package app.memoria

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Where Back goes, on the real [App]: each case opens a few screens and checks that Back returns the same way.
 * The test adds one photo to MediaStore and one named person to the DB, and pauses indexing so a run can't
 * regroup them away. Runs on an emulator like `ViewerTest`, never on the phone.
 */
class NavigationTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.getExternalFilesDir(null), "test")
    private val dao = Db.get(context).index()
    private val photoId = 9_000_000_001L // a DB-only photo for the person, MediaStore never has it
    private var personId = 0L
    private var photo = -1L // the MediaStore photo, in Pictures/NavTest

    @Before fun setUp() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (p in listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.ACCESS_MEDIA_LOCATION)) {
            ui.grantRuntimePermission(context.packageName, p)
        }
        IndexControl.pause(context)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "navtest.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/NavTest")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        photo = ContentUris.parseId(uri)
        runBlocking {
            dao.insert(
                PhotoRow(photoId, "Pictures/NavTest/person.jpg", PhotoKind.camera),
                listOf(FaceRow(photoId = photoId, box = FloatArray(4), score = 0.9f, landmarks = FloatArray(10), vector = FloatArray(4), quality = 0.9f, thumb = ByteArray(0))),
            )
            val face = dao.photoFaces(photoId).single().id
            personId = dao.nextPersonId()
            dao.insertPerson(PersonRow(personId, face, name = "Navtest"))
            dao.setPerson(listOf(face), personId)
        }
    }

    @After fun tearDown() {
        context.contentResolver.delete(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photo), null, null)
        runBlocking {
            dao.deletePerson(personId)
            dao.deleteFaces(listOf(photoId))
            dao.deletePhotos(listOf(photoId))
        }
    }

    private fun start() = rule.setContent { MemoriaTheme { Surface { App(dir) } } }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun waitFor(text: String) = rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun openLibrary() {
        rule.onNodeWithText("Library").performClick()
        rule.waitForIdle()
    }

    // the "See all" on the same line as a Library section title; the rows are no semantics nodes, so siblings can't tell them apart
    private fun seeAll(section: String) {
        val y = rule.onNodeWithText(section).fetchSemanticsNode().boundsInRoot.center.y
        val links = rule.onAllNodesWithText("See all")
        links[links.fetchSemanticsNodes().indexOfFirst { abs(it.boundsInRoot.center.y - y) < 20f }].performScrollTo().performClick()
    }

    // the Library tab is the only one with a Settings button
    private fun assertOnLibrary() = rule.onNodeWithContentDescription("Settings").assertIsDisplayed()

    // the Photos tab is the only one with a Search button
    private fun assertOnPhotos() = rule.onNodeWithContentDescription("Search").assertIsDisplayed()

    @Test fun settingsPages_backGoesUpOnePageAtATime() {
        start()
        openLibrary()
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Open source, GPL-3.0 · Licenses").performScrollTo().performClick() // below the fold at 360 dp
        rule.onNodeWithText("Licenses").assertIsDisplayed()
        back()
        rule.onNodeWithText("Licenses").assertDoesNotExist()
        rule.onNodeWithText("Hidden people").performScrollTo().performClick()
        rule.onNodeWithText("Settings").assertDoesNotExist()
        rule.onNodeWithText("Hidden people").assertIsDisplayed()
        back()
        rule.onNodeWithText("Settings").assertIsDisplayed()
        back()
        assertOnLibrary()
    }

    @Test fun search_backReturnsToTheTabItOpenedFrom() {
        start()
        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNodeWithText("Search people, places, dates").assertIsDisplayed()
        back()
        assertOnPhotos()
        openLibrary()
        rule.onNodeWithText("Search people, places, dates").performClick()
        rule.onNodeWithContentDescription("Settings").assertDoesNotExist()
        back()
        assertOnLibrary()
    }

    @Test fun person_fromLibrary_backReturnsToLibrary() {
        start()
        openLibrary()
        waitFor("Navtest")
        rule.onNodeWithText("Navtest").performClick()
        rule.onNodeWithText("1 photo · 1 face").assertIsDisplayed()
        back()
        assertOnLibrary()
    }

    @Test fun person_fromPeople_backReturnsToTheList() {
        start()
        openLibrary()
        seeAll("People")
        waitFor("Navtest")
        rule.onAllNodesWithText("Navtest").onFirst().performClick()
        rule.onNodeWithText("1 photo · 1 face").assertIsDisplayed()
        back()
        rule.onNodeWithText("1 photo · 1 face").assertDoesNotExist()
        rule.onNodeWithText("People").assertIsDisplayed()
        back()
        assertOnLibrary()
    }

    @Test fun search_personHit_backReturnsToSearchWithTheText() {
        start()
        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Navt")
        waitFor("Navtest")
        rule.onAllNodesWithText("Navtest").onFirst().performClick()
        rule.onNodeWithText("1 photo · 1 face").assertIsDisplayed()
        back()
        rule.onNodeWithText("Navt").assertIsDisplayed()
        back()
        assertOnPhotos()
    }

    @Test fun newAlbum_backFromThePicker_reopensTheSheetWithTheName() {
        start()
        openLibrary()
        seeAll("Albums")
        rule.onNodeWithText("New album").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Navtest album")
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Add to Navtest album").assertIsDisplayed()
        back()
        rule.onNodeWithText("Add to Navtest album").assertDoesNotExist()
        rule.onNodeWithText("Navtest album").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Albums").assertIsDisplayed()
        back()
        assertOnLibrary()
    }

    @Test fun showInAlbum_backReturnsToTheScreenUnderTheViewer() {
        start()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("tile-$photo").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("tile-$photo").performClick()
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Show in album").performClick()
        rule.onNodeWithText("NavTest").assertIsDisplayed()
        back()
        rule.onNodeWithText("NavTest").assertDoesNotExist()
        rule.onNodeWithContentDescription("More").assertDoesNotExist()
        assertOnPhotos()
    }

    @Test fun restore_keepsTheOpenScreensAndTheirOrder() {
        val restore = StateRestorationTester(rule)
        restore.setContent { MemoriaTheme { Surface { App(dir) } } }
        openLibrary()
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Open source, GPL-3.0 · Licenses").performScrollTo().performClick() // below the fold at 360 dp
        restore.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Licenses").assertIsDisplayed()
        back()
        rule.onNodeWithText("Settings").assertIsDisplayed()
        back()
        assertOnLibrary()
    }
}
