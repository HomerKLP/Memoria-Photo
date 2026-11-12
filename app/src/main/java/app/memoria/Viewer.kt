package app.memoria

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.view.TextureView
import android.view.WindowInsets
import android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
import android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max

internal val onViewer = Color(0xFFF4ECE1)
internal val viewerBg = Color(0xFF120E0A)
private const val maxZoom = 6f
// Double-tap zoom: short, eased, in the viewer only
private const val zoomMillis = 250

// Bars fade fast: the tap must feel immediate. Material's default fade is a spring (~400 ms).
private const val barsFadeMillis = 120

/** Full-screen photos, swipe sideways for the next one. Opens on the tile with id start. */
@Composable
fun Viewer(start: Long, tiles: List<Tile>, onBack: () -> Unit, showInAlbum: Boolean = true, onShowInAlbum: (Tile) -> Unit = {}) {
    val context = LocalContext.current
    // One player for the screen: the video on the page plays, a photo stops it. Media3 runs on the
    // phone's own hardware decoder, the same one the system player uses, so the usual codecs play fast.
    // Audio focus is asked for, so a call or another app's music owns the sound like in a real player.
    val player = remember {
        ExoPlayer.Builder(context).setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        ).build()
    }
    var playing by remember { mutableStateOf(false) }
    var videoFailed by remember { mutableStateOf(false) }
    var playAt by remember { mutableLongStateOf(0L) } // where the video is, for the bar and the line
    var videoLength by remember { mutableLongStateOf(0L) }
    var loop by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) } // 2x while a finger is down
    var pill by remember { mutableStateOf(0) } // +5 s / −5 s, on the half that was tapped
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayerError(error: PlaybackException) { videoFailed = true }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(loop) { player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    LaunchedEffect(holding) { player.setPlaybackSpeed(if (holding) 2f else 1f) }
    // Leaving the app with a video playing must not leave its sound running behind the home screen
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(player) {
        while (true) {
            // a released player (the screen is closing) must not be asked anything
            runCatching {
                playAt = player.currentPosition.coerceAtLeast(0L)
                player.duration.takeIf { it > 0 }?.let { videoLength = it }
            }
            delay(250)
        }
    }
    var list by remember { mutableStateOf(tiles) } // shrinks when a photo goes to the trash
    if (list.isEmpty()) { // the app was restarted while the viewer was open, or the last photo was trashed
        LaunchedEffect(Unit) { onBack() }
        return
    }
    // Tap the photo: the bars and the system bars hide, so only the photo is left. Tap again brings them back.
    var barsVisible by remember { mutableStateOf(true) }
    // Light bar icons on the dark viewer, dark again when it closes
    val window = (LocalView.current.context as Activity).window
    val barIcons = APPEARANCE_LIGHT_STATUS_BARS or APPEARANCE_LIGHT_NAVIGATION_BARS
    // 0: the photo sits in its grid tile, 1: full screen. It grows on open and shrinks back on close.
    val shown = remember { Animatable(0f) }
    // A spring from rest: the old front-loaded 300 ms curve did half the motion in one frame, so it looked skipped
    LaunchedEffect(Unit) { shown.animateTo(1f, spring(dampingRatio = 1f, stiffness = 500f, visibilityThreshold = 0.004f)) }
    // false: no tile on screen to grow from or land in (or the photo is zoomed), so the photo fades instead
    var fly by remember { mutableStateOf(onScreen(start)) }
    var closing by remember { mutableStateOf(false) }
    // The downward pull follows the finger and shrinks the photo, then springs back or closes
    val dragY = remember { Animatable(0f) }
    val screenH = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    // The grid shows through as the photo leaves
    val backdrop = { shown.value * (1f - (dragY.value / (screenH / 2f)).coerceIn(0f, 1f)) }
    val dark by remember { derivedStateOf { backdrop() > 0.5f } }
    // Swipe up: the black details panel rides up from the bottom edge while the photo moves into a
    // full-width square at the top (docs/design.html "Viewer, swiped up"). up = how far the panel is out, in px.
    val up = remember { Animatable(0f, visibilityThreshold = 1f) } // 1 px: the spring's long tail would keep the bottom bar away
    var detailsOpen by remember { mutableStateOf(false) } // where the panel settles
    val details by remember { derivedStateOf { up.value > 0f || detailsOpen } }
    // details longer than the panel: how far they are scrolled, and their full height
    val listY = remember { Animatable(0f) }
    var listH by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(details) { if (!details) listY.snapTo(0f) }
    val coast = rememberSplineBasedDecay<Float>()
    LaunchedEffect(details, barsVisible, dark, closing) {
        val controller = window.insetsController ?: return@LaunchedEffect
        controller.setSystemBarsAppearance(if (!dark) barIcons else 0, barIcons)
        // the details keep the top bar, so the system bars show too
        if (details || barsVisible || closing) controller.show(WindowInsets.Type.systemBars())
        else controller.hide(WindowInsets.Type.systemBars())
    }
    // Edit in… may save a new copy, so the screen under reloads on close; a plain close skips the reload (a 30 ms frame)
    var editedIn by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            window.insetsController?.setSystemBarsAppearance(barIcons, barIcons)
            window.insetsController?.show(WindowInsets.Type.systemBars())
            viewerTile = null
            if (editedIn) viewerEdits++
        }
    }
    val pager = rememberPagerState(tiles.indexOfFirst { it.id == start }.coerceAtLeast(0)) { list.size }
    // coerce: after trashing the last photo the pager is one frame behind the shorter list
    val tile = list[pager.currentPage.coerceAtMost(list.lastIndex)]
    LaunchedEffect(tile.id) { viewerTile = tile.id }
    // The page in view is what plays. A photo stops the player, so its sound never runs on behind it.
    LaunchedEffect(tile.id) {
        videoFailed = false
        pill = 0
        playAt = 0
        videoLength = tile.duration ?: 0L
        if (tile.duration == null) {
            player.pause()
            player.clearMediaItems()
        } else {
            player.setMediaItem(MediaItem.fromUri(tile.uri))
            player.prepare()
            player.play()
        }
    }
    // Swipe down to close: track the drag, dismiss only past a threshold
    val dismissAt = with(LocalDensity.current) { 120.dp.toPx() }
    // A short pull opens or closes the details, a flick decides by its direction
    val openAt = with(LocalDensity.current) { 48.dp.toPx() }
    val flingAt = with(LocalDensity.current) { 400.dp.toPx() }
    var view by remember { mutableStateOf(IntSize.Zero) }
    // Open, the panel's rounded top overlaps the photo square by this much
    val overlap = with(LocalDensity.current) { 28.dp.toPx() }
    fun openUp() = (view.height - view.width + overlap).coerceAtLeast(0f)
    // the list stops at its ends; they move when the details load after the panel opened
    LaunchedEffect(listH, view) { listY.updateBounds(0f, (listH - openUp()).coerceAtLeast(0f)) }
    val scope = rememberCoroutineScope()
    // Double tap: ±5 s on the half that was tapped, with the pill for a moment (mockup "Player, double tap")
    fun skip(dir: Int) {
        if (tile.duration == null || videoFailed) return
        val end = player.duration.coerceAtLeast(0L)
        player.seekTo((player.currentPosition + dir * 5000L).coerceIn(0L, if (end > 0L) end else Long.MAX_VALUE))
        pill = dir
        scope.launch { delay(450); if (pill == dir) pill = 0 }
    }
    fun settle(open: Boolean) {
        detailsOpen = open
        scope.launch { up.animateTo(if (open) openUp() else 0f, spring(stiffness = Spring.StiffnessMediumLow)) }
    }
    // A zoomed photo owns every finger, so the pager and the vertical swipes wait. Reset when the page changes.
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { zoomed = false }
    fun close() {
        if (closing) return
        closing = true
        // a photo in the details square fades, the panel slides down
        fly = !zoomed && up.value == 0f && onScreen(tile.id)
        if (up.value > 0f) scope.launch { up.animateTo(0f, tween(250)) }
        scope.launch {
            // no tile to land in: a dragged photo slides off, a still one fades
            if (!fly && dragY.value > 0f) dragY.animateTo(screenH, tween(200))
            else shown.animateTo(0f, tween(250, easing = FastOutSlowInEasing))
            onBack()
        }
    }
    BackHandler { if (detailsOpen) settle(false) else close() }
    // Favorites: our override wins over the timeline, MediaStore is the source of truth on the next open
    val favorites = remember { mutableStateMapOf<Long, Boolean>() }
    var pending by remember { mutableStateOf<Pair<Long, Boolean>?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        pending?.let { (id, favorite) ->
            if (result.resultCode == Activity.RESULT_OK) { favorites[id] = favorite; viewerEdits++ }
            pending = null
        }
    }
    val favorite = favorites[tile.id] ?: tile.favorite
    // Trash: Android asks the user, then the next photo slides in
    var trashing by remember { mutableStateOf<Long?>(null) }
    val trashAsk = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        trashing?.let { id ->
            if (result.resultCode == Activity.RESULT_OK) { list = list.filterNot { it.id == id }; forgetTiles(setOf(id)); viewerEdits++ }
            trashing = null
        }
    }
    // Move to album: the sheet picks a folder, Android asks for consent when the file belongs to another app.
    // A photo opened from an album leaves it on a move, so the viewer shows the next one (same as trashing).
    var moving by remember { mutableStateOf(false) }
    fun afterMove(id: Long) { if (!showInAlbum) list = list.filterNot { it.id == id }; viewerEdits++ }
    var movePending by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val moveAsk = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        movePending?.let { (id, path) ->
            if (result.resultCode == Activity.RESULT_OK) list.firstOrNull { it.id == id }
                ?.let { runCatching { movePhoto(context, it, path) }.onSuccess { afterMove(id) } }
            movePending = null
        }
    }
    fun applyMove(id: Long, path: String) {
        val t = list.firstOrNull { it.id == id } ?: return
        runCatching { movePhoto(context, t, path) }.onSuccess { afterMove(id) }.onFailure {
            movePending = id to path
            val ask = MediaStore.createWriteRequest(context.contentResolver, listOf(t.uri))
            moveAsk.launch(IntentSenderRequest.Builder(ask.intentSender).build())
        }
    }
    // Photo fills the whole screen, the bars float on it (transparent gradient, see ViewerChrome)
    val zone = Instant.ofEpochMilli(tile.date).atZone(ZoneId.systemDefault())
    val dayPattern = if (zone.year == LocalDate.now().year) "EEE, d MMM" else "EEE, d MMM yyyy"
    // The photo moves alone (ViewerPage); this dark backdrop only fades, so the screen under shows through.
    Box(Modifier.fillMaxSize().onSizeChanged { view = it }.drawBehind { drawRect(viewerBg, alpha = backdrop()) }) {
        Box(
            Modifier.fillMaxSize()
                // One vertical drag drives it all. Up: the details panel rises, then its list scrolls.
                // Down: the list scrolls back, then the panel lowers, then the plain photo pulls down to close.
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        val open = openUp()
                        when {
                            dragY.value > 0f -> scope.launch { dragY.snapTo((dragY.value + delta).coerceAtLeast(0f)) }
                            delta < 0f && up.value < open -> scope.launch { up.snapTo((up.value - delta).coerceAtMost(open)) }
                            delta < 0f || listY.value > 0f -> scope.launch { listY.snapTo(listY.value - delta) }
                            up.value > 0f -> scope.launch { up.snapTo((up.value - delta).coerceAtLeast(0f)) }
                            else -> { detailsOpen = false; scope.launch { dragY.snapTo(delta.coerceAtLeast(0f)) } }
                        }
                    },
                    enabled = !zoomed && !closing,
                    onDragStopped = { velocity ->
                        when {
                            // past the threshold the photo goes back to its tile; under it, it eases back into place
                            dragY.value > dismissAt -> close()
                            dragY.value > 0f -> scope.launch { dragY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                            up.value >= openUp() -> {
                                detailsOpen = true
                                // a flick keeps the list going and it coasts to a stop, at the latest at either end
                                scope.launch { listY.animateDecay(-velocity, coast) }
                            }
                            up.value > 0f -> settle(
                                if (abs(velocity) > flingAt) velocity < 0f
                                else if (detailsOpen) up.value > openUp() - openAt else up.value > openAt,
                            )
                            else -> detailsOpen = false // the panel was dragged all the way down
                        }
                    },
                ),
        ) {
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, userScrollEnabled = !zoomed && !details, key = { list[it].id }) { i ->
                val current = i == pager.currentPage
                ViewerPage(
                    list[i], { zoomed = it }, onTap = { if (details) settle(false) else barsVisible = !barsVisible },
                    shown = if (current) ({ shown.value }) else ({ 1f }),
                    dragY = if (current) ({ dragY.value }) else ({ 0f }),
                    details = if (current) ({ if (openUp() > 0f) (up.value / openUp()).coerceIn(0f, 1f) else 0f }) else ({ 0f }),
                    fly = fly,
                    player = player,
                    current = current,
                    pill = if (current) pill else 0,
                    holding = current && holding,
                    onSkip = { skip(it) },
                    onHold = { if (current) holding = it },
                )
            }
            // the bars step aside while the photo moves
            val still by remember { derivedStateOf { shown.value == 1f && dragY.value == 0f && !closing } }
            ViewerChrome(
                visible = (barsVisible || details) && still,
                bottom = !details,
                title = DateTimeFormatter.ofPattern(dayPattern).format(zone),
                subtitle = DateTimeFormatter.ofPattern("HH:mm").format(zone),
                favorite = favorite,
                onBack = { close() },
                onShare = {
                    val send = Intent(Intent.ACTION_SEND).setType(context.contentResolver.getType(tile.uri))
                        .putExtra(Intent.EXTRA_STREAM, tile.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, null))
                },
                onFavorite = {
                    val next = !favorite
                    try {
                        setFavorite(context, tile, next) // our own photos need no dialog
                        favorites[tile.id] = next
                        updateTiles(listOf(tile.copy(favorite = next)))
                        viewerEdits++
                    } catch (e: SecurityException) {
                        // someone else's photo: Android asks the user once, then remembers
                        pending = tile.id to next
                        val ask = MediaStore.createFavoriteRequest(context.contentResolver, listOf(tile.uri), next)
                        consent.launch(IntentSenderRequest.Builder(ask.intentSender).build())
                    }
                },
                onInfo = { settle(true) },
                onDelete = {
                    trashing = tile.id
                    val ask = MediaStore.createTrashRequest(context.contentResolver, listOf(tile.uri), true)
                    trashAsk.launch(IntentSenderRequest.Builder(ask.intentSender).build())
                },
                menuItems = if (tile.duration != null) buildList {
                    // a video: the photo menu minus Edit in… and Use as…, which need a still photo (mockup "Video, More")
                    add(MenuItem(R.drawable.ic_refresh, "Loop", checked = loop) { loop = !loop })
                    add(MenuItem(R.drawable.ic_play, "Open in the phone's player") { openInPlayer(context, tile) })
                    if (showInAlbum) add(MenuItem(R.drawable.ic_folder, "Show in album") { onShowInAlbum(tile) })
                    add(MenuItem(R.drawable.ic_move, "Move to album", dividerBefore = true) { moving = true })
                    add(MenuItem(R.drawable.ic_video, "Compress video", enabled = false) {}) // Phase 5
                } else buildList {
                    add(MenuItem(R.drawable.ic_edit, "Edit in…") { editedIn = true; editIn(context, tile) })
                    add(MenuItem(R.drawable.ic_wallpaper, "Use as…") { useAs(context, tile) })
                    if (showInAlbum) add(MenuItem(R.drawable.ic_folder, "Show in album") { onShowInAlbum(tile) })
                    add(MenuItem(R.drawable.ic_move, "Move to album", dividerBefore = true) { moving = true })
                },
                playerBar = if (tile.duration != null && !videoFailed) ({
                    PlayerBar(
                        playing = playing,
                        position = playAt,
                        length = videoLength,
                        onToggle = {
                            if (player.isPlaying) player.pause()
                            else {
                                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                                player.play()
                            }
                        },
                        onSeek = { player.seekTo(it) },
                    )
                }) else null,
                // Bars hidden while it plays: only the thin progress line stays (mockup "Video, playing, tapped")
                progress = if (tile.duration != null && !videoFailed && videoLength > 0L) {
                    (playAt.toFloat() / videoLength).coerceIn(0f, 1f)
                } else null,
            )
            // the panel is as tall as its open spot and slides up from under the bottom edge
            if (details) Box(
                Modifier.fillMaxWidth().height(with(LocalDensity.current) { openUp().toDp() })
                    .graphicsLayer { translationY = view.height - up.value }
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(viewerBg)
                    .pointerInput(Unit) {}, // a tap on the panel's empty space must not reach the photo under it
            ) {
                DetailsPanel(
                    tile,
                    Modifier.wrapContentHeight(Alignment.Top, unbounded = true)
                        .onSizeChanged { listH = it.height.toFloat() }
                        .graphicsLayer { translationY = -listY.value },
                )
            }
            // A file the decoder refuses: the card replaces the controls, the frame stays behind it
            if (videoFailed) PlayerFailed(tile) { openInPlayer(context, tile) }
        }
    }
    if (moving) MoveToAlbumSheet(tile, onDismiss = { moving = false }, onMove = { path -> moving = false; applyMove(tile.id, path) })
}

