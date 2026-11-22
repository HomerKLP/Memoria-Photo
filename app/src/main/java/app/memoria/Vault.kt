package app.memoria

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A vault file did not pass its check: a changed byte, the wrong key, or a cut file. */
class VaultBroken(message: String) : java.io.IOException(message)

/** The vault's two 32-byte keys: AES-256 for the bytes, HMAC-SHA256 for the checks. One master, split in two. */
class VaultKeys(master: ByteArray) {
    init {
        require(master.size == SIZE) { "a vault key is $SIZE bytes, not ${master.size}" }
    }

    val data = SecretKeySpec(master, 0, 32, "AES")
    val mac = SecretKeySpec(master, 32, 32, "HmacSHA256")

    companion object {
        const val SIZE = 64

        fun random() = ByteArray(SIZE).also { SecureRandom().nextBytes(it) }
    }
}

/**
 * The vault's file format, made for photos and videos. AES-256 in CTR mode for the bytes, an
 * HMAC-SHA256 over each 64 KiB chunk, so a player seeks to any second without reading the rest.
 * A cut file is caught by the length and the header's MAC, not read as a shorter one.
 *
 * Header: `MRV1`, nonce (8), plain size (8), chunk size (4), and the HMAC of those 24 bytes (16).
 * Then one record per chunk: the 16-byte chunk MAC, then the ciphertext (64 KiB, the last one less).
 */
object Vault {
    const val CHUNK = 64 * 1024
    private const val magic = "MRV1"
    internal const val macSize = 16
    internal const val headerSize = 4 + 8 + 8 + 4 + macSize
    internal const val recordSize = macSize + CHUNK

    /**
     * Writes [src] into [dst] and returns its plain size. [dst] is a file the caller made for this:
     * on any error it is deleted, so a half file never looks valid.
     */
    fun encrypt(src: InputStream, dst: File, keys: VaultKeys): Long {
        val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val mac = Mac.getInstance("HmacSHA256").apply { init(keys.mac) }
        try {
            RandomAccessFile(dst, "rw").use { out ->
                // The size is only known at the end, so the header waits empty and is stamped last.
                out.setLength(0)
                out.write(ByteArray(headerSize))
                val plain = ByteArray(CHUNK)
                var total = 0L
                var index = 0L
                while (true) {
                    val n = readUpTo(src, plain)
                    if (n == 0) break
                    cipher.init(Cipher.ENCRYPT_MODE, keys.data, IvParameterSpec(iv(nonce, index)))
                    val ct = cipher.doFinal(plain, 0, n)
                    out.write(chunkMac(mac, index, ct, ct.size))
                    out.write(ct)
                    total += n
                    index++
                }
                val header = ByteArray(headerSize - macSize)
                magic.toByteArray().copyInto(header)
                nonce.copyInto(header, 4)
                putLong(header, 12, total)
                putInt(header, 20, CHUNK)
                out.seek(0)
                out.write(header)
                out.write(macOf(mac, header))
                return total
            }
        } catch (e: Throwable) {
            dst.delete()
            throw e
        }
    }

    /** Opens a vault file. Reads the header: a wrong key, a changed header or a cut file throws here. */
    fun open(file: File, keys: VaultKeys): VaultInput = VaultInput(file, keys)

    private fun readUpTo(src: InputStream, buf: ByteArray): Int {
        var got = 0
        while (got < buf.size) {
            val n = src.read(buf, got, buf.size - got)
            if (n < 0) break
            got += n
        }
        return got
    }

    /** A chunk starts on a block boundary, so its counter is the chunk number in 16-byte blocks. */
    internal fun iv(nonce: ByteArray, index: Long): ByteArray = ByteArray(16).also {
        nonce.copyInto(it)
        putLong(it, 8, index * (CHUNK / 16))
    }

    internal fun macOf(mac: Mac, bytes: ByteArray, from: Int = 0, len: Int = bytes.size): ByteArray {
        mac.reset()
        mac.update(bytes, from, len)
        return mac.doFinal().copyOf(macSize)
    }

