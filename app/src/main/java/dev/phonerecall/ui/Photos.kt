package dev.phonerecall.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Local photos only (the closet folder). Decoded off the main thread, downsampled to the size shown,
 * EXIF-rotated, kept in a small memory cache. No network library: nothing here can leave the phone.
 */
object Photos {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val gate = Semaphore(3)

    fun cached(path: String, px: Int): ImageBitmap? = cache.get("$path@$px")

    suspend fun load(path: String, px: Int): ImageBitmap? {
        val key = "$path@$px"
        cache.get(key)?.let { return it }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
                    val raw = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
                    val rotated = rotate(raw, path)
                    rotated.asImageBitmap().also { cache.put(key, it) }
                }.getOrNull()
            }
        }
    }

    private fun rotate(b: Bitmap, path: String): Bitmap {
        val deg = when (runCatching { ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) return b
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
    }
}

/** A cropped local photo. Shows the block's raised tone until it has decoded; no shimmer, no fade. */
@Composable
fun LocalPhoto(path: String, px: Int, shape: Shape, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val bmp by produceState(Photos.cached(path, px), path, px) { value = Photos.load(path, px) }
    val b = bmp
    if (b != null) Image(b, contentDescription = null, modifier.clip(shape), contentScale = contentScale)
    else Box(modifier.clip(shape).background(Athena.colors.raised))
}
