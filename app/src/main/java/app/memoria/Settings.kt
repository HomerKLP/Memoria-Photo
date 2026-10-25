package app.memoria

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.withTransaction
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Settings from the Library gear. Backup waits for its design. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onHidden: () -> Unit, onSkip: () -> Unit, onLicenses: () -> Unit, onDev: () -> Unit, atUpdates: Boolean = false) {
    val context = LocalContext.current
    val debug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val dao = remember { Db.get(context).index() }
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    val hidden by cachedProduceState("settings", reload) { withContext(Dispatchers.IO) { dao.people().count { it.hidden } } }
    val work by remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("index") }.collectAsState(emptyList())
    val running = work.any { it.state == WorkInfo.State.RUNNING }
    val indexed by remember { dao.kinds() }.collectAsState(emptyMap())
    val total by cachedProduceState("photoCount") { withContext(Dispatchers.IO) { libraryPhotoCount(context) } }
    var charging by remember { mutableStateOf(IndexProgress.chargingOnly(context)) }
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var allowed by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        allowed = power.isIgnoringBatteryOptimizations(context.packageName)
    }
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
    var rescan by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(Models.status(context)) }
    LaunchedEffect(models.first) {
        while (models.first == Models.Status.Downloading) { delay(1000); models = withContext(Dispatchers.IO) { Models.status(context) } }
    }
    var update by remember { mutableStateOf(Updates.status(context)) }
    LaunchedEffect(update.status) {
        while (update.status == Updates.Status.Downloading) { delay(1000); update = withContext(Dispatchers.IO) { Updates.status(context) } }
    }
    var autoUpdate by remember { mutableStateOf(Updates.auto(context)) }
    // the update notification: About is at the bottom
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) { if (atUpdates) scroll.scrollTo(Int.MAX_VALUE) }
    val setCharging = { on: Boolean ->
        charging = on
        IndexProgress.setChargingOnly(context, on)
        if (work.any { !it.state.isFinished }) IndexControl.resume(context) // a run that is on now picks up the new rule
    }

    SettingsContent(
        charging, allowed, hidden, version,
        models = models.first,
        modelsDone = models.second,
        onDownload = { Models.download(context); models = Models.status(context) },
        onCancelDownload = { Models.cancel(context); models = Models.status(context) },
        indexing = running,
        waiting = !running && work.any { it.state == WorkInfo.State.ENQUEUED } && waitsForCharger(context),
        paused = !running && IndexProgress.paused(context),
        indexed = indexed.values.sum(),
        total = total ?: 0,
        startedAt = IndexProgress.startedAt(context),
        base = IndexProgress.base(context),
        onPauseIndex = { IndexControl.pause(context) },
        onResumeIndex = { IndexControl.resume(context) },
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
        onSkip = onSkip,
        onDev = onDev.takeIf { debug },
        update = update.takeUnless { debug },
        autoUpdate = autoUpdate,
        onCheck = {
            update = update.copy(status = Updates.Status.Checking)
            scope.launch {
                val ok = Updates.check(context, notify = false)
                // a failed check keeps a version found before
                update = Updates.status(context).let { if (!ok && it.status == Updates.Status.UpToDate) it.copy(status = Updates.Status.Failed) else it }
            }
        },
        onUpdate = { Updates.download(context); update = Updates.status(context) },
        onCancelUpdate = { Updates.cancel(context); update = Updates.status(context) },
        onInstall = { Updates.install(context) },
        onReleasePage = { Updates.openPage(context) },
        onAutoUpdate = { on -> autoUpdate = on; Updates.setAuto(context, on) },
        scroll = scroll,
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
                    // NonCancellable and one transaction: a Back halfway never leaves photos marked done with no faces, or the index paused
                    withContext(Dispatchers.IO + NonCancellable) {
                        IndexControl.pause(context)
                        Db.get(context).withTransaction {
                            dao.clearFaces(); dao.clearAnswers(); dao.clearMerges(); dao.clearPhotos(); dao.deletePeople(); dao.clearPersonIds()
                        }
                        IndexProgress.finish(context)
                        IndexControl.start(context, testOnly = false)
                    }
                    reload++
                }
            }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete and scan") }
        },
        dismissButton = { TextButton({ rescan = false }) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.background,
        iconContentColor = MaterialTheme.colorScheme.error,
    )
}

/** Folders to skip: every album, a check on the ones face search skips. The next index run drops or searches them. */
@Composable
fun SkipScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val albums by cachedProduceState("albums") { withContext(Dispatchers.IO) { albums(context) } }
    var skipped by remember { mutableStateOf(IndexProgress.skipped(context)) }
    SkipContent(albums, skipped, onBack) { id, on -> IndexProgress.setSkipped(context, id, on); skipped = IndexProgress.skipped(context) }
}

