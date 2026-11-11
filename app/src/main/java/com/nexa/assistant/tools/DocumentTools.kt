package com.nexa.assistant.tools

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.nexa.assistant.documents.DocFormat
import com.nexa.assistant.documents.DocumentStore
import com.nexa.assistant.documents.DocxRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Strumenti per creare, leggere, modificare, aprire e condividere documenti (PDF, Word, testo, tabelle…). */
fun documentTools(ctx: Context): List<MemyTool> {
    val files = Files(ctx)
    return listOf(
        CreateDocumentTool(files), EditDocumentTool(files), ReadDocumentTool(files), ListDocumentsTool(files),
        OpenDocumentTool(ctx, files), ShareDocumentTool(ctx, files), DeleteDocumentTool(files),
    )
}

/** Un file trovato: un documento di Memy (in Download/Memy) oppure un file qualsiasi del telefono. */
internal class Found(val name: String, val uri: Uri?, val file: File?, val memy: Boolean)

internal class Files(val ctx: Context) {
    val store = DocumentStore(ctx)

    fun allFilesAccess() = com.nexa.assistant.access.Capabilities.allFilesAccess()

    private val roots: List<File>
        get() = listOf(
            Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS, "Desktop", Environment.DIRECTORY_DCIM,
        ).map { Environment.getExternalStoragePublicDirectory(it) } + Environment.getExternalStorageDirectory()

    /** Cerca un file per nome (o percorso): prima tra i documenti di Memy, poi nel telefono se c'è l'accesso ai file. */
    fun find(query: String): Found? {
        val q = query.trim().removePrefix("/")
        val name = q.substringAfterLast('/')
        store.existing(name)?.let { return Found(name, it, File(store.folder, name), memy = true) }
        store.list().firstOrNull { it.name.equals(name, true) || it.name.substringBeforeLast('.').equals(name, true) }
            ?.let { return Found(it.name, it.uri, File(store.folder, it.name), memy = true) }
        if (!allFilesAccess()) return null
        if ('/' in q) {
            File(Environment.getExternalStorageDirectory(), q).takeIf { it.isFile }?.let { return Found(it.name, null, it, memy = false) }
        }
        for (root in roots) {
            root.walkTopDown().maxDepth(if (root == Environment.getExternalStorageDirectory()) 2 else 4)
                .onEnter { !it.name.startsWith(".") && it.name != "Android" }
                .firstOrNull { it.isFile && (it.name.equals(name, true) || it.nameWithoutExtension.equals(name, true)) }
                ?.let { return Found(it.name, null, it, memy = false) }
        }
        return null
    }

    fun bytes(f: Found): ByteArray = f.uri?.let { store.read(it) } ?: f.file!!.readBytes()

    fun shareUri(f: Found): Uri = f.uri ?: FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f.file!!)

    fun notFound(name: String) = ToolResult.error(
        "File \"$name\" non trovato tra i documenti di Memy." +
            if (allFilesAccess()) " Non è nemmeno in Download, Documenti o DCIM: chiedi all'utente dove si trova."
            else " Per cercare negli altri file del telefono l'utente deve attivare \"File e documenti\" in Memy → Impostazioni → Poteri."
    )
}

private fun fileName(raw: String, format: DocFormat): String {
    val base = raw.trim().replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { "Documento" }
    return if (base.lowercase().endsWith(".${format.ext}")) base else "${base.substringBeforeLast(".${format.ext}")}.${format.ext}"
}

private const val CONTENT_HELP =
    "Contenuto in markdown semplice: '# Sezione', '## Sottosezione', paragrafi separati da una riga vuota, " +
        "'- punto' o '1. punto' per gli elenchi, tabelle con '| Colonna | Colonna |' (la prima riga è l'intestazione), " +
        "'---' per una linea, **grassetto** e *corsivo*. Per il formato csv scrivi direttamente le righe separate da virgole."

