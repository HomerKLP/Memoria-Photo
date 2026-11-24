package app.memoria

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The vault's keys while it is unlocked: in memory only, cleared when the folder closes or the app stops. */
var vaultKeys by mutableStateOf<VaultKeys?>(null)

class VaultLocked : IOException("The locked folder is locked")

/**
 * One locked photo or video. Nothing here is secret but the names and dates, and the manifest holds
 * them encrypted; the tile's id is negative, so it never meets a MediaStore id.
 */
data class VaultItem(
    val n: Long, val name: String, val type: String,
    val date: Long, val added: Long, val size: Long,
    val width: Int, val height: Int, val duration: Long?, val folder: String,
) {
    val id: Long get() = -n
    val uri: Uri get() = Uri.parse("memoria-vault://$n")
    val tile: Tile get() = Tile(id, uri, date, duration, ratio = 0f, vault = true)
}

/**
 * The locked folder on disk: `files/vault/` with the wrapped key, one encrypted manifest, one encrypted
 * file per item (`f<n>`) and its encrypted thumbnail (`t<n>`). Locking encrypts first and only then lets
 * Android delete the gallery copy, so a crash at any point leaves a duplicate and never a lost photo.
 */
/** The manifest's text: one TSV line per item, names, types and folders encoded (java.net, so the JVM tests run too). */
internal fun encodeVault(items: List<VaultItem>): String = items.joinToString("\n") { i ->
    fun e(s: String) = URLEncoder.encode(s, "UTF-8")
    listOf(i.n, e(i.name), e(i.type), i.date, i.added, i.size, i.width, i.height, i.duration ?: -1L, e(i.folder))
        .joinToString("\t")
}

internal fun decodeVault(text: String): List<VaultItem> = text.lines().filter { it.isNotBlank() }.map { line ->
    val p = line.split('\t')
    fun d(s: String) = URLDecoder.decode(s, "UTF-8")
    VaultItem(
        p[0].toLong(), d(p[1]), d(p[2]), p[3].toLong(), p[4].toLong(), p[5].toLong(),
        p[6].toInt(), p[7].toInt(), p[8].toLong().takeIf { it >= 0 }, d(p[9]),
    )
}

object VaultStore {
    private const val manifestName = "manifest"
    private const val thumbPx = 384
    private const val maxPhoto = 128L * 1024 * 1024 // a photo bigger than this is not decoded into memory
    // One change at a time: a sweep or a second lock mid-lock would drop files the manifest does not list yet
    private val busy = Mutex()

    /** What [lock] made: the tiles taken and their new items, until Android's confirm settles it. */
    class Batch(val tiles: List<Tile>, val items: List<VaultItem>)

    fun dir(context: Context) = File(context.filesDir, "vault")
    fun file(context: Context, n: Long) = File(dir(context), "f$n")
    fun thumbFile(context: Context, n: Long) = File(dir(context), "t$n")

    /** Newest first. Throws [VaultLocked] when the vault is locked. */
    suspend fun items(context: Context): List<VaultItem> = withContext(Dispatchers.IO) {
        read(context).sortedByDescending { it.date }
    }

    fun item(context: Context, id: Long): VaultItem? = runCatching { read(context).firstOrNull { it.id == id } }.getOrNull()

    /**
     * Encrypts every tile into the vault and commits the manifest: a crash now leaves a duplicate, not a gap.
     * [progress] gets the bytes done and the total; [cancelled] is checked per chunk, until the manifest is written.
     */
    suspend fun lock(
        context: Context, tiles: List<Tile>,
        progress: (Long, Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false },
    ): Batch = withContext(Dispatchers.IO) { busy.withLock {
        val keys = vaultKeys ?: throw VaultLocked()
        val all = read(context)
        var next = (all.maxOfOrNull { it.n } ?: 0L) + 1
        val made = mutableListOf<VaultItem>()
        try {
            val infos = tiles.map { info(context, it.uri) }
            val total = infos.sumOf { it.size }
            var done = 0L
            progress(0, total) // the dialog never reads "0.0 of 0.0 MB"
            val chunk = { n: Int ->
                if (cancelled()) throw IOException("lock cancelled")
                done += n
                progress(done, total)
            }
            for ((t, info) in tiles.zip(infos)) {
                if (cancelled()) throw IOException("lock cancelled")
                made += take(context, keys, t, info, next++, chunk)
            }
            if (cancelled()) throw IOException("lock cancelled")
            // The manifest goes last: until it says so, the files are orphans and the sweep deletes them
            write(context, all + made)
            Batch(tiles, made)
        } catch (e: Throwable) {
            made.forEach { drop(context, it) }
            throw e
        }
    } }

