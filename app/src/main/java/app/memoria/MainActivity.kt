package app.memoria

import ai.onnxruntime.OrtEnvironment
import android.Manifest
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.WorkManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Light app: dark bar icons even when the phone is in dark mode
        val bars = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(bars, bars)
        super.onCreate(savedInstanceState)
        val dir = File(getExternalFilesDir(null), "test").apply { mkdirs() }
        setContent { MemoriaTheme { Surface { App(dir) } } }
    }
}

/** Two tabs, Photos and Library. The test screen opens from Library. */
@Composable
fun App(dir: File) {
    var screen by rememberSaveable { mutableStateOf("photos") }
    // new photos start an index run on their own while the app is open (see AutoIndex.kt)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val watcher = watchNewPhotos(context, lifecycleOwner.lifecycle)
        onDispose { watcher.close() }
    }
    val tabs = rememberSaveableStateHolder() // keeps each tab's scroll while the other one is open
    val photosGrid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var viewing by rememberSaveable { mutableStateOf<Long?>(null) }
    var viewTiles by remember { mutableStateOf<List<Tile>>(emptyList()) }
    var openPerson by rememberSaveable { mutableStateOf<Long?>(null) } // People screen shows this person, if set
    var personDirect by rememberSaveable { mutableStateOf(false) } // person was opened from Library, so back returns there
    var openAlbum by rememberSaveable { mutableStateOf(0L) }
    var albumFrom by rememberSaveable { mutableStateOf("library") } // back from an album returns here
    var recognizeFor by rememberSaveable { mutableStateOf<Long?>(null) } // Help recognize asks only about this person, if set
    var recognizeFrom by rememberSaveable { mutableStateOf("library") } // back from Help recognize returns here
    viewing?.let { return Viewer(it, viewTiles) { viewing = null } }
    val open: (List<Tile>, Tile) -> Unit = { list, tile -> viewTiles = list; viewing = tile.id }
    when (screen) {
        "dev" -> {
            BackHandler { screen = "library" }
            TestScreen(dir) { screen = "people" }
        }
        "settings" -> {
            BackHandler { screen = "library" }
            SettingsScreen(onBack = { screen = "library" }, onHidden = { screen = "hidden" })
        }
        "hidden" -> {
            val close = { tabs.removeState("hidden"); openPerson = null; screen = "settings" }
            tabs.SaveableStateProvider("hidden") { // keeps the open person while the viewer is on top
                PeopleScreen(
                    openId = openPerson,
                    onOpenPerson = { openPerson = it },
                    onPersonBack = { openPerson = null },
                    onBack = close,
                    onOpen = open,
                    onHelpRecognize = { recognizeFor = it; recognizeFrom = "hidden"; screen = "recognize" },
                    hiddenOnly = true,
                )
            }
        }
        "people" -> {
            // removeState on close: the next visit starts at the list, not the last person
            val close = { tabs.removeState("people"); openPerson = null; personDirect = false; screen = "library" }
            tabs.SaveableStateProvider("people") { // keeps the open person and list scroll while the viewer is on top
                PeopleScreen(
                    openId = openPerson,
                    onOpenPerson = { openPerson = it; personDirect = false },
                    onPersonBack = { if (personDirect) close() else openPerson = null },
                    onBack = close,
                    onOpen = open,
                    onHelpRecognize = { recognizeFor = it; recognizeFrom = "people"; screen = "recognize" },
                )
            }
        }
        "recognize" -> {
            val close = { screen = recognizeFrom }
            BackHandler(onBack = close)
            HelpRecognizeScreen(recognizeFor, onBack = close)
        }
        "favorites" -> {
            val close = { tabs.removeState("favorites"); screen = "library" } // next visit starts at the top
            BackHandler(onBack = close)
            tabs.SaveableStateProvider("favorites") { // keeps the scroll while the viewer is open
                Box(Modifier.safeDrawingPadding()) { PhotosScreen(favoritesOnly = true, onBack = close, onOpen = open) }
            }
        }
        "albums" -> {
            val close = { tabs.removeState("albums"); screen = "library" } // next visit starts at the top
            BackHandler(onBack = close)
            tabs.SaveableStateProvider("albums") { // keeps the scroll while an album is open
                AlbumsScreen(onBack = close, onOpenAlbum = { openAlbum = it; albumFrom = "albums"; screen = "album" })
            }
        }
        "album" -> {
            val close = { tabs.removeState("album"); screen = albumFrom }
            BackHandler(onBack = close)
            tabs.SaveableStateProvider("album") { AlbumScreen(openAlbum, onBack = close, onOpen = open) }
        }
        "videos" -> {
            val close = { tabs.removeState("videos"); screen = "library" } // next visit starts at the top
            BackHandler(onBack = close)
            tabs.SaveableStateProvider("videos") { VideosScreen(onBack = close, onOpen = open) } // keeps the scroll while the viewer is open
        }
        "trash" -> {
            BackHandler { screen = "library" }
            TrashScreen { screen = "library" }
        }
        else -> Scaffold(bottomBar = {
            ShortNavigationBar {
                ShortNavigationBarItem(
                    screen == "photos", {
                        if (screen == "photos") scope.launch { photosGrid.animateScrollToItem(0) } else screen = "photos"
                    }, { Icon(painterResource(R.drawable.ic_photo), null) }, { Text("Photos") },
                    iconPosition = NavigationItemIconPosition.Start,
                )
                ShortNavigationBarItem(
                    screen == "library", { screen = "library" }, { Icon(painterResource(R.drawable.ic_library), null) }, { Text("Library") },
                    iconPosition = NavigationItemIconPosition.Start,
                )
            }
        }) { padding ->
            Column(Modifier.padding(padding)) {
                tabs.SaveableStateProvider(screen) {
                    if (screen == "photos") PhotosScreen(onOpen = open, grid = photosGrid)
                    else LibraryScreen(
                        onOpenPeople = { screen = "people" },
                        onOpenPerson = { openPerson = it; personDirect = true; screen = "people" },
                        onOpenAlbums = { screen = "albums" },
                        onOpenAlbum = { openAlbum = it; albumFrom = "library"; screen = "album" },
                        onOpenFavorites = { screen = "favorites" },
                        onOpenVideos = { screen = "videos" },
                        onOpenTrash = { screen = "trash" },
                        onHelpRecognize = { recognizeFor = null; recognizeFrom = "library"; screen = "recognize" },
                        onOpenSettings = { screen = "settings" },
                        onDev = { screen = "dev" },
                    )
                }
            }
        }
    }
}

