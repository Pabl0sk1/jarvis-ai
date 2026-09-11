"""Herramientas: cosas que Jarvis puede hacer, no sólo decir."""

import json
import logging
import os
import re
import threading
import webbrowser
from dataclasses import dataclass
from typing import Callable
from urllib.parse import quote_plus

import requests

from . import config
from . import idioma as idiomas
from .memoria import Memoria
from .tele import ACCIONES as ACCIONES_TELE
from .tele import Tele

log = logging.getLogger(__name__)

# Sólo se abre lo que está en esta lista, para que Jarvis no pueda ejecutar cualquier cosa.
APLICACIONES = {
    "calculadora": "calc",
    "bloc de notas": "notepad",
    "explorador de archivos": "explorer",
    "configuración": "ms-settings:",
    "navegador": "https://www.google.com",
    "youtube": "https://www.youtube.com",
    "spotify": "spotify:",
    "correo": "https://mail.google.com",
}

# Los modelos añaden fechas a las búsquedas ("noticias 11 de septiembre 2026") y así el
# buscador no encuentra nada; se quitan antes de buscar.
FECHAS = re.compile(
    r"\b(\d{1,2}\s+(de\s+)?)?(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|"
    r"octubre|noviembre|diciembre|january|february|march|april|may|june|july|august|"
    r"september|october|november|december)(\s+\d{1,2}\b,?)?(\s+(de\s+)?\d{4})?\b|\b20\d{2}\b|"
    r"\b(hoy|today)\b",
    re.IGNORECASE,
)

# Códigos meteorológicos WMO que devuelve Open-Meteo
CIELO = {
    0: "despejado", 1: "mayormente despejado", 2: "parcialmente nublado", 3: "cubierto",
    45: "niebla", 48: "niebla con escarcha", 51: "llovizna débil", 53: "llovizna",
    55: "llovizna intensa", 61: "lluvia débil", 63: "lluvia", 65: "lluvia fuerte",
    71: "nieve débil", 73: "nieve", 75: "nieve fuerte", 80: "chubascos débiles",
    81: "chubascos", 82: "chubascos fuertes", 95: "tormenta", 96: "tormenta con granizo",
    99: "tormenta con granizo fuerte",
}


@dataclass
class Herramienta:
    nombre: str
    descripcion: str
    parametros: dict
    funcion: Callable[..., str]


