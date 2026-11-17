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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

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
        val sure = person.shownFaces.filter { it.sure }.sortedByDescending { it.quality }
        listOf(cover) + sure.filter { it.id != cover.id }
    }
    Column(Modifier.padding(bottom = 28.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            FaceImage(cover, Modifier.size(48.dp).clip(CircleShape))
            Column(Modifier.padding(start = 16.dp)) {
                Text("Change cover", style = MaterialTheme.typography.titleLarge)
                Text("${faces.size} ${if (person.pet) "picture" else "face"}${if (faces.size == 1) "" else "s"}, clearest first", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** "Unmerge" sheet from the mockup: one face per part merged into [person]; the picked parts split off. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnmergeSheet(person: Person, parts: List<FaceVec>, busy: Boolean, onDismiss: () -> Unit, onUnmerge: (List<Long>) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var picked by remember { mutableStateOf(emptySet<Long>()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.background) {
        UnmergeContent(person, parts, picked, { f -> picked = if (f in picked) picked - f else picked + f }, onDismiss, onUnmerge, busy)
    }
}

/** The sheet's body without the bottom-sheet chrome, so tests can render it directly. */
@Composable
fun UnmergeContent(person: Person, parts: List<FaceVec>, picked: Set<Long>, onPick: (Long) -> Unit, onCancel: () -> Unit, onUnmerge: (List<Long>) -> Unit, busy: Boolean = false) {
    Column(Modifier.padding(bottom = 16.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            FaceImage(person.cover, Modifier.size(48.dp).clip(CircleShape))
            Column(Modifier.padding(start = 16.dp)) {
                Text("Unmerge ${person.name?.takeIf { it.isNotBlank() } ?: if (person.pet) "this pet" else "this person"}", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Pick what to split off", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        // the parts never change while the sheet is open, so the grid's height (and the buttons) stay put
        LazyVerticalGrid(GridCells.Fixed(4), Modifier.heightIn(max = 320.dp), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp)) {
            items(parts, key = { it.id }) { f ->
                Box(Modifier.padding(4.dp).testTag("unmergeFace-${f.id}").clickable(enabled = !busy) { onPick(f.id) }) { PickFace(f, 68.dp, f.id in picked) }
            }
        }
        ActionBar(
            // the buttons share the row by weight, so the longer label never changes the width
            "Cancel", null, onCancel, if (busy) "Unmerging…" else "Unmerge", R.drawable.ic_person_remove, { onUnmerge(picked.toList()) },
            danger = false, primaryEnabled = picked.isNotEmpty() && !busy,
        )
    }
}

/**
 * "Name this person" sheet from the mockup: just a name field.
 * Saving a cleared field removes the person's name.
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
    val canSave = text.isNotBlank() || !person.name.isNullOrBlank()
    Column(Modifier.padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            FaceImage(person.cover, Modifier.size(48.dp).clip(CircleShape))
            Column(Modifier.padding(start = 16.dp)) {
                Text(if (person.pet) "Name this pet" else "Name this person", style = MaterialTheme.typography.titleLarge)
                Text("${person.photos} photo${if (person.photos == 1) "" else "s"}" + if (person.pet) "" else " · ${person.faces.size} face${if (person.faces.size == 1) "" else "s"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                text, { if (it.length <= 40) onText(it) }, singleLine = true,
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
                    .background(if (canSave) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = canSave) { onSave(text) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = if (canSave) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), tint = color)
                Spacer(Modifier.width(8.dp))
                Text("Save", color = color, fontWeight = FontWeight.Medium)
            }
        }
    }
}