class CreateDocumentTool internal constructor(private val files: Files) : MemyTool {
    override val name = "create_document"
    override val description =
        "Crea un documento e lo salva nella cartella Download/Memy del telefono: PDF, Word (docx), testo, markdown, " +
            "pagina web (html) o tabella CSV. Usalo per lettere, relazioni, liste, preventivi, ricette, riassunti, tabelle. " +
            "Scrivi tu il contenuto completo e curato. Poi, se utile, aprilo con open_document o condividilo con share_document."
    override val properties = mapOf(
        "file_name" to prop("string", "Nome del file senza estensione, chiaro e in italiano (es. 'Lettera di dimissioni')"),
        "format" to enumProp("Formato del file", DocFormat.entries.map { it.ext }),
        "title" to prop("string", "Titolo mostrato in cima al documento (facoltativo)"),
        "content" to prop("string", CONTENT_HELP),
    )
    override val required = listOf("file_name", "format", "content")

    private fun target(input: ToolInput): Pair<String, DocFormat> {
        val format = DocFormat.entries.firstOrNull { it.ext == input.string("format")?.lowercase()?.removePrefix(".") }
            ?: throw IllegalArgumentException("Formato non valido: usa ${DocFormat.entries.joinToString { it.ext }}")
        return fileName(input.requireString("file_name"), format) to format
    }

    override suspend fun confirmationPrompt(input: ToolInput): String? {
        val (name, _) = runCatching { target(input) }.getOrNull() ?: return null
        return if (withContext(Dispatchers.IO) { files.store.existing(name) } != null) "Esiste già \"$name\". Lo sostituisco?" else null
    }

    override suspend fun execute(input: ToolInput): ToolResult = withContext(Dispatchers.IO) {
        val (name, format) = target(input)
        val saved = files.store.save(name, format, input.string("title"), input.requireString("content"))
        val pages = saved.pages?.let { " ($it ${if (it == 1) "pagina" else "pagine"})" }.orEmpty()
        ToolResult.ok("Creato \"${saved.name}\"$pages in ${saved.path.substringBeforeLast('/')}.")
    }
}

class EditDocumentTool internal constructor(private val files: Files) : MemyTool {
    override val name = "edit_document"
    override val description =
        "Modifica un documento. Per quelli creati da Memy (anche PDF e Word) puoi sostituire tutto il contenuto, " +
            "sostituire un pezzo di testo o aggiungere in fondo; il file viene rigenerato con la stessa impaginazione. " +
            "Per i file di testo del telefono (txt, md, csv, html) le modifiche si fanno direttamente. " +
            "Per PDF e Word non creati da Memy: leggili con read_document e crea una nuova versione con create_document."
    override val properties = mapOf(
        "file" to prop("string", "Nome del file (es. 'Lista della spesa.pdf') o percorso"),
        "content" to prop("string", "Nuovo contenuto completo, che sostituisce tutto. $CONTENT_HELP"),
        "find" to prop("string", "Testo da cercare e sostituire (da usare con replace)"),
        "replace" to prop("string", "Testo che prende il posto di 'find'"),
        "append" to prop("string", "Testo da aggiungere in fondo"),
        "title" to prop("string", "Nuovo titolo del documento (facoltativo)"),
    )
    override val required = listOf("file")

    override suspend fun confirmationPrompt(input: ToolInput): String? {
        val f = withContext(Dispatchers.IO) { files.find(input.string("file") ?: return@withContext null) } ?: return null
        return if (!f.memy) "Sto per modificare il file \"${f.name}\" del telefono. Confermi?" else null
    }

    private fun change(old: String, input: ToolInput): String {
        input.string("content")?.let { return it }
        var text = old
        input.string("find")?.let { find ->
            if (find !in text) throw IllegalArgumentException("Il testo \"$find\" non è nel documento: rileggilo con read_document.")
            text = text.replace(find, input.raw["replace"]?.toString().orEmpty())
        }
        input.string("append")?.let { text = text.trimEnd() + "\n\n" + it }
        if (text == old && input.string("title") == null) throw IllegalArgumentException("Indica content, find/replace oppure append.")
        return text
    }

