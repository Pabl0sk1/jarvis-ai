package com.pabl0sk1.jarvis

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Control de la tele Samsung (Tizen) desde el celular, como jarvis/tele.py del PC
 * (mismo protocolo que la librería samsungtvws). Llamadas bloqueantes: fuera del hilo principal.
 *
 * La primera vez hay que emparejarla (botón en Ajustes): la tele muestra un aviso y, al aceptarlo,
 * devuelve un token que se guarda en el celular.
 */
class TeleSamsung(contexto: Context) {

    private val ip = BuildConfig.TELE_IP
    private val mac = BuildConfig.TELE_MAC
    private val preferencias = contexto.getSharedPreferences("tele", Context.MODE_PRIVATE)

    fun configurada() = ip.isNotEmpty()
    fun emparejada() = token() != null

    fun encendida(): Boolean = try {
        CLIENTE_REST.newCall(Request.Builder().url("http://$ip:8001/api/v2/").build()).execute().use { it.isSuccessful }
    } catch (e: IOException) {
        false
    }

    /** Pide permiso en la tele (espera hasta 60 s a que lo acepten). */
    fun emparejar(): String {
        if (!encendida()) return NO_RESPONDE
        sesion(esperaConexion = 60) {}
        return if (emparejada()) "Tele emparejada." else "La tele no dio permiso."
    }

    fun controlar(accion: String, veces: Int = 1): String {
        if (accion == "encender") return encender()
        if (!encendida()) return NO_RESPONDE
        if (accion == "apagar") {
            pulsar("KEY_POWER", 1)
            return "Tele apagada."
        }
        val tecla = TECLAS[accion] ?: return "No conozco la acción $accion."
        pulsar(tecla, veces.coerceIn(1, 30))
        return "Hecho."
    }

    fun apps(): String {
        if (!encendida()) return NO_RESPONDE
        return listaDeApps().joinToString(", ") { it.second }.ifEmpty { "No pude leer las apps de la tele." }
    }

    fun abrirApp(app: String): String {
        if (!encendida()) return "$NO_RESPONDE Si está apagada, hay que encenderla primero."
        val buscada = app.trim().lowercase()
        val instaladas = listaDeApps()
        val encontrada = instaladas.firstOrNull { it.second.lowercase().contains(buscada) }
        val id = encontrada?.first ?: APPS_CONOCIDAS[buscada]
            ?: return "No encuentro $app en la tele. Apps instaladas: " + instaladas.joinToString(", ") { it.second }
        sesion { it.send(emitir("ed.apps.launch", JSONObject().put("appId", id).put("action_type", "DEEP_LINK"))) }
        return "Abriendo ${encontrada?.second ?: app} en la tele."
    }

    // --- Protocolo ----------------------------------------------------------------

    private fun encender(): String {
        if (encendida()) return "La tele ya estaba encendida."
        if (mac.isEmpty()) return "No sé la MAC de la tele (JARVIS_TELE_MAC), así que no puedo encenderla por red."
        despertarPorRed()
        repeat(15) {
            Thread.sleep(1000)
            if (encendida()) return "Tele encendida."
        }
        return "He mandado la orden de encendido, pero la tele no responde. ¿Está activada «Conexión con el móvil»?"
    }

    private fun pulsar(tecla: String, veces: Int) = sesion { socket ->
        repeat(veces) {
            socket.send(JSONObject().put("method", "ms.remote.control").put("params", JSONObject()
                .put("Cmd", "Click").put("DataOfCmd", tecla).put("Option", "false")
                .put("TypeOfRemote", "SendRemoteKey")).toString())
            Thread.sleep(300)
        }
    }

    private fun listaDeApps(): List<Pair<String, String>> {
        val respuesta = sesion(esperarEvento = "ed.installedApp.get") {
            it.send(emitir("ed.installedApp.get", null))
        } ?: return emptyList()
        val lista = respuesta.optJSONObject("data")?.optJSONArray("data") ?: return emptyList()
        return (0 until lista.length()).map { lista.getJSONObject(it) }.map { it.optString("appId") to it.optString("name") }
    }

