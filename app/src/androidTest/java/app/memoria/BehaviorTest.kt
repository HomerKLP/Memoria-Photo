package app.memoria

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Behavior, not layout: a tap runs the right callback, a field updates, a button turns on. The
 * screens are split into stateless `XxxContent(data, callbacks)`, so each test drives one with fakes
 * and asserts what the callbacks did. Bounds stay `LayoutJumpTest`'s job.
 *
 * Runs on a device or emulator, like `LayoutJumpTest` (`make behavior`).
 */
class BehaviorTest {

    @get:Rule val rule = createComposeRule()

    // --- fakes ---------------------------------------------------------------------------------

    private fun face(id: Long, quality: Float = 0.9f) =
        FaceVec(id, id, floatArrayOf(0f, 0f, 50f, 50f), FloatArray(512), quality, "camera", null)

    private fun tile(id: Long) = Tile(id, Uri.parse("content://media/external/images/media/$id"), 0L, null)

    private fun person(id: Long, name: String? = null) =
        face(id).let { Person((1L..8L).map { i -> face(id * 100 + i) }, it, id, name) }

    private val timeline: List<Any> = listOf(
        Header("m1", "October 2026", true),
        Header("d1", "Sat, 11 Oct", false),
    ) + (1L..9L).map { tile(it) }

    private val trashed = (1L..9L).map { Trashed(tile(it), it.toInt()) }

    // --- Photos --------------------------------------------------------------------------------

    @Test fun photos_tapTile_opensIt() {
        var opened: Tile? = null
        var openedList: List<Tile>? = null
        rule.setContent {
            MemoriaTheme { Surface {
                PhotosContent(timeline, selection = PhotoSelection(), onOpen = { list, t -> openedList = list; opened = t })
            } }
        }

        rule.onNodeWithTag("tile-3").performClick()
        assertEquals(3L, opened?.id)
        assertEquals(9, openedList?.size)
    }

    @Test fun photos_longPress_startsSelection() {
        val selection = PhotoSelection()
        rule.setContent { MemoriaTheme { Surface { PhotosContent(timeline, selection = selection) } } }

        rule.onNodeWithTag("tile-3").performTouchInput { longClick() }
        assertTrue(selection.active)
        assertEquals(setOf(3L), selection.ids)
    }

    @Test fun photos_tapInSelectMode_togglesTheTile() {
        val selection = PhotoSelection()
        rule.setContent { MemoriaTheme { Surface { PhotosContent(timeline, selection = selection) } } }

        rule.runOnIdle { selection.start(tile(1)) }
        rule.onNodeWithTag("tile-2").performClick()
        assertEquals(setOf(1L, 2L), selection.ids)
        rule.onNodeWithTag("tile-2").performClick()
        assertEquals(setOf(1L), selection.ids)
    }

    private val longTimeline: List<Any> = listOf(
        Header("m1", "October 2026", true),
        Header("d1", "Sat, 11 Oct", false),
    ) + (1L..80L).map { tile(it) }

    @Test fun photos_scrollDown_hidesTheTopBar_scrollUpAwayFromTop_keepsItHidden() {
        rule.setContent { MemoriaTheme { Surface { PhotosContent(longTimeline, selection = PhotoSelection()) } } }

        rule.onNodeWithContentDescription("Search").assertIsDisplayed()
        rule.onNodeWithTag("photosGrid").performTouchInput { swipeUp() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Search").assertIsNotDisplayed()
        rule.onNodeWithTag("photosGrid").performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.5f) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Search").assertIsNotDisplayed()
    }

    @Test fun photos_dragBackUpToTheTop_bringsTheTopBarBack() {
        rule.setContent { MemoriaTheme { Surface { PhotosContent(longTimeline, selection = PhotoSelection()) } } }

        rule.onNodeWithTag("photosGrid").performTouchInput { swipeUp() }
        rule.waitForIdle()
        repeat(4) { rule.onNodeWithTag("photosGrid").performTouchInput { swipeDown() } }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Search").assertIsDisplayed()
    }

