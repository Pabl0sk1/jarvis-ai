package com.pabl0sk1.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Memoria a largo plazo: lo que Jarvis sabe del usuario entre una sesión y otra. */
class Memoria(contexto: Context) {

    private val archivo = File(contexto.filesDir, "memoria.json")
    private val datos = mutableListOf<Pair<String, String>>()  // (dato, fecha)

    init {
        if (archivo.exists()) {
            val lista = JSONArray(archivo.readText())
            for (i in 0 until lista.length()) {
                val d = lista.getJSONObject(i)
                datos += d.getString("dato") to d.optString("fecha")
            }
        }
    }

    @Synchronized
    fun recordar(dato: String) {
        datos += dato.trim() to LocalDate.now().toString()
        guardar()
    }

    /** Borra los recuerdos que contienen [texto]. Devuelve cuántos borró. */
    @Synchronized
    fun olvidar(texto: String): Int {
        val antes = datos.size
        datos.removeAll { it.first.contains(texto, ignoreCase = true) }
        guardar()
        return antes - datos.size
    }

    @Synchronized
    fun comoTexto(): String =
        if (datos.isEmpty()) "(todavía no sabes nada del usuario)"
        else datos.joinToString("\n") { (dato, fecha) -> "- $dato (anotado el $fecha)" }

    private fun guardar() {
        val lista = JSONArray()
        datos.forEach { (dato, fecha) -> lista.put(JSONObject().put("dato", dato).put("fecha", fecha)) }
        archivo.writeText(lista.toString(2))
    }
}
