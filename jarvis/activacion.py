"""Detección de la palabra de activación ("Hey Jarvis"), 100 % local."""

from pathlib import Path

import numpy as np
import openwakeword
from openwakeword.model import Model


class DetectorActivacion:
    def __init__(self, palabra: str, umbral: float):
        """`palabra` es un modelo de openWakeWord ("hey_jarvis") o la ruta a un .onnx propio."""
        self._umbral = umbral
        propio = Path(palabra).suffix == ".onnx"
        openwakeword.utils.download_models(model_names=["__ninguno__"] if propio else [palabra])
        self._modelo = Model(wakeword_models=[palabra], inference_framework="onnx")

    def escuchar(self, bloque: np.ndarray) -> bool:
        puntuaciones = self._modelo.predict(bloque)
        return max(puntuaciones.values(), default=0.0) >= self._umbral

    def reiniciar(self) -> None:
        self._modelo.reset()
