"""El cerebro de Jarvis: prueba varios modelos de IA en orden hasta que uno responde.

- Claude (de pago), con el SDK de Anthropic.
- Groq y Gemini (planes gratuitos) y Ollama (local), con el formato compatible con OpenAI.
"""

import json
import logging

import anthropic
import requests
from openai import OpenAI

from . import config, idioma
from .herramientas import Herramientas
from .memoria import Memoria
from .personalidad import instrucciones_sistema

log = logging.getLogger(__name__)

MAX_TURNOS = 10  # turnos de conversación que recuerda dentro de una sesión
MAX_PASOS = 5    # rondas de herramientas por respuesta; en la última tiene que contestar ya
URL_GROQ = "https://api.groq.com/openai/v1"
URL_GEMINI = "https://generativelanguage.googleapis.com/v1beta/openai/"
AVISO_ULTIMA_RONDA = ("(Aviso del sistema: ya no puedes usar más herramientas. "
                      "Responde ahora con la información que tienes.)")


class Claude:
    def __init__(self, herramientas: Herramientas):
        self.nombre = f"Claude ({config.MODELO_CLAUDE})"
        self._herramientas = herramientas
        self._cliente = anthropic.Anthropic(api_key=config.ANTHROPIC_API_KEY, max_retries=1, timeout=45)
        self._busqueda_web = config.BUSQUEDA_WEB_CLAUDE

    def disponible(self) -> bool:
        return True

    def responder(self, sistema: str, historial: list[dict], texto: str) -> str:
        herramientas = self._herramientas.para_claude()
        if self._busqueda_web:
            # La búsqueda propia de Claude es mejor que la gratuita, pero cuesta 0,01 USD por búsqueda
            herramientas = [h for h in herramientas if h["name"] != "buscar_en_internet"]
            herramientas.append({
                "type": "web_search_20250305", "name": "web_search", "max_uses": 3,
                "user_location": {"type": "approximate", "city": config.CIUDAD,
                                  "country": config.PAIS, "timezone": config.ZONA_HORARIA},
            })
        mensajes = [*historial, {"role": "user", "content": texto}]

        for paso in range(MAX_PASOS):
            try:
                r = self._cliente.messages.create(
                    model=config.MODELO_CLAUDE, max_tokens=1024, system=sistema,
                    tools=herramientas, messages=mensajes,
                    tool_choice={"type": "none" if paso == MAX_PASOS - 1 else "auto"},
                )
            except anthropic.BadRequestError as error:
                if self._busqueda_web and "web_search" in str(error):
                    log.warning("La búsqueda web no está activada en tu cuenta de Claude; la desactivo.")
                    self._busqueda_web = False
                    return self.responder(sistema, historial, texto)
                raise

            log.info("%s: %d tokens de entrada, %d de salida", self.nombre,
                     r.usage.input_tokens, r.usage.output_tokens)
            if r.stop_reason not in ("tool_use", "pause_turn"):
                return "".join(b.text for b in r.content if b.type == "text").strip()

            mensajes.append({"role": "assistant", "content": r.content})
            if r.stop_reason == "tool_use":
                mensajes.append({"role": "user", "content": [
                    {"type": "tool_result", "tool_use_id": b.id,
                     "content": self._herramientas.ejecutar(b.name, b.input)}
                    for b in r.content if b.type == "tool_use"
                ]})
            # con "pause_turn" basta con reenviar: la búsqueda web continúa donde se quedó

        return idioma.actual().confundido