class Herramientas:
    def __init__(self, memoria: Memoria, avisar: Callable[[str], None]):
        self._memoria = memoria
        self._avisar = avisar
        self._lista = [
            Herramienta(
                "buscar_en_internet",
                "Busca información actual en internet: noticias, resultados deportivos, precios, "
                "horarios o cualquier dato reciente que no sepas con seguridad. Usa consultas "
                "cortas, de 2 a 5 palabras y sin fechas (p. ej. 'noticias Paraguay').",
                {"type": "object", "properties": {
                    "consulta": {"type": "string"},
                    "noticias": {"type": "boolean",
                                 "description": "true para buscar sólo noticias recientes."},
                }, "required": ["consulta"]},
                self.buscar_en_internet,
            ),
            Herramienta(
                "consultar_clima",
                "Tiempo actual y previsión de los próximos tres días en una ciudad.",
                {"type": "object", "properties": {
                    "ciudad": {"type": "string",
                               "description": f"Ciudad. Si el usuario no dice ninguna, {config.CIUDAD}."},
                }},
                self.consultar_clima,
            ),
            Herramienta(
                "poner_temporizador",
                "Pone un temporizador o recordatorio. Cuando termine, Jarvis lo anunciará en voz alta.",
                {"type": "object", "properties": {
                    "minutos": {"type": "number", "description": "Minutos hasta el aviso (0.5 = 30 segundos)."},
                    "motivo": {"type": "string", "description": "Qué hay que recordar, p. ej. 'sacar la pizza'."},
                }, "required": ["minutos"]},
                self.poner_temporizador,
            ),
            Herramienta(
                "abrir_aplicacion",
                "Abre una aplicación o página en el ordenador del usuario.",
                {"type": "object", "properties": {
                    "nombre": {"type": "string", "enum": list(APLICACIONES)},
                }, "required": ["nombre"]},
                self.abrir_aplicacion,
            ),
            Herramienta(
                "buscar_en_navegador",
                "Abre una búsqueda en el navegador del usuario para que él mismo vea los "
                "resultados, o pone música o vídeos en YouTube.",
                {"type": "object", "properties": {
                    "consulta": {"type": "string"},
                    "sitio": {"type": "string", "enum": ["google", "youtube"]},
                }, "required": ["consulta"]},
                self.buscar_en_navegador,
            ),
            Herramienta(
                "cambiar_idioma",
                "Cambia el idioma en el que Jarvis escucha y habla: es = español latino, en = inglés.",
                {"type": "object", "properties": {
                    "idioma": {"type": "string", "enum": list(idiomas.IDIOMAS)},
                }, "required": ["idioma"]},
                self.cambiar_idioma,
            ),
            Herramienta(
                "recordar",
                "Guarda para siempre un dato sobre el usuario (gustos, nombres, rutinas, fechas).",
                {"type": "object", "properties": {
                    "dato": {"type": "string", "description": "El dato, redactado en tercera persona."},
                }, "required": ["dato"]},
                self.recordar,
            ),
            Herramienta(
                "olvidar",
                "Borra de la memoria los datos que contengan un texto, cuando el usuario lo pida.",
                {"type": "object", "properties": {
                    "texto": {"type": "string"},
                }, "required": ["texto"]},
                self.olvidar,
            ),
        ]
        self._tele = Tele(config.TELE_IP, config.TELE_MAC) if config.TELE_IP else None
        if self._tele:
            self._lista += [
                Herramienta(
                    "controlar_tele",
                    "Controla la tele Samsung del usuario: encender, apagar, volumen, canales, "
                    "moverse por los menús o cambiar de entrada (hdmi = la TV box Android). Cuando la "
                    "tele está en la entrada HDMI, las flechas, ok, volver, reproducir y pausa "
                    "controlan la TV box (HDMI-CEC).",
                    {"type": "object", "properties": {
                        "accion": {"type": "string", "enum": ACCIONES_TELE},
                        "veces": {"type": "integer",
                                  "description": "Cuántas veces pulsar: 'un poco' son 2 o 3; para deshacer "
                                                 "algo, usa el mismo número que antes. Por defecto 1."},
                    }, "required": ["accion"]},
                    self._tele.controlar,
                ),
                Herramienta(
                    "abrir_app_tele",
                    "Abre una aplicación en la tele Samsung (YouTube, Netflix, Prime Video, Spotify...).",
                    {"type": "object", "properties": {
                        "app": {"type": "string"},
                    }, "required": ["app"]},
                    self._tele.abrir_app,
                ),
                Herramienta(
                    "ver_apps_tele",
                    "Lista las aplicaciones instaladas en la tele Samsung del usuario.",
                    {"type": "object", "properties": {}},
                    self._tele.apps,
                ),
            ]
        self._por_nombre = {h.nombre: h for h in self._lista}

    # --- Formatos para cada cerebro -------------------------------------------

    def para_claude(self) -> list[dict]:
        return [{"name": h.nombre, "description": h.descripcion, "input_schema": h.parametros}
                for h in self._lista]

    def para_openai(self) -> list[dict]:
        return [{"type": "function", "function": {
                    "name": h.nombre, "description": h.descripcion, "parameters": h.parametros}}
                for h in self._lista]

    def ejecutar(self, nombre: str, argumentos: dict) -> str:
        herramienta = self._por_nombre.get(nombre)
        if herramienta is None:
            return f"Error: no existe la herramienta {nombre}."
        log.info("Herramienta %s(%s)", nombre, argumentos)
        try:
            return herramienta.funcion(**(argumentos or {}))
        except Exception as error:  # el cerebro recibe el error y se lo explica al usuario
            log.warning("La herramienta %s falló: %s", nombre, error)
            return f"Error al ejecutar {nombre}: {error}"

    # --- Implementaciones -----------------------------------------------------

    def buscar_en_internet(self, consulta: str, noticias: bool = False) -> str:
        from ddgs import DDGS  # se importa aquí porque tarda y no siempre hace falta

        consulta = re.sub(r"\s+", " ", FECHAS.sub(" ", consulta)).strip() or consulta
        region = "xl-es" if idiomas.actual().codigo == "es" else "us-en"
        buscador = DDGS()
        resultados = []
        # ddgs lanza una excepción cuando no encuentra nada; si no hay noticias, se prueba en la web
        if noticias:
            try:
                resultados = [{"fecha": r.get("date", "")[:10], "titulo": r["title"],
                               "resumen": r.get("body", "")[:300]}
                              for r in buscador.news(consulta, region=region, max_results=5)]
            except Exception as error:
                log.info("Sin noticias para %r (%s); busco en la web", consulta, error)
        if not resultados:
            try:
                resultados = [{"titulo": r["title"], "resumen": r["body"][:300]}
                              for r in buscador.text(consulta, region=region, max_results=5)]
            except Exception as error:
                log.info("Sin resultados para %r (%s)", consulta, error)
        if not resultados:
            return "No he encontrado nada. Prueba otra consulta más corta y sin fechas."
        return json.dumps(resultados, ensure_ascii=False)

    def consultar_clima(self, ciudad: str | None = None) -> str:
        ciudad = ciudad or config.CIUDAD
        geo = requests.get(
            "https://geocoding-api.open-meteo.com/v1/search",
            params={"name": ciudad, "count": 1, "language": "es"}, timeout=8,
        ).json()
        if not geo.get("results"):
            return f"No encuentro ninguna ciudad llamada {ciudad}."
        lugar = geo["results"][0]
        datos = requests.get(
            "https://api.open-meteo.com/v1/forecast",
            params={
                "latitude": lugar["latitude"], "longitude": lugar["longitude"],
                "current": "temperature_2m,apparent_temperature,relative_humidity_2m,"
                           "weather_code,wind_speed_10m",
                "daily": "weather_code,temperature_2m_max,temperature_2m_min,"
                         "precipitation_probability_max",
                "timezone": "auto", "forecast_days": 3,
            },
            timeout=8,
        ).json()
        actual, dias = datos["current"], datos["daily"]
        return json.dumps({
            "lugar": f"{lugar['name']}, {lugar.get('country', '')}",
            "ahora": {
                "cielo": CIELO.get(actual["weather_code"], "desconocido"),
                "temperatura_c": actual["temperature_2m"],
                "sensacion_c": actual["apparent_temperature"],
                "humedad_pct": actual["relative_humidity_2m"],
                "viento_kmh": actual["wind_speed_10m"],
            },
            "prevision": [
                {"fecha": fecha, "cielo": CIELO.get(codigo, "desconocido"),
                 "max_c": maxima, "min_c": minima, "prob_lluvia_pct": lluvia}
                for fecha, codigo, maxima, minima, lluvia in zip(
                    dias["time"], dias["weather_code"], dias["temperature_2m_max"],
                    dias["temperature_2m_min"], dias["precipitation_probability_max"])
            ],
        }, ensure_ascii=False)

    def poner_temporizador(self, minutos: float, motivo: str = "") -> str:
        i = idiomas.actual()
        aviso = i.decir(i.temporizador_motivo, motivo=motivo) if motivo else i.decir(i.temporizador)
        temporizador = threading.Timer(float(minutos) * 60, self._avisar, args=(aviso,))
        temporizador.daemon = True
        temporizador.start()
        return f"Temporizador de {minutos} minutos en marcha."

    def abrir_aplicacion(self, nombre: str) -> str:
        destino = APLICACIONES.get(nombre.lower())
        if destino is None:
            return f"No sé abrir {nombre}. Puedo abrir: {', '.join(APLICACIONES)}."
        os.startfile(destino)
        return f"{nombre} abierto."

    def buscar_en_navegador(self, consulta: str, sitio: str = "google") -> str:
        if sitio == "youtube":
            url = f"https://www.youtube.com/results?search_query={quote_plus(consulta)}"
        else:
            url = f"https://www.google.com/search?q={quote_plus(consulta)}"
        webbrowser.open(url)
        return f"Búsqueda abierta en {sitio}."

    def cambiar_idioma(self, idioma: str) -> str:
        nuevo = idiomas.cambiar(idioma)
        return f"Idioma cambiado a {nuevo.nombre}. Responde desde ahora en {nuevo.nombre}."

    def recordar(self, dato: str) -> str:
        self._memoria.recordar(dato)
        return "Guardado en la memoria."

    def olvidar(self, texto: str) -> str:
        borrados = self._memoria.olvidar(texto)
        return f"Borrados {borrados} recuerdos." if borrados else "No había nada que coincidiera."
