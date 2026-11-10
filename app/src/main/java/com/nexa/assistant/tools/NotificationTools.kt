package com.nexa.assistant.tools

import com.nexa.assistant.access.MemyNotificationListener
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Strumenti per leggere le notifiche e rispondere ai messaggi (richiedono l'accesso alle notifiche). */
fun notificationTools(): List<MemyTool> =
    listOf(ReadNotificationsTool(), ReplyNotificationTool(), OpenNotificationTool(), DismissNotificationsTool(), NowPlayingTool())

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

/** L'ultimo elenco mostrato a Claude: i numeri usati negli altri strumenti si riferiscono a questo. */
private var lastListed: List<MemyNotificationListener.Item> = emptyList()

class ReadNotificationsTool : MemyTool {
    override val name = "read_notifications"
    override val description =
        "Elenca le notifiche presenti sul telefono (messaggi WhatsApp/Telegram, email, promemoria…), dalla più recente. " +
            "Ogni notifica ha un numero da usare con reply_to_notification, open_notification o dismiss_notifications."
    override val properties = mapOf("app" to prop("string", "Filtra per nome dell'app (facoltativo), es. 'WhatsApp'"))
    override val required = emptyList<String>()

    override suspend fun execute(input: ToolInput): ToolResult {
        val svc = MemyNotificationListener.instance ?: return ToolResult.error(NEED_NOTIFICATIONS)
        val filter = input.string("app")?.let(::normalize)
        val items = svc.list().filter { filter == null || normalize(it.app).contains(filter) }.take(25)
        lastListed = items
        if (items.isEmpty()) return ToolResult.ok("Nessuna notifica.")
        return ToolResult.ok(items.mapIndexed { i, n ->
            val time = Instant.ofEpochMilli(n.time).atZone(ZoneId.systemDefault()).format(timeFmt)
            "[${i + 1}] ${n.app} · $time · ${n.title}: ${n.text}" + if (n.canReply) " (si può rispondere)" else ""
        }.joinToString("\n"))
    }
}

class ReplyNotificationTool : MemyTool {
    override val name = "reply_to_notification"
    override val description =
        "Risponde a un messaggio direttamente dalla sua notifica (funziona con WhatsApp, Telegram, Messaggi e simili), senza aprire l'app. " +
            "L'app chiede conferma all'utente prima di inviare. Usa il numero dato da read_notifications."
    override val properties = mapOf(
        "index" to prop("integer", "Numero della notifica"),
        "message" to prop("string", "Testo della risposta"),
    )
    override val required = listOf("index", "message")

    private fun item(input: ToolInput) = lastListed.getOrNull((input.int("index") ?: 0) - 1)

    override suspend fun confirmationPrompt(input: ToolInput): String? {
        val n = item(input)?.takeIf { it.canReply } ?: return null
        return "Rispondo a ${n.title} su ${n.app}: \"${input.requireString("message")}\". Confermi?"
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val svc = MemyNotificationListener.instance ?: return ToolResult.error(NEED_NOTIFICATIONS)
        val n = item(input) ?: return ToolResult.error("Notifica non trovata: rileggi le notifiche con read_notifications.")
        if (!n.canReply) return ToolResult.error("Questa notifica non permette la risposta rapida. Apri l'app e usa gli strumenti dello schermo.")
        return if (svc.reply(n.key, input.requireString("message"))) ToolResult.ok("Risposta inviata a ${n.title} su ${n.app}.")
        else ToolResult.error("Invio non riuscito: la notifica potrebbe non esserci più.")
    }
}

class OpenNotificationTool : MemyTool {
    override val name = "open_notification"
    override val description = "Apre l'app o la conversazione collegata a una notifica."
    override val properties = mapOf("index" to prop("integer", "Numero della notifica"))
    override val required = listOf("index")
    override suspend fun execute(input: ToolInput): ToolResult {
        val svc = MemyNotificationListener.instance ?: return ToolResult.error(NEED_NOTIFICATIONS)
        val n = lastListed.getOrNull((input.int("index") ?: 0) - 1)
            ?: return ToolResult.error("Notifica non trovata: rileggi le notifiche.")
        return if (svc.open(n.key)) ToolResult.ok("Aperta la notifica di ${n.app}.") else ToolResult.error("Impossibile aprire la notifica.")
    }
}

class DismissNotificationsTool : MemyTool {
    override val name = "dismiss_notifications"
    override val description = "Cancella una notifica (con il suo numero) oppure tutte le notifiche (senza numero)."
    override val properties = mapOf("index" to prop("integer", "Numero della notifica; ometti per cancellarle tutte"))
    override val required = emptyList<String>()
    override suspend fun execute(input: ToolInput): ToolResult {
        val svc = MemyNotificationListener.instance ?: return ToolResult.error(NEED_NOTIFICATIONS)
        val index = input.int("index")
        if (index == null) return ToolResult.ok("Cancellate ${svc.dismiss(null)} notifiche.")
        val n = lastListed.getOrNull(index - 1) ?: return ToolResult.error("Notifica non trovata: rileggi le notifiche.")
        svc.dismiss(n.key)
        return ToolResult.ok("Notifica di ${n.app} cancellata.")
    }
}

class NowPlayingTool : MemyTool {
    override val name = "get_now_playing"
    override val description = "Dice quale brano, podcast o video è in riproduzione sul telefono."
    override val properties = emptyMap<String, Map<String, Any>>()
    override val required = emptyList<String>()
    override suspend fun execute(input: ToolInput): ToolResult {
        val svc = MemyNotificationListener.instance ?: return ToolResult.error(NEED_NOTIFICATIONS)
        return ToolResult.ok(svc.nowPlaying() ?: "Non c'è nulla in riproduzione.")
    }
}
