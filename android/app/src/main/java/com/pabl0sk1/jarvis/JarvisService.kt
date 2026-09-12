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
    val estado = MutableStateFlow("Apagado")
    val conversacion = MutableStateFlow(listOf<String>())
    val bluetooth = MutableStateFlow("")

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
        cerebro = Cerebro(Herramientas(this, memoria, alcance) { voz.hablar(it) }, memoria)
        detector = DetectorActivacion(this)
        despierto = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis:escucha").apply { acquire() }
        ContextCompat.registerReceiver(this, receptorBluetooth, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }, ContextCompat.RECEIVER_EXPORTED)

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
        if (despierto.isHeld) despierto.release()
        detector.close()
        voz.cerrar()
        EstadoJarvis.estado.value = "Apagado"
        super.onDestroy()
    }

    private suspend fun bucle() {
        while (alcance.isActive) {
            EstadoJarvis.estado.value = "Esperando «Hey Jarvis»..."
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
            EstadoJarvis.estado.value = "Te escucho..."
            val texto = oido.escuchar() ?: return
            EstadoJarvis.anotar("Tú: $texto")
            val i = Idiomas.actual
            if (normalizar(texto) in i.despedidas) {
                voz.hablar(i.decir(i.despedida))
                return
            }
            EstadoJarvis.estado.value = "Pensando..."
            val respuesta = cerebro.responder(texto)
            EstadoJarvis.anotar("Jarvis: $respuesta")
            EstadoJarvis.estado.value = "Hablando..."
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

        fun normalizar(texto: String): String =
            Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^\\w\\s]"), "")
                .lowercase().trim()
    }
}
