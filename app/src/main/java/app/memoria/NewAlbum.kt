package app.memoria

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "New album" sheet (mockup "New album"): name the folder, then Next picks the photos. An album is a phone
 * folder and Android keeps no empty folders, so nothing is made until photos move in. The body is split out
 * so previews and layout tests can render it (a ModalBottomSheet renders blank in the screenshot tests).
 */
@Composable
fun NewAlbumContent(name: String, onName: (String) -> Unit, taken: Boolean, onCancel: () -> Unit, onNext: () -> Unit) {
    Column(Modifier.padding(bottom = 28.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary) }
            Column(Modifier.padding(start = 16.dp)) {
                Text("New album", style = MaterialTheme.typography.titleLarge)
                Text("A folder in Pictures", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        AlbumNameField(name, onName, Modifier.padding(horizontal = 16.dp))
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp))
                    .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(26.dp))
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) { Text("Cancel", fontWeight = FontWeight.Medium) }
            Row(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp))
                    .background(if (name.isBlank() || taken) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary)
                    .clickable(enabled = name.isNotBlank() && !taken) { onNext() },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = if (name.isBlank() || taken) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary
                Text("Next", color = color, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(6.dp))
                Icon(painterResource(R.drawable.ic_chevron), null, Modifier.size(18.dp), tint = color)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewAlbumSheet(initial: String, onDismiss: () -> Unit, onNext: (String) -> Unit) {
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(initial) }
    // Next is off while an album with that name already exists (mockup)
    val names by produceState<Set<String>?>(null) { value = withContext(Dispatchers.IO) { albumNames(context) } }
    val taken = names?.any { it.equals(name.trim(), ignoreCase = true) } == true
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.background) {
        NewAlbumContent(name, { name = it }, taken, onDismiss) { onNext(name.trim()) }
    }
}

/**
 * New album, pick photos (mockup "New album, pick photos"): the whole timeline in select mode from the start,
 * with Cancel / Move here. Picked photos move to `Pictures/<name>/`; Android asks once for photos another app
 * owns. Nothing is made until a move, and the new album opens after ([onMoved] gets its bucket id).
 */
@Composable
fun NewAlbumScreen(name: String, onBack: () -> Unit, onCancel: () -> Unit, onMoved: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by produceState(lastTimeline) {
        value = withContext(Dispatchers.IO) { timeline(context) }.also { lastTimeline = it }
    }
    val selection = remember { PhotoSelection() }
    val picked = selection.tiles.values.toList()
    val pickedSize by produceState(0L, selection.ids) {
        value = selection.ids.takeIf { it.isNotEmpty() }?.let { withContext(Dispatchers.IO) { selectedSize(context, it) } } ?: 0L
    }
    // hand the picked photos to [opening] once Android's confirm comes back OK
    var then by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) then?.invoke()
        then = null
    }
    fun finish(moved: List<Tile>) {
        scope.launch {
            val id = withContext(Dispatchers.IO) { moved.firstOrNull()?.let { albumOf(context, it.id)?.id } }
            if (id != null) onMoved(id) else onCancel()
        }
    }
    fun move(list: List<Tile>) {
        if (list.isEmpty()) return
        val path = "Pictures/${name.trim()}/"
        val foreign = list.filter { runCatching { movePhoto(context, it, path) }.isFailure }
        if (foreign.isEmpty()) finish(list)
        else {
            then = { foreign.forEach { runCatching { movePhoto(context, it, path) } }; finish(list) }
            val request = MediaStore.createWriteRequest(context.contentResolver, foreign.map { it.uri })
            ask.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        PhotosContent(
            items = items,
            selection = selection,
            pickedSize = pickedSize,
            forceSelecting = true,
            titleWhileSelecting = "Add to $name",
            scrollWhileSelecting = true,
            onSelectBack = onBack,
            footer = {
                ActionBar(
                    "Cancel", null, onCancel,
                    "Move here", R.drawable.ic_move, { move(picked) },
                    danger = false, primaryEnabled = picked.isNotEmpty(),
                )
            },
        )
    }
}
