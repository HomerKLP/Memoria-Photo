package app.memoria

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.view.WindowInsets
import android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
import android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val onViewer = Color(0xFFF4ECE1)
private val viewerBg = Color(0xFF120E0A)
private const val maxZoom = 6f

// Bars fade fast: the tap must feel immediate. Material's default fade is a spring (~400 ms).
private const val barsFadeMillis = 120

/** Full-screen photos, swipe sideways for the next one. Opens on the tile with id start. */
@Composable
fun Viewer(start: Long, tiles: List<Tile>, onBack: () -> Unit) {
    val context = LocalContext.current
    var list by remember { mutableStateOf(tiles) } // shrinks when a photo goes to the trash
    if (list.isEmpty()) { // the app was restarted while the viewer was open, or the last photo was trashed
        LaunchedEffect(Unit) { onBack() }
        return
    }
    BackHandler(onBack = onBack)
    var details by remember { mutableStateOf(false) }
    // Tap the photo: the bars and the system bars hide, so only the photo is left. Tap again brings them back.
    var barsVisible by remember { mutableStateOf(true) }
    // Light bar icons on the dark viewer, dark again when it closes or when the light details sheet is up
    val window = (LocalView.current.context as Activity).window
    val barIcons = APPEARANCE_LIGHT_STATUS_BARS or APPEARANCE_LIGHT_NAVIGATION_BARS
    LaunchedEffect(details, barsVisible) {
        val controller = window.insetsController ?: return@LaunchedEffect
        controller.setSystemBarsAppearance(if (details) barIcons else 0, barIcons)
        // the details sheet is light: its system bars never hide
        if (details || barsVisible) controller.show(WindowInsets.Type.systemBars())
        else controller.hide(WindowInsets.Type.systemBars())
    }
    DisposableEffect(Unit) {
        onDispose {
            window.insetsController?.setSystemBarsAppearance(barIcons, barIcons)
            window.insetsController?.show(WindowInsets.Type.systemBars())
        }
    }
    val pager = rememberPagerState(tiles.indexOfFirst { it.id == start }.coerceAtLeast(0)) { list.size }
    // coerce: after trashing the last photo the pager is one frame behind the shorter list
    val tile = list[pager.currentPage.coerceAtMost(list.lastIndex)]
    // Swipe down to close: track the drag, dismiss only past a threshold
    val dismissAt = with(LocalDensity.current) { 120.dp.toPx() }
    // Swipe up: a much shorter pull is enough, the remaining gesture belongs to the sheet
    val openAt = with(LocalDensity.current) { 48.dp.toPx() }
    var dragY by remember { mutableFloatStateOf(0f) }
    // A zoomed photo owns every finger, so the pager and the vertical swipes wait. Reset when the page changes.
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { zoomed = false }
    // Favorites: our override wins over the timeline, MediaStore is the source of truth on the next open
    val favorites = remember { mutableStateMapOf<Long, Boolean>() }
    var pending by remember { mutableStateOf<Pair<Long, Boolean>?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        pending?.let { (id, favorite) ->
            if (result.resultCode == Activity.RESULT_OK) favorites[id] = favorite
            pending = null
        }
    }
    val favorite = favorites[tile.id] ?: tile.favorite
    // Trash: Android asks the user, then the next photo slides in
    var trashing by remember { mutableStateOf<Long?>(null) }
    val trashAsk = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        trashing?.let { id ->
            if (result.resultCode == Activity.RESULT_OK) { list = list.filterNot { it.id == id }; forgetTile(id) }
            trashing = null
        }
    }
    // Photo fills the whole screen, the bars float on it (transparent gradient, see ViewerChrome)
    val zone = Instant.ofEpochMilli(tile.date).atZone(ZoneId.systemDefault())
    val dayPattern = if (zone.year == LocalDate.now().year) "EEE, d MMM" else "EEE, d MMM yyyy"
    Box(
        Modifier.fillMaxSize().background(viewerBg)
            // Only a downward drag moves the photo (closing it). On an upward drag the photo stays
            // put: a short pull opens the details and the sheet is the feedback.
            .offset { IntOffset(0, dragY.coerceAtLeast(0f).roundToInt()) }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState {
                    dragY += it
                    if (dragY < -openAt && !details) { details = true; dragY = 0f }
                },
                enabled = !zoomed,
                onDragStopped = {
                    if (dragY > dismissAt) onBack()
                    dragY = 0f
                },
            ),
    ) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, userScrollEnabled = !zoomed, key = { list[it].id }) {
            ViewerPage(list[it], { zoomed = it }, onTap = { barsVisible = !barsVisible })
        }
        ViewerChrome(
            visible = barsVisible,
            title = DateTimeFormatter.ofPattern(dayPattern).format(zone),
            subtitle = DateTimeFormatter.ofPattern("HH:mm").format(zone),
            favorite = favorite,
            onBack = onBack,
            onShare = {
                val send = Intent(Intent.ACTION_SEND).setType(context.contentResolver.getType(tile.uri))
                    .putExtra(Intent.EXTRA_STREAM, tile.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, null))
            },
            onFavorite = {
                val next = !favorite
                try {
                    setFavorite(context, tile, next) // our own photos need no dialog
                    favorites[tile.id] = next
                    updateTile(tile.copy(favorite = next))
                } catch (e: SecurityException) {
                    // someone else's photo: Android asks the user once, then remembers
                    pending = tile.id to next
                    val ask = MediaStore.createFavoriteRequest(context.contentResolver, listOf(tile.uri), next)
                    consent.launch(IntentSenderRequest.Builder(ask.intentSender).build())
                }
            },
            onInfo = { details = true },
            onDelete = {
                trashing = tile.id
                val ask = MediaStore.createTrashRequest(context.contentResolver, listOf(tile.uri), true)
                trashAsk.launch(IntentSenderRequest.Builder(ask.intentSender).build())
            },
        )
    }
    if (details) DetailsSheet(tile, onDismiss = { details = false })
}

