package dev.phonerecall

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.phonerecall.music.MusicItem
import dev.phonerecall.music.MusicPageContent
import dev.phonerecall.music.MusicSettings
import dev.phonerecall.music.TrackStat
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.Space
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Themis, Music with the Skips section, light and dark (app/build/shots/music-skips-*.png). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicSkipShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val skipped = listOf(
        TrackStat("spotify:track:1", "Butta Bomma", "Armaan Malik", plays = 1, skips = 4),
        TrackStat("spotify:track:2", "A song with a rather long title that runs past the edge (Remastered 2019)", "Some Band feat. Another Artist and Friends", skips = 3),
        TrackStat("spotify:track:3", "Kesariya", "Arijit Singh", skips = 3),
    )

    private fun page(dark: Boolean, learn: Boolean = true, list: List<TrackStat> = skipped) {
        compose.setContent {
            AthenaTheme(dark) {
                Box(Modifier.fillMaxSize().background(Athena.colors.surface)) {
                    MusicPageContent(
                        MusicSettings(connected = true, drivePlaylist = MusicItem("Drive Mix", "spotify:playlist:1"), spotifyInstalled = true, learnSkips = learn),
                        null, null, Space.gutterNarrow, {}, skipped = list,
                    )
                }
            }
        }
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun skipsDark() { page(true); save("music-skips-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun skipsLight() { page(false); save("music-skips-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun skipsEmptyLight() { page(false, list = emptyList()); save("music-skips-empty-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun skipsOffDark() { page(true, learn = false); save("music-skips-off-dark") }
}
