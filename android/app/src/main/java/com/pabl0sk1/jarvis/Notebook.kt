package com.pabl0sk1.jarvis

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Habla con el Jarvis de la notebook (jarvis/servidor.py): lo encuentra solo en la red de casa
 * preguntando "JARVIS?" por difusión UDP y le manda órdenes por HTTP con la clave compartida.
 * Todas las llamadas son bloqueantes: úsalas fuera del hilo principal.
 */
class Notebook {

    @Volatile
    private var direccion: String? = null  // "http://ip:puerto" de la última vez que se encontró

    fun configurada() = BuildConfig.CLAVE_RED.isNotEmpty()

    fun orden(herramienta: String, argumentos: JSONObject = JSONObject()): String {
        val respuesta = pedir("/orden", JSONObject().put("herramienta", herramienta).put("argumentos", argumentos))
        return respuesta.optString("resultado").ifEmpty { respuesta.optString("error", "La notebook no respondió.") }
    }

    /** Envía los recuerdos del celular y devuelve los de los dos ya fusionados. */
    fun fusionarMemoria(datos: JSONArray): JSONArray =
        pedir("/memoria", JSONObject().put("datos", datos)).getJSONArray("datos")

    fun olvidar(texto: String) {
        pedir("/memoria/olvidar", JSONObject().put("texto", texto))
    }

    private fun pedir(ruta: String, cuerpo: JSONObject?): JSONObject {
        val base = direccion ?: buscar() ?: throw IOException(NO_ENCONTRADA)
        return try {
            llamar(base, ruta, cuerpo)
        } catch (error: IOException) {
            // La IP de la notebook pudo cambiar: se vuelve a buscar una vez
            direccion = null
            llamar(buscar() ?: throw IOException(NO_ENCONTRADA), ruta, cuerpo)
        }
    }

    private fun buscar(): String? = descubrir() ?: escanearRed()

    /** Plan B si la difusión UDP no llega (algunos routers la filtran): prueba el puerto del servidor en toda la red. */
    private fun escanearRed(): String? {
        val propia = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }.map { it.address }
            .filterIsInstance<Inet4Address>().firstOrNull() ?: return null
        val prefijo = propia.hostAddress!!.substringBeforeLast(".")
        val grupo = Executors.newFixedThreadPool(64)
        try {
            val pruebas = (1..254).map { n -> grupo.submit(Callable { "$prefijo.$n".takeIf { abierto(it) } }) }
            val ip = pruebas.firstNotNullOfOrNull { runCatching { it.get() }.getOrNull() } ?: return null
            return "http://$ip:$PUERTO_HTTP".also { direccion = it }
        } finally {
            grupo.shutdownNow()
        }
    }

    private fun abierto(ip: String): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(ip, PUERTO_HTTP), 400) }
    }.isSuccess

    private fun llamar(base: String, ruta: String, cuerpo: JSONObject?): JSONObject {
        val peticion = Request.Builder().url(base + ruta)
            .header("X-Jarvis-Clave", BuildConfig.CLAVE_RED)
            .apply { if (cuerpo != null) post(cuerpo.toString().toRequestBody(JSON)) }
            .build()
        CLIENTE.newCall(peticion).execute().use { r ->
            val texto = r.body?.string().orEmpty()
            if (r.code == 401) {
                return JSONObject().put("error", "La notebook rechazó la clave: compila la app con el mismo .env.")
            }
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${texto.take(200)}")
            return JSONObject(texto)
        }
    }

    /** Pregunta "JARVIS?" a toda la red y espera la respuesta de la notebook. */
    private fun descubrir(): String? {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 1500
            val mensaje = "JARVIS?".toByteArray()
            for (destino in direccionesDeDifusion()) {
                runCatching { socket.send(DatagramPacket(mensaje, mensaje.size, destino, PUERTO_DESCUBRIR)) }
            }
            val bufer = ByteArray(512)
            val paquete = DatagramPacket(bufer, bufer.size)
            return try {
                socket.receive(paquete)
                val datos = JSONObject(String(bufer, 0, paquete.length))
                "http://${paquete.address.hostAddress}:${datos.getInt("puerto")}".also { direccion = it }
            } catch (e: SocketTimeoutException) {
                null
            }
        }
    }

    /** 255.255.255.255 más la difusión de cada red del celular (algunos routers sólo pasan esta). */
    private fun direccionesDeDifusion(): List<InetAddress> {
        val redes = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.broadcast }
        return (listOf(InetAddress.getByName("255.255.255.255")) + redes).distinct()
    }

    companion object {
        private const val PUERTO_DESCUBRIR = 47800
        private const val PUERTO_HTTP = 47801
        private const val NO_ENCONTRADA = "No encuentro la notebook en la red. Tiene que estar encendida, " +
            "con Jarvis abierto y en la misma red Wi-Fi que el celular."
        private val JSON = "application/json".toMediaType()
        private val CLIENTE = HTTP.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    }
}
