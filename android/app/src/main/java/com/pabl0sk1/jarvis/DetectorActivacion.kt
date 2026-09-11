package com.pabl0sk1.jarvis

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

/**
 * "Hey Jarvis" con los mismos modelos de openWakeWord que usa el PC
 * (port de openwakeword/utils.py: AudioFeatures._streaming_features).
 *
 * Recibe bloques de 1280 muestras (80 ms a 16 kHz) y devuelve una puntuación de 0 a 1.
 */
class DetectorActivacion(contexto: Context) : AutoCloseable {

    private val entorno = OrtEnvironment.getEnvironment()
    private val melspec = sesion(contexto, "melspectrogram.onnx")
    private val embedding = sesion(contexto, "embedding_model.onnx")
    private val jarvis = sesion(contexto, "hey_jarvis.onnx")

    // Último bloque más 480 muestras anteriores, igual que openWakeWord (n_samples + 160*3)
    private val audio = ShortArray(BLOQUE + CONTEXTO)
    private val espectro = ArrayDeque<FloatArray>()  // frames de 32 bandas mel
    private val rasgos = ArrayDeque<FloatArray>()    // embeddings de 96 valores

    init {
        reiniciar()
    }

    fun reiniciar() {
        audio.fill(0)
        espectro.clear()
        repeat(VENTANA) { espectro.addLast(FloatArray(BANDAS) { 1f }) }
        rasgos.clear()
    }

    fun puntuar(bloque: ShortArray): Float {
        require(bloque.size == BLOQUE) { "Hacen falta bloques de $BLOQUE muestras" }
        System.arraycopy(audio, BLOQUE, audio, 0, CONTEXTO)
        System.arraycopy(bloque, 0, audio, CONTEXTO, BLOQUE)

        // 1. Espectrograma mel: [1, muestras] -> [1, 1, frames, 32], transformado con x/10 + 2
        val entrada = FloatArray(audio.size) { audio[it].toFloat() }
        val mel = ejecutar(melspec, entrada, longArrayOf(1, audio.size.toLong()))
        for (inicio in mel.indices step BANDAS) {
            espectro.addLast(FloatArray(BANDAS) { mel[inicio + it] / 10f + 2f })
        }
        while (espectro.size > VENTANA) espectro.removeFirst()

        // 2. Embedding de las últimas 76 frames: [1, 76, 32, 1] -> 96 valores
        val ventana = FloatArray(VENTANA * BANDAS)
        espectro.forEachIndexed { i, frame -> System.arraycopy(frame, 0, ventana, i * BANDAS, BANDAS) }
        rasgos.addLast(ejecutar(embedding, ventana, longArrayOf(1, VENTANA.toLong(), BANDAS.toLong(), 1)))
        while (rasgos.size > RASGOS) rasgos.removeFirst()
        if (rasgos.size < RASGOS) return 0f

        // 3. Modelo "hey jarvis": [1, 16, 96] -> [1, 1]
        val entradaJarvis = FloatArray(RASGOS * 96)
        rasgos.forEachIndexed { i, r -> System.arraycopy(r, 0, entradaJarvis, i * 96, 96) }
        return ejecutar(jarvis, entradaJarvis, longArrayOf(1, RASGOS.toLong(), 96))[0]
    }

    override fun close() {
        melspec.close()
        embedding.close()
        jarvis.close()
    }

    private fun sesion(contexto: Context, archivo: String): OrtSession {
        val bytes = contexto.assets.open(archivo).use { it.readBytes() }
        return entorno.createSession(bytes, OrtSession.SessionOptions())
    }

    private fun ejecutar(sesion: OrtSession, datos: FloatArray, forma: LongArray): FloatArray {
        OnnxTensor.createTensor(entorno, FloatBuffer.wrap(datos), forma).use { tensor ->
            sesion.run(mapOf(sesion.inputNames.first() to tensor)).use { resultado ->
                val salida = (resultado[0] as OnnxTensor).floatBuffer
                return FloatArray(salida.remaining()).also { salida.get(it) }
            }
        }
    }

    companion object {
        const val BLOQUE = 1280      // 80 ms a 16 kHz
        const val FRECUENCIA = 16000
        private const val CONTEXTO = 480
        private const val BANDAS = 32
        private const val VENTANA = 76  // frames que necesita el modelo de embeddings
        private const val RASGOS = 16   // embeddings que necesita el modelo "hey jarvis"
    }
}
