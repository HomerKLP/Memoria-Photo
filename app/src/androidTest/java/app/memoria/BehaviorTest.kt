package app.memoria

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
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
        rule.onNodeWithTag("coverFace-102").performClick()
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
}
