package app.memoria

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The layout rule from AGENTS.md as a machine check: when something appears, hides, loads or
 * scrolls, nothing else on screen may move. Each test renders one state, notes an anchor's bounds,
 * switches state, and asserts the anchor did not move.
 *
 * This runs on a device or emulator (./gradlew :app:connectedDebugAndroidTest). Use a 360 dp wide
 * image, the mockup's width.
 */
class LayoutJumpTest {

    @get:Rule val rule = createComposeRule()

    private fun SemanticsNodeInteraction.top(): Dp = getUnclippedBoundsInRoot().top
    private fun SemanticsNodeInteraction.width(): Dp = getUnclippedBoundsInRoot().let { it.right - it.left }
    private fun SemanticsNodeInteraction.height(): Dp = getUnclippedBoundsInRoot().let { it.bottom - it.top }

    private fun assertSame(name: String, before: Dp, after: Dp) =
        assertEquals("$name moved", before.value, after.value, 0.5f)

    // --- fakes ---------------------------------------------------------------------------------

    private fun face(id: Long, quality: Float = 0.9f) =
        FaceVec(id, id, floatArrayOf(0f, 0f, 50f, 50f), FloatArray(512), quality, "camera", null)

    private fun tile(id: Long) = Tile(id, Uri.parse("content://media/external/images/media/$id"), 0L, null)

    /** [named] false: every person has no name, the case that used to shrink the row. [people] overrides the row (empty = the "No people yet" case). */
    private fun library(named: Boolean = true, people: List<Person>? = null, places: List<Place> = emptyList()): LibraryData {
        val faces = (1L..4L).map { i ->
            val f = face(i)
            Person(listOf(f), f, i, if (named) "Person $i" else null)
        }
        return LibraryData(
            faces = people ?: faces,
            unsure = listOf(face(101, 0.3f), face(102, 0.3f)),
            albums = listOf("Camera", "Screenshots", "Telegram").mapIndexed { i, n -> Album(i.toLong(), n, listOf(tile(i.toLong()))) },
            favorites = 38,
            videos = 211,
            trash = 7,
            total = 1706,
            places = places,
        )
    }

    private val trashed = (1L..9L).map { Trashed(tile(it), daysLeft = (it % 30).toInt()) }

    // --- tests ---------------------------------------------------------------------------------

    @Test fun library_loadingToLoaded_albumsHeaderStaysPut() {
        var data by mutableStateOf<LibraryData?>(null)
        rule.setContent { MemoriaTheme { Surface { LibraryContent(data) } } }

        val before = rule.onNodeWithText("Albums").top()
        rule.runOnIdle { data = library() }
        assertSame("Albums header", before, rule.onNodeWithText("Albums").top())
    }

    @Test fun library_pauseResume_progressBarKeepsLength() {
        var indexing by mutableStateOf(true)
        var paused by mutableStateOf(false)
        rule.setContent { MemoriaTheme { Surface { LibraryContent(library(), indexing = indexing, paused = paused, indexed = 412) } } }

        val barBefore = rule.onNodeWithTag("indexBar").width()
        val actionBefore = rule.onNodeWithTag("indexAction").width()
        rule.runOnIdle { indexing = false; paused = true }
        assertSame("progress bar", barBefore, rule.onNodeWithTag("indexBar").width())
        assertSame("Pause/Resume box", actionBefore, rule.onNodeWithTag("indexAction").width())
    }

    @Test fun library_waitingToRunning_progressBarKeepsLength() {
        var waiting by mutableStateOf(true)
        rule.setContent { MemoriaTheme { Surface { LibraryContent(library(), indexing = !waiting, waiting = waiting, indexed = 412) } } }

        val barBefore = rule.onNodeWithTag("indexBar").width()
        val actionBefore = rule.onNodeWithTag("indexAction").width()
        rule.runOnIdle { waiting = false }
        assertSame("progress bar", barBefore, rule.onNodeWithTag("indexBar").width())
        assertSame("Pause box", actionBefore, rule.onNodeWithTag("indexAction").width())
    }

    @Test fun settings_countLoadedAndAllowed_rowsBelowStayPut() {
        var hidden by mutableStateOf<Int?>(null)
        var allowed by mutableStateOf(false)
        rule.setContent { MemoriaTheme { Surface { SettingsContent(charging = false, allowed = allowed, hidden = hidden, version = "0.1") } } }

        val foldersBefore = rule.onNodeWithText("Folders to skip").top()
        val exportBefore = rule.onNodeWithText("Export face data").top()
        rule.runOnIdle { hidden = 2; allowed = true }
        assertSame("Folders to skip", foldersBefore, rule.onNodeWithText("Folders to skip").top())
        assertSame("Export face data", exportBefore, rule.onNodeWithText("Export face data").top())
    }

