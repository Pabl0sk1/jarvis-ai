"""Genera el logo en alta calidad y los iconos de la app a partir de docs/logo_completo.png.

Uso (desde la raíz del proyecto):  .venv\\Scripts\\python.exe android\\generar_iconos.py

docs/logo_completo.png es la imagen original (dibujo + texto "JARVIS AI ASSISTANT"). El script
recorta el dibujo (sin el texto, que en un icono no se leería), lo agranda 8x y prepara dos versiones:

  - COMPLETA, para ir sobre blanco: el diseño tal cual, con la cinta de relleno claro de la "J".
    Ese relleno es un degradado que se funde con el blanco, así que sólo se ve bien sobre blanco.
  - SÓLO TRAZOS, para cualquier fondo (también oscuro): los contornos con su degradado azul-celeste.
    Los bordes se suavizan antes de recortarlos, así salen curvas limpias y no los escalones de
    los píxeles del original.

Genera:
  docs/logo.svg                   logo vectorial (sólo trazos, con el degradado)
  docs/logo_transparente.png      1024 px sin fondo, sólo trazos (para apps y el sistema)
  docs/logo_fondo_blanco.png      1024 px con fondo blanco, diseño completo
  android/.../mipmap-*/           icono de escritorio (fondo blanco, diseño completo) y monocromo
  android/.../drawable-*/         icono de la barra de notificaciones
  android/.../drawable-nodpi/     logo sin fondo (sólo trazos) para la pantalla de la app
"""

import tempfile
from pathlib import Path

import numpy as np
import vtracer
from PIL import Image, ImageFilter

RAIZ = Path(__file__).resolve().parent.parent
DOCS = RAIZ / "docs"
ORIGINAL = DOCS / "logo_completo.png"
RES = RAIZ / "android" / "app" / "src" / "main" / "res"
DENSIDADES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

ESCALA = 8           # cuánto se agranda el dibujo (189 px de ancho -> ~1500 px)
MARGEN = 8           # píxeles del original alrededor del dibujo al recortarlo (el texto está 23 px más abajo)
RUIDO = 6            # "fuerza" del fondo casi blanco que se ignora
FUERZA_MAXIMA = 225  # fuerza (255 - canal más oscuro) del azul de los trazos
SUAVIZADO = 5        # desenfoque (en px ya agrandados) que borra los escalones de los píxeles
# A partir de qué fuerza un píxel es trazo. Con valores bajos (~110) entraba parte del relleno
# claro de la "J" y salían islas; con 170 quedan sólo los contornos.
UMBRAL_TRAZO = 170


def fuerza(rgb: np.ndarray) -> np.ndarray:
    """0 en el fondo blanco, ~225 en el azul de los trazos."""
    return 255.0 - rgb.min(axis=2)


def recortar_dibujo(rgb: np.ndarray) -> np.ndarray:
    """Recorta el primer bloque de filas con contenido (el dibujo; debajo va el texto)."""
    contenido = fuerza(rgb) > 60
    filas = np.nonzero(contenido.any(axis=1))[0]
    saltos = np.nonzero(np.diff(filas) > 1)[0]
    y0, y1 = filas[0], (filas[saltos[0]] if len(saltos) else filas[-1])
    columnas = np.nonzero(contenido[y0:y1 + 1].any(axis=0))[0]
    x0, x1 = columnas[0], columnas[-1]
    alto, ancho = contenido.shape
    return rgb[max(0, y0 - MARGEN):min(alto, y1 + 1 + MARGEN), max(0, x0 - MARGEN):min(ancho, x1 + 1 + MARGEN)]


def separar_del_fondo(rgb: np.ndarray):
    """Pasa el blanco a transparencia: devuelve la opacidad (0..1) y el color puro de cada píxel.

    Sobre blanco, el resultado se ve exactamente como el original.
    """
    alfa = ((fuerza(rgb) - RUIDO) / (FUERZA_MAXIMA - RUIDO)).clip(0, 1)
    # color = alfa * puro + (1 - alfa) * blanco  ->  puro = (color - (1 - alfa) * 255) / alfa
    puro = ((rgb - (1 - alfa[..., None]) * 255) / np.maximum(alfa, 1e-3)[..., None]).clip(0, 255)
    return alfa, puro