/**
 * The viewer's top and bottom bars, from docs/design.html "Viewer": they float on the fully visible
 * photo and are transparent — a soft vertical gradient keeps the white icons readable — so they never
 * hide the photo. [visible] false is the tapped state: the bars fade out and only the photo is left.
 * Purely visual, so the layout tests and previews can render it without MediaStore.
 */
@Composable
fun ViewerChrome(
    visible: Boolean,
    title: String,
    subtitle: String,
    favorite: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
    menuItems: List<MenuItem> = emptyList(),
    bottom: Boolean = true, // false: the details panel is up and covers the bottom bar
    playerBar: (@Composable () -> Unit)? = null, // a video's controls, above the action row
    progress: Float? = null, // 0..1 while a video plays with the bars hidden: the thin line at the bottom edge
) {
    var menu by remember { mutableStateOf(false) }
    // The bars stay composed and are only placed while showing: composing them when the opened photo
    // settled cost a dropped frame, and unplaced bars take no taps meant for the photo.
    val top = animateFloatAsState(if (visible) 1f else 0f, tween(barsFadeMillis), label = "topBar")
    val low = animateFloatAsState(if (visible && bottom) 1f else 0f, tween(barsFadeMillis), label = "bottomBar")
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.align(Alignment.TopCenter)
                .layout { m, c -> val p = m.measure(c); layout(p.width, p.height) { if (top.value > 0f) p.place(0, 0) } }
                .graphicsLayer { alpha = top.value }
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(viewerBg.copy(alpha = 0.62f), Color.Transparent)))
                .statusBarsPadding().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onBack) { Icon(painterResource(R.drawable.ic_back), "Back", tint = onViewer) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, color = onViewer, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = onViewer.copy(alpha = 0.7f), fontSize = 12.sp)
            }
            // More (three dots): the same width as Back, so the date stays centered. Opens the Menu part.
            IconButton({ if (menuItems.isNotEmpty()) menu = true }) {
                Icon(painterResource(R.drawable.ic_more), "More", tint = onViewer)
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter)
                .layout { m, c -> val p = m.measure(c); layout(p.width, p.height) { if (low.value > 0f) p.place(0, 0) } }
                .graphicsLayer { alpha = low.value }
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, viewerBg.copy(alpha = 0.62f))))
                .navigationBarsPadding().padding(top = 16.dp, bottom = 6.dp),
        ) {
            playerBar?.invoke()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                IconButton(onShare) { Icon(painterResource(R.drawable.ic_share), "Share", tint = onViewer) }
                IconButton(onFavorite) {
                    Icon(
                        painterResource(if (favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border),
                        if (favorite) "Remove from favorites" else "Add to favorites",
                        tint = if (favorite) Color(0xFFE9A23B) else onViewer,
                    )
                }
                IconButton(onInfo) { Icon(painterResource(R.drawable.ic_info), "Details", tint = onViewer) }
                IconButton(onDelete) { Icon(painterResource(R.drawable.ic_trash_line), "Delete", tint = onViewer) }
            }
        }
        // Playing with the bars hidden: the thin progress line at the bottom edge keeps the hang on it
        if (!visible) progress?.let {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(it.coerceIn(0f, 1f)).height(2.dp).background(Color(0xFFE9A23B)))
        }
        Menu(menu, onDismiss = { menu = false }, items = menuItems)
    }
}

