package app.memoria

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the Library tab shows, loaded in one pass. */
data class LibraryData(
    val faces: List<Person>,        // one person per row entry (main face + name), same order as the People screen
    val unsure: List<FaceVec>,      // unsure faces for the "Help recognize" card
    val albums: List<Album>,        // device folders, biggest first
    val favorites: Int,
    val videos: Int,
    val trash: Int,
    val places: List<Place> = emptyList(), // towns, most photos first
)

/** The same data with one person's cover changed, for updating a cached list in place (see [updateCached]). */
fun LibraryData.withCover(personId: Long, faceId: Long?) =
    copy(faces = faces.map { if (it.id == personId) it.withCover(faceId) else it })

/** Shown at once when the tab opens again, then replaced by a fresh load. */
@Composable
fun LibraryScreen(
    onOpenSearch: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenPerson: (Long) -> Unit,
    onOpenAlbums: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenPlaces: () -> Unit,
    onOpenPlace: (Int) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenVideos: () -> Unit,
    onOpenTrash: () -> Unit,
    onHelpRecognize: () -> Unit,
    onOpenSettings: () -> Unit,
    reload: Int = 0,
) {
    val context = LocalContext.current
    val dao = remember { Db.get(context).index() }
    val work by remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("index") }.collectAsState(emptyList())
    val running = work.any { it.state == WorkInfo.State.RUNNING }
    // cached, not null: an empty first frame is shorter and the tab's restored scroll clamps to it.
    // Keyed on [saved]: the run saves people every so often, so they show up while indexing. [reload]: back from a screen opened over it.
    val saved by peopleSaved.collectAsState()
    val data by cachedProduceState("library", saved, reload) {
        withContext(Dispatchers.IO) {
            // Faces without vectors: the row only shows covers. Merge with loads the vectors when it opens.
            val faces = dao.taggedFaces()
            val byId = faces.associateBy { it.id }
            val byPerson = faces.groupBy { it.personId }
            val people = dao.people().filter { !it.hidden }.mapNotNull { p -> byId[p.mainFaceId]?.let { m -> Person((byPerson[p.id] ?: listOf(m)).map { it.toVec() }, m.toVec(), p.id, p.name, false, p.coverFaceId) } }
                .sortedWith(compareBy({ it.name.isNullOrBlank() }, { -it.photos })) // named first, then by photo count
            val albums = albums(context)
            val all = albums.flatMap { it.tiles }
            LibraryData(
                faces = people,
                unsure = people.flatMap { p -> p.faces.filter { !it.sure } }.sortedBy { it.quality },
                albums = albums,
                favorites = all.count { it.favorite },
                videos = all.count { it.duration != null },
                trash = runCatching { trash(context).size }.getOrDefault(0),
                places = places(dao.placed(), all),
            )
        }
    }
    LibraryContent(
        data = data,
        indexing = running,
        onOpenSearch = onOpenSearch,
        onOpenPeople = onOpenPeople,
        onOpenPerson = onOpenPerson,
        onOpenAlbums = onOpenAlbums,
        onOpenAlbum = onOpenAlbum,
        onOpenPlaces = onOpenPlaces,
        onOpenPlace = onOpenPlace,
        onOpenFavorites = onOpenFavorites,
        onOpenVideos = onOpenVideos,
        onOpenTrash = onOpenTrash,
        onHelpRecognize = onHelpRecognize,
        onOpenSettings = onOpenSettings,
    )
}

/** Library tab: people, review, albums, places, shortcuts. Layout per docs/design.html; the index run shows in Settings. */
@Composable
fun LibraryContent(
    data: LibraryData?,
    indexing: Boolean = false,
    onOpenSearch: () -> Unit = {},
    onOpenPeople: () -> Unit = {},
    onOpenPerson: (Long) -> Unit = {},
    onOpenAlbums: () -> Unit = {},
    onOpenAlbum: (Long) -> Unit = {},
    onOpenPlaces: () -> Unit = {},
    onOpenPlace: (Int) -> Unit = {},
    onOpenFavorites: () -> Unit = {},
    onOpenVideos: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onHelpRecognize: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Library", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onOpenSettings) { Icon(painterResource(R.drawable.ic_settings), "Settings") }
        }
        SearchBar(onOpenSearch)
        SectionHead("People", "See all", onOpenPeople)
        PeopleRow(data?.faces, onOpenPerson, onOpenPeople, finding = indexing)
        // still loading: an invisible card holds its space, so Albums does not jump down when it arrives
        if (data == null) Box(Modifier.alpha(0f)) { ReviewCard(emptyList(), {}) }
        else if (data.unsure.isNotEmpty()) ReviewCard(data.unsure, onHelpRecognize)

        SectionHead("Albums", "See all", onOpenAlbums)
        AlbumRows(data?.albums, onOpenAlbum)
        SectionHead("Places", "See all", onOpenPlaces)
        PlaceRow(data?.places, onOpenPlace)

        Shortcuts(onOpenFavorites, onOpenVideos, onOpenTrash, data?.favorites ?: 0, data?.videos ?: 0, data?.trash ?: 0)
    }
}

