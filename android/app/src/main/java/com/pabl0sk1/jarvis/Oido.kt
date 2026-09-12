package com.pabl0sk1.jarvis

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Voz a texto con el reconocedor de Android (Google). Tiene que usarse en el hilo principal. */
class Oido(private val contexto: Context) {

    fun disponible(): Boolean = SpeechRecognizer.isRecognitionAvailable(contexto)

    /** Escucha una frase. Devuelve null si no dices nada o no se entiende. */
    suspend fun escuchar(): String? = withContext(Dispatchers.Main) {
        if (!disponible()) return@withContext null
        val reconocedor = SpeechRecognizer.createSpeechRecognizer(contexto)
        try {
            withTimeoutOrNull(20_000) {
                suspendCancellableCoroutine { continuacion ->
                    reconocedor.setRecognitionListener(object : RecognitionListener {
                        override fun onResults(resultados: Bundle?) {
                            val texto = resultados
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                            if (continuacion.isActive) continuacion.resume(texto)
                        }

                        override fun onError(error: Int) {
                            if (continuacion.isActive) continuacion.resume(null)
                        }

                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {
                            EstadoJarvis.nivel.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                        }
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onPartialResults(parciales: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    continuacion.invokeOnCancellation { reconocedor.cancel() }
                    reconocedor.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Idiomas.actual.reconocimiento)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    })
                }
            }?.let { quitarPalabraActivacion(it) }?.takeIf { it.isNotBlank() }
        } finally {
            reconocedor.destroy()
        }
    }

    companion object {
        private val ACTIVACION = Regex("^\\W*((hey|oye|ey)\\W+)?jarvis\\W*", RegexOption.IGNORE_CASE)

        /** "Hey Jarvis, ¿qué hora es?" -> "¿qué hora es?" */
        fun quitarPalabraActivacion(texto: String) = ACTIVACION.replace(texto, "").trim()
    }
}