def extender_color(color: np.ndarray, alfa: np.ndarray, radio: float = 4) -> np.ndarray:
    """Extiende el color de los trazos hacia lo transparente, para que al escalar no salgan halos."""
    premultiplicado = Image.fromarray((color * alfa[..., None]).astype(np.uint8), "RGB")
    peso = Image.fromarray((alfa * 255).astype(np.uint8))
    p = np.asarray(premultiplicado.filter(ImageFilter.GaussianBlur(radio))).astype(np.float32)
    w = np.asarray(peso.filter(ImageFilter.GaussianBlur(radio))).astype(np.float32)[..., None] / 255
    extendido = (p / np.maximum(w, 1e-3)).clip(0, 255)
    return np.where(alfa[..., None] > 0.05, color, extendido)


def agrandar(valores: np.ndarray) -> np.ndarray:
    """Agranda ESCALA veces una matriz de 0..1 (un canal) o de 0..255 (color)."""
    if valores.ndim == 2:
        imagen = Image.fromarray((valores * 255).clip(0, 255).astype(np.uint8))
    else:
        imagen = Image.fromarray(valores.clip(0, 255).astype(np.uint8), "RGB")
    grande = imagen.resize((imagen.width * ESCALA, imagen.height * ESCALA), Image.LANCZOS)
    resultado = np.asarray(grande).astype(np.float32)
    return resultado / 255 if valores.ndim == 2 else resultado


def contorno_liso(valor_grande: np.ndarray, umbral: float) -> np.ndarray:
    """Recorta con un umbral un canal ya agrandado, después de desenfocarlo: bordes curvos y limpios."""
    imagen = Image.fromarray((valor_grande * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(SUAVIZADO))
    valor = np.asarray(imagen).astype(np.float32) / 255
    duro = Image.fromarray(np.where(valor >= umbral, 255, 0).astype(np.uint8))
    return np.asarray(duro.filter(ImageFilter.GaussianBlur(1))).astype(np.float32) / 255


def afinar(opacidad: np.ndarray) -> np.ndarray:
    """Endurece los bordes con una máscara de enfoque, sin romper los degradados suaves."""
    imagen = Image.fromarray((opacidad * 255).astype(np.uint8))
    imagen = imagen.filter(ImageFilter.UnsharpMask(radius=ESCALA * 0.75, percent=220, threshold=0))
    return np.asarray(imagen).astype(np.float32) / 255


def vectorizar(mascara: np.ndarray) -> str:
    """Traza la máscara ya suavizada (True = trazo) y devuelve el SVG en negro."""
    binaria = Image.fromarray(np.where(mascara, 0, 255).astype(np.uint8))  # vtracer traza lo negro
    with tempfile.TemporaryDirectory() as carpeta:
        entrada, salida = Path(carpeta) / "mascara.png", Path(carpeta) / "logo.svg"
        binaria.convert("RGB").save(entrada)
        vtracer.convert_image_to_svg_py(str(entrada), str(salida), colormode="binary", mode="spline",
                                        filter_speckle=32, corner_threshold=90, length_threshold=5.0,
                                        splice_threshold=45, path_precision=2)
        return salida.read_text(encoding="utf-8")


def ajustar_degradado(rgb: np.ndarray):
    """Ajusta color = a + b*x + c*y (x, y de 0 a 1) al centro de los trazos del original."""
    alto, ancho, _ = rgb.shape
    ys, xs = np.nonzero(fuerza(rgb) > 180)
    matriz = np.column_stack([np.ones(len(xs)), xs / (ancho - 1), ys / (alto - 1)])
    coeficientes, *_ = np.linalg.lstsq(matriz, rgb[ys, xs].astype(np.float64), rcond=None)
    return coeficientes  # 3x3: filas = [a, b, c], columnas = R, G, B


def svg_con_degradado(svg: str, coeficientes, ancho: float, alto: float) -> str:
    """Convierte el SVG negro en uno con el degradado (aproximado con dos colores)."""
    def color(x, y):
        c = (coeficientes[0] + coeficientes[1] * x + coeficientes[2] * y).clip(0, 255)
        return "#%02x%02x%02x" % tuple(int(round(v)) for v in c)
    luz = np.array([0.299, 0.587, 0.114])  # dirección en la que más cambia la luminosidad
    dx, dy = coeficientes[1] @ luz, coeficientes[2] @ luz
    norma = max(1e-6, (dx ** 2 + dy ** 2) ** 0.5)
    inicio, fin = (0.5 - dx / norma / 2, 0.5 - dy / norma / 2), (0.5 + dx / norma / 2, 0.5 + dy / norma / 2)
    degradado = (f'<defs><linearGradient id="degradado" x1="{inicio[0]:.3f}" y1="{inicio[1]:.3f}" '
                 f'x2="{fin[0]:.3f}" y2="{fin[1]:.3f}">'
                 f'<stop offset="0" stop-color="{color(*inicio)}"/>'
                 f'<stop offset="1" stop-color="{color(*fin)}"/></linearGradient></defs>')
    cuerpo = svg[svg.index(">", svg.index("<svg")) + 1:svg.rindex("</svg>")]
    cuerpo = cuerpo.replace('fill="#000000"', 'fill="url(#degradado)"')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {ancho:.0f} {alto:.0f}">'
            f'{degradado}<g>{cuerpo}</g></svg>')


