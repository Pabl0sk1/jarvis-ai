package com.pabl0sk1.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private val pedirPermisos =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { encenderServicio() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Pantalla() }
            }
        }
    }

    @Composable
    private fun Pantalla() {
        val estado by EstadoJarvis.estado.collectAsState()
        val conversacion by EstadoJarvis.conversacion.collectAsState()
        val bluetooth by EstadoJarvis.bluetooth.collectAsState()

        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("J.A.R.V.I.S.", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(estado, color = MaterialTheme.colorScheme.primary)
            if (bluetooth.isNotEmpty()) Text("Bluetooth conectado: $bluetooth")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::pedirPermisosYEncender) { Text("Encender") }
                Button(onClick = ::hablar) { Text("Hablar") }
                OutlinedButton(onClick = ::apagar) { Text("Apagar") }
            }

            Text("Para que funcione siempre en tu Xiaomi:", fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = ::permitirBateria, Modifier.fillMaxWidth()) {
                Text(if (bateriaSinRestricciones()) "Batería sin restricciones ✓" else "Permitir batería sin restricciones")
            }
            OutlinedButton(onClick = ::permitirSuperponer, Modifier.fillMaxWidth()) {
                Text(if (Settings.canDrawOverlays(this@MainActivity)) "Mostrar sobre otras apps ✓"
                else "Permitir mostrar sobre otras apps (para abrir apps)")
            }
            OutlinedButton(onClick = ::abrirAjustesApp, Modifier.fillMaxWidth()) {
                Text("Ajustes de la app: activa «Inicio automático»")
            }

            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(conversacion) { linea -> Text(linea) }
            }
        }
    }

    private fun pedirPermisosYEncender() {
        val permisos = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        pedirPermisos.launch(permisos.toTypedArray())
    }

    private fun encenderServicio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            EstadoJarvis.estado.value = "Sin permiso de micrófono no puedo escucharte"
            return
        }
        startForegroundService(Intent(this, JarvisService::class.java))
    }

    private fun hablar() {
        startForegroundService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACCION_HABLAR))
    }

    private fun apagar() {
        startService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACCION_DETENER))
    }

    private fun bateriaSinRestricciones() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun permitirBateria() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun permitirSuperponer() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun abrirAjustesApp() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
}