/**
 * The viewer's top and bottom bars, from docs/design.html "Viewer": they float on the fully visible
 * photo and are transparent — a soft vertical gradient keeps the white icons readable — so they never
 * hide the photo. [visible] false is the tapped state: the bars fade out and only the photo is left.
 * Purely visual, so the layout tests and previews can render it without MediaStore.
 */
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
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, Modifier.align(Alignment.TopCenter), enter = fadeIn(tween(barsFadeMillis)), exit = fadeOut(tween(barsFadeMillis))) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(viewerBg.copy(alpha = 0.62f), Color.Transparent)))
                    .statusBarsPadding().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onBack) { Icon(painterResource(R.drawable.ic_back), "Back", tint = onViewer) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, color = onViewer, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(subtitle, color = onViewer.copy(alpha = 0.7f), fontSize = 12.sp)
                }
                Spacer(Modifier.size(48.dp)) // same width as Back, keeps the date centered
            }
        }
        AnimatedVisibility(visible, Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(barsFadeMillis)), exit = fadeOut(tween(barsFadeMillis))) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, viewerBg.copy(alpha = 0.62f))))
                    .navigationBarsPadding().padding(top = 16.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                IconButton(onShare) { Icon(painterResource(R.drawable.ic_share), "Share", tint = onViewer) }
                IconButton(onFavorite) {
                    Icon(
                        painterResource(if (favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border),
                        if (favorite) "Remove from favorites" else "Add to favorites",
                        tint = if (favorite) Color(0xFFE9A23B) else onViewer,
                    )
                }
                IconButton(onInfo) { Icon(painterResource(R.drawable.ic_info), "Details", tint = onViewer) }
                IconButton(onDelete) { Icon(painterResource(R.drawable.ic_trash_line), "Delete", tint = onViewer) }
            }
        }
    }
}

