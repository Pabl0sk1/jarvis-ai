package com.pabl0sk1.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Memoria a largo plazo: lo que Jarvis sabe del usuario entre una sesión y otra.
 *
 * Además de su archivo privado, guarda una copia en Android/data/com.pabl0sk1.jarvis/files,
 * que se puede copiar al PC con respaldar_memoria.bat. Si la app se reinstala y esa copia
 * se vuelve a poner en su sitio (restaurar_memoria.bat), la importa al arrancar.
 */
class Memoria(contexto: Context) {

    private val archivo = File(contexto.filesDir, NOMBRE)
    private val copia: File? = contexto.getExternalFilesDir(null)?.let { File(it, NOMBRE) }
    private val datos = mutableListOf<Pair<String, String>>()  // (dato, fecha)

    init {
        val origen = when {
            archivo.exists() -> archivo
            copia?.exists() == true -> copia  // restaurada desde el PC tras reinstalar
            else -> null
        }
        if (origen != null) {
            val lista = JSONArray(origen.readText())
            for (i in 0 until lista.length()) {
                val d = lista.getJSONObject(i)
                datos += d.getString("dato") to d.optString("fecha")
            }
            if (origen != archivo || copia?.exists() != true) guardar()
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
    fun comoJson(): JSONArray = JSONArray().apply {
        datos.forEach { (dato, fecha) -> put(JSONObject().put("dato", dato).put("fecha", fecha)) }
    }

    /** Añade los recuerdos de otro dispositivo (la notebook) que aún no tenga. Devuelve cuántos añadió. */
    @Synchronized
    fun fusionar(otros: JSONArray): Int {
        val conocidos = datos.map { it.first.trim().lowercase() }.toMutableSet()
        var anadidos = 0
        for (i in 0 until otros.length()) {
            val otro = otros.optJSONObject(i) ?: continue
            val dato = otro.optString("dato").trim()
            if (dato.isEmpty() || !conocidos.add(dato.lowercase())) continue
            datos += dato to otro.optString("fecha").ifEmpty { LocalDate.now().toString() }
            anadidos++
        }
        if (anadidos > 0) guardar()
        return anadidos
    }

    @Synchronized
    fun comoTexto(): String =
        if (datos.isEmpty()) "(todavía no sabes nada del usuario)"
        else datos.joinToString("\n") { (dato, fecha) -> "- $dato (anotado el $fecha)" }

    private fun guardar() {
        // Mismo formato que datos/memoria.json del Jarvis del PC
        val lista = JSONArray()
        datos.forEach { (dato, fecha) -> lista.put(JSONObject().put("dato", dato).put("fecha", fecha)) }
        val texto = lista.toString(2)
        archivo.writeText(texto)
        copia?.runCatching { writeText(texto) }
    }

    companion object {
        private const val NOMBRE = "memoria.json"
    }
}
