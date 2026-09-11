"""Memoria a largo plazo: lo que Jarvis sabe de ti entre una sesión y otra."""

import json
from datetime import date
from pathlib import Path

from .config import DIR_DATOS


class Memoria:
    def __init__(self, archivo: Path = DIR_DATOS / "memoria.json"):
        self._archivo = archivo
        self._datos: list[dict] = []
        if archivo.exists():
            self._datos = json.loads(archivo.read_text(encoding="utf-8"))

    def recordar(self, dato: str) -> None:
        self._datos.append({"dato": dato.strip(), "fecha": date.today().isoformat()})
        self._guardar()

    def olvidar(self, texto: str) -> int:
        """Borra los recuerdos que contienen `texto`. Devuelve cuántos borró."""
        antes = len(self._datos)
        self._datos = [d for d in self._datos if texto.lower() not in d["dato"].lower()]
        self._guardar()
        return antes - len(self._datos)

    def como_texto(self) -> str:
        if not self._datos:
            return "(todavía no sabes nada del usuario)"
        return "\n".join(f"- {d['dato']} (anotado el {d['fecha']})" for d in self._datos)

    def _guardar(self) -> None:
        self._archivo.parent.mkdir(parents=True, exist_ok=True)
        self._archivo.write_text(
            json.dumps(self._datos, ensure_ascii=False, indent=2), encoding="utf-8"
        )
