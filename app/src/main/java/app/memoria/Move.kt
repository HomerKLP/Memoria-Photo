package app.memoria

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Move to album" sheet from the mockup (Viewer, More > Move to album). The body is split out so the
 * previews and layout tests can render it: a ModalBottomSheet renders blank in the screenshot tests.
 * [onMove] hands the target's RELATIVE_PATH to the caller, which does the move and Android's confirm.
 */
@Composable
fun MoveToAlbumContent(
    photo: Tile,
    currentAlbum: String,
    targets: List<MoveTarget>?,
    naming: Boolean,
    name: String,
    onName: (String) -> Unit,
    onCancel: () -> Unit,
    onNewAlbum: () -> Unit,
    onMove: (MoveTarget) -> Unit,
    onMoveNew: (String) -> Unit,
) {
    Column(Modifier.padding(bottom = 28.dp)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Thumb(photo.copy(duration = null), Modifier.fillMaxSize())
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text("Move to album", style = MaterialTheme.typography.titleLarge)
                Text("Now in $currentAlbum", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (naming) {
            // name field + Cancel / Move, like Name a person
            Box(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp)
                    .clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (name.isEmpty()) Text("Album name", color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(
                    name, onName, singleLine = true,
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
                        .background(if (name.isBlank()) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary)
                        .clickable(enabled = name.isNotBlank()) { onMoveNew(name) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    val color = if (name.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary
                    Icon(painterResource(R.drawable.ic_move), null, Modifier.size(18.dp), tint = color)
                    Spacer(Modifier.width(8.dp))
                    Text("Move", color = color, fontWeight = FontWeight.Medium)
                }
            }
        } else {
            PickRow(icon = R.drawable.ic_add, label = "New album", note = null, onClick = onNewAlbum)
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(targets.orEmpty(), key = { it.path }) { t ->
                    PickRow(cover = t.cover, label = t.name, note = t.count.toString(), onClick = { onMove(t) })
                }
            }
        }
    }
}

/** One row of the sheet (mockup `.pick`): an accent icon or an album cover, both square, a label, a count. */
@Composable
private fun PickRow(cover: Tile? = null, icon: Int? = null, label: String, note: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            icon != null -> Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary) }
            else -> Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                cover?.let { Thumb(it.copy(duration = null), Modifier.fillMaxSize()) }
            }
        }
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        note?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveToAlbumSheet(photo: Tile, onDismiss: () -> Unit, onMove: (String) -> Unit) {
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val current by produceState<TileAlbum?>(null, photo.id) { value = withContext(Dispatchers.IO) { albumOf(context, photo.id) } }
    val targets by cachedProduceState("moveTo${photo.id}", current?.id) { withContext(Dispatchers.IO) { moveTargets(context, current?.id ?: -1L) } }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.background) {
        MoveToAlbumContent(
            photo, current?.name ?: "Other", targets, naming, name, { name = it }, onDismiss,
            onNewAlbum = { naming = true },
            onMove = { onMove(it.path) },
            onMoveNew = { onMove("Pictures/${it.trim()}/") },
        )
    }
}
