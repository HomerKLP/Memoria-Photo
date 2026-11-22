package app.memoria

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets as Insets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Bars fade fast: the tap must feel immediate. Material's default fade is a spring (~400 ms).
private const val barsFadeMillis = 120

/** Where the More menu drops: under the top bar, which upright holds the status bar and sideways does not. */
internal fun viewerMenuTop(land: Boolean) = if (land) 56.dp else 88.dp

/**
 * The viewer's top and bottom bars, from docs/design.html "Viewer": they float on the fully visible
 * photo and are transparent — a soft vertical gradient keeps the white icons readable — so they never
 * hide the photo. [visible] false is the tapped state: the bars fade out and only the photo is left.
 * Purely visual, so the layout tests and previews can render it without MediaStore.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ViewerChrome(
    visible: Boolean,
    title: String,
    subtitle: String,
    favorite: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
    menuItems: List<MenuItem> = emptyList(),
    bottom: Boolean = true, // false: the details panel is up and covers the bottom bar
    playerBar: (@Composable () -> Unit)? = null, // a video's controls, above the action row
    onMenu: (Boolean) -> Unit = {}, // the menu opened or closed: a video's controls stay while it is open
    trashed: Boolean = false, // a trashed file: no Share, Favorite or More; Restore sits before Delete
    onRestore: () -> Unit = {},
    vault: Boolean = false, // a locked file: no Share, Favorite or More; Move out sits before Delete
    onMoveOut: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(menu) { onMenu(menu) }
    // Sideways the system bars are hidden for good (see Viewer), so the bars take their space: the screen is
    // short and the controls need the room. Upright the bar height is kept even while the bars are hidden, so
    // a tap that hides them never moves the icons.
    val land = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val topInset = if (land) Insets(0, 0, 0, 0) else Insets.statusBarsIgnoringVisibility
    val bottomInset = if (land) Insets(0, 0, 0, 0) else Insets.navigationBarsIgnoringVisibility
    // The mockup's own numbers, not the camera hole's insets: its landscape frame insets the top bar 34 from
    // the camera's edge and 4 from the other, and the four actions 8 on both sides. Sideways the hole sits in
    // the middle of the edge, below a 64 dp bar, so no bar reaches it; upright the status bar's height (which
    // holds the hole) is the inset.
    val barStart = if (land) 34.dp else 4.dp
    val barEnd = 4.dp
    val actionsPad = 8.dp
    // The bars stay composed and are only placed while showing: composing them when the opened photo
    // settled cost a dropped frame, and unplaced bars take no taps meant for the photo.
    val top = animateFloatAsState(if (visible) 1f else 0f, tween(barsFadeMillis), label = "topBar")
    val low = animateFloatAsState(if (visible && bottom) 1f else 0f, tween(barsFadeMillis), label = "bottomBar")
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.align(Alignment.TopCenter)
                .layout { m, c -> val p = m.measure(c); layout(p.width, p.height) { if (top.value > 0f) p.place(0, 0) } }
                .graphicsLayer { alpha = top.value }
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(viewerBg.copy(alpha = 0.62f), Color.Transparent)))
                // The system bar height even while it is hidden: a tap hides the bars, and a bar that loses
                // its padding first jumps 40 dp up under the fading icons. Sideways the system bars are gone,
                // so the bar takes the mockup's own 8 dp top margin instead.
                .windowInsetsPadding(topInset)
                .padding(start = barStart, end = barEnd, top = if (land) 8.dp else 0.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onBack) { Icon(painterResource(R.drawable.ic_back), "Back", tint = onViewer) }
            // As the mockup: the date sits between the two buttons, so the camera's side margin shifts it a little
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, color = onViewer, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = onViewer.copy(alpha = 0.7f), fontSize = 12.sp)
            }
            // a trashed or locked file has no More menu: its slot stays empty, so the date does not move
            if (trashed || vault) Box(Modifier.size(48.dp))
            else IconButton({ if (menuItems.isNotEmpty()) menu = true }) {
                Icon(painterResource(R.drawable.ic_more), "More", tint = onViewer)
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter)
                .layout { m, c -> val p = m.measure(c); layout(p.width, p.height) { if (low.value > 0f) p.place(0, 0) } }
                .graphicsLayer { alpha = low.value }
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, viewerBg.copy(alpha = 0.62f))))
                .windowInsetsPadding(bottomInset).padding(top = 16.dp, bottom = 6.dp),
        ) {
            // the player line insets 8 of its own, so sideways this makes the mockup's 34
            Box(Modifier.padding(horizontal = if (land) 26.dp else 0.dp)) { playerBar?.invoke() }
            // Sideways a wide screen would leave four icons far apart, so they sit as one compact group
            Row(
                Modifier.fillMaxWidth().padding(horizontal = actionsPad),
                horizontalArrangement = if (land) Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally) else Arrangement.SpaceAround,
            ) {
                if (trashed) {
                    IconButton(onInfo) { Icon(painterResource(R.drawable.ic_info), "Details", tint = onViewer) }
                    IconButton(onRestore) { Icon(painterResource(R.drawable.ic_restore), "Restore", tint = onViewer) }
                } else if (vault) {
                    IconButton(onInfo) { Icon(painterResource(R.drawable.ic_info), "Details", tint = onViewer) }
                    IconButton(onMoveOut) { Icon(painterResource(R.drawable.ic_upload), "Move out", tint = onViewer) }
                } else {
                    IconButton(onShare) { Icon(painterResource(R.drawable.ic_share), "Share", tint = onViewer) }
                    IconButton(onFavorite) {
                        Icon(
                            painterResource(if (favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border),
                            if (favorite) "Remove from favorites" else "Add to favorites",
                            tint = if (favorite) Color(0xFFE9A23B) else onViewer,
                        )
                    }
                    IconButton(onInfo) { Icon(painterResource(R.drawable.ic_info), "Details", tint = onViewer) }
                }
                IconButton(onDelete) { Icon(painterResource(R.drawable.ic_trash_line), "Delete", tint = onViewer) }
            }
        }
        // Sideways the bar is shorter (no status bar), so the menu drops from the button's own bottom
        Menu(menu, onDismiss = { menu = false }, items = menuItems, top = viewerMenuTop(land))
    }
}

/** More > Edit in…: hands the photo to an editor app through Android's chooser. */
internal fun editIn(context: Context, tile: Tile) {
    val type = context.contentResolver.getType(tile.uri) ?: "*/*"
    val edit = Intent(Intent.ACTION_EDIT).setDataAndType(tile.uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(edit, null)) }
}

