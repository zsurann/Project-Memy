package com.nexa.assistant.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Gli "occhi e le mani" di Memy: legge il contenuto dello schermo e agisce nelle altre app
 * (tocchi, testo, scorrimento, tasti di sistema). Va attivato dall'utente nelle impostazioni di accessibilità.
 */
class MemyAccessibilityService : AccessibilityService() {

    /** Un elemento dello schermo, numerato per poter essere toccato in seguito. */
    class Element(val index: Int, val node: AccessibilityNodeInfo, val label: String, val bounds: Rect)

    private var elements: List<Element> = emptyList()
    private var statusView: View? = null
    private var confirmView: View? = null

    var currentPackage: String = ""
        private set

    override fun onServiceConnected() {
        instance = this
        // Il sistema avvia questo servizio da solo (anche dopo un riavvio): da qui si riattiva "Ehi Memy".
        com.nexa.assistant.MemyWakeService.sync(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.takeIf { it.isNotBlank() }?.let { currentPackage = it }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        hideStatus()
        hideConfirm()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ leggere lo schermo

    /** Descrive lo schermo attuale come elenco numerato di elementi. */
    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "Schermo non leggibile in questo momento (nessuna finestra attiva o app protetta)."
        val list = mutableListOf<Element>()
        collect(root, list, 0)
        elements = list
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg.isNotBlank()) currentPackage = pkg
        if (list.isEmpty()) return "App in primo piano: $pkg. Nessun elemento leggibile (l'app potrebbe proteggere il contenuto: prova look_at_screen)."
        return buildString {
            append("App in primo piano: ").append(pkg).append('\n')
            list.forEach { append('[').append(it.index).append("] ").append(it.label).append('\n') }
            if (list.size >= MAX_ELEMENTS) append("… (elenco troncato: scorri per vedere altro)\n")
        }
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<Element>, depth: Int) {
        if (node == null || out.size >= MAX_ELEMENTS || depth > 40) return
        if (!node.isVisibleToUser) return
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val text = if (node.isPassword) "••••" else node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString()?.trim().orEmpty() else ""
        val interactive = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable || node.isLongClickable
        val hasText = text.isNotEmpty() || desc.isNotEmpty()
        if ((interactive || hasText) && !bounds.isEmpty) {
            val type = node.className?.toString()?.substringAfterLast('.').orEmpty()
            val label = buildString {
                append(type.ifBlank { "Elemento" })
                val shown = listOf(text, desc).filter { it.isNotEmpty() }.distinct().joinToString(" | ")
                if (shown.isNotEmpty()) append(" \"").append(shown.take(MAX_TEXT)).append('"')
                else if (hint.isNotEmpty()) append(" (suggerimento: ").append(hint.take(60)).append(')')
                val flags = buildList {
                    if (node.isEditable) add("campo di testo")
                    else if (node.isClickable) add("toccabile")
                    if (node.isScrollable) add("scorrevole")
                    if (node.isCheckable) add(if (node.isChecked) "attivo" else "non attivo")
                    if (node.isSelected) add("selezionato")
                    if (!node.isEnabled) add("disabilitato")
                }
                if (flags.isNotEmpty()) append(" (").append(flags.joinToString(", ")).append(')')
            }
            out += Element(out.size + 1, node, label, bounds)
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), out, depth + 1)
    }

    fun element(index: Int): Element? = elements.firstOrNull { it.index == index }

    /** Testo dell'elemento e dei suoi figli/antenati vicini: serve a capire se un tocco è "delicato". */
    fun describeForSafety(el: Element): String = buildString {
        append(el.label)
        fun texts(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 3) return
            n.text?.let { append(' ').append(it) }
            n.contentDescription?.let { append(' ').append(it) }
            for (i in 0 until n.childCount) texts(n.getChild(i), depth + 1)
        }
        texts(el.node, 0)
        el.node.viewIdResourceName?.let { append(' ').append(it.substringAfterLast('/')) }
    }

    // ------------------------------------------------------------------ agire

    suspend fun tap(el: Element, long: Boolean = false): Boolean {
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var n: AccessibilityNodeInfo? = el.node
        var hops = 0
        while (n != null && hops < 6) {
            val can = if (long) n.isLongClickable else n.isClickable
            if (can && n.performAction(action)) return true
            n = n.parent
            hops++
        }
        // Nessun nodo "toccabile": tocco fisico al centro dell'elemento.
        return tapAt(el.bounds.exactCenterX(), el.bounds.exactCenterY(), if (long) 650L else 60L)
    }

    suspend fun tapAt(x: Float, y: Float, durationMs: Long = 60L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return gesture(GestureDescription.StrokeDescription(path, 0, durationMs))
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350L): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return gesture(GestureDescription.StrokeDescription(path, 0, durationMs))
    }

    private suspend fun gesture(stroke: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val ok = dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                    override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
                },
                null,
            )
            if (!ok && cont.isActive) cont.resume(false)
        }

    fun setText(el: Element, text: String): Boolean {
        val target = if (el.node.isEditable) el.node else findEditable(el.node) ?: return false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Scrive nel campo che ha il cursore in questo momento. */
    fun setTextInFocused(text: String): Boolean {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) findEditable(node.getChild(i))?.let { return it }
        return null
    }

    /** Preme "Invio" sulla tastiera (es. per avviare una ricerca). */
    fun pressEnter(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    suspend fun scroll(direction: String, el: Element?): Boolean {
        val forward = direction == "down" || direction == "right"
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val target = el?.node?.takeIf { it.isScrollable } ?: findScrollable(rootInActiveWindow)
        if ((direction == "down" || direction == "up") && target?.performAction(action) == true) return true
        // Ripiego: trascinamento sullo schermo.
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        return when (direction) {
            "down" -> swipe(w / 2, h * 0.72f, w / 2, h * 0.28f)
            "up" -> swipe(w / 2, h * 0.28f, w / 2, h * 0.72f)
            "right" -> swipe(w * 0.8f, h / 2, w * 0.2f, h / 2)
            else -> swipe(w * 0.2f, h / 2, w * 0.8f, h / 2)
        }
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) findScrollable(node.getChild(i))?.let { return it }
        return null
    }

    fun pressButton(button: String): Boolean = when (button) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "lock_screen" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        "screenshot" -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        "power_menu" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        "enter" -> pressEnter()
        else -> false
    }

    /** Cattura lo schermo come immagine (Android 11+), per le app che non espongono il testo. */
    suspend fun captureScreen(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        val wasVisible = statusView?.visibility == View.VISIBLE
        statusView?.visibility = View.INVISIBLE
        return try {
            suspendCancellableCoroutine { cont ->
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val bmp = runCatching {
                            Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                        result.hardwareBuffer.close()
                        if (cont.isActive) cont.resume(bmp)
                    }

                    override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
                })
            }
        } finally {
            if (wasVisible) statusView?.visibility = View.VISIBLE
        }
    }

    // ------------------------------------------------------------------ indicatori a schermo

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), 0x33FFFFFF)
    }

    private fun overlayParams(touchable: Boolean, gravity: Int, yDp: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        this.gravity = gravity
        y = dp(yDp)
    }

    /** Mostra in alto "Memy al lavoro · tocca per fermare" mentre Memy usa un'altra app. */
    fun showStatus(text: String, onStop: () -> Unit) {
        val existing = statusView as? TextView
        if (existing != null) {
            existing.text = text
            return
        }
        val view = TextView(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = pill(0xE6221B4B.toInt(), 22)
            setOnClickListener { onStop() }
        }
        runCatching {
            getSystemService(WindowManager::class.java)
                .addView(view, overlayParams(true, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 44))
            statusView = view
        }
    }

    fun hideStatus() {
        statusView?.let { v -> runCatching { getSystemService(WindowManager::class.java).removeView(v) } }
        statusView = null
    }

    /** Chiede conferma con un riquadro sopra l'app in uso. */
    suspend fun confirm(prompt: String, external: CompletableDeferred<Boolean>): Boolean {
        hideConfirm()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            background = pill(0xF2161233.toInt(), 26)
        }
        box.addView(TextView(this).apply {
            text = "Memy chiede conferma"
            setTextColor(0xFF22D3EE.toInt()); textSize = 12f
        })
        box.addView(TextView(this).apply {
            text = prompt
            setTextColor(0xFFFFFFFF.toInt()); textSize = 16f
            setPadding(0, dp(6), 0, dp(14))
            maxWidth = resources.displayMetrics.widthPixels - dp(80)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, color: Int, value: Boolean) = TextView(this).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(11), dp(22), dp(11))
            background = pill(color, 16)
            setOnClickListener { external.complete(value) }
        }
        row.addView(button("Sì, procedi", 0xFF6D4AFF.toInt(), true))
        row.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        row.addView(button("Annulla", 0x33FFFFFF, false))
        box.addView(row)
        runCatching {
            getSystemService(WindowManager::class.java)
                .addView(box, overlayParams(true, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 96))
            confirmView = box
        }.onFailure { return false }
        return try {
            external.await()
        } finally {
            hideConfirm()
        }
    }

    private fun hideConfirm() {
        confirmView?.let { v -> runCatching { getSystemService(WindowManager::class.java).removeView(v) } }
        confirmView = null
    }

    companion object {
        private const val MAX_ELEMENTS = 140
        private const val MAX_TEXT = 110

        /** Il servizio attivo, o null se l'utente non l'ha ancora abilitato. */
        @Volatile
        var instance: MemyAccessibilityService? = null
            private set
    }
}
