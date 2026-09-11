"""Voz a texto con Whisper, 100 % local."""

import re

import numpy as np
from faster_whisper import WhisperModel

from . import idioma

# Frases que Whisper se inventa a veces cuando sólo hay ruido
ALUCINACIONES = ("subtítulos", "amara.org", "gracias por ver", "suscríbete",
                 "thanks for watching", "subtitles by")
PALABRA_ACTIVACION = re.compile(r"^\W*((hey|oye|ey)\W+)?jarvis\W*", re.IGNORECASE)


class Transcriptor:
    def __init__(self, modelo: str):
        self._modelo = WhisperModel(modelo, device="cpu", compute_type="int8")

    def transcribir(self, audio: np.ndarray) -> str:
        muestras = audio.astype(np.float32) / 32768.0
        segmentos, _ = self._modelo.transcribe(muestras, language=idioma.actual().codigo,
                                               beam_size=1, vad_filter=True, hotwords="Jarvis")
        texto = " ".join(s.text.strip() for s in segmentos).strip()
        if any(a in texto.lower() for a in ALUCINACIONES):
            return ""
        # "Hey Jarvis, ¿qué hora es?" -> "¿qué hora es?"
        return PALABRA_ACTIVACION.sub("", texto).strip()
