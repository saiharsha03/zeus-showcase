package dev.phonerecall

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Dp
import dev.phonerecall.brain.SealedShelf
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.Type
import dev.phonerecall.vault.AegisItem
import dev.phonerecall.vault.AegisScope
import dev.phonerecall.vault.DropForm
import dev.phonerecall.vault.ItemKind
import dev.phonerecall.vault.Opened
import dev.phonerecall.vault.OpenedItem
import dev.phonerecall.vault.SealedContent
import dev.phonerecall.vault.SealedItem
import dev.phonerecall.vault.Shelf
import dev.phonerecall.vault.ShieldedContent
import dev.phonerecall.vault.VaultContent
import dev.phonerecall.vault.VaultLogRow
import dev.phonerecall.vault.mergedLog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Calypso's two shelves to PNG (app/build/shots/vault-*.png), cover and inner, light and dark, locked and open, on made-up data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VaultShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val now = System.currentTimeMillis()
    private val sealedItems = listOf(
        SealedItem("a1", ItemKind.SECRET, "Hetzner token (rotated 10-19)", "Atlas", now - 3_600_000, 96),
        SealedItem("a2", ItemKind.FILE, "Amex statement, October", "Iris", now - 86_400_000, 182_000),
        SealedItem("a3", ItemKind.NOTE, "Passport renewal", "The owner", now - 5 * 86_400_000L, 60),
    )
    private val sealedLog = listOf(
        VaultLogRow(now - 60_000, "calypso", "The owner", "opened", "Passport renewal", ""),
        VaultLogRow(now - 3_600_000, "calypso", "Atlas", "deposited", "Hetzner token (rotated 10-19)", "through Lemnos"),
        VaultLogRow(now - 86_400_000, "calypso", "Iris", "deposited", "Amex statement, October", "through Lemnos"),
        VaultLogRow(now - 2 * 86_400_000L, "calypso", "The owner", "moved to Aegis", "Delivery address", ""),
    )
    private val shieldedItems = listOf(
        AegisItem("delivery_address", "Home, for orders and forms", AegisScope.AGENTS, false, now, 4),
        AegisItem("delta_skymiles", "Frequent flyer number", AegisScope.AGENTS, false, now, 1),
        AegisItem("home_wifi", "Network and password", AegisScope.ATHENA, true, now, 0),
        AegisItem("sizes", "Shirt L, jeans 34x30, shoes 9", AegisScope.ATHENA, false, now, 2),
    )
    private val shieldedLog = listOf(
        VaultLogRow(now - 600_000, "aegis", "Athena", "used", "delivery_address", "Costco order for Saturday"),
        VaultLogRow(now - 3_000_000, "aegis", "Athena's circle", "refused", "home_wifi", "setting up the smart plug"),
        VaultLogRow(now - 7_200_000, "aegis", "Delphi", "used", "delta_skymiles", "Thanksgiving flight search"),
        VaultLogRow(now - 86_400_000 - 60_000, "aegis", "The owner", "revoked agents", "home_wifi", ""),
    )

    @Composable private fun Vault(dark: Boolean, gutter: Dp, wide: Boolean, shelf: Shelf, unlocked: Boolean) = AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) {
            VaultContent(
                gutter, wide, shelf, {}, unlocked, null, {},
                log = mergedLog(sealedLog, shieldedLog), otherLocked = false,
                locked = {
                    if (shelf == Shelf.SEALED) DropForm({ _, _, _, _ -> }, {})
                    else Line("Athena and the workers can still use what you share with them. Every use is logged here.", Type.small, Athena.colors.ink3, Modifier.padding(top = Space.l))
                },
            ) {
                if (shelf == Shelf.SEALED) SealedShelf(
                    keySet = true, masked = "sk-ant-api03-••••••••Xq4f", savedAt = now - 9 * 86_400_000L, revealed = false, {}, {}, {}, {},
                    sealed = { SealedContent(sealedItems, mapOf("a3" to OpenedItem(sealedItems[2], Opened(null, "Renew by March 2027. Photos at the pharmacy on Main St.".toByteArray()))), null, {}, {}, {}, {}, {}, {}) },
                ) else ShieldedContent(shieldedItems, mapOf("sizes" to "Shirt L, jeans 34x30, shoes 9"), {}, {}, {}, {}, {}, {}, { _, _, _, _ -> })
            }
        }
    }

    private fun shot(name: String, dark: Boolean, wide: Boolean, shelf: Shelf, unlocked: Boolean) {
        compose.setContent { Vault(dark, if (wide) Space.gutterWide else Space.gutterNarrow, wide, shelf, unlocked) }
        save("vault-$name")
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h2300dp-xhdpi")
    fun sealedCoverDark() = shot("sealed-cover-dark", true, false, Shelf.SEALED, true)

    @Test @Config(sdk = [35], qualifiers = "w384dp-h2300dp-xhdpi")
    fun sealedCoverLight() = shot("sealed-cover-light", false, false, Shelf.SEALED, true)

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1500dp-xhdpi")
    fun sealedInnerDark() = shot("sealed-inner-dark", true, true, Shelf.SEALED, true)

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1500dp-xhdpi")
    fun sealedInnerLight() = shot("sealed-inner-light", false, true, Shelf.SEALED, true)

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun sealedLockedCoverDark() = shot("sealed-locked-cover-dark", true, false, Shelf.SEALED, false)

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun sealedLockedCoverLight() = shot("sealed-locked-cover-light", false, false, Shelf.SEALED, false)

    @Test @Config(sdk = [35], qualifiers = "w780dp-h900dp-xhdpi")
    fun sealedLockedInnerDark() = shot("sealed-locked-inner-dark", true, true, Shelf.SEALED, false)

    @Test @Config(sdk = [35], qualifiers = "w780dp-h900dp-xhdpi")
    fun sealedLockedInnerLight() = shot("sealed-locked-inner-light", false, true, Shelf.SEALED, false)

    @Test @Config(sdk = [35], qualifiers = "w384dp-h2100dp-xhdpi")
    fun shieldedCoverDark() = shot("shielded-cover-dark", true, false, Shelf.SHIELDED, true)

    @Test @Config(sdk = [35], qualifiers = "w384dp-h2100dp-xhdpi")
    fun shieldedCoverLight() = shot("shielded-cover-light", false, false, Shelf.SHIELDED, true)

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1300dp-xhdpi")
    fun shieldedInnerDark() = shot("shielded-inner-dark", true, true, Shelf.SHIELDED, true)

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1300dp-xhdpi")
    fun shieldedInnerLight() = shot("shielded-inner-light", false, true, Shelf.SHIELDED, true)

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun shieldedLockedCoverDark() = shot("shielded-locked-cover-dark", true, false, Shelf.SHIELDED, false)

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun shieldedLockedCoverLight() = shot("shielded-locked-cover-light", false, false, Shelf.SHIELDED, false)

    @Test @Config(sdk = [35], qualifiers = "w780dp-h900dp-xhdpi")
    fun shieldedLockedInnerDark() = shot("shielded-locked-inner-dark", true, true, Shelf.SHIELDED, false)

    @Test @Config(sdk = [35], qualifiers = "w780dp-h900dp-xhdpi")
    fun shieldedLockedInnerLight() = shot("shielded-locked-inner-light", false, true, Shelf.SHIELDED, false)
}
