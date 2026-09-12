package com.pabl0sk1.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

/** Lo que la pantalla muestra del servicio. */
object EstadoJarvis {
    enum class Fase { APAGADO, ESPERANDO, ESCUCHANDO, PENSANDO, HABLANDO }

    val estado = MutableStateFlow("Apagado")
    val fase = MutableStateFlow(Fase.APAGADO)
    val nivel = MutableStateFlow(0f)  // volumen del micrófono (0..1), para animar el reactor
    val conversacion = MutableStateFlow(listOf<String>())
    val bluetooth = MutableStateFlow("")

    fun cambiar(nuevaFase: Fase, texto: String) {
        fase.value = nuevaFase
        estado.value = texto
    }

    fun anotar(linea: String) = conversacion.update { (it + linea).takeLast(40) }
}

/**
 * Servicio en primer plano que escucha "Hey Jarvis" todo el tiempo, conversa
 * y detecta cuando el celular se conecta a la radio del auto.
 */
class JarvisService : Service() {

    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pedidos = Channel<Unit>(Channel.CONFLATED)  // botón "Hablar" de la pantalla

    private lateinit var memoria: Memoria
    private lateinit var voz: Voz
    private lateinit var oido: Oido
    private lateinit var herramientas: Herramientas
    private lateinit var telefono: Telefono
    private var ultimaLlamada = 0L

