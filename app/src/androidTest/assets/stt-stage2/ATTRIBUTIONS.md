# F3.06 etapa 2: subset pequeño de voz humana española

Preparado localmente el 2026-10-12. No se ejecutó ASR, no se usó micrófono, no se subió audio ni se adquirieron modelos/dependencias.

## Procedencia y licencia de datos

Corpus **Google FLEURS**, locale `es_419` (español latinoamericano; no certifica acento peruano). Versión fijada: `70bb2e84b976b7e960aa89f1c648e09c59f894dd`.

La [ficha oficial fijada](https://huggingface.co/datasets/google/fleurs/blob/70bb2e84b976b7e960aa89f1c648e09c59f894dd/README.md) declara `license: cc-by-4.0` y licencia de los datasets CC-BY. Se conserva una copia completa como `fleurs-README.md`. La licencia corresponde al corpus de audio y texto, no se deduce de una licencia de código.

**CC BY 4.0**: [licencia](https://creativecommons.org/licenses/by/4.0/), [texto legal](https://creativecommons.org/licenses/by/4.0/legalcode.en), copia local `CC-BY-4.0-legalcode.txt`.

Atribución: Alexis Conneau, Min Ma, Simran Khanuja, Yu Zhang, Vera Axelrod, Siddharth Dalmia, Jason Riesa, Clara Rivera, Ankur Bapna. *FLEURS: Few-shot Learning Evaluation of Universal Representations of Speech*, 2022. Google FLEURS dataset en Hugging Face. [Artículo original](https://arxiv.org/abs/2205.12446). No implica respaldo de los autores ni de Google.

El [artículo original, §2.1](https://arxiv.org/html/2205.12446v1#S2.SS1) describe grabaciones por hablantes nativos, validación humana y 16 kHz. Es **voz humana leída**, no TTS. No certifica habla espontánea, micrófono del usuario ni precisión del modelo.

## Descarga acotada e integridad

Fuente: [archive oficial fijado](https://huggingface.co/datasets/google/fleurs/resolve/70bb2e84b976b7e960aa89f1c648e09c59f894dd/data/es_419/audio/test.tar.gz).

Se descargaron **solo 4.194.304 bytes**, petición HTTP Range, respuesta **206**, `Content-Range: bytes 0-4194303/582112372`. Se conserva el prefijo como `fleurs-es_419-test.prefix.partial.gz`; no es un archive completo. SHA-256 del prefijo: `c9462abfa84e89983234aef20591a35fcf5edf16845b64a441dcac9db1b87c95`.

El archive completo publicado mide 582.112.372 bytes; **no se descargó completo**, no se verificaron su SHA completo ni el CRC final gzip. `fleurs-archive-publisher-metadata.json` conserva el SHA LFS oficial del archive, expresamente sin afirmar que se verificó. Se extrajeron únicamente miembros WAV completos del prefijo mediante streaming tar local y sin ejecución de código remoto. Cada WAV fuente/preparado tiene SHA-256 calculado localmente en `audio-manifest.json`.

## Muestras preparadas

| Archivo PCM16 mono 16 kHz | Duración | Uso contextual |
| --- | ---: | --- |
| `fleurs-es_419-carpanedo.wav` | 10,62 s | positivo: Carpanedo |
| `fleurs-es_419-san_pedro.wav` | 9,72 s | positivo: Plaza de San Pedro |
| `fleurs-es_419-luna.wav` | 5,64 s | positivo: Luna |
| `fleurs-es_419-negative_tierra.wav` | 8,58 s | negativo: ninguna de las tres expresiones anteriores |

Total: **34,56 s**, **1.106.096 bytes** de WAV preparados. Texto esperado humano: archivos `.expected.txt` y campos `rawText`/`normalizedText` del manifiesto; proviene del TSV oficial fijado, no de una inferencia ASR ni de texto inventado. Unicode preservado sin caracteres U+FFFD.

Cambios: extracción de archivos originales WAVE IEEE Float32; conversión local a PCM signed16 con `round(x * 32768)` y saturación al rango signed16. Sin recorte, normalización, resampling, concatenación o TTS. Se conservan los WAV originales. `saturatedSourceSamples` registra la saturación efectiva de cada archivo.

Para reproducir la preparación, el script local `prepare_fleurs_subset.py` descarga solamente el rango explícito de 4 MiB y rechaza respuestas distintas de 206/Content-Range esperado. `finalize_fleurs_subset.py` reutiliza la extracción local y convierte solo las cuatro muestras seleccionadas. No ejecutan scripts remotos ni modelos. No ejecutarlos como descarga de modelos/dependencias.

## Límites y muestra sherpa excluida

Las cuatro muestras permiten comprobar español humano, propuestas genéricas de sesgo léxico y falsas inserciones sobre el mismo audio sin/con sesgo. No prueban YEZVI, Kotlin, Jetpack ni Nalvero; para esos positivos hace falta el audio autorizado solicitado al usuario.

El paquete existente `sherpa-onnx-nemo-fast-conformer-transducer-es-1424-int8.tar.bz2` contiene `test_wavs/es-spanish.wav`. La documentación oficial lo identifica como muestra pero no se obtuvo atribución/licencia de esa grabación. **No se extrajo, reprodujo, convirtió, incorporó ni utilizó para pruebas**. Licencias Apache del runtime/CC-BY del modelo no autorizan automáticamente el audio.

No se modificaron Kotlin, Gradle, assets de producción, contratos, Room ni `docs/ESTADO.md`. El directorio vive en `.gradle`, ignorado y susceptible de limpieza; el agente principal debe conservar documentación/licencias/manifiesto al empaquetar fixtures instrumentados autorizados.
