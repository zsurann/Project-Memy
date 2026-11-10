package com.nexa.assistant.speech

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Una voce neurale di Microsoft Edge. */
data class EdgeVoice(
    /** Nome completo, es. "it-IT-IsabellaNeural". */
    val id: String,
    /** Nome breve, es. "Isabella". */
    val name: String,
    val locale: String,
    /** "Female" o "Male". */
    val gender: String,
    val personalities: List<String> = emptyList(),
) {
    val isItalian: Boolean get() = locale.startsWith("it-")
    val isMultilingual: Boolean get() = id.contains("Multilingual")
}

class EdgeTtsException(message: String, val code: Int = 0) : Exception(message)

/**
 * Sintesi vocale gratuita con le voci neurali di Microsoft Edge ("Leggi ad alta voce").
 *
 * È lo stesso servizio che usa il browser Edge: non serve un account né una chiave.
 * Il protocollo segue la libreria open source edge-tts (https://github.com/rany2/edge-tts, v7.3.1),
 * compreso il gettone "Sec-MS-GEC" v2 richiesto da ottobre 2026.
 * Non è un'API ufficiale: Microsoft potrebbe cambiarla in futuro.
 */
class EdgeTts(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    /** Sintetizza un testo e restituisce l'audio MP3 (24 kHz, 48 kbit/s). Bloccante. */
    fun synthesize(text: String, voice: String, ratePercent: Int = 0, pitchHz: Int = 0): ByteArray {
        val clean = escapeXml(removeIncompatible(text)).trim()
        require(clean.isNotEmpty()) { "Testo vuoto" }
        return try {
            synthesizeOnce(clean, voice, ratePercent, pitchHz)
        } catch (e: EdgeTtsException) {
            // 403: probabilmente l'orologio del telefono non è preciso. Si corregge con l'ora del server e si riprova.
            if (e.code == 403 && Drm.lastServerSkewAdjusted) synthesizeOnce(clean, voice, ratePercent, pitchHz) else throw e
        }
    }

    private fun synthesizeOnce(escapedText: String, voice: String, ratePercent: Int, pitchHz: Int): ByteArray {
        val request = Request.Builder()
            .url(
                "$WSS_URL&ConnectionId=${connectId()}&Sec-MS-GEC=${Drm.secMsGec()}&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"
            )
            .apply { WSS_HEADERS.forEach { (k, v) -> header(k, v) } }
            .header("Cookie", "muid=${Drm.muid()};")
            .build()

        val audio = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        var failure: Exception? = null
        var gotAudio = false

        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(speechConfig())
                webSocket.send(ssmlMessage(mkSsml(voice, ratePercent, pitchHz, escapedText)))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val path = headerValue(text.substringBefore("\r\n\r\n"), "Path")
                if (path == "turn.end") {
                    webSocket.close(1000, null)
                    done.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size < 2) return
                val headerLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                if (headerLength + 2 > data.size) return
                val headers = String(data, 2, headerLength, Charsets.UTF_8)
                if (headerValue(headers, "Path") != "audio") return
                if (headerValue(headers, "Content-Type") != "audio/mpeg") return
                val start = 2 + headerLength
                if (start < data.size) {
                    synchronized(audio) { audio.write(data, start, data.size - start) }
                    gotAudio = true
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                if (code == 403) Drm.adjustSkewFrom(response?.header("Date"))
                failure = EdgeTtsException(
                    when (code) {
                        0 -> "servizio vocale non raggiungibile (${t.message})"
                        403 -> "accesso al servizio vocale negato (403)"
                        else -> "errore del servizio vocale ($code)"
                    },
                    code,
                )
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.countDown()
            }
        })

        if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            socket.cancel()
            throw EdgeTtsException("il servizio vocale non risponde")
        }
        failure?.let { throw it }
        if (!gotAudio) throw EdgeTtsException("nessun audio ricevuto (voce \"$voice\" non valida?)")
        return synchronized(audio) { audio.toByteArray() }
    }

    /** Elenco di tutte le voci disponibili. Bloccante. */
    fun voices(): List<EdgeVoice> {
        val req = Request.Builder()
            .url("$VOICE_LIST&Sec-MS-GEC=${Drm.secMsGec()}&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION")
            .apply { VOICE_HEADERS.forEach { (k, v) -> header(k, v) } }
            .header("Cookie", "muid=${Drm.muid()};")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                if (resp.code == 403) Drm.adjustSkewFrom(resp.header("Date"))
                throw EdgeTtsException("elenco voci non disponibile (${resp.code})", resp.code)
            }
            val arr = JSONArray(resp.body!!.string())
            return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
                val tag = o.optJSONObject("VoiceTag")
                val pers = tag?.optJSONArray("VoicePersonalities")
                EdgeVoice(
                    id = o.getString("ShortName"),
                    name = shortName(o.getString("ShortName")),
                    locale = o.optString("Locale"),
                    gender = o.optString("Gender"),
                    personalities = if (pers == null) emptyList() else (0 until pers.length()).map { pers.getString(it) },
                )
            }
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 25L
        private const val BASE_URL = "speech.platform.bing.com/consumer/speech/synthesize/readaloud"
        internal const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val WSS_URL = "wss://$BASE_URL/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"
        private const val VOICE_LIST = "https://$BASE_URL/voices/list?trustedclienttoken=$TRUSTED_CLIENT_TOKEN"
        private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
        private val CHROMIUM_MAJOR = CHROMIUM_FULL_VERSION.substringBefore('.')
        private val SEC_MS_GEC_VERSION = "2-$CHROMIUM_FULL_VERSION"

        // Niente "Accept-Encoding": la compressione la gestisce OkHttp da solo.
        private val BASE_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR.0.0.0",
            "Accept-Language" to "en-US,en;q=0.9",
        )
        private val WSS_HEADERS = mapOf(
            "Pragma" to "no-cache",
            "Cache-Control" to "no-cache",
            "Origin" to "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold",
        ) + BASE_HEADERS
        private val VOICE_HEADERS = mapOf(
            "Authority" to "speech.platform.bing.com",
            "Sec-CH-UA" to "\" Not;A Brand\";v=\"99\", \"Microsoft Edge\";v=\"$CHROMIUM_MAJOR\", \"Chromium\";v=\"$CHROMIUM_MAJOR\"",
            "Sec-CH-UA-Mobile" to "?0",
            "Accept" to "*/*",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty",
        ) + BASE_HEADERS

        /** "it-IT-IsabellaNeural" → "Isabella", "it-IT-GiuseppeMultilingualNeural" → "Giuseppe". */
        fun shortName(id: String): String =
            id.substringAfterLast('-').removeSuffix("Neural").removeSuffix("Multilingual").ifBlank { id }

        internal fun connectId(): String = UUID.randomUUID().toString().replace("-", "")

        /** Data in stile JavaScript, come la manda il browser. */
        private fun dateToString(): String =
            SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date())

        private fun speechConfig(): String =
            "X-Timestamp:${dateToString()}\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
                "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"

        private fun ssmlMessage(ssml: String): String =
            "X-RequestId:${connectId()}\r\n" +
                "Content-Type:application/ssml+xml\r\n" +
                "X-Timestamp:${dateToString()}Z\r\n" + // la "Z" in più c'è anche nel browser Edge
                "Path:ssml\r\n\r\n" +
                ssml

        internal fun mkSsml(voice: String, ratePercent: Int, pitchHz: Int, escapedText: String): String {
            fun signed(v: Int) = if (v >= 0) "+$v" else "$v"
            return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                "<voice name='${escapeXml(voice)}'>" +
                "<prosody pitch='${signed(pitchHz)}Hz' rate='${signed(ratePercent)}%' volume='+0%'>" +
                escapedText +
                "</prosody></voice></speak>"
        }

        private fun headerValue(headers: String, name: String): String? =
            headers.split("\r\n").firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim()

        /** Il servizio rifiuta alcuni caratteri di controllo: li sostituiamo con spazi. */
        internal fun removeIncompatible(s: String): String = buildString(s.length) {
            for (c in s) {
                val code = c.code
                append(if (code in 0..8 || code in 11..12 || code in 14..31) ' ' else c)
            }
        }

        internal fun escapeXml(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;").replace("\"", "&quot;")
    }
}

