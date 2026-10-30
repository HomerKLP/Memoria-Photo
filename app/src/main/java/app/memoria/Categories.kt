package app.memoria

import android.content.Context
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A photo tag from `assets/tags.json` (`bench/tags.py export`): a photo has it when dot(its vector, [vector]) > [cutoff]. */
class Tag(val id: String, val name: String, val words: List<String>, val cutoff: Float, val vector: FloatArray) {
    companion object {
        /** No tags when the file was made for another tagger model. */
        fun parse(json: String): List<Tag> {
            val o = JSONObject(json)
            if (o.getInt("model") != Tagger.VERSION) return emptyList()
            val tags = o.getJSONArray("tags")
            return List(tags.length()) { i ->
                val t = tags.getJSONObject(i)
                val words = t.getJSONArray("words")
                val v = t.getJSONArray("vector")
                Tag(t.getString("id"), t.getString("name"), List(words.length()) { words.getString(it) }, t.getDouble("cutoff").toFloat(), FloatArray(v.length()) { v.getDouble(it).toFloat() })
            }
        }

        @Volatile private var loaded: List<Tag>? = null

        /** Parsed once per process. */
        fun load(context: Context) = loaded ?: synchronized(this) {
            loaded ?: parse(context.assets.open("tags.json").bufferedReader().readText()).also { loaded = it }
        }
    }
}

/** A tag and its photos, newest first. [cover] is the photo the tagger is surest of. */
class Category(val id: String, val name: String, val words: List<String>, val cover: Tile, val tiles: List<Tile>)

/**
 * Tags that have photos, most photos first. [tiles] drops photos gone from the phone. Photos only: videos have no vector.
 * [pets]: photos with a cat or dog box and its score, while the object models are here; then they make the pet tag (`docs/objects.md`).
 */
fun categories(tags: List<Tag>, vectors: List<PhotoVectorRow>, tiles: List<Tile>, pets: Map<Long, Float>? = null): List<Category> {
    val byId = tiles.associateBy { it.id }
    val shown = vectors.mapNotNull { v -> byId[v.photoId]?.let { it to v.vector } }
    return tags.mapNotNull { tag ->
        val hits = if (tag.id == "pet" && pets != null) pets.mapNotNull { (id, score) -> byId[id]?.let { it to score } }
            else shown.map { (t, v) -> t to dot(v, tag.vector) }.filter { it.second > tag.cutoff }
        if (hits.isEmpty()) null
        else Category(tag.id, tag.name, tag.words, hits.maxBy { it.second }.first, hits.map { it.first }.sortedByDescending { it.date })
    }.sortedByDescending { it.tiles.size }
}

private fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}

/** Reads every photo vector (3 KB each), only when there are tags to score. */
suspend fun loadCategories(context: Context, tiles: List<Tile>): List<Category> = withContext(Dispatchers.IO) {
    val tags = Tag.load(context)
    // test phone, debug build, 4383 photos: read ~50 ms, score ~55 ms (first open ~280 ms), so no cache
    val dao = Db.get(context).index()
    if (tags.isEmpty()) emptyList() else categories(tags, dao.vectors(Tagger.VERSION), tiles, if (objectsFiles(context).all { it.exists() }) dao.petPhotos() else null)
}

private suspend fun loadCategories(context: Context) = withContext(Dispatchers.IO) { loadCategories(context, albums(context).flatMap { it.tiles }) }

/** Library's Categories row: the 3 with the most photos. null (loading) or none yet: grey tiles hold the space, like [PlaceRow]. */
@Composable
fun CategoryRow(categories: List<Category>?, onOpen: (String) -> Unit) {
    val shown: List<Category?> = categories?.take(3)?.takeIf { it.isNotEmpty() } ?: List(3) { null }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { c ->
            if (c == null) CoverTile(null, "", "", Modifier.weight(1f))
            else CoverTile(c.cover, c.name, "${c.tiles.size}", Modifier.weight(1f).clickable { onOpen(c.id) })
        }
        repeat(3 - shown.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** Every category with photos, most photos first. Layout per docs/design.html "Categories", like Places. */
@Composable
fun CategoriesScreen(onBack: () -> Unit, onOpenCategory: (String) -> Unit) {
    val context = LocalContext.current
    val categories by cachedProduceState("categories") { loadCategories(context) }
    CategoriesContent(categories, onBack, onOpenCategory)
}

@Composable
fun CategoriesContent(categories: List<Category>?, onBack: () -> Unit = {}, onOpenCategory: (String) -> Unit = {}) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopBar("Categories", categories?.let { if (it.size == 1) "1 category" else "${it.size} categories" }, onBack = onBack)
        LazyVerticalGrid(
            GridCells.Fixed(3), Modifier.testTag("categoriesGrid"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(categories.orEmpty(), key = { it.id }) { CoverTile(it.cover, it.name, "${it.tiles.size}", Modifier.animateItem(fadeInSpec = null).clickable { onOpenCategory(it.id) }) }
        }
    }
}

/** One category's photos (mockup "Category"): the Place page, since both are a name and photos by month. */
@Composable
fun CategoryScreen(id: String, onBack: () -> Unit, onOpen: (List<Tile>, Tile) -> Unit) {
    val context = LocalContext.current
    val pick = rememberPick()
    val category by cachedProduceState("category$id", pick.changes, isEmpty = { it.tiles.isEmpty() }) {
        loadCategories(context).firstOrNull { it.id == id }.let { Place(0, it?.name.orEmpty(), it?.tiles.orEmpty()) }
    }
    // the last photo was deleted, or the tags are gone: close instead of a blank page
    CloseWhenEmpty(category, category?.tiles?.isEmpty() == true, onBack)
    PlaceContent(category, onBack, onOpen, pick)
}
