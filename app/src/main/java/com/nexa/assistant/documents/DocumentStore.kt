package com.nexa.assistant.documents

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Formati che Memy sa creare. */
enum class DocFormat(val ext: String, val mime: String, val label: String) {
    PDF("pdf", "application/pdf", "PDF"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word"),
    TXT("txt", "text/plain", "testo"),
    MD("md", "text/markdown", "Markdown"),
    HTML("html", "text/html", "pagina web"),
    CSV("csv", "text/csv", "tabella CSV");

    companion object {
        fun of(name: String): DocFormat? = entries.firstOrNull { it.ext == name.substringAfterLast('.', "").lowercase() }
    }
}

/**
 * I documenti di Memy: si salvano in Download/Memy (visibili da qualsiasi app di file) e, accanto, Memy tiene
 * una copia del contenuto in markdown, così può rileggerli e modificarli anche quando sono PDF o Word.
 */
class DocumentStore(private val context: Context) {

    data class Saved(val name: String, val uri: Uri, val path: String, val pages: Int?)

    private val sources = File(context.filesDir, "documenti").apply { mkdirs() }
    private fun sourceFile(fileName: String) = File(sources, "$fileName.md")
    private fun metaFile(fileName: String) = File(sources, "$fileName.title")

    val folder: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)

    /** Il contenuto originale (markdown) di un documento creato da Memy. */
    fun source(fileName: String): String? = sourceFile(fileName).takeIf { it.exists() }?.readText()
    fun title(fileName: String): String? = metaFile(fileName).takeIf { it.exists() }?.readText()?.ifBlank { null }

    fun render(format: DocFormat, title: String?, content: String): ByteArray {
        if (format == DocFormat.CSV) return content.trimEnd().plus("\n").toByteArray()
        if (format == DocFormat.MD) return ((title?.let { "# $it\n\n" } ?: "") + content.trimEnd() + "\n").toByteArray()
        // Se il contenuto ripete il titolo come prima intestazione, non lo si stampa due volte.
        val blocks = Markdown.parse(content).let { b ->
            val first = b.firstOrNull()
            if (title != null && first is Block.Heading && Markdown.plain(first.text).equals(title.trim(), true)) b.drop(1) else b
        }
        return when (format) {
            DocFormat.PDF -> PdfRenderer.render(title, blocks)
            DocFormat.DOCX -> DocxRenderer.render(title, blocks)
            DocFormat.HTML -> HtmlRenderer.render(title, blocks).toByteArray()
            else -> Markdown.toPlainText(title, blocks).toByteArray()
        }
    }

    /** Crea (o sovrascrive) il documento in Download/Memy. */
    fun save(fileName: String, format: DocFormat, title: String?, content: String): Saved {
        val bytes = render(format, title, content)
        val uri = existing(fileName) ?: insert(fileName, format)
        // Se c'era già un file con quel nome che Memy non vede, Android lo rinomina ("Nome (1).pdf"): si usa il nome vero.
        val name = displayName(uri) ?: fileName
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        sourceFile(name).writeText(content)
        metaFile(name).writeText(title.orEmpty())
        val pages = if (format == DocFormat.PDF) Regex("/Type\\s*/Page[^s]").findAll(String(bytes, Charsets.ISO_8859_1)).count() else null
        return Saved(name, uri, "Download/$FOLDER/$name", pages)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun insert(fileName: String, format: DocFormat): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, format.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(collection(), values) ?: error("impossibile creare il file")
    }

    private fun collection(): Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** Il file in Download/Memy con questo nome, se c'è. */
    fun existing(fileName: String): Uri? = runCatching {
        context.contentResolver.query(
            collection(), arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(fileName, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/"), null,
        )?.use { c -> if (c.moveToFirst()) Uri.withAppendedPath(collection(), c.getLong(0).toString()) else null }
    }.getOrNull()

    data class Entry(val name: String, val size: Long, val modified: Long, val uri: Uri)

    /** I documenti in Download/Memy, dal più recente. */
    fun list(): List<Entry> = runCatching {
        context.contentResolver.query(
            collection(),
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/"),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            buildList {
                while (c.moveToNext()) add(Entry(c.getString(1), c.getLong(2), c.getLong(3) * 1000, Uri.withAppendedPath(collection(), c.getLong(0).toString())))
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    fun read(uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    fun delete(fileName: String): Boolean {
        val uri = existing(fileName) ?: return false
        val ok = context.contentResolver.delete(uri, null, null) > 0
        sourceFile(fileName).delete(); metaFile(fileName).delete()
        return ok
    }

    companion object {
        const val FOLDER = "Memy"
    }
}