/**
 * Generazione del gettone "Sec-MS-GEC" v2, trascritta da edge_tts/drm.py (v7.3.1).
 * Usa aritmetica a 32 bit senza segno su Long, mascherando come nel codice Python originale.
 */
internal object Drm {
    private const val WIN_EPOCH = 11_644_473_600L
    private const val MASK = 0xFFFFFFFFL

    @Volatile private var clockSkewSeconds = 0L
    @Volatile var lastServerSkewAdjusted = false
        private set
    private val random = SecureRandom()

    fun muid(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02X".format(it) }

    fun nowUnixSeconds(): Long = System.currentTimeMillis() / 1000 + clockSkewSeconds

    /** Corregge l'orologio usando l'intestazione "Date" del server (come fa edge-tts dopo un 403). */
    fun adjustSkewFrom(date: String?) {
        lastServerSkewAdjusted = false
        val server = date?.let {
            runCatching { SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(it)?.time }.getOrNull()
        } ?: return
        val skew = server / 1000 - nowUnixSeconds()
        if (kotlin.math.abs(skew) >= 1) {
            clockSkewSeconds += skew
            lastServerSkewAdjusted = true
        }
    }

    fun secMsGec(unixSeconds: Long = nowUnixSeconds()): String {
        var ticks = unixSeconds + WIN_EPOCH
        ticks -= ticks % 300
        val str = (ticks * 10_000_000L).toString() + EdgeTts.TRUSTED_CLIENT_TOKEN
        val data = str.toByteArray(Charsets.US_ASCII)
        val digest = sha256Digest(data)
        val (c0, c1) = cs64Digest(data, digest)
        val cs64 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(c0.toInt()).putInt(c1.toInt()).array()
        return hex(cs64) + "-" + hex(digest)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

    // ---------------------------------------------------------------- costanti

    private val SHA256_INITIAL = longArrayOf(
        0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A, 0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19,
    )
    private val SHA256_STATE = longArrayOf(
        0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A, 0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2,
    )
    private val SHA256_SCHEDULE = longArrayOf(
        0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA, 0xA622EE8C, 0xD1DD9491, 0xC0DD25A0,
        0xF9E70AB6, 0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33, 0xD258B060, 0x72766564,
    )
    private val K = longArrayOf(
        0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
        0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
        0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
        0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2,
    )
    private const val SHA256_ADJUSTMENT = 0xC9DC06A7L
    private val BLOCK_K = K.copyOf().also { it[14] = SHA256_ADJUSTMENT }
    private const val SIGMA0_ADJUSTMENT = 0x059C0B80L
    private const val SIGMA1_ADJUSTMENT = 0x000B92C0L

    private val CS64_COEFFICIENTS = arrayOf(
        longArrayOf(0xE82E91B1, 0xB32700D0), longArrayOf(0x44DE24C6, 0x88DFF51D), longArrayOf(0x3BEB65BC, 0x29514038),
        longArrayOf(0xC89C5933, 0xB328CBDF), longArrayOf(0x8D1EC211, 0xD405B4D5), longArrayOf(0x314EF5D5, 0x26E55F3C),
        longArrayOf(0xE92491B1, 0x607801C6), longArrayOf(0xF0A5B9D5, 0x82B2C1FB), longArrayOf(0x06064CF3, 0xFA51A3DA),
        longArrayOf(0xDA79FA60, 0x207CC464), longArrayOf(0xBED20560, 0x3C3C74F3), longArrayOf(0x89F9C9F8, 0xD2D14B61),
        longArrayOf(0x35FA38C6, 0xBB04A2C1), longArrayOf(0xCB49E3C6, 0xC5626167),
    )
    private const val CS64_FACTOR = 0x82893933L
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1AL
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000L

    // ---------------------------------------------------------------- funzioni di base

    private fun rol16(v: Long): Long {
        val x = v and MASK
        return ((x shl 16) or (x ushr 16)) and MASK
    }

    private fun rotr(x: Long, n: Int): Long = ((x ushr n) or (x shl (32 - n))) and MASK

    private fun sigma0(v: Long): Long = ((v ushr 7) xor (v shl 25) xor (v ushr 18) xor (v shl 14) xor (v ushr 3)) and MASK
    private fun sigma1(v: Long): Long = ((v ushr 17) xor (v shl 15) xor (v ushr 19) xor (v shl 13) xor (v ushr 10)) and MASK

    /** Round SHA-256 standard, a partire da [firstRound] (14 per il primo blocco "precalcolato"). */
    private fun rounds(state: LongArray, w: LongArray, firstRound: Int): LongArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        val k = if (firstRound == 14) BLOCK_K else K
        for (i in firstRound until 64) {
            val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
            val ch = g xor (e and (f xor g))
            val t1 = (h + s1 + ch + k[i] + w[i]) and MASK
            val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
            val maj = (a and b) or (c and (a or b))
            val t2 = (s0 + maj) and MASK
            h = g; g = f; f = e
            e = (d + t1) and MASK
            d = c; c = b; b = a
            a = (t1 + t2) and MASK
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expand(w: MutableList<Long>, firstWord: Int) {
        for (i in firstWord until 64) {
            val x = w[i - 2]
            val y = w[i - 15]
            w.add((sigma1(x) + w[i - 7] + sigma0(y) + w[i - 16]) and MASK)
        }
    }

    private fun be(data: ByteArray, from: Int, to: Int): Long {
        var v = 0L
        for (i in from until to) v = (v shl 8) or (data[i].toLong() and 0xFF)
        return v
    }

    private fun sha256Init(data7: ByteArray): LongArray {
        val w = MutableList(14) { 0L }
        w.add(be(data7, 0, 3))
        w.add(be(data7, 3, 7))
        for (index in 16 until 30) {
            var value = SHA256_SCHEDULE[index - 16]
            if (index - 2 >= 14) {
                value += if (index == 16) sigma1(w[index - 2]) xor SIGMA1_ADJUSTMENT else sigma1(w[index - 2])
            }
            if (index - 7 >= 14) value += w[index - 7]
            if (index - 15 >= 14) {
                value += if (index == 29) sigma0(w[index - 15]) xor SIGMA0_ADJUSTMENT else sigma0(w[index - 15])
            }
            w.add(value and MASK)
        }
        w.add((sigma1(w[28]) + w[23] + sigma0(w[15]) + w[14] + SHA256_ADJUSTMENT - K[14]) and MASK)
        expand(w, 31)
        val working = rounds(SHA256_STATE, w.toLongArray(), 14)
        return LongArray(8) { (SHA256_INITIAL[it] + working[it]) and MASK }
    }

    fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7) { "Il sale del gettone deve avere almeno sette byte" }
        val length = 57 + data.size
        val zeros = Math.floorMod(55 - length, 64)
        val padded = ByteBuffer.allocate(data.size - 7 + 1 + zeros + 8).order(ByteOrder.BIG_ENDIAN).apply {
            put(data, 7, data.size - 7)
            put(0x80.toByte())
            put(ByteArray(zeros))
            putLong(length * 8L)
        }.array()
        var state = sha256Init(data.copyOfRange(0, 7))
        for (offset in padded.indices step 64) {
            val w = MutableList(16) { i -> be(padded, offset + i * 4, offset + i * 4 + 4) }
            expand(w, 16)
            val working = rounds(state, w.toLongArray(), 0)
            state = LongArray(8) { (state[it] + working[it]) and MASK }
        }
        val out = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        state.forEach { out.putInt(it.toInt()) }
        return out.array()
    }

    private fun wordsLe4Even(data: ByteArray): List<Long> {
        val padded = data.copyOf(data.size + Math.floorMod(-data.size, 4))
        if (padded.isEmpty()) return emptyList()
        val buf = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN)
        val words = MutableList(padded.size / 4) { buf.int.toLong() and MASK }
        if (words.size % 2 == 1) words.add(0L)
        return words
    }

    fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<Long, Long> {
        val keys = ByteBuffer.wrap(digest, 0, 8).order(ByteOrder.LITTLE_ENDIAN)
        val keyA = (keys.int.toLong() and MASK) or 1L
        val keyB = (keys.int.toLong() and MASK) or 1L
        val factors = longArrayOf((keyA * CS64_FACTOR) and MASK, (keyB * CS64_FACTOR) and MASK)
        val terms = CS64_COEFFICIENTS.mapIndexed { i, c -> (c[0] * factors[i % 2]) and MASK }.toMutableList()
        val offsets = CS64_COEFFICIENTS.map { it[1] }.toMutableList()
        wordsLe4Even(byteArrayOf(0) + data).forEachIndexed { index, word ->
            val even = index % 2 == 0
            var term = word * (if (even) keyA else keyB)
            var offset = rol16(word) * (if (even) 0xEF0569FBL else 0xC31713DBL)
            if (index == 0) {
                term += CS64_ADJUSTMENT0 * factors[0]
                offset += CS64_ADJUSTMENT1
            }
            terms.add(term and MASK)
            offsets.add(offset and MASK)
        }
        var swapAcc = 0L
        var swapLast = 0L
        var state = 0L
        var acc = 0L
        for (i in terms.indices step 2) {
            val x = terms[i]
            val y = terms[i + 1]
            var t = rol16((offsets[i] + x) and MASK)
            var q = (t * 0x76F50F34L) and MASK
            swapAcc += (rol16(q) * 0xC3EFEA97L + q) and MASK
            t = rol16((offsets[i + 1] + y) and MASK)
            q = (t * 0x1881CE3CL) and MASK
            swapLast = (rol16(q) * 0x35BD1EC9L + q) and MASK
            swapAcc = (swapAcc + swapLast) and MASK

            state = (state * keyA + x) and MASK
            state = (rol16(state) * 0xCF98B111L) and MASK
            state = (rol16(state) * 0x87085B9FL) and MASK
            state = (rol16(state) * 0x12CEB96DL) and MASK
            state = (rol16(state) * 0x257E1D83L) and MASK
            acc += state
            t = (state * keyB + y) and MASK
            t = (rol16(t) * 0xA27416F5L) and MASK
            t = (rol16(t) * 0xD38396FFL) and MASK
            state = (rol16((rol16(t) * 0x7C932B89L) and MASK) * 0xBFA49F69L) and MASK
            acc = (acc + state) and MASK
        }
        return Pair(swapLast xor state, swapAcc xor acc)
    }
}
