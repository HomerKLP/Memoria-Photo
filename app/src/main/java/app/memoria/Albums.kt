package app.memoria

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A device folder (MediaStore bucket) with its photos and videos, newest first. */
class Album(val id: Long, val name: String, val tiles: List<Tile>)

/** Device folders, biggest first. [bucket] keeps only that folder. Same date rules as the timeline. */
fun albums(context: Context, bucket: Long? = null): List<Album> {
    val zone = ZoneId.systemDefault()
    val rows = mutableListOf<Triple<Long, String, Tile>>()
    val cols = arrayOf(
        FileColumns._ID, FileColumns.MEDIA_TYPE, FileColumns.DATE_TAKEN, FileColumns.DATE_ADDED, FileColumns.DURATION, FileColumns.DATE_MODIFIED,
        FileColumns.DISPLAY_NAME, FileColumns.IS_FAVORITE, FileColumns.BUCKET_ID, FileColumns.BUCKET_DISPLAY_NAME,
    )
    var where = "${FileColumns.MEDIA_TYPE} IN (${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO})"
    if (bucket != null) where += " AND ${FileColumns.BUCKET_ID} = $bucket"
    val args = Bundle().apply { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where) }
    context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), cols, args, null)?.use { c ->
        while (c.moveToNext()) {
            val video = c.getInt(1) == FileColumns.MEDIA_TYPE_VIDEO
            val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val date = if (c.isNull(2)) fallbackDate(c.getString(6), c.getLong(3), c.getLong(5), zone) else c.getLong(2)
            val tile = Tile(c.getLong(0), ContentUris.withAppendedId(base, c.getLong(0)), date, if (video) c.getLong(4) else null, c.getInt(7) != 0)
            rows += Triple(c.getLong(8), c.getString(9) ?: "Other", tile)
        }
    }
    return rows.groupBy { it.first }
        .map { (id, r) -> Album(id, r[0].second, r.map { it.third }.sortedByDescending { it.date }) }
        .sortedByDescending { it.tiles.size }
}

/** The device folder (bucket) a photo or video sits in, with its display name, for the viewer's More. */
class TileAlbum(val id: Long, val name: String)

fun albumOf(context: Context, id: Long): TileAlbum? {
    val cols = arrayOf(FileColumns.BUCKET_ID, FileColumns.BUCKET_DISPLAY_NAME)
    val args = Bundle().apply { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${FileColumns._ID} = $id") }
    context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), cols, args, null)?.use { c ->
        if (c.moveToFirst()) return TileAlbum(c.getLong(0), c.getString(1) ?: "Other")
    }
    return null
}

/** An album a photo can be moved to: its name, its MediaStore RELATIVE_PATH, a cover and a count. */
class MoveTarget(val name: String, val path: String, val cover: Tile?, val count: Int)

/**
 * The folders "Move to album" can offer: only DCIM and Pictures, where Android lets a move land, biggest
 * first, without [except] (the photo's own album, since moving there would do nothing). The path is the
 * folder's RELATIVE_PATH, which the move writes back.
 */
fun moveTargets(context: Context, except: Long): List<MoveTarget> {
    val zone = ZoneId.systemDefault()
    val cols = arrayOf(
        FileColumns._ID, FileColumns.MEDIA_TYPE, FileColumns.DATE_TAKEN, FileColumns.DATE_ADDED, FileColumns.DURATION, FileColumns.DATE_MODIFIED,
        FileColumns.IS_FAVORITE, FileColumns.BUCKET_ID, FileColumns.BUCKET_DISPLAY_NAME, FileColumns.RELATIVE_PATH,
    )
    val where = "${FileColumns.MEDIA_TYPE} IN (${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO})" +
        " AND ${FileColumns.BUCKET_ID} != $except" +
        " AND (${FileColumns.RELATIVE_PATH} LIKE 'DCIM/%' OR ${FileColumns.RELATIVE_PATH} LIKE 'Pictures/%')"
    val args = Bundle().apply { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where) }
    val rows = mutableListOf<Triple<Long, Pair<String, String>, Tile>>()
    context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), cols, args, null)?.use { c ->
        while (c.moveToNext()) {
            val video = c.getInt(1) == FileColumns.MEDIA_TYPE_VIDEO
            val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val date = if (c.isNull(2)) fallbackDate(c.getString(9), c.getLong(3), c.getLong(5), zone) else c.getLong(2)
            val tile = Tile(c.getLong(0), ContentUris.withAppendedId(base, c.getLong(0)), date, if (video) c.getLong(4) else null, c.getInt(6) != 0)
            rows += Triple(c.getLong(7), (c.getString(8) ?: "Other") to (c.getString(9) ?: ""), tile)
        }
    }
    return rows.groupBy { it.first }
        .map { (_, r) -> MoveTarget(r[0].second.first, r[0].second.second, r.maxByOrNull { it.third.date }?.third, r.size) }
        .sortedByDescending { it.count }
}

