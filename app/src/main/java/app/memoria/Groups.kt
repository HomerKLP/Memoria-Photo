package app.memoria

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}

internal fun mean(faces: List<FaceVec>) = FloatArray(512).also { m -> faces.forEach { f -> f.vector.forEachIndexed { k, v -> m[k] += v } } }

/** Average linkage on cosine distance (normalized vectors), groups whose distance is at most [cut], like scipy in the bench. Indices into [vectors]. */
fun averageLinkage(vectors: List<FloatArray>, cut: Float): List<List<Int>> {
    val n = vectors.size
    val d = Array(n) { FloatArray(n) }
    for (i in 0 until n) for (j in i + 1 until n) { d[i][j] = 1 - dot(vectors[i], vectors[j]); d[j][i] = d[i][j] }
    val members = Array(n) { mutableListOf(it) }
    val alive = BooleanArray(n) { true } // not merged into another group
    val open = BooleanArray(n) { true } // may still merge
    // Nearest-neighbour chain: same groups as merging the closest pair each step (scipy, bench), O(n²) not O(n³)
    val chain = IntArray(n); var top = 0; var next = 0
    while (true) {
        if (top == 0) {
            while (next < n && !open[next]) next++
            if (next == n) break
            chain[top++] = next
        }
        val x = chain[top - 1]
        // on a tie keep the group below in the chain, or the chain can loop
        var y = if (top > 1) chain[top - 2] else -1
        var best = if (y >= 0) d[x][y] else Float.MAX_VALUE
        for (k in 0 until n) if (open[k] && k != x && d[x][k] < best) { best = d[x][k]; y = k }
        // nothing within the cut: merging others never brings a group closer (an average is never below its smallest part)
        if (best > cut) { open[x] = false; top--; continue }
        if (top > 1 && y == chain[top - 2]) {
            top -= 2
            val a = min(x, y); val b = max(x, y)
            val na = members[a].size; val nb = members[b].size
            for (k in 0 until n) { d[a][k] = (na * d[a][k] + nb * d[b][k]) / (na + nb); d[k][a] = d[a][k] }
            members[a].addAll(members[b])
            alive[b] = false; open[b] = false
        } else chain[top++] = y
    }
    return (0 until n).filter { alive[it] }.map { members[it] }
}

/**
 * Like bench.py group: average linkage on cosine distance, cut at 0.68, faces from 40 px.
 * Only camera faces with quality 0.4+ start a person (lower ones were cats, masks, backs of heads),
 * other faces join the closest person under the cut. Joined faces under 0.4 are unsure: they wait for the user's yes / no.
 * [answers]: a yes keeps the face with that person and lets faces within 0.5 of it join too, a no rules that person out.
 * [merges]: the groups that hold both main faces of a Merge with become one.
 */