    @Test fun photos_backAtTheTop_showsTheTopBarAgain() {
        rule.setContent { MemoriaTheme { Surface { PhotosContent(longTimeline, selection = PhotoSelection()) } } }

        rule.onNodeWithTag("photosGrid").performTouchInput { swipeUp() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Search").assertIsNotDisplayed()
        rule.onNodeWithTag("photosGrid").performScrollToIndex(0)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Search").assertIsDisplayed()
    }

    @Test fun photos_topBarHidden_tapOnSelectBar_doesNotReachTheTilesUnderIt() {
        val selection = PhotoSelection()
        rule.setContent { MemoriaTheme { Surface { PhotosContent(longTimeline, selection = selection) } } }

        rule.onNodeWithTag("photosGrid").performTouchInput { swipeUp() }
        rule.waitForIdle()
        rule.runOnIdle { selection.start(tile(1)) }
        rule.waitForIdle()
        rule.onNodeWithText("1 selected").performClick()
        assertEquals(setOf(1L), selection.ids)
    }

    // --- Favorites -----------------------------------------------------------------------------

    @Test fun favorites_tapTile_opensIt() {
        var opened: Tile? = null
        var openedList: List<Tile>? = null
        val tiles = (1L..4L).map { tile(it) }
        rule.setContent {
            MemoriaTheme { Surface {
                FavoritesContent(Favorites(linkedMapOf("October 2026" to tiles), tiles), onOpen = { list, t -> openedList = list; opened = t })
            } }
        }

        rule.onNodeWithTag("tile-3").performClick()
        assertEquals(3L, opened?.id)
        assertEquals(4, openedList?.size)
    }

    // --- Trash ---------------------------------------------------------------------------------

    @Test fun trash_longPress_picksTheRow() {
        var toggled = -1L
        rule.setContent { MemoriaTheme { Surface { TrashContent(trashed, onToggle = { toggled = it }) } } }

        rule.onNodeWithText("3 days").performTouchInput { longClick() }
        assertEquals(3L, toggled)
    }

    @Test fun trash_selectAll_fires() {
        var all = false
        rule.setContent {
            MemoriaTheme { Surface {
                TrashContent(trashed, selected = setOf(2L), onSelectAll = { all = true })
            } }
        }

        rule.onNodeWithText("Select all").performClick()
        assertTrue(all)
    }

    @Test fun trash_empty_fires() {
        var emptied = false
        rule.setContent { MemoriaTheme { Surface { TrashContent(trashed, onEmpty = { emptied = true }) } } }

        rule.onNodeWithText("Empty").performClick()
        assertTrue(emptied)
    }

    // --- Sheets --------------------------------------------------------------------------------

    @Test fun namePerson_typing_enablesSaveAndSaves() {
        var text by mutableStateOf("")
        var saved: String? = null
        val p = person(1)
        rule.setContent {
            MemoriaTheme { Surface { NamePersonContent(p, text, { text = it }, {}, { saved = it }) } }
        }

        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("Dana")
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.onNodeWithText("Save").performClick()
        assertEquals("Dana", saved)
    }

    @Test fun whoIsThis_pickNamed_picksThatPerson() {
        var picked: Person? = null
        val people = listOf(person(1, "Dana"), person(2, "Arman"))
        rule.setContent {
            MemoriaTheme { Surface {
                WhoIsThisContent(face(9), null, people, "", {}, { picked = it }, null, {})
            } }
        }

        rule.onNodeWithText("Arman").performClick()
        assertEquals(2L, picked?.id)
    }

    @Test fun whoIsThis_newPerson_needsATypedName() {
        var text by mutableStateOf("")
        var created: String? = null
        rule.setContent {
            MemoriaTheme { Surface {
                WhoIsThisContent(face(9), null, listOf(person(1, "Dana")), text, { text = it }, {}, null, { created = it })
            } }
        }

        rule.onNodeWithText("New person").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("Bota")
        rule.onNodeWithText("New person").assertIsEnabled().performClick()
        assertEquals("Bota", created)
    }

    @Test fun merge_pickThenMerge_mergesThePick() {
        var picked by mutableStateOf<Person?>(null)
        var merged: Person? = null
        rule.setContent {
            MemoriaTheme { Surface {
                MergeContent(person(1, "Dana"), listOf(person(2, "Bota")), "", {}, picked, { picked = it }, {}, { merged = it })
            } }
        }

        rule.onNodeWithText("Merge").assertIsNotEnabled()
        rule.onNodeWithText("Bota").performClick()
        rule.onNodeWithText("Merge").assertIsEnabled()
        rule.onNodeWithText("Merge").performClick()
        assertEquals(2L, merged?.id)
    }

    @Test fun changeCover_pickThenSave_savesThePick() {
        var picked by mutableStateOf<Long?>(null)
        var saved: Long? = null
        rule.setContent {
            MemoriaTheme { Surface { ChangeCoverContent(person(1, "Dana"), picked, { picked = it }, {}, { saved = it }) } }
        }

        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithTag("pickCheck", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("coverFace-102").performClick()
        rule.onNodeWithTag("pickCheck", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.onNodeWithText("Save").performClick()
        assertEquals(102L, saved)
    }

    // --- Viewer --------------------------------------------------------------------------------

    @Test fun viewer_more_opensMenuAndFiresItem() {
        var fired: String? = null
        rule.setContent {
            MemoriaTheme { Surface { Box(Modifier.fillMaxSize()) {
                ViewerChrome(
                    true, "Sat, 11 Oct", "14:32", false, {}, {}, {}, {}, {},
                    menuItems = listOf(MenuItem(R.drawable.ic_edit, "Edit in…") { fired = "edit" }),
                )
            } } }
        }

        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Edit in…").performClick()
        assertEquals("edit", fired)
    }

    // --- Album ---------------------------------------------------------------------------------

    private val album = Album(1, "Camera", (1L..6L).map { tile(it) })

    @Test fun album_longPress_picksThePhoto() {
        var picked: Set<Long>? = null
        rule.setContent { MemoriaTheme { Surface { AlbumContent(album, onSelect = { picked = it }) } } }

        rule.onNodeWithTag("tile-3").performTouchInput { longClick() }
        assertEquals(setOf(3L), picked)
    }

    @Test fun album_moreSelect_startsWithNothingPicked() {
        var picked: Set<Long>? = null
        rule.setContent { MemoriaTheme { Surface { AlbumContent(album, onSelect = { picked = it }) } } }

        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Select").performClick()
        assertEquals(emptySet<Long>(), picked)
    }

    @Test fun album_selecting_tapTogglesAndSelectAllPicksAll() {
        var picked by mutableStateOf<Set<Long>?>(setOf(1L))
        rule.setContent { MemoriaTheme { Surface { AlbumContent(album, selected = picked, onSelect = { picked = it }) } } }

        rule.onNodeWithTag("tile-2").performClick()
        assertEquals(setOf(1L, 2L), picked)
        rule.onNodeWithTag("tile-2").performClick()
        assertEquals(setOf(1L), picked)
        rule.onNodeWithText("Select all").performClick()
        assertEquals((1L..6L).toSet(), picked)
        rule.onNodeWithContentDescription("Cancel").performClick()
        assertEquals(null, picked)
    }

    @Test fun album_selectBar_hasMove() {
        var moved = false
        rule.setContent { MemoriaTheme { Surface { AlbumContent(album, selected = setOf(1L), onMove = { moved = true }) } } }

        rule.onNodeWithText("Move").performClick()
        assertTrue(moved)
    }

    @Test fun album_ignoreFaces_asksOnlyWhenTurningOn() {
        var ignored by mutableStateOf(false)
        rule.setContent { MemoriaTheme { Surface { AlbumContent(album, ignored = ignored, onIgnore = { ignored = it }) } } }

        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Ignore faces in this album").performClick()
        rule.onNodeWithText("Ignore faces in Camera?").assertIsDisplayed()
        assertEquals(false, ignored)
        rule.onNodeWithText("Ignore faces").performClick()
        assertEquals(true, ignored)

        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithContentDescription("On").assertIsDisplayed() // the check
        rule.onNodeWithText("Ignore faces in this album").performClick()
        rule.onNodeWithText("Ignore faces in Camera?").assertDoesNotExist()
        assertEquals(false, ignored)
    }

    // --- Select on Favorites, Videos, Place, Person ---------------------------------------------

    @Test fun favorites_selecting_unfavoritesThePicked() {
        val tiles = (1L..4L).map { tile(it) }
        var ids by mutableStateOf<Set<Long>?>(null)
        var unfavorited: List<Tile>? = null
        rule.setContent {
            MemoriaTheme { Surface {
                FavoritesContent(Favorites(linkedMapOf("October 2026" to tiles), tiles), pick = Pick(ids, set = { ids = it }, favorite = { unfavorited = it }))
            } }
        }

        rule.onNodeWithTag("tile-2").performTouchInput { longClick() }
        rule.onNodeWithTag("tile-3").performClick()
        rule.onNodeWithText("Unfavorite").performClick()
        assertEquals(listOf(2L, 3L), unfavorited?.map { it.id })
    }

    @Test fun videos_selecting_selectAllThenShareSendsAll() {
        val tiles = (1L..4L).map { tile(it) }
        var ids by mutableStateOf<Set<Long>?>(null)
        var shared: List<Tile>? = null
        rule.setContent {
            MemoriaTheme { Surface {
                VideosContent(Videos(linkedMapOf("October 2026" to tiles), tiles, 0L), pick = Pick(ids, set = { ids = it }, share = { shared = it }))
            } }
        }

        rule.onNodeWithTag("tile-1").performTouchInput { longClick() }
        rule.onNodeWithText("Select all").performClick()
        rule.onNodeWithText("Share").performClick()
        assertEquals((1L..4L).toList(), shared?.map { it.id })
    }

    @Test fun place_selecting_tapTogglesAndDeleteGetsThePicked() {
        val tiles = (1L..4L).map { tile(it) }
        var ids by mutableStateOf<Set<Long>?>(null)
        var deleted: List<Tile>? = null
        var opened = false
        rule.setContent {
            MemoriaTheme { Surface {
                PlaceContent(Place(1, "Riverton", tiles), onOpen = { _, _ -> opened = true }, pick = Pick(ids, set = { ids = it }, delete = { deleted = it }))
            } }
        }

        rule.onNodeWithTag("tile-1").performTouchInput { longClick() }
        rule.onNodeWithTag("tile-2").performClick()
        rule.onNodeWithTag("tile-1").performClick()
        assertEquals(setOf(2L), ids)
        assertEquals(false, opened)
        rule.onNodeWithText("Delete").performClick()
        assertEquals(listOf(2L), deleted?.map { it.id })
        rule.onNodeWithContentDescription("Cancel").performClick()
        assertEquals(null, ids)
    }

    @Test fun person_selecting_favoriteGetsThePicked() {
        val photos = (101L..106L).map { tile(it) }
        var ids by mutableStateOf<Set<Long>?>(null)
        var faved: List<Tile>? = null
        rule.setContent {
            MemoriaTheme { Surface {
                PersonDetail(person(1, "Dana"), onBack = {}, onName = {}, onOpen = { _, _ -> }, loadPhotos = { photos }, pick = Pick(ids, set = { ids = it }, favorite = { faved = it }))
            } }
        }

        rule.onNodeWithTag("tile-102").performTouchInput { longClick() }
        rule.onNodeWithTag("tile-104").performClick()
        rule.onNodeWithText("2 selected").assertIsDisplayed()
        rule.onNodeWithText("Favorite").performClick()
        assertEquals(listOf(102L, 104L), faved?.map { it.id })
    }

    // --- Move to album -------------------------------------------------------------------------

    @Test fun moveToAlbum_pick_handsTheFolderPath() {
        var path: String? = null
        val targets = listOf(MoveTarget("Screenshots", "Pictures/Screenshots/", null, 214))
        rule.setContent {
            MemoriaTheme { Surface {
                MoveToAlbumContent(tile(1), "Camera", targets, false, "", {}, {}, {}, { path = it.path }, {})
            } }
        }

        rule.onNodeWithText("Screenshots").performClick()
        assertEquals("Pictures/Screenshots/", path)
    }

    @Test fun moveToAlbum_newAlbum_needsAName() {
        var naming by mutableStateOf(false)
        var typed by mutableStateOf("")
        var path: String? = null
        rule.setContent {
            MemoriaTheme { Surface {
                MoveToAlbumContent(
                    tile(1), "Camera", emptyList(), naming, typed, { typed = it }, {}, { naming = true },
                    {}, { path = "Pictures/$it/" },
                )
            } }
        }

        rule.onNodeWithText("New album").performClick()
        rule.onNodeWithText("Move").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("Holidays")
        rule.onNodeWithText("Move").assertIsEnabled().performClick()
        assertEquals("Pictures/Holidays/", path)
    }

    // --- New album -----------------------------------------------------------------------------

    @Test fun newAlbum_next_needsANameAndAUniqueOne() {
        var name by mutableStateOf("")
        var taken by mutableStateOf(false)
        var next = false
        rule.setContent { MemoriaTheme { Surface { NewAlbumContent(name, { name = it }, taken, {}, { next = true }) } } }

        rule.onNodeWithText("Next").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("Holidays")
        rule.onNodeWithText("Next").assertIsEnabled()
        rule.runOnIdle { taken = true } // an album with that name exists
        rule.onNodeWithText("Next").assertIsNotEnabled()
        rule.runOnIdle { taken = false }
        rule.onNodeWithText("Next").performClick()
        assertTrue(next)
    }

    @Test fun newAlbumPick_moveHereOffUntilPicked() {
        val selection = PhotoSelection()
        rule.setContent {
            MemoriaTheme { Surface {
                PhotosContent(
                    timeline, selection = selection,
                    forceSelecting = true, titleWhileSelecting = "Add to Holidays",
                    footer = {
                        ActionBar("Cancel", null, {}, "Move here", R.drawable.ic_move, {}, danger = false, primaryEnabled = selection.ids.isNotEmpty())
                    },
                )
            } }
        }

        rule.onNodeWithText("Add to Holidays").assertIsDisplayed()
        rule.onNodeWithText("Move here").assertIsNotEnabled()
        rule.onNodeWithTag("tile-3").performClick()
        assertEquals(setOf(3L), selection.ids)
        rule.onNodeWithText("Move here").assertIsEnabled()
    }

    // --- Empty pages ---------------------------------------------------------------------------

    @Test fun closeWhenEmpty_firesOnlyAfterALoadFindsNothing() {
        var value by mutableStateOf<Any?>(null)
        var closes = 0
        rule.setContent {
            val v = value
            MemoriaTheme { Surface { CloseWhenEmpty(v, v is Collection<*> && v.isEmpty()) { closes++ } } }
        }

        rule.runOnIdle { assertEquals(0, closes) } // still loading: nothing closes
        rule.runOnIdle { value = emptyList<Int>() }
        rule.waitUntil(5_000) { closes == 1 }
        assertEquals(1, closes)
    }

    // --- Settings ------------------------------------------------------------------------------

    @Test fun settings_rows_fireTheirCallbacks() {
        var charging: Boolean? = null
        var rescan = false
        var hidden = false
        rule.setContent {
            MemoriaTheme { Surface {
                SettingsContent(
                    charging = false, allowed = false, hidden = 2, version = "0.1",
                    onCharging = { charging = it }, onRescan = { rescan = true }, onHidden = { hidden = true },
                )
            } }
        }

        rule.onNodeWithText("Only while charging").assertIsDisplayed().performClick()
        assertEquals(true, charging)
        rule.onNodeWithText("Find faces again").performScrollTo().performClick()
        assertTrue(rescan)
        rule.onNodeWithText("Hidden people").performScrollTo().performClick()
        assertTrue(hidden)
    }

    // --- Search --------------------------------------------------------------------------------

    private val search = SearchData(
        people = listOf(person(1, "Dana")),
        hits = listOf(
            Hit("person", "1", "Dana", "8 photos", face = face(1)),
            Hit("album", "5", "Downloads", "33 items", cover = tile(5)),
            Hit("album", "6", "Andy's phone", "4 items", cover = tile(6)),
            Hit("month", "m2025-12", "December 2025", "48 items"),
        ),
        types = listOf(Hit("videos", "videos", "Videos"), Hit("favorites", "favorites", "Favorites"), Hit("album", "7", "Screenshots")),
    )

    @Test fun search_typing_findsWordStartsAndOpensAHit() {
        var text by mutableStateOf("")
        var opened: Hit? = null
        rule.setContent { MemoriaTheme { Surface { SearchContent(search, text, onQuery = { text = it }, onOpen = { opened = it }) } } }

        rule.onNodeWithTag("searchField").performTextInput("d")
        rule.onNodeWithText("December 2025").assertIsDisplayed()
        rule.onNodeWithText("Andy's phone").assertDoesNotExist()
        rule.onNodeWithText("Downloads").performClick()
        assertEquals("5", opened?.key)
    }

    @Test fun search_clear_emptiesTheField() {
        var text by mutableStateOf("da")
        rule.setContent { MemoriaTheme { Surface { SearchContent(search, text, onQuery = { text = it }) } } }

        rule.onNodeWithContentDescription("Clear").performClick()
        assertEquals("", text)
        rule.onNodeWithText("Types").assertIsDisplayed()
    }

    @Test fun search_noHits_saysSo() {
        rule.setContent { MemoriaTheme { Surface { SearchContent(search, "xyz") } } }
        rule.onNodeWithText("Nothing for \"xyz\"").assertIsDisplayed()
    }

    @Test fun search_peopleChipsAndRecent_fireTheirCallbacks() {
        var opened: Hit? = null
        var removed: Hit? = null
        var cleared = false
        rule.setContent {
            MemoriaTheme { Surface {
                SearchContent(search, "", recent = listOf(search.hits[3]), onOpen = { opened = it }, onRemoveRecent = { removed = it }, onClearRecent = { cleared = true })
            } }
        }

        rule.onNodeWithText("Dana").performClick()
        assertEquals("person", opened?.kind)
        rule.onNodeWithText("Screenshots").performClick()
        assertEquals("7", opened?.key)
        rule.onNodeWithText("December 2025").performClick()
        assertEquals("m2025-12", opened?.key)
        rule.onNodeWithContentDescription("Remove").performClick()
        assertEquals("m2025-12", removed?.key)
        rule.onNodeWithText("Clear").performClick()
        assertTrue(cleared)
    }

    // --- People --------------------------------------------------------------------------------

    private val peopleData = PeopleData(
        summary = "3 people · 20 faces",
        people = listOf(person(1, "Dana"), person(2, "Arman"), person(3)),
        unsure = listOf(face(99)),
        total = 10,
    )

    private fun hiddenPerson(id: Long, name: String) =
        Person((1L..8L).map { i -> face(id * 100 + i) }, face(id * 100 + 1), id, name, hidden = true)

    @Test fun people_list_tapsAndIndex_fire() {
        var opened: Long? = null
        var review = false
        var paused = false
        rule.setContent {
            MemoriaTheme { Surface {
                PeopleContent(
                    peopleData, onOpenPerson = { opened = it }, onHelpRecognize = { review = true },
                    indexing = true, indexed = 3, onPauseIndex = { paused = true },
                )
            } }
        }

        rule.onNodeWithText("Review").performClick()
        assertTrue(review)
        rule.onNodeWithText("Pause").performClick()
        assertTrue(paused)
        rule.onNodeWithText("Dana").performClick()
        assertEquals(1L, opened)
    }

    @Test fun people_list_unnamedTap_opensThePerson() {
        var opened: Long? = null
        rule.setContent { MemoriaTheme { Surface { PeopleContent(peopleData, onOpenPerson = { opened = it }) } } }

        rule.onNodeWithTag("unnamedCard").performClick()
        assertEquals(3L, opened)
    }

    @Test fun people_list_backAndResume_fire() {
        var back = false
        var resume = false
        rule.setContent {
            MemoriaTheme { Surface {
                PeopleContent(peopleData, onBack = { back = true }, paused = true, indexed = 3, onResumeIndex = { resume = true })
            } }
        }

        rule.onNodeWithContentDescription("Back").performClick()
        assertTrue(back)
        rule.onNodeWithText("Resume").performClick()
        assertTrue(resume)
    }

    @Test fun person_chips_fire() {
        var named = false
        var merge = false
        var cover = false
        var hide = false
        rule.setContent {
            MemoriaTheme { Surface {
                PeopleContent(
                    peopleData, openId = 1,
                    onName = { named = true }, onMerge = { merge = true }, onCover = { cover = true }, onHide = { hide = true },
                )
            } }
        }

        rule.onNodeWithText("Rename").performScrollTo().performClick()
        assertTrue(named)
        rule.onNodeWithText("Merge with").performScrollTo().performClick()
        assertTrue(merge)
        rule.onNodeWithText("Change cover").performScrollTo().performClick()
        assertTrue(cover)
        rule.onNodeWithText("Hide person").performScrollTo().performClick()
        assertTrue(hide)
    }

    @Test fun person_unnamed_chipAddsName() {
        var named = false
        rule.setContent { MemoriaTheme { Surface { PeopleContent(peopleData, openId = 3, onName = { named = true }) } } }

        rule.onNodeWithText("Add name").performScrollTo().performClick()
        assertTrue(named)
    }

    @Test fun person_hidden_chipShowsPerson() {
        var hide = false
        rule.setContent {
            MemoriaTheme { Surface {
                PeopleContent(
                    PeopleData("1 person", listOf(hiddenPerson(4, "Sara")), emptyList(), 10),
                    openId = 4, onHide = { hide = true },
                )
            } }
        }

        rule.onNodeWithText("Show person").performScrollTo().performClick()
        assertTrue(hide)
    }

    @Test fun person_unsureCard_asksAboutThePerson() {
        val p = Person(listOf(face(1, 0.9f), face(2, 0.2f)), face(1), 1, "Dana")
        var review: Long? = null
        rule.setContent {
            MemoriaTheme { Surface {
                PeopleContent(PeopleData("1 person", listOf(p), emptyList(), 10), openId = 1, onHelpRecognize = { review = it })
            } }
        }

        rule.onNodeWithText("Review").performScrollTo().performClick()
        assertEquals(1L, review)
    }

    @Test fun person_photoTap_opensTheViewer() {
        val photos = (101L..106L).map { tile(it) }
        var opened: Tile? = null
        var openedList: List<Tile>? = null
        rule.setContent {
            MemoriaTheme { Surface {
                PersonDetail(
                    person(1, "Dana"), onBack = {}, onName = {},
                    onOpen = { list, t -> openedList = list; opened = t }, loadPhotos = { photos },
                )
            } }
        }

        rule.onNodeWithTag("tile-103").performClick()
        assertEquals(103L, opened?.id)
        assertEquals(6, openedList?.size)
    }

    /** Person: the photos are grouped by month, and the month slider shows once the months scroll by. */
    @Test fun person_scrollingPhotos_showsTheMonthSlider() {
        val photos = (1L..60L).map { Tile(it, Uri.parse("content://media/external/images/media/$it"), 1_700_000_000_000L - it * 31L * 86_400_000L, null) }
        rule.setContent {
            MemoriaTheme { Surface {
                PersonDetail(person(1, "Dana"), onBack = {}, onName = {}, onOpen = { _, _ -> }, loadPhotos = { photos })
            } }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("tile-1").fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.autoAdvance = false

        rule.onNodeWithTag("monthRail").assertDoesNotExist()
        rule.onNodeWithTag("personPhotos").performScrollToIndex(40)
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("monthRail").assertExists()
    }

    /** Videos: the month slider shows once the months scroll by. */
    @Test fun videos_scrollingMonths_showsTheMonthSlider() {
        val months = linkedMapOf<String, List<Tile>>()
        for ((m, name) in listOf("October 2026", "September 2026", "August 2026").withIndex()) {
            months[name] = (1L..20L).map { tile(m * 100L + it) }
        }
        val all = months.values.flatten()
        rule.setContent { MemoriaTheme { Surface { VideosContent(Videos(months, all, 0L)) } } }
        rule.mainClock.autoAdvance = false

        rule.onNodeWithTag("monthRail").assertDoesNotExist()
        rule.onNodeWithTag("videosGrid").performScrollToIndex(30)
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("monthRail").assertExists()
    }

    /** Favorites: the month slider shows once the months scroll by. */
    @Test fun favorites_scrollingMonths_showsTheMonthSlider() {
        val months = linkedMapOf<String, List<Tile>>()
        for ((m, name) in listOf("October 2026", "September 2026", "August 2026").withIndex()) {
            months[name] = (1L..20L).map { tile(m * 100L + it) }
        }
        val all = months.values.flatten()
        rule.setContent { MemoriaTheme { Surface { FavoritesContent(Favorites(months, all)) } } }
        rule.mainClock.autoAdvance = false

        rule.onNodeWithTag("monthRail").assertDoesNotExist()
        rule.onNodeWithTag("favoritesGrid").performScrollToIndex(30)
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("monthRail").assertExists()
    }

    @Test fun person_selectAll_picksEveryPhoto() {
        val photos = (101L..106L).map { tile(it) }
        var ids by mutableStateOf<Set<Long>?>(setOf(101L))
        rule.setContent {
            MemoriaTheme { Surface {
                PersonDetail(
                    person(1, "Dana"), onBack = {}, onName = {}, onOpen = { _, _ -> },
                    loadPhotos = { photos }, pick = Pick(ids, set = { ids = it }),
                )
            } }
        }

        rule.onNodeWithText("Select all").performClick()
        assertEquals((101L..106L).toSet(), ids)
    }

    @Test fun hiddenPeople_openAndBack_fire() {
        var opened: Long? = null
        var back = false
        rule.setContent {
            MemoriaTheme { Surface {
                PeopleContent(
                    PeopleData("1 person", listOf(hiddenPerson(4, "Sara")), emptyList(), 10),
                    hiddenOnly = true, onOpenPerson = { opened = it }, onBack = { back = true },
                )
            } }
        }

        rule.onNodeWithText("Hidden people").assertIsDisplayed()
        rule.onNodeWithText("Sara").performClick()
        assertEquals(4L, opened)
        rule.onNodeWithContentDescription("Back").performClick()
        assertTrue(back)
    }

    @Test fun hiddenPeople_empty_showsHint() {
        rule.setContent {
            MemoriaTheme { Surface {
                PeopleContent(PeopleData("0 people", emptyList(), emptyList(), 10), hiddenOnly = true)
            } }
        }

        rule.onNodeWithText("No hidden people").assertIsDisplayed()
    }

    // --- Library -------------------------------------------------------------------------------
    // 3 people: PeopleRow then adds no "See all" tile, so the three section heads are the only ones.

    private val library = LibraryData(
        faces = (1L..3L).map { person(it, "Person $it") },
        unsure = listOf(face(99)),
        albums = listOf(Album(1, "Camera", (1L..6L).map { tile(it) })),
        favorites = 4, videos = 2, trash = 3, total = 10,
        places = listOf(Place(1, "Riverton", (1L..3L).map { tile(it) })),
    )

    @Test fun library_searchAndSettings_fire() {
        var search = false
        var settings = false
        rule.setContent {
            MemoriaTheme { Surface {
                LibraryContent(library, onOpenSearch = { search = true }, onOpenSettings = { settings = true })
            } }
        }

        rule.onNodeWithText("Search people, places, dates").performClick()
        assertTrue(search)
        rule.onNodeWithContentDescription("Settings").performClick()
        assertTrue(settings)
    }

    @Test fun library_seeAllHeads_fire() {
        var people = false
        var albums = false
        var places = false
        rule.setContent {
            MemoriaTheme { Surface {
                LibraryContent(library, onOpenPeople = { people = true }, onOpenAlbums = { albums = true }, onOpenPlaces = { places = true })
            } }
        }

        // three "See all" heads, top to bottom: People, Albums, Places
        rule.onAllNodesWithText("See all")[0].performClick()
        assertTrue(people)
        rule.onAllNodesWithText("See all")[1].performScrollTo().performClick()
        assertTrue(albums)
        rule.onAllNodesWithText("See all")[2].performScrollTo().performClick()
        assertTrue(places)
    }

    @Test fun library_personTile_opensThePerson() {
        var opened: Long? = null
        rule.setContent { MemoriaTheme { Surface { LibraryContent(library, onOpenPerson = { opened = it }) } } }

        rule.onNodeWithText("Person 1").performClick()
        assertEquals(1L, opened)
    }

    @Test fun library_peopleRowSeeAll_opensPeople() {
        var seeAll = false
        rule.setContent {
            MemoriaTheme { Surface {
                PeopleRow((1L..13L).map { person(it, "Person $it") }, {}, { seeAll = true })
            } }
        }

        rule.onNodeWithTag("peopleRow").performScrollToNode(hasText("See all"))
        rule.onNodeWithText("See all").performClick()
        assertTrue(seeAll)
    }

    @Test fun library_albumCover_opensTheAlbum() {
        var opened: Long? = null
        rule.setContent { MemoriaTheme { Surface { LibraryContent(library, onOpenAlbum = { opened = it }) } } }

        rule.onNodeWithText("Camera").performScrollTo().performClick()
        assertEquals(1L, opened)
    }

    @Test fun library_placeCover_opensThePlace() {
        var opened = 0
        rule.setContent { MemoriaTheme { Surface { LibraryContent(library, onOpenPlace = { opened = it }) } } }

        rule.onNodeWithText("Riverton").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test fun library_reviewCard_fires() {
        var review = false
        rule.setContent { MemoriaTheme { Surface { LibraryContent(library, onHelpRecognize = { review = true }) } } }

        rule.onNodeWithText("Review").performScrollTo().performClick()
        assertTrue(review)
    }

    @Test fun library_shortcuts_fire() {
        var favorites = false
        var videos = false
        var trash = false
        rule.setContent {
            MemoriaTheme { Surface {
                LibraryContent(library, onOpenFavorites = { favorites = true }, onOpenVideos = { videos = true }, onOpenTrash = { trash = true })
            } }
        }

        rule.onNodeWithText("Favorites").performScrollTo().performClick()
        assertTrue(favorites)
        rule.onNodeWithText("Videos").performScrollTo().performClick()
        assertTrue(videos)
        rule.onNodeWithText("Trash").performScrollTo().performClick()
        assertTrue(trash)
    }

    @Test fun settings_devTools_fires() {
        var dev = false
        rule.setContent { MemoriaTheme { Surface { SettingsContent(charging = false, allowed = false, hidden = 2, version = "0.1", onDev = { dev = true }) } } }

        rule.onNodeWithText("Dev tools").performScrollTo().performClick()
        assertTrue(dev)
    }

    @Test fun settings_withoutOnDev_hasNoDeveloperSection() {
        rule.setContent { MemoriaTheme { Surface { SettingsContent(charging = false, allowed = false, hidden = 2, version = "0.1") } } }

        rule.onNodeWithText("Developer").assertDoesNotExist()
        rule.onNodeWithText("Dev tools").assertDoesNotExist()
    }

    @Test fun devTools_rows_fireTheirCallbacks() {
        var cpu = false
        var npu = false
        var folder = false
        var back = false
        rule.setContent {
            MemoriaTheme { Surface {
                DevToolsContent(
                    busy = false, numbers = null, log = "Index SUCCEEDED",
                    onBack = { back = true }, onRunCpu = { cpu = true }, onRunNpu = { npu = true }, onIndexTest = { folder = true },
                )
            } }
        }

        rule.onNodeWithText("Run on CPU").performClick()
        assertTrue(cpu)
        rule.onNodeWithText("Run on NPU").performClick()
        assertTrue(npu)
        rule.onNodeWithText("Index test folder").performClick()
        assertTrue(folder)
        rule.onNodeWithContentDescription("Back").performClick()
        assertTrue(back)
    }

    @Test fun devTools_busy_runRowsDoNothing_indexStillWorks() {
        var cpu = false
        var npu = false
        var folder = false
        rule.setContent {
            MemoriaTheme { Surface {
                DevToolsContent(
                    busy = true, numbers = null, log = "",
                    onRunCpu = { cpu = true }, onRunNpu = { npu = true }, onIndexTest = { folder = true },
                )
            } }
        }

        rule.onNodeWithText("Run on CPU").performClick()
        rule.onNodeWithText("Run on NPU").performClick()
        assertFalse(cpu)
        assertFalse(npu)
        rule.onNodeWithText("Index test folder").performClick()
        assertTrue(folder)
    }

    @Test fun library_indexPause_fires() {
        var pause = false
        rule.setContent {
            MemoriaTheme { Surface {
                LibraryContent(library, indexing = true, indexed = 3, onPauseIndex = { pause = true })
            } }
        }

        rule.onNodeWithText("Pause").performClick()
        assertTrue(pause)
    }

    @Test fun library_indexResume_fires() {
        var resume = false
        rule.setContent {
            MemoriaTheme { Surface {
                LibraryContent(library, paused = true, indexed = 3, onResumeIndex = { resume = true })
            } }
        }

        rule.onNodeWithText("Resume").performClick()
        assertTrue(resume)
    }

    // --- Month slider --------------------------------------------------------------------------

    /** Hidden at the top, fades in when the grid scrolls, and fades out 2 s after the scroll stops. */
    @Test fun monthSlider_showsOnScroll_hidesTwoSecondsAfterStop() {
        val months = (0 until 6).map { (it * 13) to "Month $it" } // a heading plus 12 tiles each
        rule.setContent {
            MemoriaTheme { Surface {
                val grid = rememberLazyGridState()
                MonthSliderBox(grid, months) {
                    LazyVerticalGrid(GridCells.Fixed(4), Modifier.testTag("grid"), state = grid) {
                        months.forEach { (_, m) ->
                            stickyHeader { Text(m) }
                            items(12) { Text("$m $it", Modifier.height(80.dp)) }
                        }
                    }
                }
            } }
        }
        rule.mainClock.autoAdvance = false

        rule.onNodeWithTag("monthRail").assertDoesNotExist()
        rule.onNodeWithTag("grid").performScrollToIndex(30)
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("monthRail").assertExists()
        rule.mainClock.advanceTimeBy(1000) // 1.5 s after the stop: still there
        rule.onNodeWithTag("monthRail").assertExists()
        rule.mainClock.advanceTimeBy(1200) // 2.7 s: the 2 s wait and the fade are over
        rule.onNodeWithTag("monthRail").assertDoesNotExist()
    }

    /** A header above the months (Person): resting inside it keeps the slider hidden; it shows once the months have started. */
    @Test fun monthSlider_headerAbove_hiddenUntilTheMonthsStart() {
        val months = (0 until 6).map { (3 + it * 13) to "Month $it" } // 3 header items, then a heading plus 12 tiles each
        rule.setContent {
            MemoriaTheme { Surface {
                val grid = rememberLazyGridState()
                MonthSliderBox(grid, months) {
                    LazyVerticalGrid(GridCells.Fixed(4), Modifier.testTag("grid"), state = grid) {
                        items(3, span = { GridItemSpan(maxLineSpan) }) { Text("header $it", Modifier.height(80.dp)) }
                        months.forEach { (_, m) ->
                            stickyHeader { Text(m) }
                            items(12) { Text("$m $it", Modifier.height(80.dp)) }
                        }
                    }
                }
            } }
        }
        rule.mainClock.autoAdvance = false

        rule.onNodeWithTag("grid").performScrollToIndex(2)
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("monthRail").assertDoesNotExist()
        rule.onNodeWithTag("grid").performScrollToIndex(20)
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("monthRail").assertExists()
    }

    /** At the end of the list the knob sits on the rail's bottom end, even when the last months are shorter than the screen. */
    @Test fun monthSlider_atTheEnd_knobReachesTheRailBottom() {
        val months = (0 until 6).map { (it * 13) to "Month $it" } // a heading plus 12 tiles each
        rule.setContent {
            MemoriaTheme { Surface {
                val grid = rememberLazyGridState()
                MonthSliderBox(grid, months) {
                    LazyVerticalGrid(GridCells.Fixed(4), Modifier.testTag("grid"), state = grid) {
                        months.forEach { (_, m) ->
                            stickyHeader { Text(m) }
                            items(12) { Text("$m $it", Modifier.height(80.dp)) }
                        }
                    }
                }
            } }
        }
        rule.mainClock.autoAdvance = false

        rule.onNodeWithTag("grid").performScrollToIndex(77)
        rule.mainClock.advanceTimeBy(500)
        val rail = rule.onNodeWithTag("monthRail").getUnclippedBoundsInRoot()
        val knob = rule.onNodeWithTag("monthKnob").getUnclippedBoundsInRoot()
        assertEquals(rail.bottom.value, (knob.top.value + knob.bottom.value) / 2, 1f)
    }

    /** One month has nothing to jump to: the slider never shows, however far the grid scrolls. */
    @Test fun monthSlider_oneMonth_neverShows() {
        val months = listOf(0 to "Month 0")
        rule.setContent {
            MemoriaTheme { Surface {
                val grid = rememberLazyGridState()
                MonthSliderBox(grid, months) {
                    LazyVerticalGrid(GridCells.Fixed(4), Modifier.testTag("grid"), state = grid) {
                        stickyHeader { Text("Month 0") }
                        items(40) { Text("Month 0 $it", Modifier.height(80.dp)) }
                    }
                }
            } }
        }
        rule.mainClock.autoAdvance = false

        rule.onNodeWithTag("grid").performScrollToIndex(30)
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("monthRail").assertDoesNotExist()
    }

    /** Two months are a short scroll: no slider either. */
    @Test fun monthSlider_twoMonths_neverShows() {
        val months = listOf(0 to "Month 0", 41 to "Month 1")
        rule.setContent {
            MemoriaTheme { Surface {
                val grid = rememberLazyGridState()
                MonthSliderBox(grid, months) {
                    LazyVerticalGrid(GridCells.Fixed(4), Modifier.testTag("grid"), state = grid) {
                        months.forEach { (_, m) ->
                            stickyHeader { Text(m) }
                            items(40) { Text("$m $it", Modifier.height(80.dp)) }
                        }
                    }
                }
            } }
        }
        rule.mainClock.autoAdvance = false

        rule.onNodeWithTag("grid").performScrollToIndex(60)
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("monthRail").assertDoesNotExist()
    }
}
