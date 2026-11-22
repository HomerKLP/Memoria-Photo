package app.memoria

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.FileNotFoundException
import java.io.IOException

/** A locked video for the player: read straight from the vault, and a seek decrypts only the chunks it lands on. */
class VaultDataSource(private val context: Context) : BaseDataSource(false) {
    private var input: VaultInput? = null
    private var at = 0L
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        close()
        // memoria-vault://1: the id is the host, since the URI has no path
        val n = (dataSpec.uri.host ?: dataSpec.uri.lastPathSegment)?.toLongOrNull() ?: throw IOException("no vault id in ${dataSpec.uri}")
        val file = VaultStore.file(context, n)
        if (!file.exists()) throw FileNotFoundException(dataSpec.uri.toString())
        val vin = Vault.open(file, vaultKeys ?: throw IOException("the locked folder is locked"))
        if (dataSpec.position > vin.size) {
            vin.close()
            return 0
        }
        input = vin
        at = dataSpec.position
        transferStarted(dataSpec)
        started = true
        return vin.size - dataSpec.position
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val vin = input ?: return -1
        val n = vin.read(at, buffer, offset, length)
        if (n > 0) {
            at += n
            bytesTransferred(n)
        }
        return n
    }

    override fun getUri(): Uri? = null

    override fun close() {
        input?.close()
        input = null
        if (started) {
            started = false
            transferEnded()
        }
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = VaultDataSource(context)
    }
}

/** The same bytes for MediaMetadataRetriever, which needs a file-like source for a video's poster. */
class VaultMediaSource(private val input: VaultInput) : MediaDataSource() {
    override fun getSize(): Long = input.size
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = input.read(position, buffer, offset, size)
    override fun close() = input.close()
}
