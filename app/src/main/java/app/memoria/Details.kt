package app.memoria

import android.content.Context
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** What the details sheet shows. [place] is the nearest town; coordinates are never shown. */
class PhotoInfo(
    val name: String,
    val folder: String,
    val size: Long,
    val width: Int,
    val height: Int,
    val place: String?,
    val camera: String?,
    val settings: String?,
)

/** Reads MediaStore columns and the camera EXIF. Blocking: call from IO. */
fun loadInfo(context: Context, tile: Tile): PhotoInfo {
    var name = ""
    var folder = ""
    var size = 0L
    var width = 0
    var height = 0
    var lat: Double? = null
    var lon: Double? = null
    val base = if (tile.duration == null) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val cols = arrayOf(
        MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
    )
    context.contentResolver.query(base, cols, "${MediaStore.MediaColumns._ID} = ?", arrayOf(tile.id.toString()), null)?.use { c ->
        if (c.moveToNext()) {
            name = c.getString(0).orEmpty()
            folder = c.getString(1).orEmpty().trimEnd('/')
            size = c.getLong(2)
            width = c.getInt(3)
            height = c.getInt(4)
        }
    }
    // MediaStore's latitude/longitude are always null since Android 10; GPS is only in the original file
    val original = runCatching { MediaStore.setRequireOriginal(tile.uri) }.getOrDefault(tile.uri)
    // Videos have no camera EXIF
    val exif = if (tile.duration != null) null
    else runCatching { context.contentResolver.openInputStream(original)?.use { ExifInterface(it) } }.getOrNull()
        ?: runCatching { context.contentResolver.openInputStream(tile.uri)?.use { ExifInterface(it) } }.getOrNull()
    val ll = FloatArray(2)
    if (exif != null) { if (exif.getLatLong(ll)) { lat = ll[0].toDouble(); lon = ll[1].toDouble() } }
    else if (tile.duration != null) runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(context, original)
            iso6709(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))?.let { lat = it.first; lon = it.second }
        }
    }
    if (lat == 0.0 && lon == 0.0) { lat = null; lon = null } // some cameras write 0,0 when they have no GPS
    val la = lat
    val lo = lon
    val place = if (la != null && lo != null) Towns.load(context).let { it.name[it.nearest(la, lo)] } else null
    val f = exif?.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0) ?: 0.0
    val exposure = exif?.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0) ?: 0.0
    val iso = exif?.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0) ?: 0
    val focal = exif?.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0) ?: 0.0
    val settings = buildList {
        if (f > 0) add("f/%.1f".format(f))
        if (exposure > 0) add(if (exposure < 1) "1/%.0f s".format(1 / exposure) else "%.1f s".format(exposure))
        if (iso > 0) add("ISO $iso")
        if (focal > 0) add("%.0f mm".format(focal))
    }.joinToString(" · ").takeIf { it.isNotEmpty() }
    return PhotoInfo(
        name, folder, size, width, height, place,
        exif?.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() }, settings,
    )
}

