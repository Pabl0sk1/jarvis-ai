package com.pabl0sk1.jarvis

/** Español latino e inglés, como en el Jarvis del PC (jarvis/idioma.py). */
data class Idioma(
    val codigo: String,
    val nombre: String,
    val voz: String,
    val tono: String,
    val tratamiento: String,
    val reconocimiento: String,  // idioma para el reconocedor de voz de Android
    val regla: String,
    val saludos: List<String>,   // mañana, tarde, noche
    val enLinea: String,
    val despedida: String,
    val despedidas: Set<String>,
    val sinCerebro: String,
    val confundido: String,
    val temporizador: String,
    val temporizadorMotivo: String,
    val bienvenidaAuto: String,
    val llamadaEntrante: String,
) {
    fun decir(plantilla: String, motivo: String = ""): String = plantilla
        .replace("{t}", tratamiento)
        .replace("{T}", tratamiento.replaceFirstChar { it.uppercase() })
        .replace("{motivo}", motivo)
}

object Idiomas {
    val ES = Idioma(
        codigo = "es", nombre = "español",
        voz = BuildConfig.VOZ_ES, tono = BuildConfig.TONO_ES, tratamiento = "señor",
        reconocimiento = "es-419",
        regla = "Habla siempre en español latinoamericano neutro y trata al usuario de usted, " +
            "con la cortesía de un mayordomo.",
        saludos = listOf("Buenos días", "Buenas tardes", "Buenas noches"),
        enLinea = "Todos los sistemas en línea.",
        despedida = "Aquí estaré, {t}.",
        despedidas = setOf("eso es todo", "nada mas", "nada", "gracias eso es todo", "adios", "hasta luego"),
        sinCerebro = "Lo siento, {t}, ahora mismo no tengo ningún cerebro disponible. " +
            "Revise la conexión a internet.",
        confundido = "Me temo que me he confundido con esa petición. ¿Podría pedírmela de otra forma?",
        temporizador = "{T}, el temporizador ha terminado.",
        temporizadorMotivo = "{T}, es la hora: {motivo}.",
        bienvenidaAuto = "Bienvenido a bordo, {t}. Estoy conectado al auto.",
        llamadaEntrante = "{T}, le llama {motivo}.",
    )

    val EN = Idioma(
        codigo = "en", nombre = "inglés",
        voz = BuildConfig.VOZ_EN, tono = BuildConfig.TONO_EN, tratamiento = "sir",
        reconocimiento = "en-GB",
        regla = "Always reply in English, with the refined British manner of the original JARVIS, " +
            "even though these instructions are written in Spanish.",
        saludos = listOf("Good morning", "Good afternoon", "Good evening"),
        enLinea = "All systems online.",
        despedida = "I'll be here, {t}.",
        despedidas = setOf("thats all", "that is all", "nothing", "goodbye", "bye", "thank you thats all"),
        sinCerebro = "I'm sorry, {t}, I have no brain available at the moment. " +
            "Please check the internet connection.",
        confundido = "I'm afraid I got rather tangled up with that request. Could you phrase it differently?",
        temporizador = "{T}, your timer is up.",
        temporizadorMotivo = "{T}, it's time: {motivo}.",
        bienvenidaAuto = "Welcome aboard, {t}. I'm connected to the car.",
        llamadaEntrante = "{T}, you have a call from {motivo}.",
    )

    val todos = mapOf("es" to ES, "en" to EN)

    @Volatile
    var actual: Idioma = ES
}
