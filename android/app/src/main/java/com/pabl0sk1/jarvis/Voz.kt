package com.pabl0sk1.jarvis

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Texto a voz con la misma voz de Microsoft Edge que el PC (port de edge-tts),
 * y la voz de Google del celular como respaldo sin internet.
 */
class Voz(private val contexto: Context) {

    private val audio = contexto.getSystemService(AudioManager::class.java)
    private val turno = Mutex()  // un temporizador no puede pisar otra respuesta
    private val atributos = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)  // así sale por el Bluetooth del auto
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    @Volatile
    private var ttsListo = false
    private val tts = TextToSpeech(contexto) { estado -> ttsListo = estado == TextToSpeech.SUCCESS }

    suspend fun hablar(texto: String) {
        val limpio = limpiarParaVoz(texto)
        if (limpio.isEmpty()) return
        turno.withLock {
            val i = Idiomas.actual
            val foco = pedirFoco()
            try {
                val mp3 = try {
                    withContext(Dispatchers.IO) { sintetizarConEdge(limpio, i) }
                } catch (error: Exception) {
                    Log.w(TAG, "Edge TTS no disponible; uso la voz de Google", error)
                    null
                }
                if (mp3 != null && mp3.isNotEmpty()) reproducir(mp3) else hablarConAndroid(limpio, i)
            } finally {
                audio.abandonAudioFocusRequest(foco)
            }
        }
    }

    /** Pitido corto para indicar que Jarvis te está escuchando. */
    fun pitido() {
        ToneGenerator(AudioManager.STREAM_MUSIC, 60).apply {
            startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        }
    }

    fun cerrar() {
        tts.shutdown()
    }

    // --- Edge TTS -----------------------------------------------------------------

    private suspend fun sintetizarConEdge(texto: String, i: Idioma): ByteArray {
        val resultado = CompletableDeferred<ByteArray>()
        val mp3 = ByteArrayOutputStream()
        val url = "wss://$BASE/edge/v1?TrustedClientToken=$TOKEN&ConnectionId=${uuid()}" +
            "&Sec-MS-GEC=${secMsGec()}&Sec-MS-GEC-Version=$GEC_VERSION"
        val peticion = Request.Builder().url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", AGENTE)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${hexAleatorio(16)};")
            .build()

        val socket = HTTP.newWebSocket(peticion, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(mensajeConfiguracion())
                webSocket.send(mensajeSsml(texto, i))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if ("Path:turn.end" in text) {
                    resultado.complete(mp3.toByteArray())
                    webSocket.close(1000, null)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 2 bytes con la longitud de las cabeceras, las cabeceras y después el audio
                val datos = bytes.toByteArray()
                if (datos.size < 2) return
                val largo = ((datos[0].toInt() and 0xFF) shl 8) or (datos[1].toInt() and 0xFF)
                if (largo + 2 > datos.size) return
                if ("Path:audio" in String(datos, 2, largo, Charsets.UTF_8)) {
                    mp3.write(datos, largo + 2, datos.size - largo - 2)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                resultado.completeExceptionally(t)
            }
        })
        return try {
            withTimeout(15_000) { resultado.await() }
        } finally {
            socket.cancel()
        }
    }

    private fun mensajeConfiguracion(): String =
        "X-Timestamp:${fechaJs()}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
            "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"

    private fun mensajeSsml(texto: String, i: Idioma): String =
        "X-RequestId:${uuid()}\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${fechaJs()}Z\r\n" +  // la Z sobra, pero Edge lo manda así
            "Path:ssml\r\n\r\n" +
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='${nombreCompleto(i.voz)}'>" +
            "<prosody pitch='${i.tono}' rate='+0%' volume='+0%'>${escaparXml(texto)}</prosody>" +
            "</voice></speak>"

    // --- Reproducción -------------------------------------------------------------

    private suspend fun reproducir(mp3: ByteArray) {
        val archivo = File(contexto.cacheDir, "voz.mp3").apply { writeBytes(mp3) }
        withContext(Dispatchers.Main) {
            val reproductor = MediaPlayer().apply {
                setAudioAttributes(atributos)
                setDataSource(archivo.path)
                prepare()
            }
            try {
                suspendCancellableCoroutine { continuacion ->
                    reproductor.setOnCompletionListener { if (continuacion.isActive) continuacion.resume(Unit) }
                    reproductor.setOnErrorListener { _, _, _ ->
                        if (continuacion.isActive) continuacion.resume(Unit)
                        true
                    }
                    continuacion.invokeOnCancellation { reproductor.stop() }
                    reproductor.start()
                }
            } finally {
                reproductor.release()
            }
        }
    }

    private suspend fun hablarConAndroid(texto: String, i: Idioma) {
        if (!ttsListo) return
        tts.setAudioAttributes(atributos)
        tts.language = Locale.forLanguageTag(if (i.codigo == "es") "es-US" else "en-GB")
        suspendCancellableCoroutine { continuacion ->
            val id = uuid()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (continuacion.isActive) continuacion.resume(Unit)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (continuacion.isActive) continuacion.resume(Unit)
                }
            })
            continuacion.invokeOnCancellation { tts.stop() }
            tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    /** Baja la música (o la radio) mientras Jarvis habla, en vez de pararla. */
    private fun pedirFoco(): AudioFocusRequest {
        val peticion = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(atributos)
            .build()
        audio.requestAudioFocus(peticion)
        return peticion
    }

    companion object {
        private const val TAG = "JarvisVoz"
        private const val BASE = "speech.platform.bing.com/consumer/speech/synthesize/readaloud"
        private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val VERSION_CHROMIUM = "143.0.3650.75"
        private const val GEC_VERSION = "1-$VERSION_CHROMIUM"
        private const val AGENTE = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
        private const val EPOCA_WINDOWS = 11644473600L

        fun limpiarParaVoz(texto: String): String = texto
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*_#`>|]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        /** Token Sec-MS-GEC: hora de Windows redondeada a 5 minutos + token, en SHA-256. */
        private fun secMsGec(): String {
            val segundos = System.currentTimeMillis() / 1000 + EPOCA_WINDOWS
            val ticks = (segundos - segundos % 300) * 10_000_000L
            val hash = MessageDigest.getInstance("SHA-256").digest("$ticks$TOKEN".toByteArray(Charsets.US_ASCII))
            return hash.joinToString("") { "%02X".format(it) }
        }

        /** "es-MX-JorgeNeural" -> "Microsoft Server Speech Text to Speech Voice (es-MX, JorgeNeural)" */
        private fun nombreCompleto(voz: String): String {
            val partes = Regex("^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$").find(voz) ?: return voz
            val (lengua, region, nombre) = partes.destructured
            return "Microsoft Server Speech Text to Speech Voice ($lengua-$region, $nombre)"
        }

        private fun escaparXml(texto: String) = texto
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun fechaJs(): String =
            SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date())

        private fun uuid() = UUID.randomUUID().toString().replace("-", "")

        private fun hexAleatorio(bytes: Int): String =
            ByteArray(bytes).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(it) }
    }
}
