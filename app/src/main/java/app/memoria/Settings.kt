package app.memoria

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings from the Library gear. Folders to skip and backup wait for their design. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onHidden: () -> Unit, onLicenses: () -> Unit, onDev: () -> Unit) {
    val context = LocalContext.current
    val debug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val dao = remember { Db.get(context).index() }
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    val hidden by cachedProduceState("settings", reload) { withContext(Dispatchers.IO) { dao.people().count { it.hidden } } }
    val work by remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("index") }.collectAsState(emptyList())
    var charging by remember { mutableStateOf(IndexProgress.chargingOnly(context)) }
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var allowed by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        allowed = power.isIgnoringBatteryOptimizations(context.packageName)
    }
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
    var rescan by remember { mutableStateOf(false) }
    val setCharging = { on: Boolean ->
        charging = on
        IndexProgress.setChargingOnly(context, on)
        if (work.any { !it.state.isFinished }) IndexControl.resume(context) // a run that is on now picks up the new rule
    }

    SettingsContent(
        charging, allowed, hidden, version,
        modelsReady = remember { modelsReady(context) },
        onBack = onBack,
        onCharging = setCharging,
        onBackgroundWork = {
            val intent = if (allowed) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            battery.launch(intent)
        },
        onRescan = { rescan = true },
        onHidden = onHidden,
        onLicenses = onLicenses,
        skipped = remember { IndexProgress.skipped(context).size },
        onDev = onDev.takeIf { debug },
    )

    if (rescan) AlertDialog(
        onDismissRequest = { rescan = false },
        icon = { Icon(painterResource(R.drawable.ic_refresh), null) },
        title = { Text("Find faces again?") },
        text = { Text("All faces, people and names will be deleted. Then every photo is scanned again, this takes a while.") },
        confirmButton = {
            TextButton({
                rescan = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        IndexControl.pause(context)
                        dao.clearFaces(); dao.clearAnswers(); dao.clearMerges(); dao.clearPhotos(); dao.deletePeople(); dao.clearPersonIds()
                        IndexProgress.finish(context)
                    }
                    IndexControl.start(context, testOnly = false)
                    reload++
                }
            }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete and scan") }
        },
        dismissButton = { TextButton({ rescan = false }) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.background,
        iconContentColor = MaterialTheme.colorScheme.error,
    )
}

/** Settings rows per the mockup. [hidden] null: still counting. */
@Composable
fun SettingsContent(
    charging: Boolean,
    allowed: Boolean,
    hidden: Int?,
    version: String?,
    modelsReady: Boolean = true,
    onBack: () -> Unit = {},
    onCharging: (Boolean) -> Unit = {},
    onBackgroundWork: () -> Unit = {},
    onRescan: () -> Unit = {},
    onHidden: () -> Unit = {},
    onLicenses: () -> Unit = {},
    skipped: Int = 0,
    onDev: (() -> Unit)? = null, // null: a release build, no Developer section
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        TopBar("Settings", null, onBack = onBack)
        SectionHead("Finding faces")
        // The download is not built yet: no onClick greys the row, and the button is off
        SettingRow(
            if (modelsReady) R.drawable.ic_check else R.drawable.ic_download, "Face models",
            if (modelsReady) "Ready · 169 MB" else "Not downloaded · 169 MB",
        ) { if (!modelsReady) PillButton("Download", {}, enabled = false) }
        SettingRow(R.drawable.ic_bolt, "Only while charging", "Pause face search on battery", { onCharging(!charging) }) {
            Switch(
                charging, onCharging,
                colors = SwitchDefaults.colors(
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
        SettingRow(
            R.drawable.ic_battery, "Background work",
            if (allowed) "Allowed. Face search goes on with the screen off" else "Not allowed. The phone may stop face search",
            onBackgroundWork,
        ) { Chevron() }
        SettingRow(
            R.drawable.ic_folder, "Folders to skip",
            if (skipped == 0) "None, all photos are searched" else "$skipped ${if (skipped == 1) "album" else "albums"}",
        ) { Chevron() }
        SettingRow(R.drawable.ic_refresh, "Find faces again", "Deletes faces and names, scans all photos again", onRescan)
        SectionHead("People")
        SettingRow(
            R.drawable.ic_block, "Hidden people",
            hidden?.let { if (it == 0) "No people" else "$it ${if (it == 1) "person" else "people"}" } ?: "\u00A0",
            onHidden,
        ) { Chevron() }
        SectionHead("Backup", hint = "Names and faces, not photos")
        SettingRow(R.drawable.ic_download, "Export face data", "Save to a file")
        SettingRow(R.drawable.ic_upload, "Import face data", "From an export file")
        SectionHead("About")
        SettingRow(R.drawable.ic_info, "Memoria Photo $version", "Open source, GPL-3.0 · Licenses", onLicenses) { Chevron() }
        if (onDev != null) {
            SectionHead("Developer", hint = "Debug builds only")
            SettingRow(R.drawable.ic_settings, "Dev tools", "Test the models, index the test folder", onDev) { Chevron() }
        }
    }
}

/** Mockup `.set`: icon, title, one muted line, then a switch or a chevron. No [onClick]: not built yet, greyed out. */
@Composable
fun SettingRow(icon: Int, title: String, info: String, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().alpha(if (onClick == null) 0.38f else 1f).heightIn(min = 56.dp).clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            Text(info, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
private fun Chevron() = Icon(painterResource(R.drawable.ic_chevron), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
