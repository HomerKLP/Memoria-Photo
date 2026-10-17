package app.memoria

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.cos

/** GeoNames towns of 1000+ people, city districts left out (decisions.md). `assets/towns.tsv` comes from `bench/places.py towns`. */
class Towns(val id: IntArray, val name: Array<String>, val lat: FloatArray, val lon: FloatArray) {
    /** Index of the nearest town. */
    // ponytail: linear scan of ~140k towns, ~1 ms a photo; a lat/lon grid if it ever shows in the index time
    fun nearest(la: Double, lo: Double): Int {
        val k = cos(Math.toRadians(la)) // flat-earth distance: fine at town distances, and only the order matters
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in id.indices) {
            val dy = lat[i] - la
            val dx = abs(lon[i] - lo).let { if (it > 180) 360 - it else it } * k
            val d = dy * dy + dx * dx
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    companion object {
        fun parse(lines: List<String>): Towns {
            val rows = lines.filter { it.isNotEmpty() }.map { it.split('\t') }
            return Towns(
                IntArray(rows.size) { rows[it][0].toInt() }, Array(rows.size) { rows[it][1] },
                FloatArray(rows.size) { rows[it][2].toFloat() }, FloatArray(rows.size) { rows[it][3].toFloat() },
            )
        }

        fun load(context: Context) = parse(context.assets.open("towns.tsv").bufferedReader().readLines())
    }
}

/** Photo GPS from the original file (MediaStore hides it since Android 10), null if it has none. Blocking. */
fun photoGps(context: Context, uri: Uri): Pair<Double, Double>? {
    val exif = runCatching { context.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri))!!.use { ExifInterface(it) } }.getOrNull() ?: return null
    val ll = FloatArray(2)
    if (!exif.getLatLong(ll) || ll[0] == 0f && ll[1] == 0f) return null // some cameras write 0,0 when they have no GPS
    return ll[0].toDouble() to ll[1].toDouble()
}

/** A town and its photos, newest first. */
class Place(val id: Int, val name: String, val tiles: List<Tile>)

/** Towns of the indexed photos, most photos first. Photos only: videos have no town yet. [tiles] drops photos gone from the phone. */
fun places(rows: List<PhotoRow>, tiles: List<Tile>): List<Place> {
    val byId = tiles.associateBy { it.id }
    return rows.groupBy { it.townId!! }
        .mapNotNull { (id, r) -> r.mapNotNull { byId[it.id] }.takeIf { it.isNotEmpty() }?.let { t -> Place(id, r[0].town!!, t.sortedByDescending { it.date }) } }
        .sortedByDescending { it.tiles.size }
}

private suspend fun loadPlaces(context: Context) =
    withContext(Dispatchers.IO) { places(Db.get(context).index().placed(), albums(context).flatMap { it.tiles }) }

/** Library's Places row: the 3 biggest towns. null (loading) or no places yet: grey tiles hold the space. */
@Composable
fun PlaceRow(places: List<Place>?, onOpen: (Int) -> Unit) {
    val shown: List<Place?> = places?.take(3)?.takeIf { it.isNotEmpty() } ?: List(3) { null }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { p ->
            if (p == null) CoverTile(null, "", "", Modifier.weight(1f))
            else CoverTile(p.tiles.first(), p.name, "${p.tiles.size}", Modifier.weight(1f).clickable { onOpen(p.id) })
        }
        repeat(3 - shown.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** Every town, most photos first. Layout per docs/design.html "Places". */
@Composable
fun PlacesScreen(onBack: () -> Unit, onOpenPlace: (Int) -> Unit) {
    val context = LocalContext.current
    val places by cachedProduceState("places") { loadPlaces(context) }
    PlacesContent(places, onBack, onOpenPlace)
}

@Composable
fun PlacesContent(places: List<Place>?, onBack: () -> Unit = {}, onOpenPlace: (Int) -> Unit = {}) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopBar("Places", places?.let { p -> "${p.size} places · ${p.sumOf { it.tiles.size }} photos" }, onBack = onBack)
        LazyVerticalGrid(
            GridCells.Fixed(3), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(places.orEmpty(), key = { it.id }) { CoverTile(it.tiles.first(), it.name, "${it.tiles.size}", Modifier.clickable { onOpenPlace(it.id) }) }
        }
    }
}

/** One town's photos by month, the month pinned on top, like Videos. Tap opens the viewer. */
@Composable
fun PlaceScreen(id: Int, onBack: () -> Unit, onOpen: (List<Tile>, Tile) -> Unit) {
    val context = LocalContext.current
    val place by cachedProduceState("place$id") { loadPlaces(context).firstOrNull { it.id == id } ?: Place(id, "", emptyList()) }
    val p = place
    val month = DateTimeFormatter.ofPattern("LLLL yyyy")
    val zone = ZoneId.systemDefault()
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        // " " while loading: an empty title may measure to nothing and the grid would jump
        TopBar(p?.name?.ifEmpty { " " } ?: " ", p?.let { "${it.tiles.size} photos" }, onBack = onBack)
        LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            p?.tiles?.groupBy { month.format(Instant.ofEpochMilli(it.date).atZone(zone)) }?.forEach { (m, tiles) ->
                stickyHeader(key = m) {
                    // background: the pinned month covers the tiles passing under it
                    Text(
                        m, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 16.dp, top = 20.dp, bottom = 10.dp),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                items(tiles, key = { it.id }) { TileImage(it, Modifier.clickable { onOpen(p.tiles, it) }) }
            }
        }
    }
}
