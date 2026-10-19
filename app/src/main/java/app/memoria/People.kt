package app.memoria

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.withTransaction
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A saved face with the kind of its photo. [yes]: the user said it is its person. [rejected]: the user said no to some person. */
class FaceVec(
    val id: Long, val photoId: Long, val box: FloatArray, val vector: FloatArray, val quality: Float, val kind: String, val personId: Long?,
    val yes: Boolean = false, val rejected: Boolean = false,
) {
    /** Unsure faces wait in Help recognize. A face moved off a person by a no landed by distance, so it is asked again. */
    val sure get() = yes || (quality >= 0.4f && !rejected)
}

/**
 * A face that belongs to a person, without its 512-float vector (see `IndexDao.taggedFaces`).
 * The viewer details panel shows names, covers and counts; it never matches by distance, so the vector is not read.
 */
class LightFace(
    val id: Long, val photoId: Long, val box: FloatArray, val quality: Float, val kind: String, val personId: Long,
    val yes: Boolean = false, val rejected: Boolean = false,
) {
    fun toVec() = FaceVec(id, photoId, box, FloatArray(0), quality, kind, personId, yes, rejected)
}

class Person(val faces: List<FaceVec>, val main: FaceVec, val id: Long = 0, val name: String? = null, val hidden: Boolean = false, val coverFaceId: Long? = null) {
    /** Distinct photos the person appears in. */
    val photos get() = faces.map { it.photoId }.distinct().size

    /** The face shown for this person (People, Library, the top bar, the sheets): the user's pick, or the main face. */
    val cover get() = faces.firstOrNull { it.id == coverFaceId } ?: main
}

/** The same person with a new cover, for updating a cached list in place (see [updateCached]). */
fun Person.withCover(cover: Long?) = Person(faces, main, id, name, hidden, cover)

/** Everything the People screen shows, loaded in one pass. */
data class PeopleData(val summary: String, val people: List<Person>, val unsure: List<FaceVec>, val total: Int)

/** The same data with one person's cover changed, for updating a cached list in place (see [updateCached]). */
fun PeopleData.withCover(personId: Long, faceId: Long?) =
    copy(people = people.map { if (it.id == personId) it.withCover(faceId) else it })

// Decoded face pictures, so scrolling the grids does not decode the same face again
private val faceBitmaps = object : LruCache<Long, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
    override fun sizeOf(key: Long, value: ImageBitmap) = value.width * value.height * 4
}

private fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}

private fun mean(faces: List<FaceVec>) = FloatArray(512).also { m -> faces.forEach { f -> f.vector.forEachIndexed { k, v -> m[k] += v } } }

/**
 * Like bench.py group: average linkage on cosine distance, cut at 0.68, faces from 40 px.
 * Only camera faces with quality 0.4+ start a person (lower ones were cats, masks, backs of heads),
 * other faces join the closest person under the cut. Joined faces under 0.4 are unsure: they wait for the user's yes / no.
 * [answers]: a yes keeps the face with that person, a no rules that person out.
 * [merges]: the groups that hold both main faces of a Merge with become one.
 */