/** More > Edit in…: hands the photo to an editor app through Android's chooser. */
private fun editIn(context: Context, tile: Tile) {
    val type = context.contentResolver.getType(tile.uri) ?: "*/*"
    val edit = Intent(Intent.ACTION_EDIT).setDataAndType(tile.uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(edit, null)) }
}

/** More > Use as…: set as wallpaper / contact photo through Android's chooser. */
private fun useAs(context: Context, tile: Tile) {
    val type = context.contentResolver.getType(tile.uri) ?: "*/*"
    val use = Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(tile.uri, type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra("mimeType", type)
    runCatching { context.startActivity(Intent.createChooser(use, null)) }
}

/** More > Open in the phone's player, and the way out of a file our decoder refuses (mockup "Video, cannot play"). */
private fun openInPlayer(context: Context, tile: Tile) {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(tile.uri, context.contentResolver.getType(tile.uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(view) }
}

/** m:ss like every player: "1:24 / 3:10" in the mockup "Video, playing". */
internal fun clock(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) + 500) / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/**
 * A video's whole control set (mockup "Video, playing"): play / pause, one seek bar, the time. Purely
 * visual with callbacks, so the behavior tests drive it without a player.
 */
@Composable
fun PlayerBar(
    playing: Boolean,
    position: Long,
    length: Long,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var drag by remember { mutableStateOf<Float?>(null) } // the thumb under the finger, before the seek lands
    val span = length.coerceAtLeast(1L).toFloat()
    val at = drag ?: (position / span).coerceIn(0f, 1f)
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onToggle) {
            Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play), if (playing) "Pause" else "Play", tint = onViewer)
        }
        SeekBar(
            at = at,
            onScrub = { drag = it },
            onSeek = { drag = null; onSeek((it * span).toLong()) },
            modifier = Modifier.weight(1f),
        )
        // Fixed width and tabular digits: the total arrives once the file is read, and the slider must not jump then
        Row(Modifier.width(120.dp), horizontalArrangement = Arrangement.End) {
            val time = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
            Text(clock((at * span).toLong()), color = onViewer, style = time)
            Text(" / ${clock(length)}", color = onViewer.copy(alpha = 0.65f), style = time)
        }
    }
}

