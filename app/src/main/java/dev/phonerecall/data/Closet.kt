package dev.phonerecall.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import org.json.JSONObject
import java.io.File

/** Closet groups, in display order. */
enum class ClosetGroup(val label: String) {
    TOPS("Tops"), BOTTOMS("Bottoms"), SHOES("Shoes"), OUTERWEAR("Outerwear"), ACCESSORIES("Accessories"), CARE("Care"),
}

@Immutable
data class ClosetItem(
    val id: String,
    val group: ClosetGroup,
    val category: String,
    val kind: String,
    val brandGuess: String,
    val colour: String,
    val description: String,
    val occasions: Set<String>,
    val formality: Int,
    val temp: String,
    val shade: String,
    val size: String,
    val archived: Boolean,
    val notes: String,
    /** His own photos (absolute paths that exist): the source of truth for condition and colour. */
    val photos: List<String>,
    /** The generated studio render (products/<id>.jpg), if there is one. A view, not a record. */
    val render: String? = null,
    /** The render's background, ARGB, from products.json (near-white or black). */
    val renderBg: Int? = null,
) {
    /** "Cornflower blue polo": what the card and the grid call it. */
    val name: String get() = "$colour ${kind.lowercase().replace("ziptop", "quarter-zip").replace("campshirt", "camp shirt").replace("dressshirt", "dress shirt").replace("longsleeve", "long-sleeve tee")}"

    /** What to show: the render if there is one, else his own photo. */
    val picture: String? get() = render ?: photos.firstOrNull()
}

@Immutable
data class ClosetSnapshot(val imported: Boolean = false, val items: List<ClosetItem> = emptyList(), val loaded: Boolean = false)

/**
 * Reads the closet Iris catalogued from app storage on the phone. Nothing is bundled in the APK,
 * nothing is uploaded. Call on IO.
 *
 * Layout under the closet root: closet-index.csv, products.json, and the images under static/ or
 * closet/static/ (items/ = his photos, products/ = renders). The root is internal storage
 * (files/closet) or, as a fallback, the external files dir.
 */
class ClosetSource(private val context: Context) {
    private fun internal() = File(context.filesDir, "closet")
    private fun external(): File? = context.getExternalFilesDir(null)?.let { File(it, "closet") }

