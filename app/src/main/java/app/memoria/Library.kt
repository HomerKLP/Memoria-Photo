package app.memoria

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the Library tab shows, loaded in one pass. Places waits for its screen. */
class LibraryData(
    val faces: List<Person>,        // one person per row entry (main face + name), same order as the People screen
    val unsure: List<FaceVec>,      // unsure faces for the "Help recognize" card
    val albums: List<Album>,        // device folders, biggest first
    val favorites: Int,
    val videos: Int,
    val trash: Int,
    val total: Int,                 // photos in the index scope, for the progress bar
)

/** Shown at once when the tab opens again, then replaced by a fresh load. */
@Composable
fun LibraryScreen(
    onOpenPeople: () -> Unit,
    onOpenPerson: (Long) -> Unit,
    onOpenAlbums: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenVideos: () -> Unit,
    onOpenTrash: () -> Unit,
    onHelpRecognize: () -> Unit,
    onOpenSettings: () -> Unit,
    onDev: () -> Unit,
) {
    val context = LocalContext.current
    val dao = remember { Db.get(context).index() }
    val work by remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("index") }.collectAsState(emptyList())
    val indexed by remember { dao.kinds() }.collectAsState(emptyMap())
    val running = work.any { it.state == WorkInfo.State.RUNNING }
    // cached, not null: an empty first frame is shorter and the tab's restored scroll clamps to it.
    // Keyed on [running]: people only exist after the run's savePeople, so the tab loads them again when the run ends.
    val data by cachedProduceState("library", running) {
        withContext(Dispatchers.IO) {
            val faces = dao.faceVecs()
            val byId = faces.associateBy { it.id }
            val byPerson = faces.filter { it.personId != null }.groupBy { it.personId!! }
            val people = dao.people().filter { !it.hidden }.mapNotNull { p -> byId[p.mainFaceId]?.let { m -> Person(byPerson[p.id] ?: listOf(m), m, p.id, p.name) } }
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
                total = libraryPhotos(context).size,
            )
        }
    }
    LibraryContent(
        data = data,
        indexing = running,
        waiting = !running && work.any { it.state == WorkInfo.State.ENQUEUED } && waitsForCharger(context),
        indexed = indexed.values.sum(),
        startedAt = IndexProgress.startedAt(context),
        base = IndexProgress.base(context),
        paused = !running && IndexProgress.paused(context),
        onPauseIndex = { IndexControl.pause(context) },
        onResumeIndex = { IndexControl.resume(context) },
        onOpenPeople = onOpenPeople,
        onOpenPerson = onOpenPerson,
        onOpenAlbums = onOpenAlbums,
        onOpenAlbum = onOpenAlbum,
        onOpenFavorites = onOpenFavorites,
        onOpenVideos = onOpenVideos,
        onOpenTrash = onOpenTrash,
        onHelpRecognize = onHelpRecognize,
        onOpenSettings = onOpenSettings,
        onDev = onDev,
    )
}

/** Library tab: people, index progress, review, albums, places, shortcuts. Layout per docs/design.html. */
@Composable
fun LibraryContent(
    data: LibraryData?,
    indexing: Boolean = false,
    waiting: Boolean = false,
    indexed: Int = 0,
    startedAt: Long = 0L,
    base: Int = 0,
    paused: Boolean = false,
    onPauseIndex: () -> Unit = {},
    onResumeIndex: () -> Unit = {},
    onOpenPeople: () -> Unit = {},
    onOpenPerson: (Long) -> Unit = {},
    onOpenAlbums: () -> Unit = {},
    onOpenAlbum: (Long) -> Unit = {},
    onOpenFavorites: () -> Unit = {},
    onOpenVideos: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onHelpRecognize: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onDev: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Library", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onOpenSettings) { Icon(painterResource(R.drawable.ic_settings), "Settings") }
        }
        SearchBar()
        SectionHead("People", "See all", onOpenPeople)
        PeopleRow(data?.faces, onOpenPerson, onOpenPeople)
        IndexProgressRow(
            indexing = indexing, waiting = waiting, indexed = indexed, total = data?.total ?: 0,
            startedAt = startedAt, base = base, paused = paused,
            onPause = onPauseIndex, onResume = onResumeIndex,
        )
        // still loading: an invisible card holds its space, so Albums does not jump down when it arrives
        if (data == null) Box(Modifier.alpha(0f)) { ReviewCard(emptyList(), {}) }
        else if (data.unsure.isNotEmpty()) ReviewCard(data.unsure, onHelpRecognize)

        SectionHead("Albums", "See all", onOpenAlbums)
        AlbumRows(data?.albums, onOpenAlbum)
        SectionHead("Places", "See all")
        PlaceholderGrid(3) { CoverItem(null, "Place", null) }

        Shortcuts(onOpenFavorites, onOpenVideos, onOpenTrash, data?.favorites ?: 0, data?.videos ?: 0, data?.trash ?: 0)
        TextButton(onDev, Modifier.padding(start = 8.dp, bottom = 12.dp)) { Text("Dev tools") }
    }
}

