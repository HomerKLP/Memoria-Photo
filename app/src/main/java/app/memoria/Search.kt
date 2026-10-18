package app.memoria

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Something Search opens: a person, place, album, year or month, or a type chip. [key] says which one. */
class Hit(val kind: String, val key: String, val name: String, val note: String = "", val face: FaceVec? = null, val cover: Tile? = null)

/** Named people for the row, everything typing can find (people, places, albums, years and months, in that order), the type chips. */
class SearchData(val people: List<Person>, val hits: List<Hit>, val types: List<Hit>)

/** Where [query] starts a word of [name], case ignored ("d" finds Downloads, not Andy), or -1. */
fun wordStart(query: String, name: String): Int {
    val q = query.trim()
    if (q.isEmpty()) return -1
    return name.indices.firstOrNull { i -> (i == 0 || !name[i - 1].isLetterOrDigit()) && name.regionMatches(i, q, 0, q.length, ignoreCase = true) } ?: -1
}

private fun count(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

@Composable
fun SearchScreen(onBack: () -> Unit, onOpen: (Hit) -> Unit, onSeeAllPeople: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { Db.get(context).index() }
    val prefs = remember { context.getSharedPreferences("search", Context.MODE_PRIVATE) }
    // what you opened, newest first, as "kind key" lines; the names come from fresh data, so a rename shows
    var recent by remember { mutableStateOf(prefs.getString("recent", "").orEmpty().lines().filter { it.isNotBlank() }) }
    val saveRecent = { lines: List<String> -> recent = lines; prefs.edit { putString("recent", lines.joinToString("\n")) } }
    var query by rememberSaveable { mutableStateOf("") }
    val data by cachedProduceState("search") {
        withContext(Dispatchers.IO) {
            val faces = dao.faceVecs()
            val byId = faces.associateBy { it.id }
            val byPerson = faces.filter { it.personId != null }.groupBy { it.personId!! }
            val people = dao.people().filter { !it.hidden && !it.name.isNullOrBlank() }
                .mapNotNull { p -> byId[p.mainFaceId]?.let { m -> Person(byPerson[p.id] ?: listOf(m), m, p.id, p.name, false, p.coverFaceId) } }
                .sortedByDescending { it.photos }
            val albums = albums(context)
            val all = albums.flatMap { it.tiles }
            // same month names and keys as the Photos tab headers, so a month opens the tab right there
            val zone = ZoneId.systemDefault()
            val monthName = DateTimeFormatter.ofPattern("LLLL yyyy")
            val months = all.groupBy { YearMonth.from(Instant.ofEpochMilli(it.date).atZone(zone)) }.toSortedMap(reverseOrder())
            val dates = months.entries.groupBy { it.key.year }.flatMap { (year, ms) ->
                // a year opens the tab at its newest month
                listOf(Hit("year", "m${ms.first().key}", "$year", count(ms.sumOf { it.value.size }, "item"))) +
                    ms.map { (m, tiles) -> Hit("month", "m$m", monthName.format(m), count(tiles.size, "item")) }
            }
            SearchData(
                people = people,
                hits = people.map { Hit("person", "${it.id}", it.name!!, count(it.photos, "photo"), face = it.cover) } +
                    places(dao.placed(), all).map { Hit("place", "${it.id}", it.name, count(it.tiles.size, "photo"), cover = it.tiles.firstOrNull()) } +
                    albums.map { Hit("album", "${it.id}", it.name, count(it.tiles.size, "item"), cover = it.tiles.firstOrNull()) } +
                    dates,
                types = listOfNotNull(
                    Hit("videos", "videos", "Videos"),
                    Hit("favorites", "favorites", "Favorites"),
                    // no screenshots screen yet: the chip opens the phone's Screenshots album, if there is one
                    albums.firstOrNull { it.name.equals("Screenshots", ignoreCase = true) }?.let { Hit("album", "${it.id}", it.name) },
                ),
            )
        }
    }
    val line = { h: Hit -> "${h.kind} ${h.key}" }
    val all = data?.let { it.hits + it.types }.orEmpty()
    SearchContent(
        data = data,
        query = query,
        recent = recent.mapNotNull { r -> all.firstOrNull { line(it) == r } },
        onQuery = { query = it },
        onBack = onBack,
        onOpen = { h -> saveRecent((listOf(line(h)) + recent.filter { it != line(h) }).take(10)); onOpen(h) },
        onRemoveRecent = { h -> saveRecent(recent.filter { it != line(h) }) },
        onClearRecent = { saveRecent(emptyList()) },
        onSeeAllPeople = onSeeAllPeople,
    )
}

/** Search (mockup "Search", "Search, typing"): the field is the top bar; named people, types and recent, or the hits for what you type. */
@Composable
fun SearchContent(
    data: SearchData?,
    query: String,
    recent: List<Hit> = emptyList(),
    onQuery: (String) -> Unit = {},
    onBack: () -> Unit = {},
    onOpen: (Hit) -> Unit = {},
    onRemoveRecent: (Hit) -> Unit = {},
    onClearRecent: () -> Unit = {},
    onSeeAllPeople: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onBack) { Icon(painterResource(R.drawable.ic_back), "Back") }
            Row(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.padding(start = 12.dp).weight(1f)) {
                    if (query.isEmpty()) Text("Search people, places, dates", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    BasicTextField(
                        query, onQuery, Modifier.fillMaxWidth().testTag("searchField"), singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    )
                }
                // inside the 48 dp field, so it comes and goes without changing the bar's height
                if (query.isNotEmpty()) IconButton({ onQuery("") }) {
                    Icon(painterResource(R.drawable.ic_close), "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // nothing until loaded: all parts below the field show at once, so none of them moves
        if (data == null) return@Column
        val q = query.trim()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (q.isNotEmpty()) {
                val found = data.hits.mapNotNull { h -> wordStart(q, h.name).takeIf { it >= 0 }?.let { h to it } }
                if (found.isEmpty()) item {
                    Text("Nothing for \"$q\"", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(found, key = { (h, _) -> "${h.kind} ${h.key}" }) { (h, at) -> HitRow(h, at, q.length) { onOpen(h) } }
                return@LazyColumn
            }
            if (data.people.isNotEmpty()) {
                item { SectionHead("People") }
                item { PeopleRow(data.people, { id -> data.hits.firstOrNull { it.kind == "person" && it.key == "$id" }?.let(onOpen) }, onSeeAllPeople) }
            }
            item { SectionHead("Types") }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    data.types.forEach { t ->
                        Chip(t.name, when (t.kind) { "videos" -> R.drawable.ic_video; "favorites" -> R.drawable.ic_favorite; else -> R.drawable.ic_photo }) { onOpen(t) }
                    }
                }
            }
            if (recent.isNotEmpty()) {
                item { SectionHead("Recent", "Clear", onClearRecent, chevron = false) }
                items(recent, key = { "r ${it.kind} ${it.key}" }) { h ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(h) }.heightIn(min = 52.dp).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_history), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(h.name, Modifier.weight(1f).padding(start = 16.dp), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton({ onRemoveRecent(h) }) { Icon(painterResource(R.drawable.ic_close), "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

/** One hit: a round face for a person, a square cover for a place or album, a square calendar for a date; the typed letters in the accent. */
@Composable
private fun HitRow(hit: Hit, at: Int, length: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val lead = Modifier.size(40.dp)
        when {
            hit.face != null -> FaceImage(hit.face, lead.clip(CircleShape))
            hit.cover != null -> Thumb(hit.cover.copy(duration = null), lead.clip(RoundedCornerShape(10.dp))) // no video badge on a cover
            else -> Box(lead.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_calendar), null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            buildAnnotatedString { append(hit.name); addStyle(SpanStyle(MaterialTheme.colorScheme.primary), at, at + length) },
            Modifier.weight(1f).padding(start = 16.dp), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(hit.note, Modifier.padding(start = 12.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
