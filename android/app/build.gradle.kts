import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import java.util.TimeZone

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Las claves y ajustes se leen del mismo .env que usa el Jarvis del PC.
// Quedan dentro del APK: es una app personal, no la compartas.
val env: Map<String, String> = File(rootDir.parentFile, ".env").takeIf { it.exists() }
    ?.readLines()
    ?.mapNotNull { linea ->
        val limpia = linea.trim()
        if (limpia.isEmpty() || limpia.startsWith("#") || "=" !in limpia) null
        else limpia.substringBefore("=").trim() to limpia.substringAfter("=").trim()
    }?.toMap() ?: emptyMap()

fun ajuste(nombre: String, defecto: String = "") =
    "\"" + (env[nombre]?.takeIf { it.isNotEmpty() } ?: defecto).replace("\"", "\\\"") + "\""

// Clave de firma propia (android/firma.properties, fuera de git). Android sólo deja
// actualizar la app sin borrar sus datos si siempre se firma con la misma clave.
val firma = Properties().apply {
    File(rootDir, "firma.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.pabl0sk1.jarvis"
    compileSdk = 35

    signingConfigs {
        if (firma.getProperty("storeFile") != null) {
            create("jarvis") {
                storeFile = file(firma.getProperty("storeFile"))
                storePassword = firma.getProperty("storePassword")
                keyAlias = firma.getProperty("keyAlias")
                keyPassword = firma.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.pabl0sk1.jarvis"
        minSdk = 26
        targetSdk = 35
        // Minutos desde 1970: sube solo en cada compilación, así cada versión es más nueva que la anterior
        versionCode = (System.currentTimeMillis() / 60_000).toInt()
        // Hora de Paraguay (UTC-3 todo el año). No se usa la zona de Java: el JDK 21.0.1 instalado
        // tiene reglas horarias antiguas y restaba una hora.
        versionName = "0.1-" + SimpleDateFormat("yyyyMMdd.HHmm").apply {
            timeZone = TimeZone.getTimeZone("GMT-03:00")
        }.format(Date())

        buildConfigField("String", "GEMINI_API_KEY", ajuste("GEMINI_API_KEY"))
        buildConfigField("String", "GROQ_API_KEY", ajuste("GROQ_API_KEY"))
        buildConfigField("String", "MODELO_GEMINI", ajuste("JARVIS_MODELO_GEMINI", "gemini-3.1-flash-lite"))
        buildConfigField("String", "MODELO_GROQ", ajuste("JARVIS_MODELO_GROQ", "openai/gpt-oss-120b"))
        buildConfigField("String", "NOMBRE_USUARIO", ajuste("JARVIS_NOMBRE_USUARIO"))
        buildConfigField("String", "CIUDAD", ajuste("JARVIS_CIUDAD", "Asunción"))
        buildConfigField("String", "VOZ_ES", ajuste("JARVIS_VOZ_ES", "es-MX-JorgeNeural"))
        buildConfigField("String", "VOZ_EN", ajuste("JARVIS_VOZ_EN", "en-GB-RyanNeural"))
        buildConfigField("String", "TONO_ES", ajuste("JARVIS_TONO_ES", "-4Hz"))
        buildConfigField("String", "TONO_EN", ajuste("JARVIS_TONO_EN", "-2Hz"))
        buildConfigField("String", "RADIO_AUTO", ajuste("JARVIS_RADIO_AUTO"))
        buildConfigField("String", "CLAVE_RED", ajuste("JARVIS_CLAVE_RED"))
        buildConfigField("String", "CONTACTOS_EMERGENCIA", ajuste("JARVIS_CONTACTOS_EMERGENCIA"))
        buildConfigField("String", "TELE_IP", ajuste("JARVIS_TELE_IP"))
        buildConfigField("String", "TELE_MAC", ajuste("JARVIS_TELE_MAC"))

        // Sólo celulares ARM de 64 bits (como el Redmi Note 14 5G): ONNX Runtime trae
        // librerías para cuatro arquitecturas y el APK pasaría de ~80 MB
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        val clave = signingConfigs.findByName("jarvis") ?: signingConfigs.getByName("debug")
        debug {
            signingConfig = clave
        }
        release {
            isMinifyEnabled = false
            signingConfig = clave
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        noCompress += "onnx"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