/** Rounded search field from the mockup. Search is not built yet. */
@Composable
fun SearchBar(placeholder: String = "Search people, places, dates") {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp).fillMaxWidth()
            .height(52.dp).clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(placeholder, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "People" / "See all" header row. */
@Composable
fun SectionHead(title: String, action: String? = null, onAction: (() -> Unit)? = null, hint: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontSize = 18.sp)
        if (action != null) Row(
            Modifier.clip(RoundedCornerShape(18.dp)).clickable(enabled = onAction != null) { onAction?.invoke() }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(action, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
            Icon(painterResource(R.drawable.ic_chevron), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        if (hint != null) Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * "Finding faces" line, bar and Pause / Resume. Shows nothing when no run is active.
 * Shared by Library and People so the two stay in step.
 */
@Composable
fun IndexProgressRow(
    indexing: Boolean,
    waiting: Boolean,
    indexed: Int,
    total: Int,
    startedAt: Long,
    base: Int,
    paused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!indexing && !waiting && !paused) return
    // time left from this run only: photos already indexed before the run (base) do not count
    val eta = if (indexing) etaMillis(startedAt, (indexed - base).coerceAtLeast(0), (total - base).coerceAtLeast(0)) else null
    Column(modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
        Text(
            when {
                waiting -> "Waiting for charger · $indexed of $total photos"
                !indexing -> "Indexing paused · $indexed of $total photos"
                total <= 0 -> "Finding faces · $indexed photos"
                eta != null -> "Finding faces · $indexed of $total photos · ${formatEta(eta)}"
                else -> "Finding faces · $indexed of $total photos"
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, // a second line mid-run would push everything below down
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (total > 0) LinearProgressIndicator(
                { (indexed.toFloat() / total).coerceIn(0f, 1f) }, Modifier.testTag("indexBar").weight(1f).height(4.dp),
                color = MaterialTheme.colorScheme.primary,
            ) else LinearProgressIndicator(Modifier.testTag("indexBar").weight(1f).height(4.dp), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            // fixed width: Resume is wider than Pause, and the bar would change length on each tap
            Box(Modifier.testTag("indexAction").widthIn(min = 88.dp), contentAlignment = Alignment.CenterEnd) {
                if (indexing || waiting) PillButton("Pause", onPause) else PillButton("Resume", onResume)
            }
        }
    }
}

/** Small accent pill button, like the mockup's Review / Pause. */
@Composable
fun PillButton(text: String, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        // 18 sp line + 9 dp padding = the mockup's 36 dp; the default 24 sp line made it 42
        color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp,
    )
}

/** Horizontal row of up to 12 people, 72 dp faces, then a See all tile. Tap opens the person; naming happens on the person screen. */
@Composable
fun PeopleRow(faces: List<Person>?, onOpen: (Long) -> Unit, onSeeAll: () -> Unit) {
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
            Column(Modifier.width(72.dp).clickable { onOpen(p.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                FaceImage(p.main, Modifier.size(72.dp).clip(CircleShape))
                Spacer(Modifier.height(6.dp))
                // empty line for unnamed people too: the row's height follows the visible items, so it jumped while scrolling
                Text(p.name.orEmpty(), fontSize = 13.sp, minLines = 1, maxLines = 1)
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
                Text("No people yet", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        // 36 dp faces, each 12 dp over the last; the ring sits outside the photo, so no photo edge shows around it
        Row(horizontalArrangement = Arrangement.spacedBy((-12).dp)) {
            unsure.take(3).forEach { f ->
                FaceImage(f, Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape).padding(2.dp).clip(CircleShape))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(note ?: "${unsure.size} face${if (unsure.size == 1) "" else "s"} to check", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        if (a == null) CoverItem(null, "", "")
                        else CoverItem(a.tiles.first(), a.name, "${a.tiles.size}", Modifier.clickable { onOpen(a.id) })
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 3-column grid of placeholder covers, until the Places screen exists. */
@Composable
fun PlaceholderGrid(columns: Int, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(columns) {
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

/** Rounded square cover, name and count. Grey when [cover] is null. */
@Composable
fun CoverItem(cover: Tile?, label: String, note: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        val square = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
        // no video badge on a cover
        if (cover != null) Thumb(cover.copy(duration = null), square)
        else Box(square.background(MaterialTheme.colorScheme.surfaceVariant))
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        note?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 1, maxLines = 1) }
    }
}

/** Favorites, Videos, Locked folder, Trash. */
@Composable
fun Shortcuts(onOpenFavorites: () -> Unit, onOpenVideos: () -> Unit, onOpenTrash: () -> Unit, favorites: Int, videos: Int, trash: Int) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Shortcut(R.drawable.ic_favorite, "Favorites", favorites.toString(), Modifier.weight(1f), onOpenFavorites)
            Shortcut(R.drawable.ic_video, "Videos", videos.toString(), Modifier.weight(1f), onOpenVideos)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Shortcut(R.drawable.ic_lock, "Locked folder", "Private", Modifier.weight(1f)) {}
            Shortcut(R.drawable.ic_trash, "Trash", if (trash == 0) "Empty" else trash.toString(), Modifier.weight(1f), onOpenTrash)
        }
    }
}