// ponytail: O(n³) merge loop, fine for a few thousand faces; NN-chain for a full library
fun groupFaces(all: List<FaceVec>, answers: List<AnswerRow> = emptyList(), merges: List<MergeRow> = emptyList(), cut: Float = 0.68f): List<Person> {
    // a face the user fixed by hand follows the answers: it never starts a person (a moved face would go back), and small ones stay
    val answered = answers.map { it.faceId }.toSet()
    val (seeds, others) = all.filter { min(it.box[2] - it.box[0], it.box[3] - it.box[1]) >= 40 || it.id in answered }
        .partition { it.kind == "camera" && it.quality >= 0.4f && it.id !in answered }
    val n = seeds.size
    // ponytail: n×n floats, 17 MB at 2k seeds but 400 MB at 10k; store half of it (or float16) before libraries get that big
    val d = Array(n) { FloatArray(n) }
    for (i in 0 until n) for (j in i + 1 until n) { d[i][j] = 1 - dot(seeds[i].vector, seeds[j].vector); d[j][i] = d[i][j] }
    val members = Array(n) { mutableListOf(seeds[it]) }
    val alive = BooleanArray(n) { true } // not merged into another group
    val open = BooleanArray(n) { true } // may still merge
    // Average linkage by nearest-neighbour chain: same groups as merging the closest pair each step (scipy, bench), O(n²) not O(n³)
    val chain = IntArray(n); var top = 0; var next = 0
    while (true) {
        if (top == 0) {
            while (next < n && !open[next]) next++
            if (next == n) break
            chain[top++] = next
        }
        val x = chain[top - 1]
        // on a tie keep the group below in the chain, or the chain can loop
        var y = if (top > 1) chain[top - 2] else -1
        var best = if (y >= 0) d[x][y] else Float.MAX_VALUE
        for (k in 0 until n) if (open[k] && k != x && d[x][k] < best) { best = d[x][k]; y = k }
        // nothing within the cut: merging others never brings a group closer (an average is never below its smallest part)
        if (best > cut) { open[x] = false; top--; continue }
        if (top > 1 && y == chain[top - 2]) {
            top -= 2
            val a = min(x, y); val b = max(x, y)
            val na = members[a].size; val nb = members[b].size
            for (k in 0 until n) { d[a][k] = (na * d[a][k] + nb * d[b][k]) / (na + nb); d[k][a] = d[a][k] }
            members[a].addAll(members[b])
            alive[b] = false; open[b] = false
        } else chain[top++] = y
    }
    // A person needs faces from 6+ photos: fewer is often statues, posters or strangers seen once
    val auto = (0 until n).filter { alive[it] && members[it].distinctBy { f -> f.photoId }.size > 5 }.map { members[it] }
    // "Who is this?" > New person: a yes to the face's own id. That person grows only by the user's yes, never by distance.
    val own = answers.filter { it.yes && it.faceId == it.personFace }.map { it.faceId }.toSet()
    // second: takes faces by distance. A New person merged into a grouped person does too.
    val parts = (auto.map { it to true } + others.filter { it.id in own }.map { listOf(it) to false }).toMutableList()
    for (m in merges) {
        val a = parts.indexOfFirst { (g, _) -> g.any { it.id == m.personFace } }
        val b = parts.indexOfFirst { (g, _) -> g.any { it.id == m.face } }
        if (a < 0 || b < 0 || a == b) continue
        parts[a] = (parts[a].first + parts[b].first) to (parts[a].second || parts[b].second)
        parts.removeAt(b)
    }
    val groups = parts.map { it.first }
    val joined = groups.map { mutableListOf<FaceVec>() }
    val ids = groups.map { g -> g.map { it.id }.toSet() }
    val yes = answers.filter { it.yes }.associate { it.faceId to it.personFace }
    val no = answers.filter { !it.yes }.groupBy({ it.faceId }, { it.personFace })
    // average distance to a group's faces = distance to the sum of its vectors / size: one dot per group, not per face
    val sums = groups.map { mean(it) }
    for (f in others) {
        if (f.id in own) continue
        val kept = yes[f.id]?.let { p -> ids.indexOfFirst { p in it } } ?: -1
        if (kept >= 0) { joined[kept].add(f); continue }
        val ruledOut = no[f.id].orEmpty()
        val (i, dist) = groups.indices.filter { i -> parts[i].second && ruledOut.none { it in ids[i] } }
            .map { i -> i to 1.0 - dot(sums[i], f.vector) / groups[i].size }.minByOrNull { it.second } ?: continue
        if (dist <= cut) joined[i].add(f)
    }
    return groups.indices.map { i ->
        Person(groups[i] + joined[i], groups[i].maxBy { dot(it.vector, sums[i]) })
    }.sortedByDescending { it.faces.size }
}

/** Face plus 30% margin as a small WebP, longest side up to 288 px (the 96 dp People face at 3x). Saved while indexing, so the People screen never decodes photos. */
fun faceThumb(image: Bitmap, box: FloatArray): ByteArray {
    val m = max(box[2] - box[0], box[3] - box[1]) * 0.3f
    val l = (box[0] - m).toInt().coerceIn(0, image.width - 1)
    val t = (box[1] - m).toInt().coerceIn(0, image.height - 1)
    val w = ((box[2] + m).toInt().coerceAtMost(image.width) - l).coerceAtLeast(1)
    val h = ((box[3] + m).toInt().coerceAtMost(image.height) - t).coerceAtLeast(1)
    val s = min(1f, 288f / max(w, h))
    val thumb = Bitmap.createScaledBitmap(Bitmap.createBitmap(image, l, t, w, h), max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()), true)
    return ByteArrayOutputStream().also { thumb.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) }.toByteArray()
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

