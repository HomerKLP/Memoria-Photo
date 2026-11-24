package app.memoria

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Android's Media management apps page for Memoria: one switch. */
fun mediaSettingsIntent(context: Context) = Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${context.packageName}"))

/**
 * Media management, first open (mockup): call it after Android's photos and notifications questions.
 * It asks once, never with the switch already on. Both answers close it for good.
 */
@Composable
fun rememberMediaAsk(
    openSettings: () -> Unit = LocalContext.current.let { c -> { runCatching { c.startActivity(mediaSettingsIntent(c)) } } },
): () -> Unit {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("media", Context.MODE_PRIVATE) }
    var asking by remember { mutableStateOf(false) }
    val close = { asking = false; prefs.edit().putBoolean("asked", true).apply() }
    if (asking) AlertDialog(
        onDismissRequest = close,
        icon = { Icon(painterResource(R.drawable.ic_photo), null) },
        title = { Text("Use Memoria's own questions?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Android asks its own question before every delete, restore, move or lock.")
                Text("Turn on Media management for Memoria, and Memoria asks you instead. Android then lets it change photos without its check.")
            }
        },
        confirmButton = { TextButton({ close(); openSettings() }) { Text("Open settings") } },
        dismissButton = { TextButton(close) { Text("Not now") } },
    )
    return { if (!prefs.getBoolean("asked", false) && !MediaStore.canManageMedia(context)) asking = true }
}

/** A question Memoria asks before a change while Media management is on (mockup "Media management"). */
class MediaQuestion(val icon: Int, val title: String, val body: String, val button: String, val red: Boolean)

private fun kinds(tiles: List<Tile>) = countWord(tiles.size, tiles.count { it.duration != null })

fun trashQuestion(tiles: List<Tile>) = MediaQuestion(
    R.drawable.ic_trash_line, "Move ${kinds(tiles)} to trash?",
    if (tiles.size == 1) "It stays in Trash for 30 days, then it is deleted for good." else "They stay in Trash for 30 days, then they are deleted for good.",
    "Move to trash", red = true,
)

/** [all]: Empty, every item in Trash. */
fun deleteQuestion(tiles: List<Tile>, all: Boolean = false) = MediaQuestion(
    R.drawable.ic_trash_line, "Delete ${if (all && tiles.size > 1) "all " else ""}${kinds(tiles)} for good?",
    if (tiles.size == 1) "It can't be restored." else "They can't be restored.",
    "Delete", red = true,
)

fun restoreQuestion(tiles: List<Tile>) = MediaQuestion(
    R.drawable.ic_restore, "Restore ${kinds(tiles)}?",
    if (tiles.size == 1) "It goes back to the folder it came from." else "They go back to the folders they came from.",
    "Restore", red = false,
)

fun moveQuestion(tiles: List<Tile>, from: String, to: String) = MediaQuestion(
    R.drawable.ic_move, "Move ${kinds(tiles)} to $to?",
    if (tiles.size == 1) "It leaves $from and shows in $to." else "They leave $from and show in $to.",
    "Move", red = false,
)

/**
 * With Media management on, Android's requests finish with no dialog, so Memoria asks first and [go] runs on yes.
 * Off, [go] runs at once and Android asks, so nobody answers twice. The switch is read on every call.
 */
@Composable
fun rememberMediaConfirm(): (MediaQuestion, () -> Unit) -> Unit {
    val context = LocalContext.current
    var asking by remember { mutableStateOf<Pair<MediaQuestion, () -> Unit>?>(null) }
    asking?.let { (q, go) ->
        AlertDialog(
            onDismissRequest = { asking = null },
            icon = { Icon(painterResource(q.icon), null) },
            title = { Text(q.title) },
            text = { Text(q.body) },
            confirmButton = { TextButton({ asking = null; go() }) { Text(q.button, color = if (q.red) MaterialTheme.colorScheme.error else Color.Unspecified) } },
            dismissButton = { TextButton({ asking = null }) { Text("Cancel") } },
            iconContentColor = if (q.red) MaterialTheme.colorScheme.error else AlertDialogDefaults.iconContentColor,
        )
    }
    return { q, go -> if (MediaStore.canManageMedia(context)) asking = q to go else go() }
}
