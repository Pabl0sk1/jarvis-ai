"""Arranque de Jarvis.

    python -m jarvis                  modo voz: di "Hey Jarvis" y habla
    python -m jarvis --idioma en      arrancar en inglés
    python -m jarvis --texto          chat por teclado (para probar sin micrófono)
    python -m jarvis --texto --hablar chat por teclado, pero Jarvis responde en voz alta
    python -m jarvis --probar-voces   escuchar las voces disponibles para elegir una
    python -m jarvis --buscar-dispositivos  buscar la tele y otros aparatos en la red
    python -m jarvis --emparejar-tele       dar permiso a Jarvis en la tele Samsung
    python -m jarvis --servidor             sólo el servidor para la app del celular (sin voz)
"""

import argparse
import logging
import re
import sys
import threading
import unicodedata

from . import config, idioma
from .cerebro import Cerebro
from .energia import VigilanteEnergia
from .herramientas import Herramientas
from .memoria import Memoria
from .personalidad import saludo
from .respaldo import iniciar_respaldo_diario
from .voz import Voz

VOCES_PRUEBA = {
    "es": ["es-MX-JorgeNeural", "es-US-AlonsoNeural", "es-CO-GonzaloNeural", "es-AR-TomasNeural"],
    "en": ["en-GB-RyanNeural", "en-GB-ThomasNeural"],
}
FRASES_PRUEBA = {
    "es": "Buenas noches, señor. Todos los sistemas en línea. ¿En qué puedo ayudarle?",
    "en": "Good evening, sir. All systems online. How may I assist you?",
}


def normalizar(texto: str) -> str:
    sin_tildes = unicodedata.normalize("NFKD", texto).encode("ascii", "ignore").decode()
    return re.sub(r"[^\w\s]", "", sin_tildes).lower().strip()


def probar_voces(voz: Voz) -> None:
    for codigo, voces in VOCES_PRUEBA.items():
        idioma.cambiar(codigo)
        for nombre in voces:
            print(f"  {nombre}")
            voz.hablar(FRASES_PRUEBA[codigo], voz_edge=nombre)
    print("\nPon la que más te guste en JARVIS_VOZ_ES / JARVIS_VOZ_EN dentro del archivo .env")


def iniciar_servidor(herramientas: Herramientas, memoria: Memoria) -> None:
    """Deja que la app del celular controle esta notebook y comparta la memoria (si hay clave)."""
    if not config.CLAVE_RED:
        return
    from .red import ip_local
    from .servidor import PUERTO_HTTP, Servidor

    try:
        Servidor(herramientas, memoria).iniciar()
    except OSError as error:
        print(f"No pude abrir el servidor para el celular: {error}")
        return
    print(f"Servidor para el celular en {ip_local()}:{PUERTO_HTTP}")


def mostrar_dispositivos() -> None:
    from .red import buscar_dispositivos, ip_local

    print(f"Buscando dispositivos en la red de {ip_local()}...")
    encontrados = buscar_dispositivos()
    if not encontrados:
        print("No encontré nada. ¿Está el PC conectado a la misma red que la tele?")
    for dispositivo in encontrados:
        print(" · " + ", ".join(f"{clave}: {valor}" for clave, valor in dispositivo.items() if valor))
    if any(d["tipo"] == "tele Samsung" for d in encontrados):
        print("\nPon la IP y la MAC de la tele en JARVIS_TELE_IP y JARVIS_TELE_MAC dentro del .env")


def emparejar_tele() -> None:
    from .tele import Tele

    if not config.TELE_IP:
        print("Primero pon JARVIS_TELE_IP en el .env (búscala con --buscar-dispositivos).")
        return
    tele = Tele(config.TELE_IP, config.TELE_MAC)
    if not tele.encendida():
        print("La tele no responde: enciéndela y comprueba que el PC está en su misma red.")
        return
    print("Mira la tele: aparecerá un aviso pidiendo permiso para «Jarvis». Elige «Permitir» "
          "(tienes un minuto)...")
    try:
        tele.emparejar()
    except Exception as error:
        print(f"No se pudo emparejar: {error}")
        return
    if tele.emparejada():
        print("¡Listo! Jarvis ya puede controlar la tele.")
    else:
        print("La tele no dio permiso. Si pulsaste «Denegar», ve en la tele a Configuración → "
              "General → Administrador de dispositivos externos → Administrador de conexión "
              "de dispositivos, borra «Jarvis» de la lista y vuelve a intentarlo.")


