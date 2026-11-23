package app.memoria

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
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
    var first by remember { mutableStateOf<List<Tile>?>(null) } // waiting on the first-lock sheet
    var noLock by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("vault", Context.MODE_PRIVATE) }
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
    fun lock(tiles: List<Tile>) {
        if (activity != null) scope.launch {
            // Locking asks for nothing: the phone's sheet is for opening the folder, never for locking into it
            if (vaultKeys == null) vaultKeys = runCatching {
                withContext(Dispatchers.IO) {
                    VaultKey.keys(context) ?: if (!VaultKey.exists(context)) VaultKey.create(context) else null
                }
            }.getOrNull()
            // The first builds' key opened only through the sheet, so a vault made by them still needs it, once
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
    first?.let { tiles ->
        FirstLockDialog(tiles, onCancel = { first = null }, onLock = {
            first = null
            prefs.edit().putBoolean("introSeen", true).apply()
            lock(tiles)
        })
    }
    if (noLock) NoScreenLockDialog(onCancel = { noLock = false }, onSettings = { noLock = false; openLockSettings(context) })
    return { tiles ->
        if (tiles.isNotEmpty()) when {
            vaultKeys == null && !canUnlockVault(context) -> noLock = true
            prefs.getBoolean("introSeen", false) -> lock(tiles)
            else -> first = tiles
        }
    }
}

/** Android's own screen lock settings: "Choose a screen lock" where the phone has it, else Security. */
private fun openLockSettings(context: Context) {
    runCatching { context.startActivity(Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD)) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } }
}

/** "6 photos", "1 video", or "3 items" when both kinds are in. */
private fun countWord(n: Int, videos: Int) = count(n, if (videos == 0) "photo" else if (videos == n) "video" else "item")

/** Lock with no screen lock (mockup "Lock, no screen lock"): the phone's sheet cannot show, so this says why. */
@Composable
fun NoScreenLockDialog(onCancel: () -> Unit, onSettings: () -> Unit) = AlertDialog(
    onDismissRequest = onCancel,
    icon = { Icon(painterResource(R.drawable.ic_lock), null) },
    title = { Text("Set a screen lock first") },
    text = { Text("Locked photos open with this phone's own fingerprint or PIN, and it has none now.") },
    confirmButton = { TextButton(onSettings) { Text("Open settings") } },
    dismissButton = { TextButton(onCancel) { Text("Cancel") } },
)

/** Move out, every time (mockup): the files show in the gallery and other apps again. Not red: nothing is lost. */
@Composable
fun MoveOutDialog(n: Int, videos: Int, onCancel: () -> Unit, onMove: () -> Unit) {
    val (go, came, them) = if (n == 1) Triple("It goes", "the folder it", "it") else Triple("They go", "the folders they", "them")
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(painterResource(R.drawable.ic_upload), null) },
        title = { Text("Move ${countWord(n, videos)} out?") },
        text = { Text("$go back to the phone's gallery, in $came came from. Any app that can see your photos can open $them again.") },
        confirmButton = { TextButton(onMove) { Text("Move out") } },
        dismissButton = { TextButton(onCancel) { Text("Cancel") } },
    )
}

/** Lock, the first time only (mockup "Lock, the first time"): the two things nobody expects, once, before Android's confirm. */
@Composable
fun FirstLockDialog(tiles: List<Tile>, onCancel: () -> Unit, onLock: () -> Unit) {
    val (they, them) = if (tiles.size == 1) "It leaves" to "it" else "They leave" to "them"
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(painterResource(R.drawable.ic_lock), null) },
        title = { Text("Lock ${countWord(tiles.size, tiles.count { it.duration != null })}?") },
        text = { Text("$they the phone's gallery, so no other app can open $them. Clearing the app's data or uninstalling Memoria deletes $them completely.") },
        confirmButton = { TextButton(onLock) { Text("Lock", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onCancel) { Text("Cancel") } },
    )
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
    var loaded by remember { mutableStateOf(false) } // the first read is done: the fade starts with the photos in hand
    val ready = vaultKeys != null && loaded
    var noLock by remember { mutableStateOf(false) }
    // No screen of its own for the lock: the phone's sheet is the screen. Cancelled, the folder closes.
    // With no screen lock the sheet cannot show: the folder says so, and back from Settings this runs again.
    LifecycleStartEffect(Unit) {
        var gone = false
        scope.launch {
            noLock = vaultKeys == null && !canUnlockVault(context)
            if (noLock) return@launch
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
        loaded = true
    }
    LockedContent(
        items = items,
        ready = ready,
        noLock = noLock,
        onOpenSettings = { openLockSettings(context) },
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
                runCatching { VaultStore.delete(context, picked) }
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
    noLock: Boolean = false, // the phone has no screen lock, so its sheet cannot open the folder
    onOpenSettings: () -> Unit = {},
) {
    val selecting = selected.isNotEmpty()
    // Unlocking never pops (mockup "Locked folder"): the grid fades and settles in under the top bar
    val show by animateFloatAsState(if (ready) 1f else 0f, tween(200), label = "unlock")
    var info by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var movingOut by remember { mutableStateOf(false) }
    BackHandler(selecting) { onCancelSelect() }
    val grid = rememberLazyGridState()
    FollowViewer(grid, items.map { it.tile })
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopBar(
            title = if (selecting) "${selected.size} selected" else "Locked folder",
            subtitle = when {
                selecting -> "${count(items.size, "item")} · ${formatBytes(items.filter { it.id in selected }.sumOf { i -> i.size })}"
                !ready -> null // behind the unlock sheet: the count is not known yet
                items.isEmpty() -> "Empty"
                else -> "${count(items.size, "item")} · ${formatBytes(items.sumOf { it.size })}"
            },
            onBack = if (selecting) ({ onCancelSelect() }) else onBack,
            backIcon = if (selecting) R.drawable.ic_close else R.drawable.ic_back,
            backLabel = if (selecting) "Cancel" else "Back",
        ) {
            if (selecting && selected.size < items.size) TextButton(onSelectAll) { Text("Select all") }
            else if (!selecting) IconButton({ info = true }) { Icon(painterResource(R.drawable.ic_info), "About the locked folder") }
        }
        when {
            noLock -> EmptyState(
                R.drawable.ic_lock, "Set a screen lock first",
                "The folder opens with this phone's own fingerprint or PIN, and it has none now. Anything already locked stays safe",
                button = "Open settings", onButton = onOpenSettings,
            )
            !ready -> Box(Modifier.weight(1f).fillMaxWidth())
            items.isEmpty() -> EmptyState(
                R.drawable.ic_lock, "Nothing locked yet",
                "Pick photos or videos and tap Lock, or lock one from the viewer's More. They leave your gallery and only open here",
            )
            else -> LazyVerticalGrid(
                GridCells.Fixed(3), Modifier.weight(1f).graphicsLayer {
                    alpha = show
                    scaleX = 0.96f + 0.04f * show
                    scaleY = 0.96f + 0.04f * show
                }.testTag("lockedGrid"), state = grid,
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
            "Move out", R.drawable.ic_upload, { movingOut = true },
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
    if (movingOut) items.filter { it.id in selected }.let { picked ->
        MoveOutDialog(picked.size, picked.count { it.duration != null }, onCancel = { movingOut = false }) { movingOut = false; onMoveOut() }
    }
    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text("Delete these photos?") },
        text = { Text("A locked file is not in the phone's trash. It is gone completely.") },
        confirmButton = { TextButton({ asking = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ asking = false }) { Text("Cancel") } },
    )
}
