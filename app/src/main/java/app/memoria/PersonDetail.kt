package app.memoria

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One person: name (tap to rename), the faces that match, their photos; fixes are in More. Layout per docs/design.html. */
@Composable
fun PersonDetail(
    p: Person,
    onBack: () -> Unit,
    onName: () -> Unit,
    onOpen: (List<Tile>, Tile) -> Unit,
    onReview: () -> Unit = {},
    onMerge: () -> Unit = {},
    onCover: () -> Unit = {},
    onHide: () -> Unit = {},
    loadPhotos: (suspend (List<Long>) -> List<Tile>)? = null, // tests inject a fake; null loads from MediaStore
    pick: Pick = Pick(null),
    onUnmerge: (() -> Unit)? = null, // null: nothing merged in, no Unmerge in More
    filter: Filter = Filter.All,
    onFilter: (Filter) -> Unit = {},
) {
    val context = LocalContext.current
    val sel = pick.ids
    if (sel != null) BackHandler { pick.set(null) }
    // keyed on the photos, not the person id: a merge or a fixed face keeps the id (old photos stay shown while it reloads)
    val photoIds = p.faces.map { it.photoId }.distinct()
    // cached, not produceState: the viewer replaces the whole screen tree, so on the way back an
    // empty first frame would let the grid's restored scroll clamp to the top (see cachedProduceState)
    val photos by cachedProduceState("person${p.id}", photoIds, pick.changes) {
        val load = loadPhotos ?: { ids: List<Long> -> photosByIds(context, ids) }
        runCatching { withContext(Dispatchers.IO) { load(photoIds) } }.getOrDefault(emptyList())
    }
    val (sure, unsure) = p.shownFaces.partition { it.sure }
    val name = p.name ?: "No name"
    // an unnamed person reads badly in a sentence ("11 may be No name"), so use "this person" there
    val who = p.name ?: if (p.pet) "this pet" else "this person"
    // the filter narrows the photo grid only: the faces row and the Unsure card stay as they are
    val shownPhotos = remember(photos, filter) { photos.orEmpty().filter { filter.keeps(it) } }
    val month = DateTimeFormatter.ofPattern("LLLL yyyy")
    val zone = ZoneId.systemDefault()
    val groups = remember(shownPhotos) {
        shownPhotos.groupBy { month.format(Instant.ofEpochMilli(it.date).atZone(zone)) }.entries.toList()
    }
    // one (grid item index, month name) per month heading, so the slider can jump to it; the items
    // before the first month are the Faces head and row, the Unsure card (if any), the Photos head
    val lead = if (unsure.isNotEmpty()) 4 else 3
    val months = remember(groups, lead) {
        var i = lead
        groups.map { (m, tiles) -> (i to m).also { i += 1 + tiles.size } }
    }
    var more by remember { mutableStateOf(false) }
    var filtering by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        // own Column: the Select bar goes under the grid
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Crossfade(sel != null, Modifier.fillMaxWidth(), animationSpec = tween(150), label = "topBar") { on ->
                if (on) TopBar(
                    "${sel.orEmpty().size} selected", "$name · ${formatBytes(pick.size)}",
                    onBack = { pick.set(null) }, backIcon = R.drawable.ic_close, backLabel = "Cancel",
                ) {
                    if (sel.orEmpty().size < shownPhotos.size) TextButton({ pick.set(shownPhotos.map { it.id }.toSet()) }) { Text("Select all") }
                }
                else TopBar(
                    title = if (p.name.isNullOrBlank()) "Add name" else name,
                    // videos the person is in count here and in the Photos hint, but never as a photo; the
                    // Filter icon takes the room the faces count had, so that count moved to the Faces hint
                    subtitle = photosAndVideos(p.photos, p.videos),
                    onBack = onBack,
                    leading = { FaceImage(p.cover, Modifier.padding(end = 12.dp).size(40.dp).clip(CircleShape)) },
                    onTitle = onName,
                    titleColor = if (p.name.isNullOrBlank()) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    trailing = {
                        FilterButton(filter) { filtering = true }
                        IconButton({ more = true }) { Icon(painterResource(R.drawable.ic_more), "More") }
                    },
                )
            }
            val grid = rememberLazyGridState()
            FollowViewer(grid, shownPhotos)
            // the Select bar floats over the grid, so nothing moves when it comes in
            Box(Modifier.weight(1f)) {
                MonthSliderBox(grid = grid, months = months, enabled = sel == null, top = monthHeadingHeight(below = 10.dp), modifier = Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        GridCells.Fixed(3), modifier = Modifier.testTag("personPhotos"), state = grid,
                        contentPadding = PaddingValues(bottom = if (sel != null) 72.dp else 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(1.dp), verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) { SectionHead(if (p.pet) "Pictures" else "Faces", hint = if (p.pet) "These match $who" else facesMatch(p.shownFaces.size, who)) }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                sure.take(30).forEach { FaceImage(it, Modifier.size(44.dp).clip(CircleShape)) }
                            }
                        }
                        if (unsure.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            // "may be this person" wraps next to the face stack
                            ReviewCard(unsure, onReview, title = "Unsure faces", note = if (p.name.isNullOrBlank()) "${unsure.size} to check" else "${unsure.size} may be ${p.name}")
                        }
                        // the hint follows the filter, unlike the title line (mockup "Person, Videos");
                        // on All it is the person's own counts, which are there before the tiles load
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionHead("Photos", hint = "${if (filter == Filter.All) p.photos + p.videos else shownPhotos.size} · by date")
                        }
                        if (filter != Filter.All && shownPhotos.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            FilterEmpty(filter, if (p.name.isNullOrBlank()) if (p.pet) "This pet" else "This person" else p.name)
                        }
                        groups.forEach { (m, tiles) ->
                            stickyHeader(key = m, contentType = "month") { MonthHeading(m, m, grid, below = 10.dp) }
                            items(tiles, key = { it.id }) { t ->
                                val on = sel != null && t.id in sel
                                TileImage(
                                    t,
                                    Modifier.combinedClickable(
                                        onClick = { if (sel == null) onOpen(shownPhotos, t) else pick.set(if (on) sel - t.id else sel + t.id) },
                                        onLongClick = { pick.set(sel.orEmpty() + t.id) },
                                    ).testTag("tile-${t.id}").animateItem(fadeInSpec = null), // no fade in: a first load shows at once
                                    selecting = sel != null, selected = on,
                                )
                            }
                        }
                    }
                }
                if (sel != null) {
                    val picked = shownPhotos.filter { it.id in sel }
                    SelectBar(picked.isNotEmpty() && picked.all { it.favorite }, { pick.share(picked) }, { pick.favorite(picked) }, { pick.delete(picked) }, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        Menu(filtering, onDismiss = { filtering = false }, items = filterItems(filter) { filtering = false; onFilter(it) }, end = 56.dp)
        Menu(
            more, onDismiss = { more = false },
            items = listOfNotNull(
                MenuItem(if (p.pet) R.drawable.ic_paw else R.drawable.ic_person, if (p.name.isNullOrBlank()) "Add name" else "Rename") { more = false; onName() },
                MenuItem(R.drawable.ic_person_add, "Merge with") { more = false; onMerge() },
                onUnmerge?.let { MenuItem(R.drawable.ic_person_remove, "Unmerge") { more = false; it() } },
                MenuItem(R.drawable.ic_photo, "Change cover") { more = false; onCover() },
                if (p.hidden) MenuItem(R.drawable.ic_eye, if (p.pet) "Show pet" else "Show person", dividerBefore = true) { more = false; onHide() }
                else MenuItem(R.drawable.ic_block, if (p.pet) "Hide pet" else "Hide person", dividerBefore = true) { more = false; onHide() },
            ),
        )
    }
}

/** Bordered action chip, like the mockup's Search chips. */
@Composable
fun Chip(label: String, icon: Int, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(label, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    }
}