/** Mockup "Folders to skip". Checking an album asks first, unchecking does not. [albums] null: still loading. */
@Composable
fun SkipContent(albums: List<Album>?, skipped: Set<Long>, onBack: () -> Unit = {}, onSkip: (Long, Boolean) -> Unit = { _, _ -> }) {
    var asking by remember { mutableStateOf<Album?>(null) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopBar("Folders to skip", if (skipped.isEmpty()) "None skipped" else "${skipped.size} skipped", onBack = onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { Note(R.drawable.ic_info, "No face search in a checked album. Its photos stay", Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) }
            items(albums.orEmpty(), key = { it.id }) { a ->
                val on = a.id in skipped
                PickRow(a.tiles.first(), label = a.name, note = "${a.tiles.size}", checked = on) { if (on) onSkip(a.id, false) else asking = a }
            }
        }
    }
    asking?.let { a -> IgnoreFacesDialog(a.name, onDismiss = { asking = null }) { asking = null; onSkip(a.id, true) } }
}

/** Settings rows per the mockup. [hidden] null: still counting. */
@Composable
fun SettingsContent(
    charging: Boolean,
    allowed: Boolean,
    hidden: Int?,
    version: String?,
    models: Models.Status = Models.Status.Ready,
    modelsDone: Long = 0,
    onDownload: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    indexing: Boolean = false,
    waiting: Boolean = false,
    paused: Boolean = false,
    indexed: Int = 0,
    total: Int = 0,
    startedAt: Long = 0L,
    base: Int = 0,
    onPauseIndex: () -> Unit = {},
    onResumeIndex: () -> Unit = {},
    onBack: () -> Unit = {},
    onCharging: (Boolean) -> Unit = {},
    onBackgroundWork: () -> Unit = {},
    onRescan: () -> Unit = {},
    onHidden: () -> Unit = {},
    onLicenses: () -> Unit = {},
    skipped: Int = 0,
    onSkip: () -> Unit = {},
    onDev: (() -> Unit)? = null, // null: a release build, no Developer section
    update: Updates.State? = null, // null: a debug build, no Updates rows
    autoUpdate: Boolean = true,
    onCheck: () -> Unit = {},
    onUpdate: () -> Unit = {},
    onCancelUpdate: () -> Unit = {},
    onInstall: () -> Unit = {},
    onReleasePage: () -> Unit = {},
    onAutoUpdate: (Boolean) -> Unit = {},
    scroll: ScrollState = rememberScrollState(),
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(scroll).padding(bottom = 16.dp)) {
        TopBar("Settings", null, onBack = onBack)
        SectionHead("Finding faces")
        FaceModelsRow(models, modelsDone, onDownload, onCancelDownload)
        FaceSearchRow(indexing, waiting, paused, indexed, total, startedAt, base, onPauseIndex, onResumeIndex)
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
            if (skipped == 0) "None, all photos are searched" else "$skipped skipped",
            onSkip,
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
        if (update != null) {
            UpdatesRow(update, onCheck, onUpdate, onCancelUpdate, onInstall, onReleasePage)
            SettingRow(R.drawable.ic_history, "Check for updates", "Once a day, notifies you of a new version", { onAutoUpdate(!autoUpdate) }) {
                Switch(
                    autoUpdate, onAutoUpdate,
                    colors = SwitchDefaults.colors(
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
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

/** Mockup "Face models": the same build as [FaceSearchRow], so its height and button width never change. */
@Composable
fun FaceModelsRow(status: Models.Status, done: Long, onDownload: () -> Unit, onCancel: () -> Unit) {
    val total = Models.mb(Models.TOTAL)
    val info = when (status) {
        Models.Status.Ready -> "Ready · $total MB"
        Models.Status.Missing -> "Not downloaded · $total MB"
        Models.Status.Downloading -> "Downloading · ${Models.mb(done)} of $total MB"
        Models.Status.Stopped -> "Stopped · ${Models.mb(done)} of $total MB"
    }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(if (status == Models.Status.Ready) R.drawable.ic_check else R.drawable.ic_download), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text("Face models", fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                Text(info, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val button = Modifier.testTag("modelsAction").width(88.dp)
            when (status) {
                Models.Status.Ready -> {}
                // no side padding, like the mockup: "Download" wraps in 88 dp with it
                Models.Status.Missing -> PillButton("Download", onDownload, modifier = button, sides = 0.dp)
                Models.Status.Downloading -> PillButton("Cancel", onCancel, modifier = button, sides = 0.dp)
                Models.Status.Stopped -> PillButton("Retry", onDownload, modifier = button, sides = 0.dp)
            }
        }
        if (status == Models.Status.Downloading || status == Models.Status.Stopped) LinearProgressIndicator(
            { (done.toFloat() / Models.TOTAL).coerceIn(0f, 1f) },
            Modifier.testTag("modelsBar").align(Alignment.BottomStart).padding(start = 56.dp, end = 120.dp, bottom = 4.dp).fillMaxWidth().height(4.dp),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Mockup "Updates": built like [FaceModelsRow]. While a newer version is known, a tap on the row opens its release page. */
@Composable
fun UpdatesRow(
    state: Updates.State, onCheck: () -> Unit, onUpdate: () -> Unit, onCancel: () -> Unit, onInstall: () -> Unit, onReleasePage: () -> Unit,
) {
    val size = Models.mb(state.size)
    val whatsNew = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
    val info = buildAnnotatedString {
        when (state.status) {
            Updates.Status.UpToDate -> append(
                if (state.checkedAt == 0L) "Not checked yet"
                else "Up to date · checked " + if (DateUtils.isToday(state.checkedAt)) "today"
                else DateTimeFormatter.ofPattern("d MMM").format(Instant.ofEpochMilli(state.checkedAt).atZone(ZoneId.systemDefault()))
            )
            Updates.Status.Checking -> append("Checking…")
            Updates.Status.Failed -> append("Couldn't check")
            Updates.Status.New -> { append("${state.version} · $size MB · "); withStyle(whatsNew) { append("What's new") } }
            Updates.Status.Downloading -> append("Downloading · ${Models.mb(state.done)} of $size MB")
            Updates.Status.Stopped -> append("Stopped · $size MB")
            Updates.Status.Ready -> { append("${state.version} downloaded · "); withStyle(whatsNew) { append("What's new") } }
        }
    }
    val known = state.status in listOf(Updates.Status.New, Updates.Status.Downloading, Updates.Status.Stopped, Updates.Status.Ready)
    Box(Modifier.fillMaxWidth().clickable(enabled = known, onClick = onReleasePage)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(if (known) R.drawable.ic_download else R.drawable.ic_check), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text("Updates", fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                Text(info, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val button = Modifier.testTag("updateAction").width(88.dp)
            when (state.status) {
                Updates.Status.UpToDate, Updates.Status.Failed -> PillButton("Check", onCheck, modifier = button, sides = 0.dp)
                Updates.Status.Checking -> PillButton("Check", onCheck, enabled = false, modifier = button.alpha(0.38f), sides = 0.dp)
                Updates.Status.New -> PillButton("Update", onUpdate, modifier = button, sides = 0.dp)
                Updates.Status.Downloading -> PillButton("Cancel", onCancel, modifier = button, sides = 0.dp)
                Updates.Status.Stopped -> PillButton("Retry", onUpdate, modifier = button, sides = 0.dp)
                Updates.Status.Ready -> PillButton("Install", onInstall, modifier = button, sides = 0.dp)
            }
        }
        if (state.status == Updates.Status.Downloading) LinearProgressIndicator(
            { if (state.size > 0) (state.done.toFloat() / state.size).coerceIn(0f, 1f) else 0f },
            Modifier.testTag("updateBar").align(Alignment.BottomStart).padding(start = 56.dp, end = 120.dp, bottom = 4.dp).fillMaxWidth().height(4.dp),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Mockup "Face search": the index run, the only place it shows. The same height in every state: the bar
 * sits on the bottom edge and the button keeps its width, so the rows below never move.
 */
@Composable
fun FaceSearchRow(
    indexing: Boolean, waiting: Boolean, paused: Boolean, indexed: Int, total: Int, startedAt: Long, base: Int,
    onPause: () -> Unit, onResume: () -> Unit,
) {
    val run = indexing || waiting || paused
    // time left from this run only: photos already indexed before the run (base) do not count
    val eta = if (indexing) etaMillis(startedAt, (indexed - base).coerceAtLeast(0), (total - base).coerceAtLeast(0)) else null
    val info = when {
        waiting -> "Waiting for charger"
        paused -> "Paused · $indexed of $total"
        !indexing -> "Done · $indexed photos"
        total <= 0 -> "$indexed photos"
        eta != null -> "$indexed of $total · ${formatEta(eta)}"
        else -> "$indexed of $total"
    }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(if (run) R.drawable.ic_search else R.drawable.ic_check), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text("Face search", fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                // one line: a second line mid-run would push the rows below down
                Text(info, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (run) PillButton(if (paused) "Resume" else "Pause", if (paused) onResume else onPause, modifier = Modifier.testTag("indexAction").width(88.dp))
        }
        // from the text's left edge to the button's: 16 + 24 + 16 dp in, 16 + 88 + 16 dp from the end
        val bar = Modifier.testTag("indexBar").align(Alignment.BottomStart).padding(start = 56.dp, end = 120.dp, bottom = 4.dp).fillMaxWidth().height(4.dp)
        if (run && total > 0) LinearProgressIndicator({ (indexed.toFloat() / total).coerceIn(0f, 1f) }, bar, color = MaterialTheme.colorScheme.primary)
        else if (run) LinearProgressIndicator(bar, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Chevron() = Icon(painterResource(R.drawable.ic_chevron), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