/** Moves a photo or video into the folder at [path] (a MediaStore RELATIVE_PATH). Throws SecurityException when the file belongs to another app, so the caller asks Android for consent. */
fun movePhoto(context: Context, tile: Tile, path: String) {
    val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, path) }
    context.contentResolver.update(tile.uri, values, null, null)
}

/** The display names of the device's folders (buckets), for the New album "this name is taken" check. */
fun albumNames(context: Context): Set<String> {
    val names = mutableSetOf<String>()
    val cols = arrayOf(FileColumns.BUCKET_DISPLAY_NAME)
    val where = "${FileColumns.MEDIA_TYPE} IN (${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO})"
    val args = Bundle().apply { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where) }
    context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), cols, args, null)?.use { c ->
        while (c.moveToNext()) c.getString(0)?.let { names += it }
    }
    return names
}

/** All device folders as covers. Layout per docs/design.html. Split from the load so previews can pass fake data. */
@Composable
fun AlbumsContent(albums: List<Album>?, onBack: () -> Unit = {}, onOpenAlbum: (Long) -> Unit = {}, onNewAlbum: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopBar("Albums", albums?.let { a -> "${a.size} albums · ${a.sumOf { it.tiles.size }} items" }, onBack = onBack)
        SectionHead("On this device", hint = "Device folders")
        LazyVerticalGrid(
            GridCells.Fixed(3), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(albums.orEmpty(), key = { it.id }) { CoverTile(it.tiles.first(), it.name, "${it.tiles.size}", Modifier.animateItem(fadeInSpec = null).clickable { onOpenAlbum(it.id) }) }
            // the New album tile stays last, like the mockup, and does not depend on the load
            item(key = "newAlbum") { NewAlbumTile(Modifier.animateItem(fadeInSpec = null), onClick = onNewAlbum) }
        }
    }
}

/** New album tile on Albums (mockup `.place.new`): accent fill, a centered + in the space above the labels, name and "Pick photos" at the bottom. */
@Composable
fun NewAlbumTile(modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Box(modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = onClick)) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            // the + centers in the free space above the labels (mockup `svg.i { margin: auto }` in a flex-end column)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_add), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            }
            // lineHeight 1.4 like the mockup's body: a plain Text would take the ambient 24 sp line and push the two lines apart
            Text("New album", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Pick photos", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun AlbumsScreen(onBack: () -> Unit, onOpenAlbum: (Long) -> Unit, onNewAlbum: () -> Unit = {}) {
    val context = LocalContext.current
    val albums by cachedProduceState("albums") { withContext(Dispatchers.IO) { albums(context) } }
    AlbumsContent(albums, onBack, onOpenAlbum, onNewAlbum)
}

/**
 * One device folder: 3-column grid, tap opens the viewer. Split from the load for previews.
 * [selected] null: not selecting; empty: More > Select with nothing picked yet. Long press picks a
 * photo, the top bar swaps in place and the Select bar comes up under the grid (mockup "Album, selecting").
 */
