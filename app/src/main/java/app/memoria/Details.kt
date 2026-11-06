package app.memoria

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** What the details panel shows. [place] is the nearest town; coordinates are never shown. */
class PhotoInfo(
    val name: String,
    val folder: String,
    val size: Long,
    val width: Int,
    val height: Int,
    val place: String?,
    val camera: String?,
    val settings: String?,
    val mime: String = "",
    val orientation: Int = 0, // EXIF, 0 = none
)

/** Reads MediaStore columns and the camera EXIF. Blocking: call from IO. */
fun loadInfo(context: Context, tile: Tile): PhotoInfo {
    var name = ""
    var folder = ""
    var size = 0L
    var width = 0
    var height = 0
    var mime = ""
    var lat: Double? = null
    var lon: Double? = null
    val base = if (tile.duration == null) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val cols = arrayOf(
        MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
        MediaStore.MediaColumns.MIME_TYPE,
    )
    context.contentResolver.query(base, cols, "${MediaStore.MediaColumns._ID} = ?", arrayOf(tile.id.toString()), null)?.use { c ->
        if (c.moveToNext()) {
            name = c.getString(0).orEmpty()
            folder = c.getString(1).orEmpty().trimEnd('/')
            size = c.getLong(2)
            width = c.getInt(3)
            height = c.getInt(4)
            mime = c.getString(5).orEmpty()
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
        mime, exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) ?: 0,
    )
}

/**
 * The five rows (title, subtitle) of the debug section, per docs/design.html "viewer-debug": file, faces, tags, objects, index.
 * [photo] null = not indexed (videos too). [tags]: "id score;id score" from [photoTags]; [tagNames] maps a tag id to its name.
 */
