package app.memoria

import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

/** Everything [PeopleScreen] shows, in one pass, also warmed at app start (see [preloadPeople]). */
suspend fun peopleData(dao: IndexDao): PeopleData = withContext(Dispatchers.IO) {
    // Faces without vectors: the grid only shows pictures. Merge with ranks by distance, so it loads them itself.
    val faces = dao.taggedFaces()
    val byPerson = faces.groupBy { it.personId }
    // getValue would crash on a person with no faces (stopped index, manual edit); skip it
    val people = dao.people().mapNotNull { p -> byPerson[p.id]?.let { fs -> Person(fs.map { it.toVec() }, (fs.firstOrNull { it.id == p.mainFaceId } ?: fs.first()).toVec(), p.id, p.name, p.hidden, p.coverFaceId, p.pos) } }
        .sortedWith(compareBy({ it.name.isNullOrBlank() }, { it.pos ?: Int.MAX_VALUE }, { -it.photos })) // named first, moved ones in their order, then by photo count
    // hidden people stay in the list, so one opened from Hidden people still shows
    val shown = people.filter { !it.hidden }
    val unsure = shown.flatMap { p -> p.shownFaces.filter { !it.sure } }.sortedBy { it.quality }
    PeopleData(peopleAndPets(shown), people, unsure)
}

/** Loads the list and its first covers at app start, so the first open shows faces instead of grey circles. */
suspend fun preloadPeople(context: Context) = preloadCached("people") {
    val dao = Db.get(context).index()
    peopleData(dao).also { data ->
        withContext(Dispatchers.IO) {
            data.people.filter { !it.hidden }.take(60).forEach { p ->
                if (faceBitmaps.get(p.cover.id) == null) runCatching {
                    dao.thumb(p.cover.id)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                }.getOrNull()?.also { faceBitmaps.put(p.cover.id, it) }
            }
        }
    }
}

