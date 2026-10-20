package app.memoria

import android.app.Activity
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's own trash (MediaStore, 30 days). Restore and Delete go through Android's own
 * requests, so the system asks the user to confirm and nothing is copied into our own trash.
 */
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<Trashed>?>(null) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) { items = withContext(Dispatchers.IO) { runCatching { trash(context) }.getOrDefault(emptyList()) } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) { selected = emptySet(); reload++ }
    }
    fun ask(uris: List<Uri>, restore: Boolean) {
        if (uris.isEmpty()) return
        val request = if (restore) MediaStore.createTrashRequest(context.contentResolver, uris, false)
        else MediaStore.createDeleteRequest(context.contentResolver, uris)
        launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }
    TrashContent(
        items = items.orEmpty(),
        loading = items == null,
        selected = selected,
        onBack = onBack,
        onToggle = { id -> selected = if (id in selected) selected - id else selected + id },
        onCancelSelect = { selected = emptySet() },
        onSelectAll = { selected = items.orEmpty().map { it.tile.id }.toSet() },
        onRestore = { ask(items.orEmpty().filter { it.tile.id in selected }.map { it.tile.uri }, restore = true) },
        onDelete = { ask(items.orEmpty().filter { it.tile.id in selected }.map { it.tile.uri }, restore = false) },
        onEmpty = { ask(items.orEmpty().map { it.tile.uri }, restore = false) },
    )
}

/** Trash layout per docs/design.html: list, select mode and empty state. */
@Composable
fun TrashContent(
    items: List<Trashed>,
    loading: Boolean = false,
    selected: Set<Long> = emptySet(),
    onBack: () -> Unit = {},
    onToggle: (Long) -> Unit = {},
    onCancelSelect: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    onRestore: () -> Unit = {},
    onDelete: () -> Unit = {},
    onEmpty: () -> Unit = {},
) {
    val selecting = selected.isNotEmpty()
    BackHandler { if (selecting) onCancelSelect() else onBack() }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopBar(
            title = if (selecting) "${selected.size} selected" else "Trash",
            subtitle = if (items.isEmpty() && !loading) "Empty" else "${items.size} items",
            onBack = if (selecting) onCancelSelect else onBack,
            backIcon = if (selecting) R.drawable.ic_close else R.drawable.ic_back,
            backLabel = if (selecting) "Cancel" else "Back",
        ) {
            when {
                selecting && selected.size < items.size -> TextButton(onSelectAll) { Text("Select all") }
                !selecting && items.isNotEmpty() -> TextButton(onEmpty) {
                    Text("Empty", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                }
                else -> {}
            }
        }
        // stays in both modes: hiding it would shift the whole grid up
        if (items.isNotEmpty()) Note(
            R.drawable.ic_info, "Items are deleted for good after 30 days",
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        when {
            items.isEmpty() -> if (!loading) EmptyState(
                R.drawable.ic_trash, "Trash is empty",
                "Deleted photos and videos stay here 30 days, then they are gone for good",
            )
            else -> LazyVerticalGrid(
                GridCells.Fixed(3), Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(1.dp), verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(items, key = { it.tile.id }) { t ->
                    TrashTile(
                        t, selecting, t.tile.id in selected,
                        onClick = { if (selecting) onToggle(t.tile.id) },
                        onLongClick = { onToggle(t.tile.id) },
                    )
                }
            }
        }
        if (selecting) ActionBar(
            "Delete", R.drawable.ic_trash_line, onDelete,
            "Restore", R.drawable.ic_restore, onRestore,
        )
    }
}

/** One trashed tile: picture, days left, and the select circle while selecting. */
@Composable
fun TrashTile(
    t: Trashed,
    selecting: Boolean,
    on: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
) {
    Box(
        modifier.aspectRatio(1f)
            .then(if (on) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Thumb(t.tile, Modifier.fillMaxSize())
        if (selecting) Box(
            Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).clip(CircleShape)
                .then(
                    if (on) Modifier.background(MaterialTheme.colorScheme.primary)
                    else Modifier.border(2.dp, Color.White, CircleShape),
                ),
            contentAlignment = Alignment.Center,
        ) { if (on) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp), tint = Color.White) }
        val shadow = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium, shadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 1f), 2f))
        Text("${t.daysLeft} days", Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 5.dp), style = shadow)
    }
}
