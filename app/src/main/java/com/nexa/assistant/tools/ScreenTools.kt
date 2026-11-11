package com.nexa.assistant.tools

import android.graphics.Bitmap
import com.nexa.assistant.access.MemyAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Strumenti per vedere lo schermo e usare qualsiasi app (richiedono il servizio di accessibilità). */
fun screenTools(): List<MemyTool> = listOf(
    ReadScreenTool(), LookAtScreenTool(), TapElementTool(), TapPositionTool(), TypeTextTool(),
    ScrollTool(), PressButtonTool(), WaitTool(),
)

private fun service() = MemyAccessibilityService.instance

/** Legge lo schermo; se la finestra non è ancora pronta riprova per un attimo. */
private suspend fun readWithRetry(svc: MemyAccessibilityService): String {
    repeat(3) {
        if (svc.hasReadableWindow()) return svc.readScreen()
        delay(250)
    }
    return svc.readScreen()
}

/** Dopo un'azione aspetta che l'interfaccia si aggiorni e restituisce il nuovo schermo. */
private suspend fun settledScreen(svc: MemyAccessibilityService, waitMs: Long = 650): String {
    delay(waitMs)
    return "Schermo dopo l'azione:\n" + svc.readScreen()
}

/** Parole che indicano un'azione con effetti reali: per queste Memy chiede sempre conferma. */
private val SENSITIVE = listOf(
    "invia", "send", "inoltra", "paga", "pay", "acquista", "compra", "buy", "ordina", "order", "checkout",
    "conferma", "confirm", "elimina", "delete", "cancella", "rimuovi", "remove", "disinstalla", "uninstall",
    "pubblica", "publish", "post", "condividi", "share", "trasferisci", "transfer", "bonifico", "abbonati", "subscribe",
    "chiama", "call", "blocca", "block", "esci", "logout", "sign out", "formatta", "ripristina", "reset",
    "发送", "支付", "付款", "购买", "删除", "确认", "转账", "卸载",
)

internal fun looksSensitive(text: String): Boolean {
    val t = text.lowercase()
    return SENSITIVE.any { word ->
        if (word.any { it.code > 0x2E80 }) t.contains(word)
        else Regex("(^|[^a-zà-ù])" + Regex.escape(word) + "($|[^a-zà-ù])").containsMatchIn(t)
    }
}

class ReadScreenTool : MemyTool {
    override val name = "read_screen"
    override val description =
        "Legge lo schermo dell'app in primo piano e restituisce l'elenco numerato degli elementi (testi, pulsanti, campi). " +
            "Usalo per capire cosa c'è sullo schermo prima di toccare qualcosa. I numeri valgono fino alla prossima lettura."
    override val properties = emptyMap<String, Map<String, Any>>()
    override val required = emptyList<String>()
    override suspend fun execute(input: ToolInput): ToolResult {
        val svc = service() ?: return ToolResult.error(NEED_ACCESSIBILITY)
        return withContext(Dispatchers.Main) { ToolResult.ok(readWithRetry(svc)) }
    }
}

class LookAtScreenTool : MemyTool {
    override val name = "look_at_screen"
    override val description =
        "Scatta un'immagine dello schermo e te la mostra. Usalo solo quando read_screen non basta (immagini, giochi, " +
            "mappe, app che non espongono il testo), perché è più lento e costoso. Per toccare un punto visto nell'immagine usa tap_position."
    override val properties = emptyMap<String, Map<String, Any>>()
    override val required = emptyList<String>()
    override suspend fun execute(input: ToolInput): ToolResult {
        val svc = service() ?: return ToolResult.error(NEED_ACCESSIBILITY)
        val bmp = withContext(Dispatchers.Main) { svc.captureScreen() }
            ?: return ToolResult.error("Impossibile catturare lo schermo (app protetta o Android troppo vecchio). Usa read_screen.")
        val jpeg = withContext(Dispatchers.Default) {
            val scale = 1024f / maxOf(bmp.width, bmp.height)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 72, it) }.toByteArray()
        }
        return ToolResult("Immagine dello schermo (le posizioni per tap_position sono percentuali: 0,0 in alto a sinistra, 100,100 in basso a destra).", imageJpeg = jpeg)
    }
}

