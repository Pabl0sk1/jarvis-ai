"""Texto a voz: Edge TTS (online) con la voz de Windows como respaldo sin internet."""

import asyncio
import io
import logging
import os
import re
import subprocess
import threading

import edge_tts
import sounddevice as sd
import soundfile as sf

from . import idioma

log = logging.getLogger(__name__)

# Lee el texto por la entrada estándar para no tener que escaparlo
_VOZ_WINDOWS = (
    "[Console]::InputEncoding = [Text.Encoding]::UTF8;"
    "Add-Type -AssemblyName System.Speech;"
    "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer;"
    "$c = $env:JARVIS_CULTURA + '-*';"
    "$v = $s.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -like $c } "
    "| Select-Object -First 1;"
    "if ($v) { $s.SelectVoice($v.VoiceInfo.Name) };"
    "$s.Speak([Console]::In.ReadToEnd())"
)


def limpiar_para_voz(texto: str) -> str:
    texto = re.sub(r"https?://\S+", "", texto)
    texto = re.sub(r"[*_#`>|]+", "", texto)
    return re.sub(r"\s+", " ", texto).strip()


class Voz:
    def __init__(self):
        self._turno = threading.Lock()  # un temporizador no puede pisar otra respuesta

    def hablar(self, texto: str, voz_edge: str | None = None) -> None:
        texto = limpiar_para_voz(texto)
        if not texto:
            return
        i = idioma.actual()
        with self._turno:
            try:
                audio = asyncio.run(self._sintetizar(texto, voz_edge or i.voz, i.velocidad, i.tono))
                datos, frecuencia = sf.read(io.BytesIO(audio), dtype="float32")
            except Exception as error:
                log.warning("Edge TTS no disponible (%s). Uso la voz de Windows.", error)
                self._hablar_con_windows(texto, i.codigo)
                return
            sd.play(datos, frecuencia)
            sd.wait()

    async def _sintetizar(self, texto: str, voz: str, velocidad: str, tono: str) -> bytes:
        comunicador = edge_tts.Communicate(texto, voz, rate=velocidad, pitch=tono)
        audio = bytearray()
        async for trozo in comunicador.stream():
            if trozo["type"] == "audio":
                audio.extend(trozo["data"])
        return bytes(audio)

    def _hablar_con_windows(self, texto: str, cultura: str) -> None:
        subprocess.run(["powershell", "-NoProfile", "-Command", _VOZ_WINDOWS],
                       input=texto, text=True, encoding="utf-8", timeout=120,
                       env={**os.environ, "JARVIS_CULTURA": cultura})