    override suspend fun execute(input: ToolInput): ToolResult = withContext(Dispatchers.IO) {
        val query = input.requireString("file")
        val f = files.find(query) ?: return@withContext files.notFound(query)
        val format = DocFormat.of(f.name)
        val source = if (f.memy) files.store.source(f.name) else null
        if (source != null && format != null) {
            val title = input.string("title") ?: files.store.title(f.name)
            files.store.save(f.name, format, title, change(source, input))
            return@withContext ToolResult.ok("Aggiornato \"${f.name}\".")
        }
        val ext = f.name.substringAfterLast('.', "").lowercase()
        if (ext !in TEXT_EXT) {
            return@withContext ToolResult.error(
                "\"${f.name}\" non si può modificare direttamente: leggilo con read_document e crea una nuova versione con create_document."
            )
        }
        val updated = change(String(files.bytes(f)), input)
        if (f.uri != null) files.ctx.contentResolver.openOutputStream(f.uri, "wt")!!.use { it.write(updated.toByteArray()) }
        else f.file!!.writeText(updated)
        ToolResult.ok("Aggiornato \"${f.name}\".")
    }
}

private val TEXT_EXT = setOf("txt", "md", "csv", "html", "htm", "json", "xml", "log")

class ReadDocumentTool internal constructor(private val files: Files) : MemyTool {
    override val name = "read_document"
    override val description =
        "Legge un documento: i documenti di Memy, oppure (con l'accesso ai file) PDF, Word e file di testo del telefono. " +
            "I PDF si leggono una pagina alla volta come immagine: indica il numero di pagina."
    override val properties = mapOf(
        "file" to prop("string", "Nome del file o percorso"),
        "page" to prop("integer", "Per i PDF: la pagina da guardare, da 1 (predefinita 1)"),
    )
    override val required = listOf("file")

    override suspend fun execute(input: ToolInput): ToolResult = withContext(Dispatchers.IO) {
        val query = input.requireString("file")
        val f = files.find(query) ?: return@withContext files.notFound(query)
        if (f.memy) files.store.source(f.name)?.let { src ->
            val title = files.store.title(f.name)?.let { "Titolo: $it\n" }.orEmpty()
            return@withContext ToolResult.ok("Contenuto di \"${f.name}\" (in markdown):\n$title$src".take(MAX_CHARS))
        }
        when (f.name.substringAfterLast('.', "").lowercase()) {
            in TEXT_EXT -> ToolResult.ok("Contenuto di \"${f.name}\":\n" + String(files.bytes(f)).take(MAX_CHARS))
            "docx" -> ToolResult.ok("Testo di \"${f.name}\":\n" + DocxRenderer.extractText(files.bytes(f)).take(MAX_CHARS))
            "pdf" -> pdfPage(f, input.int("page") ?: 1)
            else -> ToolResult.error("Non so leggere questo tipo di file (${f.name}).")
        }
    }

