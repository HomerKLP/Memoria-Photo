package app.memoria

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Shared screen parts, one implementation each. The mockup has the same list under
 * "Shared parts" (`docs/design.html`): check it before building a new screen.
 */

private val cache = mutableMapOf<String, Any?>()

/**
 * Like produceState, but starts from what this key had last time and remembers the fresh value.
 * A screen that starts empty is short for a frame, and a list's restored scroll clamps to that.
 * An empty result is never remembered: it is as short as no cache, and a screen that closes when its
 * set runs empty (see [CloseWhenEmpty]) must not close on a stale empty value.
 */
@Composable
fun <T : Any> cachedProduceState(
    key: String,
    vararg keys: Any?,
    isEmpty: (T) -> Boolean = { it is Collection<*> && it.isEmpty() },
    load: suspend () -> T,
): State<T?> =
    produceState(cache[key] as T?, key, *keys) {
        val fresh = load()
        if (!isEmpty(fresh)) cache[key] = fresh
        value = fresh
    }

/**
 * Replaces one cached value in place. A screen that is off composition (Library while a person is open)
 * starts from this cache, so a small change like a new cover shows on its first frame instead of the
 * stale value. Does nothing when the key is not cached or holds another type.
 */
fun <T : Any> updateCached(key: String, type: Class<T>, transform: (T) -> T) =
    updateInCache(cache, key, type, transform)

/** The map part of [updateCached], split out so it can be unit-tested without a composition. */
internal fun <T : Any> updateInCache(map: MutableMap<String, Any?>, key: String, type: Class<T>, transform: (T) -> T) {
    val current = map[key]
    if (current == null || !type.isInstance(current)) return
    @Suppress("UNCHECKED_CAST")
    map[key] = transform(current as T)
}

/**
 * Calls [onClose] once a load has finished and found nothing. A page whose whole set ran empty (the
 * last photo of an album, a place or the videos) closes instead of sitting there blank. [value] null
 * means the screen is still loading, so nothing happens yet.
 */
@Composable
fun <T> CloseWhenEmpty(value: T?, empty: Boolean, onClose: () -> Unit) {
    LaunchedEffect(value, empty) { if (value != null && empty) onClose() }
}

/**
 * One title, one subtitle line, one trailing button. The subtitle keeps its line even when it is
 * empty, so the bar is the same height in every mode and the list under it never shifts.
 * [leading] sits between Back and the title (the Person screen puts the face there).
 */
