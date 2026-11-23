package app.memoria

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The vault's key in the emulator's Keystore. Android deletes a key bound to the screen lock when the
 * lock is turned off, and every locked photo with it. Runs on an emulator like `BehaviorTest`, never on the phone.
 */
class VaultKeyTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).let { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }
        }

    // The emulator's PIN (Makefile `PIN`): put back, or the next cold boot cannot type it
    private val hadPin = shell("locksettings verify --old 1234").contains("successfully")

    @After fun restore() {
        VaultStore.dir(context).deleteRecursively()
        if (hadPin) shell("locksettings set-pin 1234") else shell("locksettings clear --old 1234")
    }

    @Test fun key_survivesTheScreenLockTurnedOff() {
        VaultStore.dir(context).deleteRecursively()
        if (!hadPin) shell("locksettings set-pin 1234")
        val made = VaultKey.create(context)

        shell("locksettings clear --old 1234")
        val opened = VaultKey.keys(context)
        assertNotNull("the vault key did not open after the screen lock was turned off", opened)
        assertArrayEquals(made.data.encoded, opened!!.data.encoded)
        assertArrayEquals(made.mac.encoded, opened.mac.encoded)
    }
}
