package app.memoria

import android.app.Activity
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The vault's last list: the folder shows it at once on the next open, before the reload. */
var lastVault by mutableStateOf<List<VaultItem>?>(null)

/** True while the locked folder is on screen: a lock from elsewhere drops the keys when it is done, unless it is. */
var vaultOpen by mutableStateOf(false)

/** Leaving the folder or the app drops the keys and the locked thumbnails: coming back asks for the phone's lock. */
fun lockVault() {
    vaultKeys = null
    lastVault = null
    thumbs.snapshot().keys.filter { it < 0 }.forEach { thumbs.remove(it) }
}

/**
 * The Lock action, from a grid or the viewer: encrypt first, then Android's own confirm for the gallery
 * copies. Cancel takes the encrypted copies back out, so a photo is never in two places and never lost.
 */
@Composable
fun rememberVaultLock(onLocked: (List<Tile>) -> Unit = {}): (List<Tile>) -> Unit {
    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    var batch by remember { mutableStateOf<VaultStore.Batch?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val b = batch ?: return@rememberLauncherForActivityResult
        batch = null
        scope.launch {
            if (result.resultCode == Activity.RESULT_OK) {
                runCatching { VaultStore.commit(context, b) }
                onLocked(b.tiles)
                AppLog.i("vault: locked ${b.items.size} items")
            } else {
                runCatching { VaultStore.undo(context, b) }
                AppLog.i("vault: lock cancelled, took ${b.items.size} copies back out")
            }
            if (!vaultOpen) lockVault()
            viewerEdits++
        }
    }
    return { tiles ->
        if (tiles.isNotEmpty() && activity != null) scope.launch {
            if (vaultKeys == null) vaultKeys = unlockVault(activity)
            if (vaultKeys != null) {
                val b = runCatching { VaultStore.lock(context, tiles) }
                    .onFailure { AppLog.i("vault: lock failed on ${tiles.size} tiles: $it") }
                    .getOrNull()
                if (b != null) {
                    batch = b
                    val request = MediaStore.createDeleteRequest(context.contentResolver, b.tiles.map { it.uri })
                    ask.launch(IntentSenderRequest.Builder(request.intentSender).build())
                } else if (!vaultOpen) lockVault()
            }
        }
    }
}

/** The Locked folder (mockup "Locked folder"): the phone's own sheet, then the grid, select mode and the info. */
@Composable
fun LockedScreen(onBack: () -> Unit, onOpen: (List<Tile>, Tile) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(lastVault.orEmpty()) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var reload by remember { mutableIntStateOf(0) }
    val ready = vaultKeys != null
    // No screen of its own for the lock: the phone's sheet is the screen. Cancelled, the folder closes.
    LifecycleStartEffect(Unit) {
        var gone = false
        scope.launch {
            if (vaultKeys == null) vaultKeys = unlockVault(activity ?: return@launch)
            if (vaultKeys == null) {
                if (!gone) onBack()
                return@launch
            }
            withContext(Dispatchers.IO) { VaultStore.sweep(context) }
            reload++
        }
        vaultOpen = true
        onStopOrDispose {
            gone = true
            vaultOpen = false
            lockVault()
            items = emptyList()
            selected = emptySet()
        }
    }
    // No screenshot and no card in Recents while the folder (or its viewer) is up
    LifecycleStartEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onStopOrDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(reload, viewerEdits) {
        if (vaultKeys == null) return@LaunchedEffect
        items = runCatching { VaultStore.items(context) }.getOrDefault(emptyList())
        lastVault = items
    }
    LockedContent(
        items = items,
        ready = ready,
        selected = selected,
        onBack = onBack,
        onToggle = { id -> selected = if (id in selected) selected - id else selected + id },
        onCancelSelect = { selected = emptySet() },
        onSelectAll = { selected = items.map { it.id }.toSet() },
        onMoveOut = {
            val picked = items.filter { it.id in selected }
            selected = emptySet()
            scope.launch {
                runCatching { VaultStore.moveOut(context, picked) }
                reload++
                viewerEdits++
            }
        },
        onDelete = {
            val picked = items.filter { it.id in selected }
            selected = emptySet()
            scope.launch {
                VaultStore.delete(context, picked)
                reload++
                viewerEdits++
            }
        },
        onOpen = { item -> onOpen(items.map { it.tile }, item.tile) },
    )
}

/** The folder's layout per docs/design.html: grid, select mode, empty state, info and the delete question. */
@Composable
fun LockedContent(
    items: List<VaultItem>,
    ready: Boolean = true,
    selected: Set<Long> = emptySet(),
    onBack: () -> Unit = {},
    onToggle: (Long) -> Unit = {},
    onCancelSelect: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    onMoveOut: () -> Unit = {},
    onDelete: () -> Unit = {},
    onOpen: (VaultItem) -> Unit = {},
) {
    val selecting = selected.isNotEmpty()
    var info by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    BackHandler(selecting) { onCancelSelect() }
    val grid = rememberLazyGridState()
    FollowViewer(grid, items.map { it.tile })
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopBar(
            title = if (selecting) "${selected.size} selected" else "Locked folder",
            subtitle = when {
                selecting -> "${items.size} items · ${formatBytes(items.filter { it.id in selected }.sumOf { i -> i.size })}"
                items.isEmpty() && ready -> "Empty"
                else -> "${items.size} items · ${formatBytes(items.sumOf { it.size })}"
            },
            onBack = if (selecting) ({ onCancelSelect() }) else onBack,
            backIcon = if (selecting) R.drawable.ic_close else R.drawable.ic_back,
            backLabel = if (selecting) "Cancel" else "Back",
        ) {
            if (selecting && selected.size < items.size) TextButton(onSelectAll) { Text("Select all") }
            else if (!selecting) IconButton({ info = true }) { Icon(painterResource(R.drawable.ic_info), "About the locked folder") }
        }
        when {
            !ready -> Box(Modifier.weight(1f).fillMaxWidth())
            items.isEmpty() -> EmptyState(
                R.drawable.ic_lock, "Nothing locked yet",
                "Pick photos or videos and tap Lock, or lock one from the viewer's More. They leave your gallery and only open here",
            )
            else -> LazyVerticalGrid(
                GridCells.Fixed(3), Modifier.weight(1f).testTag("lockedGrid"), state = grid,
                horizontalArrangement = Arrangement.spacedBy(1.dp), verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    Box(
                        Modifier.aspectRatio(1f).testTag("vault-${item.n}").combinedClickable(
                            onClick = { if (selecting) onToggle(item.id) else onOpen(item) },
                            onLongClick = { onToggle(item.id) },
                        ),
                    ) { TileImage(item.tile, Modifier.fillMaxSize(), selecting = selecting, selected = item.id in selected, heart = false) }
                }
            }
        }
        if (selecting) ActionBar(
            "Delete", R.drawable.ic_trash_line, { asking = true },
            "Move out", R.drawable.ic_upload, onMoveOut,
        )
    }
    if (info) AlertDialog(
        onDismissRequest = { info = false },
        title = { Text("Locked folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Locked photos and videos are kept encrypted inside the app. They are not in the phone's gallery, its trash or any backup.")
                Text("Clearing Memoria's data or uninstalling it deletes them completely.")
            }
        },
        confirmButton = { TextButton({ info = false }) { Text("Got it") } },
    )
    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text("Delete these photos?") },
        text = { Text("A locked file is not in the phone's trash. It is gone completely.") },
        confirmButton = { TextButton({ asking = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ asking = false }) { Text("Cancel") } },
    )
}
