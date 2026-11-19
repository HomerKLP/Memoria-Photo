package app.memoria

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Shown at once when the tab opens again, then replaced by a fresh query
var lastTimeline: List<TimelineItem>? = null
var lastFavorites: List<TimelineItem>? = null

/** Replaces tiles in the cached timelines after a change, so the grid and viewer see them on the next open. One pass for any number. */
fun updateTiles(tiles: List<Tile>) {
    if (tiles.isEmpty()) return
    val byId = tiles.associateBy { it.id }
    lastTimeline = lastTimeline?.map { if (it is Tile) byId[it.id] ?: it else it }
    lastFavorites = lastFavorites?.map { if (it is Tile) byId[it.id] ?: it else it }
}

/** Drops trashed tiles from the cached timelines, so the grid does not flash them before the reload. */
fun forgetTiles(ids: Set<Long>) {
    lastTimeline = lastTimeline?.filterNot { it is Tile && it.id in ids }
    lastFavorites = lastFavorites?.filterNot { it is Tile && it.id in ids }
}

/** Goes up on every photo edit (the viewer or select mode), so the screens showing it reload. */
var viewerEdits by mutableIntStateOf(0)

/** Where each grid tile is on screen, so the viewer can grow a photo out of it and shrink it back in. */
val tileSpots = HashMap<Long, LayoutCoordinates>()

/** The photo open in the viewer; its tile stays empty, since the viewer draws it. */
var viewerTile by mutableStateOf<Long?>(null)

/**
 * A tab-tap scroll to the top is running. Tiles that fly by don't start a thumbnail decode; they load
 * once it settles, so the fly-by stays cheap instead of decoding (and dropping frames) on the way up.
 */
var fastScrolling by mutableStateOf(false)

/**
 * Keeps the viewer's photo in view in [grid], whose tiles come in [order], so closing the viewer lands in its tile.
 * Only after a swipe: on open the grid stays put, since the photo is growing out of its tile.
 * [enabled] false: the grid sits under another screen, whose own grid follows the viewer.
 */
@Composable
fun FollowViewer(grid: LazyGridState, order: List<Tile>, enabled: () -> Boolean = { true }) {
    val tiles by rememberUpdatedState(order)
    val follow by rememberUpdatedState(enabled)
    LaunchedEffect(grid) {
        var last: Long? = null
        snapshotFlow { viewerTile }.collectLatest { id ->
            val swiped = last != null
            last = id
            if (swiped && id != null && follow()) grid.reveal(id, tiles)
        }
    }
}

/** Scrolls until tile [id] is fully in view and not under a pinned month header. */
private suspend fun LazyGridState.reveal(id: Long, order: List<Tile>) {
    val at = order.indexOfFirst { it.id == id }
    if (at < 0) return
    repeat(50) {
        val info = layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.key == id }
        if (item == null) {
            val seen = info.visibleItemsInfo.firstOrNull { it.key is Long }?.let { v -> order.indexOfFirst { it.id == v.key } } ?: return
            scrollBy(if (at < seen) -info.viewportSize.height.toFloat() else info.viewportSize.height.toFloat())
            return@repeat
        }
        // tile keys are Long, header keys are not; a pinned header is drawn where the content starts (offset 0,
        // under the top padding), with tiles passing under it
        val top = info.visibleItemsInfo.filter { it.key !is Long && it.offset.y <= 0 }
            .maxOfOrNull { maxOf(it.offset.y, 0) + it.size.height } ?: 0
        val below = item.offset.y + item.size.height - info.viewportEndOffset
        if (item.offset.y < top) animateScrollBy((item.offset.y - top).toFloat())
        else if (below > 0) animateScrollBy(below.toFloat())
        return
    }
}

/**
 * How long the tab-tap scroll to the top takes. One fixed number, whatever the distance (Immich /
 * Telegram style): the grid scrolls there in one continuous motion, capped, instead of a longer
 * animation the farther you scrolled.
 */
const val tabScrollToTopMs = 350

/**
 * Writes MediaStore's own favorite flag. Throws SecurityException for photos the app does not own:
 * the caller then asks the user once through MediaStore.createFavoriteRequest.
 */