    /**
     * Abre el WebSocket, espera a que la tele confirme la conexión, ejecuta [accion] y, si se
     * indica, espera el evento de respuesta. Devuelve ese evento (o null).
     */
    private fun sesion(esperarEvento: String? = null, esperaConexion: Long = 8,
                       accion: (WebSocket) -> Unit): JSONObject? {
        if (!emparejada() && esperaConexion < 60) {
            throw IOException("La tele no está emparejada con el celular: empareja la tele desde Ajustes.")
        }
        val conectada = CountDownLatch(1)
        val terminada = CountDownLatch(1)
        val evento = AtomicReference<JSONObject?>()
        val fallo = AtomicReference<String?>()
        val nombre = Base64.encodeToString("Jarvis celular".toByteArray(), Base64.NO_WRAP)
        val url = "wss://$ip:8002/api/v2/channels/samsung.remote.control?name=$nombre" +
            (token()?.let { "&token=$it" } ?: "")

        val socket = CLIENTE_TV.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val mensaje = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (val nombreEvento = mensaje.optString("event")) {
                    "ms.channel.connect" -> {
                        mensaje.optJSONObject("data")?.optString("token")?.takeIf { it.isNotEmpty() }
                            ?.let { preferencias.edit().putString("token", it).apply() }
                        conectada.countDown()
                    }
                    "ms.channel.unauthorized" -> {
                        fallo.set("La tele no dio permiso: acepta el aviso en la tele.")
                        conectada.countDown()
                        terminada.countDown()
                    }
                    else -> if (nombreEvento == esperarEvento) {
                        evento.set(mensaje)
                        terminada.countDown()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fallo.set(t.message ?: "error de conexión")
                conectada.countDown()
                terminada.countDown()
            }
        })
        try {
            if (!conectada.await(esperaConexion, TimeUnit.SECONDS)) throw IOException("La tele no respondió.")
            fallo.get()?.let { throw IOException(it) }
            accion(socket)
            if (esperarEvento != null) terminada.await(8, TimeUnit.SECONDS) else Thread.sleep(300)
            return evento.get()
        } finally {
            socket.close(1000, null)
        }
    }

    private fun token(): String? = preferencias.getString("token", null)

    private fun emitir(evento: String, datos: JSONObject?): String =
        JSONObject().put("method", "ms.channel.emit").put("params", JSONObject()
            .put("event", evento).put("to", "host").apply { if (datos != null) put("data", datos) }).toString()

    /** Paquete mágico de Wake-on-LAN: 6 bytes 0xFF y la MAC repetida 16 veces. */
    private fun despertarPorRed() {
        val bytesMac = mac.split(":", "-").map { it.toInt(16).toByte() }.toByteArray()
        val paquete = ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { bytesMac[it % 6] }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val subred = ip.substringBeforeLast(".") + ".255"
            for (destino in listOf("255.255.255.255", subred)) {
                socket.send(DatagramPacket(paquete, paquete.size, InetAddress.getByName(destino), 9))
            }
        }
    }

    companion object {
        const val NO_RESPONDE = "La tele no responde: está apagada o el celular no está en la red de casa (COMMON)."
        val TECLAS = mapOf(
            "subir_volumen" to "KEY_VOLUP", "bajar_volumen" to "KEY_VOLDOWN", "silenciar" to "KEY_MUTE",
            "subir_canal" to "KEY_CHUP", "bajar_canal" to "KEY_CHDOWN",
            "inicio" to "KEY_HOME", "volver" to "KEY_RETURN", "ok" to "KEY_ENTER",
            "arriba" to "KEY_UP", "abajo" to "KEY_DOWN", "izquierda" to "KEY_LEFT", "derecha" to "KEY_RIGHT",
            "fuente" to "KEY_SOURCE", "hdmi" to "KEY_HDMI", "reproducir" to "KEY_PLAY", "pausa" to "KEY_PAUSE",
        )
        val ACCIONES = listOf("encender", "apagar") + TECLAS.keys
        private val APPS_CONOCIDAS = mapOf("youtube" to "111299001912", "netflix" to "11101200001",
            "prime video" to "3201512006785", "spotify" to "3201606009684")

        private val CLIENTE_REST = HTTP.newBuilder().callTimeout(2, TimeUnit.SECONDS).build()

        // La tele usa un certificado propio (autofirmado): sólo este cliente lo acepta, y sólo se usa
        // para hablar con la tele de la red de casa
        @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
        private val CLIENTE_TV: OkHttpClient = run {
            val confiarEnTodo = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(confiarEnTodo), null) }
            HTTP.newBuilder()
                .sslSocketFactory(ssl.socketFactory, confiarEnTodo)
                .hostnameVerifier { _, _ -> true }
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}