/** A 3 dp line with a 12 dp knob, not Material's fat slider (mockup "Video, playing"). Tap or drag it to seek. */
@Composable
private fun SeekBar(at: Float, onScrub: (Float) -> Unit, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(28.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(at, 0f..1f)
                setProgress { onScrub(it); onSeek(it); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.coerceAtLeast(1)
                    onScrub((down.position.x / w).coerceIn(0f, 1f))
                    var x = down.position.x
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { x = it.position.x; onScrub((x / w).coerceIn(0f, 1f)); it.consume() }
                    } while (event.changes.any { it.pressed })
                    onSeek((x / w).coerceIn(0f, 1f))
                }
            }
            .drawBehind {
                val y = size.height / 2f
                val line = 1.5.dp.toPx()
                drawLine(onViewer.copy(alpha = 0.3f), Offset(0f, y), Offset(size.width, y), line, StrokeCap.Round)
                drawLine(onViewer, Offset(0f, y), Offset(size.width * at, y), line, StrokeCap.Round)
                drawCircle(onViewer, 6.dp.toPx(), Offset(size.width * at, y))
            },
    )
}

/** A file the decoder refuses (mockup "Video, cannot play"): a scrim over the frame, and the phone's player as the way out. */
@Composable
fun PlayerFailed(tile: Tile, onOpen: () -> Unit) {
    Box(Modifier.fillMaxSize().background(viewerBg.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(painterResource(R.drawable.ic_video), null, Modifier.size(48.dp), tint = onViewer.copy(alpha = 0.3f))
            Text("This video can't play here", color = onViewer, style = MaterialTheme.typography.titleLarge)
            Text(
                clock(tile.duration ?: 0L) + " · The phone's own player may still open it",
                color = onViewer.copy(alpha = 0.65f), fontSize = 13.sp, textAlign = TextAlign.Center,
            )
            Button(onClick = onOpen) { Text("Open in the phone's player") }
        }
    }
}

