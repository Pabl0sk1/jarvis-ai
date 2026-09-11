"""Configuración de Jarvis. Todo se puede cambiar desde el archivo .env."""

import os
from pathlib import Path

from dotenv import load_dotenv

RAIZ = Path(__file__).resolve().parent.parent
DIR_DATOS = RAIZ / "datos"
load_dotenv(RAIZ / ".env")


def _texto(nombre: str, defecto: str) -> str:
    return os.getenv(nombre, defecto).strip()


def _numero(nombre: str, defecto: float) -> float:
    return float(_texto(nombre, str(defecto)).replace(",", "."))


def _si(nombre: str, defecto: str) -> bool:
    return _texto(nombre, defecto).lower() in ("si", "sí", "1", "true")


# Cerebros, en orden de preferencia: se usa el primero que responda.
# Sólo cuentan los que tienen clave ("local" sólo si Ollama está en marcha).
CEREBROS = [c.strip().lower()
            for c in _texto("JARVIS_CEREBROS", "claude,groq,gemini,local").split(",") if c.strip()]
ANTHROPIC_API_KEY = _texto("ANTHROPIC_API_KEY", "")
MODELO_CLAUDE = _texto("JARVIS_MODELO_CLAUDE", "claude-sonnet-5")
BUSQUEDA_WEB_CLAUDE = _si("JARVIS_BUSQUEDA_WEB_CLAUDE", "no")  # de pago: 0,01 USD por búsqueda
GROQ_API_KEY = _texto("GROQ_API_KEY", "")
MODELO_GROQ = _texto("JARVIS_MODELO_GROQ", "openai/gpt-oss-120b")
GEMINI_API_KEY = _texto("GEMINI_API_KEY", "")
MODELO_GEMINI = _texto("JARVIS_MODELO_GEMINI", "gemini-3.5-flash-lite")
MODELO_LOCAL = _texto("JARVIS_MODELO_LOCAL", "qwen2.5:3b")
OLLAMA_HOST = _texto("OLLAMA_HOST", "http://localhost:11434")

# Usuario
NOMBRE_USUARIO = _texto("JARVIS_NOMBRE_USUARIO", "")
CIUDAD = _texto("JARVIS_CIUDAD", "Asunción")
PAIS = _texto("JARVIS_PAIS", "PY")
ZONA_HORARIA = _texto("JARVIS_ZONA_HORARIA", "America/Asuncion")

# Idioma y voz (es = español latino, en = inglés como en la versión original)
IDIOMA = _texto("JARVIS_IDIOMA", "es").lower()
VOZ_ES = _texto("JARVIS_VOZ_ES", "es-MX-JorgeNeural")
TONO_ES = _texto("JARVIS_TONO_ES", "-4Hz")
VELOCIDAD_ES = _texto("JARVIS_VELOCIDAD_ES", "+0%")
TRATAMIENTO_ES = _texto("JARVIS_TRATAMIENTO_ES", "señor")
VOZ_EN = _texto("JARVIS_VOZ_EN", "en-GB-RyanNeural")
TONO_EN = _texto("JARVIS_TONO_EN", "-2Hz")
VELOCIDAD_EN = _texto("JARVIS_VELOCIDAD_EN", "+0%")
TRATAMIENTO_EN = _texto("JARVIS_TRATAMIENTO_EN", "sir")

# Oído
MICROFONO = _texto("JARVIS_MICROFONO", "")  # nombre (o parte) o número; vacío = el de Windows
PALABRA_ACTIVACION = _texto("JARVIS_PALABRA_ACTIVACION", "hey_jarvis")
UMBRAL_ACTIVACION = _numero("JARVIS_UMBRAL_ACTIVACION", 0.5)
MODELO_WHISPER = _texto("JARVIS_MODELO_WHISPER", "small")
SEGUNDOS_SEGUIMIENTO = _numero("JARVIS_SEGUNDOS_SEGUIMIENTO", 6)