    /** The gallery copies are gone: the index rows for them go too, so People and Search forget the photo. */
    suspend fun commit(context: Context, batch: Batch) = withContext(Dispatchers.IO) {
        val ids = batch.tiles.map { it.id }
        val dao = Db.get(context).index()
        dao.deleteFaces(ids)
        dao.deletePhotos(ids)
    }

    /** Android refused the delete: take the encrypted copies back out, so nothing is left in two places. */
    suspend fun undo(context: Context, batch: Batch) = delete(context, batch.items)

    suspend fun delete(context: Context, items: List<VaultItem>) = withContext(Dispatchers.IO) { busy.withLock {
        val n = items.map { it.n }.toSet()
        write(context, read(context).filterNot { it.n in n })
        items.forEach { drop(context, it) }
    } }

    /**
     * Decrypts each item back into the gallery, in its first folder, then drops the vault copy. Returns how many moved.
     * [progress] gets the bytes done and the total. [cancelled] is checked per chunk: the file in progress is deleted
     * from the gallery, the ones already out stay out, the rest stay locked.
     */
    suspend fun moveOut(
        context: Context, items: List<VaultItem>,
        progress: (Long, Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false },
    ): Int = withContext(Dispatchers.IO) { busy.withLock {
        val keys = vaultKeys ?: throw VaultLocked()
        val total = items.sumOf { runCatching { Vault.open(file(context, it.n), keys).use { v -> v.size } }.getOrDefault(0L) }
        var done = 0L
        progress(0, total)
        val chunk = { n: Int ->
            if (cancelled()) throw IOException("move out cancelled")
            done += n
            progress(done, total)
        }
        var moved = 0
        for (item in items) {
            if (cancelled()) throw IOException("move out cancelled")
            // A second tap on Move out: the first one already took it, and a second copy would land in the gallery
            if (read(context).none { it.n == item.n }) continue
            writeBack(context, keys, item, chunk)
            write(context, read(context).filterNot { it.n == item.n })
            drop(context, item)
            moved++
        }
        moved
    } }

    /** Deletes crash leftovers and files the manifest does not list: the first thing after an unlock. */
    suspend fun sweep(context: Context) = withContext(Dispatchers.IO) { busy.withLock {
        val dir = dir(context)
        val items = runCatching { read(context) }.getOrNull() ?: return@withLock
        val keep = items.flatMap { listOf("f${it.n}", "t${it.n}") }.toSet() + setOf(manifestName, "key")
        dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
    } }

