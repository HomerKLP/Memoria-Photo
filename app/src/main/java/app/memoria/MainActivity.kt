package app.memoria

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.ConnectivityManager
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
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
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import androidx.work.WorkInfo
import com.google.ai.edge.litert.Accelerator
import java.io.File
import java.io.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    // bumped by a tap on the index or update notification, so App opens Settings; the update one scrolls to Updates
    private var openSettings by mutableIntStateOf(0)
    private var atUpdates by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light app: dark bar icons even when the phone is in dark mode
        val bars = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(bars, bars)
        super.onCreate(savedInstanceState)
        // The phone caps apps it does not know at 90 Hz; ask for its fastest mode at the same resolution
        window.attributes = window.attributes.apply {
            val now = display.mode
            preferredDisplayModeId = display.supportedModes
                .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
                .maxBy { it.refreshRate }.modeId
        }
        // not on a restore: the same intent comes back, and Settings would open again
        if (savedInstanceState == null && intent.getBooleanExtra("openSettings", false)) { atUpdates = intent.getBooleanExtra("atUpdates", false); openSettings++ }
        val dir = File(getExternalFilesDir(null), "test").apply { mkdirs() }
        // the models come by themselves on Wi-Fi; on mobile data, or after Cancel, the user taps Download or Retry
        lifecycleScope.launch(Dispatchers.IO) {
            val wifi = getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == false
            if (wifi && Models.status(this@MainActivity).first == Models.Status.Missing) Models.download(this@MainActivity, wifiOnly = true)
            Updates.tidy(this@MainActivity)
        }
        val debug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debug && Updates.auto(this)) Updates.schedule(this)
        setContent { MemoriaTheme { Surface { App(dir, openSettings, atUpdates) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("openSettings", false)) { atUpdates = intent.getBooleanExtra("atUpdates", false); openSettings++ }
    }
}