class TapElementTool : MemyTool {
    override val name = "tap_element"
    override val description =
        "Tocca un elemento dello schermo indicato dal numero dato da read_screen. Restituisce lo schermo aggiornato. " +
            "Se il tocco invia un messaggio, paga, acquista, elimina o pubblica qualcosa, imposta sensitive=true: l'app chiederà conferma all'utente."
    override val properties = mapOf(
        "index" to prop("integer", "Numero dell'elemento da toccare"),
        "purpose" to prop("string", "Cosa ottiene questo tocco, in poche parole in italiano (es. 'inviare il messaggio a Marco')"),
        "long_press" to prop("boolean", "true per una pressione lunga"),
        "sensitive" to prop("boolean", "true se il tocco ha effetti reali non annullabili (inviare, pagare, eliminare, pubblicare)"),
    )
    override val required = listOf("index", "purpose")

    override suspend fun confirmationPrompt(input: ToolInput): String? {
        val svc = service() ?: return null
        val el = svc.element(input.int("index") ?: return null) ?: return null
        val sensitive = input.bool("sensitive") == true ||
            withContext(Dispatchers.Main) { looksSensitive(svc.describeForSafety(el)) }
        if (!sensitive) return null
        val purpose = input.string("purpose") ?: "toccare ${el.label}"
        return "Sto per $purpose. Confermi?"
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val svc = service() ?: return ToolResult.error(NEED_ACCESSIBILITY)
        val index = input.int("index") ?: return ToolResult.error("Manca il numero dell'elemento")
        return withContext(Dispatchers.Main) {
            val el = svc.element(index)
                ?: return@withContext ToolResult.error("Elemento $index non trovato: rileggi lo schermo con read_screen.")
            if (!svc.tap(el, input.bool("long_press") == true)) {
                return@withContext ToolResult.error("Il tocco su ${el.label} non è riuscito. Rileggi lo schermo e riprova.")
            }
            ToolResult.ok("Toccato: ${el.label}.\n" + settledScreen(svc))
        }
    }
}

class TapPositionTool : MemyTool {
    override val name = "tap_position"
    override val description =
        "Tocca un punto dello schermo indicato in percentuale (da usare dopo look_at_screen, quando l'elemento non compare in read_screen). " +
            "Se il tocco invia, paga, elimina o pubblica qualcosa imposta sensitive=true."
    override val properties = mapOf(
        "x_percent" to prop("integer", "Posizione orizzontale, 0 = bordo sinistro, 100 = bordo destro"),
        "y_percent" to prop("integer", "Posizione verticale, 0 = in alto, 100 = in basso"),
        "purpose" to prop("string", "Cosa ottiene questo tocco, in poche parole in italiano"),
        "sensitive" to prop("boolean", "true se il tocco ha effetti reali non annullabili"),
    )
    override val required = listOf("x_percent", "y_percent", "purpose")

    override suspend fun confirmationPrompt(input: ToolInput): String? {
        val purpose = input.string("purpose").orEmpty()
        return if (input.bool("sensitive") == true || looksSensitive(purpose)) "Sto per ${purpose.ifBlank { "toccare lo schermo" }}. Confermi?" else null
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val svc = service() ?: return ToolResult.error(NEED_ACCESSIBILITY)
        val x = (input.int("x_percent") ?: return ToolResult.error("Manca x_percent")).coerceIn(0, 100)
        val y = (input.int("y_percent") ?: return ToolResult.error("Manca y_percent")).coerceIn(0, 100)
        return withContext(Dispatchers.Main) {
            val dm = svc.resources.displayMetrics
            if (!svc.tapAt(dm.widthPixels * x / 100f, dm.heightPixels * y / 100f)) {
                return@withContext ToolResult.error("Il tocco non è riuscito.")
            }
            ToolResult.ok("Toccato il punto ($x%, $y%).\n" + settledScreen(svc))
        }
    }
}