/** Loads people, unsure faces and the index totals, then hands them to [PeopleContent]. */
@Composable
fun PeopleScreen(
    openId: Long?,
    onOpenPerson: (Long) -> Unit,
    onPersonBack: () -> Unit,
    onBack: () -> Unit,
    onOpen: (List<Tile>, Tile) -> Unit = { _, _ -> },
    onHelpRecognize: (Long?) -> Unit = {},
    hiddenOnly: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { Db.get(context) }
    val dao = remember { db.index() }
    var reload by remember { mutableStateOf(0) }
    var naming by remember { mutableStateOf<Person?>(null) }
    var merging by remember { mutableStateOf<Person?>(null) }
    var covering by remember { mutableStateOf<Person?>(null) }
    var hiding by remember { mutableStateOf<Person?>(null) }
    val work by remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("index") }.collectAsState(emptyList())
    val indexed by remember { dao.kinds() }.collectAsState(emptyMap())
    val running = work.any { it.state == WorkInfo.State.RUNNING }
    // [saved] as a key: the run saves people every so often, so they show up while indexing.
    val saved by peopleSaved.collectAsState()
    val result by cachedProduceState("people", reload, saved) {
        withContext(Dispatchers.IO) {
            // Faces without vectors: the grid only shows pictures. Merge with ranks by distance, so it loads them itself.
            val faces = dao.taggedFaces()
            val byPerson = faces.groupBy { it.personId }
            // getValue would crash on a person with no faces (stopped index, manual edit); skip it
            val people = dao.people().mapNotNull { p -> byPerson[p.id]?.let { fs -> Person(fs.map { it.toVec() }, (fs.firstOrNull { it.id == p.mainFaceId } ?: fs.first()).toVec(), p.id, p.name, p.hidden, p.coverFaceId) } }
                .sortedWith(compareBy({ it.name.isNullOrBlank() }, { -it.photos })) // named first, then by photo count
            // hidden people stay in the list, so one opened from Hidden people still shows
            val shown = people.filter { !it.hidden }
            val unsure = shown.flatMap { p -> p.faces.filter { !it.sure } }.sortedBy { it.quality }
            PeopleData("${shown.size} people · ${dao.faceTotal()} faces", people, unsure, libraryPhotoCount(context))
        }
    }
    BackHandler { if (naming != null) naming = null else if (openId != null) onPersonBack() else onBack() }
    val pick = rememberPick()
    LaunchedEffect(openId) { pick.set(null) }
    // The open person left the list (faces removed or regrouped away): go back to the list instead of a
    // stale page. Close only after the person was seen once, so a stale cached list missing a person
    // just created elsewhere (Library -> the person) does not close the screen on open.
    var sawOpen by remember { mutableStateOf(false) }
    LaunchedEffect(result, openId) {
        if (openId == null) sawOpen = false
        else if (result?.people?.any { it.id == openId } == true) sawOpen = true
        else if (sawOpen && result != null) onPersonBack()
    }

    val r = result
    PeopleContent(
        data = r,
        openId = openId,
        indexing = running,
        waiting = !running && work.any { it.state == WorkInfo.State.ENQUEUED } && waitsForCharger(context),
        indexed = indexed.values.sum(),
        startedAt = IndexProgress.startedAt(context),
        base = IndexProgress.base(context),
        paused = !running && IndexProgress.paused(context),
        onPauseIndex = { IndexControl.pause(context) },
        onResumeIndex = { IndexControl.resume(context) },
        onBack = onBack,
        onOpenPerson = onOpenPerson,
        onClosePerson = onPersonBack,
        onName = { naming = it },
        onOpen = onOpen,
        onHelpRecognize = onHelpRecognize,
        hiddenOnly = hiddenOnly,
        pick = pick,
        onMerge = { merging = it },
        onCover = { covering = it },
        onHide = { p ->
            if (p.hidden) scope.launch { withContext(Dispatchers.IO) { dao.setHidden(p.id, false) }; reload++ } else hiding = p
        },
    )
    merging?.let { p ->
        MergeSheet(p, r?.people.orEmpty().filter { it.id != p.id && !it.hidden }, onDismiss = { merging = null }) { other ->
            merging = null
            scope.launch {
                withContext(Dispatchers.IO) { mergePeople(db, p, other) }
                reload++
            }
        }
    }
    covering?.let { p ->
        ChangeCoverSheet(p, onDismiss = { covering = null }) { faceId ->
            covering = null
            scope.launch {
                withContext(Dispatchers.IO) { dao.setCover(p.id, faceId) }
                // The Library tab is off composition while this person is open, so update its cache (and
                // People's) in place; otherwise its first frame back shows the old cover, then flips.
                updateCached("library", LibraryData::class.java) { it.withCover(p.id, faceId) }
                updateCached("people", PeopleData::class.java) { it.withCover(p.id, faceId) }
                reload++
            }
        }
    }
    hiding?.let { p ->
        val name = p.name?.takeIf { it.isNotBlank() }
        AlertDialog(
            onDismissRequest = { hiding = null },
            icon = { Icon(painterResource(R.drawable.ic_block), null) },
            title = { Text("Hide ${name ?: "this person"}?") },
            text = {
                Text("${name ?: "This person"} leaves People, Library and search. Their faces stay with them, so they never join someone else. Show them again from Settings, Hidden people.")
            },
            confirmButton = {
                TextButton({
                    hiding = null
                    scope.launch {
                        withContext(Dispatchers.IO) { dao.setHidden(p.id, true) }
                        reload++
                    }
                    onPersonBack()
                }) { Text("Hide") }
            },
            dismissButton = { TextButton({ hiding = null }) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.background,
            iconContentColor = MaterialTheme.colorScheme.primary,
        )
    }
    naming?.let { p ->
        NamePersonSheet(
            person = p,
            onDismiss = { naming = null },
        ) { name ->
            naming = null
            scope.launch {
                withContext(Dispatchers.IO) { dao.setName(p.id, name.trim().ifEmpty { null }) }
                reload++
            }
        }
    }
}