internal fun debugRows(
    id: Long, info: PhotoInfo?, photo: PhotoRow?, faces: List<FaceVec>,
    vectorBytes: Int?, tags: String?, tagNames: Map<String, String>, boxes: List<ObjectBoxRow>,
): List<Pair<String, String>> {
    val file = "${info?.mime?.takeIf { it.isNotEmpty() } ?: "–"} · orientation ${info?.orientation?.takeIf { it > 0 } ?: "–"}" to "MediaStore id $id"
    if (photo == null) return listOf(file) + List(4) { "Not indexed" to "–" }
    val people = faces.filter { !it.pet }
    val ids = people.mapNotNull { it.personId }.distinct()
    val faceRow = (if (people.isEmpty()) "No faces" else "${people.size} ${if (people.size == 1) "face" else "faces"} · quality " + people.joinToString(", ") { "%.2f".format(it.quality) }) to
        if (ids.isEmpty()) "No person yet" else "People " + ids.joinToString(", ") { "#$it" }
    val tagRow = when (vectorBytes) {
        null -> "Tag vector: none yet" to "–"
        0 -> "Tag vector: empty" to "The photo could not be read"
        else -> "Tag vector: yes" to (tags.orEmpty().split(';').filter { it.isNotEmpty() }.map { it.split(' ') }
            .sortedByDescending { it[1].toFloat() }.take(3)
            .joinToString(" · ") { "${tagNames[it[0]] ?: it[0]} %.2f".format(it[1].toFloat()) }.ifEmpty { "No tags yet" })
    }
    val objectRow = if (photo.objects == null) "Not boxed yet" to "–" else
        listOf(PERSON to "person", CAT to "cat", DOG to "dog").mapNotNull { (cls, name) -> boxes.count { it.cls == cls }.takeIf { it > 0 }?.let { "$it $name" } }
            .joinToString(" · ").ifEmpty { "No boxes" } to
            (boxes.sortedByDescending { it.score }.joinToString(", ") { "%.2f".format(it.score) }.takeIf { it.isNotEmpty() }?.let { "Box scores $it" } ?: "–")
    val indexRow = "Indexed · ${photo.kind} photo" to (photo.objects?.let { "Objects model v$it" } ?: "Objects not run")
    return listOf(file, faceRow, tagRow, objectRow, indexRow)
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
 * Viewer details, per docs/design.html "Viewer, swiped up": the panel under the photo.
 * A face of a named person or of no one opens Who is this?; a face of an unnamed person ("Add name") names that person. Pets the same way.
 */
@Composable
fun DetailsPanel(tile: Tile, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { Db.get(context) }
    var info by remember(tile.id) { mutableStateOf<PhotoInfo?>(null) }
    var faces by remember(tile.id) { mutableStateOf(emptyList<FaceVec>()) }
    var names by remember(tile.id) { mutableStateOf(emptyMap<Long, String?>()) }
    var fixing by remember(tile.id) { mutableStateOf<FaceVec?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val debuggable = remember { context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
    var debug by remember(tile.id) { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(tile.id, reload) {
        withContext(Dispatchers.IO) {
            val dao = db.index()
            val info = runCatching { loadInfo(context, tile) }.getOrNull()
            // only this photo's faces and the people's names: no whole-library read
            // people left to right, like the photo, then pets
            val photoFaces = if (tile.duration != null) emptyList() else dao.viewerFaces(tile.id).sortedWith(compareBy({ it.pet }, { it.box[0] }))
            val names = if (photoFaces.isEmpty()) emptyMap() else dao.people().associate { it.id to it.name }
            Triple(info, photoFaces, names)
        }.let { (i, f, n) -> info = i; faces = f; names = n }
        if (debuggable) debug = withContext(Dispatchers.IO) {
            val dao = db.index()
            val tags = Tag.load(context)
            debugRows(
                tile.id, info, dao.photoRow(tile.id), faces, dao.vectorBytes(tile.id), dao.tagsOf(tile.id, Tag.stamp),
                tags.associate { it.id to it.name }, dao.boxesOf(tile.id),
            )
        }
    }
    // every saved person with their faces, read only when a face is tapped: the fix sheets list them
    var people by remember(tile.id, fixing) { mutableStateOf<List<Person>?>(null) }
    LaunchedEffect(fixing) {
        if (fixing == null) return@LaunchedEffect
        people = withContext(Dispatchers.IO) {
            val dao = db.index()
            val byPerson = dao.taggedFaces().groupBy { it.personId }
            dao.people().mapNotNull { p ->
                byPerson[p.id]?.let { fs ->
                    val main = fs.firstOrNull { it.id == p.mainFaceId } ?: fs.first()
                    Person(fs.map { it.toVec() }, main.toVec(), p.id, p.name, p.hidden, p.coverFaceId)
                }
            }.sortedWith(compareBy({ it.name.isNullOrBlank() }, { -it.photos }))
        }
    }
    // closes the sheet at once, then writes and reloads the row; NonCancellable, so closing the viewer right after keeps the write.
    // peopleSaved: the Person or Pet screen under the viewer reloads too, and closes if its last face left.
    val fix: (suspend () -> Unit) -> Unit = { write -> fixing = null; scope.launch { withContext(Dispatchers.IO + NonCancellable) { write(); peopleSaved.value++ }; reload++ } }
    val all = people
    if (all != null) fixing?.let { f ->
        val p = all.firstOrNull { it.id == f.personId }
        if (p != null && p.name.isNullOrBlank()) NamePersonSheet(p, onDismiss = { fixing = null }) { name ->
            fix { db.index().setName(p.id, name.trim().ifEmpty { null }) }
        } else WhoIsThisSheet(
            f, p, all,
            onDismiss = { fixing = null },
            onPick = { to -> fix { moveFace(db, f, p, to) } },
            onRemove = p?.let { { fix { removeTag(db, f, it) } } },
        ) { name -> fix { newPerson(db, f, p, name) } }
    }
    // "…" rows hold the space while the debug rows load, so nothing moves when they fill in
    DetailsContent(tile, info, faces, names, onFixFace = { fixing = it }, modifier, debug = if (debuggable) debug ?: List(5) { "…" to "…" } else null)
}

/** The panel's body, split from the load so previews and layout tests can render it. Light text for the viewer's black. */
@Composable
fun DetailsContent(
    tile: Tile,
    info: PhotoInfo?,
    faces: List<FaceVec>,
    names: Map<Long, String?>,
    onFixFace: (FaceVec) -> Unit = {},
    modifier: Modifier = Modifier,
    debug: List<Pair<String, String>>? = null, // debug builds only, five rows from [debugRows]; null = no section
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val time = Instant.ofEpochMilli(tile.date).atZone(zone)
    CompositionLocalProvider(LocalContentColor provides onViewer) { Column(modifier.navigationBarsPadding().padding(bottom = 24.dp)) {
        Box(
            Modifier.padding(top = 10.dp).size(32.dp, 4.dp).align(Alignment.CenterHorizontally)
                .background(detailsMuted.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
        )
        Text(
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy").format(time),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        Text(
            "${DateTimeFormatter.ofPattern("HH:mm").format(time)} · ${zoneLabel(time.offset)}",
            color = detailsMuted, fontSize = 13.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
        )
        if (faces.isNotEmpty()) {
            Section(if (faces.none { it.pet }) "People" else if (faces.all { it.pet }) "Pets" else "People & pets", "Tap one to fix it")
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                faces.forEach { f ->
                    val name = f.personId?.let { names[it] }?.takeIf { it.isNotBlank() }
                    Column(Modifier.width(72.dp).clickable { onFixFace(f) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        FaceImage(f, Modifier.size(72.dp).clip(CircleShape))
                        Text(name ?: "Add name", Modifier.padding(top = 6.dp), fontSize = 13.sp, color = if (name == null) detailsMuted else Color.Unspecified, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        debug?.let { rows ->
            Section("Debug", "Debug builds only")
            listOf(R.drawable.ic_photo, R.drawable.ic_person, R.drawable.ic_search, R.drawable.ic_paw, R.drawable.ic_history)
                .zip(rows).forEach { (icon, row) -> Line(icon, row.first, row.second) }
        }
    } }
}

private val detailsMuted = onViewer.copy(alpha = 0.62f)

@Composable
private fun Section(title: String, hint: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (hint != null) Text(hint, fontSize = 12.sp, color = detailsMuted)
    }
}

@Composable
private fun Line(icon: Int, title: String, subtitle: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(painterResource(icon), null, Modifier.padding(top = 2.dp), tint = detailsMuted)
        Column {
            Text(title)
            subtitle?.takeIf { it.isNotEmpty() }?.let { Text(it, color = detailsMuted, fontSize = 13.sp) }
        }
    }
}
