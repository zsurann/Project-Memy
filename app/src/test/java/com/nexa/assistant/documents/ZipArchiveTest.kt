package com.nexa.assistant.documents

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class ZipArchiveTest {
    private fun zip(vararg files: Pair<String, ByteArray>): File {
        val f = File.createTempFile("prova", ".zip")
        ZipArchiveOutputStream(f).use { z ->
            for ((name, data) in files) {
                z.putArchiveEntry(ZipArchiveEntry(name)); z.write(data); z.closeArchiveEntry()
            }
        }
        return f
    }

    private val lotsOfSpace = 100L shl 30

    @Test
    fun safePathRejectsEscapes() {
        assertEquals("cartella/file.txt", ZipArchive.safePath("cartella/./file.txt"))
        assertEquals("a/b.txt", ZipArchive.safePath("a\\b.txt"))
        assertNull(ZipArchive.safePath("../fuori.txt"))
        assertNull(ZipArchive.safePath("a/../../fuori.txt"))
        assertNull(ZipArchive.safePath("/etc/passwd"))
        assertNull(ZipArchive.safePath("C:/Windows/x"))
    }

    @Test
    fun listsReadsAndExtracts() {
        val f = zip("documenti/nota.txt" to "ciao".toByteArray(), "foto/a.jpg" to ByteArray(10), "../trappola.txt" to "x".toByteArray())
        val info = ZipArchive.list(f)
        assertEquals(3, info.files.size)
        assertEquals("ciao", String(ZipArchive.read(f, "documenti/nota.txt", 1000)))
        val out = mutableMapOf<String, ByteArrayOutputStream>()
        val res = ZipArchive.extract(f, null, lotsOfSpace) { path -> ByteArrayOutputStream().also { out[path] = it } }
        assertEquals(setOf("documenti/nota.txt", "foto/a.jpg"), out.keys)
        assertEquals(2, res.written)
        assertTrue(res.skipped.single().contains("percorso non sicuro"))
        // Solo una cartella
        val only = mutableListOf<String>()
        ZipArchive.extract(f, "documenti", lotsOfSpace) { p -> only += p; ByteArrayOutputStream() }
        assertEquals(listOf("documenti/nota.txt"), only)
    }

    @Test
    fun zipBombIsStopped() {
        // 30 MB di zeri si comprimono più di 100:1.
        val f = zip("bomba.bin" to ByteArray(30 shl 20))
        try {
            ZipArchive.extract(f, null, lotsOfSpace) { ByteArrayOutputStream() }
            fail("doveva fermarsi")
        } catch (e: ZipArchive.ZipException) {
            assertTrue(e.message, e.message!!.contains("compressione sospetta"))
        }
    }

    @Test
    fun keepsFreeSpace() {
        val f = zip("a.txt" to ByteArray(1000) { 1 })
        try {
            ZipArchive.extract(f, null, ZipArchive.KEEP_FREE_BYTES + 10) { ByteArrayOutputStream() }
            fail("doveva fermarsi")
        } catch (e: ZipArchive.ZipException) {
            assertTrue(e.message!!.contains("spazio"))
        }
    }
}