/** Library shortcut card from the mockup (Favorites, Videos, Trash). One line each, so the two cards in a row stay the same height. */
@Composable
fun Shortcut(icon: Int, name: String, info: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick).padding(14.dp),
        Arrangement.spacedBy(12.dp), Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Runs models from <app files>/models on photos in <app files>/test, writes results_cpu/npu.json. */
@Composable
fun TestScreen(dir: File, onPeople: () -> Unit) {
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf("Photos: ${dir.path}") }
    var preview by remember { mutableStateOf<Pair<Bitmap, List<Face>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val dao = remember { Db.get(context).index() }
    val kinds by remember { dao.kinds() }.collectAsState(emptyMap())
    val faces by remember { dao.faceCount() }.collectAsState(0)
    val work by remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("index") }.collectAsState(emptyList())
    var total by remember { mutableStateOf(0) }
    var testOnly by remember { mutableStateOf(true) }
    val photos = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    val index = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[photos] == true) {
            total = if (testOnly) testPhotos(context).size else libraryPhotos(context).size
            IndexControl.start(context, testOnly)
        } else log += "\nNo photo permission"
    }
    val start = { npu: Boolean ->
        busy = true
        scope.launch(Dispatchers.Default) {
            try {
                runTest(dir, npu, { log += "\n$it" }) { preview = it }
            } catch (e: Exception) {
                log += "\nError: $e"
            }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row {
            Button(onClick = { start(false) }, enabled = !busy) { Text("Run on CPU") }
            Button(onClick = { start(true) }, enabled = !busy, modifier = Modifier.padding(start = 8.dp)) { Text("Run on NPU") }
        }
        Button(onClick = {
            testOnly = true
            index.launch(arrayOf(photos, Manifest.permission.POST_NOTIFICATIONS))
        }) { Text("Index test folder") }
        Button(onClick = {
            testOnly = false
            index.launch(arrayOf(photos, Manifest.permission.POST_NOTIFICATIONS))
        }) { Text("Index all photos") }
        Button(onClick = {
            scope.launch(Dispatchers.IO) {
                dao.clearFaces(); dao.clearAnswers(); dao.clearMerges(); dao.clearPhotos(); dao.deletePeople(); dao.clearPersonIds()
                IndexProgress.finish(context)
            }
        }) { Text("Clear index") }
        Row {
            Button(onClick = { IndexControl.pause(context) }) { Text("Pause") }
            Button(onClick = { IndexControl.resume(context) }, modifier = Modifier.padding(start = 8.dp)) { Text("Resume") }
        }
        Button(onClick = onPeople) { Text("People") }
        Text("Index ${work.firstOrNull()?.state ?: "-"}: ${kinds.values.sum()} / $total photos $kinds, $faces faces")
        preview?.let { (bitmap, faces) ->
            Box(Modifier.padding(vertical = 8.dp)) {
                Image(bitmap.asImageBitmap(), null, Modifier.fillMaxWidth())
                Canvas(Modifier.matchParentSize()) {
                    val s = size.width / bitmap.width
                    for (f in faces) {
                        drawRect(Color.Green, Offset(f.box[0] * s, f.box[1] * s), Size((f.box[2] - f.box[0]) * s, (f.box[3] - f.box[1]) * s), style = Stroke(3f))
                    }
                }
            }
        }
        Text(log, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

fun runTest(dir: File, npu: Boolean, log: (String) -> Unit, show: (Pair<Bitmap, List<Face>>) -> Unit) {
    val mode = if (npu) "npu" else "cpu"
    var t = System.nanoTime()
    val ms = { (System.nanoTime() - t) / 1_000_000.0 }
    val pipeline = FacePipeline(
        OrtEnvironment.getEnvironment(),
        File(dir.parentFile, "models/detector.onnx").path,
        File(dir.parentFile, "models/recognizer.onnx").path,
        File(dir.parentFile, "models/quality.onnx").path,
    ) { if (npu) npuSession(OrtEnvironment.getEnvironment(), File(it), File(dir.parentFile, "npu")) else OrtEnvironment.getEnvironment().createSession(it) }
    log("$mode: models loaded in %.0f ms".format(ms()))
    val photos = dir.walk().filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }.sorted().toList()
    val results = JSONObject()
    var decode = 0.0; var detect = 0.0; var embed = 0.0; var faces = 0; var most = -1
    pipeline.use {
        for ((i, file) in photos.withIndex()) {
            t = System.nanoTime(); val image = loadPhoto(ImageDecoder.createSource(file)); decode += ms()
            t = System.nanoTime(); val found = pipeline.detect(image); detect += ms()
            t = System.nanoTime(); found.forEach { pipeline.embed(image, it) }; embed += ms()
            faces += found.size
            results.put(file.relativeTo(dir).path, JSONArray(found.map { f ->
                JSONObject().put("box", JSONArray(f.box.toList())).put("score", f.score.toDouble())
                    .put("lm", JSONArray(f.landmarks.toList())).put("vec", JSONArray(f.vector!!.toList())).put("q", f.quality!!.toDouble())
            }))
            if (found.size > most) {
                most = found.size
                val s = 1024f / maxOf(image.width, image.height)
                show(Bitmap.createScaledBitmap(image, (image.width * s).toInt(), (image.height * s).toInt(), true) to
                    found.map { Face(FloatArray(4) { k -> it.box[k] * s }, it.score, it.landmarks) })
            }
            if (i % 20 == 19) log("${i + 1}/${photos.size}")
        }
    }
    File(dir.parentFile, "results_$mode.json").writeText(results.toString())
    val n = photos.size.coerceAtLeast(1)
    log("$mode: ${photos.size} photos, $faces faces. Per photo: decode %.0f ms, detect %.0f ms. Per face: embed %.0f ms".format(decode / n, detect / n, embed / faces.coerceAtLeast(1)))
}