fun groupFaces(all: List<FaceVec>, answers: List<AnswerRow> = emptyList(), merges: List<MergeRow> = emptyList(), cut: Float = 0.68f): List<Person> {
    // a face the user fixed by hand follows the answers: it never starts a person (a moved face would go back), and small ones stay
    val answered = answers.map { it.faceId }.toSet()
    val (seeds, others) = all.filter { min(it.box[2] - it.box[0], it.box[3] - it.box[1]) >= 40 || it.id in answered }
        .partition { it.kind == PhotoKind.camera && it.quality >= 0.4f && it.id !in answered }
    // A person needs faces from 6+ photos: fewer is often statues, posters or strangers seen once
    val auto = averageLinkage(seeds.map { it.vector }, cut).map { g -> g.map { seeds[it] } }.filter { it.distinctBy { f -> f.photoId }.size > 5 }
    // "Who is this?" > New person: a yes to the face's own id. That person grows only by the user's yes, never by distance.
    // A video face never starts one that way either: it is never shown, so no answer can point at it.
    val selfYes = answers.filter { it.yes && it.faceId == it.personFace }.map { it.faceId }.toSet()
    val own = others.filter { it.kind != PhotoKind.video && it.id in selfYes }.map { it.id }.toSet()
    // second: takes faces by distance. A New person merged into a grouped person does too.
    val parts = (auto.map { it to true } + others.filter { it.id in own }.map { listOf(it) to false }).toMutableList()
    for (m in merges) {
        val a = parts.indexOfFirst { (g, _) -> g.any { it.id == m.personFace } }
        val b = parts.indexOfFirst { (g, _) -> g.any { it.id == m.face } }
        if (a < 0 || b < 0 || a == b) continue
        parts[a] = (parts[a].first + parts[b].first) to (parts[a].second || parts[b].second)
        parts.removeAt(b)
    }
    val groups = parts.map { it.first }
    val joined = groups.map { mutableListOf<FaceVec>() }
    val ids = groups.map { g -> g.map { it.id }.toSet() }
    val yes = answers.filter { it.yes }.associate { it.faceId to it.personFace }
    val no = answers.filter { !it.yes }.groupBy({ it.faceId }, { it.personFace })
    // average distance to a group's faces = distance to the sum of its vectors / size: one dot per group, not per face
    val sums = groups.map { mean(it) }
    // A face close to a face the user said Yes to joins that person, even when the person's average is too far (side views, old photos).
    // Only the user's Yes faces count (quality 0.3+), never faces that joined this way. The person's own face set stays the sharp starting faces.
    val refs = others.filter { it.quality >= 0.3f }.mapNotNull { r ->
        val i = yes[r.id]?.let { p -> ids.indexOfFirst { p in it } } ?: -1
        if (i >= 0 && parts[i].second) i to r.vector else null
    }
    for (f in others) {
        if (f.id in own) continue
        val kept = yes[f.id]?.let { p -> ids.indexOfFirst { p in it } } ?: -1
        if (kept >= 0) { joined[kept].add(f); continue }
        val ruledOut = no[f.id].orEmpty()
        val near = refs.filter { (i, _) -> ruledOut.none { it in ids[i] } }.map { (i, v) -> i to 1.0 - dot(v, f.vector) }.filter { it.second <= 0.5 }.minByOrNull { it.second }
        if (near != null) { joined[near.first].add(f); continue }
        val (i, dist) = groups.indices.filter { i -> parts[i].second && ruledOut.none { it in ids[i] } }
            .map { i -> i to 1.0 - dot(sums[i], f.vector) / groups[i].size }.minByOrNull { it.second } ?: continue
        // A video face joins like a face that can't start a person, on the same average rule, but a blurry track (under 0.4) needs 0.62, not 0.68
        if (dist <= if (f.kind == PhotoKind.video && f.quality < 0.4f) 0.62f else cut) joined[i].add(f)
    }
    // A joined face the quality line leaves unsure is still sure when the person's sharp or Yes faces vouch for it: the closest one
    // (within 0.5) is this person's and no other person's is 0.1 closer, or a Yes face of this person is within 0.3.
    // Faces made sure here are never anchors, so this cannot chain.
    val anchors = groups.indices.flatMap { i ->
        // a video face only joins, it never vouches for another face
        (groups[i] + joined[i]).filter { it.kind != PhotoKind.video && ((it.quality >= 0.4f && !it.rejected) || (it.id in yes && it.quality >= 0.3f)) }.map { i to it }
    }
    val vouched = groups.indices.map { i ->
        joined[i].map { f ->
            val v = !(f.quality >= 0.4f || f.id in yes || f.rejected) && run {
                var own = 2f; var other = 2f; var twin = 2f
                for ((j, a) in anchors) {
                    val d = f.distanceTo(a)
                    if (j != i) other = min(other, d) else { own = min(own, d); if (a.id in yes) twin = min(twin, d) }
                }
                twin <= 0.3f || (own <= 0.5f && other >= own + 0.1f)
            }
            if (v == f.nearSure) f else f.withNearSure(v)
        }
    }
    return groups.indices.map { i ->
        Person(groups[i] + vouched[i], groups[i].maxBy { it.quality })
    }.sortedByDescending { it.faces.size }
}

/**
 * Pets like people (`docs/objects.md`): average linkage on the pet vectors, cut 0.68 (better left out than in another cat's group), and a pet needs pictures from 6+ photos.
 * Then pets under 0.76 apart join, closest first: one cat's split groups. Loose pictures stay out, so they can't pull two cats together.
 * [merges] join two pets as for people. [answers] come from the viewer's Who is this? (pets have no Help recognize):
 * a picture the user fixed follows them, never the distances. A yes puts it with that pet, a no alone keeps it out of every pet.
 */
