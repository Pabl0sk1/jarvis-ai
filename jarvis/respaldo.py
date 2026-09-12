"""Copia de seguridad automática de la memoria de Jarvis, una vez al día.

Se guarda en datos/respaldos y, si la notebook tiene OneDrive, también en OneDrive/Jarvis/respaldos
(así queda una copia en la nube). Se conservan las últimas 14 copias.
"""

import logging
import os
import threading
import time
import zipfile
from datetime import date
from pathlib import Path

from .config import DIR_DATOS

log = logging.getLogger(__name__)
COPIAS = 14
ARCHIVOS = ("memoria.json", "memoria_celular.json")  # sin tokens ni claves


def destinos() -> list[Path]:
    carpetas = [DIR_DATOS / "respaldos"]
    onedrive = os.environ.get("OneDrive")
    if onedrive and Path(onedrive).is_dir():
        carpetas.append(Path(onedrive) / "Jarvis" / "respaldos")
    return carpetas


def respaldar() -> list[Path]:
    """Hace la copia de hoy si todavía no existe. Devuelve las copias creadas."""
    archivos = [DIR_DATOS / nombre for nombre in ARCHIVOS if (DIR_DATOS / nombre).exists()]
    if not archivos:
        return []
    creadas = []
    for carpeta in destinos():
        carpeta.mkdir(parents=True, exist_ok=True)
        copia = carpeta / f"jarvis_{date.today().isoformat()}.zip"
        if not copia.exists():
            with zipfile.ZipFile(copia, "w", zipfile.ZIP_DEFLATED) as zip_:
                for archivo in archivos:
                    zip_.write(archivo, archivo.name)
            creadas.append(copia)
        for vieja in sorted(carpeta.glob("jarvis_*.zip"))[:-COPIAS]:
            vieja.unlink()
    return creadas


def iniciar_respaldo_diario() -> None:
    """Copia ahora y después cada vez que cambie el día (se revisa cada hora)."""
    def bucle() -> None:
        while True:
            try:
                for copia in respaldar():
                    log.info("Copia de seguridad: %s", copia)
            except OSError as error:
                log.warning("No pude hacer la copia de seguridad: %s", error)
            time.sleep(3600)

    threading.Thread(target=bucle, daemon=True, name="jarvis-respaldo").start()
