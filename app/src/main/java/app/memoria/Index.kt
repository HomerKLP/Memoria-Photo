package app.memoria

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import android.os.BatteryManager
import android.provider.MediaStore.Images.Media
import android.util.Log
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.min

/** kind: camera (can start a person), other (downloads, messengers: only join people), skip, error. */
@Entity(tableName = "photo")
class PhotoRow(@PrimaryKey val id: Long, val path: String, val kind: String)

@Entity(tableName = "face", indices = [Index("photoId")])
class FaceRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val photoId: Long,
    val box: FloatArray,
    val score: Float,
    val landmarks: FloatArray,
    val vector: FloatArray,
    val quality: Float,
    val thumb: ByteArray,
    val personId: Long? = null,
    val notFace: Boolean = false, // the user said "Not a face": left out of everything
)

/** People saved by the last index run, see savePeople. `name` and `hidden` are the user's; kept across re-index runs. */
@Entity(tableName = "person")
class PersonRow(@PrimaryKey val id: Long, val mainFaceId: Long, val name: String? = null, val hidden: Boolean = false)

/**
 * Help recognize answer: face [faceId] is (yes) or is not (no) the person that [personFace] belongs to.
 * [personFace] is that person's main face (a starting face, or the face a New person began from), so it outlives the person ids that every regroup changes.
 */
@Entity(tableName = "answer", primaryKeys = ["faceId", "personFace"])
class AnswerRow(val faceId: Long, val personFace: Long, val yes: Boolean)

/** Merge with: the person whose main face is [face] is the same as the one whose main face is [personFace]. Main faces, like [AnswerRow]. */
@Entity(tableName = "merged", primaryKeys = ["face", "personFace"])
class MergeRow(val face: Long, val personFace: Long)

class Converters {
    @TypeConverter fun toBytes(v: FloatArray): ByteArray = ByteBuffer.allocate(v.size * 4).apply { asFloatBuffer().put(v) }.array()
    @TypeConverter fun toFloats(b: ByteArray): FloatArray = FloatArray(b.size / 4).also { ByteBuffer.wrap(b).asFloatBuffer().get(it) }
}

@Dao
interface IndexDao {
    @Query("SELECT id FROM photo") suspend fun photoIds(): List<Long>
    @Query("DELETE FROM photo") suspend fun clearPhotos()
    @Query("DELETE FROM face") suspend fun clearFaces()
    @Query("DELETE FROM answer") suspend fun clearAnswers()
    @Insert suspend fun insert(photo: PhotoRow, faces: List<FaceRow>)
    @Query("SELECT kind, COUNT(*) AS n FROM photo GROUP BY kind")
    fun kinds(): Flow<Map<@MapColumn("kind") String, @MapColumn("n") Int>>
    @Query("SELECT COUNT(*) FROM face") fun faceCount(): Flow<Int>
    @Query(
        "SELECT f.id, f.photoId, f.box, f.vector, f.quality, p.kind, f.personId, " +
            "EXISTS(SELECT 1 FROM answer a WHERE a.faceId = f.id AND a.yes) AS yes, " +
            "EXISTS(SELECT 1 FROM answer a WHERE a.faceId = f.id AND NOT a.yes) AS rejected FROM face f JOIN photo p ON p.id = f.photoId WHERE NOT f.notFace",
    )
    suspend fun faceVecs(): List<FaceVec>
    @Query("SELECT * FROM person ORDER BY id") suspend fun people(): List<PersonRow>
    @Query("UPDATE person SET name = :name WHERE id = :id") suspend fun setName(id: Long, name: String?)
    @Query("DELETE FROM person") suspend fun deletePeople()
    @Query("UPDATE face SET personId = NULL") suspend fun clearPersonIds()
    @Insert suspend fun insertPerson(person: PersonRow)
    @Query("UPDATE face SET personId = :person WHERE id IN (:faces)") suspend fun setPerson(faces: List<Long>, person: Long)
    @Query("SELECT thumb FROM face WHERE id = :id") suspend fun thumb(id: Long): ByteArray?
    @Query("SELECT * FROM answer") suspend fun answers(): List<AnswerRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun answer(answer: AnswerRow)
    @Query("DELETE FROM answer WHERE faceId = :face AND yes") suspend fun dropYes(face: Long)
    @Query("SELECT COALESCE(MAX(id), 0) + 1 FROM person") suspend fun nextPersonId(): Long
    @Query("UPDATE face SET personId = NULL WHERE id = :face") suspend fun leavePerson(face: Long)
    @Query("UPDATE face SET notFace = 1, personId = NULL WHERE id = :face") suspend fun notFace(face: Long)
    @Query("SELECT * FROM merged") suspend fun merges(): List<MergeRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun merge(merge: MergeRow)
    @Query("DELETE FROM merged") suspend fun clearMerges()
    @Query("UPDATE face SET personId = :to WHERE personId = :from") suspend fun movePerson(from: Long, to: Long)
    @Query("DELETE FROM person WHERE id = :id") suspend fun deletePerson(id: Long)
    @Query("UPDATE person SET hidden = :hidden WHERE id = :id") suspend fun setHidden(id: Long, hidden: Boolean)
}

