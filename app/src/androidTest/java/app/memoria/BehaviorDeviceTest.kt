package app.memoria

import android.net.Uri
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.test.junit4.createComposeRule

/** The `BehaviorTest` cases that need a real device: MediaStore, the Media management switch, or a clock Robolectric fakes. */
class BehaviorDeviceTest {

    @get:Rule val rule = createComposeRule()

    private fun tile(id: Long) = Tile(id, Uri.parse("content://media/external/images/media/$id"), 0L, null)

    private fun videoTile(id: Long) = Tile(id, Uri.parse("content://media/external/video/media/$id"), 0L, 42_000L)

    // Android's delete confirm answered after the app was made again (dark mode, font size): the keys must go anyway
    @Test fun lock_answeredAfterRestart_dropsTheKeys() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("vault", 0).edit().putBoolean("introSeen", true).commit()
        VaultStore.dir(context).run { deleteRecursively(); mkdirs() }
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "behaviortest-${System.nanoTime()}.jpg")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/BehaviorTest")
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use {
            android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
        }
        val photo = Tile(android.content.ContentUris.parseId(uri), uri, 0L, null)
        var asked: Int? = null
        val registry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I,
                options: androidx.core.app.ActivityOptionsCompat?,
            ) { asked = requestCode }
        }
        val owner = object : androidx.activity.result.ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val restore = androidx.compose.ui.test.junit4.StateRestorationTester(rule)
        try {
            restore.setContent {
                MemoriaTheme { Surface {
                    androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) {
                        val lock = rememberVaultLock()
                        androidx.compose.material3.TextButton({ lock(listOf(photo)) }) { Text("Lock it") }
                    }
                } }
            }
            rule.onNodeWithText("Lock it").performClick()
            rule.onNodeWithText("Lock").performClick()
            rule.waitUntil(5_000) { asked != null }
            restore.emulateSavedInstanceStateRestore()
            rule.runOnIdle { registry.dispatchResult(asked!!, android.app.Activity.RESULT_CANCELED, android.content.Intent()) }
            rule.waitForIdle()
            assertNull(vaultKeys)
        } finally {
            context.contentResolver.delete(uri, null, null)
            VaultStore.dir(context).deleteRecursively()
            vaultKeys = null
        }
    }

    // Cancel while the files are encrypted: no copy stays, Android is never asked, the photo stays in the gallery (mockup "Locking")
    @Test fun lock_cancelWhileLocking_leavesTheGalleryAsIs() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("vault", 0).edit().putBoolean("introSeen", true).commit()
        VaultStore.dir(context).run { deleteRecursively(); mkdirs() }
        vaultKeys = VaultKeys(VaultKeys.random())
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "behaviortest-${System.nanoTime()}.jpg")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/BehaviorTest")
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        // 64 MiB: encrypting it takes far longer than the two taps below
        context.contentResolver.openOutputStream(uri)!!.use { out -> val mb = ByteArray(1 shl 20); repeat(64) { out.write(mb) } }
        val photo = Tile(android.content.ContentUris.parseId(uri), uri, 0L, null)
        var asked: Int? = null
        val registry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I,
                options: androidx.core.app.ActivityOptionsCompat?,
            ) { asked = requestCode }
        }
        val owner = object : androidx.activity.result.ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        try {
            rule.setContent {
                MemoriaTheme { Surface {
                    androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) {
                        val lock = rememberVaultLock()
                        androidx.compose.material3.TextButton({ lock(listOf(photo)) }) { Text("Lock it") }
                    }
                } }
            }
            rule.onNodeWithText("Lock it").performClick()
            rule.onNodeWithText("Lock 1 photo?").assertIsDisplayed()
            rule.mainClock.autoAdvance = false
            rule.onNodeWithText("Lock").performClick()
            rule.mainClock.advanceTimeBy(400)
            rule.onNodeWithText("Locking 1 photo").assertIsDisplayed()
            rule.onNodeWithText("Cancel").performClick()
            rule.mainClock.autoAdvance = true
            rule.waitUntil(10_000) { vaultKeys == null } // the lock has ended: a folder that is not open drops its keys
            rule.onNodeWithText("Locking 1 photo").assertDoesNotExist()
            assertNull(asked)
            assertEquals(emptyList<String>(), VaultStore.dir(context).list()!!.toList())
            context.contentResolver.query(uri, null, null, null, null)!!.use { assertEquals(1, it.count) }
        } finally {
            rule.mainClock.autoAdvance = true
            context.contentResolver.delete(uri, null, null)
            VaultStore.dir(context).deleteRecursively()
            vaultKeys = null
        }
    }

    // A slow lock shows how far it is, then closes and Android asks (mockup "Locking")
    @Test fun lock_slow_showsBytesThenAndroidAsks() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("vault", 0).edit().putBoolean("introSeen", true).commit()
        VaultStore.dir(context).run { deleteRecursively(); mkdirs() }
        vaultKeys = VaultKeys(VaultKeys.random())
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "behaviortest-${System.nanoTime()}.jpg")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/BehaviorTest")
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { out -> val mb = ByteArray(1 shl 20); repeat(64) { out.write(mb) } }
        val photo = Tile(android.content.ContentUris.parseId(uri), uri, 0L, null)
        var asked: Int? = null
        val registry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I,
                options: androidx.core.app.ActivityOptionsCompat?,
            ) { asked = requestCode }
        }
        val owner = object : androidx.activity.result.ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        try {
            rule.setContent {
                MemoriaTheme { Surface {
                    androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) {
                        val lock = rememberVaultLock()
                        androidx.compose.material3.TextButton({ lock(listOf(photo)) }) { Text("Lock it") }
                    }
                } }
            }
            rule.onNodeWithText("Lock it").performClick()
            rule.onNodeWithText("Lock 1 photo?").assertIsDisplayed()
            rule.mainClock.autoAdvance = false
            rule.onNodeWithText("Lock").performClick()
            rule.mainClock.advanceTimeBy(400)
            rule.onNodeWithText("Locking 1 photo").assertIsDisplayed()
            // the total comes from the IO thread in real time, and the frozen clock needs a frame to show it
            rule.waitUntil(5_000) {
                rule.mainClock.advanceTimeByFrame()
                rule.onAllNodesWithText("of 67.1 MB", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            rule.mainClock.autoAdvance = true
            rule.waitUntil(10_000) { asked != null }
            rule.onNodeWithText("Locking 1 photo").assertDoesNotExist()
            rule.runOnIdle { registry.dispatchResult(asked!!, android.app.Activity.RESULT_CANCELED, android.content.Intent()) }
            rule.waitUntil(10_000) { vaultKeys == null }
        } finally {
            rule.mainClock.autoAdvance = true
            context.contentResolver.delete(uri, null, null)
            VaultStore.dir(context).deleteRecursively()
            vaultKeys = null
        }
    }

    // Cancel while moving out: the file stays locked, and no copy, whole or half, is left in the gallery (mockup "Moving out")
    @Test fun moveOut_cancelWhileMoving_keepsItLocked() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        VaultStore.dir(context).run { deleteRecursively(); mkdirs() }
        vaultKeys = VaultKeys(VaultKeys.random())
        val name = "behaviortest-${System.nanoTime()}.jpg"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/BehaviorTest")
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        // 64 MiB: moving it out takes far longer than the taps below
        context.contentResolver.openOutputStream(uri)!!.use { out -> val mb = ByteArray(1 shl 20); repeat(64) { out.write(mb) } }
        val inGallery = android.os.Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(name))
            putInt(android.provider.MediaStore.QUERY_ARG_MATCH_PENDING, android.provider.MediaStore.MATCH_INCLUDE)
        }
        try {
            // Locked, and Android said yes: the vault holds it, the gallery does not
            kotlinx.coroutines.runBlocking { VaultStore.lock(context, listOf(Tile(android.content.ContentUris.parseId(uri), uri, 0L, null))) }
            context.contentResolver.delete(uri, null, null)
            rule.setContent { MemoriaTheme { LockedScreen(onBack = {}) } }
            rule.waitUntil(5_000) { rule.onAllNodesWithTag("vault-1").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("vault-1").performTouchInput { longClick() }
            rule.onNodeWithText("Move out").performClick()
            rule.onNodeWithText("Move 1 photo out?").assertIsDisplayed()
            rule.mainClock.autoAdvance = false
            rule.onAllNodesWithText("Move out").onLast().performClick()
            rule.mainClock.advanceTimeBy(400)
            rule.onNodeWithText("Moving 1 photo out").assertIsDisplayed()
            // the bytes come from the IO thread once the move holds the vault; the frozen clock needs a frame to show them
            rule.waitUntil(5_000) {
                rule.mainClock.advanceTimeByFrame()
                rule.onAllNodesWithText("of 67.1 MB", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("Cancel").performClick()
            rule.mainClock.autoAdvance = true
            kotlinx.coroutines.runBlocking { VaultStore.sweep(context) } // waits until the move lets go of the vault
            rule.onNodeWithText("Moving 1 photo out").assertDoesNotExist()
            assertEquals(1, kotlinx.coroutines.runBlocking { VaultStore.items(context) }.size)
            context.contentResolver.query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, inGallery, null)!!.use { assertEquals(0, it.count) }
        } finally {
            rule.mainClock.autoAdvance = true
            context.contentResolver.query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(android.provider.MediaStore.MediaColumns._ID), inGallery, null)?.use { c ->
                while (c.moveToNext()) context.contentResolver.delete(android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)), null, null)
            }
            VaultStore.dir(context).deleteRecursively()
            vaultKeys = null
        }
    }

    // Share in the folder: no question, then both files go to Android's share sheet as plain copies (mockup "Preparing to share")
    @Test fun share_twoItems_handsBothToTheSheet() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        VaultStore.dir(context).run { deleteRecursively(); mkdirs() }
        vaultKeys = VaultKeys(VaultKeys.random())
        val files = listOf("behaviortest-a-${System.nanoTime()}.jpg", "behaviortest-b-${System.nanoTime()}.jpg")
            .associateWith { n -> ByteArray(100_000) { (it * 7 + n.length).toByte() }.toList() }
        val uris = files.map { (name, bytes) ->
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/BehaviorTest")
            }
            resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                .also { u -> resolver.openOutputStream(u)!!.use { it.write(bytes.toByteArray()) } }
        }
        // Android's share sheet is caught, not opened
        var sheet: android.content.Intent? = null
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action != android.content.Intent.ACTION_CHOOSER) return null
                sheet = intent
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            kotlinx.coroutines.runBlocking { VaultStore.lock(context, uris.map { Tile(android.content.ContentUris.parseId(it), it, 0L, null) }) }
            uris.forEach { resolver.delete(it, null, null) }
            rule.setContent { MemoriaTheme { LockedScreen(onBack = {}) } }
            rule.waitUntil(5_000) { rule.onAllNodesWithTag("vault-2").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("vault-1").performTouchInput { longClick() }
            rule.onNodeWithTag("vault-2").performClick()
            rule.onNodeWithText("Share").performClick()
            rule.waitUntil(5_000) { sheet != null }

            val send = sheet!!.getParcelableExtra(android.content.Intent.EXTRA_INTENT, android.content.Intent::class.java)!!
            assertEquals(android.content.Intent.ACTION_SEND_MULTIPLE, send.action)
            assertTrue(send.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val got = send.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, Uri::class.java)!!.associate { u ->
                val name = resolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)!!.use { it.moveToFirst(); it.getString(0) }
                name to resolver.openInputStream(u)!!.use { it.readBytes() }.toList()
            }
            assertEquals(files, got)
        } finally {
            instrumentation.removeMonitor(monitor)
            VaultStore.dir(context).deleteRecursively()
            VaultStore.shareDir(context).deleteRecursively()
            vaultKeys = null
        }
    }

    @Test fun closeWhenEmpty_firesOnlyAfterALoadFindsNothing() {
        var value by mutableStateOf<Any?>(null)
        var closes = 0
        rule.setContent {
            val v = value
            MemoriaTheme { Surface { CloseWhenEmpty(v, v is Collection<*> && v.isEmpty()) { closes++ } } }
        }

        rule.runOnIdle { assertEquals(0, closes) } // still loading: nothing closes
        rule.runOnIdle { value = emptyList<Int>() }
        rule.waitUntil(5_000) { closes == 1 }
        assertEquals(1, closes)
    }

    // --- Media management (mockup "Media management") ------------------------------------------

    private fun mediaSwitch(on: Boolean) {
        val ui = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val pkg = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.packageName
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("appops set $pkg MANAGE_MEDIA ${if (on) "allow" else "default"}")).use { it.readBytes() }
    }

    private fun mediaAskReset() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("media", 0).edit().clear().commit()

    // First open: asked once. Not now closes it for good; the Settings row stays the way in
    @Test fun mediaAsk_notNow_closesForGood() {
        mediaAskReset()
        rule.setContent {
            MemoriaTheme { Surface {
                val ask = rememberMediaAsk()
                androidx.compose.material3.TextButton({ ask() }) { Text("Open app") }
            } }
        }

        rule.onNodeWithText("Open app").performClick()
        rule.onNodeWithText("Use Memoria's own questions?").assertIsDisplayed()
        rule.onNodeWithText("Not now").performClick()
        rule.onNodeWithText("Use Memoria's own questions?").assertDoesNotExist()
        rule.onNodeWithText("Open app").performClick()
        rule.onNodeWithText("Use Memoria's own questions?").assertDoesNotExist()
    }

    // Open settings goes to Android's page; back in the app, nothing else shows
    @Test fun mediaAsk_openSettings_opensAndroidsPageOnce() {
        mediaAskReset()
        var opened = 0
        rule.setContent {
            MemoriaTheme { Surface {
                val ask = rememberMediaAsk(openSettings = { opened++ })
                androidx.compose.material3.TextButton({ ask() }) { Text("Open app") }
            } }
        }

        rule.onNodeWithText("Open app").performClick()
        rule.onNodeWithText("Open settings").performClick()
        assertEquals(1, opened)
        rule.onNodeWithText("Use Memoria's own questions?").assertDoesNotExist()
        rule.onNodeWithText("Open app").performClick()
        rule.onNodeWithText("Use Memoria's own questions?").assertDoesNotExist()
    }

    // Not shown if the switch is already on
    @Test fun mediaAsk_switchOn_asksNothing() {
        mediaAskReset()
        rule.setContent {
            MemoriaTheme { Surface {
                val ask = rememberMediaAsk()
                androidx.compose.material3.TextButton({ ask() }) { Text("Open app") }
            } }
        }
        try {
            mediaSwitch(on = true)
            rule.onNodeWithText("Open app").performClick()
            rule.onNodeWithText("Use Memoria's own questions?").assertDoesNotExist()
            mediaSwitch(on = false)
            rule.onNodeWithText("Open app").performClick()
            rule.onNodeWithText("Use Memoria's own questions?").assertIsDisplayed()
        } finally {
            mediaSwitch(on = false)
        }
    }

    // Switch on: Memoria asks before the change (mockup "Delete, with Media management on"); Cancel changes nothing
    @Test fun mediaOn_asksBeforeTheChange_cancelKeepsIt() {
        var ran = 0
        rule.setContent {
            MemoriaTheme { Surface {
                val confirm = rememberMediaConfirm()
                androidx.compose.material3.TextButton({ confirm(trashQuestion((1L..6L).map { tile(it) })) { ran++ } }) { Text("Delete them") }
            } }
        }
        try {
            mediaSwitch(on = true)
            rule.onNodeWithText("Delete them").performClick()
            rule.onNodeWithText("Move 6 photos to trash?").assertIsDisplayed()
            rule.onNodeWithText("They stay in Trash for 30 days, then they are deleted for good.").assertIsDisplayed()
            rule.onNodeWithText("Cancel").performClick()
            rule.onNodeWithText("Move 6 photos to trash?").assertDoesNotExist()
            assertEquals(0, ran)
            rule.onNodeWithText("Delete them").performClick()
            rule.onNodeWithText("Move to trash").performClick()
            assertEquals(1, ran)
        } finally {
            mediaSwitch(on = false)
        }
    }

    // Switch off: Android's own dialog stays and Memoria adds none, so nobody answers twice
    @Test fun mediaOff_goesStraightToAndroid() {
        mediaSwitch(on = false)
        var ran = 0
        rule.setContent {
            MemoriaTheme { Surface {
                val confirm = rememberMediaConfirm()
                androidx.compose.material3.TextButton({ confirm(trashQuestion((1L..6L).map { tile(it) })) { ran++ } }) { Text("Delete them") }
            } }
        }

        rule.onNodeWithText("Delete them").performClick()
        assertEquals(1, ran)
        rule.onNodeWithText("Move 6 photos to trash?").assertDoesNotExist()
    }

    // Delete in Album, Favorites, Place, Person and Videos select (rememberPick): ours first, photos and videos mixed read "items"
    @Test fun mediaOn_pickDelete_asksOurs() {
        rule.setContent {
            MemoriaTheme { Surface {
                val pick = rememberPick()
                androidx.compose.material3.TextButton({ pick.delete(listOf(tile(1), videoTile(2))) }) { Text("Delete them") }
            } }
        }
        try {
            mediaSwitch(on = true)
            rule.onNodeWithText("Delete them").performClick()
            rule.onNodeWithText("Move 2 items to trash?").assertIsDisplayed()
            rule.onNodeWithText("Cancel").performClick()
            rule.onNodeWithText("Move 2 items to trash?").assertDoesNotExist()
        } finally {
            mediaSwitch(on = false)
        }
    }

    // Trash's Empty, Restore and Delete ask ours (mockup "Delete for good", "Restore"); every answer here is Cancel
    @Test fun mediaOn_trash_emptyRestoreDelete_askOurs() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "trashtest-${System.nanoTime()}.jpg")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/TrashTest")
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use {
            android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
        }
        val trashed = context.contentResolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
        val id = android.content.ContentUris.parseId(uri)
        assertEquals("trashed rows", 1, trashed)
        assertTrue("in trash()", trash(context).any { it.tile.id == id })
        rule.setContent { MemoriaTheme { Surface { TrashScreen(onBack = {}) } } }
        try {
            mediaSwitch(on = true)
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("tile-$id").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Empty").performClick()
            rule.onNodeWithText("for good?", substring = true).assertIsDisplayed() // the emulator's trash may hold more than ours
            rule.onNodeWithText("Cancel").performClick()
            rule.onNodeWithText("for good?", substring = true).assertDoesNotExist()

            rule.onNodeWithTag("tile-$id").performTouchInput { longClick() }
            rule.onNodeWithText("Restore").performClick()
            rule.onNodeWithText("Restore 1 photo?").assertIsDisplayed()
            rule.onNodeWithText("It goes back to the folder it came from.").assertIsDisplayed()
            rule.onNodeWithText("Cancel").performClick()
            rule.onNodeWithText("Delete").performClick()
            rule.onNodeWithText("Delete 1 photo for good?").assertIsDisplayed()
            rule.onNodeWithText("It can't be restored.").assertIsDisplayed()
            rule.onNodeWithText("Cancel").performClick()
            rule.onNodeWithText("Delete 1 photo for good?").assertDoesNotExist()
            rule.onNodeWithTag("tile-$id").assertExists()
        } finally {
            mediaSwitch(on = false)
            // trashed rows are skipped by default, even by id
            context.contentResolver.delete(uri, android.os.Bundle().apply { putInt(android.provider.MediaStore.QUERY_ARG_MATCH_TRASHED, android.provider.MediaStore.MATCH_INCLUDE) })
        }
    }

    @Test fun settings_updateUpToDate_checkFires_rowDoesNotOpenPage() {
        var check = false
        var page = false
        rule.setContent {
            MemoriaTheme { Surface {
                SettingsContent(
                    charging = false, allowed = false, hidden = 2, version = "0.2.0",
                    update = Updates.State(Updates.Status.UpToDate, checkedAt = System.currentTimeMillis()), onCheck = { check = true }, onReleasePage = { page = true },
                )
            } }
        }

        rule.onNodeWithText("Up to date · checked today").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Updates").performClick()
        assertFalse(page)
        rule.onNodeWithText("Check").performClick()
        assertTrue(check)
    }
}