/** Rounded search field from the mockup: 48 dp tall, 24 dp corners, 16 dp from the edge. A tap opens Search. */
@Composable
fun SearchBar(onClick: () -> Unit) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp).fillMaxWidth()
            .height(48.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text("Search people, places, dates", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "People" / "See all" header row. */
@Composable
fun SectionHead(title: String, action: String? = null, onAction: (() -> Unit)? = null, hint: String? = null, chevron: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // with a hint, the hint shrinks and ends with …, not the title
        Text(title, if (hint == null) Modifier.weight(1f) else Modifier, style = MaterialTheme.typography.titleMedium, fontSize = 18.sp)
        if (action != null) Row(
            Modifier.clip(RoundedCornerShape(18.dp)).clickable(enabled = onAction != null) { onAction?.invoke() }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(action, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
            if (chevron) Icon(painterResource(R.drawable.ic_chevron), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        if (hint != null) Text(
            hint, Modifier.weight(1f).padding(start = 12.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Small accent pill button, like the mockup's Review / Pause. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier, sides: Dp = 16.dp) {
    Text(
        text,
        modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primary)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = sides, vertical = 9.dp),
        // 18 sp line + 9 dp padding = the mockup's 36 dp; the default 24 sp line made it 42
        color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp,
        textAlign = TextAlign.Center, // for a fixed [modifier] width
    )
}

/** Horizontal row of up to 12 people, 72 dp faces, then a See all tile. Tap opens the person; naming happens on the person screen. */
@Composable
fun PeopleRow(faces: List<Person>?, onOpen: (Long) -> Unit, onSeeAll: () -> Unit, finding: Boolean = false) {
    // people a regroup adds fade in; the first load shows at once
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(faces != null) { if (faces != null) loaded = true }
    LazyRow(
        Modifier.testTag("peopleRow").fillMaxWidth().padding(bottom = 2.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // null = still loading: same-size grey circles, so nothing below moves when the faces arrive
        if (faces == null) items(5) {
            Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
                Spacer(Modifier.height(6.dp))
                Text("", fontSize = 13.sp, minLines = 1)
            }
        }
        items(faces.orEmpty().take(12), key = { it.id }) { p ->
            Column(Modifier.animateItem(fadeInSpec = if (loaded) spring(stiffness = Spring.StiffnessMediumLow) else null).width(72.dp).clickable { onOpen(p.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                FaceImage(p.cover, Modifier.size(72.dp).clip(CircleShape))
                Spacer(Modifier.height(6.dp))
                // empty line for unnamed people too: the row's height follows the visible items, so it jumped while scrolling
                Text(p.name.orEmpty(), fontSize = 13.sp, minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if ((faces?.size ?: 0) > 12) item {
            Column(Modifier.width(72.dp).clickable(onClick = onSeeAll), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_chevron), null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(6.dp))
                Text("See all", fontSize = 13.sp, maxLines = 1, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            }
        }
        if (faces?.isEmpty() == true) item {
            // same 72 dp item as a face: the row keeps its height, so Albums below does not move when people arrive.
            // Width is free to grow for the one-line label; only the height has to match.
            Column(Modifier.widthIn(min = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_person), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                Text(if (finding) "Finding faces…" else "No people yet", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "Help recognize" card: the first 3 unsure faces as a stack and a Review button. [note] defaults to the face count. */
@Composable
fun ReviewCard(unsure: List<FaceVec>, onReview: () -> Unit, title: String = "Help recognize", note: String? = null) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 36 dp faces, each 12 dp over the last; the ring sits outside the photo, so no photo edge shows around it.
        // Always 3 faces wide, so the text does not shift left when fewer are left.
        Row(Modifier.width(96.dp), horizontalArrangement = Arrangement.spacedBy((-12).dp)) {
            unsure.take(3).forEach { f ->
                FaceImage(f, Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape).padding(2.dp).clip(CircleShape))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(note ?: "${unsure.size} face${if (unsure.size == 1) "" else "s"} to check", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        PillButton("Review", onReview)
    }
}

/** Up to 6 album covers in two rows. null = still loading: grey covers hold the space. */
@Composable
fun AlbumRows(albums: List<Album>?, onOpen: (Long) -> Unit) {
    val shown: List<Album?> = albums?.take(6) ?: List(6) { null }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        shown.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { a ->
                    Box(Modifier.weight(1f)) {
                        if (a == null) CoverTile(null, "", "")
                        else CoverTile(a.tiles.first(), a.name, "${a.tiles.size}", Modifier.clickable { onOpen(a.id) })
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Album and place cover (mockup `.place`): the photo, square, name and count on it over a dark fade with a text shadow, so white
 * covers stay readable. One line each, a long name ends with …. Grey when [cover] is null.
 */
@Composable
fun CoverTile(cover: Tile?, label: String, note: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (cover == null) return@Box
        Thumb(cover.copy(duration = null), Modifier.fillMaxSize()) // no video badge on a cover
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
        val shadow = with(LocalDensity.current) { TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 1.dp.toPx()), 2.dp.toPx())) }
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, style = shadow)
            Text(note, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, maxLines = 1, style = shadow)
        }
    }
}

/** Favorites, Videos, Locked folder, Trash. */
@Composable
fun Shortcuts(onOpenFavorites: () -> Unit, onOpenVideos: () -> Unit, onOpenTrash: () -> Unit, favorites: Int, videos: Int, trash: Int) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Shortcut(R.drawable.ic_favorite, "Favorites", favorites.toString(), Modifier.weight(1f), onOpenFavorites)
            Shortcut(R.drawable.ic_video, "Videos", videos.toString(), Modifier.weight(1f), onOpenVideos)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Shortcut(R.drawable.ic_lock, "Locked folder", "Private", Modifier.weight(1f), null) // not built yet
            Shortcut(R.drawable.ic_trash, "Trash", if (trash == 0) "Empty" else trash.toString(), Modifier.weight(1f), onOpenTrash)
        }
    }
}