/** How big the photo really is on screen with ContentScale.Fit, so panning stops at its edges. */
internal fun fitSize(imageWidth: Int, imageHeight: Int, boxWidth: Int, boxHeight: Int): Size {
    if (imageWidth <= 0 || imageHeight <= 0 || boxWidth == 0 || boxHeight == 0) return Size(0f, 0f)
    val s = minOf(boxWidth.toFloat() / imageWidth, boxHeight.toFloat() / imageHeight)
    return Size(imageWidth * s, imageHeight * s)
}

private fun fitSize(bitmap: Bitmap?, box: IntSize): Size =
    if (bitmap == null) Size(0f, 0f) else fitSize(bitmap.width, bitmap.height, box.width, box.height)

/** The photo's halving step for the viewer: the short side stays at least the [screen]'s, the long side within the 4096 px GPU limit. */
internal fun viewerSample(w: Int, h: Int, screen: Int): Int {
    var s = 1
    while (minOf(w, h) / (s * 2) >= screen || maxOf(w, h) / s > 4096) s *= 2
    return s
}

/**
 * The photo at about screen size, as a hardware bitmap so it uploads here, not on the render thread mid-animation.
 * Halving lets the JPEG decoder skip work; the old exact 2560 px decoded a 12 MP photo whole (250 ms).
 * ponytail: zoom past ~1.5x is a bit softer than before; decode a sharper copy on zoom if it shows.
 */
