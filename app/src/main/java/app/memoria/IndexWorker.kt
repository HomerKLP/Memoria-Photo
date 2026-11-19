package app.memoria

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.Images.Media
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A photo read ahead on the helper thread: its kind and the 640 px decode the models start from. A skipped photo has no decode. */
private class Prepared(val kind: PhotoKind, val uri: Uri?, val small: Bitmap?, val width: Int, val height: Int)

/** The slow CPU part of one photo (HEIC decodes in ~130 ms), so the worker can do it for the next photo while the models run on this one. */
private fun prepare(context: Context, photo: MediaPhoto): Prepared {
    val uri = ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, photo.id)
    val kind = kindOf(context, photo, uri)
    if (kind == PhotoKind.skip) return Prepared(kind, null, null, 0, 0)
    var w = 0
    var h = 0
    val small = loadPhoto(context.contentResolver, uri, 640) { pw, ph -> w = pw; h = ph }
    return Prepared(kind, uri, small, w, h)
}

/** A face or a pet found in one video frame: it joins a track by its [vector] and keeps the best frame's [box], score, [quality] and thumb. */
private class Tracked(val t: Long, val box: FloatArray, val score: Float, val quality: Float, val vector: FloatArray, val thumb: ByteArray)

/** A frame size for `getScaledFrameAtTime`: the long side 640, the other side scaled to match. */
private fun frameSize(w: Int, h: Int): Pair<Int, Int> = when {
    w <= 0 || h <= 0 -> 640 to 640
    w >= h -> 640 to max(1, (640f * h / w).roundToInt())
    else -> max(1, (640f * w / h).roundToInt()) to 640
}

/** The mean of [vs], normalized: the top-3 track vector (bench/video.py `tracks`). */
private fun averageVector(vs: List<FloatArray>): FloatArray {
    val s = FloatArray(vs[0].size)
    for (v in vs) for (i in v.indices) s[i] += v[i]
    val len = sqrt(s.sumOf { (it * it).toDouble() }).toFloat()
    return if (len == 0f) s else FloatArray(s.size) { s[it] / len }
}

