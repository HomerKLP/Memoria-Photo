package app.memoria

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Decoded face pictures, so scrolling the grids does not decode the same face again
internal val faceBitmaps = object : LruCache<Long, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
    override fun sizeOf(key: Long, value: ImageBitmap) = value.width * value.height * 4
}

@Composable
fun FaceImage(face: FaceVec, modifier: Modifier) {
    val context = LocalContext.current
    val image by produceState(faceBitmaps.get(face.id), face.id) {
        value = faceBitmaps.get(face.id) // produceState keeps the old value on a new key: drop the last face's picture
        // One face's picture at a time: loading all of them read ~25 MB on every screen open
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching { Db.get(context).index().thumb(face.id) }.getOrNull()?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }?.also { faceBitmaps.put(face.id, it) }
    }
    Box(modifier.background(Color.LightGray)) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** Widens a full-width row of the People grid over the grid's 16 dp sides: the row pads its own sides. */
internal fun Modifier.overSides() = layout { m, c ->
    val side = 16.dp.roundToPx()
    val p = m.measure(c.offset(horizontal = 2 * side))
    layout(c.maxWidth, p.height) { p.place(-side, 0) }
}

/**
 * The face of a friend picker (Merge with, Change cover): the person's circle, and while [picked] an
 * accent ring outside it plus the accent check at its top right (mockup `.pcard.on`). Both are drawn
 * outside the face, so picking never moves the grid.
 */
@Composable
internal fun PickFace(face: FaceVec, faceSize: Dp, picked: Boolean, lifted: Boolean = false) {
    val ring = MaterialTheme.colorScheme.primary
    Box {
        FaceImage(
            face,
            Modifier.drawBehind { if (picked) drawCircle(ring, size.minDimension / 2 + 4.5.dp.toPx(), style = Stroke(3.dp.toPx())) }
                .shadow(if (lifted) 8.dp else 0.dp, CircleShape).size(faceSize).clip(CircleShape),
        )
        if (picked) Box(
            Modifier.align(Alignment.TopEnd).testTag("pickCheck").size(22.dp).clip(CircleShape).background(ring),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp), tint = Color.White) }
    }
}

/**
 * Circular face, the name if any, and the photo count. 68 dp by default (Hidden people).
 * [tight] is the People screen's and Merge with's card (mockup `.tight`): 6 dp under the face and the
 * count right under the name, no gap. [showName] / [showCount] hide a line without losing the space,
 * for the unnamed grid (faces only). [picked]: accent ring and check (Merge with). [lifted]: being dragged, a bit bigger with a shadow (mockup `.pcard.lifted`).
 */
@Composable
internal fun PersonCard(
    p: Person,
    modifier: Modifier = Modifier,
    faceSize: Dp = 68.dp,
    picked: Boolean = false,
    tight: Boolean = false,
    showName: Boolean = true,
    showCount: Boolean = true,
    lifted: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        // no ripple once lifted: the held press would show as a grey box around the card
        modifier.graphicsLayer { if (lifted) { scaleX = 1.08f; scaleY = 1.08f } }
            .combinedClickable(interactionSource = null, indication = if (lifted) null else LocalIndication.current, onLongClick = onLongClick, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PickFace(p.cover, faceSize, picked, lifted)
        if (showName || showCount) Spacer(Modifier.height(if (tight) 6.dp else 8.dp))
        // 1.25 line height like the mockup: the plain Text default (a 24 sp line) left a big gap between name and count
        if (showName && !p.name.isNullOrBlank()) Text(p.name, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        if (showCount) Text("${p.photos}", fontSize = 12.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