    @Test fun library_emptyToPeople_peopleRowStaysSameHeight() {
        var data by mutableStateOf(library(people = emptyList()))
        rule.setContent { MemoriaTheme { Surface { LibraryContent(data) } } }

        val before = rule.onNodeWithTag("peopleRow").height()
        rule.runOnIdle { data = library() }
        assertSame("People row height", before, rule.onNodeWithTag("peopleRow").height())
    }

    @Test fun library_placesArriving_shortcutsStayPut() {
        var data by mutableStateOf(library())
        rule.setContent { MemoriaTheme { Surface { LibraryContent(data) } } }

        // one town: the row keeps its height with fewer than 3 tiles
        val before = rule.onNodeWithText("Favorites").top()
        rule.runOnIdle { data = library(places = listOf(Place(1, "Riverton", listOf(tile(1))))) }
        assertSame("Favorites", before, rule.onNodeWithText("Favorites").top())
    }

    @Test fun library_loadingToEmpty_peopleRowStaysSameHeight() {
        var data by mutableStateOf<LibraryData?>(null)
        rule.setContent { MemoriaTheme { Surface { LibraryContent(data) } } }

        val before = rule.onNodeWithTag("peopleRow").height()
        rule.runOnIdle { data = library(people = emptyList()) }
        assertSame("People row height", before, rule.onNodeWithTag("peopleRow").height())
    }

    @Test fun library_unnamedPeople_rowStaysSameHeight() {
        var data by mutableStateOf(library(named = true))
        rule.setContent { MemoriaTheme { Surface { LibraryContent(data) } } }

        val before = rule.onNodeWithTag("peopleRow").height()
        rule.runOnIdle { data = library(named = false) }
        assertSame("People row height", before, rule.onNodeWithTag("peopleRow").height())
    }

    @Test fun trash_selecting_keepsTopAndHeader() {
        var selected by mutableStateOf(emptySet<Long>())
        rule.setContent { MemoriaTheme { Surface { TrashContent(trashed, selected = selected) } } }

        val barBefore = rule.onNodeWithTag("topBar").height()
        val noteBefore = rule.onNodeWithText("Items are deleted for good after 30 days").top()
        rule.runOnIdle { selected = setOf(2L) }
        assertSame("top bar height", barBefore, rule.onNodeWithTag("topBar").height())
        assertSame("note top", noteBefore, rule.onNodeWithText("Items are deleted for good after 30 days").top())
    }

    @Test fun topBar_emptySubtitle_keepsHeight() {
        var subtitle by mutableStateOf<String?>(null)
        rule.setContent { MemoriaTheme { Surface { Column { TopBar("Trash", subtitle, onBack = {}) } } } }

        val before = rule.onNodeWithTag("topBar").height()
        rule.runOnIdle { subtitle = "7 items" }
        assertSame("top bar height", before, rule.onNodeWithTag("topBar").height())
    }

    /** The name sheet: the typing placeholder must not move the field or the buttons under it. */
    @Test fun namePerson_typingPlaceholder_keepsFieldAndButtonsPut() {
        var text by mutableStateOf("")
        val person = face(1).let { Person(listOf(it), it, 1, null) }
        rule.setContent { MemoriaTheme { Surface { NamePersonContent(person, text, { text = it }, {}, {}) } } }

        val fieldBefore = rule.onNodeWithText("Type a name").top()
        val cancelBefore = rule.onNodeWithText("Cancel").top()
        rule.runOnIdle { text = "Dana" }
        assertSame("name field", fieldBefore, rule.onNodeWithText("Dana").top())
        assertSame("Cancel button", cancelBefore, rule.onNodeWithText("Cancel").top())
    }

    private fun person(id: Long, name: String? = null) = face(id).let { Person(listOf(it), it, id, name) }

    /** Who is this?: a search that hides people must not move the field or the New person row. */
    @Test fun whoIsThis_filtering_keepsFieldAndRowsPut() {
        var text by mutableStateOf("")
        val people = listOf(person(1, "Dana"), person(2, "Arman"), person(3, "Bota"))
        rule.setContent { MemoriaTheme { Surface { WhoIsThisContent(face(9), null, people, text, { text = it }, {}, null, {}) } } }

        val titleBefore = rule.onNodeWithText("Who is this?").top()
        val newBefore = rule.onNodeWithText("New person").top()
        rule.runOnIdle { text = "Arman" }
        assertSame("title", titleBefore, rule.onNodeWithText("Who is this?").top())
        assertSame("New person row", newBefore, rule.onNodeWithText("New person").top())
    }