fun setFavorite(context: Context, tile: Tile, favorite: Boolean) {
    context.contentResolver.update(tile.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_FAVORITE, if (favorite) 1 else 0) }, null, null)
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
    scrollToTop: Int = 0,
    onTop: () -> Boolean = { true },
) {
    val context = LocalContext.current
    val media = arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    val perms = media + Manifest.permission.ACCESS_MEDIA_LOCATION // photo GPS, optional
    fun has(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(media.all(::has)) }
    // the face search notification, optional: asked once, after the photos dialog, so a no is never asked again
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = media.all(::has)
        if (!has(Manifest.permission.POST_NOTIFICATIONS)) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(Unit) { if (!perms.all(::has)) ask.launch(perms) }
    val pick = rememberPick() // Favorites only, the timeline has its own select mode
    // New photos while the tab is open: reload once the burst of changes has been quiet.
    // In the background only note it, and reload once on return.
    var mediaChanges by remember { mutableIntStateOf(0) }
    var missed by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(context, lifecycle) {
        val handler = Handler(Looper.getMainLooper())
        val bump = Runnable { if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mediaChanges++ else missed = true }
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.removeCallbacks(bump)
                // 300 ms: enough to swallow a burst, short enough that a single new photo shows at once
                handler.postDelayed(bump, 300)
            }
        }
        context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
            handler.removeCallbacks(bump)
        }
    }
    LifecycleStartEffect(Unit) {
        if (missed) { missed = false; mediaChanges++ }
        onStopOrDispose {}
    }
    val items by produceState(if (favoritesOnly) lastFavorites else lastTimeline, granted, reload, pick.changes, mediaChanges) {
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
        onOpen = onOpen,
        grid = grid,
        selection = selection,
        pickedSize = pickedSize,
        onSearch = onSearch,
        scrollToTop = scrollToTop,
        onTop = onTop,
    )
}

