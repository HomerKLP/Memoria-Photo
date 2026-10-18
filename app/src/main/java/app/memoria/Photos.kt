package app.memoria

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.min

/** A photo or video in the timeline. duration is set for videos only. favorite is MediaStore's own flag. */
data class Tile(val id: Long, val uri: Uri, val date: Long, val duration: Long?, val favorite: Boolean = false)

class Header(val key: String, val text: String, val month: Boolean)

private val nameDate = Regex("(?<!\\d)((?:19|20)\\d{6})(?:[_-](\\d{6}))?(?!\\d)")
private val nameFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

/** No EXIF date: the date in the file name (IMG_20250901_003222, IMG-20250308-WA0005), else the older of added and modified. */
fun fallbackDate(name: String, added: Long, modified: Long, zone: ZoneId): Long {
    val file = min(added, modified) * 1000
    val m = nameDate.find(name) ?: return file
    return runCatching { LocalDateTime.parse(m.groupValues[1] + m.groupValues[2].ifEmpty { "000000" }, nameFormat).atZone(zone).toInstant().toEpochMilli() }
        .getOrDefault(file)
}

/**
 * All photos and videos, newest first, with month and day headers between them.
 * favoritesOnly keeps MediaStore's own favorite flag (`QUERY_ARG_MATCH_FAVORITE`), so nothing is copied.
 */
fun timeline(context: Context, favoritesOnly: Boolean = false): List<Any> {
    val zone = ZoneId.systemDefault()
    val tiles = mutableListOf<Tile>()
    val cols = arrayOf(FileColumns._ID, FileColumns.MEDIA_TYPE, FileColumns.DATE_TAKEN, FileColumns.DATE_ADDED, FileColumns.DURATION, FileColumns.DATE_MODIFIED, FileColumns.DISPLAY_NAME, FileColumns.IS_FAVORITE)
    val where = "${FileColumns.MEDIA_TYPE} IN (${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO})"
    val args = Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where)
        if (favoritesOnly) putInt(MediaStore.QUERY_ARG_MATCH_FAVORITE, MediaStore.MATCH_ONLY)
    }
    context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), cols, args, null)?.use { c ->
        while (c.moveToNext()) {
            val video = c.getInt(1) == FileColumns.MEDIA_TYPE_VIDEO
            val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val date = if (c.isNull(2)) fallbackDate(c.getString(6), c.getLong(3), c.getLong(5), zone) else c.getLong(2)
            tiles += Tile(c.getLong(0), ContentUris.withAppendedId(base, c.getLong(0)), date, if (video) c.getLong(4) else null, c.getInt(7) != 0)
        }
    }
    val today = LocalDate.now()
    val monthName = DateTimeFormatter.ofPattern("LLLL yyyy")
    val dayName = DateTimeFormatter.ofPattern("EEE, d MMM")
    val out = mutableListOf<Any>()
    var month: YearMonth? = null
    var day: LocalDate? = null
    for (t in tiles.sortedByDescending { it.date }) {
        val d = Instant.ofEpochMilli(t.date).atZone(zone).toLocalDate()
        if (YearMonth.from(d) != month) {
            month = YearMonth.from(d)
            out += Header("m$month", monthName.format(d), true)
        }
        if (d != day) {
            day = d
            out += Header("d$d", if (d == today) "Today" else if (d == today.minusDays(1)) "Yesterday" else dayName.format(d), false)
        }
        out += t
    }
    return out
}

/** A trashed photo or video, and the days it has left before Android deletes it for good. */
class Trashed(val tile: Tile, val daysLeft: Int)

