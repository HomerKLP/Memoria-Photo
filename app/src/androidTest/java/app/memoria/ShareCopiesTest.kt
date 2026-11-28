package app.memoria

import android.Manifest
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Share's plain copies of locked files (mockup "Preparing to share") go as soon as the app is on screen again, sent or not. */
@RunWith(AndroidJUnit4::class)
class ShareCopiesTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun copies_goWhenTheAppIsBack() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // no question from Android or the app may cover it, or the activity never comes back to resumed
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (p in listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)) {
            ui.grantRuntimePermission(context.packageName, p)
        }
        IndexControl.pause(context)
        context.getSharedPreferences("media", 0).edit().putBoolean("asked", true).commit()
        val copy = File(VaultStore.shareDir(context), "1/IMG_1.jpg")
        copy.parentFile!!.mkdirs()
        copy.writeText("plain")
        rule.setContent { MemoriaTheme { Surface { App(File(context.filesDir, "test")) } } }
        rule.waitUntil(5_000) { !copy.exists() } // the app's start, after Android killed it in the other app

        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED) // paused, as under the share sheet
        copy.parentFile!!.mkdirs()
        copy.writeText("plain")
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) // back on screen
        rule.waitUntil(5_000) { !copy.exists() }
    }
}