/** More > Use as…: set as wallpaper / contact photo through Android's chooser. */
internal fun useAs(context: Context, tile: Tile) {
    val type = context.contentResolver.getType(tile.uri) ?: "*/*"
    val use = Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(tile.uri, type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra("mimeType", type)
    runCatching { context.startActivity(Intent.createChooser(use, null)) }
}

/** More > Open in the phone's player, and the way out of a file our decoder refuses (mockup "Video, cannot play"). */
internal fun openInPlayer(context: Context, tile: Tile) {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(tile.uri, context.contentResolver.getType(tile.uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(view) }
}

/** m:ss like every player: "1:24 / 3:10" in the mockup "Video, playing". */
internal fun clock(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) + 500) / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/**
 * A video's whole control set (mockup "Video, playing"): play / pause, one seek bar, the time. Purely
 * visual with callbacks, so the behavior tests drive it without a player.
 */
@Composable
fun PlayerBar(
    playing: Boolean,
    position: Long,
    length: Long,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onScrubbing: (Boolean) -> Unit = {}, // a drag on the bar starts and ends: the player gets ready for many seeks
) {
    var drag by remember { mutableStateOf<Float?>(null) } // the thumb under the finger
    val span = length.coerceAtLeast(1L).toFloat()
    val at = drag ?: (position / span).coerceIn(0f, 1f)
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onToggle) {
            Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play), if (playing) "Pause" else "Play", tint = onViewer)
        }
        SeekBar(
            at = at,
            // the video seeks as the finger moves, so the frame shows where the drag is
            onScrub = { if (drag == null) onScrubbing(true); drag = it; onSeek((it * span).toLong()) },
            onSeek = { drag = null; onSeek((it * span).toLong()); onScrubbing(false) },
            modifier = Modifier.weight(1f),
        )
        // Fixed width and tabular digits: the total arrives once the file is read, and the slider must not jump then
        // 88 dp fits "59:59 / 59:59"
        Row(Modifier.width(88.dp), horizontalArrangement = Arrangement.End) {
            val time = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
            Text(clock((at * span).toLong()), color = onViewer, style = time)
            Text(" / ${clock(length)}", color = onViewer.copy(alpha = 0.65f), style = time)
        }
    }
}

/** A 4 dp line with a 12 dp knob, not Material's fat slider (mockup "Video, playing"). Tap or drag it: [onScrub] on each move, [onSeek] on the lift. */
@Composable
private fun SeekBar(at: Float, onScrub: (Float) -> Unit, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(28.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(at, 0f..1f)
                setProgress { onScrub(it); onSeek(it); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.coerceAtLeast(1)
                    onScrub((down.position.x / w).coerceIn(0f, 1f))
                    var x = down.position.x
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { x = it.position.x; onScrub((x / w).coerceIn(0f, 1f)); it.consume() }
                    } while (event.changes.any { it.pressed })
                    onSeek((x / w).coerceIn(0f, 1f))
                }
            }
            .drawBehind {
                val y = size.height / 2f
                val line = 4.dp.toPx()
                drawLine(onViewer.copy(alpha = 0.3f), Offset(0f, y), Offset(size.width, y), line, StrokeCap.Round)
                drawLine(onViewer, Offset(0f, y), Offset(size.width * at, y), line, StrokeCap.Round)
                // the mockup's soft shadow under the knob, so it reads on a light frame
                drawCircle(Color.Black.copy(alpha = 0.3f), 7.dp.toPx(), Offset(size.width * at, y + 1.dp.toPx()))
                drawCircle(onViewer, 6.dp.toPx(), Offset(size.width * at, y))
            },
    )
}