@Composable
fun AlbumContent(
    album: Album?,
    onBack: () -> Unit = {},
    onOpen: (List<Tile>, Tile) -> Unit = { _, _ -> },
    selected: Set<Long>? = null,
    pickedSize: Long = 0L,
    ignored: Boolean = false,
    onSelect: (Set<Long>?) -> Unit = {},
    onIgnore: (Boolean) -> Unit = {},
    onShare: () -> Unit = {},
    onFavorite: () -> Unit = {},
    onMove: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val a = album
    val videos = a?.tiles?.count { it.duration != null } ?: 0
    val month = DateTimeFormatter.ofPattern("LLLL yyyy")
    val zone = ZoneId.systemDefault()
    val groups = remember(a?.tiles) {
        a?.tiles.orEmpty().groupBy { month.format(Instant.ofEpochMilli(it.date).atZone(zone)) }.entries.toList()
    }
    // one (grid item index, month name) per month heading, so the slider can jump to it
    val months = remember(groups) {
        var i = 0
        groups.map { (m, tiles) -> (i to m).also { i += 1 + tiles.size } }
    }
    var more by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    if (selected != null) BackHandler { onSelect(null) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (selected != null) TopBar(
                "${selected.size} selected", a?.let { "${it.name} · ${formatBytes(pickedSize)}" },
                onBack = { onSelect(null) }, backIcon = R.drawable.ic_close, backLabel = "Cancel",
            ) {
                if (a != null && selected.size < a.tiles.size) TextButton({ onSelect(a.tiles.map { it.id }.toSet()) }) { Text("Select all") }
            }
            // " " while loading: an empty title may measure to nothing and the grid would jump
            else TopBar(a?.name ?: " ", a?.let { "${it.tiles.size - videos} photos" + if (videos > 0) " · $videos videos" else "" }, onBack = onBack) {
                IconButton({ more = true }) { Icon(painterResource(R.drawable.ic_more), "More") }
            }
            val grid = rememberLazyGridState()
            FollowViewer(grid, a?.tiles.orEmpty())
            MonthSliderBox(grid = grid, months = months, enabled = selected == null, top = monthHeadingHeight(below = 10.dp), modifier = Modifier.weight(1f)) {
                LazyVerticalGrid(
                    GridCells.Fixed(3), Modifier.testTag("albumGrid"), state = grid,
                    horizontalArrangement = Arrangement.spacedBy(1.dp), verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    groups.forEach { (m, tiles) ->
                        stickyHeader(key = m) {
                            // background: the pinned month covers the tiles passing under it
                            Text(
                                m, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 16.dp, top = 20.dp, bottom = 10.dp),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        items(tiles, key = { it.id }) { t ->
                            val on = selected != null && t.id in selected
                            TileImage(
                                t,
                                Modifier.combinedClickable(
                                    onClick = { if (selected == null) onOpen(a!!.tiles, t) else onSelect(if (on) selected - t.id else selected + t.id) },
                                    onLongClick = { onSelect(selected.orEmpty() + t.id) },
                                ).testTag("tile-${t.id}").animateItem(fadeInSpec = null), // no fade in: a first load shows at once
                                selecting = selected != null, selected = on,
                            )
                        }
                    }
                }
            }
            if (selected != null) {
                val picked = a?.tiles.orEmpty().filter { it.id in selected }
                SelectBar(picked.isNotEmpty() && picked.all { it.favorite }, onShare, onFavorite, onDelete, onMove = onMove)
            }
        }
        Menu(
            more, onDismiss = { more = false },
            items = listOf(
                MenuItem(R.drawable.ic_select, "Select") { more = false; onSelect(emptySet()) },
                // asks first only when turning it on: faces and names go away
                MenuItem(R.drawable.ic_block, "Ignore faces in this album", dividerBefore = true, checked = ignored) {
                    more = false
                    if (ignored) onIgnore(false) else asking = true
                },
            ),
        )
    }
    if (asking) IgnoreFacesDialog(a?.name, onDismiss = { asking = false }) { asking = false; onIgnore(true) }
}