fun groupPets(all: List<FaceVec>, answers: List<AnswerRow> = emptyList(), merges: List<MergeRow> = emptyList()): List<Person> {
    // older, shorter vectors (DINOv2 alone) wait until the catch-up pass redoes their photo
    val size = all.maxOfOrNull { it.vector.size }
    val answered = answers.map { it.faceId }.toSet()
    val (fixed, pictures) = all.filter { it.vector.size == size }.partition { it.id in answered }
    // A video pet only joins a pet built from pictures: it stays out of the linkage, so it never starts a pet nor pulls two together
    val (videoPets, pets) = pictures.partition { it.kind == PhotoKind.video }
    val yes = answers.filter { it.yes }.associate { it.faceId to it.personFace }
    // "Who is this?" > New pet: a yes to the picture's own id. A pet of its own however few photos, grown only by the user's yes.
    // A video pet never starts one that way either: it never shows, so no answer can point at it.
    val own = fixed.filter { it.kind != PhotoKind.video && yes[it.id] == it.id }.map { it.id }.toSet()
    val groups = (averageLinkage(pets.map { it.vector }, 0.68f).map { g -> g.map { pets[it] } } + fixed.filter { it.id in own }.map { listOf(it) }).toMutableList()
    for (m in merges) {
        val a = groups.indexOfFirst { g -> g.any { it.id == m.personFace } }
        val b = groups.indexOfFirst { g -> g.any { it.id == m.face } }
        if (a < 0 || b < 0 || a == b) continue
        groups[a] = groups[a] + groups[b]
        groups.removeAt(b)
    }
    // a New pet never joins another by distance: the user said it is a different one
    val (made, auto) = groups.partition { g -> g.any { it.id in own } }
    val shown = auto.filter { g -> g.distinctBy { it.photoId }.size > 5 }.toMutableList()
    while (true) {
        val sums = shown.map { mean(it) }
        var best = 0.76f; var a = -1; var b = -1
        for (i in shown.indices) for (j in i + 1 until shown.size) {
            val d = 1 - dot(sums[i], sums[j]) / (shown[i].size * shown[j].size) // average distance of every pair
            if (d < best) { best = d; a = i; b = j }
        }
        if (a < 0) break
        shown[a] = shown[a] + shown[b]
        shown.removeAt(b)
    }
    // the main picture comes before the moved ones, so a pet keeps the main picture its answers point to
    val base = (shown + made).map { g -> g + fixed.filter { f -> f.id !in own && g.any { it.id == yes[f.id] } } }
    // a video pet joins the closest pet whose average distance to its pictures is within 0.68
    val n = base.firstOrNull()?.firstOrNull()?.vector?.size ?: 0
    val sums = base.map { g -> FloatArray(n).also { m -> g.forEach { f -> for (k in f.vector.indices) m[k] += f.vector[k] } } }
    val video = List(base.size) { mutableListOf<FaceVec>() }
    for (v in videoPets) {
        val best = base.indices.map { i -> i to 1f - dot(sums[i], v.vector) / base[i].size }.minByOrNull { it.second } ?: continue
        if (best.second <= 0.68f) video[best.first].add(v)
    }
    return base.indices.map { i -> Person(base[i] + video[i], base[i].maxBy { f -> f.quality }) }.sortedByDescending { it.faces.size }
}

/** A person and a pet never merge (docs/design.html, Merge with). */
fun canMerge(a: Person, b: Person) = a.pet == b.pet

/** Face plus 30% margin as a small WebP, longest side up to 288 px (the 96 dp People face at 3x). Saved while indexing, so the People screen never decodes photos. */
fun faceThumb(image: Bitmap, box: FloatArray): ByteArray {
    val m = max(box[2] - box[0], box[3] - box[1]) * 0.3f
    val l = (box[0] - m).toInt().coerceIn(0, image.width - 1)
    val t = (box[1] - m).toInt().coerceIn(0, image.height - 1)
    val w = ((box[2] + m).toInt().coerceAtMost(image.width) - l).coerceAtLeast(1)
    val h = ((box[3] + m).toInt().coerceAtMost(image.height) - t).coerceAtLeast(1)
    val s = min(1f, 288f / max(w, h))
    val thumb = Bitmap.createScaledBitmap(Bitmap.createBitmap(image, l, t, w, h), max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()), true)
    return ByteArrayOutputStream().also { thumb.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) }.toByteArray()
}