/** Everything in the phone's own trash, the fewest days left last. Our app never keeps its own trash. */
fun trash(context: Context): List<Trashed> {
    val cols = arrayOf(FileColumns._ID, FileColumns.MEDIA_TYPE, FileColumns.DURATION, FileColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_EXPIRES)
    val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
    val out = mutableListOf<Trashed>()
    val now = System.currentTimeMillis()
    context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), cols, args, null)?.use { c ->
        while (c.moveToNext()) {
            val video = c.getInt(1) == FileColumns.MEDIA_TYPE_VIDEO
            val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val id = c.getLong(0)
            // DATE_EXPIRES is in seconds, every other MediaStore date is in milliseconds
            val daysLeft = if (c.isNull(4)) 0 else ((c.getLong(4) * 1000 - now).coerceAtLeast(0) / 86_400_000L).toInt()
            out += Trashed(Tile(id, ContentUris.withAppendedId(base, id), c.getLong(3), if (video) c.getLong(2) else null), daysLeft)
        }
    }
    return out.sortedByDescending { it.daysLeft }
}

/** The given MediaStore ids as photos, newest first, with the same date rules as the timeline. Ids MediaStore no longer has are dropped. */
fun photosByIds(context: Context, ids: List<Long>): List<Tile> {
    if (ids.isEmpty()) return emptyList()
    val zone = ZoneId.systemDefault()
    val out = mutableListOf<Tile>()
    val cols = arrayOf(FileColumns._ID, FileColumns.DATE_TAKEN, FileColumns.DATE_ADDED, FileColumns.DURATION, FileColumns.DATE_MODIFIED, FileColumns.DISPLAY_NAME, FileColumns.IS_FAVORITE)
    val where = "${FileColumns._ID} IN (${ids.joinToString(",")}) AND ${FileColumns.MEDIA_TYPE} = ${FileColumns.MEDIA_TYPE_IMAGE}"
    val args = Bundle().apply { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where) }
    context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), cols, args, null)?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            val date = if (c.isNull(1)) fallbackDate(c.getString(5), c.getLong(2), c.getLong(4), zone) else c.getLong(1)
            out += Tile(id, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), date, if (c.isNull(3)) null else c.getLong(3), c.getInt(6) != 0)
        }
    }
    return out.sortedByDescending { it.date }
}

// Shown at once when the tab opens again, then replaced by a fresh query
var lastTimeline: List<Any>? = null
var lastFavorites: List<Any>? = null

/** Replaces one tile in the cached timelines after a change, so the grid and viewer see it on the next open. */
fun updateTile(tile: Tile) {
    lastTimeline = lastTimeline?.map { if (it is Tile && it.id == tile.id) tile else it }
    lastFavorites = lastFavorites?.map { if (it is Tile && it.id == tile.id) tile else it }
}

/** Drops a trashed tile from the cached timelines, so the grid does not flash it before the reload. */
fun forgetTile(id: Long) {
    lastTimeline = lastTimeline?.filterNot { it is Tile && it.id == id }
    lastFavorites = lastFavorites?.filterNot { it is Tile && it.id == id }
}

/**
 * Writes MediaStore's own favorite flag. Throws SecurityException for photos the app does not own:
 * the caller then asks the user once through MediaStore.createFavoriteRequest.
 */
fun setFavorite(context: Context, tile: Tile, favorite: Boolean) {
    context.contentResolver.update(tile.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_FAVORITE, if (favorite) 1 else 0) }, null, null)
}

val thumbs = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
    override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
}

/**
 * The Photos tab's select mode (mockup "Photos, selecting"): the picked tiles, live so the app can swap
 * the bottom bar and act on the whole set. Long press starts it, a tap toggles, a drag adds the tiles it
 * passes, a day's circle picks the whole day, Back or the X leaves. Purely state, so it is unit-testable.
 */
@Stable
class PhotoSelection {
    var tiles by mutableStateOf<Map<Long, Tile>>(emptyMap())
        private set

    val ids: Set<Long> get() = tiles.keys
    val active: Boolean get() = tiles.isNotEmpty()
    /** True when there is a selection and every picked tile is already a favorite. */
    val allFavorite: Boolean get() = tiles.isNotEmpty() && tiles.values.all { it.favorite }

