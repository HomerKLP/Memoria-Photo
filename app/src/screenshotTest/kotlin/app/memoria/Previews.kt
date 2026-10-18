package app.memoria

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
    FaceVec(id, 0, floatArrayOf(0f, 0f, 50f, 50f), FloatArray(512), quality, "camera", 1)

private fun fakePerson(id: Long, name: String?) =
    (1L..7L).map { fakeFace(id * 100 + it, if (it == 7L) 0.3f else 0.9f) }
        .let { Person(it, it.first(), id, name) }

@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun PeopleList() = MemoriaTheme {
    Surface {
        PeopleContent(
            data = PeopleData(
                summary = "7 people · 412 faces",
                people = listOf(fakePerson(1, "Dana"), fakePerson(2, "Arman"), fakePerson(3, null), fakePerson(4, null), fakePerson(5, "Bota"), fakePerson(6, null), fakePerson(7, "Mei")),
                unsure = listOf(fakeFace(201, 0.3f), fakeFace(202, 0.3f), fakeFace(203, 0.3f)),
                total = 1706,
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
                total = 1706,
            ),
            openId = 1,
        )
    }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 1000)
@Composable
fun LibraryLayout() = MemoriaTheme {
    Surface {
        LibraryContent(
            data = fakeLibrary(),
            indexing = true,
            indexed = 412,
        )
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
    total = 1706,
    places = fakePlaces,
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

private fun fakeFavorites() = linkedMapOf(
    "October 2026" to (1L..4L).map { Tile(it, android.net.Uri.parse("content://media/external/images/media/$it"), 1_700_000_000_000L - it * 86_400_000L, null) },
    "September 2026" to (5L..7L).map { Tile(it, android.net.Uri.parse("content://media/external/images/media/$it"), 1_690_000_000_000L - it * 86_400_000L, null) },
).let { Favorites(it, it.values.flatten()) }

@PreviewTest
@Preview(widthDp = 360, heightDp = 760)
@Composable
fun FavoritesGrid() = MemoriaTheme { Surface { FavoritesContent(fakeFavorites()) } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 760)
@Composable
fun FavoritesLoading() = MemoriaTheme { Surface { FavoritesContent(null) } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 760)
@Composable
fun DetailsView() = MemoriaTheme {
    Surface {
        DetailsContent(
            tile = Tile(1, android.net.Uri.parse("content://media/external/images/media/1"), 1_700_000_000_000L, null),
            info = PhotoInfo("IMG_20261011_143201.jpg", "DCIM/Camera", 3_500_000, 4032, 3024, "Mountain View", "Pixel 9", "f/1.8 · 1/120 s · ISO 64 · 24 mm"),
            faces = listOf(
                fakeFace(1, 0.9f),
                FaceVec(2, 0, floatArrayOf(0f, 0f, 50f, 50f), FloatArray(512), 0.9f, "camera", null),
            ),
            people = listOf(fakePerson(1, "Dana")),
        )
    }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 1000)
@Composable
fun LibraryEmpty() = MemoriaTheme {
    Surface { LibraryContent(fakeLibrary(people = emptyList(), unsure = emptyList())) }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 1000)
@Composable
fun LibraryLoading() = MemoriaTheme { Surface { LibraryContent(data = null) } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 1000)
@Composable
fun LibraryIndexing() = MemoriaTheme { Surface { LibraryContent(fakeLibrary(), indexing = true, indexed = 412) } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 1000)
@Composable
fun LibraryPaused() = MemoriaTheme { Surface { LibraryContent(fakeLibrary(), paused = true, indexed = 412) } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 1000)
@Composable
fun LibraryWaiting() = MemoriaTheme { Surface { LibraryContent(fakeLibrary(), waiting = true, indexed = 412) } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun SettingsPage() = MemoriaTheme { Surface { SettingsContent(charging = false, allowed = false, hidden = 2, version = "0.1") } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun PeopleLoading() = MemoriaTheme { Surface { PeopleContent(data = null) } }

@PreviewTest
@Preview(widthDp = 360, heightDp = 900)
@Composable
fun PeopleEmpty() = MemoriaTheme {
    Surface { PeopleContent(data = PeopleData("No people", emptyList(), emptyList(), total = 0)) }
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

/** The timeline month slider (Ui.kt shared part): rail, ticks, the month on screen and the knob. */
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
            ) {
                Box(Modifier.fillMaxSize().background(Color(0xFFE9D8B8)))
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
    val items: List<Any> = remember { listOf(Header("m1", "October 2026", true), Header("d1", "Sat, 11 Oct", false)) + tiles }
    val selection = remember { PhotoSelection().apply { addAll(tiles.take(6)) } }
    Surface { PhotosContent(items, selection = selection, pickedSize = 18_400_000) }
}