internal fun viewerPhoto(context: Context, tile: Tile): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, tile.uri)) { decoder, info, _ ->
        val screen = context.resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) }
        decoder.setTargetSampleSize(viewerSample(info.size.width, info.size.height, screen))
    }.turnedBy(dngTurn(context.contentResolver, tile.uri))

/**
 * Pan that keeps the [focus] point (double tap, or the pinch's finger center) in place while zooming
 * from [from] with [pan] to [target]. Pan is measured from the centered photo (0 = centered), so a tap
 * right of center pans left to bring that side in. Clamped to the photo's pan room, so a tap near an edge is fine.
 */
internal fun zoomToFocusPan(fit: Size, box: Size, target: Float, focus: Offset, from: Float = 1f, pan: Offset = Offset.Zero): Offset {
    if (target <= 1f) return Offset.Zero
    val k = target / from
    val p = pan * k + (focus - Offset(box.width / 2f, box.height / 2f)) * (1f - k)
    val roomX = ((fit.width * target - box.width) / 2f).coerceAtLeast(0f)
    val roomY = ((fit.height * target - box.height) / 2f).coerceAtLeast(0f)
    return Offset(p.x.coerceIn(-roomX, roomX), p.y.coerceIn(-roomY, roomY))
}

/** True when the photo's grid tile is at least partly on screen. */
private fun onScreen(id: Long): Boolean = tileSpots[id]?.takeIf { it.isAttached }?.boundsInWindow()?.isEmpty == false

/**
 * Where the photo is drawn and what part of it shows, between its grid [tile] ([t] = 0, cropped to the
 * tile's [visible] part) and full screen ([t] = 1). [dragY] pulls the full-screen photo down and shrinks it.
 * [reveal] = 1: the crop is gone and the whole photo shows.
 */
internal fun photoMotion(fit: Size, screen: Size, tile: Rect?, visible: Rect?, t: Float, dragY: Float, reveal: Float = t): Pair<Rect, Rect?> {
    fun scaled(center: Offset, k: Float) = Rect(center - Offset(fit.width * k / 2f, fit.height * k / 2f), fit * k)
    val dragged = scaled(Offset(screen.width / 2f, screen.height / 2f + dragY), 1f - 0.3f * (dragY / (screen.height / 2f)).coerceIn(0f, 1f))
    if (tile == null || visible == null || t >= 1f) return dragged to null
    val crop = scaled(tile.center, max(tile.width / fit.width, tile.height / fit.height))
    val rect = lerp(crop, dragged, t)
    return rect to lerp(visible, rect, reveal)
}