@Composable
fun TopBar(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)? = null,
    backIcon: Int = R.drawable.ic_back,
    backLabel: String = "Back",
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    Row(Modifier.testTag("topBar").fillMaxWidth().padding(start = 4.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onBack) { Icon(painterResource(backIcon), backLabel) }
        leading()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontSize = 20.sp)
            // "\u00A0" and not "": an empty string may measure to nothing and the line would collapse
            Text(subtitle ?: "\u00A0", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

/** Quiet hint above a list. Keep it in every mode of the screen, or the list under it moves. */
@Composable
fun Note(icon: Int, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Two buttons at the bottom: the danger one outlined, the safe one filled with the accent. [danger] false: grey
 * outline (Help recognize's No, the New album picker's Cancel). [dangerIcon] null draws no icon (New album's Cancel
 * has none in the mockup). [primaryEnabled] false greys the safe button out (Move here until a photo is picked).
 */
@Composable
fun ActionBar(
    dangerLabel: String,
    dangerIcon: Int?,
    onDanger: () -> Unit,
    primaryLabel: String,
    primaryIcon: Int,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = true,
    primaryEnabled: Boolean = true,
) {
    val outline = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val content = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val primaryBg = if (primaryEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val primaryContent = if (primaryEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.weight(1f).height(52.dp).clip(CircleShape).border(1.dp, outline, CircleShape).clickable(onClick = onDanger),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
        ) {
            dangerIcon?.let { Icon(painterResource(it), null, Modifier.size(20.dp), tint = content) }
            Text(dangerLabel, color = content, fontWeight = FontWeight.Medium, fontSize = 15.sp)
        }
        Row(
            Modifier.weight(1f).height(52.dp).clip(CircleShape).background(primaryBg).clickable(enabled = primaryEnabled, onClick = onPrimary),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(primaryIcon), null, Modifier.size(20.dp), tint = primaryContent)
            Text(primaryLabel, color = primaryContent, fontWeight = FontWeight.Medium, fontSize = 15.sp)
        }
    }
}

/**
 * Bottom bar while selecting (mockup "Select bar"). It takes the bottom tabs' place and is built from
 * the same [ShortNavigationBar], so it has exactly the same height and the grid never moves. Share and
 * Favorite read normally, Delete sits last in red. [favorite] true: everything picked is already a
 * favorite, so the middle action reads Unfavorite. [onMove] adds Move (albums only); four actions put
 * the label under the icon, or they do not fit at 360 dp.
 */
@Composable
fun SelectBar(
    favorite: Boolean,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    onMove: (() -> Unit)? = null,
) {
    val error = MaterialTheme.colorScheme.error
    val normal = ShortNavigationBarItemDefaults.colors(
        unselectedIconColor = MaterialTheme.colorScheme.onSurface,
        unselectedTextColor = MaterialTheme.colorScheme.onSurface,
    )
    val danger = ShortNavigationBarItemDefaults.colors(unselectedIconColor = error, unselectedTextColor = error)
    val at = if (onMove != null) NavigationItemIconPosition.Top else NavigationItemIconPosition.Start
    ShortNavigationBar {
        ShortNavigationBarItem(
            false, onShare,
            { Icon(painterResource(R.drawable.ic_share), null) },
            { Text("Share") },
            iconPosition = at,
            colors = normal,
        )
        ShortNavigationBarItem(
            false, onFavorite,
            { Icon(painterResource(R.drawable.ic_favorite_border), null) },
            { Text(if (favorite) "Unfavorite" else "Favorite") },
            iconPosition = at,
            colors = normal,
        )
        if (onMove != null) ShortNavigationBarItem(
            false, onMove,
            { Icon(painterResource(R.drawable.ic_move), null) },
            { Text("Move") },
            iconPosition = at,
            colors = normal,
        )
        ShortNavigationBarItem(
            false, onDelete,
            { Icon(painterResource(R.drawable.ic_trash_line), null) },
            { Text("Delete") },
            iconPosition = at,
            colors = danger,
        )
    }
}

/** Icon, one line, one sentence. For a list that is really empty, never for a list that is still loading. */@Composable
fun EmptyState(icon: Int, title: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.surfaceVariant)
        Text(title, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleMedium, fontSize = 20.sp)
        Text(text, Modifier.padding(top = 10.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** The slider strip: this much of the right edge catches drags (mockup: `.slider` width 28px). */
private val sliderWidth = 28.dp

/** The rail keeps this gap at the top and the bottom of the slider box (mockup: `.rail` top and bottom 10px). */
private val sliderPad = 10.dp

/** The slider box ends this far above the grid's bottom, clear of the bar (mockup: 33px above it). */
private val sliderBottom = 33.dp

/** The slider box starts this far under the pinned month heading (mockup: 3px). */
private val sliderTopGap = 3.dp

/** Height of a pinned month heading: `titleLarge` with 20 dp above it and [below] under it. */
@Composable
fun monthHeadingHeight(below: Dp): Dp =
    20.dp + with(LocalDensity.current) { MaterialTheme.typography.titleLarge.lineHeight.toDp() } + below

/** Where the month at [index] of the rail sits. The months run from [pad] to height - [pad]. */
internal fun trackY(months: Int, index: Int, height: Dp, pad: Dp): Dp {
    val track = (height - pad * 2).coerceAtLeast(0.dp)
    return pad + track * (if (months < 2) 0.5f else index.toFloat() / (months - 1))
}

/** Which month the finger at [y] points at, the same rounding the knob is placed with. */
internal fun monthAt(y: Float, height: Int, pad: Float, months: Int): Int {
    if (months < 2) return 0
    val track = (height - pad * 2).coerceAtLeast(1f)
    return (((y - pad) / track).coerceIn(0f, 1f) * (months - 1)).roundToInt()
}

/**
 * Timeline month slider (mockup: "Timeline month slider"). [content] is the timeline; the slider
 * sits over its right edge: a thin rail, a knob on the month on screen, and a bubble with the
 * month while [dragging] (the month index under the finger, null when not dragging).
 * Show it only while scrolling: [visible] fades it in and out, the caller owns the timing. [top] is
 * the pinned month heading's height: the slider starts just under it and ends clear of the bottom bar.
 *
 * The drag is caught on the Initial pass and only after touch slop, so the grid never scrolls away
 * under it and a plain tap on a photo behind the strip still reaches its tile.
 */
@Composable
fun MonthSlider(
    months: List<String>,
    current: Int,
    dragging: Int?,
    visible: Boolean,
    onDragging: (Boolean) -> Unit,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    top: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outlineVariant
    val on = enabled && (visible || dragging != null)
    val alpha by animateFloatAsState(if (on) 1f else 0f, tween(250), label = "monthSlider")
    Box(
        modifier.fillMaxSize().monthSliderDrag(
            months = if (enabled) months.size else 0,
            top = top + sliderTopGap,
            onDragging = onDragging,
            onMonth = { onJump(it); haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
        )
    ) {
        content()
        // keep it composed while it fades out, and leave it out entirely when the slider is off
        if (alpha > 0.01f) BoxWithConstraints(Modifier.fillMaxSize().padding(top = top + sliderTopGap, bottom = sliderBottom).alpha(alpha)) {
            val now = dragging ?: current
            // rail
            Box(
                Modifier.align(Alignment.TopEnd).offset(y = sliderPad).padding(end = 12.dp)
                    .width(3.dp).height((maxHeight - sliderPad * 2).coerceAtLeast(0.dp))
                    .testTag("monthRail")
                    .background(line, RoundedCornerShape(2.dp))
            )
            // knob: an 18 dp accent dot in a 3 dp white ring (24 dp total), per the mockup
            Box(
                Modifier.align(Alignment.TopEnd)
                    .offset(y = trackY(months.size, now, maxHeight, sliderPad) - 12.dp)
                    .padding(end = 1.dp).size(24.dp).testTag("monthKnob")
                    .shadow(2.dp, CircleShape, clip = false, spotColor = Color.Black.copy(alpha = 0.3f))
                    .background(Color.White.copy(alpha = 0.85f), CircleShape)
                    .padding(3.dp).background(accent, CircleShape)
            )
            dragging?.let { i ->
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .offset(y = trackY(months.size, i, maxHeight, sliderPad) - 16.dp)
                        .padding(end = 30.dp).height(32.dp)
                        .clip(RoundedCornerShape(16.dp)).background(accent).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(months.getOrNull(i).orEmpty(), color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/**
 * Wraps a date-grouped grid in a [MonthSlider]: [months] is one (grid item index, name) per month
 * heading. The slider fades in while scrolling and out 2 s after the last step, stays hidden at the
 * top, and a drag jumps the grid to that month's heading. Shared by the timeline and Place.
 * [top] is the pinned heading's height, see [monthHeadingHeight].
 */
@Composable
fun MonthSliderBox(
    grid: androidx.compose.foundation.lazy.grid.LazyGridState,
    months: List<Pair<Int, String>>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    top: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val starts = remember(months) { months.map { it.first } }
    val names = remember(months) { months.map { it.second } }
    val current by remember(grid, starts) {
        derivedStateOf {
            // at the end the last months may be shorter than the screen, so the knob goes to the rail's end
            if (grid.canScrollBackward && !grid.canScrollForward) starts.lastIndex.coerceAtLeast(0)
            else starts.indexOfLast { it <= grid.firstVisibleItemIndex }.coerceAtLeast(0)
        }
    }
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(false) } // fades in while scrolling, out two seconds later
    var tick by remember { mutableIntStateOf(0) } // bumped on scroll activity, restarts the hide timer
    var drag by remember { mutableStateOf<Int?>(null) }
    // the top is where the first month starts: Person has a header above its months, the others start at 0
    val first = starts.firstOrNull() ?: 0
    LaunchedEffect(grid, first) {
        snapshotFlow { grid.firstVisibleItemIndex to grid.isScrollInProgress }.collect { (index, scrolling) ->
            if (drag != null) return@collect
            if (index <= first && !scrolling) on = false // hidden at the top, where there is nothing to jump to
            else if (scrolling || index > first) { on = true; tick++ }
        }
    }
    LaunchedEffect(tick) {
        if (tick > 0) { delay(2000); if (drag == null) on = false }
    }
    MonthSlider(
        months = names, current = current, dragging = drag, visible = on,
        onDragging = { dragging ->
            if (dragging) on = true
            else {
                drag = null
                // a drag that ended at the first month hides it, like a scroll that reached the top
                if (grid.firstVisibleItemIndex > first) { on = true; tick++ } else on = false
            }
        },
        onJump = { i -> drag = i; scope.launch { grid.scrollToItem(starts.getOrElse(i) { 0 }) } },
        modifier = modifier, enabled = enabled, top = top, content = content,
    )
}

/** Drags that start on the right [sliderWidth] strip jump to a month; all other touches go to the content. */
private fun Modifier.monthSliderDrag(months: Int, top: Dp, onDragging: (Boolean) -> Unit, onMonth: (Int) -> Unit): Modifier =
    pointerInput(months, top) {
        if (months < 1) return@pointerInput
        val strip = sliderWidth.toPx()
        val pad = sliderPad.toPx()
        val above = top.toPx()
        val below = sliderBottom.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.x < size.width - strip) return@awaitEachGesture
            var month = -1
            var own = false
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.lastOrNull { it.id == down.id } ?: break
                if (!own) {
                    // A plain tap under the strip belongs to the tile, so wait for real movement first
                    if (change.changedToUpIgnoreConsumed()) break
                    if ((change.position - down.position).getDistance() < viewConfiguration.touchSlop) continue
                    own = true
                    onDragging(true)
                }
                if (change.changedToUpIgnoreConsumed()) break
                // the rail lives in the slider box, so measure from its top and over its height
                val m = monthAt(change.position.y - above, (size.height - above - below).toInt(), pad, months)
                if (m != month) {
                    month = m
                    onMonth(m)
                }
                change.consume()
            }
            if (own) onDragging(false)
        }
    }

/** One line in a [Menu]: an icon, a label, an action. [dividerBefore] draws a line above it (groups). [checked] shows the accent check. */
class MenuItem(val icon: Int, val label: String, val dividerBefore: Boolean = false, val checked: Boolean = false, val onClick: () -> Unit)

/**
 * The shared Menu part (mockup "Menu"): drops under a top bar's More, over the screen, no dim. One
 * line per item with an icon; [dividerBefore] splits groups. Tap outside or Back closes it, so
 * [modifier] should fill the screen (the dismiss layer is the whole screen). The menu is part of the
 * composition, not a Popup, so the layout tests and previews can render it.
 */
@Composable
fun Menu(expanded: Boolean, onDismiss: () -> Unit, items: List<MenuItem>, modifier: Modifier = Modifier) {
    if (!expanded) return
    // a preview has no back dispatcher; guard so a plain render does not crash
    if (LocalOnBackPressedDispatcherOwner.current != null) BackHandler(onBack = onDismiss)
    Box(
        modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss,
        ),
    ) {
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 88.dp, end = 8.dp)
                .widthIn(min = 248.dp).width(IntrinsicSize.Max) // a long label stays on one line
                .shadow(12.dp, RoundedCornerShape(16.dp), spotColor = Color.Black.copy(alpha = 0.5f))
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(vertical = 8.dp)
                // a tap on the menu itself must not reach the dismiss layer behind it
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            items.forEachIndexed { i, item ->
                if (item.dividerBefore && i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().height(48.dp).clickable(onClick = item.onClick).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(painterResource(item.icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.label, Modifier.weight(1f), fontSize = 15.sp, maxLines = 1)
                    if (item.checked) Icon(painterResource(R.drawable.ic_check), "On", Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
