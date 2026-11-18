package app.memoria

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val maxZoom = 6f
// Double-tap zoom: short, eased, in the viewer only
private const val zoomMillis = 250

/** How big the photo really is on screen with ContentScale.Fit, so panning stops at its edges. */
internal fun fitSize(imageWidth: Int, imageHeight: Int, boxWidth: Int, boxHeight: Int): Size {
    if (imageWidth <= 0 || imageHeight <= 0 || boxWidth == 0 || boxHeight == 0) return Size(0f, 0f)
    val s = minOf(boxWidth.toFloat() / imageWidth, boxHeight.toFloat() / imageHeight)
    return Size(imageWidth * s, imageHeight * s)
}

/** The biggest box of [aspect] (width / height) that fits [box], centered: where a video sits on screen. Same size as [VideoBox] lays out. */
internal fun fitOf(aspect: Float, box: IntSize): Size {
    if (aspect <= 0f || box.width <= 0 || box.height <= 0) return Size(0f, 0f)
    val h = minOf(box.height.toFloat(), box.width / aspect)
    return Size(h * aspect, h)
}

private fun fitSize(bitmap: Bitmap?, box: IntSize): Size =
    if (bitmap == null) Size(0f, 0f) else fitSize(bitmap.width, bitmap.height, box.width, box.height)

/** The photo's halving step for the viewer: the short side stays at least the [screen]'s, the long side within the 4096 px GPU limit. */
internal fun viewerSample(w: Int, h: Int, screen: Int): Int {
    var s = 1
    while (minOf(w, h) / (s * 2) >= screen || maxOf(w, h) / s > 4096) s *= 2
    return s
}

/**
 * The photo at about screen size, as a hardware bitmap so it uploads here, not on the render thread mid-animation.
 * Halving lets the JPEG decoder skip work; the old exact 2560 px decoded a 12 MP photo whole (250 ms).
 */
internal fun viewerPhoto(context: Context, tile: Tile): Bitmap {
    val (source, turn) = plainSource(context.contentResolver, tile.uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val screen = context.resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) }
        decoder.setTargetSampleSize(viewerSample(info.size.width, info.size.height, screen))
    }.turnedBy(turn)
}

/**
 * A sharp first frame of the video: the poster the player starts over. The first, not "any" (-1), which may be
 * another frame, so the picture does not change when playback takes over.
 */
internal fun videoPoster(context: Context, tile: Tile): Bitmap? = runCatching {
    MediaMetadataRetriever().use {
        it.setDataSource(context, tile.uri)
        it.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1440, 1440)
    }
}.getOrNull()

/**
 * Pan that keeps the [focus] point (double tap, or the pinch's finger center) in place while zooming
 * from [from] with [pan] to [target]. Pan is measured from the centered photo (0 = centered), so a tap
 * right of center pans left to bring that side in. Clamped to the photo's pan room, so a tap near an edge is fine.
 */
internal fun zoomToFocusPan(fit: Size, box: Size, target: Float, focus: Offset, from: Float = 1f, pan: Offset = Offset.Zero): Offset {
    if (target <= 1f) return Offset.Zero
    val k = target / from
    val p = pan * k + (focus - Offset(box.width / 2f, box.height / 2f)) * (1f - k)
    val roomX = ((fit.width * target - box.width) / 2f).coerceAtLeast(0f)
    val roomY = ((fit.height * target - box.height) / 2f).coerceAtLeast(0f)
    return Offset(p.x.coerceIn(-roomX, roomX), p.y.coerceIn(-roomY, roomY))
}

/** True when the photo's grid tile is at least partly on screen. */
internal fun onScreen(id: Long): Boolean = tileSpots[id]?.takeIf { it.isAttached }?.boundsInWindow()?.isEmpty == false

/**
 * Where the photo is drawn and what part of it shows, between its grid [tile] ([t] = 0, cropped to the
 * tile's [visible] part) and full screen ([t] = 1). [dragY] pulls the full-screen photo down and shrinks it.
 * [reveal] = 1: the crop is gone and the whole photo shows.
 */
