"""Micrófono siempre abierto y sonidos del sistema."""

import queue
from collections import deque

import numpy as np
import sounddevice as sd

from . import config

FRECUENCIA = 16000
BLOQUE = 1280  # 80 ms: el tamaño que espera openWakeWord
SEG_POR_BLOQUE = BLOQUE / FRECUENCIA


def nivel(bloque: np.ndarray) -> float:
    """Volumen (RMS) de un bloque de audio."""
    return float(np.sqrt(np.mean(bloque.astype(np.float32) ** 2)))


def pitido(frecuencia: float = 880, duracion: float = 0.12) -> None:
    """Pitido corto para indicar que Jarvis te está escuchando. No bloquea."""
    t = np.linspace(0, duracion, int(22050 * duracion), endpoint=False)
    tono = 0.25 * np.sin(2 * np.pi * frecuencia * t) * np.hanning(t.size)
    sd.play(tono.astype(np.float32), 22050)


def _dispositivo(valor: str) -> int | str | None:
    """JARVIS_MICROFONO: vacío = el de Windows, un número o parte del nombre."""
    if not valor:
        return None
    return int(valor) if valor.isdigit() else valor


class Microfono:
    """Graba sin parar en segundo plano y deja el audio en una cola por bloques."""

    def __init__(self):
        self._cola: queue.Queue[np.ndarray] = queue.Queue()
        self._stream = sd.InputStream(samplerate=FRECUENCIA, channels=1, dtype="int16",
                                      blocksize=BLOQUE, callback=self._recibir,
                                      device=_dispositivo(config.MICROFONO))
        self.ruido = 150.0  # ruido de fondo estimado; se ajusta solo mientras espera

    def _recibir(self, indata, frames, tiempo, estado) -> None:
        self._cola.put(indata[:, 0].copy())

    def __enter__(self) -> "Microfono":
        self._stream.start()
        return self

    def __exit__(self, *_) -> None:
        self._stream.stop()
        self._stream.close()

    def leer(self) -> np.ndarray:
        return self._cola.get()

    def vaciar(self) -> None:
        """Descarta el audio acumulado (p. ej. la propia voz de Jarvis)."""
        while True:
            try:
                self._cola.get_nowait()
            except queue.Empty:
                return

    def aprender_ruido(self, bloque: np.ndarray) -> None:
        self.ruido = 0.98 * self.ruido + 0.02 * nivel(bloque)

    def grabar_frase(self, espera_max: float = 6.0, silencio_final: float = 1.0,
                     duracion_max: float = 20.0) -> np.ndarray | None:
        """Espera a que empieces a hablar y graba hasta que te callas.

        Devuelve None si no dices nada en `espera_max` segundos.
        """
        umbral = max(self.ruido * 3, 180)
        previos: deque[np.ndarray] = deque(maxlen=5)  # no cortar el principio de la frase
        bloques: list[np.ndarray] = []
        esperado = silencio = 0.0

        while True:
            bloque = self.leer()
            fuerte = nivel(bloque) > umbral
            if not bloques:
                previos.append(bloque)
                if fuerte:
                    bloques.extend(previos)
                    continue
                esperado += SEG_POR_BLOQUE
                if esperado >= espera_max:
                    return None
                continue

            bloques.append(bloque)
            silencio = 0.0 if fuerte else silencio + SEG_POR_BLOQUE
            if silencio >= silencio_final or len(bloques) * SEG_POR_BLOQUE >= duracion_max:
                return np.concatenate(bloques)
