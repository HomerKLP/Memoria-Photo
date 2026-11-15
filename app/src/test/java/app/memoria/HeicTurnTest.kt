package app.memoria

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `blankHeicTurn` reads a HEIC's turn and mirror and clears them, so a decode of the stored pixels turns once. */
class HeicTurnTest {

    private val fixtures = File("src/androidTest/assets/fixtures")

    @Test fun everyFixture_givesItsTag_thenNothing() {
        for (n in 1..8) {
            val bytes = File(fixtures, "heic_o$n.heic").readBytes()
            assertEquals("heic_o$n", n, blankHeicTurn(bytes))
            assertEquals("heic_o$n again", 1, blankHeicTurn(bytes))
        }
    }

    @Test fun theMirrorAxis_isTheFilesOwn() {
        val axes = listOf(-1, 1, -1, 0, 0, -1, 1, -1)
        for (n in 1..8) {
            assertEquals("heic_o$n", axes[n - 1], blankHeic(File(fixtures, "heic_o$n.heic").readBytes()).second)
        }
    }

    @Test fun boxes_giveTheTable() {
        // irot value (or -1 for no box), imir value (-1 for none), the tag
        for ((rot, axis, tag) in listOf(
            Triple(0, -1, 1), Triple(0, 0, 4), Triple(0, 1, 2),
            Triple(1, -1, 8), Triple(1, 0, 5), Triple(1, 1, 7),
            Triple(2, -1, 3), Triple(2, 0, 2), Triple(2, 1, 4),
            Triple(3, -1, 6), Triple(3, 0, 7), Triple(3, 1, 5),
            Triple(-1, 1, 2), Triple(-1, 0, 4),
        )) {
            val p = props(rot, axis)
            val bytes = file(p, assoc = mapOf(1 to List(p.size) { it + 1 }), primary = 1)
            assertEquals("irot $rot imir $axis", tag, blankHeicTurn(bytes))
            assertTrue("irot $rot imir $axis still essential", essential(bytes).isEmpty())
            assertEquals("irot $rot imir $axis again", 1, blankHeicTurn(bytes))
        }
    }

    @Test fun thePrimaryItem_givesItsOwnBox() {
        val bytes = file(listOf("irot" to 3, "irot" to 1), assoc = mapOf(2 to listOf(1), 1 to listOf(2)), primary = 1)
        assertEquals(8, blankHeicTurn(bytes)) // item 1 holds irot 1 (90° counter-clockwise), not the first irot
    }

    @Test fun noEssentialProperty_isLeftFree() {
        val bytes = File(fixtures, "heic_o2.heic").readBytes()
        val before = essential(bytes)
        blankHeicTurn(bytes)
        val types = properties(bytes)
        for ((item, index) in essential(bytes)) assertTrue("item $item -> $index", types[index - 1] != "free")
        assertTrue("nothing was cleared", essential(bytes).size < before.size)
    }

    private fun props(rot: Int, axis: Int): List<Pair<String, Int>> =
        (if (rot >= 0) listOf("irot" to rot) else emptyList()) + (if (axis >= 0) listOf("imir" to axis) else emptyList())

    /** meta{pitm, iprp{ipco, ipma}}: [props] are the ipco children, [assoc] maps an item to its essential property indexes. */
    private fun file(props: List<Pair<String, Int>>, assoc: Map<Int, List<Int>>, primary: Int): ByteArray {
        val ipco = box("ipco", *props.map { box(it.first, byteArrayOf(it.second.toByte())) }.toTypedArray())
        val entries = assoc.entries.fold(ByteArray(0)) { a, (item, list) ->
            a + u16(item) + byteArrayOf(list.size.toByte()) +
                list.fold(ByteArray(0)) { s, i -> s + byteArrayOf((0x80 or i).toByte()) } // bit 7: essential
        }
        val ipma = box("ipma", byteArrayOf(0, 0, 0, 0), u32(assoc.size), entries)
        val pitm = box("pitm", byteArrayOf(0, 0, 0, 0), u16(primary))
        return box("meta", byteArrayOf(0, 0, 0, 0), pitm, box("iprp", ipco, ipma))
    }

    private fun box(type: String, vararg content: ByteArray): ByteArray {
        val body = content.fold(ByteArray(0)) { a, b -> a + b }
        return u32(8 + body.size) + type.toByteArray() + body
    }

    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** The ipco children's types, read the plain way. */
    private fun properties(bytes: ByteArray): List<String> {
        val start = startOf(bytes, "ipco")
        val end = start + u32At(bytes, start)
        val out = ArrayList<String>()
        var at = start + 8
        while (at + 8 <= end) {
            out += String(bytes, at + 4, 4)
            at += u32At(bytes, at)
        }
        return out
    }

    /** (item, property index) of every association the file marks essential, read the plain way. */
    private fun essential(bytes: ByteArray): List<Pair<Int, Int>> {
        val start = startOf(bytes, "ipma")
        val version = bytes[start + 8].toInt() and 0xff
        val wide = (bytes[start + 11].toInt() and 1) != 0
        var at = start + 12
        val entries = u32At(bytes, at)
        at += 4
        val out = ArrayList<Pair<Int, Int>>()
        repeat(entries) {
            val id = if (version == 0) u16At(bytes, at) else u32At(bytes, at)
            at += if (version == 0) 2 else 4
            val count = bytes[at].toInt() and 0xff
            at++
            repeat(count) {
                val v = if (wide) u16At(bytes, at) else bytes[at].toInt() and 0xff
                val isEssential = if (wide) v ushr 15 == 1 else (v and 0x80) != 0
                if (isEssential) out += id to (v and if (wide) 0x7fff else 0x7f)
                at += if (wide) 2 else 1
            }
        }
        return out
    }

    private fun startOf(bytes: ByteArray, type: String) = String(bytes, Charsets.ISO_8859_1).indexOf(type) - 4

    private fun u16At(b: ByteArray, at: Int) = ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)

    private fun u32At(b: ByteArray, at: Int) = ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or
        ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)
}