    fun start(t: Tile) { tiles = mapOf(t.id to t) }
    fun toggle(t: Tile) { tiles = if (t.id in tiles) tiles - t.id else tiles + (t.id to t) }
    fun add(t: Tile) { if (t.id !in tiles) tiles = tiles + (t.id to t) }
    fun addAll(ts: Collection<Tile>) { tiles = tiles + ts.associateBy { it.id } }
    fun remove(ids: Collection<Long>) { tiles = tiles - ids.toSet() }
    fun clear() { tiles = emptyMap() }
}

/**
 * Select mode on a grid without tabs (Album, Favorites, Place, Person, Videos). Plain values, so tests
 * build one by hand; [rememberPick] makes the real one. Screens reload when [changes] goes up.
 */
class Pick(
    val ids: Set<Long>?, // null: not selecting; empty: More > Select with nothing picked yet
    val size: Long = 0L,
    val changes: Int = 0,
    val set: (Set<Long>?) -> Unit = {},
    val share: (List<Tile>) -> Unit = {},
    val favorite: (List<Tile>) -> Unit = {},
    val delete: (List<Tile>) -> Unit = {},
    val confirm: (PendingIntent, () -> Unit) -> Unit = { _, _ -> },
    val done: () -> Unit = {},
)

@Composable
fun rememberPick(): Pick {
    val context = LocalContext.current
    var ids by remember { mutableStateOf<Set<Long>?>(null) }
    var changes by remember { mutableIntStateOf(0) }
    val size by produceState(0L, ids) { value = withContext(Dispatchers.IO) { selectedSize(context, ids.orEmpty()) } }
    // Android's confirm for photos another app owns: [then] runs on OK
    var then by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) { then?.invoke(); ids = null }
        then = null
        changes++ // our own photos changed before the confirm, even on Cancel
    }
    fun confirm(request: PendingIntent, onOk: () -> Unit) { then = onOk; ask.launch(IntentSenderRequest.Builder(request.intentSender).build()) }
    fun done() { ids = null; changes++ }
    return Pick(
        ids, size, changes, set = { ids = it },
        share = { picked ->
            if (picked.isNotEmpty()) {
                val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(picked.map { it.uri }))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, null))
            }
        },
        favorite = { picked ->
            if (picked.isNotEmpty()) {
                val fav = !picked.all { it.favorite }
                val foreign = picked.filter { t -> runCatching { setFavorite(context, t, fav) }.onSuccess { updateTile(t.copy(favorite = fav)) }.isFailure }
                if (foreign.isEmpty()) done()
                else confirm(MediaStore.createFavoriteRequest(context.contentResolver, foreign.map { it.uri }, fav)) { foreign.forEach { updateTile(it.copy(favorite = fav)) } }
            }
        },
        delete = { picked ->
            if (picked.isNotEmpty()) confirm(MediaStore.createTrashRequest(context.contentResolver, picked.map { it.uri }, true)) { picked.forEach { forgetTile(it.id) } }
        },
        confirm = ::confirm, done = ::done,
    )
}

/** Total bytes of the given MediaStore ids, for the select top bar's "18.4 MB" (one small query). */
fun selectedSize(context: Context, ids: Collection<Long>): Long {
    if (ids.isEmpty()) return 0L
    var total = 0L
    val args = Bundle().apply { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${FileColumns._ID} IN (${ids.joinToString(",")})") }
    context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), arrayOf(MediaStore.MediaColumns.SIZE), args, null)?.use { c ->
        while (c.moveToNext()) if (!c.isNull(0)) total += c.getLong(0)
    }
    return total
}

