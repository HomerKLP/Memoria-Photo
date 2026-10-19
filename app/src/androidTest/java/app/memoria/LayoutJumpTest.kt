package app.memoria

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** Change cover: the grid is a fixed height and picking only adds a ring and a check, so the header and Save stay put. */
    @Test fun changeCover_pickingFace_keepsHeaderAndSavePut() {
        var picked by mutableStateOf<Long?>(null)
        val person = face(1).let { Person((1L..8L).map { i -> face(i) }, it, 1, "Dana") }
        rule.setContent { MemoriaTheme { Surface { ChangeCoverContent(person, picked, { picked = it }, {}, {}) } } }

        val titleBefore = rule.onNodeWithText("Change cover").top()
        val saveBefore = rule.onNodeWithText("Save").top()
        rule.onNodeWithTag("pickCheck", useUnmergedTree = true).assertDoesNotExist()
        rule.runOnIdle { picked = 3L }
        rule.onNodeWithTag("pickCheck", useUnmergedTree = true).assertExists()
        assertSame("title", titleBefore, rule.onNodeWithText("Change cover").top())
        assertSame("Save button", saveBefore, rule.onNodeWithText("Save").top())
    }

    /** Merge with: picking a face draws the check at its top right and must not move the grid or the buttons. */
    @Test fun merge_pickingFace_keepsGridAndButtonsPut() {
        var picked by mutableStateOf<Person?>(null)
        val people = listOf(person(2, "Arman"), person(3, "Bota"))
        rule.setContent { MemoriaTheme { Surface { MergeContent(person(1), people, "", {}, picked, { picked = it }, {}, {}) } } }

        val armanBefore = rule.onNodeWithText("Arman").top()
        val mergeBefore = rule.onNodeWithText("Merge").top()
        rule.runOnIdle { picked = people.first() }
        rule.onNodeWithTag("pickCheck", useUnmergedTree = true).assertExists()
        assertSame("Arman card", armanBefore, rule.onNodeWithText("Arman").top())
        assertSame("Merge button", mergeBefore, rule.onNodeWithText("Merge").top())
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

    /** The rail starts under the pinned month heading, never through it (mockup: slider top 3 dp under the heading). */
    @Test fun monthSlider_rail_startsUnderPinnedHeading() {
        rule.setContent {
            MemoriaTheme {
                Surface {
                    MonthSlider(
                        months = listOf("September 2026", "August 2026", "July 2026"),
                        current = 1,
                        dragging = null,
                        visible = true,
                        onDragging = {},
                        onJump = {},
                        top = monthHeadingHeight(below = 2.dp),
                    ) {
                        Text("September 2026", Modifier.padding(start = 16.dp, top = 20.dp, bottom = 2.dp), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }

        val heading = rule.onNodeWithText("September 2026").getUnclippedBoundsInRoot()
        val rail = rule.onNodeWithTag("monthRail").getUnclippedBoundsInRoot()
        assertTrue("rail top ${rail.top} is above the heading bottom ${heading.bottom}", rail.top >= heading.bottom)
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

    /** Viewer More: the menu drops over the screen (no dim, no reflow), so opening it must not move what is behind. */
    @Test fun viewerMore_opening_keepsContentPut() {
        var open by mutableStateOf(false)
        rule.setContent {
            MemoriaTheme {
                Surface {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().testTag("viewerPhoto").background(Color.Black))
                        Menu(open, onDismiss = { open = false }, items = listOf(MenuItem(R.drawable.ic_edit, "Edit in…") {}))
                    }
                }
            }
        }

        val top = rule.onNodeWithTag("viewerPhoto").top()
        val height = rule.onNodeWithTag("viewerPhoto").height()
        rule.runOnIdle { open = true }
        rule.onNodeWithText("Edit in…").assertIsDisplayed()
        assertSame("photo top", top, rule.onNodeWithTag("viewerPhoto").top())
        assertSame("photo height", height, rule.onNodeWithTag("viewerPhoto").height())
    }

    /** Move to album: New album swaps the list for the name field; the sheet head must not move. */
    @Test fun moveToAlbum_naming_keepsHeadPut() {
        var naming by mutableStateOf(false)
        rule.setContent {
            MemoriaTheme {
                Surface {
                    MoveToAlbumContent(
                        tile(1), "Camera", listOf(MoveTarget("Screenshots", "Pictures/Screenshots/", null, 214)),
                        naming, "", {}, {}, { naming = true }, {}, {},
                    )
                }
            }
        }

        val head = rule.onNodeWithText("Move to album").top()
        val now = rule.onNodeWithText("Now in Camera").top()
        rule.runOnIdle { naming = true }
        assertSame("head top", head, rule.onNodeWithText("Move to album").top())
        assertSame("now in top", now, rule.onNodeWithText("Now in Camera").top())
    }

    /** Photos select mode: the top bar swaps for "N selected" and the tiles grow a circle; the grid must not move. */
    /** New album: the picker opens in select mode; loading the timeline must not move its bar or footer. */
    @Test fun newAlbumPick_loadingToLoaded_topBarAndFooterStayPut() {
        var items by mutableStateOf<List<Any>?>(null)
        val selection = PhotoSelection()
        rule.setContent {
            MemoriaTheme {
                Surface {
                    PhotosContent(
                        items, selection = selection,
                        forceSelecting = true, titleWhileSelecting = "Add to Holidays",
                        footer = { ActionBar("Cancel", null, {}, "Move here", R.drawable.ic_move, {}, danger = false) },
                    )
                }
            }
        }

        val titleBefore = rule.onNodeWithText("Add to Holidays").top()
        val moveBefore = rule.onNodeWithText("Move here").top()
        rule.runOnIdle { items = listOf(Header("m1", "October 2026", true), Header("d1", "Sat, 11 Oct", false)) + (1L..9L).map { tile(it) } }
        assertSame("picker title", titleBefore, rule.onNodeWithText("Add to Holidays").top())
        assertSame("Move here", moveBefore, rule.onNodeWithText("Move here").top())
    }

    /** New album sheet: typing must not move the field or the buttons under it. */
    @Test fun newAlbum_typingPlaceholder_keepsFieldAndButtonsPut() {
        var name by mutableStateOf("")
        rule.setContent { MemoriaTheme { Surface { NewAlbumContent(name, { name = it }, false, {}, {}) } } }

        val fieldBefore = rule.onNodeWithText("Album name").top()
        val cancelBefore = rule.onNodeWithText("Cancel").top()
        rule.runOnIdle { name = "Holidays" }
        assertSame("name field", fieldBefore, rule.onNodeWithText("Holidays").top())
        assertSame("Cancel button", cancelBefore, rule.onNodeWithText("Cancel").top())
    }

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

    /**
     * Person: the viewer replaces the whole screen tree, so the person's photo grid leaves
     * composition. Back from a photo must keep the grid where it was, not land at the top: the
     * grid's restored scroll clamps to an empty first frame (the bug). Mirrors `App`'s
     * `SaveableStateProvider("people")` + viewer swap; fails if the photos reload empty.
     */
    @Test fun personDetail_backFromViewer_keepsGridScroll() {
        val faces = (1L..40L).map { face(it) }
        val person = Person(faces, faces.first(), 1, "Dana")
        val photos = (1L..40L).map { tile(it) }
        var viewing by mutableStateOf(false)
        rule.setContent {
            MemoriaTheme {
                Surface {
                    val holder = rememberSaveableStateHolder()
                    if (viewing) Box(Modifier.fillMaxSize().testTag("viewer"))
                    else holder.SaveableStateProvider("person") {
                        PersonDetail(person, onBack = {}, onName = {}, onOpen = { _, _ -> }, loadPhotos = { photos })
                    }
                }
            }
        }

        // scroll past the "Photos" heading (grid index 3); it is off-screen while the scroll holds
        rule.onNodeWithTag("personPhotos").performScrollToIndex(35)
        rule.onNodeWithText("Photos").assertDoesNotExist()

        rule.runOnIdle { viewing = true }  // the viewer takes over the tree
        rule.runOnIdle { viewing = false } // back to the person
        rule.onNodeWithText("Photos").assertDoesNotExist()
    }

    private fun albumTiles(id: Long, count: Int) = (1L..count).map { tile(id * 1000 + it) }
    private fun album(id: Long, name: String, count: Int) = Album(id, name, albumTiles(id, count))

    /** Albums: the cover grid loads after the bar; the "On this device" heading must not move. */
    @Test fun albums_loadingToLoaded_headerStaysPut() {
        var albums by mutableStateOf<List<Album>?>(null)
        rule.setContent { MemoriaTheme { Surface { AlbumsContent(albums) } } }

        val headerBefore = rule.onNodeWithText("On this device").top()
        rule.runOnIdle { albums = listOf(album(1, "Camera", 5), album(2, "Screenshots", 3)) }
        assertSame("On this device", headerBefore, rule.onNodeWithText("On this device").top())
    }

    /** Album: the title is a space while loading so the bar keeps its height; it must not move. */
    @Test fun album_loadingToLoaded_topBarStaysPut() {
        var album by mutableStateOf<Album?>(null)
        rule.setContent { MemoriaTheme { Surface { AlbumContent(album) } } }

        val barBefore = rule.onNodeWithTag("topBar").height()
        rule.runOnIdle { album = album(1, "Camera", 9) }
        assertSame("top bar height", barBefore, rule.onNodeWithTag("topBar").height())
    }

    /** Album, selecting: the top bar swaps in place and the Select bar comes up under the grid; the grid's top must not move. */
    @Test fun album_selecting_keepsTopBarAndGridTop() {
        var selected by mutableStateOf<Set<Long>?>(null)
        rule.setContent { MemoriaTheme { Surface { AlbumContent(album(1, "Camera", 9), selected = selected) } } }

        val barBefore = rule.onNodeWithTag("topBar").height()
        val gridBefore = rule.onNodeWithTag("albumGrid").top()
        rule.runOnIdle { selected = setOf(1001L) }
        assertSame("top bar height", barBefore, rule.onNodeWithTag("topBar").height())
        assertSame("grid top", gridBefore, rule.onNodeWithTag("albumGrid").top())
        rule.runOnIdle { selected = (1001L..1009L).toSet() } // Select all hides itself
        assertSame("top bar height, all picked", barBefore, rule.onNodeWithTag("topBar").height())
    }

    /** Videos: the month header is pinned; loading must not move the screen's bar. */
    @Test fun videos_loadingToLoaded_topBarStaysPut() {
        var videos by mutableStateOf<Videos?>(null)
        rule.setContent { MemoriaTheme { Surface { VideosContent(videos) } } }

        val barBefore = rule.onNodeWithTag("topBar").height()
        rule.runOnIdle { videos = Videos(linkedMapOf("October 2026" to albumTiles(1, 4)), albumTiles(1, 4), 2_500_000_000L) }
        assertSame("top bar height", barBefore, rule.onNodeWithTag("topBar").height())
    }

    /** Favorites: the month header is pinned, no day headings; loading must not move the screen's bar. */
    @Test fun favorites_loadingToLoaded_topBarStaysPut() {
        var favorites by mutableStateOf<Favorites?>(null)
        rule.setContent { MemoriaTheme { Surface { FavoritesContent(favorites) } } }

        val barBefore = rule.onNodeWithTag("topBar").height()
        rule.runOnIdle { favorites = Favorites(linkedMapOf("October 2026" to albumTiles(1, 4)), albumTiles(1, 4)) }
        assertSame("top bar height", barBefore, rule.onNodeWithTag("topBar").height())
    }

    /** Favorites, selecting: the top bar swaps in place and the Select bar comes up under the grid; the grid's top must not move. */
    @Test fun favorites_selecting_keepsTopBarAndGridTop() {
        val tiles = albumTiles(1, 9)
        var ids by mutableStateOf<Set<Long>?>(null)
        rule.setContent { MemoriaTheme { Surface { FavoritesContent(Favorites(linkedMapOf("October 2026" to tiles), tiles), pick = Pick(ids)) } } }

        val barBefore = rule.onNodeWithTag("topBar").height()
        val gridBefore = rule.onNodeWithTag("favoritesGrid").top()
        rule.runOnIdle { ids = setOf(1001L) }
        assertSame("top bar height", barBefore, rule.onNodeWithTag("topBar").height())
        assertSame("grid top", gridBefore, rule.onNodeWithTag("favoritesGrid").top())
    }

    /** Videos, selecting: same as Favorites. */
    @Test fun videos_selecting_keepsTopBarAndGridTop() {
        val tiles = albumTiles(1, 9)
        var ids by mutableStateOf<Set<Long>?>(null)
        rule.setContent { MemoriaTheme { Surface { VideosContent(Videos(linkedMapOf("October 2026" to tiles), tiles, 2_500_000_000L), pick = Pick(ids)) } } }

        val barBefore = rule.onNodeWithTag("topBar").height()
        val gridBefore = rule.onNodeWithTag("videosGrid").top()
        rule.runOnIdle { ids = setOf(1001L) }
        assertSame("top bar height", barBefore, rule.onNodeWithTag("topBar").height())
        assertSame("grid top", gridBefore, rule.onNodeWithTag("videosGrid").top())
    }

    /** Place, selecting: same as Favorites. */
    @Test fun place_selecting_keepsTopBarAndGridTop() {
        var ids by mutableStateOf<Set<Long>?>(null)
        rule.setContent { MemoriaTheme { Surface { PlaceContent(Place(1, "Riverton", albumTiles(1, 9)), pick = Pick(ids)) } } }

        val barBefore = rule.onNodeWithTag("topBar").height()
        val gridBefore = rule.onNodeWithTag("placeGrid").top()
        rule.runOnIdle { ids = setOf(1001L) }
        assertSame("top bar height", barBefore, rule.onNodeWithTag("topBar").height())
        assertSame("grid top", gridBefore, rule.onNodeWithTag("placeGrid").top())
    }

    /** Person, selecting: the bar loses the face and More, but keeps its height; the chips and grid above the photos stay put. */
    @Test fun personDetail_selecting_keepsTopBarAndChips() {
        val faces = (1L..6L).map { face(it) }
        val person = Person(faces, faces.first(), 1, "Dana")
        var ids by mutableStateOf<Set<Long>?>(null)
        rule.setContent { MemoriaTheme { Surface { PersonDetail(person, onBack = {}, onName = {}, onOpen = { _, _ -> }, loadPhotos = { albumTiles(1, 6) }, pick = Pick(ids)) } } }

        val barBefore = rule.onNodeWithTag("topBar").height()
        val gridBefore = rule.onNodeWithTag("personPhotos").top()
        val chipBefore = rule.onNodeWithText("Rename").top()
        rule.runOnIdle { ids = setOf(1001L) }
        assertSame("top bar height", barBefore, rule.onNodeWithTag("topBar").height())
        assertSame("grid top", gridBefore, rule.onNodeWithTag("personPhotos").top())
        assertSame("chips", chipBefore, rule.onNodeWithText("Rename").top())
    }

    /** Details: faces, place and camera facts fill in below the date; the date header must not move. */
    @Test fun details_fillingInSections_keepsDatePut() {
        val tile = Tile(1, Uri.parse("content://media/external/images/media/1"), 1_700_000_000_000L, null)
        val bare = PhotoInfo("a.jpg", "DCIM/Camera", 0, 0, 0, null, null, null)
        val full = PhotoInfo("a.jpg", "DCIM/Camera", 3_500_000, 4032, 3024, "Mountain View", "Pixel 9", "f/1.8 · 1/120 s")
        var faces by mutableStateOf(emptyList<FaceVec>())
        var info by mutableStateOf(bare)
        rule.setContent { MemoriaTheme { Surface { DetailsContent(tile, info, faces, emptyList()) } } }

        val dateText = java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")
            .format(java.time.Instant.ofEpochMilli(tile.date).atZone(java.time.ZoneId.systemDefault()))
        val dateBefore = rule.onNodeWithText(dateText).top()
        rule.runOnIdle {
            faces = listOf(face(1))
            info = full
        }
        assertSame("date", dateBefore, rule.onNodeWithText(dateText).top())
    }

    /**
     * Person: the chips are a horizontal scroll. With the row wide enough to hold them all, the
     * last chip ("Hide person") must keep the mockup's 16 dp right padding, not run to the edge.
     */
    @Test fun personDetail_lastChip_keepsRightPadding() {
        val faces = (1L..3L).map { face(it) }
        val person = Person(faces, faces.first(), 1, "Dana")
        val wide = 600.dp
        rule.setContent { MemoriaTheme { Surface { Box(Modifier.width(wide)) { PersonDetail(person, onBack = {}, onName = {}, onOpen = { _, _ -> }, loadPhotos = { emptyList() }) } } } }

        val right = rule.onNodeWithText("Hide person").getUnclippedBoundsInRoot().right.value
        assertTrue("last chip runs into the right edge: right=$right", right <= wide.value - 16f + 0.5f)
    }

    /** People: the bar keeps its height while the list loads, and the grid below must not shift. */
    @Test fun people_loadingToLoaded_topBarKeepsHeight() {
        var data by mutableStateOf<PeopleData?>(null)
        rule.setContent { MemoriaTheme { Surface { PeopleContent(data) } } }

        val barBefore = rule.onNodeWithTag("topBar").height()
        rule.runOnIdle {
            data = PeopleData(
                "7 people · 412 faces",
                (1L..7L).map { person(it, if (it % 2 == 0L) "Person $it" else null) },
                emptyList(),
                total = 1706,
            )
        }
        assertSame("top bar height", barBefore, rule.onNodeWithTag("topBar").height())
    }

    /**
     * People redesign: named people are 3 a row, unnamed 5 a row. Every card in a lazy row must be the
     * same height, or the row resizes (and everything below shifts) once a face's name or picture lands.
     */
    @Test fun people_rowCards_sameHeight() {
        val named = (1L..3L).map { person(it, "Person $it") }
        val unnamed = (4L..8L).map { person(it, null) }
        rule.setContent { MemoriaTheme { Surface { PeopleContent(PeopleData("8 people", named + unnamed, emptyList(), 412)) } } }

        fun heights(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot.height }
        val namedH = heights("namedCard")
        assertEquals("named cards on screen", 3, namedH.size)
        assertTrue("named cards differ in height: $namedH", namedH.all { it == namedH.first() })
        val unnamedH = heights("unnamedCard")
        assertEquals("unnamed cards on screen", 5, unnamedH.size)
        assertTrue("unnamed cards differ in height: $unnamedH", unnamedH.all { it == unnamedH.first() })
    }

    /** Search: the field is the top bar, so loading, typing (the × shows, hits replace the lists) and clearing never move it. */
    @Test fun search_loadingAndTyping_keepFieldPut() {
        var data by mutableStateOf<SearchData?>(null)
        var text by mutableStateOf("")
        rule.setContent { MemoriaTheme { Surface { SearchContent(data, text, onQuery = { text = it }) } } }

        val top = rule.onNodeWithTag("searchField").top()
        val height = rule.onNodeWithTag("searchField").height()
        rule.runOnIdle {
            data = SearchData(listOf(person(1, "Dana")), listOf(Hit("person", "1", "Dana", "1 photo", face = face(1))), listOf(Hit("videos", "videos", "Videos")))
        }
        assertSame("field after loading", top, rule.onNodeWithTag("searchField").top())
        rule.runOnIdle { text = "d" }
        assertSame("field while typing", top, rule.onNodeWithTag("searchField").top())
        assertSame("field height while typing", height, rule.onNodeWithTag("searchField").height())
        rule.runOnIdle { text = "" }
        assertSame("field after clearing", top, rule.onNodeWithTag("searchField").top())
    }
}
