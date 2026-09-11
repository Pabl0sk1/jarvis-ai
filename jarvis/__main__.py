"""Arranque de Jarvis.

    python -m jarvis                  modo voz: di "Hey Jarvis" y habla
    python -m jarvis --idioma en      arrancar en inglés
    python -m jarvis --texto          chat por teclado (para probar sin micrófono)
    python -m jarvis --texto --hablar chat por teclado, pero Jarvis responde en voz alta
    python -m jarvis --probar-voces   escuchar las voces disponibles para elegir una
"""

import argparse
import logging
import re
import sys
import unicodedata

from . import config, idioma
from .cerebro import Cerebro
from .herramientas import Herramientas
from .memoria import Memoria
from .personalidad import saludo
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
    parser.add_argument("--debug", action="store_true", help="mostrar más detalles")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO if args.debug else logging.WARNING,
                        format="[%(levelname)s] %(name)s: %(message)s")
    if args.idioma:
        idioma.cambiar(args.idioma)

    if args.probar_voces:
        probar_voces(Voz())
        return

    memoria = Memoria()
    voz = Voz() if (not args.texto or args.hablar) else None
    avisar = voz.hablar if voz else (lambda mensaje: print(f"\nJarvis: {mensaje}\nTú: ", end=""))
    cerebro = Cerebro(Herramientas(memoria, avisar), memoria)
    print(f"JARVIS · idioma: {idioma.actual().nombre} · cerebro: {cerebro.describir()}")

    try:
        if args.texto:
            modo_texto(cerebro, voz)
        else:
            modo_voz(cerebro, voz)
    except KeyboardInterrupt:
        pass
    print("\nJarvis desconectado.")


if __name__ == "__main__":
    main()
