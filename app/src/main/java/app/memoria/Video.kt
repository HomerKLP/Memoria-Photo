package app.memoria

import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one page whose view the player draws to. A page that stops being current clears the surface on its
 * way out; without knowing the owner that clear can land after the next page attached its own view, and
 * the video plays with sound and no picture.
 */
class VideoSurface {
    var owner: Long? = null // the tile id of the page the player draws for
}

/** May [id]'s page clear the player's surface? Yes when it owns it, or when this is a plain page (a test). */
internal fun ownsTexture(surface: VideoSurface?, id: Long) = surface == null || surface.owner == id

/** A file the decoder refuses (mockup "Video, cannot play"): a scrim over the frame, and the phone's player as the way out. */
@Composable
fun PlayerFailed(tile: Tile, onOpen: (() -> Unit)?) {
    val context = LocalContext.current
    // "1:24 · MP4 · 48 MB": the length at once, the file's type and size once Android tells them
    val facts by produceState(clock(tile.duration ?: 0L), tile.id) {
        value = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val type = runCatching { MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(tile.uri))?.uppercase() }.getOrNull()
            val size = runCatching {
                resolver.query(tile.uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else null }
            }.getOrNull()
            listOfNotNull(clock(tile.duration ?: 0L), type, size?.let { formatBytes(it) }).joinToString(" · ")
        }
    }
    Box(Modifier.fillMaxSize().background(viewerBg.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(painterResource(R.drawable.ic_video), null, Modifier.size(48.dp), tint = onViewer.copy(alpha = 0.3f))
            Text("This video can't play here", color = onViewer, style = MaterialTheme.typography.titleLarge)
            Text(
                if (onOpen == null) facts else "$facts\nThe phone's own player may still open it",
                color = onViewer.copy(alpha = 0.65f), fontSize = 13.sp, textAlign = TextAlign.Center,
            )
            onOpen?.let { PillButton("Open in the phone's player", it) }
        }
    }
}

/**
 * The video at its own shape in the middle of the screen, black around it: a TextureView stretches its
 * frames to its bounds, so the box takes the video's width / height and never distorts it.
 */
@Composable
fun VideoBox(aspect: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(if (aspect > 0f) aspect else 16f / 9f).testTag("videoBox")) { content() }
    }
}

/** A video's width / height as the player shows it, its pixel ratio applied. 0: nothing known yet. */
internal fun videoAspectOf(width: Int, height: Int, pixelRatio: Float = 1f): Float =
    if (width <= 0 || height <= 0) 0f else width * pixelRatio / height
