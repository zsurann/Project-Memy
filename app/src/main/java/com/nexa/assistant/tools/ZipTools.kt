package com.nexa.assistant.tools

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.nexa.assistant.documents.DocumentStore
import com.nexa.assistant.documents.DocxRenderer
import com.nexa.assistant.documents.ZipArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Strumenti per gli zip: vedere cosa c'è dentro, leggerne i file, estrarli. Le app trovate non vengono installate. */
fun zipTools(ctx: Context): List<MemyTool> {
    val zips = Zips(ctx)
    return listOf(ZipContentsTool(zips), ZipReadTool(zips), ZipExtractTool(zips))
}

internal class Zips(val ctx: Context) {
    private val files = Files(ctx)

    /**
     * Trova lo zip: tra quelli condivisi con Memy, poi tra i file del telefono. "esterno.zip/interno.zip" apre uno
     * zip contenuto in un altro (lo copia nella cache).
     */
    fun find(name: String): File? {
        val q = name.trim().removePrefix("/")
        val nested = Regex("(?i)\\.zip/").find(q)
        if (nested != null) {
            val outer = find(q.substring(0, nested.range.first + 4)) ?: return null
            val inner = q.substring(nested.range.last + 1)
            val target = File(shared(ctx), "${outer.nameWithoutExtension}__${inner.replace('/', '_')}")
            if (!target.exists()) {
                target.outputStream().use { out ->
                    ZipArchive.extract(outer, inner, freeBytes()) { out.let { NonClosing(it) } }
                }
            }
            return target
        }
        shared(ctx).listFiles()?.firstOrNull { it.name.equals(q, true) || it.nameWithoutExtension.equals(q, true) }?.let { return it }
        val f = files.find(q) ?: return null
        return f.file?.takeIf { it.canRead() } ?: f.uri?.let { uri ->
            File(shared(ctx), f.name).also { tmp -> ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } } }
        }
    }

    fun freeBytes(): Long = StatFs(Environment.getExternalStorageDirectory().path).availableBytes

    fun notFound(name: String) = ToolResult.error("Zip \"$name\" non trovato: chiedi all'utente di condividerlo con Memy (Condividi → Memy).")

    /** Nome e versione di un'app (.apk) dentro lo zip, senza installarla. */
    fun apkLabel(zip: File, path: String): String? = runCatching {
        val tmp = File(ctx.cacheDir, "apk-anteprima.apk")
        tmp.writeBytes(ZipArchive.read(zip, path, 300L shl 20))
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(tmp.path, 0) ?: return@runCatching null
        info.applicationInfo?.let { it.sourceDir = tmp.path; it.publicSourceDir = tmp.path }
        val label = info.applicationInfo?.loadLabel(pm)?.toString() ?: info.packageName
        tmp.delete()
        "$label ${info.versionName.orEmpty()}".trim()
    }.getOrNull()

    companion object {
        /** Dove finiscono gli zip condivisi con Memy. */
        fun shared(ctx: Context) = File(ctx.cacheDir, "condivisi").apply { mkdirs() }

        /** Riepilogo di uno zip, usato quando viene condiviso e dallo strumento zip_contents. */
        fun describe(zips: Zips?, file: File, max: Int = 120): String {
            val info = ZipArchive.list(file)
            val files = info.files
            return buildString {
                append("Zip \"${file.name}\": ${files.size} file, ${mb(info.totalSize)} una volta estratto.")
                if (info.encrypted) append(" Alcuni file sono protetti da password: non si possono leggere né estrarre.")
                append('\n')
                files.take(max).forEach { append("- ${it.path} (${mb(it.size)})\n") }
                if (files.size > max) append("… altri ${files.size - max} file\n")
                val apks = files.filter { it.path.endsWith(".apk", true) || it.path.endsWith(".xapk", true) || it.path.endsWith(".apks", true) }
                if (apks.isNotEmpty()) {
                    append("App Android trovate (Memy non le installa):\n")
                    apks.take(5).forEach { a -> append("- ${a.path}: ${zips?.apkLabel(file, a.path) ?: "app"}\n") }
                }
                val nested = files.count { it.path.endsWith(".zip", true) }
                if (nested > 0) append("Contiene $nested zip: si aprono con il nome \"${file.name}/<percorso>.zip\".\n")
            }
        }

        fun mb(bytes: Long) = if (bytes < 1 shl 20) "${bytes / 1024 + 1} KB" else "%.1f MB".format(bytes / 1048576.0)
    }
}

/** Lo stream del file di destinazione resta aperto: lo chiude chi lo ha creato. */
private class NonClosing(out: java.io.OutputStream) : java.io.FilterOutputStream(out) {
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun close() = flush()
}

class ZipContentsTool internal constructor(private val zips: Zips) : MemyTool {
    override val name = "zip_contents"
    override val description = "Elenca cosa c'è in uno zip condiviso con Memy o presente sul telefono (file, dimensioni, app trovate)."
    override val properties = mapOf("file" to prop("string", "Nome dello zip; per uno zip dentro un altro: \"esterno.zip/cartella/interno.zip\""))
    override val required = listOf("file")
    override suspend fun execute(input: ToolInput): ToolResult = withContext(Dispatchers.IO) {
        val name = input.requireString("file")
        val f = zips.find(name) ?: return@withContext zips.notFound(name)
        ToolResult.ok(Zips.describe(zips, f))
    }
}

