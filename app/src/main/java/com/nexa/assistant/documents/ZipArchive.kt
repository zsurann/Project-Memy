package com.nexa.assistant.documents

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset

/**
 * Lettura ed estrazione sicura degli zip (commons-compress, già nel progetto).
 * Protegge da zip "bomba" (limiti di dimensione e di compressione) e da percorsi che uscirebbero dalla cartella.
 */
object ZipArchive {
    const val MAX_ENTRIES = 10_000
    const val MAX_ENTRY_BYTES = 1L shl 30          // 1 GB per file
    const val MAX_TOTAL_BYTES = 4L shl 30          // 4 GB in tutto
    const val MAX_RATIO = 100                       // compressione oltre 100:1 = sospetta
    const val KEEP_FREE_BYTES = 500L shl 20        // lascia sempre 500 MB liberi

    data class Entry(val path: String, val size: Long, val compressed: Long, val dir: Boolean, val encrypted: Boolean)

    class Info(val entries: List<Entry>) {
        val files get() = entries.filter { !it.dir }
        val encrypted get() = entries.any { it.encrypted }
        val totalSize get() = files.sumOf { it.size.coerceAtLeast(0) }
    }

    class Result(val written: Int, val bytes: Long, val skipped: List<String>)

    class ZipException(message: String) : Exception(message)

    /** Apre lo zip: nomi UTF-8, oppure GBK per gli zip creati su Windows in cinese (nomi illeggibili). */
    fun <T> open(file: File, block: (ZipFile) -> T): T {
        val utf = ZipFile.builder().setFile(file).setCharset(Charsets.UTF_8).setUseUnicodeExtraFields(true).get()
        val garbled = utf.entries.asSequence().any { '�' in it.name }
        val zip = if (garbled) {
            utf.close()
            ZipFile.builder().setFile(file).setCharset(Charset.forName("GBK")).setUseUnicodeExtraFields(true).get()
        } else utf
        return zip.use(block)
    }

    /** Percorso sicuro dentro la cartella di destinazione, o null se va rifiutato ("../", assoluti, vuoti). */
    fun safePath(name: String): String? {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." } || name.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(name)) return null
        return parts.joinToString("/")
    }

    private fun ZipArchiveEntry.toEntry() = Entry(
        name, size, compressedSize, isDirectory, generalPurposeBit.usesEncryption(),
    )

    fun list(file: File): Info = open(file) { zip ->
        val out = ArrayList<Entry>()
        for (e in zip.entries) {
            out += e.toEntry()
            if (out.size > MAX_ENTRIES) throw ZipException("troppi file nello zip (oltre $MAX_ENTRIES)")
        }
        Info(out)
    }

    /** Copia con i limiti: si contano i byte davvero scritti, non le dimensioni dichiarate (che possono mentire). */
    private fun copyLimited(input: InputStream, out: OutputStream, entry: ZipArchiveEntry, budget: Long): Long {
        val buf = ByteArray(64 * 1024)
        var written = 0L
        val ratioCap = if (entry.compressedSize > 0) entry.compressedSize * MAX_RATIO + (1L shl 20) else Long.MAX_VALUE
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            written += n
            if (written > MAX_ENTRY_BYTES) throw ZipException("${entry.name}: file troppo grande (oltre 1 GB)")
            if (written > ratioCap) throw ZipException("${entry.name}: compressione sospetta (possibile zip bomba)")
            if (written > budget) throw ZipException("spazio esaurito: lo zip estratto supera il limite")
            out.write(buf, 0, n)
        }
        return written
    }

    /** Legge un solo file dello zip in memoria (per guardarne il contenuto). */
    fun read(file: File, path: String, maxBytes: Long): ByteArray = open(file) { zip ->
        val e = zip.getEntry(path) ?: throw ZipException("\"$path\" non è nello zip")
        if (e.generalPurposeBit.usesEncryption()) throw ZipException("\"$path\" è protetto da password")
        val out = java.io.ByteArrayOutputStream()
        zip.getInputStream(e).use { copyLimited(it, out, e, maxBytes) }
        out.toByteArray()
    }

    /**
     * Estrae tutto (o solo [only]) chiamando [sink] per ogni file con il percorso sicuro.
     * @param freeBytes spazio libero sul telefono, per non riempirlo
     */
    fun extract(file: File, only: String?, freeBytes: Long, sink: (String) -> OutputStream): Result {
        val budget = minOf(MAX_TOTAL_BYTES, freeBytes - KEEP_FREE_BYTES)
        if (budget <= 0) throw ZipException("spazio libero insufficiente sul telefono")
        return open(file) { zip ->
            var total = 0L
            var count = 0
            val skipped = ArrayList<String>()
            for (e in zip.entries) {
                if (e.isDirectory || (only != null && e.name != only && !e.name.startsWith(only.trimEnd('/') + "/"))) continue
                val path = safePath(e.name)
                when {
                    path == null -> { skipped += "${e.name} (percorso non sicuro)"; continue }
                    e.isUnixSymlink -> { skipped += "${e.name} (collegamento)"; continue }
                    e.generalPurposeBit.usesEncryption() -> { skipped += "${e.name} (protetto da password)"; continue }
                    !zip.canReadEntryData(e) -> { skipped += "${e.name} (formato non supportato)"; continue }
                }
                if (++count > MAX_ENTRIES) throw ZipException("troppi file nello zip")
                try {
                    sink(path!!).use { out -> zip.getInputStream(e).use { total += copyLimited(it, out, e, budget - total) } }
                } catch (_: LinkageError) {
                    // Compressione zstd/xz: la libreria per leggerla non è inclusa nell'app.
                    count--; skipped += "${e.name} (compressione non supportata)"
                }
            }
            if (count == 0 && only != null) throw ZipException("\"$only\" non è nello zip")
            Result(count, total, skipped)
        }
    }
}