@Database(entities = [PhotoRow::class, FaceRow::class, PersonRow::class, AnswerRow::class, MergeRow::class], version = 7)
@TypeConverters(Converters::class)
abstract class Db : RoomDatabase() {
    abstract fun index(): IndexDao

    companion object {
        /** v5: person.name, so a named person keeps its name across re-index runs. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE person ADD COLUMN name TEXT")
        }
        /** v6: Help recognize answers. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE face ADD COLUMN notFace INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE answer (faceId INTEGER NOT NULL, personFace INTEGER NOT NULL, yes INTEGER NOT NULL, PRIMARY KEY(faceId, personFace))")
            }
        }
        /** v7: Merge with and Hide person. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE person ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE merged (face INTEGER NOT NULL, personFace INTEGER NOT NULL, PRIMARY KEY(face, personFace))")
            }
        }
        internal val MIGRATIONS = arrayOf(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
        private var db: Db? = null
        @Synchronized fun get(context: Context) =
            db ?: // a missing migration crashes instead of wiping names; MigrationTest checks each bump
            Room.databaseBuilder(context.applicationContext, Db::class.java, "memoria.db")
                .addMigrations(*MIGRATIONS)
                .fallbackToDestructiveMigrationFrom(true, 1, 2, 3).build().also { db = it }
    }
}

class MediaPhoto(val id: Long, val path: String, val mime: String, val width: Int, val height: Int)

// ponytail: test folder only, drop the filter to index the whole library
fun testPhotos(context: Context): List<MediaPhoto> {
    val photos = mutableListOf<MediaPhoto>()
    val cols = arrayOf(Media._ID, Media.RELATIVE_PATH, Media.DISPLAY_NAME, Media.MIME_TYPE, Media.WIDTH, Media.HEIGHT)
    context.contentResolver.query(Media.EXTERNAL_CONTENT_URI, cols, "${Media.RELATIVE_PATH} LIKE ?", arrayOf("Pictures/MemoriaTest/%"), null)?.use { c ->
        while (c.moveToNext()) photos += MediaPhoto(c.getLong(0), c.getString(1) + c.getString(2), c.getString(3), c.getInt(4), c.getInt(5))
    }
    return photos
}

/** Folders the whole-library index leaves out. Empty for now, scope not decided (see AGENTS.md). */
private val excludedFolders = emptyList<String>()

/**
 * Every image MediaStore knows about, like Immich's import paths: include all, minus [excludedFolders].
 * Videos stay out for now. kindOf sorts camera / other / skip.
 */
fun libraryPhotos(context: Context): List<MediaPhoto> {
    val photos = mutableListOf<MediaPhoto>()
    val cols = arrayOf(Media._ID, Media.RELATIVE_PATH, Media.DISPLAY_NAME, Media.MIME_TYPE, Media.WIDTH, Media.HEIGHT)
    context.contentResolver.query(Media.EXTERNAL_CONTENT_URI, cols, null, null, null)?.use { c ->
        while (c.moveToNext()) {
            val path = c.getString(1) + c.getString(2)
            if (excludedFolders.none { c.getString(1).startsWith(it) }) photos += MediaPhoto(c.getLong(0), path, c.getString(3), c.getInt(4), c.getInt(5))
        }
    }
    return photos
}

/** Total and start time of the current index run, so the UI can show a progress bar and time left. */
object IndexProgress {
    private fun prefs(context: Context) = context.getSharedPreferences("index", Context.MODE_PRIVATE)
    fun start(context: Context, total: Int, base: Int) {
        prefs(context).edit().putInt("total", total).putInt("base", base).putLong("started", System.currentTimeMillis()).putBoolean("paused", false).apply()
    }
    fun total(context: Context) = prefs(context).getInt("total", 0)
    fun base(context: Context) = prefs(context).getInt("base", 0)
    fun startedAt(context: Context) = prefs(context).getLong("started", 0L)
    fun paused(context: Context) = prefs(context).getBoolean("paused", false)
    fun setPaused(context: Context, paused: Boolean) = prefs(context).edit().putBoolean("paused", paused).apply()
    fun testOnly(context: Context) = prefs(context).getBoolean("testOnly", false)
    fun setTestOnly(context: Context, testOnly: Boolean) = prefs(context).edit().putBoolean("testOnly", testOnly).apply()
    fun chargingOnly(context: Context) = prefs(context).getBoolean("chargingOnly", false)
    fun setChargingOnly(context: Context, on: Boolean) = prefs(context).edit().putBoolean("chargingOnly", on).apply()
    /** Done: clears the run's total, base and start time, keeps the paused and scope flags. */
    fun finish(context: Context) = prefs(context).edit().remove("total").remove("base").remove("started").apply()
}

/** A queued run that only waits for the charger, so the progress row says so instead of hiding. */
fun waitsForCharger(context: Context) =
    IndexProgress.chargingOnly(context) && !context.getSystemService(BatteryManager::class.java).isCharging

/** Starts, pauses and resumes the index work. Pausing cancels the worker; already-saved photos are skipped on resume. */
object IndexControl {
    private const val WORK = "index"
    fun start(context: Context, testOnly: Boolean) {
        IndexProgress.setTestOnly(context, testOnly)
        IndexProgress.setPaused(context, false)
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<IndexWorker>().setInputData(workDataOf("testOnly" to testOnly))
                // unplugging stops the worker, WorkManager starts it again on the next charge
                .setConstraints(Constraints.Builder().setRequiresCharging(IndexProgress.chargingOnly(context)).build()).build(),
        )
    }
    fun pause(context: Context) {
        IndexProgress.setPaused(context, true)
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
    fun resume(context: Context) = start(context, IndexProgress.testOnly(context))
}

/** Rough time left: elapsed per photo times the photos left. Null until at least one photo is done. */
fun etaMillis(started: Long, done: Int, total: Int): Long? {
    if (started <= 0L || done <= 0 || total <= done) return null
    return (System.currentTimeMillis() - started) / done * (total - done)
}

fun formatEta(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(1)
    return when {
        s < 60 -> "${s}s left"
        s < 3600 -> "${s / 60}m left"
        else -> "${s / 3600}h ${(s % 3600) / 60}m left"
    }
}

fun kindOf(context: Context, photo: MediaPhoto, uri: Uri): String = when {
    "screenshot" in photo.path.lowercase() || photo.mime == "image/gif" || min(photo.width, photo.height) in 1 until 200 -> "skip"
    context.contentResolver.openInputStream(uri)!!.use { ExifInterface(it).getAttribute(ExifInterface.TAG_MAKE) } != null -> "camera"
    else -> "other"
}

/** Finds faces in new photos and saves them. Photos already in the DB are skipped, so it can stop and resume. */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dao = Db.get(applicationContext).index()
        val testOnly = inputData.getBoolean("testOnly", false)
        val all = if (testOnly) testPhotos(applicationContext) else libraryPhotos(applicationContext)
        val total = all.size
        val done = dao.photoIds().toHashSet()
        val todo = all.filter { it.id !in done }
        if (todo.isEmpty()) {
            IndexProgress.finish(applicationContext)
            return savePeople() // nothing new, but the grouping code may have changed
        }
        val base = total - todo.size // photos already in the DB, so the bar starts there
        IndexProgress.start(applicationContext, total, base)
        val models = File(applicationContext.getExternalFilesDir(null), "models")
        val options = OrtSession.SessionOptions()
        options.addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst", "enable_htp_fp16_precision" to "1"))
        // Foreground, or the system stops the job midway. Not allowed when started from the background (rerun after a stop), then it runs as a plain job.
        val notifications = applicationContext.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("index", "Indexing", NotificationManager.IMPORTANCE_LOW))
        val foreground = try {
            setForeground(ForegroundInfo(1, notice(base, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            true
        } catch (e: IllegalStateException) {
            Log.w("Memoria", "index runs as a plain job", e)
            false
        }
        val start = System.nanoTime()
        var count = 0
        try {
            FacePipeline(OrtEnvironment.getEnvironment(), File(models, "detector.onnx").path, File(models, "recognizer.onnx").path, File(models, "quality.onnx").path, options).use { pipeline ->
                for (photo in todo) {
                    if (isStopped) break
                    val uri = ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, photo.id)
                    val (kind, faces) = try {
                        val kind = kindOf(applicationContext, photo, uri)
                        kind to if (kind == "skip") emptyList() else {
                            // Detect on a small decode, full decode only for photos with faces (~half)
                            val source = ImageDecoder.createSource(applicationContext.contentResolver, uri)
                            val small = loadPhoto(source, 640)
                            val found = pipeline.detect(small)
                            if (found.isEmpty()) found else {
                                val image = loadPhoto(source)
                                val kx = image.width.toFloat() / small.width
                                val ky = image.height.toFloat() / small.height
                                found.onEach { f ->
                                    for (a in listOf(f.box, f.landmarks)) for (i in a.indices) a[i] *= if (i % 2 == 0) kx else ky
                                    pipeline.embed(image, f)
                                    f.thumb = faceThumb(image, f.box)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("Memoria", "index ${photo.path}", e)
                        "error" to emptyList()
                    }
                    dao.insert(PhotoRow(photo.id, photo.path, kind), faces.map {
                        FaceRow(photoId = photo.id, box = it.box, score = it.score, landmarks = it.landmarks, vector = it.vector!!, quality = it.quality!!, thumb = it.thumb!!)
                    })
                    count++
                    if (foreground && count % 10 == 0) notifications.notify(1, notice(base + count, total))
                }
            }
        } finally {
            IndexProgress.finish(applicationContext)
            val stop = if (isStopped) ", stopped, reason $stopReason" else ""
            Log.i("Memoria", "indexed $count photos in %.0f s$stop".format((System.nanoTime() - start) / 1e9))
        }
        return if (isStopped) Result.success() else savePeople()
    }

    /** Groups all faces again and replaces the saved people. Names and hidden survive by matching old and new groups on shared faces. */
    private suspend fun savePeople(): Result {
        val start = System.nanoTime()
        val db = Db.get(applicationContext)
        val dao = db.index()
        val faces = dao.faceVecs()
        val old = dao.people().filter { it.name != null || it.hidden }
        val oldFaces = faces.filter { it.personId != null }.groupBy { it.personId!! }.mapValues { e -> e.value.map { it.id }.toSet() }
        val people = groupFaces(faces, dao.answers(), dao.merges())
        val taken = mutableSetOf<Long>()
        db.withTransaction {
            dao.deletePeople()
            dao.clearPersonIds()
            people.forEachIndexed { i, p ->
                val ids = p.faces.map { it.id }
                if (ids.isEmpty()) return@forEachIndexed // never happens (a person needs faces), keeps IN () off the DB
                val pid = i + 1L
                val kept = carry(ids, old, oldFaces, taken)
                dao.insertPerson(PersonRow(pid, p.main.id, kept?.name, kept?.hidden ?: false))
                dao.setPerson(ids, pid)
            }
        }
        Log.i("Memoria", "saved ${people.size} people (${taken.size} named or hidden) in ${(System.nanoTime() - start) / 1_000_000} ms")
        return Result.success()
    }

    /**
     * The old named or hidden person whose faces overlap this new group the most, if it kept at least half of them.
     * `taken` keeps one old person from landing on two groups when it split.
     */
    private fun carry(ids: List<Long>, old: List<PersonRow>, oldFaces: Map<Long, Set<Long>>, taken: MutableSet<Long>): PersonRow? {
        val set = ids.toSet()
        return old.filter { it.id !in taken }
            .map { it to set.count { f -> f in oldFaces[it.id].orEmpty() } }
            .filter { (o, n) -> n * 2 >= (oldFaces[o.id]?.size ?: 1) }
            .maxByOrNull { it.second }?.first?.also { taken += it.id }
    }

    private fun notice(done: Int, total: Int): Notification {
        val base = IndexProgress.base(applicationContext)
        val eta = etaMillis(IndexProgress.startedAt(applicationContext), (done - base).coerceAtLeast(0), total - base)
        return Notification.Builder(applicationContext, "index")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Finding faces")
            .setContentText(if (eta != null) "$done / $total photos · ${formatEta(eta)}" else "$done / $total photos")
            .setProgress(total, done, false)
            .setOngoing(true)
            .build()
    }
}
