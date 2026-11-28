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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
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
        context.getSharedPreferences("media", 0).edit().putBoolean("asked", true).commit() // the first-open question would cover every screen
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
            dao.deleteVectors(listOf(photo))
            dao.deleteTags(listOf(photo))
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

    // a sideways swipe from inside the screen, clear of the month slider's strip on the right edge
    private fun swipe(left: Boolean) {
        rule.onRoot().performTouchInput { if (left) swipeLeft(width * 0.8f, width * 0.2f) else swipeRight(width * 0.2f, width * 0.8f) }
        rule.waitForIdle()
    }

    // the Library tab is the only one with a Settings button
    private fun assertOnLibrary() = rule.onNodeWithContentDescription("Settings").assertIsDisplayed()

    // the Photos tab is the only one with a Search button
    private fun assertOnPhotos() = rule.onNodeWithContentDescription("Search").assertIsDisplayed()

    // Media management, first open (mockup): the photos are allowed already, so the question shows at once; Not now closes it
    @Test fun firstOpen_asksMediaManagement_notNowCloses() {
        context.getSharedPreferences("media", 0).edit().clear().commit()
        start()
        waitFor("Use Memoria's own questions?")
        rule.onNodeWithText("Not now").performClick()
        rule.onNodeWithText("Use Memoria's own questions?").assertDoesNotExist()
        assertOnPhotos()
    }

    // Photos tab select bar: Delete asks ours with Media management on (mockup "Photos, selecting"); Cancel keeps the photo
    @Test fun mediaOn_photosSelectDelete_asksOurs() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun mediaSwitch(on: Boolean) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            ui.executeShellCommand("appops set ${context.packageName} MANAGE_MEDIA ${if (on) "allow" else "default"}"),
        ).use { it.readBytes() }
        start()
        try {
            mediaSwitch(on = true)
            rule.waitUntil(5_000) { rule.onAllNodesWithTag("tile-$photo").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("tile-$photo").performTouchInput { longClick() }
            rule.onNodeWithText("Delete").performClick()
            rule.onNodeWithText("Move 1 photo to trash?").assertIsDisplayed()
            rule.onNodeWithText("Cancel").performClick()
            rule.onNodeWithText("Move 1 photo to trash?").assertDoesNotExist()
            rule.onNodeWithTag("tile-$photo").assertExists()
        } finally {
            mediaSwitch(on = false)
        }
    }

    @Test fun updateNotification_opensSettingsAtTheBottom_backGoesToLibrary() {
        rule.setContent { MemoriaTheme { Surface { App(dir, openSettings = 1, atUpdates = true) } } }
        waitFor("Dev tools")
        rule.waitForIdle()

        rule.onNodeWithText("Dev tools").assertIsDisplayed()
        back()
        assertOnLibrary()
    }

    // the debug shortcut (`make jump --es screen Places`): straight to a screen, no tapping through the app
    @Test fun jump_opensTheScreen_backGoesToTheTabUnderIt() {
        rule.setContent { MemoriaTheme { Surface { App(dir, jump = Jump("places", null, 1)) } } }
        waitFor("Places")
        back()
        assertOnLibrary()
    }

    @Test fun jump_opensTheTab() {
        rule.setContent { MemoriaTheme { Surface { App(dir, jump = Jump(null, "library", 1)) } } }
        assertOnLibrary()
    }

    @Test fun settingsPages_backGoesUpOnePageAtATime() {
        start()
        openLibrary()
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Open source, GPL-3.0 · Licenses").performScrollTo().performClick() // below the fold at 360 dp
        rule.onNodeWithText("Licenses").assertIsDisplayed()
        back()
        rule.onNodeWithText("Licenses").assertDoesNotExist()
        rule.onNodeWithText("Hidden people & pets").performScrollTo().performClick()
        rule.onNodeWithText("Settings").assertDoesNotExist()
        rule.onNodeWithText("Hidden people & pets").assertIsDisplayed()
        back()
        rule.onNodeWithText("Settings").assertExists() // the title scrolled up with the page, so not on screen
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

    // the MediaStore photo gets the Pets tag's own vector, so it scores 1 and Pets is there
    private fun tagAsPet() = runBlocking { dao.insertVector(PhotoVectorRow(photo, Tagger.VERSION, Tag.load(context).first { it.id == "pet" }.vector)) }

    @Test fun category_fromLibrary_backGoesUpOneScreenAtATime() {
        tagAsPet()
        start()
        openLibrary()
        waitFor("Categories")
        seeAll("Categories")
        // other photos on the emulator may put Pets further down the grid
        rule.waitUntil(5_000) { rule.onAllNodesWithText("categor", substring = true).fetchSemanticsNodes().isNotEmpty() } // "N categories": loaded
        rule.onNodeWithTag("categoriesGrid").performScrollToNode(hasText("Pets"))
        rule.onNodeWithText("Pets").performClick()
        rule.onNodeWithTag("placeGrid").assertIsDisplayed()
        back()
        rule.onNodeWithTag("placeGrid").assertDoesNotExist()
        rule.onNodeWithText("Categories").assertIsDisplayed()
        rule.onNodeWithContentDescription("Settings").assertDoesNotExist()
        back()
        assertOnLibrary()
    }

    @Test fun search_categoryWordHit_backReturnsToSearchWithTheText() {
        tagAsPet()
        start()
        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("do")
        rule.waitUntil(5_000) { rule.onAllNodesWithText("dog · ", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Pets").performClick()
        rule.onNodeWithTag("placeGrid").assertIsDisplayed()
        back()
        rule.onNodeWithText("do").assertIsDisplayed()
        back()
        assertOnPhotos()
    }

    @Test fun person_fromLibrary_backReturnsToLibrary() {
        start()
        openLibrary()
        waitFor("Navtest")
        rule.onNodeWithText("Navtest").performClick()
        rule.onNodeWithText("1 face matches Navtest").assertIsDisplayed()
        back()
        assertOnLibrary()
    }

    // Photos and Library slide side by side (mockup "Tabs" swipe): left opens Library, right goes back to Photos
    @Test fun swipe_movesBetweenTabs() {
        start()
        assertOnPhotos()
        swipe(left = true)
        assertOnLibrary()
        swipe(left = false)
        assertOnPhotos()
    }

    // No swipe while selecting: the Select bar stays and Library stays out of view
    @Test fun swipe_whileSelecting_staysOnPhotos() {
        start()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("tile-$photo").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("tile-$photo").performTouchInput { longClick() }
        swipe(left = true)
        rule.onNodeWithText("Delete").assertIsDisplayed()
        rule.onNodeWithContentDescription("Settings").assertDoesNotExist()
    }

    // The People row keeps its drag at its ends, so a drag on it never slides to Photos
    @Test fun swipe_onPeopleRow_staysOnLibrary() {
        start()
        openLibrary()
        waitFor("Navtest")
        rule.onNodeWithTag("peopleRow").performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertOnLibrary()
    }

    @Test fun person_fromPeople_backReturnsToTheList() {
        start()
        openLibrary()
        seeAll("People & pets")
        waitFor("Navtest")
        rule.onAllNodesWithText("Navtest").onFirst().performClick()
        rule.onNodeWithText("1 face matches Navtest").assertIsDisplayed()
        back()
        rule.onNodeWithText("1 face matches Navtest").assertDoesNotExist()
        rule.onNodeWithText("People & pets").assertIsDisplayed()
        back()
        assertOnLibrary()
    }

    @Test fun search_personHit_backReturnsToSearchWithTheText() {
        start()
        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Navt")
        waitFor("Navtest")
        rule.onAllNodesWithText("Navtest").onFirst().performClick()
        rule.onNodeWithText("1 face matches Navtest").assertIsDisplayed()
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
        rule.onNodeWithText("Settings").assertExists() // the title scrolled up with the page, so not on screen
        back()
        assertOnLibrary()
    }

    @Test fun foldersToSkip_backReturnsToSettings() {
        start()
        openLibrary()
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Folders to skip").performScrollTo().performClick()
        rule.onNodeWithText("A checked album is not indexed. Its photos stay").assertIsDisplayed()
        back()
        rule.onNodeWithText("Only while charging").assertIsDisplayed()
        back()
        assertOnLibrary()
    }

    /** A dark mode or font size switch restarts the Activity; the open photo stays open. */
    @Test fun restore_keepsTheOpenPhoto() {
        val restore = StateRestorationTester(rule)
        restore.setContent { MemoriaTheme { Surface { App(dir) } } }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("tile-$photo").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("tile-$photo").performClick()
        rule.onNodeWithContentDescription("More").assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()
        rule.onNodeWithContentDescription("More").assertIsDisplayed()
    }
}