internal fun photoMotion(fit: Size, screen: Size, tile: Rect?, visible: Rect?, t: Float, dragY: Float, reveal: Float = t): Pair<Rect, Rect?> {
    fun scaled(center: Offset, k: Float) = Rect(center - Offset(fit.width * k / 2f, fit.height * k / 2f), fit * k)
    val dragged = scaled(Offset(screen.width / 2f, screen.height / 2f + dragY), 1f - 0.3f * (dragY / (screen.height / 2f)).coerceIn(0f, 1f))
    if (tile == null || visible == null || t >= 1f) return dragged to null
    val crop = scaled(tile.center, max(tile.width / fit.width, tile.height / fit.height))
    val rect = lerp(crop, dragged, t)
    return rect to lerp(visible, rect, reveal)
}

// The crop opens in the first part of the open, then the whole photo grows: a crop widening all the way looked cheap
private const val cropOpensBy = 0.4f

// ImageDecoder ignores cancel: two decodes at a time, so pages swiped past wait and drop out instead of decoding
private val viewerLoads = Dispatchers.IO.limitedParallelism(2)

@Composable
fun ViewerPage(
    tile: Tile,
    onZoomed: (Boolean) -> Unit,
    onTap: () -> Unit = {},
    shown: () -> Float = { 1f },
    dragY: () -> Float = { 0f },
    details: () -> Float = { 0f },
    fly: Boolean = false,
    player: ExoPlayer? = null,
    surface: VideoSurface? = null, // the page that owns the player's view, so a leaving page cannot clear it
    current: Boolean = false, // the player holds this page's video: its picture shows here and moves with the page
    aspect: Float = 0f, // the video's width / height as the player reports it, 0 until it knows
    pill: Int = 0, // +5 s / −5 s, shown on the half that was tapped
    holding: Boolean = false, // 2x while a finger is down
    onSkip: (Int) -> Unit = {},
    onHold: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    // A video has no pinch or double-tap zoom: the fingers are for the player (mockup "Player")
    val isVideo = tile.duration != null
    // Grid thumbnail first, then a screen-size decode
    val thumb = remember(tile.id) { thumbs.get(tile.id) }
    val image by produceState(thumb, tile.id) {
        val full = withContext(viewerLoads) {
            // MediaStore keeps only a small video thumbnail, so take a sharp frame from the file
            if (tile.duration != null) videoPoster(context, tile) else runCatching { viewerPhoto(context, tile) }.getOrNull()
        }
        if (full != null) value = full
    }
    val sharp = remember(tile.id) { Animatable(0f) }
    LaunchedEffect(image) { if (image !== thumb) sharp.animateTo(1f, tween(150)) }
    // Zoom state lives here: a zoomed photo cannot be swiped away, so the page is never stale.
    val zoom = remember { mutableFloatStateOf(1f) }
    val pan = remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    var zoomJob by remember { mutableStateOf<Job?>(null) }
    val box = remember { mutableStateOf(IntSize.Zero) }
    // The page's shape: a video's own width / height, from the player once it knows it, else the poster's
    // own shape (MediaStore has no ratio for some files), so the box never changes size once it shows.
    val posterAspect = image?.let { it.width.toFloat() / it.height }
    val boxAspect = when {
        !isVideo -> 0f
        aspect > 0f -> aspect
        posterAspect != null -> posterAspect
        else -> 16f / 9f
    }
    fun pageFit(box: IntSize) = if (isVideo) fitOf(boxAspect, box) else fitSize(image, box)
    val fit = remember { mutableStateOf(Size.Zero) }
    LaunchedEffect(image, box.value, boxAspect) { fit.value = pageFit(box.value) }
    // snapshotFlow, not LaunchedEffect(zoom): reporting must not recompose the viewer on every pinch event
    LaunchedEffect(Unit) { snapshotFlow { zoom.floatValue > 1f }.distinctUntilChanged().collect(onZoomed) }
    // The details square shows the photo at 1x: opening them drops a zoom, and pinching waits until they close
    val detailsNow by rememberUpdatedState(details)
    fun transform(zoomChange: Float, panChange: Offset, focus: Offset = Offset.Zero) {
        // A real pinch or pan takes over from a running double-tap zoom. A bare tap (no movement) must
        // not: its down and up both land here just to clamp the pan, and cancelling would kill the
        // zoom-out animation the same tap just started.
        if (zoomChange != 1f || panChange != Offset.Zero) zoomJob?.cancel()
        val next = if (detailsNow() > 0f) 1f else (zoom.floatValue * zoomChange).coerceIn(1f, maxZoom)
        val boxSize = Size(box.value.width.toFloat(), box.value.height.toFloat())
        pan.value = zoomToFocusPan(fit.value, boxSize, next, focus, zoom.floatValue, pan.value + panChange)
        zoom.floatValue = next
    }
    // Double tap eases to the target, and slides the pan to keep the tapped point under the finger.
    // Zooming back out (target 1x) returns the pan to center.
    fun animateZoomTo(target: Float, focus: Offset?) {
        zoomJob?.cancel()
        val startZoom = zoom.floatValue
        val startPan = pan.value
        val endPan = if (target <= 1f || focus == null) Offset.Zero
        else zoomToFocusPan(fit.value, Size(box.value.width.toFloat(), box.value.height.toFloat()), target, focus)
        zoomJob = scope.launch {
            animate(0f, 1f, animationSpec = tween(zoomMillis, easing = FastOutSlowInEasing)) { t, _ ->
                zoom.floatValue = startZoom + (target - startZoom) * t
                pan.value = startPan + (endPan - startPan) * t
            }
        }
    }
    LaunchedEffect(Unit) { snapshotFlow { detailsNow() > 0f }.collect { if (it) { zoomJob?.cancel(); transform(1f, Offset.Zero) } } }
    var page by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // The photo's rect and clip right now. No clip: no tile to fly from, so it fades with [shown].
    fun motion(fit: Size, screen: Size): Pair<Rect, Rect?> {
        // details out: the photo moves into a full-width square at the top, zoomed to fill it. Sideways
        // the panel is a sheet over the photo instead, so the photo keeps the whole screen (mockup).
        val d = details()
        if (d > 0f && screen.width <= screen.height) {
            val square = Rect(0f, 0f, screen.width, screen.width)
            return photoMotion(fit, screen, square, square, 1f - d, 0f)
        }
        val spot = tileSpots[tile.id]?.takeIf { fly && it.isAttached }
        val here = page?.takeIf { it.isAttached }
        // localBoundingBoxOf throws when the two nodes do not share a hierarchy. The tile can leave the
        // composition while the viewer opens (the grid follows the photo), so this is a real case: no
        // tile to fly from, fade instead of crashing.
        fun bounds(from: LayoutCoordinates, to: LayoutCoordinates, clip: Boolean): Rect? =
            try { from.localBoundingBoxOf(to, clipBounds = clip) } catch (_: IllegalArgumentException) { null }
        val visible = if (spot != null && here != null) bounds(here, spot, clip = true) else null
        if (visible == null || visible.isEmpty) return photoMotion(fit, screen, null, null, 1f, dragY())
        val full = bounds(here!!, spot!!, clip = false) ?: return photoMotion(fit, screen, null, null, 1f, dragY())
        return photoMotion(fit, screen, full, visible, shown(), dragY(), reveal = (shown() / cropOpensBy).coerceAtMost(1f))
    }
    Box(
        Modifier.fillMaxSize()
            .onPlaced { page = it }
            .drawWithContent {
                val fit = pageFit(IntSize(size.width.toInt(), size.height.toInt()))
                val clip = if (fit.width == 0f) null else motion(fit, size).second
                if (clip == null) drawContent() else clipRect(clip.left, clip.top, clip.right, clip.bottom) { this@drawWithContent.drawContent() }
            }
            .graphicsLayer {
                val fit = pageFit(IntSize(size.width.toInt(), size.height.toInt()))
                if (fit.width == 0f) { alpha = shown(); return@graphicsLayer }
                val (rect, clip) = motion(fit, size)
                // a clip means the photo flies from its tile, unless the details made it (then it fades on close)
                alpha = if (clip != null && details() == 0f) 1f else shown()
                scaleX = rect.width / fit.width
                scaleY = scaleX
                translationX = rect.center.x - size.width / 2f
                translationY = rect.center.y - size.height / 2f
            }
            // clipToBounds: pager pages do not clip, so a zoomed photo would spill over its neighbours
            .clipToBounds()
            .onSizeChanged { box.value = it },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize()
                // pointerInput must sit OUTSIDE graphicsLayer: inside it Compose hands out deltas
                // divided by the zoom scale, so panning would crawl at high zoom.
                .pointerInput(tile.id, isVideo) {
                    if (isVideo) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            if (zoomChange != 1f || zoom.floatValue > 1f) {
                                // zoom around the fingers; no finger down (the last up) has no center, and no zoom then
                                val focus = event.calculateCentroid()
                                transform(zoomChange, event.calculatePan(), if (focus.isSpecified) focus else Offset.Zero)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(tile.id, isVideo) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { offset ->
                            if (isVideo) onSkip(if (offset.x > size.width / 2f) 1 else -1)
                            // 1x -> 2.5x zoomed toward the tapped point; 2.5x -> 1x back to center
                            else if (zoom.floatValue > 1f) animateZoomTo(1f, offset) else animateZoomTo(2.5f, offset)
                        },
                    )
                }
                // Hold: 2x while the finger stays down, back to 1x on the way up (mockup "Video, held")
                .pointerInput(tile.id, isVideo) {
                    if (!isVideo) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        // null only when the time ran out: a swipe that took the finger early also ends the wait,
                        // and counting it as a hold flashed the 2x pill at the start of every drag
                        val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation(); true }
                        if (ended == null) {
                            onHold(true)
                            // Consume the release: the tap detector above waits for it and would fire a single
                            // tap on the lift, so the bars slid open every time a 2x hold ended
                            try { waitForUpOrCancellation()?.consume() } finally { onHold(false) }
                        }
                    }
                }
                // Deferred read: pan and zoom touch the layer block only, no recomposition, so it stays smooth
                .graphicsLayer {
                    scaleX = zoom.floatValue
                    scaleY = zoom.floatValue
                    translationX = pan.value.x
                    translationY = pan.value.y
                },
        ) {
            // the sharp decode fades in over the thumbnail, which stays opaque so the grid never shows through mid-zoom
            val poster = @Composable {
                thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                if (image !== thumb) image?.let {
                    Image(it.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer { alpha = sharp.value }, contentScale = ContentScale.Fit)
                }
            }
            if (!isVideo) poster() else VideoBox(boxAspect) {
                poster()
                // The video plays on the phone's decoder over the poster frame, on the one page whose file the
                // player holds; a neighbour keeps its poster. Compose draws a TextureView inside its own layers,
                // so the video grows, shrinks, slides and clips with the page like a photo (a SurfaceView would not).
                if (current && player != null) AndroidView(
                    factory = { TextureView(it) },
                    modifier = Modifier.fillMaxSize().testTag("videoTexture"),
                    // The page that stops being current is released too, and its clear could land after the
                    // next page attached its own view: the video then played with sound and no picture as you
                    // swiped (on the phone 2026-11-12). Only the owning page may clear the surface.
                    onRelease = {
                        if (ownsTexture(surface, tile.id)) {
                            runCatching { player.clearVideoSurface() }
                            surface?.owner = null
                        }
                    },
                ) { view -> surface?.owner = tile.id; player.setVideoTextureView(view) }
                // The grid tile shows MediaStore's frame (from the middle of the clip), not the one playing, so
                // the video landed on another picture and popped on close. The tile's own picture fades in over
                // the second half of the way to it, and out over the first half of an open.
                thumb?.let {
                    Image(
                        it.asImageBitmap(), null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = (1f - shown() * 2f).coerceIn(0f, 1f) },
                    )
                }
            }
        }
        if (pill != 0) GesturePill(
            if (pill > 0) "+5 s" else "−5 s",
            Modifier.align(if (pill > 0) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 40.dp),
        )
        if (holding) GesturePill("2x", Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
    }
}

/** The short feedback of a video gesture: +5 s / −5 s on the tapped half, 2x at the top while held (mockup "Player"). */
@Composable
private fun GesturePill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.background(viewerBg.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 10.dp),
        color = onViewer,
        style = MaterialTheme.typography.titleMedium,
    )
}