class ZipReadTool internal constructor(private val zips: Zips) : MemyTool {
    override val name = "zip_read"
    override val description =
        "Legge un file dentro uno zip senza estrarlo: testi, Word, PDF (una pagina alla volta come immagine) e immagini."
    override val properties = mapOf(
        "file" to prop("string", "Nome dello zip"),
        "entry" to prop("string", "Percorso del file dentro lo zip, come in zip_contents"),
        "page" to prop("integer", "Per i PDF: la pagina, da 1"),
    )
    override val required = listOf("file", "entry")
    override suspend fun execute(input: ToolInput): ToolResult = withContext(Dispatchers.IO) {
        val name = input.requireString("file")
        val entry = input.requireString("entry")
        val zip = zips.find(name) ?: return@withContext zips.notFound(name)
        val ext = entry.substringAfterLast('.', "").lowercase()
        try {
            when (ext) {
                "pdf" -> {
                    val tmp = File(Zips.shared(zips.ctx), "lettura.pdf").apply { writeBytes(ZipArchive.read(zip, entry, 200L shl 20)) }
                    pdfPageResult(ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY), input.int("page") ?: 1, entry)
                }
                "docx" -> ToolResult.ok("Testo di \"$entry\":\n" + DocxRenderer.extractText(ZipArchive.read(zip, entry, 100L shl 20)).take(30_000))
                "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> {
                    val bytes = ZipArchive.read(zip, entry, 100L shl 20)
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    var sample = 1
                    while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= 1024) sample *= 2
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?: return@withContext ToolResult.error("Immagine non leggibile.")
                    val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
                    ToolResult("Immagine \"$entry\" dallo zip.", imageJpeg = jpeg)
                }
                "zip" -> ToolResult.ok(Zips.describe(zips, zips.find("$name/$entry") ?: return@withContext zips.notFound("$name/$entry")))
                "apk", "xapk", "apks" -> ToolResult.ok("\"$entry\" è un'app Android: ${zips.apkLabel(zip, entry) ?: "nome non leggibile"}. Memy non installa app.")
                else -> {
                    val bytes = ZipArchive.read(zip, entry, 5L shl 20)
                    val text = String(bytes)
                    if (text.count { it == '�' || it.code < 9 } > text.length / 20) ToolResult.error("\"$entry\" non è un file di testo leggibile.")
                    else ToolResult.ok("Contenuto di \"$entry\":\n" + text.take(30_000))
                }
            }
        } catch (e: ZipArchive.ZipException) {
            ToolResult.error(e.message ?: "Lettura non riuscita")
        }
    }
}

class ZipExtractTool internal constructor(private val zips: Zips) : MemyTool {
    override val name = "zip_extract"
    override val description =
        "Estrae uno zip (tutto, oppure un solo file o cartella) in Download/Memy/<nome dello zip>, visibile da qualsiasi app di file. " +
            "Per estrarre uno zip contenuto in un altro usa file=\"esterno.zip/interno.zip\". Le app trovate vengono solo estratte, mai installate."
    override val properties = mapOf(
        "file" to prop("string", "Nome dello zip"),
        "entry" to prop("string", "Solo questo file o cartella (facoltativo; senza, estrae tutto)"),
    )
    override val required = listOf("file")
    override suspend fun execute(input: ToolInput): ToolResult = withContext(Dispatchers.IO) {
        val name = input.requireString("file")
        val zip = zips.find(name) ?: return@withContext zips.notFound(name)
        val folder = uniqueFolder(zip.nameWithoutExtension.substringAfterLast("__"))
        try {
            val res = ZipArchive.extract(zip, input.string("entry"), zips.freeBytes()) { path -> open(folder, path) }
            val skipped = if (res.skipped.isEmpty()) "" else " Saltati ${res.skipped.size}: " + res.skipped.take(5).joinToString("; ") + "."
            ToolResult.ok("Estratti ${res.written} file (${Zips.mb(res.bytes)}) in Download/${DocumentStore.FOLDER}/$folder.$skipped")
        } catch (e: ZipArchive.ZipException) {
            ToolResult.error("Estrazione interrotta: ${e.message}. I file già estratti restano in Download/${DocumentStore.FOLDER}/$folder.")
        }
    }

    private val base = "${Environment.DIRECTORY_DOWNLOADS}/${DocumentStore.FOLDER}"

    /** Una cartella nuova per ogni estrazione: non si sovrascrive mai niente. */
    private fun uniqueFolder(name: String): String {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "zip" }
        var candidate = clean
        var n = 2
        while (exists(candidate)) candidate = "$clean (${n++})"
        return candidate
    }

    private fun exists(folder: String): Boolean = runCatching {
        zips.ctx.contentResolver.query(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$base/$folder/%"), null,
        )?.use { it.count > 0 } ?: false
    }.getOrDefault(false) || File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "${DocumentStore.FOLDER}/$folder").exists()

    private fun open(folder: String, path: String): java.io.OutputStream {
        val dir = path.substringBeforeLast('/', "")
        val fileName = path.substringAfterLast('/')
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, listOf(base, folder, dir).filter { it.isNotEmpty() }.joinToString("/"))
        }
        val resolver = zips.ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw ZipArchive.ZipException("impossibile creare $path")
        return resolver.openOutputStream(uri)!!
    }
}
