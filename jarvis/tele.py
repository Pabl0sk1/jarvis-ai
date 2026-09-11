"""Control de la tele Samsung (Tizen) por la red de casa.

La primera vez hay que aceptar el permiso en la tele (python -m jarvis --emparejar-tele);
el token queda guardado en datos/tele_token.txt.
"""

import time

import requests
from samsungtvws import SamsungTVWS
from wakeonlan import send_magic_packet

from .config import DIR_DATOS

TECLAS = {
    "subir_volumen": "KEY_VOLUP", "bajar_volumen": "KEY_VOLDOWN", "silenciar": "KEY_MUTE",
    "subir_canal": "KEY_CHUP", "bajar_canal": "KEY_CHDOWN",
    "inicio": "KEY_HOME", "volver": "KEY_RETURN", "ok": "KEY_ENTER",
    "arriba": "KEY_UP", "abajo": "KEY_DOWN", "izquierda": "KEY_LEFT", "derecha": "KEY_RIGHT",
    "fuente": "KEY_SOURCE", "hdmi": "KEY_HDMI", "reproducir": "KEY_PLAY", "pausa": "KEY_PAUSE",
}
ACCIONES = ["encender", "apagar", *TECLAS]
# Por si la tele no devuelve la lista de apps instaladas
APPS_CONOCIDAS = {"youtube": "111299001912", "netflix": "11101200001",
                  "prime video": "3201512006785", "spotify": "3201606009684"}


class Tele:
    def __init__(self, ip: str, mac: str):
        self._ip = ip
        self._mac = mac
        self._token = DIR_DATOS / "tele_token.txt"

    def encendida(self) -> bool:
        try:
            return requests.get(f"http://{self._ip}:8001/api/v2/", timeout=2).ok
        except requests.RequestException:
            return False

    def emparejada(self) -> bool:
        return self._token.exists()

    def emparejar(self) -> None:
        """Conecta pidiendo permiso en la tele; al aceptarlo, la librería guarda el token."""
        tele = self._conectar(emparejando=True)
        try:
            tele.open()
        finally:
            tele.close()

    def controlar(self, accion: str, veces: int = 1) -> str:
        if accion == "encender":
            return self._encender()
        if not self.encendida():
            return "La tele está apagada."
        if accion == "apagar":
            self._pulsar("KEY_POWER")
            return "Tele apagada."
        tecla = TECLAS.get(accion)
        if tecla is None:
            return f"No conozco la acción {accion}."
        self._pulsar(tecla, max(1, min(int(veces), 30)))
        return "Hecho."

    def abrir_app(self, app: str) -> str:
        if not self.encendida():
            return "La tele está apagada; hay que encenderla primero."
        buscada = app.lower().strip()
        tele = self._conectar()
        try:
            instaladas = tele.app_list() or []
            encontrada = next((a for a in instaladas if buscada in a.get("name", "").lower()), None)
            app_id = encontrada["appId"] if encontrada else APPS_CONOCIDAS.get(buscada)
            if not app_id:
                nombres = ", ".join(a.get("name", "") for a in instaladas) or "no pude leerlas"
                return f"No encuentro {app} en la tele. Apps instaladas: {nombres}."
            tele.run_app(app_id)
        finally:
            tele.close()
        return f"Abriendo {encontrada['name'] if encontrada else app} en la tele."

    def _encender(self) -> str:
        if self.encendida():
            return "La tele ya estaba encendida."
        if not self._mac:
            return "No sé la MAC de la tele (JARVIS_TELE_MAC), así que no puedo encenderla por red."
        send_magic_packet(self._mac)
        send_magic_packet(self._mac, ip_address=self._ip.rsplit(".", 1)[0] + ".255")
        for _ in range(15):
            time.sleep(1)
            if self.encendida():
                return "Tele encendida."
        return ("He mandado la orden de encendido, pero la tele no responde. "
                "¿Está activada la opción «Conexión con el móvil» en la tele?")

    def _pulsar(self, tecla: str, veces: int = 1) -> None:
        tele = self._conectar()
        try:
            tele.send_key(tecla, times=veces, key_press_delay=0.3)
        finally:
            tele.close()

    def _conectar(self, emparejando: bool = False) -> SamsungTVWS:
        if not emparejando and not self.emparejada():
            raise RuntimeError("la tele no está emparejada: ejecuta python -m jarvis --emparejar-tele")
        DIR_DATOS.mkdir(parents=True, exist_ok=True)
        return SamsungTVWS(host=self._ip, port=8002, token_file=str(self._token),
                           timeout=60 if emparejando else 8, name="Jarvis")
