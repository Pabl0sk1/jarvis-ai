"""Búsqueda de dispositivos de casa en la red local."""

import ipaddress
import socket
from concurrent.futures import ThreadPoolExecutor

import requests

PUERTO_SAMSUNG = 8001  # API de las teles Samsung con Tizen
PUERTO_ADB = 5555      # Android con "ADB por red" activado


def ip_local() -> str:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(("8.8.8.8", 80))  # no envía nada; sólo averigua por qué interfaz sale
        return s.getsockname()[0]


def _abierto(ip: str, puerto: int) -> bool:
    with socket.socket() as conexion:
        conexion.settimeout(0.4)
        return conexion.connect_ex((ip, puerto)) == 0


def buscar_dispositivos() -> list[dict]:
    """Recorre la red /24 del PC buscando teles Samsung y Android con ADB por red."""
    red = ipaddress.ip_network(f"{ip_local()}/24", strict=False)

    def revisar(ip: str) -> tuple[str, list[int]]:
        return ip, [p for p in (PUERTO_SAMSUNG, PUERTO_ADB) if _abierto(ip, p)]

    with ThreadPoolExecutor(max_workers=128) as grupo:
        encontrados = [(ip, puertos) for ip, puertos in grupo.map(revisar, map(str, red.hosts()))
                       if puertos]

    dispositivos = []
    for ip, puertos in encontrados:
        if PUERTO_SAMSUNG in puertos:
            try:
                info = requests.get(f"http://{ip}:{PUERTO_SAMSUNG}/api/v2/", timeout=3).json()["device"]
                dispositivos.append({"tipo": "tele Samsung", "ip": ip, "nombre": info.get("name"),
                                     "modelo": info.get("modelName"), "mac": info.get("wifiMac")})
            except (requests.RequestException, KeyError, ValueError):
                dispositivos.append({"tipo": "tele Samsung (sin datos)", "ip": ip})
        if PUERTO_ADB in puertos:
            dispositivos.append({"tipo": "Android con ADB por red", "ip": ip})
    return dispositivos
