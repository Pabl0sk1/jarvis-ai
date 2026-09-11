import java.io.File

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

android {
    namespace = "com.pabl0sk1.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pabl0sk1.jarvis"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

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

        // Sólo celulares ARM de 64 bits (como el Redmi Note 14 5G): ONNX Runtime trae
        // librerías para cuatro arquitecturas y el APK pasaría de ~80 MB
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
