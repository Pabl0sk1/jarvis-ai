"""Vigila la corriente de la notebook: avisa si hay un corte de luz y la apaga antes de que muera la batería."""

import ctypes
import logging
import subprocess
import threading
import time
from typing import Callable

from . import config

log = logging.getLogger(__name__)
INTERVALO = 10  # segundos entre comprobaciones


class _EstadoEnergia(ctypes.Structure):
    _fields_ = [("ACLineStatus", ctypes.c_byte), ("BatteryFlag", ctypes.c_byte),
                ("BatteryLifePercent", ctypes.c_byte), ("SystemStatusFlag", ctypes.c_byte),
                ("BatteryLifeTime", ctypes.c_ulong), ("BatteryFullLifeTime", ctypes.c_ulong)]


def estado_energia() -> tuple[bool | None, int | None]:
    """(¿enchufada?, % de batería). None si Windows no lo sabe."""
    estado = _EstadoEnergia()
    ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(estado))
    enchufada = {0: False, 1: True}.get(estado.ACLineStatus)
    bateria = None if estado.BatteryLifePercent in (-1, 255) else int(estado.BatteryLifePercent) & 0xFF
    return enchufada, bateria


class VigilanteEnergia:
    """Avisa al cortarse y al volver la luz, y programa un apagado seguro si la batería baja de un mínimo."""

    def __init__(self, avisar: Callable[[str], None]):
        self._avisar = avisar
        self._apagado_programado = False

    def iniciar(self) -> None:
        threading.Thread(target=self._vigilar, daemon=True, name="jarvis-energia").start()

    def _vigilar(self) -> None:
        antes, _ = estado_energia()
        while True:
            time.sleep(INTERVALO)
            try:
                ahora, bateria = estado_energia()
                self._revisar(antes, ahora, bateria)
                antes = ahora
            except Exception as error:  # nunca debe tumbar a Jarvis
                log.warning("No pude leer el estado de la batería: %s", error)

    def _revisar(self, antes: bool | None, ahora: bool | None, bateria: int | None) -> None:
        if antes is True and ahora is False:
            self._avisar(f"Atención: se ha cortado la corriente. Funciono con batería"
                         + (f", al {bateria} por ciento." if bateria is not None else "."))
        if antes is False and ahora is True:
            self._avisar("Ha vuelto la corriente.")
            if self._apagado_programado:
                subprocess.run(["shutdown", "/a"], capture_output=True)
                self._apagado_programado = False
                self._avisar("He cancelado el apagado.")
        if (ahora is False and bateria is not None and bateria <= config.BATERIA_MINIMA
                and not self._apagado_programado):
            self._avisar(f"La batería está al {bateria} por ciento y sigue sin haber corriente. "
                         "Apagaré la notebook en un minuto para no perder nada.")
            subprocess.run(["shutdown", "/s", "/t", "60"], capture_output=True)
            self._apagado_programado = True