/** "18.4 MB" for the select top bar (mockup). SI units (1000), like Android's own file sizes. One decimal above KB. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var unit = -1
    while (v >= 1000 && unit < units.lastIndex) { v /= 1000; unit++ }
    return "%.1f %s".format(v, units[unit])
}

@Composable
fun PhotosScreen(
    favoritesOnly: Boolean = false,
    onBack: (() -> Unit)? = null,
    onOpen: (List<Tile>, Tile) -> Unit,
    grid: LazyGridState = rememberLazyGridState(),
    selection: PhotoSelection? = null,
    reload: Int = 0,
    onSearch: () -> Unit = {},
) {
    val context = LocalContext.current
    val media = arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    val perms = media + Manifest.permission.ACCESS_MEDIA_LOCATION // photo GPS, optional
    fun has(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(media.all(::has)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = media.all(::has) }
    LaunchedEffect(Unit) { if (!perms.all(::has)) ask.launch(perms) }
    val pick = rememberPick() // Favorites only, the timeline has its own select mode
    val items by produceState(if (favoritesOnly) lastFavorites else lastTimeline, granted, reload, pick.changes) {
        if (granted) value = withContext(Dispatchers.IO) { timeline(context, favoritesOnly) }
            .also { if (favoritesOnly) lastFavorites = it else lastTimeline = it }
    }
    ReportDrawnWhen { items != null } // "fully drawn" for startup timing (bench/perf.py)
    if (favoritesOnly) {
        FavoritesContent(
            favorites = items?.let(::groupFavorites),
            granted = granted,
            onAsk = { ask.launch(perms) },
            onBack = onBack ?: {},
            onOpen = onOpen,
            pick = pick,
        )
        return
    }
    // total bytes of the picked tiles, for the select top bar
    val pickedSize by produceState(0L, selection?.ids) {
        value = selection?.ids?.takeIf { it.isNotEmpty() }?.let { withContext(Dispatchers.IO) { selectedSize(context, it) } } ?: 0L
    }
    PhotosContent(
        items = items,
        granted = granted,
        onAsk = { ask.launch(perms) },
        onBack = onBack,
        onOpen = onOpen,
        grid = grid,
        selection = selection,
        pickedSize = pickedSize,
        onSearch = onSearch,
    )
}

/** Every favorite by month, the month pinned, no day headings and no hearts (mockup "Favorites"). */
class Favorites(val months: Map<String, List<Tile>>, val all: List<Tile>)

/** "34 photos · 4 videos", dropping a zero part. */
private fun favoritesSubtitle(f: Favorites): String = listOfNotNull(
    f.all.count { it.duration == null }.takeIf { it > 0 }?.let { "$it photos" },
    f.all.count { it.duration != null }.takeIf { it > 0 }?.let { "$it videos" },
).joinToString(" · ")

/** Favorites grouped by month name, newest first. Comes from the timeline so the cache is shared. */
fun groupFavorites(items: List<Any>): Favorites {
    val tiles = items.filterIsInstance<Tile>()
    val month = DateTimeFormatter.ofPattern("LLLL yyyy")
    val zone = ZoneId.systemDefault()
    val months = linkedMapOf<String, MutableList<Tile>>()
    for (t in tiles) months.getOrPut(month.format(Instant.ofEpochMilli(t.date).atZone(zone))) { mutableListOf() } += t
    return Favorites(months, tiles)
}

/**
 * Favorites layout per docs/design.html "Favorites": like Videos, by month, the month pinned, no day
 * headings and no hearts. No MediaStore, so previews and tests render it.
 */