/** Two tabs, Photos and Library. The test screen opens from Library. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun App(dir: File, openSettings: Int = 0, atUpdates: Boolean = false) {
    var tab by rememberSaveable { mutableStateOf("photos") } // the tab under the opened screens
    // the screens opened over the tab, oldest first; Back closes the last one
    var stack by rememberSaveable { mutableStateOf(emptyList<Screen>()) }
    val tabs = rememberSaveableStateHolder() // keeps each tab's scroll while the other one is open, and each open screen's
    val push = { s: Screen -> stack = stack + s }
    // a closed screen's state goes with it, so its next visit starts at the top
    val back = { stack.lastOrNull()?.let { tabs.removeState(screenKey(stack.lastIndex, it)) }; stack = stack.dropLast(1) }
    // the same screen with new arguments (a person opened inside People): it keeps its state
    val replaceTop = { s: Screen -> stack = stack.dropLast(1) + s }
    // first, so a screen's own Back (a sheet, select mode) goes before it
    BackHandler(stack.isNotEmpty(), back)
    // bumped when a screen goes back to its tab: Library stays composed under it, so it reloads to show changes made there
    var returns by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { snapshotFlow { stack.isEmpty() }.drop(1).collect { if (it) returns++ } }
    // new photos start an index run on their own while the app is open (see AutoIndex.kt)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val watcher = watchNewPhotos(context, lifecycleOwner.lifecycle)
        onDispose { watcher.close() }
    }
    // tiles a screen below are built early, so their thumbnails load before they scroll in
    val photosGrid = rememberLazyGridState(cacheWindow = LazyLayoutCacheWindow(aheadFraction = 1f))
    val scope = rememberCoroutineScope()
    var photosScrollTop by remember { mutableIntStateOf(0) } // bumped by a tab tap; the Photos tab reacts by scrolling to the top
    var viewing by rememberSaveable { mutableStateOf<Long?>(null) }
    // the index or update notification: Settings over Library, whatever was open
    LaunchedEffect(openSettings) {
        if (openSettings == 0) return@LaunchedEffect
        viewing = null
        repeat(stack.size) { back() }
        tab = "library"
        push(Screen.Settings(atUpdates))
    }
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
    val open: (List<Tile>, Tile) -> Unit = { list, tile -> viewTiles = list; viewing = tile.id }
    // The viewer sits on top of the screen, so the grid stays under it: the photo grows out of its tile
    // and shrinks back in. The screen under is hidden from TalkBack while the viewer is open.
    // A screen opened from a tab sits on top of it too, so the tab stays composed and back only slides the
    // screen away (building Library again during the slide made it stutter).
    // The top screen with its place in the stack, or null on the tab.
    val opened = updateTransition(stack.withIndex().lastOrNull(), label = "screen")
    // Settings pushes: the tab slides out to the left as Settings comes in, like a window to the right
    val pushed by opened.animateFloat({ tween(250) }, label = "push") { if (it?.value.isSettingsPage()) 1f else 0f }
    Box(Modifier.fillMaxSize()) {
        Box(if (viewing != null) Modifier.clearAndSetSemantics {} else Modifier) {
            Box(Modifier.graphicsLayer { translationX = -pushed * size.width }.then(if (opened.targetState != null) Modifier.clearAndSetSemantics {} else Modifier)) {
                Scaffold(bottomBar = {
                    // The tabs and the Select bar are the same height (both build on ShortNavigationBar),
                    // so crossfading them never moves the grid.
                    Crossfade(targetState = tab == "photos" && selection.active, label = "bottomBar") { selecting ->
                        if (selecting) SelectBar(
                            favorite = selection.allFavorite,
                            onShare = shareSelected,
                            onFavorite = { favoriteSelected(!selection.allFavorite) },
                            onDelete = deleteSelected,
                        )
                        else ShortNavigationBar {
                            ShortNavigationBarItem(
                                tab == "photos", {
                                    if (tab == "photos") photosScrollTop++ else tab = "photos"
                                }, { Icon(painterResource(R.drawable.ic_photo), null) }, { Text("Photos") },
                                iconPosition = NavigationItemIconPosition.Start,
                            )
                            ShortNavigationBarItem(
                                tab == "library", { tab = "library" }, { Icon(painterResource(R.drawable.ic_library), null) }, { Text("Library") },
                                iconPosition = NavigationItemIconPosition.Start,
                            )
                        }
                    }
                }) { padding ->
                    // tabs are side by side, so they fade; a push would read as going deeper
                    Crossfade(tab, Modifier.padding(padding), animationSpec = tween(200), label = "tab") { t ->
                        tabs.SaveableStateProvider(t) {
                            if (t == "photos") PhotosScreen(
                                onOpen = open, grid = photosGrid, selection = selection, reload = photosReload,
                                onSearch = { push(Screen.Search) },
                                scrollToTop = photosScrollTop,
                            )
                            else LibraryScreen(
                                onOpenSearch = { push(Screen.Search) },
                                onOpenPeople = { push(Screen.People()) },
                                onOpenPerson = { push(Screen.People(it, direct = true)) },
                                onOpenAlbums = { push(Screen.Albums()) },
                                onOpenAlbum = { push(Screen.Album(it)) },
                                onOpenPlaces = { push(Screen.Places) },
                                onOpenPlace = { push(Screen.Place(it)) },
                                onOpenCategories = { push(Screen.Categories) },
                                onOpenCategory = { push(Screen.Category(it)) },
                                onOpenFavorites = { push(Screen.Favorites) },
                                onOpenVideos = { push(Screen.Videos) },
                                onOpenTrash = { push(Screen.Trash) },
                                onHelpRecognize = { push(Screen.Recognize()) },
                                onOpenSettings = { push(Screen.Settings()) },
                                reload = returns,
                            )
                        }
                    }
                }
            }
            // An opened screen slides in from the right, and back slides it out to the right. Screen to screen
            // pushes: the old one slides out to the left, Back reverses it. Each screen paints its own
            // background and takes the taps, so the tab under it neither shows through nor reacts.
            opened.AnimatedContent(contentKey = { it?.let { e -> screenKey(e.index, e.value) } }, transitionSpec = {
                val from = initialState
                val to = targetState
                when {
                    from == null -> slideInHorizontally(tween(250)) { it } togetherWith ExitTransition.None
                    to == null -> EnterTransition.None togetherWith slideOutHorizontally(tween(250)) { it }
                    else -> {
                        val dir = if (to.index >= from.index) 1 else -1
                        slideInHorizontally(tween(250)) { it * dir } togetherWith slideOutHorizontally(tween(250)) { -it * dir }
                    }
                }
            }) { target ->
                // no screen is an empty full-size box: a zero size would clip the screen sliding in or out
                if (target == null) Box(Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) {}) {
                    // keeps the screen's scroll and fields while the viewer or another screen is on top
                    tabs.SaveableStateProvider(screenKey(target.index, target.value)) {
                        when (val s = target.value) {
                            Screen.Dev -> TestScreen(dir, back)
                            is Screen.Settings -> SettingsScreen(
                                onBack = back, onHidden = { push(Screen.Hidden()) }, onSkip = { push(Screen.Skip) }, onLicenses = { push(Screen.Licenses) },
                                onDev = { push(Screen.Dev) }, atUpdates = s.atUpdates,
                            )
                            Screen.Skip -> SkipScreen(onBack = back)
                            Screen.Licenses -> LicensesScreen(onBack = back)
                            is Screen.Hidden -> PeopleScreen(
                                openId = s.person,
                                onOpenPerson = { replaceTop(Screen.Hidden(it)) },
                                onPersonBack = { replaceTop(Screen.Hidden()) },
                                onBack = back,
                                onOpen = open,
                                onHelpRecognize = { push(Screen.Recognize(it)) },
                                hiddenOnly = true,
                            )
                            is Screen.People -> PeopleScreen(
                                openId = s.person,
                                onOpenPerson = { replaceTop(Screen.People(it)) },
                                onPersonBack = { if (s.direct) back() else replaceTop(Screen.People()) },
                                onBack = back,
                                onOpen = open,
                                onHelpRecognize = { push(Screen.Recognize(it)) },
                            )
                            is Screen.Recognize -> HelpRecognizeScreen(s.person, onBack = back)
                            Screen.Favorites -> PhotosScreen(favoritesOnly = true, onBack = back, onOpen = open)
                            is Screen.Albums -> {
                                AlbumsScreen(onBack = back, onOpenAlbum = { push(Screen.Album(it)) }, onNewAlbum = { replaceTop(Screen.Albums(naming = "")) })
                                // New album names the folder first, then the picker opens (mockup "New album")
                                s.naming?.let { typed ->
                                    NewAlbumSheet(typed, onDismiss = { replaceTop(Screen.Albums()) }) { n -> replaceTop(Screen.Albums()); push(Screen.NewAlbum(n)) }
                                }
                            }
                            // Back keeps the typed name (reopens the sheet); Cancel makes nothing
                            is Screen.NewAlbum -> NewAlbumScreen(
                                name = s.name,
                                onBack = { back(); replaceTop(Screen.Albums(naming = s.name)) },
                                onCancel = back,
                                // Albums starts over too, so the new album shows when you go back to it
                                onMoved = { id -> back(); back(); push(Screen.Albums()); push(Screen.Album(id)) },
                            )
                            is Screen.Album -> AlbumScreen(s.id, onBack = back, onOpen = open)
                            Screen.Places -> PlacesScreen(onBack = back, onOpenPlace = { push(Screen.Place(it)) })
                            is Screen.Place -> PlaceScreen(s.id, onBack = back, onOpen = open)
                            Screen.Categories -> CategoriesScreen(onBack = back, onOpenCategory = { push(Screen.Category(it)) })
                            is Screen.Category -> CategoryScreen(s.id, onBack = back, onOpen = open)
                            Screen.Videos -> VideosScreen(onBack = back, onOpen = open)
                            Screen.Search -> SearchScreen(
                                onBack = back,
                                onOpen = { h ->
                                    when (h.kind) {
                                        HitKind.person -> push(Screen.People(h.key.toLong(), direct = true))
                                        HitKind.place -> push(Screen.Place(h.key.toInt()))
                                        HitKind.album -> push(Screen.Album(h.key.toLong()))
                                        HitKind.category -> push(Screen.Category(h.key))
                                        HitKind.videos -> push(Screen.Videos)
                                        HitKind.favorites -> push(Screen.Favorites)
                                        HitKind.year, HitKind.month -> { // the Photos tab, scrolled to that month's header
                                            back(); tab = "photos"
                                            val at = lastTimeline?.indexOfFirst { it is Header && it.key == h.key } ?: -1
                                            if (at >= 0) scope.launch { photosGrid.scrollToItem(at) }
                                        }
                                    }
                                },
                                onSeeAllPeople = { push(Screen.People()) },
                            )
                            Screen.Trash -> TrashScreen(back)
                        }
                    }
                }
            }
        }
        viewing?.let {
            Viewer(
                it, viewTiles, onBack = { viewing = null },
                // the viewer never opened an album from inside an album, so Show in album is hidden there
                showInAlbum = stack.lastOrNull() !is Screen.Album,
                onShowInAlbum = { t -> albumOf(context, t.id)?.let { b -> viewing = null; push(Screen.Album(b.id)) } },
            )
        }
    }
}

// ponytail: the viewer's photos live outside composition, so a dark mode or font switch keeps them;
// after process death they are gone and the viewer closes. Reload them by the open id if that matters.
private var viewTiles by mutableStateOf<List<Tile>>(emptyList())

/** A screen opened over the tabs, with what it shows. Serializable, so the stack survives the app being restarted. */
sealed interface Screen : Serializable {
    data class Settings(val atUpdates: Boolean = false) : Screen
    data object Licenses : Screen
    data object Skip : Screen
    data object Dev : Screen
    data class Hidden(val person: Long? = null) : Screen
    /** [person] is open, if set; [direct]: People opened right at the person, so back from the person closes it. */
    data class People(val person: Long? = null, val direct: Boolean = false) : Screen
    /** Help recognize, only about [person] if set. */
    data class Recognize(val person: Long? = null) : Screen
    data object Favorites : Screen
    /** [naming]: the New album sheet is open with this name in it. */
    data class Albums(val naming: String? = null) : Screen
    /** The New album picker filling album [name]. */
    data class NewAlbum(val name: String) : Screen
    data class Album(val id: Long) : Screen
    data object Places : Screen
    data class Place(val id: Int) : Screen
    data object Categories : Screen
    data class Category(val id: String) : Screen
    data object Videos : Screen
    data object Search : Screen
    data object Trash : Screen
}