    /** Merge with: the grid is a fixed height, so filtering people must not move the buttons. */
    @Test fun merge_filtering_keepsGridAndButtonsPut() {
        var text by mutableStateOf("")
        val people = listOf(person(2, "Arman"), person(3, "Bota"))
        rule.setContent { MemoriaTheme { Surface { MergeContent(person(1), people, text, { text = it }, null, {}, {}, {}) } } }

        val subtitleBefore = rule.onNodeWithText("Pick the same person, most alike first").top()
        val mergeBefore = rule.onNodeWithText("Merge").top()
        rule.runOnIdle { text = "Bota" }
        assertSame("subtitle", subtitleBefore, rule.onNodeWithText("Pick the same person, most alike first").top())
        assertSame("Merge button", mergeBefore, rule.onNodeWithText("Merge").top())
    }

    /** Change cover: the grid is a fixed height and picking only adds a ring, so the header and Save stay put. */
    @Test fun changeCover_pickingFace_keepsHeaderAndSavePut() {
        var picked by mutableStateOf<Long?>(null)
        val person = face(1).let { Person((1L..8L).map { i -> face(i) }, it, 1, "Dana") }
        rule.setContent { MemoriaTheme { Surface { ChangeCoverContent(person, picked, { picked = it }, {}, {}) } } }

        val titleBefore = rule.onNodeWithText("Change cover").top()
        val saveBefore = rule.onNodeWithText("Save").top()
        rule.runOnIdle { picked = 3L }
        assertSame("title", titleBefore, rule.onNodeWithText("Change cover").top())
        assertSame("Save button", saveBefore, rule.onNodeWithText("Save").top())
    }

    /** The month slider fades in over the grid; showing it must not move the content under it. */
    @Test fun monthSlider_appearing_keepsContentPut() {
        var visible by mutableStateOf(false)
        rule.setContent {
            MemoriaTheme {
                Surface {
                    MonthSlider(
                        months = listOf("September 2026", "August 2026", "July 2026"),
                        current = 1,
                        dragging = null,
                        visible = visible,
                        onDragging = {},
                        onJump = {},
                    ) {
                        Column { Text("anchor") }
                    }
                }
            }
        }

        val before = rule.onNodeWithText("anchor").top()
        rule.runOnIdle { visible = true }
        assertSame("content", before, rule.onNodeWithText("anchor").top())
    }

    /** Viewer: the bars are an overlay, so hiding them (the tapped state) must not move or resize the photo behind. */
    @Test fun viewerBars_hiding_keepsPhotoPut() {
        var visible by mutableStateOf(true)
        rule.setContent {
            MemoriaTheme {
                Surface {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().testTag("viewerPhoto").background(Color.Black))
                        ViewerChrome(visible, "Sat, 11 Oct", "14:32", false, {}, {}, {}, {}, {})
                    }
                }
            }
        }

        val top = rule.onNodeWithTag("viewerPhoto").top()
        val height = rule.onNodeWithTag("viewerPhoto").height()
        rule.runOnIdle { visible = false }
        assertSame("photo top", top, rule.onNodeWithTag("viewerPhoto").top())
        assertSame("photo height", height, rule.onNodeWithTag("viewerPhoto").height())
    }

    /** Photos select mode: the top bar swaps for "N selected" and the tiles grow a circle; the grid must not move. */
    @Test fun photos_selecting_keepsGridPut() {
        val tiles = (1L..9L).map { tile(it) }
        val items: List<Any> = listOf(Header("m1", "October 2026", true), Header("d1", "Sat, 11 Oct", false)) + tiles
        val selection = PhotoSelection()
        rule.setContent { MemoriaTheme { Surface { PhotosContent(items, selection = selection) } } }

        val gridBefore = rule.onNodeWithTag("photosGrid").top()
        val dayBefore = rule.onNodeWithText("Sat, 11 Oct").top()
        val headerBefore = rule.onNodeWithTag("dayHeader").height()
        rule.runOnIdle { selection.start(tiles.first()) }
        assertSame("grid top", gridBefore, rule.onNodeWithTag("photosGrid").top())
        assertSame("day header", dayBefore, rule.onNodeWithText("Sat, 11 Oct").top())
        assertSame("day header height", headerBefore, rule.onNodeWithTag("dayHeader").height())
    }

    /** Photos select mode: the Select bar takes the tabs' place and must have exactly the same height. */
    @Test fun photos_selectBar_keepsBottomBarHeight() {
        var selecting by mutableStateOf(false)
        rule.setContent {
            MemoriaTheme {
                Surface {
                    Box(Modifier.testTag("bottomBar")) {
                        if (selecting) SelectBar(false, {}, {}, {})
                        else ShortNavigationBar {
                            ShortNavigationBarItem(
                                false, {}, { Icon(painterResource(R.drawable.ic_photo), null) }, { Text("Photos") },
                                iconPosition = NavigationItemIconPosition.Start,
                            )
                        }
                    }
                }
            }
        }

        val before = rule.onNodeWithTag("bottomBar").height()
        rule.runOnIdle { selecting = true }
        assertSame("bottom bar height", before, rule.onNodeWithTag("bottomBar").height())
    }
}
