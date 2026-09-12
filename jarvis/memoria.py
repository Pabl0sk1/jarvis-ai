"""Memoria a largo plazo: lo que Jarvis sabe de ti entre una sesión y otra.

Se comparte con la app del celular (servidor.py): los recuerdos de los dos se fusionan.
"""

import json
import threading
from datetime import date
from pathlib import Path

from .config import DIR_DATOS


class Memoria:
    def __init__(self, archivo: Path = DIR_DATOS / "memoria.json"):
        self._archivo = archivo
        self._cerrojo = threading.Lock()  # la usan a la vez la conversación y el servidor
        self._datos: list[dict] = []
        if archivo.exists():
            self._datos = json.loads(archivo.read_text(encoding="utf-8"))

    def recordar(self, dato: str) -> None:
        with self._cerrojo:
            self._datos.append({"dato": dato.strip(), "fecha": date.today().isoformat()})
            self._guardar()

    def olvidar(self, texto: str) -> int:
        """Borra los recuerdos que contienen `texto`. Devuelve cuántos borró."""
        with self._cerrojo:
            antes = len(self._datos)
            self._datos = [d for d in self._datos if texto.lower() not in d["dato"].lower()]
            self._guardar()
            return antes - len(self._datos)

    def datos(self) -> list[dict]:
        with self._cerrojo:
            return [dict(d) for d in self._datos]

    def fusionar(self, otros: list[dict]) -> int:
        """Añade los recuerdos de otro dispositivo que aún no tenga. Devuelve cuántos añadió."""
        with self._cerrojo:
            conocidos = {d["dato"].strip().lower() for d in self._datos}
            nuevos = [{"dato": d["dato"].strip(), "fecha": d.get("fecha", date.today().isoformat())}
                      for d in otros
                      if isinstance(d, dict) and str(d.get("dato", "")).strip()
                      and d["dato"].strip().lower() not in conocidos]
            if nuevos:
                self._datos.extend(nuevos)
                self._guardar()
            return len(nuevos)

    def como_texto(self) -> str:
        with self._cerrojo:
            if not self._datos:
                return "(todavía no sabes nada del usuario)"
            return "\n".join(f"- {d['dato']} (anotado el {d['fecha']})" for d in self._datos)

    def _guardar(self) -> None:
        self._archivo.parent.mkdir(parents=True, exist_ok=True)
        self._archivo.write_text(
            json.dumps(self._datos, ensure_ascii=False, indent=2), encoding="utf-8"
        )
