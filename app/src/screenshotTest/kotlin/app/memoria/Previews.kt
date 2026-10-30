package app.memoria

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(widthDp = 360)
@Composable
fun FavoritesShortcut() = MemoriaTheme {
    Surface {
        Row(Modifier.padding(16.dp), Arrangement.spacedBy(10.dp)) {
            Shortcut(R.drawable.ic_favorite, "Favorites", "38", Modifier.weight(1f)) {}
            Spacer(Modifier.weight(1f))
        }
    }
}

/** The shared parts, in one place: keeps code and the mockup's "Shared parts" section in step. */
private fun fakeFace(id: Long, quality: Float) =
    FaceVec(id, 0, floatArrayOf(0f, 0f, 50f, 50f), FloatArray(512), quality, PhotoKind.camera, 1)

private fun fakePerson(id: Long, name: String?) =
    (1L..7L).map { fakeFace(id * 100 + it, if (it == 7L) 0.3f else 0.9f) }
        .let { Person(it, it.first(), id, name) }

private fun fakePet(id: Long, name: String?) =
    (1L..7L).map { FaceVec(id * 100 + it, it, floatArrayOf(0f, 0f, 50f, 50f), FloatArray(384), 0.8f, PhotoKind.camera, id, pet = true) }
        .let { Person(it, it.first(), id, name) }

