package dev.phonerecall.ui

import android.content.res.Resources
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import dev.phonerecall.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Each place's coin medallion (res/drawable-nodpi, WebP). Places without one yet return null. */
val Place.emblem: Int?
    @DrawableRes get() = when (this) {
        Place.HOME -> R.drawable.emblem_olympus
        Place.ORACLE -> R.drawable.emblem_delphi
        Place.CALENDAR -> R.drawable.emblem_chronos
        Place.TASKS -> R.drawable.emblem_agora
        Place.CLOSET -> R.drawable.emblem_venus
        Place.AGENTS -> R.drawable.emblem_pantheon
        Place.VPS -> R.drawable.emblem_lemnos
        Place.WEATHER -> R.drawable.emblem_aeolia
        Place.DEVICES -> R.drawable.emblem_hestia
        Place.SETTINGS -> R.drawable.emblem_themis
        Place.PROPOSALS -> R.drawable.emblem_fates
        Place.VAULT -> R.drawable.emblem_calypso
        else -> null
    }

/**
 * Bundled art, decoded off the main thread and downsampled to the size it is drawn at, kept in a
 * small memory cache. No image library.
 */
object Art {
    private val cache = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cached(res: Int, px: Int): ImageBitmap? = cache.get("$res@$px")

    suspend fun load(resources: Resources, res: Int, px: Int): ImageBitmap? {
        cached(res, px)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeResource(resources, res, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
                BitmapFactory.decodeResource(resources, res, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()?.also { cache.put("$res@$px", it) }
        }
    }
}

/** A bundled image at `size` (its short side). Empty space until decoded: no shimmer, no fade. */
@Composable
fun ArtImage(
    @DrawableRes res: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    alpha: Float = 1f,
    sized: Boolean = true,
) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val resources = LocalContext.current.resources
    val bmp by produceState(Art.cached(res, px), res, px) { value = Art.load(resources, res, px) }
    val m = (if (sized) modifier.size(size) else modifier).clip(shape)
    val b = bmp
    if (b != null) Image(b, contentDescription = null, m, contentScale = contentScale, alignment = alignment, alpha = alpha) else Box(m)
}

/** A place's emblem as a round coin, or nothing if it has none. */
@Composable
fun Emblem(place: Place, size: Dp, modifier: Modifier = Modifier, alpha: Float = 1f) {
    place.emblem?.let { ArtImage(it, size, modifier, alpha = alpha) }
}

/** Athena's face: the gold Corinthian helmet coin. */
@Composable
fun AthenaAvatar(size: Dp, modifier: Modifier = Modifier) = ArtImage(R.drawable.athena_avatar, size, modifier)