    fun thumb(context: Context, id: Long): Bitmap? {
        val keys = vaultKeys ?: return null
        val f = thumbFile(context, -id)
        if (!f.exists()) return null
        val bytes = bytes(keys, f) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /** The photo itself, for the viewer: EXIF turn included, as MediaStore's own decode would. */
    fun photo(context: Context, id: Long): Bitmap? {
        val keys = vaultKeys ?: return null
        val item = item(context, id) ?: return null
        if (item.size > maxPhoto) return null
        val bytes = bytes(keys, file(context, item.n)) ?: return null
        // The turn `plainSource` reads for a gallery photo: Android skips a DNG's and mishandles a HEIC's
        val turn = when (item.type) {
            "image/heic", "image/heif" -> blankHeicTurn(bytes)
            "image/x-adobe-dng" -> runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
            else -> 1
        }
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val screen = context.resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) }
            decoder.setTargetSampleSize(viewerSample(info.size.width, info.size.height, screen))
        }.turnedBy(turn)
    }

    private fun take(context: Context, keys: VaultKeys, t: Tile, info: Info, n: Long, onChunk: (Int) -> Unit): VaultItem {
        try {
            val item = VaultItem(
                n, info.name, info.type, t.date, System.currentTimeMillis(), info.size,
                info.width, info.height, t.duration, info.folder,
            )
            // The original bytes: the plain URI hides the GPS (bugs.md 2026-11-06), and this copy replaces the file
            val src = runCatching { context.contentResolver.openInputStream(MediaStore.setRequireOriginal(t.uri)) }.getOrNull()
                ?: context.contentResolver.openInputStream(t.uri)
            val written = src?.use { Vault.encrypt(it, file(context, n), keys, onChunk) }
                ?: throw IOException("cannot read ${t.uri}")
            // A stale MediaStore row whose file is gone reads as an empty stream: never a 0-byte locked photo
            if (written == 0L) throw IOException("empty file ${t.uri}")
            runCatching { context.contentResolver.loadThumbnail(t.uri, Size(thumbPx, thumbPx), null) }.getOrNull()?.let { bmp ->
                val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                Vault.encrypt(jpeg.inputStream(), thumbFile(context, n), keys)
            }
            return item
        } catch (e: Throwable) {
            file(context, n).delete()
            thumbFile(context, n).delete()
            throw e
        }
    }

    private fun writeBack(context: Context, keys: VaultKeys, item: VaultItem, onChunk: (Int) -> Unit) {
        val resolver = context.contentResolver
        val collection = if (item.type.startsWith("video/")) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaColumns.DISPLAY_NAME, item.name)
            put(MediaColumns.MIME_TYPE, item.type)
            put(MediaColumns.RELATIVE_PATH, item.folder)
            put(MediaColumns.DATE_TAKEN, item.date)
            put(MediaColumns.DATE_ADDED, item.added / 1000)
            put(MediaColumns.DATE_MODIFIED, item.date / 1000)
            put(MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: IllegalArgumentException) {
            // Download/ or another app's Android/media/: new photos and videos may go only in DCIM, Pictures or Movies
            values.put(MediaColumns.RELATIVE_PATH, "Pictures/${item.folder.trimEnd('/').substringAfterLast('/')}/")
            resolver.insert(collection, values)
        } ?: throw IOException("cannot insert ${item.name}")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                Vault.open(file(context, item.n), keys).use { vin ->
                    val buf = ByteArray(64 * 1024)
                    var at = 0L
                    while (true) {
                        val n = vin.read(at, buf, 0, buf.size)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        at += n
                        onChunk(n)
                    }
                }
            } ?: throw IOException("cannot write ${item.name}")
            // Publishing rescans the file and drops an app's date_taken, so a photo with no EXIF date keeps its date by the file time
            runCatching { resolver.query(uri, arrayOf(MediaColumns.DATA), null, null, null)?.use { c -> if (c.moveToFirst()) File(c.getString(0)).setLastModified(item.date) } }
            resolver.update(uri, ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private fun drop(context: Context, item: VaultItem) {
        file(context, item.n).delete()
        thumbFile(context, item.n).delete()
    }

    private fun bytes(keys: VaultKeys, f: File): ByteArray? = runCatching {
        Vault.open(f, keys).use { vin ->
            val out = ByteArray(vin.size.toInt())
            var at = 0
            while (at < out.size) {
                val n = vin.read(at.toLong(), out, at, out.size - at)
                if (n <= 0) break
                at += n
            }
            out
        }
    }.getOrNull()

    private class Info(val name: String, val type: String, val size: Long, val width: Int, val height: Int, val folder: String)

    private fun info(context: Context, uri: Uri): Info =
        context.contentResolver.query(
            uri,
            arrayOf(MediaColumns.DISPLAY_NAME, MediaColumns.MIME_TYPE, MediaColumns.SIZE, MediaColumns.WIDTH, MediaColumns.HEIGHT, MediaColumns.RELATIVE_PATH),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) throw IOException("no media row for $uri")
            Info(
                c.getString(0) ?: "photo", c.getString(1) ?: "image/jpeg",
                // A file not scanned yet has size 0 in MediaStore; the file itself knows
                c.getLong(2).takeIf { it > 0 } ?: runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: 0L,
                c.getInt(3), c.getInt(4),
                c.getString(5)?.takeIf { it.endsWith("/") } ?: "Pictures/Memoria/",
            )
        } ?: throw IOException("no media row for $uri")

    // Manifest: one TSV line per item, names and folders percent-encoded. Encrypted as a vault file.
    private fun read(context: Context): MutableList<VaultItem> {
        val keys = vaultKeys ?: throw VaultLocked()
        val f = File(dir(context), manifestName)
        if (!f.exists()) return mutableListOf()
        val bytes = Vault.open(f, keys).use { vin ->
            val out = ByteArray(vin.size.toInt())
            var at = 0
            while (at < out.size) {
                val n = vin.read(at.toLong(), out, at, out.size - at)
                if (n <= 0) break
                at += n
            }
            out
        }
        return decodeVault(String(bytes)).toMutableList()
    }

    private fun write(context: Context, items: List<VaultItem>) {
        val keys = vaultKeys ?: throw VaultLocked()
        val f = File(dir(context), manifestName)
        f.parentFile?.mkdirs()
        val text = encodeVault(items)
        val tmp = File(f.parentFile, "$manifestName.tmp")
        Vault.encrypt(text.toByteArray().inputStream(), tmp, keys)
        // A fresh inode, so a crash never leaves half a manifest at the real name
        // shortcut: the folder's own entry is not synced (Java cannot open a folder), so a power loss can still drop the rename; what is left is whole
        if (!tmp.renameTo(f)) throw IOException("cannot write the vault manifest")
    }
}