/** Video GPS, ISO 6709 like "+37.4219-122.0840/" (an altitude may follow). */
internal fun iso6709(s: String?): Pair<Double, Double>? {
    val m = Regex("""^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(s ?: return null) ?: return null
    return m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
}

internal fun sizeLabel(bytes: Long) = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1e3)
    else -> "$bytes B"
}

/** "GMT+1", like the mockup. */
internal fun zoneLabel(offset: ZoneOffset): String {
    val seconds = offset.totalSeconds
    // parentheses: without them Kotlin's `if` swallows the rest of the line and a western zone prints "GMT-"
    return "GMT" + (if (seconds < 0) "-" else "+") + abs(seconds / 3600) + if (seconds % 3600 == 0) "" else ":%02d".format(abs(seconds % 3600) / 60)
}

/**
 * Viewer details, per docs/design.html (viewer swiped up): date, people, place, details.
 * A face of a named person or of no one opens Who is this?; a face of an unnamed person ("Add name") names that person.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(tile: Tile, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { Db.get(context) }
    var info by remember(tile.id) { mutableStateOf<PhotoInfo?>(null) }
    var faces by remember(tile.id) { mutableStateOf(emptyList<FaceVec>()) }
    var people by remember(tile.id) { mutableStateOf(emptyList<Person>()) }
    var loaded by remember(tile.id) { mutableStateOf(false) }
    var fixing by remember(tile.id) { mutableStateOf<FaceVec?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(tile.id, reload) {
        withContext(Dispatchers.IO) {
            val dao = db.index()
            val all = if (tile.duration != null) emptyList() else dao.faceVecs() // videos are not indexed
            val byPerson = all.filter { it.personId != null }.groupBy { it.personId!! }
            Triple(
                runCatching { loadInfo(context, tile) }.getOrNull(),
                all.filter { it.photoId == tile.id }.sortedBy { it.box[0] }, // left to right, like the photo
                dao.people().mapNotNull { p -> byPerson[p.id]?.let { fs -> Person(fs, fs.firstOrNull { it.id == p.mainFaceId } ?: fs.first(), p.id, p.name, p.hidden) } }
                    .sortedWith(compareBy({ it.name.isNullOrBlank() }, { -it.photos })),
            )
        }.let { (i, f, p) -> info = i; faces = f; people = p }
        loaded = true
    }
    // closes the sheet at once, then writes and reloads the row
    val fix: (suspend () -> Unit) -> Unit = { write -> fixing = null; scope.launch { withContext(Dispatchers.IO) { write() }; reload++ } }
    fixing?.let { f ->
        val p = people.firstOrNull { it.id == f.personId }
        if (p != null && p.name.isNullOrBlank()) NamePersonSheet(p, onDismiss = { fixing = null }) { name ->
            fix { db.index().setName(p.id, name.trim().ifEmpty { null }) }
        } else WhoIsThisSheet(
            f, p, people,
            onDismiss = { fixing = null },
            onPick = { to -> fix { moveFace(db, f, p, to) } },
            onRemove = p?.let { { fix { removeTag(db, f, it) } } },
        ) { name -> fix { newPerson(db, f, p, name) } }
    }
    // open only once loaded: a sheet that grows after it opens jumps
    if (!loaded) return
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        DetailsContent(tile, info, faces, people, onFixFace = { fixing = it })
    }
}

/** The sheet's body, split from the load and the sheet so previews and layout tests can render it. */
@Composable
fun DetailsContent(
    tile: Tile,
    info: PhotoInfo?,
    faces: List<FaceVec>,
    people: List<Person>,
    onFixFace: (FaceVec) -> Unit = {},
) {
    val time = Instant.ofEpochMilli(tile.date).atZone(ZoneId.systemDefault())
    Column(Modifier.navigationBarsPadding().padding(bottom = 24.dp)) {
        Text(
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy").format(time),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
        )
        Text(
            "${DateTimeFormatter.ofPattern("HH:mm").format(time)} · ${zoneLabel(time.offset)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
        )
        if (faces.isNotEmpty()) {
            Section("People", "Tap a face to fix it")
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                faces.forEach { f ->
                    val name = people.firstOrNull { it.id == f.personId }?.name?.takeIf { it.isNotBlank() }
                    Column(Modifier.width(72.dp).clickable { onFixFace(f) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        FaceImage(f, Modifier.size(72.dp).clip(CircleShape))
                        Text(name ?: "Add name", Modifier.padding(top = 6.dp), fontSize = 13.sp, color = if (name == null) muted else Color.Unspecified, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        info?.let { i ->
            i.place?.let {
                Section("Place")
                Line(R.drawable.ic_pin, it)
            }
            Section("Details")
            i.camera?.let { Line(R.drawable.ic_camera, it, i.settings) }
            if (i.name.isNotEmpty()) {
                val facts = listOfNotNull(
                    "%.1f MP".format(i.width * i.height / 1e6).takeIf { i.width > 0 },
                    "${i.width} × ${i.height}".takeIf { i.width > 0 },
                    sizeLabel(i.size).takeIf { i.size > 0 },
                )
                Line(R.drawable.ic_photo, i.name, facts.joinToString(" · "))
            }
            if (i.folder.isNotEmpty()) Line(R.drawable.ic_folder, i.folder.substringAfterLast('/'), i.folder)
        }
    }
}

@Composable
private fun Section(title: String, hint: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (hint != null) Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Line(icon: Int, title: String, subtitle: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(painterResource(icon), null, Modifier.padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text(title)
            subtitle?.takeIf { it.isNotEmpty() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
        }
    }
}
