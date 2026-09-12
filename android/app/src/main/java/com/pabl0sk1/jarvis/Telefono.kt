package com.pabl0sk1.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.SmsManager

/** Llamadas, SMS, contactos y emergencias. Llamadas bloqueantes: fuera del hilo principal. */
class Telefono(private val contexto: Context) {

    /** Busca un contacto por nombre (primero exacto, si no, el primero que lo contenga): (nombre, número). */
    fun buscarContacto(nombre: String): Pair<String, String>? {
        if (!permiso(Manifest.permission.READ_CONTACTS)) return null
        val telefono = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val columnas = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER)
        contexto.contentResolver.query(telefono, columnas,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%${nombre.trim()}%"), null
        )?.use { cursor ->
            var primero: Pair<String, String>? = null
            while (cursor.moveToNext()) {
                val encontrado = cursor.getString(0) to cursor.getString(1)
                if (encontrado.first.equals(nombre.trim(), ignoreCase = true)) return encontrado
                if (primero == null) primero = encontrado
            }
            return primero
        }
        return null
    }

    /** Nombre del contacto que tiene ese número, si lo hay. */
    fun nombreDe(numero: String): String? {
        if (!permiso(Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(numero))
        contexto.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) return cursor.getString(0) }
        return null
    }

    fun llamar(destino: String): String {
        if (!permiso(Manifest.permission.CALL_PHONE)) return FALTA_PERMISO + "llamadas."
        val (nombre, numero) = resolver(destino) ?: return noEncontrado(destino)
        contexto.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(numero)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Llamando a $nombre."
    }

    fun enviarSms(destino: String, mensaje: String): String {
        if (!permiso(Manifest.permission.SEND_SMS)) return FALTA_PERMISO + "SMS."
        val (nombre, numero) = resolver(destino) ?: return noEncontrado(destino)
        enviar(numero, mensaje)
        return "SMS enviado a $nombre."
    }

    /** Avisa por SMS a los contactos de emergencia (con la ubicación, si se sabe) y llama al 911. */
    fun emergencia(): String {
        val ubicacion = ubicacion()
        val quien = BuildConfig.NOMBRE_USUARIO.ifEmpty { "Tu contacto" }
        val aviso = "EMERGENCIA: $quien pidió ayuda a su asistente y está llamando al 911." +
            (ubicacion?.let { " Ubicación: $it" } ?: "")
        val avisados = if (permiso(Manifest.permission.SEND_SMS)) {
            BuildConfig.CONTACTOS_EMERGENCIA.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .mapNotNull { contacto -> runCatching { resolver(contacto)?.also { enviar(it.second, aviso) }?.first }.getOrNull() }
        } else emptyList()
        val llamada = llamar(NUMERO_EMERGENCIA)
        return llamada + if (avisados.isEmpty()) " No pude avisar a ningún contacto de emergencia." +
            " (Configúralos en JARVIS_CONTACTOS_EMERGENCIA y dame el permiso de SMS.)"
        else " Avisé por SMS a: ${avisados.joinToString(", ")}."
    }

    /** Última ubicación conocida como enlace de Google Maps (sin encender el GPS). */
    @SuppressLint("MissingPermission")
    fun ubicacion(): String? {
        if (!permiso(Manifest.permission.ACCESS_FINE_LOCATION) && !permiso(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return null
        }
        val gestor = contexto.getSystemService(LocationManager::class.java)
        val mejor = gestor.getProviders(true).mapNotNull { runCatching { gestor.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        return "https://maps.google.com/?q=${mejor.latitude},${mejor.longitude}"
    }

    private fun resolver(destino: String): Pair<String, String>? {
        val digitos = destino.count { it.isDigit() }
        return if (digitos >= 3 && digitos >= destino.count { it.isLetter() }) destino to destino
        else buscarContacto(destino)
    }

    private fun enviar(numero: String, mensaje: String) {
        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) contexto.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
        sms.sendMultipartTextMessage(numero, null, sms.divideMessage(mensaje), null, null)
    }

    private fun noEncontrado(destino: String) =
        if (permiso(Manifest.permission.READ_CONTACTS)) "No encuentro a $destino en tus contactos."
        else FALTA_PERMISO + "contactos."

    private fun permiso(nombre: String) = contexto.checkSelfPermission(nombre) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val NUMERO_EMERGENCIA = "911"  // Paraguay
        private const val FALTA_PERMISO = "Me falta un permiso: dámelo en Ajustes → Permisos del teléfono. Permiso de "
    }
}
