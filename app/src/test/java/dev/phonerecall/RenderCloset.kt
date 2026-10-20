package dev.phonerecall

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.phonerecall.closet.ClosetGridPreview
import dev.phonerecall.data.ClosetGroup
import dev.phonerecall.data.ClosetSource
import dev.phonerecall.ui.Space
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the closet grid from the laptop copy of the closet (Athena/_data/closet) to app/build/shots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderCloset {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1400dp-xhdpi")
    fun grid() {
        val root = File(System.getProperty("user.dir"), "../_data/closet")
        org.junit.Assume.assumeTrue("the closet is laptop-only (_data, not in git)", root.isDirectory)
        val snap = ClosetSource.parse(root)
        assertTrue("closet parsed", snap.imported && snap.items.isNotEmpty())
        val tops = snap.items.filter { it.group == ClosetGroup.TOPS && !it.archived }
        // The three items without a render, first, so the fallback shows in the shot.
        val noRender = tops.filter { it.render == null }
        val shown = (noRender + tops.filter { it.render != null }).take(24)
        println("closet: ${snap.items.size} items, ${snap.items.count { it.render != null }} with renders, no render: ${snap.items.filter { it.render == null }.map { it.id }}")
        compose.setContent { ClosetGridPreview(shown, Space.gutterNarrow) }
        // Photos decode on IO; give them a moment, letting Compose apply each result.
        repeat(30) { Thread.sleep(150); compose.waitForIdle() }
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "closet.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
