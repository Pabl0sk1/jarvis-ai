package com.pabl0sk1.jarvis

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * Prueba los cerebros gratuitos en orden (Gemini y después Groq) hasta que uno responde,
 * con la API compatible con OpenAI (CompatibleOpenAI en jarvis/cerebro.py del PC).
 */
class Cerebro(private val herramientas: Herramientas, private val memoria: Memoria) {

    private class Proveedor(val nombre: String, val modelo: String, val url: String, clave: String,
                            espera: Long, val reintentar: Boolean) {
        val autorizacion = "Bearer $clave"
        val cliente: OkHttpClient = HTTP.newBuilder().callTimeout(espera, TimeUnit.SECONDS).build()
    }

    private val proveedores = buildList {
        // Gemini a veces se cuelga ~30 s: sin reintento, para pasar pronto a Groq
        if (BuildConfig.GEMINI_API_KEY.isNotEmpty()) add(Proveedor("Gemini", BuildConfig.MODELO_GEMINI,
            URL_GEMINI, BuildConfig.GEMINI_API_KEY, espera = 20, reintentar = false))
        // Groq: un reintento, esperando lo que pide cuando se supera su límite por minuto
        if (BuildConfig.GROQ_API_KEY.isNotEmpty()) add(Proveedor("Groq", BuildConfig.MODELO_GROQ,
            URL_GROQ, BuildConfig.GROQ_API_KEY, espera = 45, reintentar = true))
    }
    private val historial = mutableListOf<JSONObject>()

    @Volatile
    var enAuto = false

    fun describir(): String =
        proveedores.joinToString(" → ") { "${it.nombre} (${it.modelo})" }.ifEmpty { "ninguno: faltan claves en el .env" }

    suspend fun responder(texto: String): String = withContext(Dispatchers.IO) {
        val sistema = Personalidad.instrucciones(memoria.comoTexto(), enAuto)
        for (proveedor in proveedores) {
            val respuesta = try {
                conProveedor(proveedor, sistema, texto)
            } catch (error: Exception) {
                Log.w(TAG, "${proveedor.nombre} no responde (${error.message}). Pruebo con el siguiente.")
                continue
            }
            if (respuesta.isNotBlank()) {
                synchronized(historial) {
                    historial += mensaje("user", texto)
                    historial += mensaje("assistant", respuesta)
                    while (historial.size > 2 * MAX_TURNOS) historial.removeAt(0)
                }
                return@withContext respuesta
            }
        }
        Idiomas.actual.let { it.decir(it.sinCerebro) }
    }

    private fun conProveedor(p: Proveedor, sistema: String, texto: String): String {
        val mensajes = JSONArray().put(mensaje("system", sistema))
        synchronized(historial) { historial.forEach { mensajes.put(it) } }
        mensajes.put(mensaje("user", texto))

        var usada = false   // ¿ya usó alguna herramienta en esta respuesta?
        var forzar = false  // ¿hay que obligarle a usar una?
        for (paso in 0 until MAX_PASOS) {
            val cuerpo = JSONObject().put("model", p.modelo).put("messages", mensajes)
            if (paso == MAX_PASOS - 1) {
                // Última ronda sin herramientas, para que conteste con lo que ya tiene
                mensajes.put(mensaje("user", AVISO_ULTIMA_RONDA))
            } else {
                cuerpo.put("tools", herramientas.paraOpenAI())
                if (forzar) cuerpo.put("tool_choice", "required")
            }

            val respuesta = llamar(p, cuerpo)
            respuesta.optJSONObject("usage")?.let {
                Log.i(TAG, "${p.nombre}: ${it.optInt("prompt_tokens")} tokens de entrada, " +
                    "${it.optInt("completion_tokens")} de salida")
            }
            val mensaje = respuesta.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val contenido = if (mensaje.isNull("content")) "" else mensaje.getString("content")
            val llamadas = mensaje.optJSONArray("tool_calls")

            if (llamadas == null || llamadas.length() == 0) {
                if (!usada && !forzar && pideAccion(texto)) {
                    // Algunos modelos dicen "hecho" sin llamar a la herramienta: se repite obligándole
                    Log.i(TAG, "${p.nombre} contestó a una orden sin usar herramientas; se lo repito")
                    forzar = true
                    continue
                }
                return contenido.trim()
            }

            usada = true
            forzar = false
            // Se reenvían tal cual: Gemini necesita recibir de vuelta sus campos extra ("thought_signature")
            mensajes.put(JSONObject().put("role", "assistant").put("content", contenido).put("tool_calls", llamadas))
            for (n in 0 until llamadas.length()) {
                val llamada = llamadas.getJSONObject(n)
                val funcion = llamada.getJSONObject("function")
                val resultado = herramientas.ejecutar(funcion.getString("name"), argumentos(funcion.optString("arguments")))
                mensajes.put(JSONObject().put("role", "tool").put("tool_call_id", llamada.optString("id"))
                    .put("content", resultado))
            }
        }
        return Idiomas.actual.confundido
    }

    private fun llamar(p: Proveedor, cuerpo: JSONObject, reintento: Boolean = false): JSONObject {
        val peticion = Request.Builder().url(p.url)
            .header("Authorization", p.autorizacion)
            .post(cuerpo.toString().toRequestBody(JSON))
            .build()
        p.cliente.newCall(peticion).execute().use { r ->
            val texto = r.body?.string().orEmpty()
            if (r.code == 429 && p.reintentar && !reintento) {
                val segundos = r.header("retry-after")?.toDoubleOrNull()?.coerceAtMost(15.0) ?: 8.0
                Log.i(TAG, "${p.nombre}: límite de uso alcanzado, espero ${segundos}s")
                Thread.sleep((segundos * 1000).toLong())
                return llamar(p, cuerpo, reintento = true)
            }
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${texto.take(300)}")
            return JSONObject(texto)
        }
    }

    companion object {
        private const val TAG = "JarvisCerebro"
        private const val MAX_TURNOS = 10  // turnos de conversación que recuerda
        private const val MAX_PASOS = 5    // rondas de herramientas; en la última tiene que contestar ya
        private const val URL_GEMINI = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        private const val URL_GROQ = "https://api.groq.com/openai/v1/chat/completions"
        private const val AVISO_ULTIMA_RONDA = "(Aviso del sistema: ya no puedes usar más herramientas. " +
            "Responde ahora con la información que tienes.)"
        private val JSON = "application/json".toMediaType()

        // Pedidos que implican hacer algo (igual que ORDEN en jarvis/cerebro.py)
        private val ORDEN = Regex(
            "\\b(apag|enciend|encend|prend|sub[ei]|baj[ae]|pon|abr[eai]|cierr|cambi|silenci|acuerd|" +
                "recuerd|anot|olvid|busc|temporizador|alarma|naveg|llev|turn|open|close|remember|mute|" +
                "switch|play|navigate)",
            RegexOption.IGNORE_CASE,
        )

        fun pideAccion(texto: String): Boolean {
            val sinTildes = Normalizer.normalize(texto, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            return ORDEN.containsMatchIn(sinTildes)
        }

        private fun mensaje(rol: String, contenido: String) = JSONObject().put("role", rol).put("content", contenido)

        private fun argumentos(texto: String?): JSONObject = try {
            JSONObject(texto.takeUnless { it.isNullOrBlank() } ?: "{}")
        } catch (e: JSONException) {
            JSONObject()
        }
    }
}