@Composable
fun FavoritesContent(
    favorites: Favorites?,
    granted: Boolean = true,
    onAsk: () -> Unit = {},
    onBack: () -> Unit = {},
    onOpen: (List<Tile>, Tile) -> Unit = { _, _ -> },
    pick: Pick = Pick(null),
) {
    val f = favorites
    val sel = pick.ids
    if (sel != null) BackHandler { pick.set(null) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (sel != null) TopBar(
            "${sel.size} selected", "Favorites · ${formatBytes(pick.size)}",
            onBack = { pick.set(null) }, backIcon = R.drawable.ic_close, backLabel = "Cancel",
        ) {
            if (f != null && sel.size < f.all.size) TextButton({ pick.set(f.all.map { it.id }.toSet()) }) { Text("Select all") }
        }
        else TopBar("Favorites", f?.let(::favoritesSubtitle), onBack = onBack)
        if (!granted) {
            // ponytail: after two denials Android stops asking, then the user has to allow it in system settings
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Memoria needs access to your photos and videos.")
                Button(onAsk, Modifier.padding(top = 12.dp)) { Text("Allow access") }
            }
            return@Column
        }
        if (f != null && f.all.isEmpty()) {
            Text("No favorites yet. Open a photo and tap the heart.", Modifier.padding(horizontal = 20.dp))
            return@Column
        }
        LazyVerticalGrid(
            GridCells.Fixed(3), Modifier.weight(1f).testTag("favoritesGrid"),
            horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            f?.months?.forEach { (month, tiles) ->
                stickyHeader(key = month) {
                    // background: the pinned month covers the tiles passing under it
                    Text(
                        month, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 16.dp, top = 20.dp, bottom = 10.dp),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                items(tiles, key = { it.id }) { t ->
                    val on = sel != null && t.id in sel
                    TileImage(
                        t,
                        Modifier.combinedClickable(
                            onClick = { if (sel == null) onOpen(f.all, t) else pick.set(if (on) sel - t.id else sel + t.id) },
                            onLongClick = { pick.set(sel.orEmpty() + t.id) },
                        ).testTag("tile-${t.id}"),
                        selecting = sel != null, selected = on, heart = false,
                    )
                }
            }
        }
        // everything here is a favorite, so the middle action always reads Unfavorite
        if (sel != null) {
            val picked = f?.all.orEmpty().filter { it.id in sel }
            SelectBar(true, { pick.share(picked) }, { pick.favorite(picked) }, { pick.delete(picked) })
        }
    }
}