    internal fun chunkMac(mac: Mac, index: Long, ct: ByteArray, len: Int): ByteArray {
        mac.reset()
        mac.update(longBytes(index))
        mac.update(ct, 0, len)
        return mac.doFinal().copyOf(macSize)
    }

    internal fun getLong(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    internal fun getInt(b: ByteArray, at: Int): Int {
        var v = 0
        for (i in 0 until 4) v = (v shl 8) or (b[at + i].toInt() and 0xFF)
        return v
    }

    internal fun putLong(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = (v ushr (56 - 8 * i)).toByte()
    }

    internal fun putInt(b: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) b[at + i] = (v ushr (24 - 8 * i)).toByte()
    }

    private fun longBytes(v: Long) = ByteArray(8).also { putLong(it, 0, v) }
}

/**
 * Reads a vault file at any position: one chunk is checked and decrypted per 64 KiB, so a video
 * seeks and a photo reads only what it shows. Not thread safe.
 */
class VaultInput(file: File, private val keys: VaultKeys) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val mac = Mac.getInstance("HmacSHA256").apply { init(keys.mac) }
    private val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    private val nonce = ByteArray(8)
    private val plain = ByteArray(Vault.CHUNK)
    private val ct = ByteArray(Vault.CHUNK)

    /** Plain size, from the header. */
    val size: Long

    init {
        val chunks: Long
        try {
            if (raf.length() < Vault.headerSize) throw VaultBroken("too short")
            val header = ByteArray(Vault.headerSize)
            raf.readFully(header)
            val stamped = header.copyOfRange(Vault.headerSize - Vault.macSize, Vault.headerSize)
            if (!MessageDigest.isEqual(stamped, Vault.macOf(mac, header, 0, Vault.headerSize - Vault.macSize))) {
                throw VaultBroken("header does not match")
            }
            if (String(header, 0, 4) != "MRV1") throw VaultBroken("not a vault file")
            header.copyInto(nonce, 0, 4, 12)
            size = Vault.getLong(header, 12)
            if (Vault.getInt(header, 20) != Vault.CHUNK) throw VaultBroken("chunk size")
            if (size < 0) throw VaultBroken("size")
            chunks = (size + Vault.CHUNK - 1) / Vault.CHUNK
            if (raf.length() != Vault.headerSize + chunks * Vault.macSize + size) throw VaultBroken("length")
        } catch (e: Throwable) {
            raf.close()
            throw e
        }
    }

    /** Fills [len] bytes at [position], or returns -1 at the end. */
    fun read(position: Long, dst: ByteArray, offset: Int, len: Int): Int {
        require(position >= 0 && len >= 0 && offset >= 0 && offset + len <= dst.size) { "bad read" }
        if (position >= size || len == 0) return if (position >= size) -1 else 0
        val want = minOf(len.toLong(), size - position).toInt()
        var done = 0
        while (done < want) {
            val at = position + done
            val index = at / Vault.CHUNK
            val inChunk = (at % Vault.CHUNK).toInt()
            val n = decryptChunk(index)
            val take = minOf(n - inChunk, want - done)
            plain.copyInto(dst, offset + done, inChunk, inChunk + take)
            done += take
        }
        return want
    }

    /** Checks and decrypts chunk [index] into [plain]. Returns its plain length. */
    private fun decryptChunk(index: Long): Int {
        val n = minOf(Vault.CHUNK.toLong(), size - index * Vault.CHUNK).toInt()
        raf.seek(Vault.headerSize + index * Vault.recordSize)
        val stored = ByteArray(Vault.macSize)
        raf.readFully(stored)
        raf.readFully(ct, 0, n)
        if (!MessageDigest.isEqual(stored, Vault.chunkMac(mac, index, ct, n))) throw VaultBroken("chunk $index")
        cipher.init(Cipher.DECRYPT_MODE, keys.data, IvParameterSpec(Vault.iv(nonce, index)))
        cipher.doFinal(ct, 0, n, plain, 0)
        return n
    }

    override fun close() = raf.close()
}