/** People list, or one person, per docs/design.html. Split from the load so previews can pass fake data. */
@Composable
fun PeopleContent(
    data: PeopleData?,
    openId: Long? = null,
    indexing: Boolean = false,
    indexed: Int = 0,
    startedAt: Long = 0L,
    base: Int = 0,
    paused: Boolean = false,
    waiting: Boolean = false,
    onPauseIndex: () -> Unit = {},
    onResumeIndex: () -> Unit = {},
    onBack: () -> Unit = {},
    onOpenPerson: (Long) -> Unit = {},
    onClosePerson: () -> Unit = {},
    onName: (Person) -> Unit = {},
    onOpen: (List<Tile>, Tile) -> Unit = { _, _ -> },
    onHelpRecognize: (Long?) -> Unit = {},
    hiddenOnly: Boolean = false,
    pick: Pick = Pick(null),
    onMerge: (Person) -> Unit = {},
    onCover: (Person) -> Unit = {},
    onHide: (Person) -> Unit = {},
) {
    val open = data?.people?.firstOrNull { it.id == openId }
    // hoisted: switching to a person and back must not lose the list scroll
    val listState = rememberLazyListState()
    // People come in rows of 4, the mockup's grid: 16 dp sides like the parts above it, 6 dp gaps
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (open == null && hiddenOnly) {
            val hidden = data?.people.orEmpty().filter { it.hidden }
            TopBar("Hidden people", data?.let { if (hidden.isEmpty()) "No people" else "${hidden.size} ${if (hidden.size == 1) "person" else "people"}" }, onBack = onBack)
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                if (hidden.isNotEmpty()) item {
                    Note(R.drawable.ic_info, "Not in People, Library or search. Tap one, then Show person", Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
                }
                items(hidden.chunked(4), key = { it.first().id }) { row ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { PersonCard(it, Modifier.weight(1f), tight = true) { onOpenPerson(it.id) } }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (data != null && hidden.isEmpty()) item {
                    EmptyState(R.drawable.ic_eye, "No hidden people", "Hide a person from their screen, they show up here")
                }
            }
        } else if (open == null) {
            TopBar("People", data?.summary, onBack = onBack)
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                data?.let { d ->
                    if (d.unsure.isNotEmpty()) item {
                        ReviewCard(d.unsure, { onHelpRecognize(null) })
                    }
                    item {
                        IndexProgressRow(indexing, waiting, indexed, d.total, startedAt, base, paused, onPauseIndex, onResumeIndex)
                    }
                    val shown = d.people.filter { !it.hidden }
                    // the mockup splits them: named 96 dp 3 a row (name and count), unnamed 56 dp 5 a row (faces only)
                    val (named, unnamed) = shown.partition { !it.name.isNullOrBlank() }
                    if (named.isNotEmpty()) {
                        item { SectionHead("Named", hint = "${named.size} ${if (named.size == 1) "person" else "people"}") }
                        items(named.chunked(3), key = { "n${it.first().id}" }) { row ->
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { PersonCard(it, Modifier.weight(1f).testTag("namedCard"), faceSize = 96.dp, tight = true) { onOpenPerson(it.id) } }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    if (unnamed.isNotEmpty()) {
                        item { SectionHead("No name yet", hint = "Name them to move them up") }
                        items(unnamed.chunked(5), key = { "u${it.first().id}" }) { row ->
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { PersonCard(it, Modifier.weight(1f).testTag("unnamedCard"), faceSize = 56.dp, tight = true, showName = false, showCount = false) { onOpenPerson(it.id) } }
                                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    if (shown.isEmpty()) item {
                        EmptyState(R.drawable.ic_person, "No people yet", "Index your photos in Library, then faces group into people here.")
                    }
                }
            }
        } else if (data != null) {
            PersonDetail(
                open, onBack = onClosePerson, onName = { onName(open) }, onOpen = onOpen, onReview = { onHelpRecognize(open.id) },
                onMerge = { onMerge(open) }, onCover = { onCover(open) }, onHide = { onHide(open) }, pick = pick,
            )
        }
    }
}

/**
 * The face of a friend picker (Merge with, Change cover): the person's circle, and while [picked] an
 * accent ring outside it plus the accent check at its top right (mockup `.pcard.on`). Both are drawn
 * outside the face, so picking never moves the grid.
 */
@Composable
private fun PickFace(face: FaceVec, faceSize: Dp, picked: Boolean) {
    val ring = MaterialTheme.colorScheme.primary
    Box {
        FaceImage(
            face,
            Modifier.drawBehind { if (picked) drawCircle(ring, size.minDimension / 2 + 4.5.dp.toPx(), style = Stroke(3.dp.toPx())) }
                .size(faceSize).clip(CircleShape),
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
 * for the unnamed grid (faces only). [picked]: accent ring and check (Merge with).
 */
@Composable
private fun PersonCard(
    p: Person,
    modifier: Modifier = Modifier,
    faceSize: Dp = 68.dp,
    picked: Boolean = false,
    tight: Boolean = false,
    showName: Boolean = true,
    showCount: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PickFace(p.cover, faceSize, picked)
        if (showName || showCount) Spacer(Modifier.height(if (tight) 6.dp else 8.dp))
        // 1.25 line height like the mockup: the plain Text default (a 24 sp line) left a big gap between name and count
        if (showName && !p.name.isNullOrBlank()) Text(p.name, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        if (showCount) Text("${p.photos}", fontSize = 12.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One person: name, fixes, the faces that match, their photos. Layout per docs/design.html. */
@Composable
fun PersonDetail(
    p: Person,
    onBack: () -> Unit,
    onName: () -> Unit,
    onOpen: (List<Tile>, Tile) -> Unit,
    onReview: () -> Unit = {},
    onMerge: () -> Unit = {},
    onCover: () -> Unit = {},
    onHide: () -> Unit = {},
    loadPhotos: (suspend (List<Long>) -> List<Tile>)? = null, // tests inject a fake; null loads from MediaStore
    pick: Pick = Pick(null),
) {
    val context = LocalContext.current
    val sel = pick.ids
    if (sel != null) BackHandler { pick.set(null) }
    // keyed on the photos, not the person id: a merge or a fixed face keeps the id (old photos stay shown while it reloads)
    val photoIds = p.faces.map { it.photoId }.distinct()
    // cached, not produceState: the viewer replaces the whole screen tree, so on the way back an
    // empty first frame would let the grid's restored scroll clamp to the top (see cachedProduceState)
    val photos by cachedProduceState("person${p.id}", photoIds, pick.changes) {
        val load = loadPhotos ?: { ids: List<Long> -> photosByIds(context, ids) }
        runCatching { withContext(Dispatchers.IO) { load(photoIds) } }.getOrDefault(emptyList())
    }
    val (sure, unsure) = p.faces.partition { it.sure }
    val name = p.name ?: "No name"
    // an unnamed person reads badly in a sentence ("11 may be No name"), so use "this person" there
    val who = p.name ?: "this person"
    val shownPhotos = photos.orEmpty()
    val month = DateTimeFormatter.ofPattern("LLLL yyyy")
    val zone = ZoneId.systemDefault()
    val groups = remember(shownPhotos) {
        shownPhotos.groupBy { month.format(Instant.ofEpochMilli(it.date).atZone(zone)) }.entries.toList()
    }
    // one (grid item index, month name) per month heading, so the slider can jump to it; the items
    // before the first month are the chips, the Faces head and row, the Unsure card (if any), the Photos head
    val lead = if (unsure.isNotEmpty()) 5 else 4
    val months = remember(groups, lead) {
        var i = lead
        groups.map { (m, tiles) -> (i to m).also { i += 1 + tiles.size } }
    }
    // own Column: the Select bar goes under the grid
    Column(Modifier.fillMaxSize()) {
        if (sel != null) TopBar(
            "${sel.size} selected", "$name · ${formatBytes(pick.size)}",
            onBack = { pick.set(null) }, backIcon = R.drawable.ic_close, backLabel = "Cancel",
        ) {
            if (sel.size < shownPhotos.size) TextButton({ pick.set(shownPhotos.map { it.id }.toSet()) }) { Text("Select all") }
        }
        else TopBar(
            title = name,
            subtitle = "${p.photos} photo${if (p.photos == 1) "" else "s"} · ${p.faces.size} face${if (p.faces.size == 1) "" else "s"}",
            onBack = onBack,
            leading = { FaceImage(p.cover, Modifier.padding(end = 12.dp).size(40.dp).clip(CircleShape)) },
            trailing = { // TODO: more menu
                IconButton({}) { Icon(painterResource(R.drawable.ic_more), "More") }
            },
        )
        val grid = rememberLazyGridState()
        FollowViewer(grid, shownPhotos)
        MonthSliderBox(grid = grid, months = months, enabled = sel == null, top = monthHeadingHeight(below = 10.dp), modifier = Modifier.weight(1f)) {
            LazyVerticalGrid(
                GridCells.Fixed(3), modifier = Modifier.testTag("personPhotos"), state = grid,
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Chip(if (p.name.isNullOrBlank()) "Add name" else "Rename", R.drawable.ic_person, onName)
                        Chip("Merge with", R.drawable.ic_person_add, onMerge)
                        Chip("Change cover", R.drawable.ic_photo, onCover)
                        if (p.hidden) Chip("Show person", R.drawable.ic_eye, onHide) else Chip("Hide person", R.drawable.ic_block, onHide)
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHead("Faces", hint = "These faces match $who") }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        sure.take(30).forEach { FaceImage(it, Modifier.size(44.dp).clip(CircleShape)) }
                    }
                }
                if (unsure.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    // "may be this person" wraps next to the face stack
                    ReviewCard(unsure, onReview, title = "Unsure faces", note = if (p.name.isNullOrBlank()) "${unsure.size} to check" else "${unsure.size} may be ${p.name}")
                }
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHead("Photos", hint = "${p.photos} · by date") }
                groups.forEach { (m, tiles) ->
                    stickyHeader(key = m) {
                        // background: the pinned month covers the tiles passing under it
                        Text(
                            m, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 16.dp, top = 20.dp, bottom = 10.dp),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    items(tiles, key = { it.id }) { t ->
                        val on = sel != null && t.id in sel
                        TileImage(
                            t,
                            Modifier.combinedClickable(
                                onClick = { if (sel == null) onOpen(shownPhotos, t) else pick.set(if (on) sel - t.id else sel + t.id) },
                                onLongClick = { pick.set(sel.orEmpty() + t.id) },
                            ).testTag("tile-${t.id}"),
                            selecting = sel != null, selected = on,
                        )
                    }
                }
            }
        }
        if (sel != null) {
            val picked = shownPhotos.filter { it.id in sel }
            SelectBar(picked.isNotEmpty() && picked.all { it.favorite }, { pick.share(picked) }, { pick.favorite(picked) }, { pick.delete(picked) })
        }
    }
}

/** Bordered action chip, like the mockup's Rename / Merge with. */
@Composable
fun Chip(label: String, icon: Int, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(label, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    }
}

/** Merge with: [other]'s faces join [into]. [into] keeps its name, or takes [other]'s if it has none. */
suspend fun mergePeople(db: Db, into: Person, other: Person) = db.withTransaction {
    val dao = db.index()
    dao.merge(MergeRow(other.main.id, into.main.id))
    dao.movePerson(other.id, into.id)
    if (into.name.isNullOrBlank() && !other.name.isNullOrBlank()) dao.setName(into.id, other.name)
    dao.deletePerson(other.id)
}

/** "Merge with" sheet from the mockup: [people] most alike [person] first, pick one, Merge joins it into [person]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeSheet(person: Person, people: List<Person>, onDismiss: () -> Unit, onMerge: (Person) -> Unit) {
    val context = LocalContext.current
    val dao = remember { Db.get(context).index() }
    // The list's people carry no vectors (see PeopleScreen); "most alike first" needs them, so load them
    // once here when the sheet opens, not on every People open.
    var means by remember { mutableStateOf<Map<Long, FloatArray>>(emptyMap()) }
    LaunchedEffect(person.id, people) {
        val byPerson = withContext(Dispatchers.IO) { dao.faceVecs().filter { it.personId != null }.groupBy { it.personId!! } }
        means = byPerson.mapValues { mean(it.value) }
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Person?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.background) {
        // body split out: a ModalBottomSheet renders blank in the screenshot tests (see NamePersonContent)
        MergeContent(person, people, text, { text = it }, picked, { picked = it }, onDismiss, onMerge, means)
    }
}

/** The sheet's body without the bottom-sheet chrome, so tests can render it directly. */
@Composable
fun MergeContent(
    person: Person,
    people: List<Person>,
    text: String,
    onText: (String) -> Unit,
    picked: Person?,
    onPick: (Person) -> Unit,
    onCancel: () -> Unit,
    onMerge: (Person) -> Unit,
    means: Map<Long, FloatArray> = emptyMap(),
) {
    // closest average face first; |m| is the same for all, so only the other person's length counts.
    // [means] is loaded by MergeSheet (the list's faces have no vectors); empty in tests/previews.
    val alike = remember(person, people, means) {
        if (means.isNotEmpty()) {
            val m = means[person.id]
            people.sortedByDescending { val o = means[it.id]; if (m == null || o == null) 0f else dot(m, o) / sqrt(dot(o, o)) }
        } else {
            val m = mean(person.faces)
            people.sortedByDescending { val o = mean(it.faces); dot(m, o) / sqrt(dot(o, o)) }
        }
    }
    val shown = alike.filter { it.name.orEmpty().contains(text.trim(), ignoreCase = true) }
    Column(Modifier.padding(bottom = 28.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            FaceImage(person.cover, Modifier.size(48.dp).clip(CircleShape))
            Column(Modifier.padding(start = 16.dp)) {
                Text("Merge with ${person.name?.takeIf { it.isNotBlank() } ?: "this person"}", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Pick the same person, most alike first", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp)
                .clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_search), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.padding(start = 12.dp).weight(1f)) {
                if (text.isEmpty()) Text("Search people", color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(
                    text, onText, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            }
        }
        // fixed height: a search that hides people must not move the field you are typing in
        LazyVerticalGrid(
            GridCells.Fixed(4), Modifier.height(320.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(shown, key = { it.id }) { PersonCard(it, faceSize = 68.dp, picked = it.id == picked?.id, tight = true) { onPick(it) } }
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp))
                    .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(26.dp))
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) { Text("Cancel", fontWeight = FontWeight.Medium) }
            Row(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp))
                    .background(if (picked == null) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary)
                    .clickable(enabled = picked != null) { picked?.let(onMerge) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = if (picked == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary
                Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), tint = color)
                Spacer(Modifier.width(8.dp))
                Text("Merge", color = color, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * "Change cover" sheet from the mockup: the person's sure faces, clearest first, the current cover on top.
 * Picking a face sets the person's cover; Save is off until a face is picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeCoverSheet(person: Person, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var picked by remember { mutableStateOf<Long?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.background) {
        // body split out: a ModalBottomSheet renders blank in the screenshot tests (see NamePersonContent)
        ChangeCoverContent(person, picked, { picked = it }, onDismiss, onSave)
    }
}

/** The sheet's body without the bottom-sheet chrome, so tests can render it directly. */
@Composable
fun ChangeCoverContent(person: Person, picked: Long?, onPick: (Long) -> Unit, onCancel: () -> Unit, onSave: (Long) -> Unit) {
    val cover = person.cover
    // the current cover first, then the person's sure faces, clearest first
    val faces = remember(person) {
        val sure = person.faces.filter { it.sure }.sortedByDescending { it.quality }
        listOf(cover) + sure.filter { it.id != cover.id }
    }
    Column(Modifier.padding(bottom = 28.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            FaceImage(cover, Modifier.size(48.dp).clip(CircleShape))
            Column(Modifier.padding(start = 16.dp)) {
                Text("Change cover", style = MaterialTheme.typography.titleLarge)
                Text("${faces.size} face${if (faces.size == 1) "" else "s"}, clearest first", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        // fixed height: picking a face must not move the buttons under the grid
        LazyVerticalGrid(GridCells.Fixed(4), Modifier.height(320.dp), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp)) {
            items(faces, key = { it.id }) { f ->
                Box(Modifier.padding(4.dp).testTag("coverFace-${f.id}").clickable { onPick(f.id) }) {
                    PickFace(f, 68.dp, f.id == picked)
                }
            }
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp))
                    .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(26.dp))
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) { Text("Cancel", fontWeight = FontWeight.Medium) }
            Row(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp))
                    .background(if (picked == null) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary)
                    .clickable(enabled = picked != null) { picked?.let(onSave) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = if (picked == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary
                Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), tint = color)
                Spacer(Modifier.width(8.dp))
                Text("Save", color = color, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * "Name this person" sheet from the mockup: just a name field.
 * Saves a blank name as no name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NamePersonSheet(person: Person, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf(person.name.orEmpty()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.background) {
        // the body sits in its own composable: a ModalBottomSheet renders blank in the screenshot
        // tests, only the plain content can be previewed and jump-checked
        NamePersonContent(person, text, { text = it }, onDismiss, onSave)
    }
}

/** The sheet's body without the bottom-sheet chrome, so tests can render it directly. */
@Composable
fun NamePersonContent(person: Person, text: String, onText: (String) -> Unit, onCancel: () -> Unit, onSave: (String) -> Unit) {
    Column(Modifier.padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            FaceImage(person.cover, Modifier.size(48.dp).clip(CircleShape))
            Column(Modifier.padding(start = 16.dp)) {
                Text("Name this person", style = MaterialTheme.typography.titleLarge)
                Text("${person.photos} photo${if (person.photos == 1) "" else "s"} · ${person.faces.size} face${if (person.faces.size == 1) "" else "s"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp)
                .clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text("Type a name", color = MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(
                text, onText, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp))
                    .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(26.dp))
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) { Text("Cancel", fontWeight = FontWeight.Medium) }
            Row(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp))
                    .background(if (text.isBlank()) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary)
                    .clickable(enabled = text.isNotBlank()) { onSave(text) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = if (text.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary
                Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), tint = color)
                Spacer(Modifier.width(8.dp))
                Text("Save", color = color, fontWeight = FontWeight.Medium)
            }
        }
    }
}
