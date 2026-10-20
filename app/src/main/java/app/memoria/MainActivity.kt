package app.memoria

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.WorkManager
import androidx.work.WorkInfo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
@OptIn(ExperimentalFoundationApi::class)
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
    // tiles a screen below are built early, so their thumbnails load before they scroll in
    val photosGrid = rememberLazyGridState(cacheWindow = LazyLayoutCacheWindow(aheadFraction = 1f))
    val scope = rememberCoroutineScope()
    var photosScrollTop by remember { mutableIntStateOf(0) } // bumped by a tab tap; the Photos tab reacts by scrolling to the top
    var viewing by rememberSaveable { mutableStateOf<Long?>(null) }
    var viewTiles by remember { mutableStateOf<List<Tile>>(emptyList()) }
    var viewerFrom by rememberSaveable { mutableStateOf("photos") } // the screen under the viewer, back from Show in album returns here
    var openPerson by rememberSaveable { mutableStateOf<Long?>(null) } // People screen shows this person, if set
    var personDirect by rememberSaveable { mutableStateOf(false) } // person was opened from Library, so back returns there
    var peopleFrom by rememberSaveable { mutableStateOf("library") } // back from People or a person returns here
    var shortcutFrom by rememberSaveable { mutableStateOf("library") } // back from Favorites or Videos returns here
    var searchFrom by rememberSaveable { mutableStateOf("library") } // back from Search returns to this tab
    var openAlbum by rememberSaveable { mutableStateOf(0L) }
    var albumFrom by rememberSaveable { mutableStateOf("library") } // back from an album returns here
    var namingAlbum by rememberSaveable { mutableStateOf(false) } // Albums shows the New album name sheet
    var newName by rememberSaveable { mutableStateOf("") } // the name typed in the New album sheet
    var newAlbum by rememberSaveable { mutableStateOf<String?>(null) } // the name the picker is filling
    var openPlace by rememberSaveable { mutableStateOf(0) }
    var placeFrom by rememberSaveable { mutableStateOf("library") } // back from a place returns here
    var recognizeFor by rememberSaveable { mutableStateOf<Long?>(null) } // Help recognize asks only about this person, if set
    var recognizeFrom by rememberSaveable { mutableStateOf("library") } // back from Help recognize returns here
    // Photos tab select mode (mockup "Photos, selecting"): the picked tiles, shared so the bottom bar swaps
    val selection = remember { PhotoSelection() }
    var photosReload by remember { mutableStateOf(0) } // bumped after a bulk action, so the grid refetches
    var pendingTrash by remember { mutableStateOf(false) }
    var pendingFavorite by remember { mutableStateOf<Boolean?>(null) }
    val selectAsk = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (pendingTrash) selection.tiles.values.forEach { forgetTile(it.id) }
            pendingFavorite?.let { fav -> selection.tiles.values.forEach { updateTile(it.copy(favorite = fav)) } }
            selection.clear()
            photosReload++
        }
        pendingTrash = false
        pendingFavorite = null
    }
    // Share, Favorite and Delete act on the whole picked set; Delete is one Android trash confirm for all
    val selectedTiles = { selection.tiles.values.toList() }
    val shareSelected = {
        val uris = ArrayList(selectedTiles().map { it.uri })
        if (uris.isNotEmpty()) {
            val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, null))
        }
    }
    val favoriteSelected = { favorite: Boolean ->
        val own = mutableListOf<Tile>()
        val foreign = mutableListOf<Tile>()
        for (t in selectedTiles()) runCatching { setFavorite(context, t, favorite) }.onSuccess { own += t }.onFailure { foreign += t }
        own.forEach { updateTile(it.copy(favorite = favorite)) }
        if (foreign.isEmpty()) { selection.clear(); photosReload++ }
        else {
            pendingFavorite = favorite
            val ask = MediaStore.createFavoriteRequest(context.contentResolver, foreign.map { it.uri }, favorite)
            selectAsk.launch(IntentSenderRequest.Builder(ask.intentSender).build())
        }
    }
    val deleteSelected = {
        val uris = selectedTiles().map { it.uri }
        if (uris.isNotEmpty()) {
            pendingTrash = true
            val ask = MediaStore.createTrashRequest(context.contentResolver, uris, true)
            selectAsk.launch(IntentSenderRequest.Builder(ask.intentSender).build())
        }
    }
    val open: (List<Tile>, Tile) -> Unit = { list, tile -> viewTiles = list; viewerFrom = screen; viewing = tile.id }
    // The viewer sits on top of the screen, so the grid stays under it: the photo grows out of its tile
    // and shrinks back in. The screen under is hidden from TalkBack while the viewer is open.
    Box(Modifier.fillMaxSize()) {
        Box(if (viewing != null) Modifier.clearAndSetSemantics {} else Modifier) {
            // A screen opened from a tab slides in from the right, and back slides out to the right over the tab.
            // Other moves (tab to tab, screen to screen) switch at once. Each screen paints its own background so the
            // one under it does not show through.
            AnimatedContent(screen, transitionSpec = {
                val open = isTab(initialState) && !isTab(targetState)
                val close = !isTab(initialState) && isTab(targetState)
                when {
                    open -> (slideInHorizontally(tween(250)) { it } togetherWith ExitTransition.KeepUntilTransitionsFinished).apply { targetContentZIndex = 1f }
                    close -> (EnterTransition.None togetherWith slideOutHorizontally(tween(250)) { it }).apply { targetContentZIndex = -1f }
                    else -> EnterTransition.None togetherWith ExitTransition.None
                }
            }, label = "screen") { target ->
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    when (target) {
                        "dev" -> {
                            BackHandler { screen = "settings" }
                            TestScreen(dir) { screen = "settings" }
                        }
                        "settings" -> {
                            BackHandler { screen = "library" }
                            SettingsScreen(onBack = { screen = "library" }, onHidden = { screen = "hidden" }, onLicenses = { screen = "licenses" }, onDev = { screen = "dev" })
                        }
                        "licenses" -> {
                            BackHandler { screen = "settings" }
                            LicensesScreen(onBack = { screen = "settings" })
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
                            val close = { tabs.removeState("people"); openPerson = null; personDirect = false; screen = peopleFrom }
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
                            val close = { tabs.removeState("favorites"); screen = shortcutFrom } // next visit starts at the top
                            BackHandler(onBack = close)
                            tabs.SaveableStateProvider("favorites") { // keeps the scroll while the viewer is open
                                PhotosScreen(favoritesOnly = true, onBack = close, onOpen = open)
                            }
                        }
                        "albums" -> {
                            val close = { tabs.removeState("albums"); namingAlbum = false; screen = "library" } // next visit starts at the top
                            BackHandler(onBack = close)
                            tabs.SaveableStateProvider("albums") { // keeps the scroll while an album is open
                                AlbumsScreen(
                                    onBack = close,
                                    onOpenAlbum = { openAlbum = it; albumFrom = "albums"; screen = "album" },
                                    onNewAlbum = { namingAlbum = true },
                                )
                            }
                            // New album names the folder first, then the picker opens (mockup "New album")
                            if (namingAlbum) NewAlbumSheet(newName, onDismiss = { namingAlbum = false; newName = "" }) { n ->
                                newName = n; namingAlbum = false; newAlbum = n; screen = "newalbum"
                            }
                        }
                        "newalbum" -> {
                            // Back keeps the typed name (reopens the sheet); Cancel makes nothing
                            val back = { screen = "albums"; namingAlbum = true }
                            val cancel = { newName = ""; screen = "albums" }
                            tabs.SaveableStateProvider("newalbum") { // keeps the picker while nothing else opens
                                NewAlbumScreen(
                                    name = newAlbum.orEmpty(),
                                    onBack = back,
                                    onCancel = cancel,
                                    onMoved = { id -> newName = ""; newAlbum = null; tabs.removeState("albums"); openAlbum = id; albumFrom = "albums"; screen = "album" },
                                )
                            }
                        }
                        "album" -> {
                            val close = { tabs.removeState("album"); screen = albumFrom }
                            BackHandler(onBack = close)
                            tabs.SaveableStateProvider("album") { AlbumScreen(openAlbum, onBack = close, onOpen = open) }
                        }
                        "places" -> {
                            val close = { tabs.removeState("places"); screen = "library" } // next visit starts at the top
                            BackHandler(onBack = close)
                            tabs.SaveableStateProvider("places") { // keeps the scroll while a place is open
                                PlacesScreen(onBack = close, onOpenPlace = { openPlace = it; placeFrom = "places"; screen = "place" })
                            }
                        }
                        "place" -> {
                            val close = { tabs.removeState("place"); screen = placeFrom }
                            BackHandler(onBack = close)
                            tabs.SaveableStateProvider("place") { PlaceScreen(openPlace, onBack = close, onOpen = open) }
                        }
                        "videos" -> {
                            val close = { tabs.removeState("videos"); screen = shortcutFrom } // next visit starts at the top
                            BackHandler(onBack = close)
                            tabs.SaveableStateProvider("videos") { VideosScreen(onBack = close, onOpen = open) } // keeps the scroll while the viewer is open
                        }
                        "search" -> {
                            val close = { tabs.removeState("search"); screen = searchFrom } // next visit starts empty
                            BackHandler(onBack = close)
                            tabs.SaveableStateProvider("search") { // keeps the typed text while a hit is open, so back returns to it
                                SearchScreen(
                                    onBack = close,
                                    onOpen = { h ->
                                        when (h.kind) {
                                            "person" -> { openPerson = h.key.toLong(); personDirect = true; peopleFrom = "search"; screen = "people" }
                                            "place" -> { openPlace = h.key.toInt(); placeFrom = "search"; screen = "place" }
                                            "album" -> { openAlbum = h.key.toLong(); albumFrom = "search"; screen = "album" }
                                            "videos", "favorites" -> { shortcutFrom = "search"; screen = h.kind }
                                            else -> { // a year or a month: the Photos tab, scrolled to that month's header
                                                tabs.removeState("search"); screen = "photos"
                                                val at = lastTimeline?.indexOfFirst { it is Header && it.key == h.key } ?: -1
                                                if (at >= 0) scope.launch { photosGrid.scrollToItem(at) }
                                            }
                                        }
                                    },
                                    onSeeAllPeople = { openPerson = null; personDirect = false; peopleFrom = "search"; screen = "people" },
                                )
                            }
                        }
                        "trash" -> {
                            BackHandler { screen = "library" }
                            TrashScreen { screen = "library" }
                        }
                        else -> Scaffold(bottomBar = {
                            // The tabs and the Select bar are the same height (both build on ShortNavigationBar),
                            // so crossfading them never moves the grid.
                            Crossfade(targetState = target == "photos" && selection.active, label = "bottomBar") { selecting ->
                                if (selecting) SelectBar(
                                    favorite = selection.allFavorite,
                                    onShare = shareSelected,
                                    onFavorite = { favoriteSelected(!selection.allFavorite) },
                                    onDelete = deleteSelected,
                                )
                                else ShortNavigationBar {
                                    ShortNavigationBarItem(
                                        target == "photos", {
                                            if (target == "photos") photosScrollTop++ else screen = "photos"
                                        }, { Icon(painterResource(R.drawable.ic_photo), null) }, { Text("Photos") },
                                        iconPosition = NavigationItemIconPosition.Start,
                                    )
                                    ShortNavigationBarItem(
                                        target == "library", { screen = "library" }, { Icon(painterResource(R.drawable.ic_library), null) }, { Text("Library") },
                                        iconPosition = NavigationItemIconPosition.Start,
                                    )
                                }
                            }
                        }) { padding ->
                            Column(Modifier.padding(padding)) {
                                tabs.SaveableStateProvider(target) {
                                    if (target == "photos") PhotosScreen(
                                        onOpen = open, grid = photosGrid, selection = selection, reload = photosReload,
                                        onSearch = { searchFrom = "photos"; screen = "search" },
                                        scrollToTop = photosScrollTop,
                                    )
                                    else LibraryScreen(
                                        onOpenSearch = { searchFrom = "library"; screen = "search" },
                                        onOpenPeople = { peopleFrom = "library"; screen = "people" },
                                        onOpenPerson = { openPerson = it; personDirect = true; peopleFrom = "library"; screen = "people" },
                                        onOpenAlbums = { screen = "albums" },
                                        onOpenAlbum = { openAlbum = it; albumFrom = "library"; screen = "album" },
                                        onOpenPlaces = { screen = "places" },
                                        onOpenPlace = { openPlace = it; placeFrom = "library"; screen = "place" },
                                        onOpenFavorites = { shortcutFrom = "library"; screen = "favorites" },
                                        onOpenVideos = { shortcutFrom = "library"; screen = "videos" },
                                        onOpenTrash = { screen = "trash" },
                                        onHelpRecognize = { recognizeFor = null; recognizeFrom = "library"; screen = "recognize" },
                                        onOpenSettings = { screen = "settings" },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        viewing?.let {
            Viewer(
                it, viewTiles, onBack = { viewing = null },
                // the viewer never opened an album from inside an album, so Show in album is hidden there
                showInAlbum = viewerFrom != "album",
                onShowInAlbum = { t -> albumOf(context, t.id)?.let { b -> viewing = null; openAlbum = b.id; albumFrom = viewerFrom; screen = "album" } },
            )
        }
    }
}

private fun isTab(screen: String) = screen == "photos" || screen == "library"

/** Library shortcut card from the mockup (Favorites, Videos, Trash). One line each, so the two cards in a row stay the same height. */
@Composable
fun Shortcut(icon: Int, name: String, info: String, modifier: Modifier, onClick: (() -> Unit)?) {
    Row(
        modifier.alpha(if (onClick == null) 0.38f else 1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = onClick != null) { onClick?.invoke() }.padding(14.dp),
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
fun TestScreen(dir: File, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf("Photos: ${dir.path}") }
    var preview by remember { mutableStateOf<Pair<Bitmap, List<Face>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val dao = remember { Db.get(context).index() }
    val work by remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("index") }.collectAsState(emptyList())
    // read again when a run starts or ends, not on every photo
    val running = work.any { it.state == WorkInfo.State.RUNNING }
    val numbers by produceState<DevNumbers?>(null, running) { value = withContext(Dispatchers.IO) { devNumbers(dao) } }
    val photos = Manifest.permission.READ_MEDIA_IMAGES
    val index = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[photos] == true) IndexControl.start(context, testOnly = true) else log += "\nNo photo permission"
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
    DevToolsContent(
        busy = busy,
        numbers = numbers,
        log = "Index ${work.firstOrNull()?.state ?: "-"}\n$log",
        preview = preview,
        onBack = onBack,
        onRunCpu = { start(false) },
        onRunNpu = { start(true) },
        onIndexTest = { index.launch(arrayOf(photos, Manifest.permission.POST_NOTIFICATIONS)) },
    )
}

fun runTest(dir: File, npu: Boolean, log: (String) -> Unit, show: (Pair<Bitmap, List<Face>>) -> Unit) {
    val mode = if (npu) "npu" else "cpu"
    var t = System.nanoTime()
    val ms = { (System.nanoTime() - t) / 1_000_000.0 }
    val pipeline = FacePipeline(
        ortEnv(),
        File(dir.parentFile, "models/detector.onnx").path,
        File(dir.parentFile, "models/recognizer.onnx").path,
        File(dir.parentFile, "models/quality.onnx").path,
    ) { if (npu) npuSession(ortEnv(), File(it), File(dir.parentFile, "npu")) else ortEnv().createSession(it) }
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
