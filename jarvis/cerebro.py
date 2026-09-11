"""El cerebro de Jarvis: Claude en la nube, con un modelo local (Ollama) de respaldo."""

import logging

import anthropic
import ollama
import requests

from . import config, idioma
from .herramientas import Herramientas
from .memoria import Memoria
from .personalidad import instrucciones_sistema

log = logging.getLogger(__name__)

MAX_TURNOS = 10  # turnos de conversación que recuerda dentro de una sesión
MAX_PASOS = 6    # rondas de herramientas por respuesta, para no entrar en bucle


class Cerebro:
    def __init__(self, herramientas: Herramientas, memoria: Memoria):
        self._herramientas = herramientas
        self._memoria = memoria
        self._historial: list[dict] = []
        self._busqueda_web = config.BUSQUEDA_WEB

        usar_claude = config.CEREBRO in ("hibrido", "claude") and config.ANTHROPIC_API_KEY
        usar_local = config.CEREBRO in ("hibrido", "local")
        self._claude = (anthropic.Anthropic(api_key=config.ANTHROPIC_API_KEY, max_retries=1, timeout=45)
                        if usar_claude else None)
        self._local = ollama.Client(host=config.OLLAMA_HOST, timeout=120) if usar_local else None

    def describir(self) -> str:
        partes = []
        if self._claude:
            partes.append(f"Claude ({config.MODELO_CLAUDE})")
        if self._local:
            estado = "en marcha" if self._local_disponible() else "Ollama no está en marcha"
            partes.append(f"local ({config.MODELO_LOCAL}, {estado})")
        return " + respaldo ".join(partes) or "ninguno"

    def responder(self, texto: str) -> str:
        respuesta = None
        if self._claude:
            try:
                respuesta = self._con_claude(texto)
            except anthropic.APIError as error:
                log.warning("Claude no responde (%s). Paso al cerebro local.", error)
        if respuesta is None and self._local and self._local_disponible():
            try:
                respuesta = self._con_local(texto)
            except Exception as error:
                log.warning("El cerebro local falló (%s). ¿Descargaste el modelo con "
                            "'ollama pull %s'?", error, config.MODELO_LOCAL)
        if respuesta is None:
            i = idioma.actual()
            return i.decir(i.sin_cerebro)

        self._historial += [{"role": "user", "content": texto},
                            {"role": "assistant", "content": respuesta}]
        self._historial = self._historial[-2 * MAX_TURNOS:]
        return respuesta

    def olvidar_conversacion(self) -> None:
        self._historial.clear()

    # --- Claude ---------------------------------------------------------------

    def _con_claude(self, texto: str) -> str:
        sistema = instrucciones_sistema(self._memoria.como_texto(), self._busqueda_web)
        herramientas = self._herramientas.para_claude()
        if self._busqueda_web:
            herramientas.append({
                "type": "web_search_20250305", "name": "web_search", "max_uses": 3,
                "user_location": {"type": "approximate", "city": config.CIUDAD,
                                  "country": config.PAIS, "timezone": config.ZONA_HORARIA},
            })
        mensajes = [*self._historial, {"role": "user", "content": texto}]

        for _ in range(MAX_PASOS):
            try:
                r = self._claude.messages.create(
                    model=config.MODELO_CLAUDE, max_tokens=1024, system=sistema,
                    tools=herramientas, messages=mensajes,
                )
            except anthropic.BadRequestError as error:
                if self._busqueda_web and "web_search" in str(error):
                    log.warning("La búsqueda web no está activada en tu cuenta de Claude; la desactivo.")
                    self._busqueda_web = False
                    return self._con_claude(texto)
                raise

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

    # --- Modelo local ---------------------------------------------------------

    def _local_disponible(self) -> bool:
        try:
            return requests.get(config.OLLAMA_HOST, timeout=1).ok
        except requests.RequestException:
            return False

    def _con_local(self, texto: str) -> str:
        sistema = instrucciones_sistema(self._memoria.como_texto(), busqueda_web=False)
        mensajes = [{"role": "system", "content": sistema}, *self._historial,
                    {"role": "user", "content": texto}]

        for _ in range(MAX_PASOS):
            r = self._local.chat(model=config.MODELO_LOCAL, messages=mensajes,
                                 tools=self._herramientas.para_ollama(),
                                 options={"temperature": 0.6})
            mensaje = r.message
            if not mensaje.tool_calls:
                return (mensaje.content or "").strip()
            mensajes.append(mensaje)
            for llamada in mensaje.tool_calls:
                mensajes.append({
                    "role": "tool", "tool_name": llamada.function.name,
                    "content": self._herramientas.ejecutar(llamada.function.name,
                                                           dict(llamada.function.arguments)),
                })

        return idioma.actual().confundido
