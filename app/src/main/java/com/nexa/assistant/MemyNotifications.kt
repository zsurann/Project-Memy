package com.nexa.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Aspetto comune delle notifiche di Memy: la sfera Aurora, il viola di Memy e l'icona grande. */
object MemyNotifications {
    /** Il viola della sfera, usato dal sistema per colorare l'icona e i pulsanti della notifica. */
    const val COLOR = 0xFF8B5CF6.toInt()

    @Volatile private var largeIcon: Bitmap? = null

    /** L'icona dell'app (la sfera su sfondo notte) disegnata in un cerchio. */
    private fun largeIcon(context: Context): Bitmap? {
        largeIcon?.let { return it }
        return runCatching {
            val d = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)!!
            val px = (context.resources.displayMetrics.density * 64).toInt().coerceAtLeast(96)
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.clipPath(Path().apply { addCircle(px / 2f, px / 2f, px / 2f, Path.Direction.CW) })
            d.setBounds(0, 0, px, px)
            d.draw(canvas)
            bmp
        }.getOrNull()?.also { largeIcon = it }
    }

    fun builder(context: Context, channel: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_memy_tile)
            .setColor(COLOR)
            .setColorized(false)
            .apply { largeIcon(context)?.let { setLargeIcon(it) } }
}