// The crop opens in the first part of the open, then the whole photo grows: a crop widening all the way looked cheap
private const val cropOpensBy = 0.4f

// ImageDecoder ignores cancel: two decodes at a time, so pages swiped past wait and drop out instead of decoding
private val viewerLoads = Dispatchers.IO.limitedParallelism(2)

@Composable
fun ViewerPage(
    tile: Tile,
    onZoomed: (Boolean) -> Unit,
    onTap: () -> Unit = {},
    shown: () -> Float = { 1f },
    dragY: () -> Float = { 0f },
    details: () -> Float = { 0f },
    fly: Boolean = false,
    player: ExoPlayer? = null,
    current: Boolean = false,
    pill: Int = 0, // +5 s / −5 s, shown on the half that was tapped
    holding: Boolean = false, // 2x while a finger is down
    onSkip: (Int) -> Unit = {},
    onHold: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    // A video has no pinch or double-tap zoom: the fingers are for the player (mockup "Player")
    val isVideo = tile.duration != null
    // Grid thumbnail first, then a screen-size decode
    val thumb = remember(tile.id) { thumbs.get(tile.id) }
    val image by produceState(thumb, tile.id) {
        val full = withContext(viewerLoads) {
            runCatching {
                // MediaStore keeps only a small video thumbnail, so take a sharp frame from the file
                if (tile.duration != null) MediaMetadataRetriever().use {
                    it.setDataSource(context, tile.uri)
                    it.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1440, 1440)
                } else viewerPhoto(context, tile)
            }.getOrNull()
        }
        if (full != null) value = full
    }
    val sharp = remember(tile.id) { Animatable(0f) }
    LaunchedEffect(image) { if (image !== thumb) sharp.animateTo(1f, tween(150)) }
    // Zoom state lives here: a zoomed photo cannot be swiped away, so the page is never stale.
    val zoom = remember { mutableFloatStateOf(1f) }
    val pan = remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    var zoomJob by remember { mutableStateOf<Job?>(null) }
    val box = remember { mutableStateOf(IntSize.Zero) }
    val fit = remember { mutableStateOf(Size.Zero) }
    LaunchedEffect(image, box.value) { fit.value = fitSize(image, box.value) }
    // snapshotFlow, not LaunchedEffect(zoom): reporting must not recompose the viewer on every pinch event
    LaunchedEffect(Unit) { snapshotFlow { zoom.floatValue > 1f }.distinctUntilChanged().collect(onZoomed) }
    // The details square shows the photo at 1x: opening them drops a zoom, and pinching waits until they close
    val detailsNow by rememberUpdatedState(details)
    fun transform(zoomChange: Float, panChange: Offset, focus: Offset = Offset.Zero) {
        // A real pinch or pan takes over from a running double-tap zoom. A bare tap (no movement) must
        // not: its down and up both land here just to clamp the pan, and cancelling would kill the
        // zoom-out animation the same tap just started.
        if (zoomChange != 1f || panChange != Offset.Zero) zoomJob?.cancel()
        val next = if (detailsNow() > 0f) 1f else (zoom.floatValue * zoomChange).coerceIn(1f, maxZoom)
        val boxSize = Size(box.value.width.toFloat(), box.value.height.toFloat())
        pan.value = zoomToFocusPan(fit.value, boxSize, next, focus, zoom.floatValue, pan.value + panChange)
        zoom.floatValue = next
    }
    // Double tap eases to the target, and slides the pan to keep the tapped point under the finger.
    // Zooming back out (target 1x) returns the pan to center.
    fun animateZoomTo(target: Float, focus: Offset?) {
        zoomJob?.cancel()
        val startZoom = zoom.floatValue
        val startPan = pan.value
        val endPan = if (target <= 1f || focus == null) Offset.Zero
        else zoomToFocusPan(fit.value, Size(box.value.width.toFloat(), box.value.height.toFloat()), target, focus)
        zoomJob = scope.launch {
            animate(0f, 1f, animationSpec = tween(zoomMillis, easing = FastOutSlowInEasing)) { t, _ ->
                zoom.floatValue = startZoom + (target - startZoom) * t
                pan.value = startPan + (endPan - startPan) * t
            }
        }
    }
    LaunchedEffect(Unit) { snapshotFlow { detailsNow() > 0f }.collect { if (it) { zoomJob?.cancel(); transform(1f, Offset.Zero) } } }
    var page by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // The photo's rect and clip right now. No clip: no tile to fly from, so it fades with [shown].
    fun motion(fit: Size, screen: Size): Pair<Rect, Rect?> {
        // details out: the photo moves into a full-width square at the top, zoomed to fill it
        val d = details()
        if (d > 0f) {
            val square = Rect(0f, 0f, screen.width, screen.width)
            return photoMotion(fit, screen, square, square, 1f - d, 0f)
        }
        val spot = tileSpots[tile.id]?.takeIf { fly && it.isAttached }
        val here = page?.takeIf { it.isAttached }
        // localBoundingBoxOf throws when the two nodes do not share a hierarchy. The tile can leave the
        // composition while the viewer opens (the grid follows the photo), so this is a real case: no
        // tile to fly from, fade instead of crashing.
        fun bounds(from: LayoutCoordinates, to: LayoutCoordinates, clip: Boolean): Rect? =
            try { from.localBoundingBoxOf(to, clipBounds = clip) } catch (_: IllegalArgumentException) { null }
        val visible = if (spot != null && here != null) bounds(here, spot, clip = true) else null
        if (visible == null || visible.isEmpty) return photoMotion(fit, screen, null, null, 1f, dragY())
        val full = bounds(here!!, spot!!, clip = false) ?: return photoMotion(fit, screen, null, null, 1f, dragY())
        return photoMotion(fit, screen, full, visible, shown(), dragY(), reveal = (shown() / cropOpensBy).coerceAtMost(1f))
    }
    Box(
        Modifier.fillMaxSize()
            .onPlaced { page = it }
            .drawWithContent {
                val fit = fitSize(image, IntSize(size.width.toInt(), size.height.toInt()))
                val clip = if (fit.width == 0f) null else motion(fit, size).second
                if (clip == null) drawContent() else clipRect(clip.left, clip.top, clip.right, clip.bottom) { this@drawWithContent.drawContent() }
            }
            .graphicsLayer {
                val fit = fitSize(image, IntSize(size.width.toInt(), size.height.toInt()))
                if (fit.width == 0f) { alpha = shown(); return@graphicsLayer }
                val (rect, clip) = motion(fit, size)
                // a clip means the photo flies from its tile, unless the details made it (then it fades on close)
                alpha = if (clip != null && details() == 0f) 1f else shown()
                scaleX = rect.width / fit.width
                scaleY = scaleX
                translationX = rect.center.x - size.width / 2f
                translationY = rect.center.y - size.height / 2f
            }
            // clipToBounds: pager pages do not clip, so a zoomed photo would spill over its neighbours
            .clipToBounds()
            .onSizeChanged { box.value = it },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize()
                // pointerInput must sit OUTSIDE graphicsLayer: inside it Compose hands out deltas
                // divided by the zoom scale, so panning would crawl at high zoom.
                .pointerInput(tile.id, isVideo) {
                    if (isVideo) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            if (zoomChange != 1f || zoom.floatValue > 1f) {
                                // zoom around the fingers; no finger down (the last up) has no center, and no zoom then
                                val focus = event.calculateCentroid()
                                transform(zoomChange, event.calculatePan(), if (focus.isSpecified) focus else Offset.Zero)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(tile.id, isVideo) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { offset ->
                            if (isVideo) onSkip(if (offset.x > size.width / 2f) 1 else -1)
                            // 1x -> 2.5x zoomed toward the tapped point; 2.5x -> 1x back to center
                            else if (zoom.floatValue > 1f) animateZoomTo(1f, offset) else animateZoomTo(2.5f, offset)
                        },
                    )
                }
                // Hold: 2x while the finger stays down, back to 1x on the way up (mockup "Video, held")
                .pointerInput(tile.id, isVideo) {
                    if (!isVideo) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val releasedInTime = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
                        if (releasedInTime == null) {
                            onHold(true)
                            waitForUpOrCancellation()
                            onHold(false)
                        }
                    }
                }
                // Deferred read: pan and zoom touch the layer block only, no recomposition, so it stays smooth
                .graphicsLayer {
                    scaleX = zoom.floatValue
                    scaleY = zoom.floatValue
                    translationX = pan.value.x
                    translationY = pan.value.y
                },
        ) {
            // the sharp decode fades in over the thumbnail, which stays opaque so the grid never shows through mid-zoom
            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            if (image !== thumb) image?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer { alpha = sharp.value }, contentScale = ContentScale.Fit)
            }
            // The current video plays on the phone's decoder, over the poster frame. A neighbour page keeps
            // the poster: one texture at a time, so the player never shows two videos at once.
            if (isVideo && current && player != null) {
                AndroidView(
                    factory = { TextureView(it) },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { runCatching { player.clearVideoSurface() } },
                ) { view -> player.setVideoTextureView(view) }
            }
        }
        if (pill != 0) GesturePill(
            if (pill > 0) "+5 s" else "−5 s",
            Modifier.align(if (pill > 0) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 40.dp),
        )
        if (holding) GesturePill("2x", Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
    }
}

/** The short feedback of a video gesture: +5 s / −5 s on the tapped half, 2x at the top while held (mockup "Player"). */
@Composable
private fun GesturePill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.background(viewerBg.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 10.dp),
        color = onViewer,
        style = MaterialTheme.typography.titleMedium,
    )
}