@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun PeopleList() = MemoriaTheme {
    Surface {
        PeopleContent(
            data = PeopleData(
                summary = "7 people · 2 pets",
                people = listOf(fakePerson(1, "Dana"), fakePerson(2, "Arman"), fakePerson(3, null), fakePet(8, "Murka"), fakePerson(4, null), fakePerson(5, "Bota"), fakePerson(6, null), fakePet(9, null), fakePerson(7, "Mei")),
                unsure = listOf(fakeFace(201, 0.3f), fakeFace(202, 0.3f), fakeFace(203, 0.3f)),
            ),
        )
    }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun PersonScreen() = MemoriaTheme {
    Surface {
        PeopleContent(
            data = PeopleData(
                summary = "7 people · 412 faces",
                people = listOf(fakePerson(1, "Dana"), fakePerson(2, "Arman")),
                unsure = emptyList(),
            ),
            openId = 1,
        )
    }
}

/** Pet (mockup): pictures instead of faces, photos only in the title line, no Unsure card. Pairs with petMore_opening_keepsContentPut. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun PetScreen() = MemoriaTheme {
    Surface { PeopleContent(data = PeopleData(summary = "0 people · 1 pet", people = listOf(fakePet(1, "Murka")), unsure = emptyList()), openId = 1) }
}

/** Pet, More (mockup): the person's menu with pet words. Pairs with petMore_opening_keepsContentPut. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun PetMore() = MemoriaTheme {
    Surface {
        Box(Modifier.fillMaxSize()) {
            PeopleContent(data = PeopleData(summary = "0 people · 1 pet", people = listOf(fakePet(1, "Murka")), unsure = emptyList()), openId = 1)
            Menu(
                true, onDismiss = {},
                items = listOf(
                    MenuItem(R.drawable.ic_paw, "Rename") {},
                    MenuItem(R.drawable.ic_person_add, "Merge with") {},
                    MenuItem(R.drawable.ic_photo, "Change cover") {},
                    MenuItem(R.drawable.ic_block, "Hide pet", dividerBefore = true) {},
                ),
            )
        }
    }
}

/** Person with a 40-letter name: the top bar stays one line. Pairs with topBar_longName_keepsHeight. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun PersonLongName() = MemoriaTheme {
    Surface {
        PeopleContent(
            data = PeopleData(summary = "1 person · 7 faces", people = listOf(fakePerson(1, "W".repeat(40))), unsure = emptyList()),
            openId = 1,
        )
    }
}

/** Person, unnamed (mockup): "Add name" in the accent color. Pairs with personDetail_naming_keepsGridPut. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun PersonUnnamed() = MemoriaTheme {
    Surface {
        PeopleContent(
            data = PeopleData(summary = "1 person · 7 faces", people = listOf(fakePerson(1, null)), unsure = emptyList()),
            openId = 1,
        )
    }
}

/** Person, More (mockup): the person's fixes, Hide person under a line. Pairs with personMore_opening_keepsContentPut. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun PersonMore() = MemoriaTheme {
    Surface {
        Box(Modifier.fillMaxSize()) {
            PeopleContent(
                data = PeopleData(summary = "7 people · 412 faces", people = listOf(fakePerson(1, "Dana")), unsure = emptyList()),
                openId = 1,
            )
            Menu(
                true, onDismiss = {},
                items = listOf(
                    MenuItem(R.drawable.ic_person, "Rename") {},
                    MenuItem(R.drawable.ic_person_add, "Merge with") {},
                    MenuItem(R.drawable.ic_photo, "Change cover") {},
                    MenuItem(R.drawable.ic_block, "Hide person", dividerBefore = true) {},
                ),
            )
        }
    }
}

/** Person, selecting (mockup): the bar swaps, the faces stay. Pairs with personDetail_selecting_keepsTopBarAndFaces. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun PersonSelect() = MemoriaTheme {
    Surface {
        PersonDetail(
            fakePerson(1, "Dana"), onBack = {}, onName = {}, onOpen = { _, _ -> },
            loadPhotos = { ids -> ids.map { Tile(it, android.net.Uri.parse("content://media/external/images/media/$it"), 0L, null) } },
            pick = Pick(setOf(0L), size = 7_400_000),
        )
    }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 1000)
@Composable
fun LibraryLayout() = MemoriaTheme {
    Surface {
        LibraryContent(data = fakeLibrary())
    }
}

private fun fakeTrash(id: Long, days: Int) =
    Trashed(Tile(id, android.net.Uri.parse("content://media/external/images/media/$id"), 0L, if (id % 3 == 0L) 42_000L else null), days)

private val trashDays = listOf(29, 27, 27, 18, 12, 5, 2)

@PreviewTest
@Preview(widthDp = 360, heightDp = 800)
@Composable
fun TrashList() = MemoriaTheme {
    Surface { TrashContent(trashDays.mapIndexed { i, d -> fakeTrash((i + 1).toLong(), d) }) }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 800)
@Composable
fun TrashSelect() = MemoriaTheme {
    Surface {
        TrashContent(
            trashDays.mapIndexed { i, d -> fakeTrash((i + 1).toLong(), d) },
            selected = setOf(2L, 5L),
        )
    }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 800)
@Composable
fun TrashEmpty() = MemoriaTheme { Surface { TrashContent(emptyList()) } }

/** The shared parts, in one place: keeps code and the mockup's "Shared parts" section in step. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 620)
@Composable
fun SharedParts() = MemoriaTheme {
    Surface {
        Column {
            TopBar("Trash", "7 items", onBack = {}) {
                Text("Empty", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
            }
            TopBar("2 selected", "7 items", onBack = {}, backIcon = R.drawable.ic_close, backLabel = "Cancel") {
                Text("Select all", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            }
            Note(R.drawable.ic_info, "Items are deleted for good after 30 days", Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            ActionBar("Delete", R.drawable.ic_trash_line, {}, "Restore", R.drawable.ic_restore, {})
            PeopleRow(emptyList(), {}, {})
            EmptyState(R.drawable.ic_trash, "Trash is empty", "Deleted photos and videos stay here 30 days, then they are gone for good")
        }
    }
}

// --- State previews: the same screen before and after data arrives. Pairs with LayoutJumpTest,
// which states the "nothing else may move" rule as an assertion. Regenerate with
// ./gradlew :app:updateDebugScreenshotTest, compare with :app:validateDebugScreenshotTest.

private fun fakeLibrary(people: List<Person>? = null, unsure: List<FaceVec>? = null) = LibraryData(
    faces = people ?: (1L..4L).map { val f = fakeFace(it, 0.9f); Person(listOf(f), f, it, null) },
    unsure = unsure ?: (1L..3L).map { fakeFace(100 + it, 0.3f) },
    albums = listOf("Camera", "Screenshots", "Telegram", "WhatsApp", "Downloads").mapIndexed { i, n ->
        Album(i.toLong(), n, listOf(Tile(i.toLong(), android.net.Uri.parse("content://media/external/images/media/$i"), 0L, null)))
    },
    favorites = 38,
    videos = 211,
    trash = 7,
    places = fakePlaces,
    categories = fakeCategories,
)

private val fakePlaces = listOf("Riverton" to 642, "Northport" to 118, "Harborview" to 87, "Eastfield" to 64, "Lakemoor" to 52).mapIndexed { i, (n, c) ->
    Place(i, n, List(c) { Tile(i * 1000L + it, android.net.Uri.parse("content://media/external/images/media/$it"), 0L, null) })
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 760)
@Composable
fun PlacesGrid() = MemoriaTheme { Surface { PlacesContent(fakePlaces) } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 760)
@Composable
fun PlacesLoading() = MemoriaTheme { Surface { PlacesContent(null) } }

/** Place, selecting (mockup). Pairs with place_selecting_keepsTopBarAndGridTop. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 760)
@Composable
fun PlaceSelect() = MemoriaTheme { Surface { PlaceContent(fakePlaces[1], pick = Pick(setOf(1001L, 1003L, 1004L), size = 8_100_000)) } }

private fun fakeAlbum(id: Long, name: String, count: Int) =
    Album(id, name, List(count) { Tile(id * 1000 + it, android.net.Uri.parse("content://media/external/images/media/${id * 1000 + it}"), 1_700_000_000_000L - it * 86_400_000L, null) })

@PreviewTest
@Preview(widthDp = 360, heightDp = 760)
@Composable
fun AlbumsGrid() = MemoriaTheme {
    Surface { AlbumsContent(listOf(fakeAlbum(1, "Camera", 5), fakeAlbum(2, "Screenshots", 3), fakeAlbum(3, "Telegram", 2), fakeAlbum(4, "Downloads", 1))) }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 760)
@Composable
fun AlbumsLoading() = MemoriaTheme { Surface { AlbumsContent(null) } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 760)
@Composable
fun AlbumGrid() = MemoriaTheme { Surface { AlbumContent(fakeAlbum(1, "Camera", 9)) } }

/** Album, selecting (mockup): three picked, the four-action Select bar under the grid. Pairs with album_selecting_keepsTopBarAndGridTop. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 760)
@Composable
fun AlbumSelect() = MemoriaTheme { Surface { AlbumContent(fakeAlbum(1, "Camera", 9), selected = setOf(1000L, 1001L, 1004L), pickedSize = 9_600_000) } }

/** Album, More (mockup): Select, then Ignore faces with its check on. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 760)
@Composable
fun AlbumMore() = MemoriaTheme {
    Surface {
        Box(Modifier.fillMaxSize()) {
            AlbumContent(fakeAlbum(1, "Camera", 9), ignored = true)
            Menu(
                true, onDismiss = {},
                items = listOf(
                    MenuItem(R.drawable.ic_select, "Select") {},
                    MenuItem(R.drawable.ic_block, "Ignore faces in this album", dividerBefore = true, checked = true) {},
                ),
            )
        }
    }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 760)
@Composable
fun VideosGrid() = MemoriaTheme {
    val months = linkedMapOf(
        "October 2026" to (1L..4L).map { Tile(it, android.net.Uri.parse("content://media/external/video/media/$it"), 1_700_000_000_000L - it * 86_400_000L, 12_000L) },
        "September 2026" to (5L..7L).map { Tile(it, android.net.Uri.parse("content://media/external/video/media/$it"), 1_690_000_000_000L - it * 86_400_000L, 8_000L) },
    )
    Surface { VideosContent(Videos(months, months.values.flatten(), 2_500_000_000L)) }
}

/** Videos, selecting (mockup). Pairs with videos_selecting_keepsTopBarAndGridTop. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 760)
@Composable
fun VideosSelect() = MemoriaTheme {
    val tiles = (1L..7L).map { Tile(it, android.net.Uri.parse("content://media/external/video/media/$it"), 1_700_000_000_000L - it * 86_400_000L, 12_000L) }
    Surface { VideosContent(Videos(linkedMapOf("October 2026" to tiles), tiles, 2_500_000_000L), pick = Pick(setOf(2L, 4L), size = 412_000_000)) }
}

private fun fakeFavorites() = linkedMapOf(
    "October 2026" to (1L..4L).map { Tile(it, android.net.Uri.parse("content://media/external/images/media/$it"), 1_700_000_000_000L - it * 86_400_000L, null) },
    "September 2026" to (5L..7L).map { Tile(it, android.net.Uri.parse("content://media/external/images/media/$it"), 1_690_000_000_000L - it * 86_400_000L, null) },
).let { Favorites(it, it.values.flatten()) }

@PreviewTest
@Preview(widthDp = 360, heightDp = 760)
@Composable
fun FavoritesGrid() = MemoriaTheme { Surface { FavoritesContent(fakeFavorites()) } }

/** Favorites, selecting (mockup): the bar reads Unfavorite. Pairs with favorites_selecting_keepsTopBarAndGridTop. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 760)
@Composable
fun FavoritesSelect() = MemoriaTheme { Surface { FavoritesContent(fakeFavorites(), pick = Pick(setOf(1L, 3L, 6L), size = 11_200_000)) } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 760)
@Composable
fun FavoritesLoading() = MemoriaTheme { Surface { FavoritesContent(null) } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 760)
@Composable
fun DetailsView() = MemoriaTheme {
    Surface(color = viewerBg) {
        DetailsContent(
            tile = Tile(1, android.net.Uri.parse("content://media/external/images/media/1"), 1_700_000_000_000L, null),
            info = PhotoInfo("IMG_20261011_143201.jpg", "DCIM/Camera", 3_500_000, 4032, 3024, "Mountain View", "Pixel 9", "f/1.8 · 1/120 s · ISO 64 · 24 mm"),
            faces = listOf(
                fakeFace(1, 0.9f),
                FaceVec(2, 0, floatArrayOf(0f, 0f, 50f, 50f), FloatArray(512), 0.9f, PhotoKind.camera, null),
            ),
            people = listOf(fakePerson(1, "Dana")),
            zone = java.time.ZoneOffset.UTC, // the recording machine's zone would leak into the baseline
        )
    }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 1000)
@Composable
fun LibraryEmpty() = MemoriaTheme {
    Surface { LibraryContent(fakeLibrary(people = emptyList(), unsure = emptyList())) }
}

/** One face to check; pairs with library_fewerUnsureFaces_reviewTextStaysPut. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 1000)
@Composable
fun LibraryOneUnsure() = MemoriaTheme { Surface { LibraryContent(fakeLibrary(unsure = listOf(fakeFace(101, 0.3f)))) } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 1000)
@Composable
fun LibraryLoading() =MemoriaTheme { Surface { LibraryContent(data = null) } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 1000)
@Composable
fun LibraryIndexing() = MemoriaTheme { Surface { LibraryContent(fakeLibrary(people = emptyList(), unsure = emptyList()), indexing = true) } }

/** Mockup "Folders to skip", two skipped; pairs with skip_checking_rowsStayPut. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 760)
@Composable
fun SkipFolders() = MemoriaTheme {
    Surface {
        SkipContent(
            listOf("Camera" to 1204, "WhatsApp" to 312, "Screenshots" to 214, "Telegram" to 96, "Holidays 2024" to 47, "Instagram" to 21, "Downloads" to 18, "Edits" to 9)
                .mapIndexed { i, (n, c) -> fakeAlbum(i.toLong(), n, c) },
            skipped = setOf(1L, 2L),
        )
    }
}

/** Settings with a run going (mockup "Settings"); the Scan states pair with settings_faceSearchStates_rowsStayPut. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun SettingsPage() = MemoriaTheme { Surface { SettingsContent(charging = false, allowed = false, hidden = 2, version = "0.1", indexing = true, indexed = 412, total = 1706) } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun SettingsSearchPaused() = MemoriaTheme { Surface { SettingsContent(charging = false, allowed = false, hidden = 2, version = "0.1", paused = true, indexed = 412, total = 1706) } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun SettingsSearchWaiting() = MemoriaTheme { Surface { SettingsContent(charging = true, allowed = false, hidden = 2, version = "0.1", waiting = true, indexed = 412, total = 1706) } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun SettingsSearchDone() = MemoriaTheme { Surface { SettingsContent(charging = false, allowed = false, hidden = 2, version = "0.1", indexed = 1706, total = 1706) } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun SettingsModelsMissing() = MemoriaTheme { Surface { SettingsContent(charging = false, allowed = false, hidden = 2, version = "0.1", models = Models.Status.Missing) } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun SettingsModelsDownloading() = MemoriaTheme {
    Surface { SettingsContent(charging = false, allowed = false, hidden = 2, version = "0.1", models = Models.Status.Downloading, modelsDone = 77_594_624) }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun SettingsModelsStopped() = MemoriaTheme {
    Surface { SettingsContent(charging = false, allowed = false, hidden = 2, version = "0.1", models = Models.Status.Stopped, modelsDone = 77_594_624) }
}

/** Release Settings with the Updates row; taller, so About is in the shot. Pairs with settings_updateStates_rowsStayPut. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 1100)
@Composable
fun SettingsUpdateUpToDate() = MemoriaTheme {
    // a fixed day: the renderer prints the date, so now() changed the image daily
    Surface { SettingsContent(charging = false, allowed = false, hidden = 2, version = "0.2.0", update = Updates.State(Updates.Status.UpToDate, checkedAt = 1_792_497_600_000L)) }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 1100)
@Composable
fun SettingsUpdateNew() = MemoriaTheme {
    Surface { SettingsContent(charging = false, allowed = false, hidden = 2, version = "0.2.0", update = Updates.State(Updates.Status.New, "0.3.0", 102_760_448)) }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 1100)
@Composable
fun SettingsUpdateDownloading() = MemoriaTheme {
    Surface { SettingsContent(charging = false, allowed = false, hidden = 2, version = "0.2.0", update = Updates.State(Updates.Status.Downloading, "0.3.0", 102_760_448, 42_991_616)) }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 1100)
@Composable
fun SettingsUpdateReady() = MemoriaTheme {
    Surface { SettingsContent(charging = false, allowed = false, hidden = 2, version = "0.2.0", update = Updates.State(Updates.Status.Ready, "0.3.0", 102_760_448, 102_760_448)) }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun SettingsDeveloper() = MemoriaTheme { Surface { SettingsContent(charging = false, allowed = false, hidden = 2, version = "0.1", onDev = {}) } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 1200)
@Composable
fun LicensesPage() = MemoriaTheme { Surface { LicensesContent() } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun DevToolsLoading() = MemoriaTheme { Surface { DevToolsContent(busy = false, numbers = null, log = "Index -\nPhotos: …/files/test") } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun DevTools() = MemoriaTheme {
    Surface {
        DevToolsContent(busy = false, numbers = DevNumbers(1402, 180, 124, 2870, 685, 9, 33, 2, 1480, 226), log = "Index SUCCEEDED\nPhotos: …/files/test")
    }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun PeopleLoading() = MemoriaTheme { Surface { PeopleContent(data = null) } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun PeopleEmpty() = MemoriaTheme {
    Surface { PeopleContent(data = PeopleData("No people", emptyList(), emptyList())) }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 620)
@Composable
fun NamePersonSheetPreview() = MemoriaTheme {
    Surface { NamePersonContent(fakePerson(1, null), "", {}, {}, {}) }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 620)
@Composable
fun WhoIsThisSheetPreview() = MemoriaTheme {
    Surface {
        WhoIsThisContent(
            fakeFace(9, 0.9f), null,
            listOf(fakePerson(1, "Dana"), fakePerson(2, "Arman")),
            "", {}, {}, null, {},
        )
    }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 720)
@Composable
fun MergeSheetPreview() = MemoriaTheme {
    Surface {
        MergeContent(fakePerson(1, null), listOf(fakePerson(2, "Dana"), fakePerson(3, "Arman")), "", {}, null, {}, {}, {})
    }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 720)
@Composable
fun ChangeCoverSheetPreview() = MemoriaTheme {
    Surface {
        ChangeCoverContent(fakePerson(1, null), null, {}, {}, {})
    }
}

/** Merge with, one picked (mockup `.pcard.on`): the accent ring and the check at the face's top right. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 720)
@Composable
fun MergePickedPreview() = MemoriaTheme {
    Surface {
        val picked = fakePerson(2, "Dana")
        MergeContent(fakePerson(1, null), listOf(picked, fakePerson(3, "Arman")), "", {}, picked, {}, {}, {})
    }
}

/** Change cover, one picked: the same ring and check, and Save on. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 720)
@Composable
fun ChangeCoverPickedPreview() = MemoriaTheme {
    Surface {
        val p = fakePerson(1, null)
        ChangeCoverContent(p, p.faces.first().id, {}, {}, {})
    }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 800)
@Composable
fun TrashLoading() = MemoriaTheme { Surface { TrashContent(emptyList(), loading = true) } }

/** Move to album sheet (mockup "Move to album"): the head, New album, then the folders. */
@PreviewTest
@Preview(widthDp = 360)
@Composable
fun MoveToAlbumSheetPreview() = MemoriaTheme {
    Surface {
        MoveToAlbumContent(
            Tile(1, android.net.Uri.parse("content://media/external/images/media/1"), 1_700_000_000_000L, null),
            "Camera",
            listOf(
                MoveTarget("Screenshots", "Pictures/Screenshots/", null, 214),
                MoveTarget("Telegram", "Pictures/Telegram/", null, 96),
                MoveTarget("Holidays 2024", "Pictures/Holidays 2024/", null, 47),
            ),
            false, "", {}, {}, {}, {}, {},
        )
    }
}

/** Move to album with a 40-letter album name: the row stays one line. Pairs with moveToAlbum_longAlbumName_keepsRowsPut. */
@PreviewTest
@Preview(widthDp = 360)
@Composable
fun MoveToAlbumLongName() = MemoriaTheme {
    Surface {
        MoveToAlbumContent(
            Tile(1, android.net.Uri.parse("content://media/external/images/media/1"), 1_700_000_000_000L, null),
            "Camera",
            listOf(MoveTarget("W".repeat(40), "Pictures/Long/", null, 214), MoveTarget("Telegram", "Pictures/Telegram/", null, 96)),
            false, "", {}, {}, {}, {}, {},
        )
    }
}

/** Move to album, New album: the name field and Cancel / Move (the other state of the jump test). */
@PreviewTest
@Preview(widthDp = 360)
@Composable
fun MoveToAlbumNamingPreview() = MemoriaTheme {
    Surface {
        MoveToAlbumContent(
            Tile(1, android.net.Uri.parse("content://media/external/images/media/1"), 1_700_000_000_000L, null),
            "Camera", emptyList(), true, "", {}, {}, {}, {}, {},
        )
    }
}

/** New album sheet (mockup "New album"): the folder head, the name field, Cancel / Next. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 620)
@Composable
fun NewAlbumSheetPreview() = MemoriaTheme {
    Surface { NewAlbumContent("Holidays", {}, false, {}, {}) }
}

/** New album, pick photos (mockup): the timeline in select mode, "Add to <name>", Cancel / Move here. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 800)
@Composable
fun NewAlbumPickPreview() = MemoriaTheme {
    val tiles = (1L..9L).map { Tile(it, android.net.Uri.parse("content://media/external/images/media/$it"), 0L, null) }
    val items: List<TimelineItem> = remember { listOf(Header("m1", "October 2026", true), Header("d1", "Sat, 11 Oct", false)) + tiles }
    val selection = remember { PhotoSelection().apply { addAll(tiles.take(6)) } }
    Surface {
        PhotosContent(
            items, selection = selection, pickedSize = 18_400_000,
            forceSelecting = true, titleWhileSelecting = "Add to Holidays", scrollWhileSelecting = true,
            footer = { ActionBar("Cancel", null, {}, "Move here", R.drawable.ic_move, {}, danger = false) },
        )
    }
}

/** The timeline month slider (Ui.kt shared part): the rail and the knob on the month on screen. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 480)
@Composable
fun MonthSliderPreview() = MemoriaTheme {
    Surface {
        Box(Modifier.fillMaxSize()) {
            MonthSlider(
                months = listOf("September 2026", "August 2026", "July 2026", "June 2026", "May 2026", "April 2026"),
                current = 2,
                dragging = null,
                visible = true,
                onDragging = {},
                onJump = {},
                top = monthHeadingHeight(below = 2.dp),
            ) {
                Box(Modifier.fillMaxSize().background(Color(0xFFE9D8B8))) {
                    Text("September 2026", Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 16.dp, top = 20.dp, bottom = 2.dp), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

/** Viewer bars (Viewer.kt): they float on the photo, transparent (a soft gradient keeps the icons readable). */
private val viewerPhoto = Color(0xFF7FA36B)

@PreviewTest
@Preview(widthDp = 360, heightDp = 800)
@Composable
fun ViewerBars() = MemoriaTheme {
    Surface {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(viewerPhoto))
            ViewerChrome(true, "Sat, 11 Oct", "14:32", favorite = false, {}, {}, {}, {}, {})
        }
    }
}

