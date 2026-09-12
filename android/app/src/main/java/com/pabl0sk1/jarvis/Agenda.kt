package com.pabl0sk1.jarvis

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

/** Calendario del celular (ver y crear eventos) y alarmas del reloj. */
class Agenda(private val contexto: Context) {

    private val zona = ZoneId.systemDefault()

    /** Eventos desde [desde] durante [dias] días, en texto para el cerebro. */
    fun eventos(desde: LocalDate = LocalDate.now(), dias: Int = 1): String {
        if (!permiso(Manifest.permission.READ_CALENDAR)) return FALTA_PERMISO
        val inicio = desde.atStartOfDay(zona).toInstant().toEpochMilli()
        val fin = desde.plusDays(dias.coerceIn(1, 31).toLong()).atStartOfDay(zona).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .also { ContentUris.appendId(it, inicio); ContentUris.appendId(it, fin) }.build()
        val columnas = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION)
        val lista = mutableListOf<String>()
        contexto.contentResolver.query(uri, columnas, null, null, "${CalendarContract.Instances.BEGIN} ASC")
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val cuando = Instant.ofEpochMilli(cursor.getLong(1)).atZone(zona)
                    val hora = if (cursor.getInt(2) == 1) "todo el día" else cuando.format(HORA)
                    val lugar = cursor.getString(3)?.takeIf { it.isNotBlank() }?.let { " en $it" }.orEmpty()
                    lista += "${cuando.format(DIA)} $hora: ${cursor.getString(0)}$lugar"
                }
            }
        return if (lista.isEmpty()) "No hay nada en la agenda para esos días." else lista.joinToString("\n")
    }

    /** Crea un evento en el calendario principal. [hora] "HH:mm" o vacío para todo el día. */
    fun crearEvento(titulo: String, fecha: String, hora: String?, minutos: Int = 60, lugar: String? = null): String {
        if (!permiso(Manifest.permission.WRITE_CALENDAR)) return FALTA_PERMISO
        val dia = LocalDate.parse(fecha)
        val calendario = calendarioPrincipal() ?: return "No encuentro ningún calendario donde escribir."
        val todoElDia = hora.isNullOrBlank()
        val inicio = if (todoElDia) dia.atStartOfDay(ZoneId.of("UTC")) else dia.atTime(LocalTime.parse(hora)).atZone(zona)
        val fin = if (todoElDia) inicio.plusDays(1) else inicio.plusMinutes(minutos.coerceAtLeast(5).toLong())
        val valores = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendario)
            put(CalendarContract.Events.TITLE, titulo)
            put(CalendarContract.Events.DTSTART, inicio.toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, fin.toInstant().toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, if (todoElDia) "UTC" else zona.id)
            put(CalendarContract.Events.ALL_DAY, if (todoElDia) 1 else 0)
            if (!lugar.isNullOrBlank()) put(CalendarContract.Events.EVENT_LOCATION, lugar)
        }
        contexto.contentResolver.insert(CalendarContract.Events.CONTENT_URI, valores)
            ?: return "No pude guardar el evento."
        return "Evento «$titulo» creado para el ${dia.format(DIA)}" + (if (todoElDia) "." else " a las $hora.")
    }

    /** Pone una alarma en el reloj del celular. [dias]: 1 = lunes ... 7 = domingo (vacío = una sola vez). */
    fun ponerAlarma(hora: Int, minutos: Int, etiqueta: String?, dias: List<Int>): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hora.coerceIn(0, 23))
            .putExtra(AlarmClock.EXTRA_MINUTES, minutos.coerceIn(0, 59))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!etiqueta.isNullOrBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, etiqueta)
        if (dias.isNotEmpty()) {
            // Calendar usa 1 = domingo ... 7 = sábado
            intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(dias.map { if (it == 7) Calendar.SUNDAY else it + 1 }))
        }
        contexto.startActivity(intent)
        return "Alarma puesta a las %02d:%02d".format(hora, minutos) + (if (dias.isEmpty()) "." else ", los días indicados.")
    }

    private fun calendarioPrincipal(): Long? {
        val columnas = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY)
        val filtro = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        contexto.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, columnas, filtro, null, null)?.use { cursor ->
            var primero: Long? = null
            while (cursor.moveToNext()) {
                if (cursor.getInt(1) == 1) return cursor.getLong(0)
                if (primero == null) primero = cursor.getLong(0)
            }
            return primero
        }
        return null
    }

    private fun permiso(nombre: String) = contexto.checkSelfPermission(nombre) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val FALTA_PERMISO = "Me falta el permiso del calendario: dámelo en Ajustes → Permisos del teléfono."
        private val DIA = DateTimeFormatter.ofPattern("EEEE d/MM")
        private val HORA = DateTimeFormatter.ofPattern("HH:mm")
    }
}