def centrar(dibujo: Image.Image, lado: int, proporcion: float, caja, fondo=(0, 0, 0, 0)) -> Image.Image:
    """Centra el dibujo según `caja` (el contorno del logo), ocupando `proporcion` del lado.

    Todas las versiones usan la misma caja, así quedan exactamente igual de centradas.
    """
    dibujo = dibujo.crop(caja)
    escala = lado * proporcion / max(dibujo.size)
    tamano = (max(1, round(dibujo.width * escala)), max(1, round(dibujo.height * escala)))
    lienzo = Image.new("RGBA", (lado, lado), fondo)
    lienzo.alpha_composite(dibujo.resize(tamano, Image.LANCZOS),
                           ((lado - tamano[0]) // 2, (lado - tamano[1]) // 2))
    return lienzo


def rgba(color: np.ndarray, opacidad: np.ndarray) -> Image.Image:
    return Image.fromarray(np.dstack([color, opacidad * 255]).clip(0, 255).astype(np.uint8), "RGBA")


def main() -> None:
    recorte = recortar_dibujo(np.asarray(Image.open(ORIGINAL).convert("RGB")).astype(np.float32))
    alfa, puro = separar_del_fondo(recorte)
    color = agrandar(extender_color(puro, alfa))

    # Contornos de los trazos, lisos: versión "sólo trazos", siluetas y SVG
    trazos = contorno_liso(agrandar((fuerza(recorte) / FUERZA_MAXIMA).clip(0, 1)), UMBRAL_TRAZO / FUERZA_MAXIMA)

    completo = rgba(color, afinar(agrandar(alfa)))           # para ir sobre blanco
    solo_trazos = rgba(color, trazos)                        # para cualquier fondo
    silueta = rgba(np.full_like(color, 255), trazos)         # blanca: monocromo y notificación
    caja = solo_trazos.getchannel("A").getbbox()

    DOCS.mkdir(exist_ok=True)
    alto, ancho = trazos.shape
    svg = vectorizar(trazos >= 0.5)
    (DOCS / "logo.svg").write_text(svg_con_degradado(svg, ajustar_degradado(recorte), ancho, alto), encoding="utf-8")
    centrar(solo_trazos, 1024, 0.92, caja).save(DOCS / "logo_transparente.png")
    centrar(completo, 1024, 0.72, caja, fondo=(255, 255, 255, 255)).save(DOCS / "logo_fondo_blanco.png")

    for nombre, factor in DENSIDADES.items():
        # Icono adaptativo de 108 dp con fondo blanco: en el escritorio siempre se ve el círculo central
        lado = round(108 * factor)
        carpeta = RES / f"mipmap-{nombre}"
        carpeta.mkdir(parents=True, exist_ok=True)
        centrar(completo, lado, 0.58, caja).save(carpeta / "ic_launcher_foreground.png")
        centrar(silueta, lado, 0.58, caja).save(carpeta / "ic_launcher_monochrome.png")

        carpeta = RES / f"drawable-{nombre}"
        carpeta.mkdir(parents=True, exist_ok=True)
        centrar(silueta, round(24 * factor), 0.92, caja).save(carpeta / "ic_notificacion.png")

    (RES / "drawable-nodpi").mkdir(parents=True, exist_ok=True)
    centrar(solo_trazos, 512, 0.96, caja).save(RES / "drawable-nodpi" / "logo_jarvis.png")
    print(f"Dibujo recortado de {recorte.shape[1]}x{recorte.shape[0]} px, agrandado a "
          f"{completo.width}x{completo.height} px; logo e iconos generados.")


if __name__ == "__main__":
    main()
