package app.memoria

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Random

class VaultTest {
    private val keys = VaultKeys(ByteArray(64) { (it * 7).toByte() })

    private fun temp() = File.createTempFile("vault", ".bin").apply { deleteOnExit() }

    private fun vault(bytes: ByteArray) = temp().also { Vault.encrypt(ByteArrayInputStream(bytes), it, keys) }

    private fun readAll(f: File): ByteArray = Vault.open(f, keys).use { v ->
        val out = ByteArray(v.size.toInt())
        var at = 0
        while (at < out.size) {
            val n = v.read(at.toLong(), out, at, out.size - at)
            if (n < 0) break
            at += n
        }
        out.copyOf(at)
    }

    private fun bytes(n: Int, seed: Int) = ByteArray(n).also { Random(seed.toLong()).nextBytes(it) }

    @Test
    fun everySizeComesBackTheSame() {
        for (n in listOf(0, 1, 4096, Vault.CHUNK - 1, Vault.CHUNK, Vault.CHUNK + 1, 300_000)) {
            val plain = bytes(n, 7)
            assertArrayEquals("size $n", plain, readAll(vault(plain)))
        }
    }

    @Test
    fun aReadAtAnyPositionIsTheSameBytes() {
        val plain = bytes(300_000, 3)
        Vault.open(vault(plain), keys).use { v ->
            assertEquals(plain.size.toLong(), v.size)
            val r = Random(5)
            repeat(200) {
                val at = r.nextInt(plain.size)
                val len = 1 + r.nextInt(2000)
                val buf = ByteArray(len)
                val n = v.read(at.toLong(), buf, 0, len)
                val want = minOf(len, plain.size - at)
                assertEquals(want, n)
                assertArrayEquals(plain.copyOfRange(at, at + want), buf.copyOf(n))
            }
            assertEquals(-1, v.read(plain.size.toLong(), ByteArray(4), 0, 4))
        }
    }

    @Test
    fun aChangedByteFails() {
        val f = vault(ByteArray(200_000) { 1 })
        RandomAccessFile(f, "rw").use { raf ->
            raf.seek(Vault.headerSize + 40_000L)
            val b = raf.read()
            raf.seek(Vault.headerSize + 40_000L)
            raf.write(b xor 0xFF)
        }
        expectBroken { readAll(f) }
    }

    @Test
    fun aChangedHeaderFails() {
        val f = vault(ByteArray(1000))
        RandomAccessFile(f, "rw").use { raf ->
            raf.seek(12)
            val b = raf.read()
            raf.seek(12)
            raf.write(b xor 1)
        }
        expectBroken { Vault.open(f, keys) }
    }

    @Test
    fun aCutFileFails() {
        val f = vault(ByteArray(200_000))
        RandomAccessFile(f, "rw").use { it.setLength(it.length() - 100) }
        expectBroken { readAll(f) }
    }

    @Test
    fun theWrongKeyFails() {
        val f = vault(ByteArray(1000))
        expectBroken { readAll(f, VaultKeys(ByteArray(64) { 1 })) }
    }

    @Test
    fun aFileThatIsNotAVaultFails() {
        val f = temp()
        f.writeBytes(ByteArray(5000) { 42 })
        expectBroken { Vault.open(f, keys) }
    }

    @Test
    fun theSameBytesTwiceLookDifferent() {
        val plain = ByteArray(5000) { 9 }
        assertFalse(vault(plain).readBytes().contentEquals(vault(plain).readBytes()))
    }

    private fun readAll(f: File, keys: VaultKeys): ByteArray = Vault.open(f, keys).use { v ->
        val out = ByteArray(v.size.toInt())
        v.read(0, out, 0, out.size)
        out
    }

    private fun expectBroken(block: () -> Unit) {
        try {
            block()
            fail("no VaultBroken")
        } catch (expected: VaultBroken) {
        }
    }
}