/** Finds faces in new photos and saves them. Photos already in the DB are skipped, so it can stop and resume. */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dao = Db.get(applicationContext).index()
        val testOnly = inputData.getBoolean("testOnly", false)
        // a video is indexed like a photo from the start (kind video), but with no tagger and no catch-up pass; the test folder has no videos
        val all = if (testOnly) testPhotos(applicationContext) else libraryPhotos(applicationContext) + libraryVideos(applicationContext)
        val total = all.size
        val done = dao.photoIds().toHashSet()
        val tagged = dao.taggedIds(Tagger.VERSION).toHashSet()
        val boxed = dao.boxedIds(ObjectDetector.VERSION).toHashSet()
        // one-time: faces found on a HEIC whose mirror the old decode dropped sit on a mirrored picture, so those photos are scanned again
        val prefs = applicationContext.getSharedPreferences("index", Context.MODE_PRIVATE)
        if (!testOnly && !prefs.getBoolean("mirrorFixed", false) && !prefs.contains("mirrorPending")) {
            val drops = decoderDropsMirror(applicationContext)
            val ids = if (!drops) emptyList() else all.filter {
                !it.video && it.id in done && (it.mime == "image/heic" || it.mime == "image/heif") &&
                    heicMirrorAxis(applicationContext.contentResolver, ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, it.id)) >= 0
            }.map { it.id }
            prefs.edit().putStringSet("mirrorPending", ids.map { "$it" }.toSet()).apply()
            AppLog.i("mirror fix: decoder drops the mirror $drops, ${ids.size} photos to scan again")
        }
        // a Find faces again or a deleted photo drops its id, and then nothing is left to fix
        val pending = if (testOnly) mutableSetOf() else
            prefs.getStringSet("mirrorPending", null).orEmpty().map { it.toLong() }.filter { it in done }.toMutableSet()
        fun savePending() {
            if (testOnly) return
            val edit = prefs.edit()
            if (pending.isEmpty()) edit.putBoolean("mirrorFixed", true).remove("mirrorPending")
            else edit.putStringSet("mirrorPending", pending.map { "$it" }.toSet())
            edit.apply()
        }
        if (pending.isEmpty()) savePending()
        val refresh = if (pending.isEmpty()) emptyList() else all.filter { it.id in pending }
        val tagging = taggerFile(applicationContext).exists()
        val boxing = objectsFiles(applicationContext).all { it.exists() }
        val plan = indexPlan(all, done, tagged, boxed, tagging, boxing)
        // photos deleted from the phone: forget them, their faces, vectors and boxes, so counts and people match the phone
        if (!testOnly) plan.gone.chunked(500).forEach { dao.deleteFaces(it); dao.deletePhotos(it); dao.deleteVectors(it); dao.deleteTags(it); dao.deleteBoxes(it) }
        if (isStopped) return Result.success()
        val todo = plan.todo
        val newPhotos = todo.filter { !it.video } + refresh // the refresh photos are in [done], so not in [todo]
        val newVideos = todo.filter { it.video }
        val catchUp = plan.catchUp.filter { it.id !in pending } // photos indexed before the tagger or the object models, or by older ones
        if (todo.isEmpty() && catchUp.isEmpty() && refresh.isEmpty()) {
            IndexProgress.finish(applicationContext)
            placePhotos()
            return savePeople() // nothing new, but the grouping code may have changed
        }
        val base = total - todo.size - catchUp.size - refresh.size // photos with every step done, so the bar starts there
        IndexProgress.start(applicationContext, total, base)
        // Foreground, or the system stops the job midway. Not allowed when started from the background (rerun after a stop), then it runs as a plain job.
        val notifications = applicationContext.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("index", "Indexing", NotificationManager.IMPORTANCE_LOW))
        val foreground = try {
            setForeground(ForegroundInfo(1, notice(base, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            true
        } catch (e: IllegalStateException) {
            AppLog.w("index runs as a plain job", e)
            false
        }
        AppLog.i("index run: ${todo.size} to scan, ${catchUp.size} to catch up, ${refresh.size} to fix, ${if (foreground) "foreground" else "plain job"}")
        // the user's faces stay whatever the match says: answered, merged, or a cover
        val keptFaces = if (pending.isEmpty()) emptySet() else (
            dao.answers().flatMap { listOf(it.faceId, it.personFace) } + dao.merges().flatMap { listOf(it.face, it.personFace) } +
                dao.people().mapNotNull { it.coverFaceId }
            ).toSet()
        val start = System.nanoTime()
        var count = 0
        var tagCount = 0
        var catchUpCount = 0
        var fixedCount = 0
        var keptRows = 0
        var addedRows = 0
        var droppedRows = 0
        var nextSave = start + 30_000_000_000L
        // no tagger file, or it won't load: faces only
        val tagger = if (!taggerFile(applicationContext).exists()) null else try {
            Tagger(applicationContext)
        } catch (e: Exception) {
            AppLog.w("tagger not loaded", e)
            null
        }
        // no object models, or they won't load: no gate and no pets, and the photos stay unboxed for a later run
        val objects = if (!boxing) null else {
            var detector: ObjectDetector? = null
            try {
                detector = ObjectDetector(applicationContext)
                detector to PetVectors(applicationContext)
            } catch (e: Exception) {
                AppLog.w("object models not loaded", e)
                detector?.close()
                null
            }
        }
        val prefetch = Executors.newSingleThreadExecutor() // one thread is enough: HEIC decoding tops out at ~100 ms a photo even on 4 threads
        val times = StepTimes()

        /** A new photo: its decode runs on the helper thread while the models work on the last one. */
        suspend fun scanPhotos(pipeline: FacePipeline) {
            fun ahead(i: Int): Future<kotlin.Result<Prepared>>? = newPhotos.getOrNull(i)?.let { p -> prefetch.submit(Callable { runCatching { prepare(applicationContext, p) } }) }
            var next = ahead(0)
            for ((i, photo) in newPhotos.withIndex()) {
                if (isStopped) break
                times.start()
                val ready = next!!.get() // decoded while the last photo ran
                next = ahead(i + 1)
                val tag = tagger?.takeIf { photo.id !in tagged || photo.id in pending } // a Rescan keeps the vectors
                var vector: FloatArray? = null
                var boxes: List<ObjectBoxRow>? = null // null: not boxed, the object models are not here
                var pets = emptyList<FaceRow>()
                var fullWidth = 0 // the full decode, made only when faces are found
                var fullHeight = 0
                val (kind, faces) = try {
                    val prep = ready.getOrThrow() // a decode error lands in the catch below, like before
                    val kind = prep.kind
                    times.lap(Step.Wait)
                    kind to if (kind == PhotoKind.skip) {
                        if (tag != null) vector = tagAlone(tag, photo.id)
                        if (objects != null) boxes = emptyList()
                        emptyList()
                    } else {
                        // Detect on a small decode, full decode only for photos with faces (~half). The tagger reads the same small decode.
                        val photoUri = prep.uri!!
                        val small = prep.small!!
                        val w = prep.width
                        val h = prep.height
                        if (tag != null) vector = try {
                            tag.vector(tag.input(small))
                        } catch (e: Exception) {
                            AppLog.w("tag ${photo.id}", e)
                            FloatArray(0)
                        }
                        times.lap(Step.Tag)
                        val lb = Letterbox(small)
                        val found = if (objects == null) pipeline.detect(lb) else {
                            val all = objects.first.detect(lb)
                            boxes = all.map { boxRow(photo.id, it, small) }
                            pets = all.filter { it.cls != PERSON && it.score >= 0.7f }.map { petFace(photo.id, it, small, objects.second, w, h) }
                            times.lap(Step.Objects)
                            // no person box, no face detection; then a face counts only inside a person box (docs/objects.md)
                            val people = all.filter { it.cls == PERSON }.map { it.box }
                            if (people.isEmpty()) emptyList() else pipeline.detect(lb).filter { inPerson(it.box, people) }
                        }
                        times.lap(Step.Faces)
                        if (found.isEmpty()) found else {
                            val image = loadPhoto(applicationContext.contentResolver, photoUri)
                            fullWidth = image.width
                            fullHeight = image.height
                            times.lap(Step.FullDecode)
                            val kx = image.width.toFloat() / small.width
                            val ky = image.height.toFloat() / small.height
                            found.onEach { f ->
                                for (a in listOf(f.box, f.landmarks)) for (i in a.indices) a[i] *= if (i % 2 == 0) kx else ky
                                pipeline.embed(image, f)
                                f.thumb = faceThumb(image, f.box)
                            }.also { times.lap(Step.Embed) }
                        }
                    }
                } catch (e: Exception) {
                    AppLog.w("index ${photo.id}", e)
                    if (tag != null && vector == null) vector = FloatArray(0) // unreadable: not tried again
                    if (objects != null) boxes = emptyList()
                    PhotoKind.error to emptyList()
                }
                val rows = faces.map {
                    FaceRow(photoId = photo.id, box = it.box, score = it.score, landmarks = it.landmarks, vector = it.vector!!, quality = it.quality!!, thumb = it.thumb!!)
                }
                val fixing = photo.id in pending
                val axis = if (fixing) heicMirrorAxis(applicationContext.contentResolver, ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, photo.id)) else -1
                if (!fixing) Db.get(applicationContext).withTransaction {
                    dao.insert(PhotoRow(photo.id, photo.path, kind, objects = boxes?.let { ObjectDetector.VERSION }), rows + pets)
                    boxes?.let { dao.insertBoxes(it) }
                } else Db.get(applicationContext).withTransaction {
                    // a photo found on a mirrored picture: an old face keeps its row when a new one sits at the same place
                    if (kind != PhotoKind.error) { // an unreadable photo must not lose its faces
                        dao.updatePhoto(photo.id, kind, boxes?.let { ObjectDetector.VERSION })
                        dao.deleteBoxes(listOf(photo.id))
                        boxes?.let { dao.insertBoxes(it) }
                        dao.deletePets(photo.id)
                        if (pets.isNotEmpty()) dao.insertFaces(pets)
                        dao.deleteTags(listOf(photo.id)) // made again from the new vector
                        val old = dao.faceBoxes(photo.id)
                        val match = matchFaces(old.map { it.id to it.box }, faces.map { it.box }, axis, fullWidth, fullHeight)
                        for ((n, face) in faces.withIndex()) {
                            val id = match[n]
                            if (id == null) dao.insertFaces(listOf(rows[n]))
                            else dao.updateFace(FaceUpdate(id, face.box, face.score, face.landmarks, face.vector!!, face.quality!!, face.thumb!!))
                        }
                        val drop = old.filter { it.id !in match.values && it.id !in keptFaces }.map { it.id }
                        if (drop.isNotEmpty()) dao.deleteFaceIds(drop)
                        keptRows += match.size
                        addedRows += faces.size - match.size
                        droppedRows += drop.size
                    }
                }
                // a photo being fixed that can't be read now keeps its old vector too
                if (!(fixing && kind == PhotoKind.error)) vector?.let { dao.insertVector(PhotoVectorRow(photo.id, Tagger.VERSION, it)); tagCount++ }
                if (fixing) {
                    pending -= photo.id
                    savePending()
                    fixedCount++
                }
                times.lap(Step.Database)
                count++
                if (count % 100 == 0) AppLog.i("per photo ms (n=$count): ${times.perPhoto(count)}")
                if (foreground && count % 10 == 0) notifications.notify(1, notice(base + count, total))
                // people show up while indexing; saves stay 10x their own time apart, so they never take more than ~10% of the run
                if (System.nanoTime() > nextSave) {
                    val t = System.nanoTime()
                    times.start()
                    placePhotos()
                    savePeople()
                    times.lap(Step.Save)
                    nextSave = System.nanoTime() + maxOf(30_000_000_000L, 10 * (System.nanoTime() - t))
                }
            }
        }

        /** A new video: one row per face or pet track, no tagger and no boxes. */
        suspend fun scanVideos(pipeline: FacePipeline) {
            for (video in newVideos) {
                if (isStopped) break
                times.start()
                val rows = try {
                    val r = videoTracks(video, pipeline, objects)
                    times.lap(Step.Video)
                    r
                } catch (e: Exception) {
                    AppLog.w("index video ${video.id}", e)
                    emptyList()
                }
                Db.get(applicationContext).withTransaction {
                    // no boxes: a video is no single pixel grid; objects marked so the run never boxes it again. No town yet: [placePhotos] reads its GPS
                    dao.insert(PhotoRow(video.id, video.path, PhotoKind.video, objects = ObjectDetector.VERSION), rows)
                }
                times.lap(Step.Database)
                count++
                if (foreground && count % 10 == 0) notifications.notify(1, notice(base + count, total, "Scanning videos"))
                if (System.nanoTime() > nextSave) {
                    val t = System.nanoTime()
                    times.start()
                    placePhotos()
                    savePeople()
                    times.lap(Step.Save)
                    nextSave = System.nanoTime() + maxOf(30_000_000_000L, 10 * (System.nanoTime() - t))
                }
            }
        }

        /** Photos indexed before the tagger or the object models: one 640 decode for both. */
        suspend fun scanCatchUp() {
            // the user's faces stay whatever the boxes say: answered, merged, or a cover
            val kept = if (objects == null) emptySet() else
                (dao.answers().flatMap { listOf(it.faceId, it.personFace) } + dao.merges().flatMap { listOf(it.face, it.personFace) } + dao.people().mapNotNull { it.coverFaceId }).toSet()
            for ((i, photo) in catchUp.withIndex()) {
                if (isStopped) break
                if (foreground && i % 10 == 0) notifications.notify(1, notice(base + count + i, total, if (boxing) "Scanning photos" else "Tagging photos"))
                val tag = tagger?.takeIf { photo.id !in tagged }
                val box = objects?.takeIf { photo.id !in boxed }
                if (tag == null && box == null) continue
                catchUp(photo.id, tag, box, kept)
                if (tag != null) tagCount++
                catchUpCount++
            }
        }

        try {
            FacePipeline(applicationContext).use { pipeline ->
                AppLog.i("models loaded in ${(System.nanoTime() - start) / 1_000_000} ms")
                scanPhotos(pipeline)
                scanVideos(pipeline)
            }
            scanCatchUp()
        } finally {
            prefetch.shutdownNow()
            tagger?.close()
            objects?.first?.close()
            objects?.second?.close()
            // a tap on the notice can drop the foreground (bugs.md), then nothing else removes our last notify()
            notifications.cancel(1)
            IndexProgress.finish(applicationContext)
            val stop = if (isStopped) ", stopped, reason $stopReason" else ""
            if (fixedCount > 0) AppLog.i("mirror fix: $fixedCount photos, $keptRows faces kept their row, $addedRows added, $droppedRows dropped")
            AppLog.i("indexed $count photos, tagged $tagCount, caught up $catchUpCount in %.0f s$stop".format((System.nanoTime() - start) / 1e9))
            if (count > 0) AppLog.i("per photo ms (n=$count): ${times.perPhoto(count)}")
        }
        if (isStopped) return Result.success()
        placePhotos()
        return savePeople()
    }

    /**
     * A photo indexed before the tagger or the object models: one 640 decode for both. Its old faces outside every person box are
     * dropped, except [kept] ones, and its pets are added. An unreadable photo gets an empty vector and no boxes, so it isn't tried again.
     */
    private suspend fun catchUp(id: Long, tag: Tagger?, objects: Pair<ObjectDetector, PetVectors>?, kept: Set<Long>) {
        val db = Db.get(applicationContext)
        val dao = db.index()
        var w = 0
        var h = 0
        val small = try {
            loadPhoto(applicationContext.contentResolver, ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, id), 640) { pw, ph -> w = pw; h = ph }
        } catch (e: Exception) {
            AppLog.w("catch up $id", e)
            null
        }
        if (tag != null) dao.insertVector(PhotoVectorRow(id, Tagger.VERSION, small?.let {
            try {
                tag.vector(tag.input(it))
            } catch (e: Exception) {
                AppLog.w("tag $id", e)
                null
            }
        } ?: FloatArray(0)))
        if (objects == null) return
        var boxes = emptyList<ObjectBoxRow>()
        var pets = emptyList<FaceRow>()
        var drop = emptyList<Long>()
        if (small != null) try {
            val found = objects.first.detect(Letterbox(small))
            boxes = found.map { boxRow(id, it, small) }
            pets = found.filter { it.cls != PERSON && it.score >= 0.7f }.map { petFace(id, it, small, objects.second, w, h) }
            val people = found.filter { it.cls == PERSON }.map { toFull(it.box, small, w, h) }
            drop = dao.photoFaces(id).filter { it.id !in kept && !inPerson(it.box, people) }.map { it.id }
        } catch (e: Exception) {
            AppLog.w("box $id", e)
        }
        db.withTransaction {
            if (drop.isNotEmpty()) dao.deleteFaceIds(drop)
            dao.deletePets(id)
            dao.insertFaces(pets)
            dao.deleteBoxes(listOf(id))
            dao.insertBoxes(boxes)
            dao.setBoxed(id, ObjectDetector.VERSION)
        }
    }

    /** [b] as a DB row, the box as fractions of [small]. */
    private fun boxRow(photoId: Long, b: ObjectBox, small: Bitmap) =
        ObjectBoxRow(photoId = photoId, cls = b.cls, score = b.score, box = FloatArray(4) { i -> b.box[i] / if (i % 2 == 0) small.width else small.height })

    /**
     * One video's faces and pets as one row per track: frames every max(3 s, length / 10), at most 10,
     * faces 40 px+ and pets from boxes 0.7+ joined inside the video by vector at 0.68. A row keeps the top-3 vector and the best frame's box, quality and thumb.
     * Boxes come back in the video's own pixels, so the 40 px floor means the same as on a photo (a uniform scale; a rotated video still keeps the sizes right).
     */
    private fun videoTracks(video: MediaPhoto, pipeline: FacePipeline, objects: Pair<ObjectDetector, PetVectors>?): List<FaceRow> {
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video.id)
        val faces = mutableListOf<Tracked>()
        val pets = mutableListOf<Tracked>()
        var k = 1f
        MediaMetadataRetriever().use { mmr ->
            mmr.setDataSource(applicationContext, uri)
            val length = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val vw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: video.width
            val vh = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: video.height
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (dstW, dstH) = frameSize(if (rot % 180 == 0) vw else vh, if (rot % 180 == 0) vh else vw)
            val step = max(3000L, length / 10) // a 30 s video: every 3 s; a 2 min one: every 12 s
            val times = if (length <= 0) listOf(0L) else (0 until 10).map { it * step }.filter { it < length }
            for (t in times) {
                // a keyframe, like the bench's --every fallback: seeking to the exact frame is slower and can fail near the end
                val frame = mmr.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dstW, dstH) ?: continue
                k = if (max(vw, vh) > 0) max(vw, vh).toFloat() / max(frame.width, frame.height) else 1f
                val lb = Letterbox(frame)
                val boxes = objects?.first?.detect(lb).orEmpty()
                val people = boxes.filter { it.cls == PERSON }.map { it.box }
                if (objects != null) pets += boxes.filter { it.cls != PERSON && it.score >= 0.7f }
                    .map { b -> Tracked(t, b.box, b.score, b.score, objects.second.vector(frame, b.box), faceThumb(frame, b.box)) }
                // docs/objects.md: no person box, no face; a face counts only inside a person box
                if (objects == null || people.isNotEmpty()) for (f in pipeline.detect(lb)) {
                    if (objects != null && !inPerson(f.box, people)) continue
                    if (min(f.box[2] - f.box[0], f.box[3] - f.box[1]) * k < 40f) continue
                    pipeline.embed(frame, f)
                    faces += Tracked(t, f.box, f.score, f.quality!!, f.vector!!, faceThumb(frame, f.box))
                }
                frame.recycle()
            }
        }
        return tracksOf(video.id, faces, false, k) + tracksOf(video.id, pets, true, k)
    }

    /** One row per track: the best frame by [Tracked.quality] (a tie keeps the earlier one) for the box, score, quality and thumb, and the top-3 vector. [k] scales a frame box to the video's own pixels. */
    private fun tracksOf(photoId: Long, items: List<Tracked>, pet: Boolean, k: Float): List<FaceRow> {
        if (items.isEmpty()) return emptyList()
        val order = items.sortedBy { it.t }
        return averageLinkage(order.map { it.vector }, 0.68f).map { g ->
            val top = g.map { order[it] }.sortedByDescending { it.quality } // stable: a tie keeps the earlier frame
            val best = top.first()
            FaceRow(
                photoId = photoId, box = FloatArray(4) { best.box[it] * k }, score = best.score, landmarks = FloatArray(0),
                vector = averageVector(top.take(3).map { it.vector }), quality = best.quality, thumb = best.thumb, pet = pet,
            )
        }
    }

    /** A cat or dog box with score 0.7+ (surer boxes, fewer strays among the pets) as a pet face: the DINOv2 vector, a picture cut from [small], the box in face pixels. */
    private fun petFace(photoId: Long, b: ObjectBox, small: Bitmap, pets: PetVectors, w: Int, h: Int) = FaceRow(
        photoId = photoId, box = toFull(b.box, small, w, h), score = b.score, landmarks = FloatArray(0),
        vector = pets.vector(small, b.box), quality = b.score, thumb = faceThumb(small, b.box), pet = true,
    )

    /** A box in [small]'s pixels to the pixels of loadPhoto's 4096 decode of a [w] x [h] photo, where faces are saved. */
    private fun toFull(b: FloatArray, small: Bitmap, w: Int, h: Int): FloatArray {
        val k = min(1f, 4096f / max(w, h))
        return FloatArray(4) { i -> b[i] * k * if (i % 2 == 0) w.toFloat() / small.width else h.toFloat() / small.height }
    }

    private val towns by lazy { Towns.load(applicationContext) }

    /** A 640 decode for the tagger alone. An unreadable photo gets an empty vector, so it isn't tried again. */
    private fun tagAlone(tagger: Tagger, id: Long): FloatArray = try {
        tagger.vector(tagger.input(loadPhoto(applicationContext.contentResolver, ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, id), 640)))
    } catch (e: Exception) {
        AppLog.w("tag $id", e)
        FloatArray(0)
    }

    /** Reads GPS and finds the nearest town for photos and videos saved without one: new ones, ones indexed before DB v9, and videos before video towns. */
    private suspend fun placePhotos() {
        val start = System.nanoTime()
        val dao = Db.get(applicationContext).index()
        val ids = dao.unplaced()
        for (id in ids) {
            if (isStopped) break
            val gps = photoGps(applicationContext, ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, id))
            val t = gps?.let { towns.nearest(it.first, it.second) }
            dao.setPlace(id, gps?.first, gps?.second, t?.let { towns.id[it] } ?: 0, t?.let { towns.name[it] })
        }
        // videos: the GPS is a location string in the file (ISO 6709), which is how the viewer reads it too
        val videos = dao.unplacedVideos()
        for (id in videos) {
            if (isStopped) break
            val gps = videoGps(id)
            val t = gps?.let { towns.nearest(it.first, it.second) }
            dao.setPlace(id, gps?.first, gps?.second, t?.let { towns.id[it] } ?: 0, t?.let { towns.name[it] })
        }
        AppLog.i("placed ${ids.size} photos and ${videos.size} videos in ${(System.nanoTime() - start) / 1_000_000} ms")
    }

    /** A video's GPS from its metadata: [iso6709], and null for 0,0 (some files carry an empty location). */
    private fun videoGps(id: Long): Pair<Double, Double>? = runCatching {
        // the original file: Android redacts the location on the ordinary URI without ACCESS_MEDIA_LOCATION
        val uri = MediaStore.setRequireOriginal(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id))
        MediaMetadataRetriever().use { r ->
            r.setDataSource(applicationContext, uri)
            iso6709(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))?.takeIf { it.first != 0.0 || it.second != 0.0 }
        }
    }.getOrNull()

    /** Saves people (see [regroup]); a new [GROUPING_VERSION] regroups everyone once. */
    private suspend fun savePeople(): Result {
        val prefs = applicationContext.getSharedPreferences("index", Context.MODE_PRIVATE)
        val full = prefs.getInt("grouping", 0) != GROUPING_VERSION
        regroup(Db.get(applicationContext), full = full)
        if (full) prefs.edit().putInt("grouping", GROUPING_VERSION).apply()
        peopleSaved.value++
        return Result.success()
    }

    private fun notice(done: Int, total: Int, title: String = "Finding faces"): Notification {
        val base = IndexProgress.base(applicationContext)
        val eta = etaMillis(IndexProgress.startedAt(applicationContext), (done - base).coerceAtLeast(0), total - base)
        return Notification.Builder(applicationContext, "index")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (eta != null) "$done / $total photos · ${formatEta(eta)}" else "$done / $total photos")
            .setProgress(total, done, false)
            .setOngoing(true)
            // a tap opens Settings, where the run shows; the open app gets it in onNewIntent
            .setContentIntent(PendingIntent.getActivity(
                applicationContext, 0,
                Intent(applicationContext, MainActivity::class.java).putExtra("openSettings", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            ))
            .build()
    }
}