/** Photos layout per docs/design.html: the timeline, the months slider and the select mode. No MediaStore, so previews and tests render it. */
@Composable
fun PhotosContent(
    items: List<Any>?,
    granted: Boolean = true,
    onAsk: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    onOpen: (List<Tile>, Tile) -> Unit = { _, _ -> },
    grid: LazyGridState = rememberLazyGridState(),
    selection: PhotoSelection? = null,
    pickedSize: Long = 0L,
    onSearch: () -> Unit = {},
    // The New album picker opens already in select mode (empty pick), under its own title and bar:
    forceSelecting: Boolean? = null,
    titleWhileSelecting: String? = null,
    scrollWhileSelecting: Boolean = false,
    onSelectBack: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val list = items.orEmpty()
    val photos = list.filterIsInstance<Tile>()
    val tabs = selection != null // the main tab supports select mode; Favorites passes none
    val selecting = forceSelecting ?: (selection?.active == true)
    val selectedIds = selection?.ids.orEmpty()
    // One entry per month header: the grid item a month starts at, and its name for the slider
    val months = remember(items) { list.mapIndexedNotNull { i, x -> if (x is Header && x.month) i to x.text else null } }
    val monthStarts = months.map { it.first }
    val monthNames = months.map { it.second }
    val month by remember(grid, monthStarts) {
        derivedStateOf { monthStarts.indexOfLast { it <= grid.firstVisibleItemIndex }.coerceAtLeast(0) }
    }
    val scope = rememberCoroutineScope()
    var sliderOn by remember { mutableStateOf(false) } // fades in while scrolling, out two seconds later
    var sliderTick by remember { mutableIntStateOf(0) } // bumped on scroll activity, restarts the hide timer
    var dragMonth by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(grid) {
        snapshotFlow { grid.firstVisibleItemIndex to grid.isScrollInProgress }.collect { (index, scrolling) ->
            if (dragMonth != null) return@collect
            if (index == 0 && !scrolling) sliderOn = false // hidden at the top, where there is nothing to jump to
            else if (scrolling || index > 0) { sliderOn = true; sliderTick++ }
        }
    }
    LaunchedEffect(sliderTick) {
        if (sliderTick > 0) { delay(2000); if (dragMonth == null) sliderOn = false }
    }
    // a day header's key -> its tiles, for the day circle
    val dayTiles = remember(items) {
        val map = linkedMapOf<String, MutableList<Tile>>()
        var current: String? = null
        for (x in list) when (x) {
            is Header -> if (!x.month) { current = x.key; map.getOrPut(x.key) { mutableListOf() } }
            is Tile -> current?.let { map[it]?.add(x) }
        }
        map
    }
    val tilesById = remember(photos) { photos.associateBy { it.id } }
    if (selecting) BackHandler { onSelectBack?.invoke() ?: selection?.clear() }
    Column {
        // The wordmark and the select top bar share one Box: the select bar is drawn over the wordmark,
        // so swapping them never changes the height and the grid under it never moves.
        Box(Modifier.fillMaxWidth()) {
            if (onBack != null) Row(Modifier.padding(start = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(painterResource(R.drawable.ic_back), "Back") }
                Text("Favorites", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleLarge)
            } else Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(buildAnnotatedString {
                    append("Memoria ")
                    withStyle(SpanStyle(MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)) { append("Photo") }
                }, Modifier.weight(1f).padding(start = 20.dp, top = 18.dp, bottom = 18.dp), style = MaterialTheme.typography.titleLarge)
                IconButton(onSearch) { Icon(painterResource(R.drawable.ic_search), "Search") }
            }
            if (selecting) Row(
                Modifier.matchParentSize().background(MaterialTheme.colorScheme.surface).padding(start = 4.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton({ onSelectBack?.invoke() ?: selection?.clear() }) { Icon(painterResource(R.drawable.ic_close), "Cancel") }
                Column(Modifier.weight(1f)) {
                    // The picker shows "Add to <name>" with the count under it; the tab shows "N selected"
                    Text(titleWhileSelecting ?: "${selectedIds.size} selected", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (titleWhileSelecting != null) "${selectedIds.size} selected · ${formatBytes(pickedSize)}" else formatBytes(pickedSize),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (!granted) {
            // ponytail: after two denials Android stops asking, then the user has to allow it in system settings
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Memoria needs access to your photos and videos.")
                Button(onAsk, Modifier.padding(top = 12.dp)) { Text("Allow access") }
            }
            return@Column
        }
        MonthSlider(
            months = monthNames,
            current = month,
            dragging = dragMonth,
            visible = sliderOn,
            onDragging = { dragging ->
                if (dragging) sliderOn = true
                else {
                    dragMonth = null
                    // a drag that ended at the first month hides it, like a scroll that reached the top
                    if (grid.firstVisibleItemIndex > 0) { sliderOn = true; sliderTick++ } else sliderOn = false
                }
            },
            onJump = { i ->
                dragMonth = i
                scope.launch { grid.scrollToItem(monthStarts.getOrElse(i) { 0 }) }
            },
            // always on for the timeline (Favorites has its own screen and no slider)
            enabled = !selecting,
            modifier = Modifier.weight(1f),
        ) {
            LazyVerticalGrid(
                GridCells.Fixed(4), state = grid,
                modifier = (if (tabs) Modifier.selectDrag(grid, tilesById, selection!!) else Modifier).testTag("photosGrid"),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                userScrollEnabled = !selecting || scrollWhileSelecting,
            ) {
                for (x in list) when (x) {
                    is Header -> if (x.month) {
                        // pinned while its month is on screen, background so photos scroll under it (mockup "Sticky month header")
                        stickyHeader(key = x.key) {
                            Text(x.text, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 16.dp, top = 20.dp, bottom = 2.dp), style = MaterialTheme.typography.titleLarge)
                        }
                    } else item(key = x.key, span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                        val day = dayTiles[x.key].orEmpty()
                        val all = selecting && day.isNotEmpty() && day.all { it.id in selectedIds }
                        // the circle is an overlay: the header keeps its height, so the photos under it never move
                        Box(Modifier.fillMaxWidth().testTag("dayHeader")) {
                            Text(x.text, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.titleSmall)
                            if (selecting) DayCircle(all, Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) {
                                if (all) selection?.remove(day.map { it.id }) else selection?.addAll(day)
                            }
                        }
                    }
                    is Tile -> item(key = x.id, contentType = "tile") {
                        TileImage(
                            x,
                            (if (tabs) Modifier.combinedClickable(
                                onClick = { if (selecting) selection?.toggle(x) else onOpen(photos, x) },
                                onLongClick = { selection?.start(x) },
                            ) else Modifier.clickable { onOpen(photos, x) }).testTag("tile-${x.id}"),
                            selecting = selecting,
                            selected = x.id in selectedIds,
                        )
                    }
                }
            }
        }
        footer?.invoke()
    }
}

/** The day's select circle (mockup "Photos, selecting"): an outline, filled with a check when the whole day is picked. */
@Composable
private fun DayCircle(on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(22.dp).clip(CircleShape)
            .then(
                if (on) Modifier.background(MaterialTheme.colorScheme.primary)
                else Modifier.border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { if (on) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp), tint = Color.White) }
}

/**
 * Drag-select for the timeline (mockup "Photos, selecting": drag adds the ones you pass). It reads the
 * live grid layout to find the tile under the finger, and never consumes while no selection is active,
 * so normal scrolling is untouched. The grid's own scroll is off while selecting (see PhotosContent).
 */
private fun Modifier.selectDrag(grid: LazyGridState, tilesById: Map<Long, Tile>, selection: PhotoSelection): Modifier =
    pointerInput(tilesById) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var own = false
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (!selection.active) continue
                if (!own) {
                    if ((change.position - down.position).getDistance() < viewConfiguration.touchSlop) continue
                    own = true
                }
                change.consume()
                val item = grid.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                    change.position.x >= info.offset.x && change.position.x < info.offset.x + info.size.width &&
                        change.position.y >= info.offset.y && change.position.y < info.offset.y + info.size.height
                }
                (item?.key as? Long)?.let { id -> tilesById[id]?.let { selection.add(it) } }
            }
        }
    }