def modo_texto(cerebro: Cerebro, voz: Voz | None) -> None:
    print("Escribe tu mensaje ('salir' para terminar).\n")
    while True:
        try:
            texto = input("Tú: ").strip()
        except (EOFError, KeyboardInterrupt):
            break
        if not texto:
            continue
        if normalizar(texto) in ("salir", "exit"):
            break
        respuesta = cerebro.responder(texto)
        print(f"Jarvis: {respuesta}\n")
        if voz:
            voz.hablar(respuesta)


def modo_voz(cerebro: Cerebro, voz: Voz) -> None:
    from .activacion import DetectorActivacion
    from .audio import Microfono
    from .transcripcion import Transcriptor

    print("Cargando el oído (la primera vez descarga los modelos y tarda un poco)...")
    transcriptor = Transcriptor(config.MODELO_WHISPER)
    detector = DetectorActivacion(config.PALABRA_ACTIVACION, config.UMBRAL_ACTIVACION)
    frase = config.PALABRA_ACTIVACION.replace("_", " ").title()

    with Microfono() as mic:
        voz.hablar(saludo())
        mic.vaciar()
        print(f"\nEsperando a que digas «{frase}»... (Ctrl+C para salir)")
        while True:
            bloque = mic.leer()
            if not detector.escuchar(bloque):
                mic.aprender_ruido(bloque)
                continue
            conversar(mic, transcriptor, cerebro, voz)
            detector.reiniciar()
            mic.vaciar()
            print(f"\nEsperando a que digas «{frase}»...")


def conversar(mic, transcriptor, cerebro: Cerebro, voz: Voz) -> None:
    """Una conversación: tras responder sigue escuchando unos segundos sin volver a llamarle."""
    from .audio import pitido

    pitido()
    print("Te escucho...")
    espera = 6.0
    while True:
        audio = mic.grabar_frase(espera_max=espera)
        if audio is None:
            return
        texto = transcriptor.transcribir(audio)
        if not texto:
            return
        print(f"Tú: {texto}")
        i = idioma.actual()
        if normalizar(texto) in i.despedidas:
            voz.hablar(i.decir(i.despedida))
            return
        respuesta = cerebro.responder(texto)
        print(f"Jarvis: {respuesta}")
        voz.hablar(respuesta)
        mic.vaciar()
        if config.SEGUNDOS_SEGUIMIENTO <= 0:
            return
        espera = config.SEGUNDOS_SEGUIMIENTO


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(prog="jarvis", description="Asistente personal por voz.")
    parser.add_argument("--texto", action="store_true", help="chatear por teclado en vez de por voz")
    parser.add_argument("--hablar", action="store_true", help="en modo texto, responder también en voz alta")
    parser.add_argument("--idioma", choices=list(idioma.IDIOMAS), help="idioma al arrancar (es | en)")
    parser.add_argument("--probar-voces", action="store_true", help="escuchar las voces disponibles")
    parser.add_argument("--buscar-dispositivos", action="store_true",
                        help="buscar la tele y otros aparatos en la red")
    parser.add_argument("--emparejar-tele", action="store_true", help="dar permiso a Jarvis en la tele Samsung")
    parser.add_argument("--servidor", action="store_true",
                        help="sólo el servidor para que la app del celular controle esta notebook")
    parser.add_argument("--debug", action="store_true", help="mostrar más detalles")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO if args.debug else logging.WARNING,
                        format="[%(levelname)s] %(name)s: %(message)s")
    if args.idioma:
        idioma.cambiar(args.idioma)

    if args.probar_voces:
        probar_voces(Voz())
        return
    if args.buscar_dispositivos:
        mostrar_dispositivos()
        return
    if args.emparejar_tele:
        emparejar_tele()
        return

    memoria = Memoria()
    voz = Voz() if (not args.texto or args.hablar) and not args.servidor else None
    avisar = voz.hablar if voz else (lambda mensaje: print(f"\nJarvis: {mensaje}\nTú: ", end=""))
    herramientas = Herramientas(memoria, avisar)
    cerebro = Cerebro(herramientas, memoria)
    print(f"JARVIS · idioma: {idioma.actual().nombre} · cerebro: {cerebro.describir()}")
    iniciar_servidor(herramientas, memoria)
    VigilanteEnergia(avisar).iniciar()  # cortes de luz
    iniciar_respaldo_diario()           # copia de seguridad de la memoria

    try:
        if args.servidor:
            if not config.CLAVE_RED:
                print("Falta JARVIS_CLAVE_RED en el .env: sin clave no arranco el servidor.")
                return
            print("Sólo servidor: la app del celular ya puede controlar esta notebook. Ctrl+C para salir.")
            threading.Event().wait()
        elif args.texto:
            modo_texto(cerebro, voz)
        else:
            modo_voz(cerebro, voz)
    except KeyboardInterrupt:
        pass
    print("\nJarvis desconectado.")


if __name__ == "__main__":
    main()
