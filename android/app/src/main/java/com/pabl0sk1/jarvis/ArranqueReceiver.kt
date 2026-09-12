package com.pabl0sk1.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Al encender el celular (por ejemplo, tras quedarse sin batería) muestra un aviso para reactivar
 * Jarvis con un toque. Android 14+ no deja que un servicio de micrófono arranque solo al encender.
 */
class ArranqueReceiver : BroadcastReceiver() {

    override fun onReceive(contexto: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val gestor = contexto.getSystemService(NotificationManager::class.java)
        gestor.createNotificationChannel(NotificationChannel(CANAL, "Reactivar Jarvis", NotificationManager.IMPORTANCE_HIGH))
        val abrir = PendingIntent.getActivity(contexto, 0,
            Intent(contexto, MainActivity::class.java).putExtra(MainActivity.EXTRA_ENCENDER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)
        gestor.notify(NOTIFICACION, Notification.Builder(contexto, CANAL)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle("Jarvis")
            .setContentText("El celular se reinició. Toca para volver a activarme.")
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build())
    }

    companion object {
        private const val CANAL = "reactivar"
        private const val NOTIFICACION = 2
    }
}
