package dev.phonerecall.lemnos

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.FactRow
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date

val ROOTS = listOf("home", "volume", "storagebox", "opt")
private val IMAGE = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
private val BINARY = setOf("zip", "gz", "tgz", "xz", "zst", "tar", "mp4", "mkv", "mov", "mp3", "flac", "pdf", "db", "sqlite", "bin", "so", "jar", "apk", "iso", "woff2", "pyc")

private fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"
private fun parent(p: String) = p.substringBeforeLast('/', "")

/** What a file sheet shows. */
sealed class Preview {
    data object Loading : Preview()
    data class Text(val text: String) : Preview()
    data class Picture(val bitmap: ImageBitmap) : Preview()
    data class None(val why: String) : Preview()
}

/**
 * Files on Lemnos: home, the volume, the storage box and /opt. Secrets are invisible (the server's deny
 * list). Delete moves to the root's trash folder after a second tap; uploads never overwrite.
 */
@Composable
fun FilesTab(hub: LemnosHub, gutter: Dp) {
    val ctx = LocalContext.current
    var root by rememberSaveable { mutableStateOf("home") }
    var path by rememberSaveable { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<FileEntry>?>(null) }
    var open by remember { mutableStateOf<FileEntry?>(null) }
    var preview by remember { mutableStateOf<Preview>(Preview.Loading) }
    var note by remember { mutableStateOf<String?>(null) }
    val reload = { hub.runAsync("Files") { rows = Gateway.ls(root, path) } }
    LaunchedEffect(root, path) { rows = null; reload() }

    val uploader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val (name, size) = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null
        } ?: ("upload" to -1L)
        val dest = join(path, name.replace('/', '_'))
        hub.runAsync("Upload") { Gateway.upload(root, dest, size) { ctx.contentResolver.openInputStream(uri)!! }; note = "Uploaded $name"; reload() }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val f = open ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val p = join(path, f.name)
        hub.runAsync("Download") { ctx.contentResolver.openOutputStream(uri)!!.use { Gateway.download(root, p, it) }; note = "Saved ${f.name}" }
    }

    LaunchedEffect(open) {
        val f = open ?: return@LaunchedEffect
        preview = Preview.Loading
        val ext = f.name.substringAfterLast('.', "").lowercase()
        val p = join(path, f.name)
        hub.runAsync("Preview") {
            preview = when {
                ext in IMAGE && f.size <= 12L * 1024 * 1024 -> {
                    val out = ByteArrayOutputStream(); Gateway.download(root, p, out, 12L * 1024 * 1024)
                    val bytes = out.toByteArray()
                    val opts = BitmapFactory.Options().apply { inSampleSize = if (bytes.size > 3_000_000) 2 else 1 }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { Preview.Picture(it.asImageBitmap()) } ?: Preview.None("Not a picture Zeus can show.")
                }
                ext in BINARY -> Preview.None("No preview for .$ext files. Download it instead.")
                f.size > 1024 * 1024 -> Preview.None("Too big to show here (${Fmt.bytes(f.size)}). Download it instead.")
                else -> runCatching { Preview.Text(Gateway.readText(root, p)) }.getOrElse { Preview.None("Not a text file.") }
            }
        }
    }

    val f = open
    if (f != null) {
        FileSheet(
            root, join(path, f.name), f, preview, gutter, onBack = { open = null },
            onDownload = { saver.launch(f.name) },
            onRename = { to -> hub.runAsync("Rename") { Gateway.rename(root, join(path, f.name), join(path, to)); open = null; reload() } },
            onMove = { toRoot, to -> hub.runAsync("Move") { Gateway.move(root, join(path, f.name), toRoot, to); open = null; reload() } },
            onDelete = { hub.runAsync("Delete") { note = Gateway.delete(root, join(path, f.name)).replaceFirstChar { it.uppercase() }; open = null; reload() } },
        )
        return
    }
    FilesContent(
        root, path, rows, note, gutter,
        onRoot = { root = it; path = "" }, onUp = { path = parent(path) },
        onOpen = { e -> if (e.dir) path = join(path, e.name) else open = e },
        onMkdir = { name -> hub.runAsync("New folder") { Gateway.mkdir(root, join(path, name)); reload() } },
        onUpload = { uploader.launch(arrayOf("*/*")) },
    )
}

