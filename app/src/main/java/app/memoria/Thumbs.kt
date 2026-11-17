package app.memoria

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

// Bitmap pixels live outside the Java heap, so half its limit (128 MB here, ~15 screens of tiles) is safe.
val thumbs = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 2).toInt()) {
    override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
}

@Composable
fun TileImage(tile: Tile, modifier: Modifier, selecting: Boolean = false, selected: Boolean = false, heart: Boolean = true, onTop: () -> Boolean = { true }) {
    // The Photos tab stays composed under an opened screen: its copy of a tile must not take the spot the viewer lands in
    val top = onTop()
    val spot = remember { arrayOfNulls<LayoutCoordinates>(1) }
    DisposableEffect(tile.id, top) {
        if (top) spot[0]?.let { tileSpots[tile.id] = it }
        onDispose { spot[0]?.let { tileSpots.remove(tile.id, it) } }
    }
    Box(
        modifier.aspectRatio(1f)
            .onPlaced { spot[0] = it; if (top) tileSpots[tile.id] = it }
            .drawWithContent { if (viewerTile != tile.id) drawContent() }
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

/** True when a [width] x [height] picture has the photo's [ratio] (5% slack for rounding), or the ratio is unknown. */
internal fun sameShape(width: Int, height: Int, ratio: Float): Boolean = ratio <= 0f || abs(width.toFloat() / height / ratio - 1f) < 0.05f

// At most 4 thumbnail loads at once, so a fling can't bury the tiles on screen.
private val thumbLoads = Dispatchers.IO.limitedParallelism(4)

/** Thumbnail picture with the video length badge. Callers stack their own overlay on top. */
@Composable
fun Thumb(tile: Tile, modifier: Modifier) {
    val context = LocalContext.current
    // keyed: a slot that gets another tile (Library rows reorder during a scan) must not keep the old photo
    val image by key(tile.id) { produceState(thumbs.get(tile.id), tile.id) {
        if (value != null) return@produceState
        // A tab-tap scroll to the top: a tile only flying by waits instead of decoding, so the fly-by
        // does not load (or drop frames on) photos you will scroll past anyway.
        if (fastScrolling) snapshotFlow { fastScrolling }.first { !it }
        val signal = CancellationSignal()
        launch(thumbLoads) {
            val resolver = context.contentResolver
            // a tile is a third of the screen less the 1 dp gaps
            val tilePx = context.resources.displayMetrics.let { (it.widthPixels - 2 * it.density) / 3 }
            val thumb = runCatching { resolver.loadThumbnail(tile.uri, Size(tilePx.toInt(), tilePx.toInt()), signal) }.getOrNull()
            var turn = 1
            var own = false
            if (thumb != null && tile.duration == null) {
                val type = runCatching { resolver.getType(tile.uri) }.getOrNull()
                turn = photoTurn(resolver, tile.uri, type)
                val heif = type == "image/heic" || type == "image/heif"
                // Decode the photo itself when MediaStore's picture is:
                // - under 200 px (old cameras: the 160 px EXIF preview), or under a tile / 1.2 (a camera's 320x240 preview, soft in a tile; not HEIC, which is slow to decode, see bugs.md)
                // - of another shape (some old cameras wrote a middle cut, which shows around the photo in the viewer)
                // - a DNG or HEIC with a turn or mirror tag (phones get those wrong: Android shows a DNG as stored and drops a HEIC's mirror)
                val short = min(thumb.width, thumb.height)
                own = short < 200 || (!heif && short < tilePx / 1.2f) || !sameShape(thumb.width, thumb.height, tile.ratio) || turn != 1
            }
            value = (if (own) runCatching { tileBitmap(context, tile) }.getOrNull() ?: thumb else thumb)
                ?.also { thumbs.put(tile.id, it) }
        }
        // The tile left the screen: stop its load
        awaitDispose { signal.cancel() }
    } }
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

/**
 * The grid tile's own decode, when MediaStore's picture is too small or wrongly shaped: about a third of
 * the screen, halving only (an exact size makes Skia keep every n-th pixel, which looks pixelated).
 */
internal fun tileBitmap(context: Context, tile: Tile): Bitmap {
    val (source, turn) = plainSource(context.contentResolver, tile.uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.setTargetSampleSize(viewerSample(info.size.width, info.size.height, context.resources.displayMetrics.widthPixels / 3))
    }.turnedBy(turn)
}
