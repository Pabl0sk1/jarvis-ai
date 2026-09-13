"""Servidor de red para la app del celular: controlar esta notebook y compartir la memoria.

La app encuentra la notebook sola (pregunta "JARVIS?" por difusión UDP en la red de casa) y le
manda órdenes por HTTP con la clave JARVIS_CLAVE_RED del .env, que también va dentro de la app.
Sólo se pueden usar las herramientas de PERMITIDAS.
"""

import hmac
import json
import logging
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import config
from .herramientas import Herramientas
from .memoria import Memoria

log = logging.getLogger(__name__)

PUERTO_HTTP = 47801
PUERTO_DESCUBRIR = 47800
PERMITIDAS = {"abrir_aplicacion", "buscar_en_navegador", "controlar_pc",
              "controlar_tele", "abrir_app_tele", "ver_apps_tele"}


class Servidor:
    def __init__(self, herramientas: Herramientas, memoria: Memoria):
        self._herramientas = herramientas
        self._memoria = memoria

    def iniciar(self) -> None:
        """Arranca el servidor HTTP y el de descubrimiento en hilos de fondo."""
        http = ThreadingHTTPServer(("0.0.0.0", PUERTO_HTTP), self._manejador())
        threading.Thread(target=http.serve_forever, daemon=True, name="jarvis-http").start()
        threading.Thread(target=self._responder_descubrimiento, daemon=True, name="jarvis-udp").start()

    def _responder_descubrimiento(self) -> None:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(("0.0.0.0", PUERTO_DESCUBRIR))
            while True:
                datos, origen = s.recvfrom(256)
                if datos.strip() == b"JARVIS?":
                    log.info("El celular me busca desde %s", origen[0])
                    respuesta = {"puerto": PUERTO_HTTP, "nombre": socket.gethostname()}
                    s.sendto(json.dumps(respuesta).encode("utf-8"), origen)

    def _manejador(self):
        servidor = self

        class Manejador(BaseHTTPRequestHandler):
            def log_message(self, *args) -> None:  # sin una línea por petición en la consola
                pass

            def _autorizado(self) -> bool:
                autorizado = hmac.compare_digest(self.headers.get("X-Jarvis-Clave", ""), config.CLAVE_RED)
                log.info("%s %s desde %s%s", self.command, self.path, self.client_address[0],
                         "" if autorizado else " (clave incorrecta)")
                return autorizado

            def _responder(self, codigo: int, datos: dict) -> None:
                cuerpo = json.dumps(datos, ensure_ascii=False).encode("utf-8")
                self.send_response(codigo)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(cuerpo)))
                self.end_headers()
                self.wfile.write(cuerpo)

            def do_GET(self) -> None:
                if not self._autorizado():
                    return self._responder(401, {"error": "clave incorrecta"})
                if self.path == "/estado":
                    return self._responder(200, {"nombre": socket.gethostname(), "herramientas": sorted(PERMITIDAS)})
                if self.path == "/memoria":
                    return self._responder(200, {"datos": servidor._memoria.datos()})
                self._responder(404, {"error": "no existe"})

            def do_POST(self) -> None:
                if not self._autorizado():
                    return self._responder(401, {"error": "clave incorrecta"})
                try:
                    largo = int(self.headers.get("Content-Length") or 0)
                    cuerpo = json.loads(self.rfile.read(largo) or b"{}") if largo else {}
                except ValueError:
                    return self._responder(400, {"error": "JSON inválido"})

                if self.path == "/orden":
                    nombre = cuerpo.get("herramienta", "")
                    if nombre not in PERMITIDAS:
                        return self._responder(403, {"error": f"{nombre} no está permitida"})
                    log.info("Orden del celular: %s(%s)", nombre, cuerpo.get("argumentos"))
                    resultado = servidor._herramientas.ejecutar(nombre, cuerpo.get("argumentos") or {})
                    log.info("Resultado: %s", resultado[:200])
                    return self._responder(200, {"resultado": resultado})
                if self.path == "/memoria":
                    anadidos = servidor._memoria.fusionar(cuerpo.get("datos") or [])
                    return self._responder(200, {"anadidos": anadidos, "datos": servidor._memoria.datos()})
                if self.path == "/memoria/olvidar":
                    return self._responder(200, {"borrados": servidor._memoria.olvidar(cuerpo.get("texto", ""))})
                self._responder(404, {"error": "no existe"})

        return Manejador