/** Photos layout per docs/design.html: the timeline, the months slider and the select mode. No MediaStore, so previews and tests render it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotosContent(
    items: List<TimelineItem>?,
    granted: Boolean = true,
    onAsk: () -> Unit = {},
    onOpen: (List<Tile>, Tile) -> Unit = { _, _ -> },
    grid: LazyGridState = rememberLazyGridState(),
    selection: PhotoSelection? = null,
    pickedSize: Long = 0L,
    onSearch: () -> Unit = {},
    // The New album picker opens already in select mode (empty pick), under its own title and bar:
    forceSelecting: Boolean? = null,
    titleWhileSelecting: String? = null,
    onSelectBack: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    scrollToTop: Int = 0,
    onTop: () -> Boolean = { true }, // false while another screen covers the tab
) {
    val list = items.orEmpty()
    val photos = list.filterIsInstance<Tile>()
    val tabs = selection != null // the main tab supports select mode; Favorites passes none
    val selecting = forceSelecting ?: (selection?.active == true)
    val picker = forceSelecting != null // New album: always selecting, under its own title bar and footer
    val selectedIds = selection?.ids.orEmpty()
    // One entry per month header: the grid item a month starts at (the tab's wordmark is item 0), and its name for the slider
    val months = remember(items, picker) { list.mapIndexedNotNull { i, x -> if (x is Header && x.month) i + (if (picker) 0 else 1) to x.text else null } }
    // Tab tap to the top, Immich style: one continuous scroll, capped at tabScrollToTopMs whatever the
    // distance, and no thumbnail decode for the tiles that only fly by (Thumb waits on fastScrolling).
    // The value it had when the tab came back is an old tap: only a newer one scrolls.
    val seenScrollToTop = remember { scrollToTop }
    LaunchedEffect(scrollToTop) {
        if (scrollToTop == seenScrollToTop) return@LaunchedEffect
        fastScrolling = true
        try {
            // Far down, a scroll composes every row it passes (a second per thousand rows), so first jump
            // to about three screens below the top and scroll only the rest.
            val shown = grid.layoutInfo.visibleItemsInfo.size
            if (grid.firstVisibleItemIndex > shown * 3) grid.scrollToItem(shown * 3)
            // A pinned month heading has no line, so the cache window can start at line -1 and the jump asks the grid for it; moving by 1 px resets the window.
            grid.scrollToItem(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset + 1)
            // The exact pixel distance to the top, measured with a scroll there and back inside one
            // frame: the two scrolls happen before the next draw, so the jump is never shown.
            val back = grid.scrollBy(-1e9f)
            if (back >= 0f) return@LaunchedEffect // already at the top
            grid.scrollBy(-back)
            grid.animateScrollBy(back, tween(tabScrollToTopMs, easing = FastOutSlowInEasing))
        } finally {
            fastScrolling = false
        }
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
    val density = LocalDensity.current
    val barH = maxOf(48.dp, 36.dp + with(density) { MaterialTheme.typography.titleLarge.lineHeight.toDp() })
    // The grid runs under the status bar and, on the tab, to the bottom edge behind the floating tabs
    // (mockup "Photos, scrolled"). The picker's grid starts under its title bar and ends at its footer.
    // Ignoring visibility: the viewer hides the system bars on a tap, and the grid must not move for it
    val status = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    val gridTop = status + if (picker) barH else 0.dp
    val navBottom = if (picker) 0.dp else WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()
    // The wordmark is the grid's first item, so it scrolls away with the list and comes back only at the
    // very top. When a scroll stops halfway it settles to the nearest end.
    val barPx = with(density) { barH.toPx() }
    LaunchedEffect(grid, barPx) {
        snapshotFlow { grid.isScrollInProgress }.collect { scrolling ->
            val o = grid.firstVisibleItemScrollOffset.toFloat()
            if (!picker && !scrolling && grid.firstVisibleItemIndex == 0 && o > 0f && o < barPx)
                launch { grid.animateScrollBy(if (o < barPx / 2) -o else barPx - o, tween(150)) }
        }
    }
    val wordmark = @Composable {
        Row(Modifier.fillMaxWidth().height(barH).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(buildAnnotatedString {
                append("Memoria ")
                withStyle(SpanStyle(MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)) { append("Photo") }
            }, Modifier.weight(1f).padding(start = 16.dp, top = 18.dp, bottom = 18.dp), style = MaterialTheme.typography.titleLarge)
            IconButton(onSearch) { Icon(painterResource(R.drawable.ic_search), "Search") }
        }
    }
    Box {
        Column {
            if (!granted) {
                Column(Modifier.padding(top = status)) {
                    wordmark()
                    Text("Memoria needs access to your photos and videos.", Modifier.padding(horizontal = 20.dp))
                    Button(onAsk, Modifier.padding(start = 20.dp, top = 12.dp)) { Text("Allow access") }
                }
                return@Column
            }
            MonthSliderBox(
                grid = grid,
                months = months,
                // always on for the timeline (Favorites has its own screen and no slider)
                enabled = !selecting,
                // from under the pinned month to just above the floating tabs
                top = gridTop + monthHeadingHeight(below = 2.dp),
                bottom = if (picker) 0.dp else navBottom + 24.dp,
                modifier = Modifier.weight(1f),
            ) {
                FollowViewer(grid, photos, onTop)
                LazyVerticalGrid(
                    GridCells.Fixed(3), state = grid,
                    modifier = Modifier.testTag("photosGrid"),
                    contentPadding = PaddingValues(top = gridTop, bottom = if (picker) 0.dp else navBottom + 72.dp),
                    horizontalArrangement = Arrangement.spacedBy(1.dp), verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    if (!picker) item(key = "wordmark", span = { GridItemSpan(maxLineSpan) }, contentType = "wordmark") { wordmark() }
                    for (x in list) when (x) {
                        is Header -> if (x.month) {
                            stickyHeader(key = x.key, contentType = "month") { MonthHeading(x.text, x.key, grid, below = 2.dp) }
                        } else item(key = x.key, span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                            val day = dayTiles[x.key].orEmpty()
                            val all = selecting && day.isNotEmpty() && day.all { it.id in selectedIds }
                            // the circle is an overlay: the header keeps its height, so the photos under it never move
                            Box(Modifier.fillMaxWidth().testTag("dayHeader").animateItem()) {
                                Text(x.text, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.titleSmall)
                                if (selecting) DayCircle(all, Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) {
                                    if (all) selection?.remove(day.map { it.id }) else selection?.addAll(day)
                                }
                            }
                        }
                        is Tile -> item(key = x.id, contentType = "tile") {
                            TileImage(
                                x,
                                // The long press starts select mode (and swallows the tap on lift, or it would
                                // toggle the tile straight off again); the drag detector adds the tiles it passes.
                                (if (tabs) Modifier.combinedClickable(
                                    onClick = { if (selecting) selection.toggle(x) else onOpen(photos, x) },
                                    onLongClick = { selection.start(x) },
                                ).selectDrag(grid, x, tilesById, selection) else Modifier.clickable { onOpen(photos, x) })
                                    .testTag("tile-${x.id}").animateItem(),
                                selecting = selecting,
                                selected = x.id in selectedIds,
                                onTop = onTop,
                            )
                        }
                    }
                }
            }
            footer?.invoke()
        }
        // fade the select bar in over the wordmark, or over the pinned month when the wordmark is gone;
        // solid behind the status bar too
        Crossfade(selecting, Modifier.fillMaxWidth(), animationSpec = tween(150), label = "selectBar") { on ->
            if (on) Row(
                // the empty pointerInput keeps a tap on the bar from reaching the tiles under it
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).pointerInput(Unit) {}
                    .padding(top = status).height(barH).padding(start = 4.dp, end = 8.dp),
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