/** Loads people, unsure faces and the index totals, then hands them to [PeopleContent]. */
@Composable
fun PeopleScreen(
    openId: Long?,
    onOpenPerson: (Long) -> Unit,
    onPersonBack: () -> Unit,
    onBack: () -> Unit,
    onOpen: (List<Tile>, Tile) -> Unit = { _, _ -> },
    onHelpRecognize: (Long?) -> Unit = {},
    hiddenOnly: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { Db.get(context) }
    val dao = remember { db.index() }
    var reload by remember { mutableStateOf(0) }
    var naming by remember { mutableStateOf<Person?>(null) }
    var merging by remember { mutableStateOf<Person?>(null) }
    var covering by remember { mutableStateOf<Person?>(null) }
    var hiding by remember { mutableStateOf<Person?>(null) }
    var selected by remember { mutableStateOf<List<Long>?>(null) }
    var hidingAll by remember { mutableStateOf<List<Person>?>(null) }
    var unmerging by remember { mutableStateOf<Person?>(null) }
    var unmergeBusy by remember { mutableStateOf(false) }
    // [saved] as a key: the run saves people every so often, so they show up while indexing.
    val saved by peopleSaved.collectAsState()
    val result by cachedProduceState("people", reload, saved) { peopleData(dao) }
    // the app's own Back closes the list, with the back swipe preview
    BackHandler(naming != null || selected != null || openId != null) { if (naming != null) naming = null else if (selected != null) selected = null else onPersonBack() }
    val merges by produceState(emptyList<MergeRow>(), reload, saved) { value = withContext(Dispatchers.IO) { dao.merges() } }
    val pick = rememberPick()
    LaunchedEffect(openId) { pick.set(null) }
    // The open person left the list (faces removed or regrouped away): go back to the list instead of a
    // stale page. Close only after the person was seen once, so a stale cached list missing a person
    // just created elsewhere (Library -> the person) does not close the screen on open.
    var sawOpen by remember { mutableStateOf(false) }
    LaunchedEffect(result, openId) {
        if (openId == null) sawOpen = false
        else if (result?.people?.any { it.id == openId } == true) sawOpen = true
        else if (sawOpen && result != null) onPersonBack()
    }

    val r = result
    PeopleContent(
        data = r,
        openId = openId,
        onBack = onBack,
        onOpenPerson = onOpenPerson,
        onClosePerson = onPersonBack,
        onName = { naming = it },
        onOpen = onOpen,
        onHelpRecognize = onHelpRecognize,
        hiddenOnly = hiddenOnly,
        pick = pick,
        onMerge = { merging = it },
        onCover = { covering = it },
        onHide = { p ->
            // NonCancellable: Back right after a tap closes this scope, and the write must still land
            if (p.hidden) scope.launch { withContext(Dispatchers.IO + NonCancellable) { dao.setHidden(p.id, false) }; reload++ } else hiding = p
        },
        selected = selected,
        onSelect = { selected = it },
        onMergeAll = { list ->
            selected = null
            scope.launch {
                val merged = withContext(Dispatchers.IO + NonCancellable) { runCatching { mergeAll(db, list) } }
                merged.exceptionOrNull()?.let { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                reload++
            }
        },
        onHideAll = { hidingAll = it },
        merges = merges,
        onUnmerge = { unmerging = it },
        onReorder = { ids ->
            scope.launch {
                withContext(Dispatchers.IO + NonCancellable) { db.withTransaction { ids.forEachIndexed { i, id -> dao.setPos(id, i) } } }
                // the Library row starts from its cache on Back, so its first frame shows the new order too
                updateCached("library", LibraryData::class.java) { d -> d.copy(faces = d.faces.sortedBy { ids.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }) }
                reload++
            }
        },
    )
    merging?.let { p ->
        MergeSheet(p, r?.people.orEmpty().filter { it.id != p.id && !it.hidden && canMerge(it, p) }, onDismiss = { merging = null }) { other ->
            merging = null
            scope.launch {
                val merged = withContext(Dispatchers.IO + NonCancellable) { runCatching { mergePeople(db, p, other) } }
                merged.exceptionOrNull()?.let { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                reload++
            }
        }
    }
    covering?.let { p ->
        ChangeCoverSheet(p, onDismiss = { covering = null }) { faceId ->
            covering = null
            scope.launch {
                withContext(Dispatchers.IO + NonCancellable) { dao.setCover(p.id, faceId) }
                // The Library tab is off composition while this person is open, so update its cache (and
                // People's) in place; otherwise its first frame back shows the old cover, then flips.
                updateCached("library", LibraryData::class.java) { it.withCover(p.id, faceId) }
                updateCached("people", PeopleData::class.java) { it.withCover(p.id, faceId) }
                reload++
            }
        }
    }
    hiding?.let { p ->
        val name = p.name?.takeIf { it.isNotBlank() }
        AlertDialog(
            onDismissRequest = { hiding = null },
            icon = { Icon(painterResource(R.drawable.ic_block), null) },
            title = { Text("Hide ${name ?: if (p.pet) "this pet" else "this person"}?") },
            text = {
                Text(
                    if (p.pet) "${name ?: "This pet"} leaves People & pets, Library and search. Its pictures stay with it, so they never join another pet. Show it again from Settings, Hidden people & pets."
                    else "${name ?: "This person"} leaves People, Library and search. Their faces stay with them, so they never join someone else. Show them again from Settings, Hidden people & pets.",
                )
            },
            confirmButton = {
                TextButton({
                    hiding = null
                    scope.launch {
                        withContext(Dispatchers.IO + NonCancellable) { dao.setHidden(p.id, true) }
                        reload++
                    }
                    onPersonBack()
                }) { Text("Hide") }
            },
            dismissButton = { TextButton({ hiding = null }) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.background,
            iconContentColor = MaterialTheme.colorScheme.primary,
        )
    }
    hidingAll?.let { list ->
        val pets = list.count { it.pet }
        val people = list.size - pets
        val what = listOfNotNull(
            people.takeIf { it > 0 }?.let { "$it ${if (it == 1) "person" else "people"}" },
            pets.takeIf { it > 0 }?.let { "$it pet${if (it == 1) "" else "s"}" },
        ).joinToString(" and ")
        AlertDialog(
            onDismissRequest = { hidingAll = null },
            icon = { Icon(painterResource(R.drawable.ic_block), null) },
            title = { Text("Hide $what?") },
            text = { Text("They leave People & pets, Library and search. Show them again from Settings, Hidden people & pets.") },
            confirmButton = {
                TextButton({
                    hidingAll = null
                    selected = null
                    scope.launch {
                        withContext(Dispatchers.IO + NonCancellable) { db.withTransaction { list.forEach { dao.setHidden(it.id, true) } } }
                        reload++
                    }
                }) { Text("Hide") }
            },
            dismissButton = { TextButton({ hidingAll = null }) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.background,
            iconContentColor = MaterialTheme.colorScheme.primary,
        )
    }
    unmerging?.let { p ->
        UnmergeSheet(p, mergedParts(p, merges), unmergeBusy, onDismiss = { unmerging = null }) { faces ->
            // a full regroup takes a few seconds: the sheet stays open until it is saved
            unmergeBusy = true
            scope.launch {
                withContext(Dispatchers.IO + NonCancellable) { unmergePeople(db, p, faces) }
                unmergeBusy = false
                unmerging = null
            }
        }
    }
    naming?.let { p ->
        NamePersonSheet(
            person = p,
            onDismiss = { naming = null },
        ) { name ->
            naming = null
            scope.launch {
                withContext(Dispatchers.IO + NonCancellable) { dao.setName(p.id, name.trim().ifEmpty { null }) }
                reload++
            }
        }
    }
}

/** People list, or one person, per docs/design.html. Split from the load so previews can pass fake data. */
@Composable
fun PeopleContent(
    data: PeopleData?,
    openId: Long? = null,
    onBack: () -> Unit = {},
    onOpenPerson: (Long) -> Unit = {},
    onClosePerson: () -> Unit = {},
    onName: (Person) -> Unit = {},
    onOpen: (List<Tile>, Tile) -> Unit = { _, _ -> },
    onHelpRecognize: (Long?) -> Unit = {},
    hiddenOnly: Boolean = false,
    pick: Pick = Pick(null),
    onMerge: (Person) -> Unit = {},
    onCover: (Person) -> Unit = {},
    onHide: (Person) -> Unit = {},
    onReorder: (List<Long>) -> Unit = {}, // the named people's ids in their new order, after a drag
    selected: List<Long>? = null, // select mode: the picked ids in pick order (Merge keeps the first name), null = not selecting
    onSelect: (List<Long>?) -> Unit = {},
    onMergeAll: (List<Person>) -> Unit = {},
    onHideAll: (List<Person>) -> Unit = {},
    merges: List<MergeRow> = emptyList(), // a person with parts merged in gets Unmerge in More
    onUnmerge: (Person) -> Unit = {},
) {
    // hoisted: switching to a person and back must not lose the list scroll
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    // people a regroup adds fade in; the first load shows at once
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(data != null) { if (data != null) loaded = true }
    val fadeIn = if (loaded) spring<Float>(stiffness = Spring.StiffnessMediumLow) else null
    // A person slides in from the right and Back slides them out, like a screen push in MainActivity.
    // Keyed by id, not the person, so a direct open waits for the data instead of showing the list first.
    AnimatedContent(openId, transitionSpec = {
        val dir = if (targetState != null) 1 else -1
        slideInHorizontally(tween(250)) { it * dir } togetherWith slideOutHorizontally(tween(250)) { -it * dir }
    }, label = "person") { id ->
        val open = data?.people?.firstOrNull { it.id == id }
        if (id != null) {
            // All until you leave the screen
            var filter by remember(id) { mutableStateOf(Filter.All) }
            // the person screen pads itself, so its More menu can cover the whole screen, like Album's
            if (open != null) PersonDetail(
                open, onBack = onClosePerson, onName = { onName(open) }, onOpen = onOpen, onReview = { onHelpRecognize(open.id) },
                onMerge = { onMerge(open) }, onCover = { onCover(open) }, onHide = { onHide(open) }, pick = pick,
                onUnmerge = if (mergedParts(open, merges).isEmpty()) null else { { onUnmerge(open) } },
                filter = filter, onFilter = { filter = it },
            )
            // a zero size would clip the slide
            else Box(Modifier.fillMaxSize())
        }
        // People come in rows of 4, the mockup's grid: 16 dp sides like the parts above it, 6 dp gaps
        else Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (hiddenOnly) {
                val hidden = data?.people.orEmpty().filter { it.hidden }
                TopBar("Hidden people & pets", data?.let { if (hidden.isEmpty()) "None" else "${hidden.size} hidden" }, onBack = onBack)
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (hidden.isNotEmpty()) item {
                        Note(R.drawable.ic_info, "Not in People & pets, Library or search. Tap one, then Show person or Show pet", Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
                    }
                    items(hidden.chunked(4), key = { it.first().id }) { row ->
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { PersonCard(it, Modifier.weight(1f), tight = true) { onOpenPerson(it.id) } }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (data != null && hidden.isEmpty()) item {
                        EmptyState(R.drawable.ic_eye, "Nothing hidden", "Hide a person or a pet from their screen, they show up here")
                    }
                }
            } else {
                val shown = data?.people.orEmpty().filter { !it.hidden }
                val sel = selected
                val picked = sel.orEmpty().mapNotNull { id -> shown.firstOrNull { it.id == id } }
                val toggle = { id: Long -> sel?.let { onSelect(if (id in it) it - id else it + id) } }
                // the bar fades in place at the same height, so the grid never moves
                Crossfade(sel != null, Modifier.fillMaxWidth(), animationSpec = tween(150), label = "topBar") { on ->
                    if (on) {
                        val pets = picked.count { it.pet }
                        val people = picked.size - pets
                        val kinds = listOfNotNull(
                            people.takeIf { it > 0 }?.let { "$it ${if (it == 1) "person" else "people"}" },
                            pets.takeIf { it > 0 }?.let { "$it pet${if (it == 1) "" else "s"}" },
                        ).joinToString(" · ")
                        TopBar("${picked.size} selected", kinds.ifEmpty { null }, onBack = { onSelect(null) }, backIcon = R.drawable.ic_close, backLabel = "Cancel")
                    } else TopBar("People & pets", data?.summary, onBack = onBack) {
                        IconButton({ onSelect(emptyList()) }) { Icon(painterResource(R.drawable.ic_select), "Select") }
                    }
                }
                // the mockup splits them: named 96 dp 3 a row (name and count), unnamed 56 dp 5 a row (faces only)
                val (named, unnamed) = shown.partition { !it.name.isNullOrBlank() }
                // the order while dragging; the next load starts it over from the saved one
                var order by remember(named) { mutableStateOf(named) }
                val haptic = LocalHapticFeedback.current
                // only the named cards are ReorderableItems, so a drag never lands among the unnamed or on a head
                val reorder = rememberReorderableLazyGridState(gridState) { from, to ->
                    order = order.toMutableList().apply { add(indexOfFirst { "n${it.id}" == to.key }, removeAt(indexOfFirst { "n${it.id}" == from.key })) }
                }
                // saved on drop, here and not in the handle's onDragStopped: the handle keeps its first lambda, which can hold an old [order]
                LaunchedEffect(reorder.isAnyItemDragging) { if (!reorder.isAnyItemDragging && order != named) onReorder(order.map { it.id }) }
                // one grid, not rows, so a card that leaves lets the next ones slide over. 15 columns: a named card takes 5, an unnamed one 3.
                LazyVerticalGrid(
                    GridCells.Fixed(15), Modifier.weight(1f), state = gridState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    data?.let { d ->
                        if (d.unsure.isNotEmpty()) item("review", { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.animateItem(fadeInSpec = fadeIn).overSides()) { ReviewCard(d.unsure, { onHelpRecognize(null) }) }
                        }
                        if (named.isNotEmpty()) {
                            item("namedHead", { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.animateItem(fadeInSpec = fadeIn).overSides()) { SectionHead("Named", hint = peopleAndPets(named)) }
                            }
                            items(order, key = { "n${it.id}" }, span = { GridItemSpan(5) }) {
                                ReorderableItem(reorder, "n${it.id}", animateItemModifier = Modifier.animateItem(fadeInSpec = fadeIn)) { lifted ->
                                    PersonCard(
                                        it,
                                        // fillMaxWidth: ReorderableItem's Box lets the card shrink to its face and sit left in the cell
                                        // no moving while selecting: a tap picks
                                        Modifier.longPressDraggableHandle(enabled = sel == null, onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) })
                                            .fillMaxWidth().padding(vertical = 7.dp).testTag("namedCard"),
                                        faceSize = 96.dp, picked = sel?.contains(it.id) == true, tight = true, lifted = lifted,
                                    ) { if (sel != null) toggle(it.id) else if (!reorder.isAnyItemDragging) onOpenPerson(it.id) } // a lift dropped in place is not a tap
                                }
                            }
                        }
                        if (unnamed.isNotEmpty()) {
                            item("unnamedHead", { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.animateItem(fadeInSpec = fadeIn).overSides()) { SectionHead("No name yet", hint = "Name them to move them up") }
                            }
                            items(unnamed, key = { "u${it.id}" }, span = { GridItemSpan(3) }) {
                                // an unnamed card never moves, so its long press is free to start select mode
                                PersonCard(
                                    it, Modifier.animateItem(fadeInSpec = fadeIn).padding(vertical = 4.dp).testTag("unnamedCard"), faceSize = 56.dp,
                                    picked = sel?.contains(it.id) == true, tight = true, showName = false, showCount = false,
                                    onLongClick = { if (sel == null) onSelect(listOf(it.id)) else toggle(it.id) },
                                ) { if (sel != null) toggle(it.id) else onOpenPerson(it.id) }
                            }
                        }
                        if (shown.isEmpty()) item("empty", { GridItemSpan(maxLineSpan) }) {
                            EmptyState(R.drawable.ic_person, "No people yet", "Index your photos in Library, then faces group into people here.", Modifier.overSides())
                        }
                    }
                }
                // under the grid, so nothing above it moves. A person and a pet never merge (canMerge).
                if (sel != null) ActionBar(
                    "Hide", R.drawable.ic_block, { onHideAll(picked) },
                    "Merge", R.drawable.ic_person_add, { onMergeAll(picked) },
                    danger = false, dangerEnabled = picked.isNotEmpty(),
                    primaryEnabled = picked.size > 1 && picked.all { p -> canMerge(p, picked[0]) },
                )
            }
        }
    }
}