/** Mockup "Ignore faces": asks before an album's faces go. From the album's More and from Folders to skip. */
@Composable
fun IgnoreFacesDialog(name: String?, onDismiss: () -> Unit, onConfirm: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    icon = { Icon(painterResource(R.drawable.ic_block), null) },
    title = { Text("Ignore faces in $name?") },
    text = { Text("Faces found in this album will be deleted from the index, and its photos will not be searched for faces. People seen only here go away, names too. The photos stay.") },
    confirmButton = {
        TextButton(onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Ignore faces") }
    },
    dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    containerColor = MaterialTheme.colorScheme.background,
    iconContentColor = MaterialTheme.colorScheme.error,
)

@Composable
fun AlbumScreen(id: Long, onBack: () -> Unit, onOpen: (List<Tile>, Tile) -> Unit) {
    val context = LocalContext.current
    val pick = rememberPick()
    val album by cachedProduceState("album$id", pick.changes) { withContext(Dispatchers.IO) { albums(context, id) } }
    val a = album?.firstOrNull()
    // the last photo moved, was deleted or moved out: the album is gone, so close instead of a blank page
    CloseWhenEmpty(album, album?.isEmpty() == true, onBack)
    val picked = a?.tiles.orEmpty().filter { it.id in pick.ids.orEmpty() }
    var ignored by remember { mutableStateOf(id in IndexProgress.skipped(context)) }
    var moving by remember { mutableStateOf(false) }
    AlbumContent(
        a, onBack, onOpen, pick.ids, pick.size, ignored,
        onSelect = pick.set,
        // the next index run drops (or searches again) this album's photos
        onIgnore = { IndexProgress.setSkipped(context, id, it); ignored = it },
        onShare = { pick.share(picked) },
        onFavorite = { pick.favorite(picked) },
        onMove = { if (picked.isNotEmpty()) moving = true },
        onDelete = { pick.delete(picked) },
    )
    // moved photos leave this album; the sheet's head shows the first picked
    if (moving) MoveToAlbumSheet(picked.first(), onDismiss = { moving = false }) { path ->
        moving = false
        val foreign = picked.filter { runCatching { movePhoto(context, it, path) }.isFailure }
        if (foreign.isEmpty()) pick.done()
        else pick.confirm(MediaStore.createWriteRequest(context.contentResolver, foreign.map { it.uri })) { foreign.forEach { runCatching { movePhoto(context, it, path) } } }
    }
}

/** Every video, newest first, grouped by month name, and their total size in bytes. */
class Videos(val months: Map<String, List<Tile>>, val all: List<Tile>, val bytes: Long)

/** All videos by month, the month pinned on top. Layout per docs/design.html. Split from the load for previews. */
@Composable
fun VideosContent(videos: Videos?, onBack: () -> Unit = {}, onOpen: (List<Tile>, Tile) -> Unit = { _, _ -> }, pick: Pick = Pick(null)) {
    val v = videos
    val sel = pick.ids
    if (sel != null) BackHandler { pick.set(null) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        val size = v?.bytes?.let { if (it >= 1e9) "%.1f GB".format(it / 1e9) else "%.0f MB".format(it / 1e6) }
        if (sel != null) TopBar(
            "${sel.size} selected", "Videos · ${formatBytes(pick.size)}",
            onBack = { pick.set(null) }, backIcon = R.drawable.ic_close, backLabel = "Cancel",
        ) {
            if (v != null && sel.size < v.all.size) TextButton({ pick.set(v.all.map { it.id }.toSet()) }) { Text("Select all") }
        }
        else TopBar("Videos", v?.let { "${it.all.size} videos · $size" }, onBack = onBack)
        val grid = rememberLazyGridState()
        FollowViewer(grid, v?.all.orEmpty())
        // one (grid item index, month name) per month heading, so the slider can jump to it
        val months = remember(v) {
            var i = 0
            v?.months?.map { (m, tiles) -> (i to m).also { i += 1 + tiles.size } }.orEmpty()
        }
        MonthSliderBox(grid = grid, months = months, enabled = sel == null, top = monthHeadingHeight(below = 10.dp), modifier = Modifier.weight(1f)) {
            LazyVerticalGrid(
                GridCells.Fixed(3), Modifier.testTag("videosGrid"), state = grid,
                horizontalArrangement = Arrangement.spacedBy(1.dp), verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                v?.months?.forEach { (month, tiles) ->
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
                                onClick = { if (sel == null) onOpen(v.all, t) else pick.set(if (on) sel - t.id else sel + t.id) },
                                onLongClick = { pick.set(sel.orEmpty() + t.id) },
                            ).testTag("tile-${t.id}").animateItem(fadeInSpec = null),
                            selecting = sel != null, selected = on,
                        )
                    }
                }
            }
        }
        if (sel != null) {
            val picked = v?.all.orEmpty().filter { it.id in sel }
            SelectBar(picked.isNotEmpty() && picked.all { it.favorite }, { pick.share(picked) }, { pick.favorite(picked) }, { pick.delete(picked) })
        }
    }
}

@Composable
fun VideosScreen(onBack: () -> Unit, onOpen: (List<Tile>, Tile) -> Unit) {
    val context = LocalContext.current
    val pick = rememberPick()
    val videos by cachedProduceState("videos", pick.changes, isEmpty = { it.all.isEmpty() }) {
        withContext(Dispatchers.IO) {
            val all = albums(context).flatMap { it.tiles }.filter { it.duration != null }.sortedByDescending { it.date }
            var bytes = 0L
            context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media.SIZE), null, null, null)?.use { c ->
                while (c.moveToNext()) bytes += c.getLong(0)
            }
            val month = DateTimeFormatter.ofPattern("LLLL yyyy")
            val zone = ZoneId.systemDefault()
            Videos(all.groupBy { month.format(Instant.ofEpochMilli(it.date).atZone(zone)) }, all, bytes)
        }
    }
    // the last video was deleted: nothing to show, so close instead of a blank page
    CloseWhenEmpty(videos, videos?.all?.isEmpty() == true, onBack)
    VideosContent(videos, onBack, onOpen, pick)
}
