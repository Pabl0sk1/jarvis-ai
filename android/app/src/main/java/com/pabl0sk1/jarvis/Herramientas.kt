package com.pabl0sk1.jarvis

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.MediaStore
import android.text.Html
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class Herramienta(
    val nombre: String,
    val descripcion: String,
    val parametros: JSONObject,
    val funcion: (JSONObject) -> String,
)

/** Cosas que Jarvis puede hacer desde el celular (jarvis/herramientas.py del PC). */
class Herramientas(
    private val contexto: Context,
    private val memoria: Memoria,
    private val alcance: CoroutineScope,
    private val avisar: suspend (String) -> Unit,
) {
    private val audio = contexto.getSystemService(AudioManager::class.java)

    val lista = listOf(
        Herramienta(
            "buscar_en_internet",
            "Busca información actual en internet: noticias, resultados deportivos, precios, horarios o " +
                "cualquier dato reciente que no sepas con seguridad. Usa consultas cortas, de 2 a 5 palabras " +
                "y sin fechas (p. ej. 'noticias Paraguay').",
            objeto("consulta" to texto(), "noticias" to booleano("true para buscar noticias recientes."),
                requeridos = listOf("consulta")),
            ::buscarEnInternet,
        ),
        Herramienta(
            "consultar_clima",
            "Tiempo actual y previsión de los próximos tres días en una ciudad.",
            objeto("ciudad" to texto("Ciudad. Si el usuario no dice ninguna, ${BuildConfig.CIUDAD}.")),
            ::consultarClima,
        ),
        Herramienta(
            "poner_temporizador",
            "Pone un temporizador o recordatorio. Cuando termine, Jarvis lo anunciará en voz alta.",
            objeto("minutos" to numero("Minutos hasta el aviso (0.5 = 30 segundos)."),
                "motivo" to texto("Qué hay que recordar, p. ej. 'sacar la pizza'."),
                requeridos = listOf("minutos")),
            ::ponerTemporizador,
        ),
        Herramienta(
            "abrir_aplicacion",
            "Abre una aplicación instalada en el celular (WhatsApp, YouTube, Spotify, Maps, cámara...).",
            objeto("nombre" to texto("Nombre de la app."), requeridos = listOf("nombre")),
            ::abrirAplicacion,
        ),
        Herramienta(
            "navegar_a",
            "Abre Google Maps con la navegación hacia un destino (dirección, lugar o negocio).",
            objeto("destino" to texto(), requeridos = listOf("destino")),
            ::navegarA,
        ),
        Herramienta(
            "reproducir_musica",
            "Pone y REPRODUCE directamente una canción, artista, álbum o tipo de música. Por defecto en " +
                "YouTube (reproduce el primer vídeo que encuentra, no muestra la lista).",
            objeto("consulta" to texto("Qué poner, p. ej. 'AC/DC' o 'música relajante'."),
                "app" to enumeracion(listOf("youtube", "spotify", "youtube music")),
                requeridos = listOf("consulta")),
            ::reproducirMusica,
        ),
        Herramienta(
            "ver_video_youtube",
            "Busca un vídeo en YouTube y lo REPRODUCE directamente (el primer resultado). Úsala cuando " +
                "el usuario quiera ver o escuchar un vídeo, no para mostrarle la lista de resultados.",
            objeto("consulta" to texto("Qué vídeo buscar."), requeridos = listOf("consulta")),
            ::verVideoYoutube,
        ),
        Herramienta(
            "controlar_musica",
            "Controla la música o el audio que está sonando en el celular (también en el auto) y su volumen.",
            objeto("accion" to enumeracion(listOf("reproducir", "pausar", "siguiente", "anterior",
                "subir_volumen", "bajar_volumen")),
                "veces" to entero("Para el volumen: cuántos pasos. Por defecto 1."),
                requeridos = listOf("accion")),
            ::controlarMusica,
        ),
        Herramienta(
            "estado_celular",
            "Dice la batería del celular y si se está cargando.",
            objeto(),
            ::estadoCelular,
        ),
        Herramienta(
            "cambiar_idioma",
            "Cambia el idioma en el que Jarvis escucha y habla: es = español latino, en = inglés.",
            objeto("idioma" to enumeracion(Idiomas.todos.keys.toList()), requeridos = listOf("idioma")),
            ::cambiarIdioma,
        ),
        Herramienta(
            "recordar",
            "Guarda para siempre un dato sobre el usuario (gustos, nombres, rutinas, fechas).",
            objeto("dato" to texto("El dato, redactado en tercera persona."), requeridos = listOf("dato")),
            ::recordar,
        ),
        Herramienta(
            "olvidar",
            "Borra de la memoria los datos que contengan un texto, cuando el usuario lo pida.",
            objeto("texto" to texto(), requeridos = listOf("texto")),
            ::olvidar,
        ),
    )
    private val porNombre = lista.associateBy { it.nombre }

    fun paraOpenAI(): JSONArray = JSONArray(lista.map { h ->
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", h.nombre).put("description", h.descripcion).put("parameters", h.parametros))
    })

    fun ejecutar(nombre: String, argumentos: JSONObject): String {
        val herramienta = porNombre[nombre] ?: return "Error: no existe la herramienta $nombre."
        Log.i(TAG, "Herramienta $nombre($argumentos)")
        return try {
            herramienta.funcion(argumentos)
        } catch (error: Exception) {  // el cerebro recibe el error y se lo explica al usuario
            Log.w(TAG, "La herramienta $nombre falló", error)
            "Error al ejecutar $nombre: ${error.message}"
        }
    }

    // --- Implementaciones ---------------------------------------------------------

    private fun buscarEnInternet(a: JSONObject): String {
        val original = a.getString("consulta")
        var consulta = FECHAS.replace(original, " ").replace(Regex("\\s+"), " ").trim().ifEmpty { original }
        if (a.optBoolean("noticias") && !consulta.contains("noticia", ignoreCase = true)) consulta = "noticias $consulta"
        val url = "https://html.duckduckgo.com/html/".toHttpUrl().newBuilder()
            .addQueryParameter("q", consulta)
            .addQueryParameter("kl", if (Idiomas.actual.codigo == "es") "xl-es" else "us-en")
            .build()
        val html = HTTP.newCall(Request.Builder().url(url).header("User-Agent", AGENTE).build())
            .execute().use { it.body?.string().orEmpty() }
        val opciones = setOf(RegexOption.DOT_MATCHES_ALL)
        val titulos = Regex("class=\"result__a\"[^>]*>(.*?)</a>", opciones).findAll(html)
            .map { limpiarHtml(it.groupValues[1]) }.toList()
        val resumenes = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", opciones).findAll(html)
            .map { limpiarHtml(it.groupValues[1]) }.toList()
        val resultados = titulos.zip(resumenes).take(5)
        if (resultados.isEmpty()) return "No he encontrado nada. Prueba otra consulta más corta y sin fechas."
        return JSONArray(resultados.map { (titulo, resumen) ->
            JSONObject().put("titulo", titulo).put("resumen", resumen.take(300))
        }).toString()
    }

    private fun consultarClima(a: JSONObject): String {
        val ciudad = a.optString("ciudad").ifBlank { BuildConfig.CIUDAD }
        val geo = obtenerJson("https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", ciudad).addQueryParameter("count", "1")
            .addQueryParameter("language", "es").build().toString())
        val lugar = geo.optJSONArray("results")?.optJSONObject(0) ?: return "No encuentro ninguna ciudad llamada $ciudad."
        val datos = obtenerJson("https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", lugar.getDouble("latitude").toString())
            .addQueryParameter("longitude", lugar.getDouble("longitude").toString())
            .addQueryParameter("current", "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m")
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            .addQueryParameter("timezone", "auto").addQueryParameter("forecast_days", "3")
            .build().toString())
        val actual = datos.getJSONObject("current")
        val dias = datos.getJSONObject("daily")
        val prevision = JSONArray((0 until dias.getJSONArray("time").length()).map { d ->
            JSONObject()
                .put("fecha", dias.getJSONArray("time").getString(d))
                .put("cielo", CIELO[dias.getJSONArray("weather_code").getInt(d)] ?: "desconocido")
                .put("max_c", dias.getJSONArray("temperature_2m_max").getDouble(d))
                .put("min_c", dias.getJSONArray("temperature_2m_min").getDouble(d))
                .put("prob_lluvia_pct", dias.getJSONArray("precipitation_probability_max").optInt(d))
        })
        return JSONObject()
            .put("lugar", "${lugar.getString("name")}, ${lugar.optString("country")}")
            .put("ahora", JSONObject()
                .put("cielo", CIELO[actual.getInt("weather_code")] ?: "desconocido")
                .put("temperatura_c", actual.getDouble("temperature_2m"))
                .put("sensacion_c", actual.getDouble("apparent_temperature"))
                .put("humedad_pct", actual.getInt("relative_humidity_2m"))
                .put("viento_kmh", actual.getDouble("wind_speed_10m")))
            .put("prevision", prevision)
            .toString()
    }

    private fun ponerTemporizador(a: JSONObject): String {
        val minutos = a.getDouble("minutos")
        val motivo = a.optString("motivo")
        val i = Idiomas.actual
        val aviso = if (motivo.isNotBlank()) i.decir(i.temporizadorMotivo, motivo) else i.decir(i.temporizador)
        alcance.launch {
            delay((minutos * 60_000).toLong())
            avisar(aviso)
        }
        return "Temporizador de $minutos minutos en marcha."
    }

    private fun abrirAplicacion(a: JSONObject): String {
        val buscada = a.getString("nombre").trim().lowercase()
        val pm = contexto.packageManager
        val lanzables = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val app = lanzables.firstOrNull { it.loadLabel(pm).toString().lowercase() == buscada }
            ?: lanzables.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(buscada) }
            ?: return "No encuentro ninguna app llamada ${a.getString("nombre")} en el celular."
        val intent = pm.getLaunchIntentForPackage(app.activityInfo.packageName)
            ?: return "No puedo abrir ${app.loadLabel(pm)}."
        contexto.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Abriendo ${app.loadLabel(pm)}."
    }

    private fun navegarA(a: JSONObject): String {
        val destino = a.getString("destino")
        val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(destino)))
            .setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            contexto.startActivity(maps)
        } catch (e: ActivityNotFoundException) {
            contexto.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(destino)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return "Navegación hacia $destino iniciada."
    }

    private fun reproducirMusica(a: JSONObject): String {
        val consulta = a.getString("consulta")
        val paquete = when (a.optString("app")) {
            "spotify" -> "com.spotify.music"
            "youtube music" -> "com.google.android.apps.youtube.music"
            else -> return reproducirEnYoutube(consulta)  // por defecto, YouTube
        }
        // Spotify y YouTube Music empiezan a reproducir solos con "reproducir desde búsqueda"
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, consulta)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .setPackage(paquete)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            contexto.startActivity(intent)
            "Poniendo $consulta."
        } catch (e: ActivityNotFoundException) {
            "Esa app no está instalada. " + reproducirEnYoutube(consulta)
        }
    }

    private fun verVideoYoutube(a: JSONObject): String = reproducirEnYoutube(a.getString("consulta"))

    /** Busca en YouTube y abre directamente el primer vídeo (así empieza a reproducirse). */
    private fun reproducirEnYoutube(consulta: String): String {
        val url = "https://www.youtube.com/results".toHttpUrl().newBuilder()
            .addQueryParameter("search_query", consulta).build()
        val html = HTTP.newCall(Request.Builder().url(url)
            .header("User-Agent", AGENTE_ESCRITORIO)
            .header("Accept-Language", "es-419,es;q=0.9")
            .build()).execute().use { it.body?.string().orEmpty() }
        // El primer resultado normal (videoRenderer); si YouTube cambia la página, cualquier vídeo
        val id = Regex("\"videoRenderer\":\\{\"videoId\":\"([\\w-]{11})\"").find(html)?.groupValues?.get(1)
            ?: Regex("\"videoId\":\"([\\w-]{11})\"").find(html)?.groupValues?.get(1)
            ?: return "No encontré ningún vídeo de $consulta en YouTube."
        val app = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$id"))
            .setPackage("com.google.android.youtube").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            contexto.startActivity(app)
        } catch (e: ActivityNotFoundException) {
            contexto.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return "Reproduciendo en YouTube el primer vídeo de $consulta."
    }

    private fun controlarMusica(a: JSONObject): String {
        val veces = a.optInt("veces", 1).coerceIn(1, 15)
        when (a.getString("accion")) {
            "reproducir" -> pulsarTeclaMultimedia(KeyEvent.KEYCODE_MEDIA_PLAY)
            "pausar" -> pulsarTeclaMultimedia(KeyEvent.KEYCODE_MEDIA_PAUSE)
            "siguiente" -> pulsarTeclaMultimedia(KeyEvent.KEYCODE_MEDIA_NEXT)
            "anterior" -> pulsarTeclaMultimedia(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "subir_volumen" -> repeat(veces) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            }
            "bajar_volumen" -> repeat(veces) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            }
            else -> return "No conozco esa acción."
        }
        return "Hecho."
    }

    private fun estadoCelular(a: JSONObject): String {
        val bateria = contexto.getSystemService(BatteryManager::class.java)
        val nivel = bateria.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "Batería al $nivel %" + if (bateria.isCharging) ", cargando." else ", sin cargar."
    }

    private fun cambiarIdioma(a: JSONObject): String {
        val nuevo = Idiomas.todos[a.getString("idioma")] ?: return "No conozco ese idioma."
        Idiomas.actual = nuevo
        return "Idioma cambiado a ${nuevo.nombre}. Responde desde ahora en ${nuevo.nombre}."
    }

    private fun recordar(a: JSONObject): String {
        memoria.recordar(a.getString("dato"))
        return "Guardado en la memoria."
    }

    private fun olvidar(a: JSONObject): String {
        val borrados = memoria.olvidar(a.getString("texto"))
        return if (borrados > 0) "Borrados $borrados recuerdos." else "No había nada que coincidiera."
    }

    // --- Ayudas -------------------------------------------------------------------

    private fun pulsarTeclaMultimedia(codigo: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, codigo))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, codigo))
    }

    private fun obtenerJson(url: String): JSONObject =
        HTTP.newCall(Request.Builder().url(url).build()).execute().use { JSONObject(it.body?.string().orEmpty()) }

    private fun limpiarHtml(html: String) = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim()

    companion object {
        private const val TAG = "JarvisHerramientas"
        private const val AGENTE = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36"
        // La página de resultados de YouTube de escritorio trae los vídeos en un JSON fácil de leer
        private const val AGENTE_ESCRITORIO = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"

        // Los modelos añaden fechas a las búsquedas y así no se encuentra nada; se quitan antes
        private val FECHAS = Regex(
            "\\b(\\d{1,2}\\s+(de\\s+)?)?(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|" +
                "octubre|noviembre|diciembre|january|february|march|april|may|june|july|august|" +
                "september|october|november|december)(\\s+\\d{1,2}\\b,?)?(\\s+(de\\s+)?\\d{4})?\\b|\\b20\\d{2}\\b|" +
                "\\b(hoy|today)\\b",
            RegexOption.IGNORE_CASE,
        )

        // Códigos meteorológicos WMO que devuelve Open-Meteo
        private val CIELO = mapOf(
            0 to "despejado", 1 to "mayormente despejado", 2 to "parcialmente nublado", 3 to "cubierto",
            45 to "niebla", 48 to "niebla con escarcha", 51 to "llovizna débil", 53 to "llovizna",
            55 to "llovizna intensa", 61 to "lluvia débil", 63 to "lluvia", 65 to "lluvia fuerte",
            71 to "nieve débil", 73 to "nieve", 75 to "nieve fuerte", 80 to "chubascos débiles",
            81 to "chubascos", 82 to "chubascos fuertes", 95 to "tormenta", 96 to "tormenta con granizo",
            99 to "tormenta con granizo fuerte",
        )

        private fun objeto(vararg propiedades: Pair<String, JSONObject>, requeridos: List<String> = emptyList()) =
            JSONObject().put("type", "object")
                .put("properties", JSONObject().apply { propiedades.forEach { (n, p) -> put(n, p) } })
                .apply { if (requeridos.isNotEmpty()) put("required", JSONArray(requeridos)) }

        private fun texto(descripcion: String = "") = JSONObject().put("type", "string")
            .apply { if (descripcion.isNotEmpty()) put("description", descripcion) }

        private fun numero(descripcion: String) = JSONObject().put("type", "number").put("description", descripcion)
        private fun entero(descripcion: String) = JSONObject().put("type", "integer").put("description", descripcion)
        private fun booleano(descripcion: String) = JSONObject().put("type", "boolean").put("description", descripcion)
        private fun enumeracion(valores: List<String>) = JSONObject().put("type", "string").put("enum", JSONArray(valores))
    }
}
