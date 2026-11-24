package app.memoria

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Photos tab's select mode (mockup "Photos, selecting"): the picked tiles, live so the app can swap
 * the bottom bar and act on the whole set. Long press starts it, a tap toggles, a long press then drag
 * adds the tiles it passes (a plain drag scrolls the list), a day's circle picks the whole day, Back or
 * the X leaves. Purely state, so it is unit-testable.
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
    val lock: (List<Tile>) -> Unit = {},
    val confirm: (PendingIntent, suspend () -> Unit) -> Unit = { _, _ -> },
    val done: () -> Unit = {},
)

@Composable
fun rememberPick(): Pick {
    val context = LocalContext.current
    var ids by remember { mutableStateOf<Set<Long>?>(null) }
    val size by produceState(0L, ids) { value = withContext(Dispatchers.IO) { selectedSize(context, ids.orEmpty()) } }
    val scope = rememberCoroutineScope()
    // Android's confirm for photos another app owns: [then] runs on OK
    var then by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val onOk = then
        then = null
        scope.launch {
            if (result.resultCode == Activity.RESULT_OK) { onOk?.invoke(); ids = null }
            viewerEdits++ // our own photos changed before the confirm, even on Cancel
        }
    }
    fun confirm(request: PendingIntent, onOk: suspend () -> Unit) { then = onOk; ask.launch(IntentSenderRequest.Builder(request.intentSender).build()) }
    fun done() { ids = null; viewerEdits++ }
    val lockTiles = rememberVaultLock { done() }
    val mediaConfirm = rememberMediaConfirm()
    return Pick(
        ids, size, viewerEdits, set = { ids = it },
        share = { picked ->
            if (picked.isNotEmpty()) {
                val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(picked.map { it.uri }))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, null))
            }
        },
        favorite = { picked ->
            if (picked.isNotEmpty()) scope.launch {
                val fav = !picked.all { it.favorite }
                // one MediaStore write per photo: off the main thread, a few hundred take seconds
                val foreign = withContext(Dispatchers.IO) { picked.filter { t -> runCatching { setFavorite(context, t, fav) }.isFailure } }
                updateTiles(picked.filter { it !in foreign }.map { it.copy(favorite = fav) })
                if (foreign.isEmpty()) done()
                else confirm(MediaStore.createFavoriteRequest(context.contentResolver, foreign.map { it.uri }, fav)) { updateTiles(foreign.map { it.copy(favorite = fav) }) }
            }
        },
        delete = { picked ->
            if (picked.isNotEmpty()) mediaConfirm(trashQuestion(picked)) {
                confirm(MediaStore.createTrashRequest(context.contentResolver, picked.map { it.uri }, true)) { forgetTiles(picked.map { it.id }.toSet()) }
            }
        },
        lock = { picked -> lockTiles(picked) },
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

/** "1.2 of 4.8 GB" for Locking (mockup): both in the total's unit, MB under 1 GB. */
fun bytesOf(done: Long, total: Long): String {
    val (d, unit) = if (total >= 1_000_000_000) 1e9 to "GB" else 1e6 to "MB"
    return "%.1f of %.1f %s".format(done / d, total / d, unit)
}

/**
 * Drag-select for the timeline (mockup "Photos, selecting"): a long press starts it and the drag adds
 * the tiles it passes. It sits on the tile, not on the grid: the tile is deeper than the grid's
 * scrollable, so the drag it consumes never reaches the scroll, while a plain drag is left alone and
 * scrolls the list. Only the tab has it (the other grids have no selection holder).
 */
internal fun Modifier.selectDrag(grid: LazyGridState, self: Tile, tilesById: Map<Long, Tile>, selection: PhotoSelection): Modifier =
    pointerInput(self.id, tilesById) {
        detectDragGesturesAfterLongPress(
            onDragStart = { selection.start(self) },
            onDrag = { change, _ ->
                // the finger is in this tile's space: move it into the grid's content, where the item offsets are
                val me = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == self.id }
                val item = me?.let {
                    val x = change.position.x + it.offset.x
                    val y = change.position.y + it.offset.y - grid.layoutInfo.beforeContentPadding
                    grid.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                        x >= info.offset.x && x < info.offset.x + info.size.width &&
                            y >= info.offset.y && y < info.offset.y + info.size.height
                    }
                }
                (item?.key as? Long)?.let { id -> tilesById[id]?.let { selection.add(it) } }
            },
        )
    }
