package app.memoria

import android.content.ContentUris
import android.graphics.ImageDecoder
import android.provider.MediaStore
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Help recognize, per docs/design.html: one unsure face at a time, "Is this X?". Yes keeps it with X, No rules X out
 * (the next index run may put it with someone else), Someone else moves it, Not a face hides it, Skip asks again next time.
 * [personId]: only that person's faces (the Person screen's "Unsure faces" card).
 */
@Composable
fun HelpRecognizeScreen(personId: Long?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { Db.get(context) }
    val dao = remember { db.index() }
    // loaded once: answers do not reload it, pos just moves on
    val people by produceState<List<Person>?>(null) {
        value = withContext(Dispatchers.IO) {
            val byPerson = dao.faceVecs().filter { it.personId != null }.groupBy { it.personId!! }
            dao.people().mapNotNull { p -> byPerson[p.id]?.let { fs -> Person(fs, fs.firstOrNull { it.id == p.mainFaceId } ?: fs.first(), p.id, p.name, p.hidden) } }
                .sortedWith(compareBy({ it.name.isNullOrBlank() }, { -it.photos })) // People screen order
        }
    }
    val queue = remember(people) {
        people?.filter { if (personId == null) !it.hidden else it.id == personId }
            // one person at a time, so the faces to compare stay put; clearest faces first
            ?.flatMap { p -> p.faces.filter { !it.sure }.sortedByDescending { it.quality }.map { it to p } }
    }
    var pos by remember { mutableIntStateOf(0) }
    var skipped by remember { mutableIntStateOf(0) }
    var done by remember { mutableStateOf(emptySet<Long>()) } // look-alikes of a face answered Yes: settled with it, not asked
    var picking by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf(emptyList<Person>()) } // made here by New person, so the next face can pick them
    val q = queue
    val at = q?.let { l -> (pos until l.size).firstOrNull { l[it].first.id !in done } ?: l.size } ?: 0 // a fast double tap must not jump over the next face
    val current = q?.getOrNull(at)
    // NonCancellable: Back right after an answer closes this scope, and the answer must still land
    val answer: (suspend () -> Unit) -> Unit = { write -> scope.launch { withContext(Dispatchers.IO + NonCancellable) { write() } }; pos = at + 1 }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopBar("Help recognize", q?.let { "${min(at + 1, it.size)} of ${it.size}" }, onBack = onBack) {
            if (current != null) TextButton({ skipped++; pos = at + 1 }) { Text("Skip") }
        }
        if (q != null && current == null) EmptyState(
            R.drawable.ic_check, "All done",
            if (skipped > 0) "$skipped skipped faces come back next time" else "New unsure faces show up after the next index run",
        )
        if (current != null) Column(Modifier.verticalScroll(rememberScrollState())) {
            val (face, person) = current
            FacePhoto(face)
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                FaceImage(face, Modifier.size(88.dp).clip(CircleShape))
                Text(
                    if (person.name.isNullOrBlank()) AnnotatedString("Is this the same person?")
                    else buildAnnotatedString {
                        append("Is this ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(person.name) }
                        append("?")
                    },
                    Modifier.padding(start = 16.dp),
                    style = MaterialTheme.typography.headlineMedium.copy(lineHeight = 32.sp),
                    maxLines = 2, // two lines fit next to the 88 dp face, so a long name never moves the buttons
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (person.name.isNullOrBlank()) "This person's faces" else "${person.name}'s faces",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(start = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                person.faces.filter { it.sure }.sortedByDescending { it.quality }.take(5).forEach { FaceImage(it, Modifier.size(40.dp).clip(CircleShape)) }
            }
            ActionBar(
                "No", R.drawable.ic_close, { answer { dao.answer(AnswerRow(face.id, person.main.id, false)); dao.leavePerson(face.id) } },
                "Yes", R.drawable.ic_check, {
                    val twins = person.twinsOf(face).map { it.id }
                    done += twins
                    answer { dao.answer(AnswerRow(face.id, person.main.id, true)); if (twins.isNotEmpty()) dao.setNearSure(twins) }
                },
                Modifier.padding(top = 8.dp),
                danger = false,
            )
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                TextButton({ picking = true }) {
                    Icon(painterResource(R.drawable.ic_person), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Someone else")
                }
                TextButton({ answer { dao.notFace(face.id) } }) {
                    Icon(painterResource(R.drawable.ic_block), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Not a face")
                }
            }
        }
    }
    if (picking && current != null) {
        val (face, person) = current
        WhoIsThisSheet(
            face, person, people.orEmpty() + added,
            onDismiss = { picking = false },
            onPick = { to -> picking = false; answer { moveFace(db, face, person, to) } },
        ) { name ->
            picking = false
            pos = at + 1
            scope.launch {
                val id = withContext(Dispatchers.IO + NonCancellable) { newPerson(db, face, person, name) }
                added = added + Person(listOf(face), face, id, name)
            }
        }
    }
}

/** "Who is this?" > a person: [face] is not [from] (if it had one) but [to]. */
suspend fun moveFace(db: Db, face: FaceVec, from: Person?, to: Person) = db.withTransaction { retag(db.index(), face, from, to.main.id, to.id) }

/** "Who is this?" > New person: a person that starts from [face], its main face. Returns the new person's id. */
suspend fun newPerson(db: Db, face: FaceVec, from: Person?, name: String): Long = db.withTransaction {
    val dao = db.index()
    val id = dao.nextPersonId()
    dao.insertPerson(PersonRow(id, face.id, name))
    retag(dao, face, from, face.id, id)
    id
}

/** "Who is this?" > Remove tag: like No, the next index run may put the face with someone else. */
suspend fun removeTag(db: Db, face: FaceVec, from: Person) = db.withTransaction {
    val dao = db.index()
    dao.dropYes(face.id)
    dao.answer(AnswerRow(face.id, from.main.id, false))
    dao.leavePerson(face.id)
}

/** [face] is not [from] but the person whose main face is [toFace]. One yes per face, so an older yes goes. */
private suspend fun retag(dao: IndexDao, face: FaceVec, from: Person?, toFace: Long, to: Long) {
    dao.dropYes(face.id)
    if (from != null) dao.answer(AnswerRow(face.id, from.main.id, false))
    dao.answer(AnswerRow(face.id, toFace, true))
    dao.setPerson(listOf(face.id), to)
}

/**
 * "Who is this?" sheet from the mockup: pick a named person, or type a name for a new one.
 * [now] is the face's person (null: none), left out of the list. A new name must not be taken.
 * [onRemove] adds Remove tag (the viewer, not Help recognize).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhoIsThisSheet(
    face: FaceVec,
    now: Person?,
    people: List<Person>,
    onDismiss: () -> Unit,
    onPick: (Person) -> Unit,
    onRemove: (() -> Unit)? = null,
    onNew: (String) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.background) {
        // body split out: a ModalBottomSheet renders blank in the screenshot tests (see NamePersonContent)
        WhoIsThisContent(face, now, people, text, { text = it }, onPick, onRemove, onNew)
    }
}

/** The sheet's body without the bottom-sheet chrome, so tests can render it directly. */
@Composable
fun WhoIsThisContent(
    face: FaceVec,
    now: Person?,
    people: List<Person>,
    text: String,
    onText: (String) -> Unit,
    onPick: (Person) -> Unit,
    onRemove: (() -> Unit)?,
    onNew: (String) -> Unit,
) {
    val typed = text.trim()
    val named = people.filter { it.id != now?.id && !it.name.isNullOrBlank() && !it.hidden }
    val shown = named.filter { it.name.orEmpty().contains(typed, ignoreCase = true) }
    val canAdd = typed.isNotEmpty() && people.none { it.name.equals(typed, ignoreCase = true) }
    Column(Modifier.padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            FaceImage(face, Modifier.size(48.dp).clip(CircleShape))
            Column(Modifier.padding(start = 16.dp)) {
                Text("Who is this?", style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        now == null -> "Not tagged yet"
                        now.name.isNullOrBlank() -> "Now with an unnamed person"
                        else -> "Now tagged as ${now.name}"
                    },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                if (text.isEmpty()) Text("Search or type a new name", color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(
                    text, onText, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        shown.forEach { p ->
            Row(
                Modifier.fillMaxWidth().height(52.dp).clickable { onPick(p) }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FaceImage(p.cover, Modifier.size(40.dp).clip(CircleShape))
                Text(p.name.orEmpty(), Modifier.padding(start = 16.dp).weight(1f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.photos} photo${if (p.photos == 1) "" else "s"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // rows hidden by the search keep their space here, where they were: a shorter list would
        // slide the New person row and Remove tag up under the field you are typing in
        Spacer(Modifier.height(52.dp * (named.size - shown.size)))
        Row(
            Modifier.fillMaxWidth().height(52.dp).alpha(if (canAdd) 1f else 0.4f).clickable(enabled = canAdd) { onNew(typed) }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_person_add), null, tint = MaterialTheme.colorScheme.primary)
            }
            Text("New person", Modifier.padding(start = 16.dp).weight(1f), fontWeight = FontWeight.Medium)
            Text(typed, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onRemove != null) {
            HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onRemove).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_close), null, tint = MaterialTheme.colorScheme.primary)
                }
                Text("Remove tag", Modifier.padding(start = 16.dp), fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** The whole photo fitted into a 4:3 frame, with a box on [face]. Grey while it loads; the frame keeps its size. */
@Composable
private fun FacePhoto(face: FaceVec) {
    val context = LocalContext.current
    val photo by produceState<Pair<ImageBitmap, Float>?>(null, face.id) {
        value = null // produceState keeps the old value on a new face: never draw this box on the last photo
        value = withContext(Dispatchers.IO) {
            runCatching {
                var side = 0
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, face.photoId)
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    side = max(info.size.width, info.size.height)
                    val s = 1440f / side
                    if (s < 1) decoder.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
                }
                // boxes are in index pixels: the photo with its longest side capped at 4096 (loadPhoto)
                bitmap.asImageBitmap() to max(bitmap.width, bitmap.height).toFloat() / min(side, 4096)
            }.getOrNull()
        }
    }
    Canvas(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).fillMaxWidth().aspectRatio(4f / 3)
            .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val (image, k) = photo ?: return@Canvas
        val s = min(size.width / image.width, size.height / image.height)
        val left = (size.width - image.width * s) / 2
        val top = (size.height - image.height * s) / 2
        drawImage(
            image,
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize((image.width * s).roundToInt(), (image.height * s).roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
        val b = face.box
        val f = k * s
        val corner = Offset(left + b[0] * f, top + b[1] * f)
        val boxSize = Size((b[2] - b[0]) * f, (b[3] - b[1]) * f)
        val round = CornerRadius(8.dp.toPx())
        // dark edge under the white line, so the box shows on light photos too
        drawRoundRect(Color.Black.copy(alpha = 0.35f), corner, boxSize, round, style = Stroke(4.dp.toPx()))
        drawRoundRect(Color.White, corner, boxSize, round, style = Stroke(2.dp.toPx()))
    }
}