/** A stack entry's id for its saved state: the kind and the depth, not the arguments, so [Screen.People] keeps its state as a person opens. */
internal fun screenKey(depth: Int, screen: Screen) = "$depth ${screen.javaClass.simpleName}"

private fun Screen?.isSettingsPage() = this is Screen.Settings || this is Screen.Licenses || this is Screen.Skip || this is Screen.Hidden || this is Screen.Dev

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

/** Runs models from <app files>/models on photos in <app files>/test, writes results_cpu/gpu/npu.json, tags_*.json when tagger.tflite is there, objects_*.json when objects.tflite and pets.tflite are. */
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
    val start = { on: Accelerator ->
        busy = true
        scope.launch(Dispatchers.Default) {
            try {
                runTest(context, dir, on, { Log.i("Memoria", "dev: $it"); log += "\n$it" }) { preview = it } // logcat too: the screen's log sits below the preview
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
        onRunCpu = { start(Accelerator.CPU) },
        onRunGpu = { start(Accelerator.GPU) },
        onRunNpu = { start(Accelerator.NPU) },
        onIndexTest = { index.launch(arrayOf(photos, Manifest.permission.POST_NOTIFICATIONS)) },
    )
}

fun runTest(context: Context, dir: File, on: Accelerator, log: (String) -> Unit, show: (Pair<Bitmap, List<Face>>) -> Unit) {
    val mode = on.name.lowercase()
    var t = System.nanoTime()
    val ms = { (System.nanoTime() - t) / 1_000_000.0 }
    val pipeline = if (on == Accelerator.NPU) FacePipeline(context) else FacePipeline(context, listOf(on))
    log("$mode: models loaded in %.0f ms".format(ms()))
    t = System.nanoTime()
    val tagger = if (!File(File(context.getExternalFilesDir(null), "models"), "tagger.tflite").exists()) null
        else if (on == Accelerator.NPU) Tagger(context) else Tagger(context, listOf(on))
    if (tagger != null) log("$mode: tagger loaded in %.0f ms".format(ms()))
    val models = File(context.getExternalFilesDir(null), "models")
    t = System.nanoTime()
    val objects = if (!File(models, "objects.tflite").exists()) null else if (on == Accelerator.NPU) ObjectDetector(context) else ObjectDetector(context, listOf(on))
    val pets = if (objects == null || !File(models, "pets.tflite").exists()) null else if (on == Accelerator.NPU) PetVectors(context) else PetVectors(context, listOf(on))
    if (pets != null) log("$mode: objects and pets loaded in %.0f ms".format(ms()))
    val photos = dir.walk().filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }.sorted().toList()
    val results = JSONObject()
    var decode = 0.0; var detect = 0.0; var embed = 0.0; var faces = 0; var most = -1
    val tags = JSONObject()
    var resize = 0.0; var tag = 0.0
    val boxes = JSONObject()
    var small = 0.0; var detectObjects = 0.0; var embedPets = 0.0; var petCount = 0
    pets.use { objects.use { tagger.use { pipeline.use {
        for ((i, file) in photos.withIndex()) {
            t = System.nanoTime(); val image = loadPhoto(ImageDecoder.createSource(file)); decode += ms()
            t = System.nanoTime(); val found = pipeline.detect(Letterbox(image)); detect += ms()
            t = System.nanoTime(); found.forEach { pipeline.embed(image, it) }; embed += ms()
            faces += found.size
            if (tagger != null) {
                t = System.nanoTime(); val input = tagger.input(image); resize += ms()
                t = System.nanoTime(); tags.put(file.relativeTo(dir).path, JSONArray(tagger.vector(input).toList())); tag += ms()
            }
            if (objects != null && pets != null) {
                // the index's way: the 640 decode, one letterbox, pets cut from the same decode
                t = System.nanoTime(); val image640 = loadPhoto(ImageDecoder.createSource(file), 640); small += ms()
                t = System.nanoTime(); val found640 = objects.detect(Letterbox(image640)); detectObjects += ms()
                boxes.put(file.relativeTo(dir).path, JSONObject().put("size", JSONArray(listOf(image640.width, image640.height))).put("boxes", JSONArray(found640.map { b ->
                    val row = JSONObject().put("c", b.cls).put("s", b.score.toDouble()).put("box", JSONArray(b.box.toList()))
                    if (b.cls != PERSON && b.score >= 0.5f) {
                        t = System.nanoTime(); row.put("vec", JSONArray(pets.vector(image640, b.box).toList())); embedPets += ms()
                        petCount++
                    }
                    row
                })))
            }
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
    } } } }
    File(dir.parentFile, "results_$mode.json").writeText(results.toString())
    val n = photos.size.coerceAtLeast(1)
    log("$mode: ${photos.size} photos, $faces faces. Per photo: decode %.0f ms, detect %.0f ms. Per face: embed %.0f ms".format(decode / n, detect / n, embed / faces.coerceAtLeast(1)))
    if (tagger != null) {
        File(dir.parentFile, "tags_$mode.json").writeText(tags.toString())
        log("$mode tagger: per photo resize %.0f ms, model %.0f ms".format(resize / n, tag / n))
    }
    if (pets != null) {
        File(dir.parentFile, "objects_$mode.json").writeText(boxes.toString())
        log("$mode objects: per photo 640 decode %.0f ms, YOLO26 %.0f ms. Per pet: DINOv2 %.0f ms ($petCount pets)".format(small / n, detectObjects / n, embedPets / petCount.coerceAtLeast(1)))
    }
}