    fun load(): ClosetSnapshot {
        val inner = internal()
        val outer = external()
        val innerCsv = File(inner, "closet-index.csv")
        val outerCsv = outer?.let { File(it, "closet-index.csv") }
        Log.i(TAG, "internal csv: exists=${innerCsv.exists()} canRead=${innerCsv.canRead()} at ${innerCsv.absolutePath}")
        if (outerCsv != null) Log.i(TAG, "external csv: exists=${outerCsv.exists()} canRead=${outerCsv.canRead()} at ${outerCsv.absolutePath}")
        // Files pushed by adb into the external folder: copy them in once, so later reads never depend on it.
        if (!innerCsv.isFile && outer != null && outerCsv != null && outerCsv.isFile) {
            try {
                outer.copyRecursively(inner, overwrite = false)
                Log.i(TAG, "copied external closet into internal storage")
            } catch (e: Exception) {
                Log.w(TAG, "copy from external failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        val root = when {
            innerCsv.isFile -> inner
            outerCsv != null && outerCsv.isFile && outerCsv.canRead() -> outer
            else -> {
                Log.w(TAG, "no readable closet-index.csv in internal or external storage")
                return ClosetSnapshot(imported = false, loaded = true)
            }
        }
        return parse(root)
    }

    companion object {
        private const val TAG = "Athena-Closet"

        /** Parses a closet folder. No Context needed, so the screenshot test can use the laptop copy. */
        fun parse(root: File): ClosetSnapshot {
            val text = try {
                File(root, "closet-index.csv").readText(Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "reading closet-index.csv failed: ${e.javaClass.simpleName}: ${e.message}")
                return ClosetSnapshot(imported = false, loaded = true)
            }
            val rows = parseCsv(text)
            if (rows.isEmpty()) return ClosetSnapshot(imported = false, loaded = true)
            val bases = listOf(File(root, "static"), File(root, "closet/static"))
            fun resolve(rel: String) = bases.map { File(it, rel) }.firstOrNull { it.isFile }?.absolutePath
            val manifest = readManifest(root)
            val header = rows.first().map { it.trim().lowercase() }
            fun col(name: String) = header.indexOfFirst { it.startsWith(name) }
            val iId = col("id"); val iCat = col("category"); val iKind = col("kind"); val iBrand = col("brand")
            val iColour = col("colour"); val iDesc = col("description"); val iOcc = col("occasions"); val iForm = col("formality")
            val iTemp = col("temp"); val iShade = col("shade"); val iSize = col("size"); val iArch = col("archived")
            val iNotes = col("notes"); val iPhotos = col("photos")
            var renders = 0
            val items = rows.drop(1).mapNotNull { r ->
                fun at(i: Int) = r.getOrNull(i)?.trim().orEmpty()
                val id = at(iId).ifEmpty { return@mapNotNull null }
                val category = at(iCat).lowercase()
                val kind = at(iKind)
                // His own photos: the CSV's primary and duplicate shots (not the product renders).
                val own = at(iPhotos).split('|').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { p ->
                    val i = p.indexOf(':')
                    val tag = if (i > 0) p.substring(0, i) else "primary"
                    val rel = if (i > 0) p.substring(i + 1) else p
                    if (tag == "product" || rel.startsWith("products/")) null else resolve(rel)?.let { tag to it }
                }.sortedBy { (tag, _) -> if (tag == "primary") 0 else 1 }.map { it.second }
                val m = manifest[id]
                val render = (m?.first?.let { resolve(it) } ?: resolve("products/$id.jpg"))
                    ?.takeIf { m?.third != "own-photo" }
                if (render != null) renders++
                ClosetItem(
                    id = id, group = groupOf(category, kind), category = category, kind = kind,
                    brandGuess = at(iBrand), colour = at(iColour), description = at(iDesc),
                    occasions = at(iOcc).split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
                    formality = at(iForm).toIntOrNull() ?: 1, temp = at(iTemp).lowercase(), shade = at(iShade).lowercase(),
                    size = at(iSize), archived = at(iArch) == "1", notes = cleanNotes(at(iNotes)),
                    // An "own-photo" render is his photo standing in: shown as a photo.
                    photos = if (m?.third == "own-photo") (listOfNotNull(m.first.let { resolve(it) }) + own).distinct() else own,
                    render = render, renderBg = if (render != null) m?.second else null,
                )
            }
            Log.i(TAG, "loaded ${items.size} items, $renders renders, manifest ${manifest.size}, from ${root.absolutePath}")
            return ClosetSnapshot(imported = true, items = items, loaded = true)
        }

        /** products.json: id to (src, bg ARGB, kind). Looked for at the root, then in data/ folders. */
        private fun readManifest(root: File): Map<String, Triple<String, Int?, String>> {
            val f = listOf(File(root, "products.json"), File(root, "closet/data/products.json"), File(root, "data/products.json")).firstOrNull { it.isFile }
                ?: return emptyMap()
            return try {
                val o = JSONObject(f.readText(Charsets.UTF_8))
                o.keys().asSequence().associateWith { id ->
                    val e = o.getJSONObject(id)
                    Triple(e.optString("src", "products/$id.jpg"), parseHex(e.optString("bg")), e.optString("kind", "render"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "products.json unreadable: ${e.javaClass.simpleName}: ${e.message}")
                emptyMap()
            }
        }

        /** "#fdfdfd" to opaque ARGB. */
        private fun parseHex(s: String?): Int? {
            val h = s?.trim()?.removePrefix("#") ?: return null
            if (h.length != 6) return null
            return h.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
        }

        private fun groupOf(category: String, kind: String): ClosetGroup = when {
            kind.equals("Puffer", true) || kind.equals("Jacket", true) || kind.equals("Vest", true) -> ClosetGroup.OUTERWEAR
            category == "top" -> ClosetGroup.TOPS
            category == "bottom" || category == "shorts" -> ClosetGroup.BOTTOMS
            category == "footwear" -> ClosetGroup.SHOES
            category == "skincare" || category == "fragrance" -> ClosetGroup.CARE
            else -> ClosetGroup.ACCESSORIES
        }

        /** Notes are Iris's; shown as plain sentences. Dashes become commas so the copy rules hold. */
        private fun cleanNotes(s: String) = s.replace(" — ", ", ").replace("—", ", ").replace(" | ", "\n")

        /** RFC 4180-ish: quoted fields, doubled quotes, newlines inside quotes. */
        fun parseCsv(text: String): List<List<String>> {
            val rows = ArrayList<List<String>>()
            var row = ArrayList<String>()
            val f = StringBuilder()
            var q = false
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                if (q) {
                    if (ch == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') { f.append('"'); i++ } else q = false
                    } else f.append(ch)
                } else when (ch) {
                    '"' -> q = true
                    ',' -> { row.add(f.toString()); f.setLength(0) }
                    '\r' -> Unit
                    '\n' -> { row.add(f.toString()); f.setLength(0); rows.add(row); row = ArrayList() }
                    else -> f.append(ch)
                }
                i++
            }
            if (f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); rows.add(row) }
            return rows.filter { r -> r.any { it.isNotBlank() } }
        }
    }
}