class CompatibleOpenAI:
    """Cualquier proveedor con API compatible con OpenAI: Groq, Gemini, Ollama..."""

    def __init__(self, nombre: str, modelo: str, url: str, clave: str, herramientas: Herramientas,
                 comprobar=None):
        self.nombre = f"{nombre} ({modelo})"
        self._modelo = modelo
        self._herramientas = herramientas
        self._comprobar = comprobar
        self._cliente = OpenAI(base_url=url, api_key=clave, max_retries=1, timeout=45)

    def disponible(self) -> bool:
        return self._comprobar() if self._comprobar else True

    def responder(self, sistema: str, historial: list[dict], texto: str) -> str:
        mensajes = [{"role": "system", "content": sistema}, *historial,
                    {"role": "user", "content": texto}]

        for paso in range(MAX_PASOS):
            opciones = {"tools": self._herramientas.para_openai()}
            if paso == MAX_PASOS - 1:
                # Última ronda sin herramientas, para que conteste con lo que ya tiene
                # (algunos modelos, como gpt-oss en Groq, ignoran tool_choice="none")
                opciones = {}
                mensajes.append({"role": "user", "content": AVISO_ULTIMA_RONDA})
            r = self._cliente.chat.completions.create(model=self._modelo, messages=mensajes, **opciones)
            if r.usage:
                log.info("%s: %d tokens de entrada, %d de salida", self.nombre,
                         r.usage.prompt_tokens, r.usage.completion_tokens)
            mensaje = r.choices[0].message
            if not mensaje.tool_calls:
                return (mensaje.content or "").strip()
            mensajes.append({
                "role": "assistant", "content": mensaje.content or "",
                # model_dump conserva los campos extra (Gemini necesita su "thought_signature")
                "tool_calls": [llamada.model_dump(exclude_none=True) for llamada in mensaje.tool_calls],
            })
            for llamada in mensaje.tool_calls:
                mensajes.append({
                    "role": "tool", "tool_call_id": llamada.id,
                    "content": self._herramientas.ejecutar(llamada.function.name,
                                                           _argumentos(llamada.function.arguments)),
                })

        return idioma.actual().confundido


def _argumentos(texto: str | None) -> dict:
    try:
        datos = json.loads(texto or "{}")
    except json.JSONDecodeError:
        return {}
    return datos if isinstance(datos, dict) else {}


def _ollama_en_marcha() -> bool:
    try:
        return requests.get(config.OLLAMA_HOST, timeout=1).ok
    except requests.RequestException:
        return False


class Cerebro:
    def __init__(self, herramientas: Herramientas, memoria: Memoria):
        self._memoria = memoria
        self._historial: list[dict] = []
        self._proveedores = [p for p in (_crear(nombre, herramientas) for nombre in config.CEREBROS) if p]

    def describir(self) -> str:
        if not self._proveedores:
            return "ninguno: añade una clave en el archivo .env"
        return " → ".join(p.nombre + ("" if p.disponible() else " [apagado]")
                          for p in self._proveedores)

    def responder(self, texto: str) -> str:
        sistema = instrucciones_sistema(self._memoria.como_texto())
        for proveedor in self._proveedores:
            if not proveedor.disponible():
                continue
            try:
                respuesta = proveedor.responder(sistema, self._historial, texto)
            except Exception as error:
                log.warning("%s no responde (%s). Pruebo con el siguiente.", proveedor.nombre, error)
                continue
            if respuesta:
                self._historial += [{"role": "user", "content": texto},
                                    {"role": "assistant", "content": respuesta}]
                self._historial = self._historial[-2 * MAX_TURNOS:]
                return respuesta

        i = idioma.actual()
        return i.decir(i.sin_cerebro)

    def olvidar_conversacion(self) -> None:
        self._historial.clear()


def _crear(nombre: str, herramientas: Herramientas):
    """Crea el proveedor si tiene lo que necesita (clave o Ollama); si no, devuelve None."""
    if nombre == "claude":
        return Claude(herramientas) if config.ANTHROPIC_API_KEY else None
    if nombre == "groq":
        return (CompatibleOpenAI("Groq", config.MODELO_GROQ, URL_GROQ, config.GROQ_API_KEY, herramientas)
                if config.GROQ_API_KEY else None)
    if nombre == "gemini":
        return (CompatibleOpenAI("Gemini", config.MODELO_GEMINI, URL_GEMINI, config.GEMINI_API_KEY,
                                 herramientas)
                if config.GEMINI_API_KEY else None)
    if nombre == "local":
        return CompatibleOpenAI("local", config.MODELO_LOCAL, f"{config.OLLAMA_HOST}/v1", "ollama",
                                herramientas, comprobar=_ollama_en_marcha)
    log.warning("Cerebro desconocido en JARVIS_CEREBROS: %s (usa claude, groq, gemini o local)", nombre)
    return None
