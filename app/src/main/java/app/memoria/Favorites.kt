package app.memoria

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Every favorite by month, the month pinned, no day headings and no hearts (mockup "Favorites"). */
class Favorites(val months: Map<String, List<Tile>>, val all: List<Tile>)

/** "34 photos · 4 videos", dropping a zero part. */
private fun favoritesSubtitle(f: Favorites): String = listOfNotNull(
    f.all.count { it.duration == null }.takeIf { it > 0 }?.let { "$it photos" },
    f.all.count { it.duration != null }.takeIf { it > 0 }?.let { "$it videos" },
).joinToString(" · ")

/** Favorites grouped by month name, newest first. Comes from the timeline so the cache is shared. */
fun groupFavorites(items: List<TimelineItem>): Favorites {
    val tiles = items.filterIsInstance<Tile>()
    val month = DateTimeFormatter.ofPattern("LLLL yyyy")
    val zone = ZoneId.systemDefault()
    val months = linkedMapOf<String, MutableList<Tile>>()
    for (t in tiles) months.getOrPut(month.format(Instant.ofEpochMilli(t.date).atZone(zone))) { mutableListOf() } += t
    return Favorites(months, tiles)
}

/**
 * Favorites layout per docs/design.html "Favorites": like Videos, by month, the month pinned, no day
 * headings and no hearts. No MediaStore, so previews and tests render it.
 */
@Composable
fun FavoritesContent(
    favorites: Favorites?,
    granted: Boolean = true,
    onAsk: () -> Unit = {},
    onBack: () -> Unit = {},
    onOpen: (List<Tile>, Tile) -> Unit = { _, _ -> },
    pick: Pick = Pick(null),
) {
    val f = favorites
    val sel = pick.ids
    if (sel != null) BackHandler { pick.set(null) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Crossfade(sel != null, Modifier.fillMaxWidth(), animationSpec = tween(150), label = "topBar") { on ->
            if (on) TopBar(
                "${sel.orEmpty().size} selected", "Favorites · ${formatBytes(pick.size)}",
                onBack = { pick.set(null) }, backIcon = R.drawable.ic_close, backLabel = "Cancel",
            ) {
                if (f != null && sel.orEmpty().size < f.all.size) TextButton({ pick.set(f.all.map { it.id }.toSet()) }) { Text("Select all") }
            }
            else TopBar("Favorites", f?.let(::favoritesSubtitle), onBack = onBack)
        }
        if (!granted) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Memoria needs access to your photos and videos.")
                Button(onAsk, Modifier.padding(top = 12.dp)) { Text("Allow access") }
            }
            return@Column
        }
        if (f != null && f.all.isEmpty()) {
            Text("No favorites yet. Open a photo and tap the heart.", Modifier.padding(horizontal = 20.dp))
            return@Column
        }
        val grid = rememberLazyGridState()
        FollowViewer(grid, f?.all.orEmpty())
        // one (grid item index, month name) per month heading, so the slider can jump to it
        val months = remember(f) {
            var i = 0
            f?.months?.map { (m, tiles) -> (i to m).also { i += 1 + tiles.size } }.orEmpty()
        }
        // the Select bar floats over the grid, so nothing moves when it comes in
        Box(Modifier.weight(1f)) {
            MonthSliderBox(grid = grid, months = months, enabled = sel == null, top = monthHeadingHeight(below = 10.dp), modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    GridCells.Fixed(3), Modifier.testTag("favoritesGrid"), state = grid,
                    contentPadding = PaddingValues(bottom = if (sel != null) 72.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(1.dp), verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    f?.months?.forEach { (month, tiles) ->
                        stickyHeader(key = month, contentType = "month") { MonthHeading(month, month, grid, below = 10.dp) }
                        items(tiles, key = { it.id }) { t ->
                            val on = sel != null && t.id in sel
                            TileImage(
                                t,
                                Modifier.combinedClickable(
                                    onClick = { if (sel == null) onOpen(f.all, t) else pick.set(if (on) sel - t.id else sel + t.id) },
                                    onLongClick = { pick.set(sel.orEmpty() + t.id) },
                                ).testTag("tile-${t.id}").animateItem(fadeInSpec = null), // no fade in: a first load shows at once
                                selecting = sel != null, selected = on, heart = false,
                            )
                        }
                    }
                }
            }
            // everything here is a favorite, so the middle action always reads Unfavorite
            if (sel != null) {
                val picked = f?.all.orEmpty().filter { it.id in sel }
                SelectBar(true, { pick.share(picked) }, { pick.favorite(picked) }, { pick.delete(picked) }, onLock = { pick.lock(picked) }, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}