class TypeTextTool : MemyTool {
    override val name = "type_text"
    override val description =
        "Scrive un testo in un campo. Indica il numero del campo (da read_screen); senza numero scrive nel campo che ha il cursore. " +
            "Sostituisce il contenuto del campo. Con press_enter=true preme anche Invio (utile per le ricerche). Non usarlo mai per password o codici di pagamento."
    override val properties = mapOf(
        "text" to prop("string", "Testo da scrivere"),
        "index" to prop("integer", "Numero del campo di testo (facoltativo)"),
        "press_enter" to prop("boolean", "true per premere Invio dopo aver scritto"),
    )
    override val required = listOf("text")

    override suspend fun execute(input: ToolInput): ToolResult {
        val svc = service() ?: return ToolResult.error(NEED_ACCESSIBILITY)
        val text = input.requireString("text")
        return withContext(Dispatchers.Main) {
            val index = input.int("index")
            val ok = if (index != null) {
                val el = svc.element(index)
                    ?: return@withContext ToolResult.error("Campo $index non trovato: rileggi lo schermo con read_screen.")
                svc.setText(el, text) || run {
                    // Alcune app accettano il testo solo dopo un tocco sul campo.
                    svc.tap(el); delay(400); svc.setTextInFocused(text)
                }
            } else svc.setTextInFocused(text)
            if (!ok) return@withContext ToolResult.error("Non riesco a scrivere qui: tocca prima il campo di testo e riprova.")
            if (input.bool("press_enter") == true) {
                delay(250)
                if (!svc.pressEnter()) return@withContext ToolResult.ok("Testo scritto, ma Invio non è disponibile: tocca il pulsante di ricerca/invio.\n" + settledScreen(svc))
            }
            ToolResult.ok("Testo scritto.\n" + settledScreen(svc))
        }
    }
}

class ScrollTool : MemyTool {
    override val name = "scroll"
    override val description = "Scorre lo schermo per vedere altro contenuto. 'down' mostra ciò che sta più in basso."
    override val properties = mapOf(
        "direction" to enumProp("Direzione", listOf("down", "up", "left", "right")),
        "index" to prop("integer", "Numero dell'elemento scorrevole (facoltativo)"),
    )
    override val required = listOf("direction")

    override suspend fun execute(input: ToolInput): ToolResult {
        val svc = service() ?: return ToolResult.error(NEED_ACCESSIBILITY)
        val dir = input.string("direction") ?: "down"
        return withContext(Dispatchers.Main) {
            val el = input.int("index")?.let { svc.element(it) }
            if (!svc.scroll(dir, el)) return@withContext ToolResult.error("Non si può scorrere oltre in questa direzione.")
            ToolResult.ok(settledScreen(svc))
        }
    }
}

class PressButtonTool : MemyTool {
    override val name = "press_button"
    override val description =
        "Preme un tasto di sistema: back (indietro), home, recents (app recenti), notifications (apre la tendina delle notifiche), " +
            "quick_settings (interruttori rapidi come Wi-Fi e Bluetooth), enter (Invio della tastiera), screenshot, lock_screen, power_menu."
    override val properties = mapOf(
        "button" to enumProp(
            "Tasto da premere",
            listOf("back", "home", "recents", "notifications", "quick_settings", "enter", "screenshot", "lock_screen", "power_menu"),
        ),
    )
    override val required = listOf("button")

    override suspend fun execute(input: ToolInput): ToolResult {
        val svc = service() ?: return ToolResult.error(NEED_ACCESSIBILITY)
        val button = input.requireString("button")
        return withContext(Dispatchers.Main) {
            if (!svc.pressButton(button)) return@withContext ToolResult.error("Tasto '$button' non disponibile in questo momento.")
            if (button == "lock_screen" || button == "screenshot") ToolResult.ok("Fatto.")
            else ToolResult.ok(settledScreen(svc, 800))
        }
    }
}

class WaitTool : MemyTool {
    override val name = "wait"
    override val description = "Aspetta qualche secondo (es. mentre una pagina si carica), poi restituisce lo schermo."
    override val properties = mapOf("seconds" to prop("integer", "Secondi da attendere, da 1 a 10"))
    override val required = listOf("seconds")
    override suspend fun execute(input: ToolInput): ToolResult {
        delay((input.int("seconds") ?: 2).coerceIn(1, 10) * 1000L)
        val svc = service() ?: return ToolResult.ok("Attesa terminata.")
        return withContext(Dispatchers.Main) { ToolResult.ok(readWithRetry(svc)) }
    }
}
