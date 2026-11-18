package app.memoria

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** Merge with: [other]'s faces join [into]. [into] keeps its name, or takes [other]'s if it has none. */
suspend fun mergePeople(db: Db, into: Person, other: Person) = db.withTransaction {
    require(canMerge(into, other)) { "A person and a pet can't be merged" }
    val dao = db.index()
    val rows = dao.merges()
    // roots, not main faces: the main face of a merged person can be any part's, and Unmerge must split off only what was picked
    dao.merge(MergeRow(mergeRoot(other, rows), mergeRoot(into, rows)))
    dao.movePerson(other.id, into.id)
    if (into.name.isNullOrBlank() && !other.name.isNullOrBlank()) dao.setName(into.id, other.name)
    dao.deletePerson(other.id)
}

/** The merge rows inside [p]: both faces are [p]'s. */
private fun mergesIn(p: Person, rows: List<MergeRow>): List<MergeRow> {
    val ids = p.faces.map { it.id }.toSet()
    return rows.filter { it.face in ids && it.personFace in ids }
}

/** The face all of [p]'s merges lead to: the main face of the first person, before anyone joined. [p]'s main face with no merge. */
fun mergeRoot(p: Person, rows: List<MergeRow>): Long {
    val inside = mergesIn(p, rows)
    return inside.map { it.personFace }.firstOrNull { f -> inside.none { it.face == f } } ?: p.main.id
}

/** Unmerge's list: one face per part merged into [p], the newest merge first. */
fun mergedParts(p: Person, rows: List<MergeRow>): List<FaceVec> =
    mergesIn(p, rows).reversed().map { it.face }.distinct().mapNotNull { f -> p.faces.firstOrNull { it.id == f } }

/** People select > Merge: all join the first one picked with a name, or the first picked. One transaction, so a mix of people and pets changes nothing. */
suspend fun mergeAll(db: Db, picked: List<Person>) = db.withTransaction {
    val into = picked.firstOrNull { !it.name.isNullOrBlank() } ?: picked.first()
    picked.filter { it.id != into.id }.forEach { mergePeople(db, into, it) }
}

/** Unmerge: the parts starting at [faces] leave [p] as people with no name; [p] keeps its id and name (see [carry]'s pins). */
suspend fun unmergePeople(db: Db, p: Person, faces: List<Long>) {
    db.withTransaction {
        val root = mergeRoot(p, db.index().merges())
        db.index().unmerge(faces)
        regroup(db, mapOf(p.id to root))
    }
    peopleSaved.value++
}

/** "Merge with" sheet from the mockup: [people] most alike [person] first, pick one, Merge joins it into [person]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeSheet(person: Person, people: List<Person>, onDismiss: () -> Unit, onMerge: (Person) -> Unit) {
    val context = LocalContext.current
    val dao = remember { Db.get(context).index() }
    // The list's people carry no vectors (see PeopleScreen); "most alike first" needs them, so load them
    // once here when the sheet opens, not on every People open.
    var means by remember { mutableStateOf<Map<Long, FloatArray>?>(null) }
    LaunchedEffect(person.id, people) {
        val byPerson = withContext(Dispatchers.IO) { dao.personFaceVecs().groupBy { it.personId!! } }
        means = byPerson.mapValues { mean(it.value) }
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Person?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.background) {
        // body split out: a ModalBottomSheet renders blank in the screenshot tests (see NamePersonContent)
        // no people until the vectors are here: sorted without them first, the grid re-sorted under the finger (fixed height, nothing moves)
        MergeContent(person, if (means == null) emptyList() else people, text, { text = it }, picked, { picked = it }, onDismiss, onMerge, means.orEmpty())
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
                Text("Merge with ${person.name?.takeIf { it.isNotBlank() } ?: if (person.pet) "this pet" else "this person"}", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Pick the same ${if (person.pet) "pet" else "person"}, most alike first", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                if (text.isEmpty()) Text(if (person.pet) "Search pets" else "Search people", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
