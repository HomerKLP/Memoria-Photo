package app.memoria

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.provider.MediaStore
import android.view.TextureView
import android.view.WindowInsets
import android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
import android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
import android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val onViewer = Color(0xFFF4ECE1)
internal val viewerBg = Color(0xFF120E0A)

/** Full-screen photos, swipe sideways for the next one. Opens on the tile with id start. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class]) // the player's scrubbing mode
@Composable
fun Viewer(start: Long, tiles: List<Tile>, onBack: () -> Unit, showInAlbum: Boolean = true, onShowInAlbum: (Tile) -> Unit = {}) {
    val context = LocalContext.current
    // One player for the screen: the video on the page plays, a photo stops it. Media3 runs on the
    // phone's own hardware decoder, the same one the system player uses, so the usual codecs play fast.
    // Audio focus is asked for, so a call or another app's music owns the sound like in a real player.
    val player = remember {
        // ExoPlayer changes the sound's speed itself (Sonic), not the system: letting the AudioTrack do it
        // (setEnableAudioTrackPlaybackParams) froze the clock after a pause and play on the test phone,
        // until media3 gave up with ERROR_CODE_TIMEOUT (bugs.md, 2026-11-17). A newer media3 does not
        // help: 1.12.0-beta01 moved the clock +63/+29/+27 ms per 0.5 s of play, the same freeze.
        ExoPlayer.Builder(context, DefaultRenderersFactory(context)).setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        ).setHandleAudioBecomingNoisy(true) // headphones out: pause, never go on through the speaker
            .build()
    }
    val surface = remember { VideoSurface() } // which page's view the player draws to
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<Long?>(null) } // the video the decoder refused: the card stays with it
    var playAt by remember { mutableLongStateOf(0L) } // where the video is, for the bar and the line
    var videoLength by remember { mutableLongStateOf(0L) }
    var loop by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) } // 2x while a finger is down
    var videoAspect by remember { mutableStateOf(0f) } // the held video's own width / height: a TextureView stretches without it
    var hdr by remember { mutableStateOf(false) } // the held video is HLG or PQ (Dolby Vision from a phone is HLG underneath)
    // The video the player holds: only its page draws the video, and a nudged page never restarts it
    var prepared by remember { mutableStateOf<Long?>(null) }
    var pill by remember { mutableStateOf(0) } // +5 s / −5 s, on the half that was tapped
    var touched by remember { mutableIntStateOf(0) } // any finger on the viewer restarts the controls' 2 s fade
    var menuOpen by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        // Counted for the one line written when the viewer closes: a stutter or a silent clip on the phone
        // is then in memoria.log with a number next to it
        var dropped = 0
        var underruns = 0
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            // the card hides the reason, so a refused clip leaves a line in the log
            override fun onPlayerError(error: PlaybackException) { failed = prepared; AppLog.w("player: ${error.errorCodeName} ${error.message}", error.cause) }
            override fun onVideoSizeChanged(size: VideoSize) {
                videoAspect = videoAspectOf(size.width, size.height, size.pixelWidthHeightRatio)
                AppLog.i("player: video ${size.width}x${size.height}")
            }
            // a new clip's empty tracks are skipped, so two HDR clips in a row never dim in between
            override fun onTracksChanged(tracks: Tracks) {
                if (!tracks.isEmpty) hdr = tracks.groups.any { g ->
                    g.type == C.TRACK_TYPE_VIDEO && g.isSelected &&
                        g.getTrackFormat(0).colorInfo?.colorTransfer.let { it == C.COLOR_TRANSFER_HLG || it == C.COLOR_TRANSFER_ST2084 }
                }
            }
        }
        val analytics = object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) { dropped += droppedFrames }
            override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) { underruns++ }
        }
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        onDispose {
            player.removeListener(listener)
            val at = runCatching { player.currentPosition }.getOrNull()
            player.release() // joins the playback thread, so the counters below are the last ones
            AppLog.i("player: closed at $at ms, dropped $dropped frames, $underruns audio underruns")
        }
    }
    LaunchedEffect(loop) { player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    LaunchedEffect(holding) {
        player.setPlaybackSpeed(if (holding) 2f else 1f)
        if (player.currentMediaItem != null) AppLog.i("player: speed ${if (holding) "2x" else "1x"} at ${runCatching { player.currentPosition }.getOrNull()} ms")
    }
    // A playing video keeps the screen awake, like the phone's own player; otherwise the display sleeps mid-clip
    val rootView = LocalView.current
    LaunchedEffect(playing) { rootView.keepScreenOn = playing }
    DisposableEffect(Unit) { onDispose { rootView.keepScreenOn = false } }
    // Leaving the app with a video playing must not leave its sound running behind the home screen
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
        // Immersive: the bars slide out in 340 ms. The default behavior fades them over 1.5 s, so the status
        // bar stayed long after the viewer's own bars were gone (Android's InsetsController durations)
        controller.systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
            window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            viewerTile = null
            if (editedIn) viewerEdits++
        }
    }
    val pager = rememberPagerState(tiles.indexOfFirst { it.id == start }.coerceAtLeast(0)) { list.size }
    // coerce: after trashing the last photo the pager is one frame behind the shorter list
    val tile = list[pager.currentPage.coerceAtMost(list.lastIndex)]
    // A video starts only once the pages stand still: mid-swipe the leaving video plays on, on its own page
    val atRest = !pager.isScrollInProgress
    LaunchedEffect(tile.id) { viewerTile = tile.id }
    // The new page's clock resets at once; the player still holds the last video until the swipe settles
    LaunchedEffect(tile.id) {
        pill = 0
        playAt = 0
        videoLength = tile.duration ?: 0L
    }
    // The clock follows the player only while it holds this page's video. Playing, a short clip ticks often
    // enough for a smooth line (about 3 px a step), a long one at most 4 times a second.
    LaunchedEffect(tile.id, prepared, playing) {
        if (prepared != tile.id) return@LaunchedEffect
        while (true) {
            // a released player (the screen is closing) must not be asked anything
            runCatching {
                playAt = player.currentPosition.coerceAtLeast(0L)
                player.duration.takeIf { it > 0 }?.let { videoLength = it }
            }
            delay(if (playing) (videoLength / 360).coerceIn(16L, 250L) else 250L)
        }
    }
    // The page in view is what plays. A photo stops the player, so its sound never runs on behind it.
    LaunchedEffect(tile.id, atRest) {
        if (!atRest) return@LaunchedEffect
        if (tile.duration == null) {
            player.pause()
            player.clearMediaItems()
            prepared = null
            hdr = false
        } else if (prepared != tile.id) {
            videoAspect = 0f // the last video's shape must not size this one
            failed = null
            player.setMediaItem(MediaItem.fromUri(tile.uri))
            player.prepare()
            player.play()
            prepared = tile.id
            AppLog.i("player: open ${tile.duration} ms, ratio ${tile.ratio}")
        }
    }
    // A TextureView shows HDR only in an HDR window, so the window goes HDR while the player holds an HDR clip.
    // The phone fades the extra brightness in and out by itself (3 to 5 s on the test phone).
    val hdrOn = hdr && !closing
    LaunchedEffect(hdrOn) { window.colorMode = if (hdrOn) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT }
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
        if (prepared != tile.id || failed == tile.id) return // a photo, or a video the player does not hold yet
        val end = player.duration.coerceAtLeast(0L)
        val to = (player.currentPosition + dir * 5000L).coerceIn(0L, if (end > 0L) end else Long.MAX_VALUE)
        player.seekTo(to)
        AppLog.i("player: skip ${if (dir > 0) "+5" else "-5"} s to $to ms")
        playAt = to
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
        player.pause() // the sound stops with the tap, and a still frame shrinks into the tile
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
    // Playing, the controls fade 2 s after the last touch; paused, or with a menu or the details open, they stay
    LaunchedEffect(barsVisible, playing, touched, details, menuOpen, moving) {
        if (barsVisible && playing && !details && !menuOpen && !moving) { delay(2000); barsVisible = false }
    }
    // Photo fills the whole screen, the bars float on it (transparent gradient, see ViewerChrome)
    val zone = Instant.ofEpochMilli(tile.date).atZone(ZoneId.systemDefault())
    val dayPattern = if (zone.year == LocalDate.now().year) "EEE, d MMM" else "EEE, d MMM yyyy"
    // The photo moves alone (ViewerPage); this dark backdrop only fades, so the screen under shows through.
    Box(
        Modifier.fillMaxSize().onSizeChanged { view = it }.drawBehind { drawRect(viewerBg, alpha = backdrop()) }
            // only watches: every finger restarts the controls' fade, nothing is taken from the page or the bars
            .pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); touched++ } } },
    ) {
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
                // inView takes the open and close motion, the drag and the details; the video shows on the page
                // whose file the player holds, so a leaving video slides out still playing
                val inView = i == pager.currentPage
                val holds = list[i].id == prepared
                ViewerPage(
                    list[i], { zoomed = it }, onTap = { if (details) settle(false) else barsVisible = !barsVisible },
                    shown = if (inView) ({ shown.value }) else ({ 1f }),
                    dragY = if (inView) ({ dragY.value }) else ({ 0f }),
                    details = if (inView) ({ if (openUp() > 0f) (up.value / openUp()).coerceIn(0f, 1f) else 0f }) else ({ 0f }),
                    fly = fly,
                    player = player,
                    surface = surface,
                    current = holds,
                    aspect = if (holds) videoAspect else 0f, // a neighbour keeps its own poster's shape
                    pill = if (inView) pill else 0,
                    holding = inView && holding,
                    onSkip = { skip(it) },
                    onHold = { if (inView) holding = it },
                )
            }
            // the bars step aside while the photo moves
            val still by remember { derivedStateOf { shown.value == 1f && dragY.value == 0f && !closing } }
            val video = tile.duration != null && failed != tile.id
            // A file the decoder refuses: the card replaces the player line; the bars stay over its scrim
            if (tile.duration != null && failed == tile.id) PlayerFailed(tile) { openInPlayer(context, tile) }
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
                onMenu = { menuOpen = it },
                playerBar = if (video) ({
                    PlayerBar(
                        playing = playing,
                        position = playAt,
                        length = videoLength,
                        onToggle = {
                            // mid-swipe the player still holds the last video
                            if (prepared == tile.id && player.isPlaying) {
                                player.pause()
                                AppLog.i("player: pause at ${player.currentPosition} ms")
                            } else if (prepared == tile.id) {
                                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                                player.play()
                                AppLog.i("player: play at ${player.currentPosition} ms")
                            }
                        },
                        // the knob stays where it was let go, not where the clock last read
                        onSeek = { if (prepared == tile.id) { player.seekTo(it); playAt = it } },
                        // Media3's mode for many quick seeks: the frame follows the finger, playback waits for the lift
                        onScrubbing = {
                            if (prepared == tile.id) {
                                player.isScrubbingModeEnabled = it
                                if (!it) AppLog.i("player: seek to ${player.currentPosition} ms")
                            }
                        },
                    )
                }) else null,
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
        }
    }
    if (moving) MoveToAlbumSheet(tile, onDismiss = { moving = false }, onMove = { path -> moving = false; applyMove(tile.id, path) })
}