@Composable
fun FilesContent(
    root: String, path: String, rows: List<FileEntry>?, note: String?, gutter: Dp,
    onRoot: (String) -> Unit, onUp: () -> Unit, onOpen: (FileEntry) -> Unit, onMkdir: (String) -> Unit, onUpload: () -> Unit,
) {
    val c = Athena.colors
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = gutter - Space.s)) {
            for (r in ROOTS) TextAction(r.replaceFirstChar { it.uppercase() }, { onRoot(r) }, emphasis = r == root)
        }
        Row(Modifier.fillMaxWidth().padding(start = gutter, end = gutter - Space.s), verticalAlignment = Alignment.CenterVertically) {
            Text("/" + path, style = Mono, color = c.ink2, maxLines = 1, modifier = Modifier.weight(1f))
            if (path.isNotEmpty()) TextAction("Up", onUp)
            TextAction("Folder", { naming = !naming })
            TextAction("Upload", onUpload, emphasis = true)
        }
        if (naming) Row(Modifier.padding(horizontal = gutter), verticalAlignment = Alignment.CenterVertically) {
            UnderlineField(name, { name = it.replace('/', '_') }, "New folder name", Modifier.weight(1f), onSubmit = { if (name.isNotBlank()) { onMkdir(name); name = ""; naming = false } })
            TextAction("Make", { if (name.isNotBlank()) { onMkdir(name); name = ""; naming = false } }, enabled = name.isNotBlank())
        }
        note?.let { Line(it, Type.caption, c.ok, Modifier.padding(horizontal = gutter)) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = gutter)) {
            when {
                rows == null -> Line("Reading", Type.small, c.ink3, Modifier.padding(vertical = Space.l))
                rows.isEmpty() -> Line("Empty folder.", Type.small, c.ink3, Modifier.padding(vertical = Space.l))
                else -> for (e in rows) ListRow(
                    e.name + if (e.dir) "/" else "",
                    if (e.dir) null else "${Fmt.bytes(e.size)}  ·  ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(e.mtime * 1000))}",
                    titleStyle = Type.small, onClick = { onOpen(e) },
                ) { if (e.link) StatusWord("Link", Tone.QUIET) }
            }
            Spacer(Modifier.height(Space.xxl))
        }
    }
}

@Composable
fun FileSheet(
    root: String, path: String, f: FileEntry, preview: Preview, gutter: Dp,
    onBack: () -> Unit, onDownload: () -> Unit, onRename: (String) -> Unit, onMove: (String, String) -> Unit, onDelete: () -> Unit,
) {
    val c = Athena.colors
    var mode by remember(path) { mutableStateOf<String?>(null) }
    var field by remember(path) { mutableStateOf("") }
    var toRoot by remember(path) { mutableStateOf(root) }
    var confirmDelete by remember(path) { mutableStateOf(false) }
    LaunchedEffect(confirmDelete) { if (confirmDelete) { kotlinx.coroutines.delay(4000); confirmDelete = false } }
    Column(Modifier.fillMaxSize().padding(horizontal = gutter)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextAction("Back", onBack)
            Spacer(Modifier.width(Space.s))
            Line(f.name, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, Modifier.weight(1f), maxLines = 1)
        }
        FactRow("Where", "$root:/$path")
        FactRow("Size", Fmt.bytes(f.size) + "  ·  " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(f.mtime * 1000)))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextAction("Download", onDownload, emphasis = true)
            TextAction("Rename", { mode = "rename"; field = f.name })
            TextAction("Move", { mode = "move"; field = path })
            TextAction(if (confirmDelete) "Tap again: move to trash" else "Delete", { if (confirmDelete) onDelete() else confirmDelete = true })
        }
        when (mode) {
            "rename" -> Row(verticalAlignment = Alignment.CenterVertically) {
                UnderlineField(field, { field = it.replace('/', '_') }, "New name", Modifier.weight(1f))
                TextAction("Rename", { if (field.isNotBlank() && field != f.name) onRename(field) }, emphasis = true)
            }
            "move" -> Column {
                Row(Modifier.horizontalScroll(rememberScrollState())) { for (r in ROOTS) TextAction(r, { toRoot = r }, emphasis = r == toRoot) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UnderlineField(field, { field = it }, "Path in $toRoot, with the file name", Modifier.weight(1f), style = Type.small)
                    TextAction("Move", { if (field.isNotBlank()) onMove(toRoot, field.trim('/')) }, emphasis = true)
                }
            }
        }
        Kicker("Preview", Modifier.padding(top = Space.m, bottom = Space.xs))
        val box = Modifier.weight(1f).fillMaxWidth().background(c.block(dev.phonerecall.ui.Tint.SERVER))
        when (preview) {
            Preview.Loading -> Line("Reading", Type.small, c.ink3)
            is Preview.None -> Line(preview.why, Type.small, c.ink3)
            is Preview.Picture -> Image(preview.bitmap, contentDescription = f.name, modifier = box.heightIn(min = 120.dp), contentScale = ContentScale.Fit)
            is Preview.Text -> SelectionContainer(box.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(Space.s)) {
                Text(preview.text.take(200_000), style = Mono, color = c.ink)
            }
        }
        Spacer(Modifier.height(Space.s))
    }
}