/** Viewer, tapped: the bars are gone, only the photo is left. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 800)
@Composable
fun ViewerTapped() = MemoriaTheme {
    Surface {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(viewerPhoto))
            ViewerChrome(false, "Sat, 11 Oct", "14:32", favorite = false, {}, {}, {}, {}, {})
        }
    }
}

/** Viewer, More (mockup): the menu drops under the More over the photo, no dim. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 800)
@Composable
fun ViewerMore() = MemoriaTheme {
    Surface {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(viewerPhoto))
            Menu(
                true, onDismiss = {},
                items = listOf(
                    MenuItem(R.drawable.ic_edit, "Edit in…") {},
                    MenuItem(R.drawable.ic_wallpaper, "Use as…") {},
                    MenuItem(R.drawable.ic_folder, "Show in album") {},
                ),
            )
        }
    }
}

/** Photos, selecting (mockup): the select top bar and the day circle, some tiles picked. Pairs with photos_selecting_keepsGridPut. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 800)
@Composable
fun PhotosSelect() = MemoriaTheme {
    val tiles = (1L..9L).map { Tile(it, android.net.Uri.parse("content://media/external/images/media/$it"), 0L, if (it % 3 == 0L) 42_000L else null, it == 2L) }
    val items: List<TimelineItem> = remember { listOf(Header("m1", "October 2026", true), Header("d1", "Sat, 11 Oct", false)) + tiles }
    val selection = remember { PhotoSelection().apply { addAll(tiles.take(6)) } }
    Surface { PhotosContent(items, selection = selection, pickedSize = 18_400_000) }
}

private val fakeCategories = listOf("Pets" to 412, "Food" to 174, "Selfies" to 96, "Group photos" to 71, "Documents" to 58, "Night" to 44, "Sunsets" to 41).mapIndexed { i, (n, c) ->
    val tiles = List(c) { Tile(5000L + i * 1000 + it, android.net.Uri.parse("content://media/external/images/media/$it"), 0L, null) }
    Category(n.lowercase(), n, if (n == "Pets") listOf("cat", "dog") else emptyList(), tiles.first(), tiles)
}

private val fakeSearch = SearchData(
    people = listOf(fakePerson(1, "Dana"), fakePerson(2, "Arman"), fakePerson(3, "Bota"), fakePerson(4, "Mei")),
    hits = listOf(
        Hit(HitKind.person, "1", "Dana", "230 photos", face = fakeFace(100, 0.9f)),
        Hit(HitKind.album, "5", "Downloads", "33 items", cover = fakeAlbum(5, "Downloads", 1).tiles.first()),
        Hit(HitKind.category, "pets", "Pets", "412 photos", cover = fakeCategories[0].cover, words = fakeCategories[0].words),
        Hit(HitKind.category, "documents", "Documents", "58 photos", cover = fakeCategories[4].cover),
        Hit(HitKind.year, "m2025-12", "2025", "412 items"),
        Hit(HitKind.month, "m2025-12", "December 2025", "48 items"),
        Hit(HitKind.album, "6", "Camera", "1,204 items", cover = fakeAlbum(6, "Camera", 1).tiles.first()),
    ),
    types = listOf(Hit(HitKind.videos, "videos", "Videos"), Hit(HitKind.favorites, "favorites", "Favorites"), Hit(HitKind.album, "7", "Screenshots")),
)

/** Search (mockup): named people, types and recent. Pairs with search_loadingAndTyping_keepFieldPut. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 760)
@Composable
fun SearchStart() = MemoriaTheme { Surface { SearchContent(fakeSearch, "", recent = listOf(fakeSearch.hits[0], fakeSearch.hits[5], fakeSearch.hits[6])) } }

/** Search, typing (mockup): word-start hits, the typed letter in the accent. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 760)
@Composable
fun SearchTyping() = MemoriaTheme { Surface { SearchContent(fakeSearch, "d") } }

/** Search, typing two letters: a category found by one of its words shows that word before the count. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 400)
@Composable
fun SearchWordHit() = MemoriaTheme { Surface { SearchContent(fakeSearch, "do") } }

/** Categories (mockup). Pairs with categories_loadingToLoaded_titleStaysPut. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 760)
@Composable
fun CategoriesGrid() = MemoriaTheme { Surface { CategoriesContent(fakeCategories) } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 760)
@Composable
fun CategoriesLoading() = MemoriaTheme { Surface { CategoriesContent(null) } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 400)
@Composable
fun SearchNoHits() = MemoriaTheme { Surface { SearchContent(fakeSearch, "xyz") } }