    /** Anuncia quién llama (el aviso llega dos veces, con y sin número: sólo se usa el que lo trae). */
    private val receptorLlamadas = object : BroadcastReceiver() {
        override fun onReceive(contexto: Context, intent: Intent) {
            if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_RINGING) return
            @Suppress("DEPRECATION")
            val numero = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: return
            val ahora = System.currentTimeMillis()
            if (ahora - ultimaLlamada < 10_000) return
            ultimaLlamada = ahora
            alcance.launch(Dispatchers.IO) {
                val quien = telefono.nombreDe(numero) ?: numero.chunked(3).joinToString(" ")
                voz.hablar(Idiomas.actual.let { it.decir(it.llamadaEntrante, quien) })
            }
        }
    }
    private lateinit var cerebro: Cerebro
    private lateinit var detector: DetectorActivacion
    private lateinit var despierto: PowerManager.WakeLock

    private val receptorBluetooth = object : BroadcastReceiver() {
        override fun onReceive(contexto: Context, intent: Intent) {
            val dispositivo = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE,
                BluetoothDevice::class.java) ?: return
            val nombre = nombreBluetooth(dispositivo)
            val conectado = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
            EstadoJarvis.bluetooth.value = if (conectado) nombre else ""
            val radio = BuildConfig.RADIO_AUTO
            if (radio.isEmpty() || !nombre.contains(radio, ignoreCase = true)) return
            cerebro.enAuto = conectado
            if (conectado) alcance.launch {
                delay(4_000)  // espera a que la radio abra el canal de audio
                voz.hablar(Idiomas.actual.let { it.decir(it.bienvenidaAuto) })
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        iniciarPrimerPlano()
        memoria = Memoria(this)
        voz = Voz(this)
        oido = Oido(this)
        telefono = Telefono(this)
        herramientas = Herramientas(this, memoria, alcance, TeleSamsung(this), Notebook(), telefono, Agenda(this)) {
            voz.hablar(it)
        }
        cerebro = Cerebro(herramientas, memoria)
        herramientas.sincronizarMemoria()
        detector = DetectorActivacion(this)
        despierto = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis:escucha").apply { acquire() }
        ContextCompat.registerReceiver(this, receptorBluetooth, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(this, receptorLlamadas,
            IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)

        Log.i(TAG, "Cerebro: ${cerebro.describir()}")
        alcance.launch {
            voz.hablar(Personalidad.saludo())
            bucle()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACCION_DETENER -> stopSelf()
            ACCION_HABLAR -> pedidos.trySend(Unit)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alcance.cancel()
        unregisterReceiver(receptorBluetooth)
        unregisterReceiver(receptorLlamadas)
        if (despierto.isHeld) despierto.release()
        detector.close()
        voz.cerrar()
        EstadoJarvis.cambiar(EstadoJarvis.Fase.APAGADO, "Apagado")
        EstadoJarvis.nivel.value = 0f
        super.onDestroy()
    }

    private suspend fun bucle() {
        while (alcance.isActive) {
            EstadoJarvis.cambiar(EstadoJarvis.Fase.ESPERANDO, "Esperando «Hey Jarvis»")
            if (esperarActivacion()) conversar()
        }
    }

    /** Graba en bloques de 80 ms hasta oír "Hey Jarvis" (o hasta que pulsen "Hablar"). */
    @SuppressLint("MissingPermission")
    private suspend fun esperarActivacion(): Boolean = withContext(Dispatchers.Default) {
        val bloqueBytes = DetectorActivacion.BLOQUE * 2
        val minimo = AudioRecord.getMinBufferSize(DetectorActivacion.FRECUENCIA,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val grabadora = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, DetectorActivacion.FRECUENCIA,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimo, bloqueBytes * 4))
        if (grabadora.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "No se pudo abrir el micrófono")
            grabadora.release()
            delay(3_000)
            return@withContext false
        }
        detector.reiniciar()
        val bloque = ShortArray(DetectorActivacion.BLOQUE)
        grabadora.startRecording()
        try {
            while (isActive) {
                var leidas = 0
                while (leidas < bloque.size) {
                    val n = grabadora.read(bloque, leidas, bloque.size - leidas)
                    if (n <= 0) return@withContext false
                    leidas += n
                }
                EstadoJarvis.nivel.value = nivelDe(bloque)
                if (pedidos.tryReceive().isSuccess) return@withContext true
                if (detector.puntuar(bloque) >= UMBRAL) return@withContext true
            }
            false
        } finally {
            grabadora.stop()  // el reconocedor de voz necesita el micrófono libre
            grabadora.release()
        }
    }

    /** Una conversación: tras responder sigue escuchando hasta que te quedas callado. */
    private suspend fun conversar() {
        voz.pitido()
        while (alcance.isActive) {
            EstadoJarvis.cambiar(EstadoJarvis.Fase.ESCUCHANDO, "Te escucho, ${Idiomas.actual.tratamiento}")
            val texto = oido.escuchar() ?: return
            EstadoJarvis.anotar("Tú: $texto")
            val i = Idiomas.actual
            if (normalizar(texto) in i.despedidas) {
                voz.hablar(i.decir(i.despedida))
                return
            }
            EstadoJarvis.cambiar(EstadoJarvis.Fase.PENSANDO, "Pensando")
            val respuesta = cerebro.responder(texto)
            EstadoJarvis.anotar("Jarvis: $respuesta")
            EstadoJarvis.cambiar(EstadoJarvis.Fase.HABLANDO, "Hablando")
            voz.hablar(respuesta)
        }
    }

    private fun iniciarPrimerPlano() {
        val canal = NotificationChannel(CANAL, "Jarvis escuchando", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val detener = PendingIntent.getService(this, 1,
            Intent(this, JarvisService::class.java).setAction(ACCION_DETENER), PendingIntent.FLAG_IMMUTABLE)
        val notificacion = Notification.Builder(this, CANAL)
            .setContentTitle("Jarvis")
            .setContentText("Escuchando «Hey Jarvis»")
            .setSmallIcon(R.drawable.ic_notificacion)
            .setOngoing(true)
            .setContentIntent(abrir)
            .addAction(Notification.Action.Builder(null, "Detener", detener).build())
            .build()
        val tipo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICACION, notificacion, tipo)
    }

    @SuppressLint("MissingPermission")
    private fun nombreBluetooth(dispositivo: BluetoothDevice): String {
        val permitido = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        return if (permitido) dispositivo.name ?: dispositivo.address else dispositivo.address
    }

    companion object {
        private const val TAG = "Jarvis"
        private const val CANAL = "jarvis"
        private const val NOTIFICACION = 1
        private const val UMBRAL = 0.5f
        const val ACCION_DETENER = "com.pabl0sk1.jarvis.DETENER"
        const val ACCION_HABLAR = "com.pabl0sk1.jarvis.HABLAR"

        /** Volumen (RMS) de un bloque de audio, de 0 a 1. */
        fun nivelDe(bloque: ShortArray): Float {
            var suma = 0.0
            for (muestra in bloque) suma += muestra.toDouble() * muestra
            return (kotlin.math.sqrt(suma / bloque.size) / 3000.0).toFloat().coerceIn(0f, 1f)
        }

        fun normalizar(texto: String): String =
            Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^\\w\\s]"), "")
                .lowercase().trim()
    }
}
