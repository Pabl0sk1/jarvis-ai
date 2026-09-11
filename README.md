# J.A.R.V.I.S. · Asistente personal por voz

> *"Buenas noches, señor. Todos los sistemas en línea."*

Asistente de inteligencia artificial inspirado en el JARVIS de Iron Man. Vive en tu PC, escucha en segundo plano y se activa cuando dices **"Hey Jarvis"**. Contesta cualquier pregunta por voz, busca en internet, consulta el clima, pone temporizadores, abre aplicaciones y se acuerda de ti entre sesiones.

Habla **español latino** e **inglés** (como en la versión original de la película), y puedes cambiar de idioma en plena conversación.

![Python](https://img.shields.io/badge/Python-3.11%2B-3776AB?logo=python&logoColor=white)
![Windows](https://img.shields.io/badge/Windows-10%20%7C%2011-0078D6?logo=windows&logoColor=white)
![Claude](https://img.shields.io/badge/Cerebro-Claude%20%2B%20Ollama-D97757)
![Licencia MIT](https://img.shields.io/badge/Licencia-MIT-green)

---

## Características

- **Activación por voz**: detecta "Hey Jarvis" en local, sin enviar audio a internet mientras espera.
- **Conversación natural**: después de responder sigue escuchando unos segundos, así que puedes seguir hablando sin volver a llamarle.
- **Cerebro híbrido**: usa [Claude](https://www.anthropic.com/claude) como cerebro principal y un modelo local con [Ollama](https://ollama.com) si no hay internet.
- **Bilingüe**: español latino con trato de usted, o inglés con modales británicos. Se cambia con *"Jarvis, habla en inglés"* / *"Jarvis, speak Spanish"*.
- **Búsqueda en internet**: noticias, resultados, precios y cualquier dato actual.
- **Memoria a largo plazo**: recuerda tus gustos, nombres y rutinas entre sesiones.
- **Herramientas**: clima, temporizadores con aviso por voz, abrir aplicaciones y búsquedas en Google o YouTube.
- **Respaldo sin internet**: si falla la voz online, usa la voz de Windows; si falla Claude, usa el modelo local.

## Cómo funciona

```
🎤 Micrófono (siempre escuchando)
   │
   ▼
1. Activación ── openWakeWord detecta "Hey Jarvis" (local)
   │                 🔔 pitido: "te escucho"
   ▼
2. Oído ──────── faster-whisper convierte tu voz en texto (local)
   │
   ▼
3. Cerebro ───── Claude (nube) ──► si falla ──► Ollama (local)
   │                 └─ herramientas: clima, búsqueda web, temporizadores, memoria...
   ▼
4. Voz ───────── Edge TTS ──► si falla ──► voz de Windows
   │
   ▼
🔊 Altavoz
```

| Pieza | Tecnología | Dónde se ejecuta |
|---|---|---|
| Palabra de activación | [openWakeWord](https://github.com/dscripka/openWakeWord) | Local |
| Voz a texto | [faster-whisper](https://github.com/SYSTRAN/faster-whisper) | Local |
| Cerebro | [Claude API](https://docs.anthropic.com) + [Ollama](https://ollama.com) | Nube + local |
| Texto a voz | [edge-tts](https://github.com/rany2/edge-tts) + Windows SAPI | Nube + local |

## Requisitos

- Windows 10 u 11
- Python 3.11 o superior
- Micrófono y altavoces
- Una clave de la [API de Claude](https://console.anthropic.com/settings/keys) (pago por uso, unos pocos dólares al mes con un uso normal)
- *Opcional:* [Ollama](https://ollama.com) para el cerebro sin internet. Con 8 GB de RAM usa modelos pequeños (~3B) como `qwen2.5:3b`.

## Instalación

```powershell
git clone https://github.com/Pabl0sk1/jarvis-ai.git
cd jarvis-ai

python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt

copy .env.example .env
```

Abre `.env` y pega tu clave en `ANTHROPIC_API_KEY`. Aprovecha para poner tu nombre, tu ciudad y el idioma inicial.

**Cerebro local (opcional):**

```powershell
winget install Ollama.Ollama
ollama pull qwen2.5:3b
```

## Uso

```powershell
iniciar_jarvis.bat                   # modo voz: di "Hey Jarvis" y habla
iniciar_jarvis.bat --idioma en       # arrancar en inglés
iniciar_jarvis.bat --texto           # chatear por teclado (sin micrófono)
iniciar_jarvis.bat --texto --hablar  # por teclado, pero responde en voz alta
iniciar_jarvis.bat --probar-voces    # escuchar las voces para elegir la tuya
iniciar_jarvis.bat --debug           # ver qué herramientas usa y los errores
```

La primera vez descarga los modelos de voz y de activación (unos 500 MB), así que tarda un poco más.

### Ejemplos

| Tú dices | Jarvis |
|---|---|
| "Hey Jarvis, ¿qué tiempo va a hacer mañana?" | Consulta la previsión de tu ciudad |
| "Pon un temporizador de 10 minutos para sacar la pizza" | Te avisa por voz cuando termine |
| "¿Quién ganó el partido de anoche?" | Lo busca en internet |
| "Acuérdate de que mi color favorito es el azul" | Lo guarda en su memoria |
| "Pon música de AC/DC en YouTube" | Abre la búsqueda en el navegador |
| "Abre la calculadora" | Abre la aplicación |
| "Jarvis, habla en inglés" | *"Of course, sir."* A partir de ahí, todo en inglés |
| "Eso es todo" | *"Aquí estaré, señor."* y vuelve a esperar |

## Configuración

Todo se configura en el archivo `.env` (ver [`.env.example`](.env.example)):

| Variable | Por defecto | Descripción |
|---|---|---|
| `ANTHROPIC_API_KEY` | — | Clave de la API de Claude |
| `JARVIS_CEREBRO` | `hibrido` | `hibrido`, `claude` o `local` |
| `JARVIS_MODELO_CLAUDE` | `claude-sonnet-5` | Modelo de Claude |
| `JARVIS_MODELO_LOCAL` | `qwen2.5:3b` | Modelo de Ollama |
| `JARVIS_BUSQUEDA_WEB` | `si` | Búsqueda en internet (sólo con Claude) |
| `JARVIS_NOMBRE_USUARIO` | — | Cómo te llamas |
| `JARVIS_CIUDAD` | `Asunción` | Ciudad para el clima y las búsquedas |
| `JARVIS_IDIOMA` | `es` | Idioma al arrancar: `es` o `en` |
| `JARVIS_VOZ_ES` / `JARVIS_VOZ_EN` | `es-MX-JorgeNeural` / `en-GB-RyanNeural` | Voz de cada idioma |
| `JARVIS_TONO_ES` / `JARVIS_TONO_EN` | `-4Hz` / `-2Hz` | Más negativo = voz más grave |
| `JARVIS_TRATAMIENTO_ES` / `_EN` | `señor` / `sir` | Cómo te llama Jarvis |
| `JARVIS_MICROFONO` | — | Micrófono a usar (número o parte del nombre); vacío = el de Windows |
| `JARVIS_UMBRAL_ACTIVACION` | `0.5` | Sensibilidad de "Hey Jarvis" |
| `JARVIS_MODELO_WHISPER` | `small` | `tiny`, `base`, `small` o `medium` |
| `JARVIS_SEGUNDOS_SEGUIMIENTO` | `6` | Segundos que sigue escuchando tras responder |

## Estructura del proyecto

```
jarvis-ai/
├── jarvis/
│   ├── __main__.py      # arranque y bucle de conversación
│   ├── config.py        # lectura del archivo .env
│   ├── idioma.py        # español latino / inglés, cambio en caliente
│   ├── personalidad.py  # quién es Jarvis y cómo habla
│   ├── cerebro.py       # Claude + Ollama con herramientas
│   ├── herramientas.py  # clima, temporizadores, apps, memoria...
│   ├── memoria.py       # memoria a largo plazo (datos/memoria.json)
│   ├── audio.py         # micrófono y detección de silencio
│   ├── activacion.py    # "Hey Jarvis" con openWakeWord
│   ├── transcripcion.py # voz a texto con faster-whisper
│   └── voz.py           # texto a voz con Edge TTS / Windows
├── .env.example
├── iniciar_jarvis.bat
├── requirements.txt
└── LICENSE
```

## Añadir una herramienta nueva

Las herramientas están en [`jarvis/herramientas.py`](jarvis/herramientas.py). Para añadir una:

1. Escribe un método que devuelva un `str` con el resultado.
2. Regístralo en `self._lista` con un `Herramienta(nombre, descripción, parámetros, función)`.

Claude y Ollama la descubren solos y la usan cuando haga falta.

## Solución de problemas

| Problema | Solución |
|---|---|
| No te oye en absoluto | Revisa que el micrófono no esté silenciado (tecla de silencio del portátil o *Configuración → Sistema → Sonido → Entrada*) y que el volumen de entrada no esté a 0 |
| No se activa al decir "Hey Jarvis" | Baja `JARVIS_UMBRAL_ACTIVACION` a `0.3`–`0.4` y habla claro, cerca del micrófono |
| Se activa solo | Sube `JARVIS_UMBRAL_ACTIVACION` a `0.6`–`0.7` |
| Tarda mucho en entenderte | Usa `JARVIS_MODELO_WHISPER=base` |
| Te entiende mal | Usa `JARVIS_MODELO_WHISPER=medium` (más lento) |
| "No tengo ningún cerebro disponible" | Revisa `ANTHROPIC_API_KEY`, tu conexión o que Ollama esté abierto |
| Se escucha a sí mismo | Baja el volumen de los altavoces o usa auriculares |

## Hoja de ruta

- [x] Activación por voz, conversación y respuestas habladas
- [x] Cerebro híbrido Claude + Ollama
- [x] Español latino e inglés con cambio en caliente
- [x] Memoria a largo plazo y herramientas básicas
- [ ] Modelo de activación propio con sólo "Jarvis"
- [ ] Arranque automático con Windows, en segundo plano
- [ ] Interrumpirle mientras habla
- [ ] Domótica: luces y enchufes inteligentes (Home Assistant)
- [ ] Interfaz visual estilo holograma
- [ ] Hablar con Jarvis desde el móvil (Telegram)
- [ ] Dispositivo dedicado: mini PC o Raspberry Pi 5

## Créditos

- Inspirado en J.A.R.V.I.S. de *Iron Man* (Marvel Studios). Este proyecto no está afiliado a Marvel ni a Disney. Las voces son sintéticas y sólo se parecen en estilo a la de la película.
- Ideas tomadas de [OpenClaw](https://openclaw.ai): memoria persistente, herramientas ampliables y "skills".
- Construido con [openWakeWord](https://github.com/dscripka/openWakeWord), [faster-whisper](https://github.com/SYSTRAN/faster-whisper), [edge-tts](https://github.com/rany2/edge-tts), [Claude](https://www.anthropic.com/claude) y [Ollama](https://ollama.com).

## Licencia

[MIT](LICENSE) © 2026 Pabl0sk1