/** How big the photo really is on screen with ContentScale.Fit, so panning stops at its edges. */
internal fun fitSize(imageWidth: Int, imageHeight: Int, boxWidth: Int, boxHeight: Int): Size {
    if (imageWidth <= 0 || imageHeight <= 0 || boxWidth == 0 || boxHeight == 0) return Size(0f, 0f)
    val s = minOf(boxWidth.toFloat() / imageWidth, boxHeight.toFloat() / imageHeight)
    return Size(imageWidth * s, imageHeight * s)
}

private fun fitSize(bitmap: Bitmap?, box: IntSize): Size =
    if (bitmap == null) Size(0f, 0f) else fitSize(bitmap.width, bitmap.height, box.width, box.height)

@Composable
fun ViewerPage(tile: Tile, onZoomed: (Boolean) -> Unit, onTap: () -> Unit = {}) {
    val context = LocalContext.current
    // Grid thumbnail first, then a screen-size decode
    val image by produceState(thumbs.get(tile.id), tile.id) {
        val full = withContext(Dispatchers.IO) {
            runCatching {
                // MediaStore keeps only a small video thumbnail, so take a sharp frame from the file
                if (tile.duration != null) MediaMetadataRetriever().use {
                    it.setDataSource(context, tile.uri)
                    it.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1440, 1440)
                } else loadPhoto(ImageDecoder.createSource(context.contentResolver, tile.uri), 2560)
            }.getOrNull()
        }
        if (full != null) value = full
    }
    // Zoom state lives here: a zoomed photo cannot be swiped away, so the page is never stale.
    val zoom = remember { mutableFloatStateOf(1f) }
    val pan = remember { mutableStateOf(Offset.Zero) }
    val box = remember { mutableStateOf(IntSize.Zero) }
    val fit = remember { mutableStateOf(Size.Zero) }
    LaunchedEffect(image, box.value) { fit.value = fitSize(image, box.value) }
    // snapshotFlow, not LaunchedEffect(zoom): reporting must not recompose the viewer on every pinch event
    LaunchedEffect(Unit) { snapshotFlow { zoom.floatValue > 1f }.distinctUntilChanged().collect(onZoomed) }
    fun transform(zoomChange: Float, panChange: Offset) {
        val next = (zoom.floatValue * zoomChange).coerceIn(1f, maxZoom)
        val roomX = ((fit.value.width * next - box.value.width) / 2f).coerceAtLeast(0f)
        val roomY = ((fit.value.height * next - box.value.height) / 2f).coerceAtLeast(0f)
        pan.value = if (next == 1f) Offset.Zero
        else Offset((pan.value.x + panChange.x).coerceIn(-roomX, roomX), (pan.value.y + panChange.y).coerceIn(-roomY, roomY))
        zoom.floatValue = next
    }
    // clipToBounds: pager pages do not clip, so a zoomed photo would spill over its neighbours
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { box.value = it }, contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize()
                // pointerInput must sit OUTSIDE graphicsLayer: inside it Compose hands out deltas
                // divided by the zoom scale, so panning would crawl at high zoom.
                .pointerInput(tile.id) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            if (zoomChange != 1f || zoom.floatValue > 1f) {
                                transform(zoomChange, event.calculatePan())
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(tile.id) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = {
                            // 1x -> 2.5x, then back out. Center anchored: good enough, keeps the maths short.
                            if (zoom.floatValue > 1f) transform(1f / zoom.floatValue, Offset.Zero) else transform(2.5f, Offset.Zero)
                        },
                    )
                }
                // Deferred read: pan and zoom touch the layer block only, no recomposition, so it stays smooth
                .graphicsLayer {
                    scaleX = zoom.floatValue
                    scaleY = zoom.floatValue
                    translationX = pan.value.x
                    translationY = pan.value.y
                },
        ) {
            image?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        if (tile.duration != null) {
            // ponytail: the phone's own player for now, Media3 player in phase 4
            IconButton({
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(tile.uri, context.contentResolver.getType(tile.uri))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { context.startActivity(view) }
            }, Modifier.size(72.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape)) {
                Icon(painterResource(R.drawable.ic_play), "Play", Modifier.size(40.dp), tint = Color.White)
            }
        }
    }
}
