package com.nexa.assistant.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

private val SAMPLE = """
# Spesa della settimana

Lista per **sabato** mattina, da comprare al *mercato*.

- Pane
- Latte & uova
1. Prima il fruttivendolo
2. Poi il panificio

| Prodotto | Quantità | Prezzo |
|---|---|---|
| Mele | 2 kg | 3,50 € |
| Pane <integrale> | 1 | 2,10 € |

---

## Note
Ricordarsi le borse.
""".trimIndent()

class MarkdownTest {
    @Test
    fun parsesBlocksAndInlineStyles() {
        val blocks = Markdown.parse(SAMPLE)
        assertEquals(Block.Heading(1, "Spesa della settimana"), blocks[0])
        assertTrue(blocks[1] is Block.Paragraph)
        assertEquals(Block.Bullet("Pane"), blocks[2])
        assertEquals(Block.Bullet("Prima il fruttivendolo", 1), blocks[4])
        val table = blocks.filterIsInstance<Block.Table>().single()
        assertEquals(3, table.rows.size)
        assertEquals(listOf("Mele", "2 kg", "3,50 €"), table.rows[1])
        assertTrue(blocks.contains(Block.Rule))
        assertEquals(Block.Heading(2, "Note"), blocks[blocks.size - 2])
        val runs = Markdown.runs("Lista per **sabato** e *domenica*")
        assertEquals(listOf(Run("Lista per "), Run("sabato", bold = true), Run(" e "), Run("domenica", italic = true)), runs)
    }

    @Test
    fun wordFileIsValidAndReadable() {
        val bytes = DocxRenderer.render("Spesa", Markdown.parse(SAMPLE))
        val names = ZipInputStream(bytes.inputStream()).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
        assertEquals(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml"), names)
        // L'XML deve essere ben formato (altrimenti Word non apre il file).
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(z.readBytes().inputStream())
                assertTrue(e.name, true)
            }
        }
        val text = DocxRenderer.extractText(bytes)
        assertTrue(text, text.contains("Pane <integrale>") && text.contains("Latte & uova") && text.contains("sabato"))
        File("build/documents").mkdirs(); File("build/documents/prova.docx").writeBytes(bytes)
    }

    @Test
    fun htmlEscapesAndStyles() {
        val html = HtmlRenderer.render("Spesa", Markdown.parse(SAMPLE))
        assertTrue(html.contains("<strong>sabato</strong>") && html.contains("Pane &lt;integrale&gt;") && html.contains("<ol>"))
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfTest {
    @Test
    fun pdfLayoutFlowsAcrossPages() {
        val long = SAMPLE + "\n\n## Dettagli\n\n" + (1..30).joinToString("\n\n") { "Paragrafo $it: " + "testo di prova per riempire la pagina, con àccenti è perché €. ".repeat(3) }
        val out = File("build/documents").apply { mkdirs() }
        val bitmaps = ArrayList<android.graphics.Bitmap>()
        val count = PdfRenderer.draw("Spesa della settimana", Markdown.parse(long), object : PdfRenderer.Pages {
            override fun start(number: Int, width: Int, height: Int): android.graphics.Canvas {
                val bmp = android.graphics.Bitmap.createBitmap(width * 2, height * 2, android.graphics.Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                bitmaps += bmp
                return android.graphics.Canvas(bmp).apply { scale(2f, 2f) }
            }
            override fun finish() {}
        })
        assertEquals(bitmaps.size, count)
        assertTrue("pagine: $count", count >= 2)
        bitmaps.forEachIndexed { i, b -> File(out, "pagina-${i + 1}.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        println("PDF impaginato: $count pagine")
    }
}
