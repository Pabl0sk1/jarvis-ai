"""Idiomas de Jarvis: español latino y la versión original en inglés.

El idioma se puede cambiar en marcha ("Jarvis, habla en inglés"): la voz, el oído y el
cerebro consultan `actual()` en cada frase.
"""

from dataclasses import dataclass

from . import config


@dataclass(frozen=True)
class Idioma:
    codigo: str
    nombre: str
    voz: str
    tono: str
    velocidad: str
    tratamiento: str
    regla: str                 # cómo debe hablar el cerebro
    saludos: tuple[str, str, str]  # mañana, tarde, noche
    en_linea: str
    despedida: str
    despedidas: frozenset[str]  # frases (normalizadas) que cierran la conversación
    sin_cerebro: str
    confundido: str
    temporizador: str
    temporizador_motivo: str

    def decir(self, plantilla: str, **datos) -> str:
        return plantilla.format(t=self.tratamiento, T=self.tratamiento.capitalize(), **datos)


IDIOMAS = {
    "es": Idioma(
        codigo="es", nombre="español",
        voz=config.VOZ_ES, tono=config.TONO_ES, velocidad=config.VELOCIDAD_ES,
        tratamiento=config.TRATAMIENTO_ES,
        regla="Habla siempre en español latinoamericano neutro y trata al usuario de usted, "
              "con la cortesía de un mayordomo.",
        saludos=("Buenos días", "Buenas tardes", "Buenas noches"),
        en_linea="Todos los sistemas en línea.",
        despedida="Aquí estaré, {t}.",
        despedidas=frozenset({"eso es todo", "nada mas", "nada", "gracias eso es todo",
                              "adios", "hasta luego"}),
        sin_cerebro="Lo siento, {t}, ahora mismo no tengo ningún cerebro disponible. Revise la "
                    "conexión a internet, la clave de Claude o que Ollama esté en marcha.",
        confundido="Me temo que me he confundido con esa petición. ¿Podría pedírmela de otra forma?",
        temporizador="{T}, el temporizador ha terminado.",
        temporizador_motivo="{T}, es la hora: {motivo}.",
    ),
    "en": Idioma(
        codigo="en", nombre="inglés",
        voz=config.VOZ_EN, tono=config.TONO_EN, velocidad=config.VELOCIDAD_EN,
        tratamiento=config.TRATAMIENTO_EN,
        regla="Always reply in English, with the refined British manner of the original JARVIS, "
              "even though these instructions are written in Spanish.",
        saludos=("Good morning", "Good afternoon", "Good evening"),
        en_linea="All systems online.",
        despedida="I'll be here, {t}.",
        despedidas=frozenset({"thats all", "that is all", "nothing", "goodbye", "bye",
                              "thank you thats all"}),
        sin_cerebro="I'm sorry, {t}, I have no brain available at the moment. Please check the "
                    "internet connection, the Claude API key, or that Ollama is running.",
        confundido="I'm afraid I got rather tangled up with that request. Could you phrase it differently?",
        temporizador="{T}, your timer is up.",
        temporizador_motivo="{T}, it's time: {motivo}.",
    ),
}

_actual = IDIOMAS.get(config.IDIOMA, IDIOMAS["es"])


def actual() -> Idioma:
    return _actual


def cambiar(codigo: str) -> Idioma:
    global _actual
    _actual = IDIOMAS[codigo]
    return _actual
