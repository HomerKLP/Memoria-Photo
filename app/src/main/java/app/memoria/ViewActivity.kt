package app.memoria

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Open with from another app: one photo or video, and Back returns to that app (mockup "Viewer, from another app"). */
// a FragmentActivity: a vault made by the first builds needs the phone's sheet once to lock into it
class ViewActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(bars, bars)
        super.onCreate(savedInstanceState)
        // the phone's fastest mode, like MainActivity
        window.attributes = window.attributes.apply {
            val now = display.mode
            preferredDisplayModeId = display.supportedModes
                .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
                .maxBy { it.refreshRate }.modeId
        }
        val uri = intent.data ?: run { finish(); return }
        val type = intent.type ?: contentResolver.getType(uri)
        setContent {
            MemoriaTheme {
                val opened by produceState<Pair<Tile, Boolean>?>(null) {
                    value = withContext(Dispatchers.IO) { openedTile(this@ViewActivity, uri, type) }
                }
                val lock = rememberVaultLock { locked -> forgetTiles(locked.map { it.id }.toSet()); finish() }
                Box(Modifier.fillMaxSize().background(viewerBg)) {
                    opened?.let { (tile, foreign) ->
                        Viewer(tile.id, listOf(tile), onBack = ::finish, foreign = foreign, onLock = { lock(listOf(it)) })
                    }
                }
            }
        }
    }
}

// a photo picker URI is under "media" too, but its number is not a MediaStore id
internal val libraryPath = Regex("/[^/]+/(images/media|video/media|file)/\\d+")

/** The library's own tile for [uri], or another app's file (second = true): id 0 and the date from the file. Blocking. */
internal fun openedTile(context: Context, uri: Uri, type: String?): Pair<Tile, Boolean> {
    val media = when {
        uri.authority == MediaStore.AUTHORITY -> uri
        // Files and the phone's own pickers: MediaStore knows the same file under its own id
        DocumentsContract.isDocumentUri(context, uri) -> runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
        else -> null
    }
    // no Photos access yet: MediaStore lists nothing, so the file opens as another app's
    val id = media?.takeIf { libraryPath.matches(it.path.orEmpty()) }?.lastPathSegment?.toLongOrNull()
    id?.let { photosByIds(context, listOf(it)).firstOrNull() }?.let { return it to false }
    val zone = ZoneId.systemDefault()
    val tile = if (type?.startsWith("video/") == true) {
        val (date, length) = runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(context, uri)
                videoDate(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)) to
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }
        }.getOrDefault(0L to null)
        Tile(0, uri, date, length ?: 0L) // a video always has a duration, so the viewer plays it
    } else {
        val exif = runCatching { context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()
        val at = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif?.getAttribute(ExifInterface.TAG_DATETIME)
        Tile(0, uri, exifDate(at, exif?.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL), zone), null)
    }
    return tile to true
}

private val exifFormat = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

/** EXIF's "2025:09:01 00:32:22" at its [offset] ("+03:00"), or in [zone] without one, like MediaStore. 0: no date. */
internal fun exifDate(at: String?, offset: String?, zone: ZoneId): Long {
    val local = runCatching { LocalDateTime.parse(at!!.trim().take(19), exifFormat) }.getOrNull() ?: return 0L
    val off = runCatching { ZoneOffset.of(offset!!.trim()) }.getOrNull()
    return (if (off != null) local.atOffset(off).toInstant() else local.atZone(zone).toInstant()).toEpochMilli()
}

private val videoFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

/** MediaMetadataRetriever's "20250901T003222.000Z", in UTC. 0: none, or the 1904 a camera writes for no date. */
internal fun videoDate(s: String?): Long =
    runCatching { LocalDateTime.parse(s!!.take(15), videoFormat).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrDefault(0L).coerceAtLeast(0L)