    private fun pdfPage(f: Found, page: Int): ToolResult {
        val pfd = f.uri?.let { files.ctx.contentResolver.openFileDescriptor(it, "r") }
            ?: ParcelFileDescriptor.open(f.file!!, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd.use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { pdf ->
                val n = pdf.pageCount
                if (page !in 1..n) return ToolResult.error("Il PDF ha $n pagine.")
                pdf.openPage(page - 1).use { p ->
                    val scale = 1400f / maxOf(p.width, p.height)
                    val bmp = Bitmap.createBitmap((p.width * scale).toInt(), (p.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
                    return ToolResult("Pagina $page di $n di \"${f.name}\".", imageJpeg = jpeg)
                }
            }
        }
    }

    private companion object { const val MAX_CHARS = 30_000 }
}

class ListDocumentsTool internal constructor(private val files: Files) : MemyTool {
    override val name = "list_documents"
    override val description = "Elenca i documenti creati da Memy (Download/Memy) e, con l'accesso ai file, quelli in Download e Documenti."
    override val properties = mapOf("where" to enumProp("Dove guardare (predefinito: memy)", listOf("memy", "download", "documenti")))
    override val required = emptyList<String>()

    override suspend fun execute(input: ToolInput): ToolResult = withContext(Dispatchers.IO) {
        val fmt = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.ITALIAN)
        when (input.string("where") ?: "memy") {
            "memy" -> {
                val list = files.store.list()
                if (list.isEmpty()) ToolResult.ok("Nessun documento in Download/Memy.")
                else ToolResult.ok(list.joinToString("\n") { "- ${it.name} (${it.size / 1024 + 1} KB, ${fmt.format(it.modified)})" })
            }
            else -> {
                if (!files.allFilesAccess()) return@withContext files.notFound("(elenco)")
                val dir = Environment.getExternalStoragePublicDirectory(
                    if (input.string("where") == "download") Environment.DIRECTORY_DOWNLOADS else Environment.DIRECTORY_DOCUMENTS,
                )
                val list = dir.walkTopDown().maxDepth(2).filter { it.isFile && !it.name.startsWith(".") }
                    .sortedByDescending { it.lastModified() }.take(40).toList()
                if (list.isEmpty()) ToolResult.ok("Cartella vuota.")
                else ToolResult.ok(list.joinToString("\n") { "- ${it.relativeTo(dir).path} (${it.length() / 1024 + 1} KB, ${fmt.format(it.lastModified())})" })
            }
        }
    }
}

private fun mimeOf(name: String) =
    DocFormat.of(name)?.mime ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "*/*"

class OpenDocumentTool internal constructor(private val ctx: Context, private val files: Files) : MemyTool {
    override val name = "open_document"
    override val description = "Apre un documento con l'app adatta (lettore PDF, Word, browser…), così l'utente lo vede."
    override val properties = mapOf("file" to prop("string", "Nome del file o percorso"))
    override val required = listOf("file")
    override suspend fun execute(input: ToolInput): ToolResult {
        val query = input.requireString("file")
        val f = withContext(Dispatchers.IO) { files.find(query) } ?: return files.notFound(query)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(files.shareUri(f), mimeOf(f.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return if (ctx.launch(intent)) ToolResult.ok("Aperto \"${f.name}\".")
        else ToolResult.error("Nessuna app sul telefono sa aprire \"${f.name}\".")
    }
}

class ShareDocumentTool internal constructor(private val ctx: Context, private val files: Files) : MemyTool {
    override val name = "share_document"
    override val description =
        "Condivide un documento: apre la schermata di condivisione (WhatsApp, email, Telegram…). " +
            "Poi puoi scegliere l'app con read_screen e tap_element; l'invio vero chiede sempre conferma."
    override val properties = mapOf(
        "file" to prop("string", "Nome del file o percorso"),
        "message" to prop("string", "Testo da accompagnare al file (facoltativo)"),
    )
    override val required = listOf("file")
    override suspend fun execute(input: ToolInput): ToolResult {
        val query = input.requireString("file")
        val f = withContext(Dispatchers.IO) { files.find(query) } ?: return files.notFound(query)
        val send = Intent(Intent.ACTION_SEND).setType(mimeOf(f.name))
            .putExtra(Intent.EXTRA_STREAM, files.shareUri(f))
            .apply { input.string("message")?.let { putExtra(Intent.EXTRA_TEXT, it) } }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return if (ctx.launch(Intent.createChooser(send, "Condividi ${f.name}"))) ToolResult.ok("Schermata di condivisione aperta per \"${f.name}\".")
        else ToolResult.error("Condivisione non riuscita.")
    }
}

class DeleteDocumentTool internal constructor(private val files: Files) : MemyTool {
    override val name = "delete_document"
    override val description = "Elimina un documento creato da Memy (in Download/Memy)."
    override val properties = mapOf("file" to prop("string", "Nome del file"))
    override val required = listOf("file")
    override suspend fun confirmationPrompt(input: ToolInput) = "Elimino il documento \"${input.string("file")}\"?"
    override suspend fun execute(input: ToolInput): ToolResult = withContext(Dispatchers.IO) {
        val query = input.requireString("file")
        val f = files.find(query)
        if (f == null || !f.memy) return@withContext ToolResult.error("Posso eliminare solo i documenti creati da Memy.")
        if (files.store.delete(f.name)) ToolResult.ok("Eliminato \"${f.name}\".") else ToolResult.error("Eliminazione non riuscita.")
    }
}