@Composable
fun TileImage(tile: Tile, modifier: Modifier, selecting: Boolean = false, selected: Boolean = false, heart: Boolean = true) {
    Box(
        modifier.aspectRatio(1f)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier),
    ) {
        Thumb(tile, Modifier.fillMaxSize())
        if (selecting) {
            // the circle covers the heart while selecting (mockup "Tile")
            Box(
                Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).clip(CircleShape)
                    .then(
                        if (selected) Modifier.background(MaterialTheme.colorScheme.primary)
                        else Modifier.border(2.dp, Color.White, CircleShape),
                    ),
                contentAlignment = Alignment.Center,
            ) { if (selected) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp), tint = Color.White) }
        } else if (heart && tile.favorite) {
            Box(
                Modifier.align(Alignment.TopStart).padding(4.dp).size(16.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(R.drawable.ic_favorite), "Favorite", Modifier.size(10.dp), tint = Color.White) }
        }
    }
}

// At most 4 thumbnail loads at once, so a fling can't bury the tiles on screen.
private val thumbLoads = Dispatchers.IO.limitedParallelism(4)

/** Thumbnail picture with the video length badge. Callers stack their own overlay on top. */
@Composable
fun Thumb(tile: Tile, modifier: Modifier) {
    val context = LocalContext.current
    val image by produceState(thumbs.get(tile.id), tile.id) {
        if (value != null) return@produceState
        val signal = CancellationSignal()
        launch(thumbLoads) {
            val thumb = runCatching { context.contentResolver.loadThumbnail(tile.uri, Size(300, 300), signal) }.getOrNull()
            // old camera photos: MediaStore hands back the 160 px EXIF preview, blurry in a tile, so decode the photo itself
            val small = thumb != null && tile.duration == null && min(thumb.width, thumb.height) < 200
            value = (if (small) runCatching { loadPhoto(ImageDecoder.createSource(context.contentResolver, tile.uri), 480) }.getOrNull() ?: thumb else thumb)
                ?.also { thumbs.put(tile.id, it) }
        }
        // The tile left the screen: stop its load
        awaitDispose { signal.cancel() }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        image?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        tile.duration?.let { ms ->
            val s = ms / 1000
            val shadow = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium, shadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 1f), 2f))
            Row(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("%d:%02d".format(s / 60, s % 60), style = shadow)
                Icon(painterResource(R.drawable.ic_play), null, Modifier.size(14.dp), tint = Color.White)
            }
        }
    }
}
