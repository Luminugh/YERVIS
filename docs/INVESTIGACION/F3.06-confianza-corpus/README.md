# F3.06 — Corpus público para evaluar incertidumbre

Preparación del 14-10-2026: **105 grabaciones humanas de español latinoamericano**, 2.662 palabras oficiales y 1.265,04 s de audio. Son lecturas de FLEURS `es_419`; no representan conversación espontánea, todos los dialectos, tecnicismos ni el micrófono de un Samsung.

## Procedencia, atribución y licencia

[Google FLEURS](https://huggingface.co/datasets/google/fleurs), revisión fijada `70bb2e84b976b7e960aa89f1c648e09c59f894dd`; [ficha y licencia oficiales de esa revisión](https://huggingface.co/datasets/google/fleurs/blob/70bb2e84b976b7e960aa89f1c648e09c59f894dd/README.md). Autores: Alexis Conneau, Min Ma, Simran Khanuja, Yu Zhang, Vera Axelrod, Siddharth Dalmia, Jason Riesa, Clara Rivera y Ankur Bapna. *FLEURS: Few-shot Learning Evaluation of Universal Representations of Speech*, 2022.

Los audios y transcripciones se utilizan con **CC BY 4.0**. Se conserva atribución, enlace a la fuente y [licencia](https://creativecommons.org/licenses/by/4.0/); las modificaciones se declaran a continuación. El texto legal completo ya está versionado en `app/src/androidTest/assets/stt-stage2/CC-BY-4.0-legalcode.txt` y se copia al caché del corpus. La licencia de este corpus no se aplica automáticamente al código o a los pesos ASR.

Los únicos cambios del audio son la extracción de miembros WAV completos y conversión de IEEE float32 a PCM signed16, mono, 16.000 Hz, con `round(x*32768)` y saturación. No se recorta, normaliza, remuestrea ni altera el texto. Se conservan hashes del original extraído y del WAV preparado, muestras, duración y saturaciones por archivo en `corpus-manifest.json`.

## Descarga acotada e integridad

Se reutilizaron 4.194.304 bytes de la etapa 2 y se obtuvieron **62.914.560 bytes nuevos** mediante HTTP Range 206 del mismo archivo oficial fijado: prefijo total **67.108.864 bytes**. No se descargó el archivo completo de 582.112.372 bytes ni modelos/binarios adicionales.

El SHA-256 publicado del archivo completo es `981802f6c828fd214fcf8bfc1036d80c9184b6eeb5650b3f7882f8affec046c9`; **no está verificado**, porque solo se adquirió un prefijo. Tampoco se verifica el CRC final del gzip incompleto. Los SHA-256 del prefijo y de cada WAV son locales, no firmas ni checksums publicados por muestra. El manifiesto distingue estos hechos.

El extractor solo admite archivos presentes en el TSV oficial ya verificado, comprueba formato y cantidad de muestras y rechaza datos no finitos. No ejecuta código remoto ni extrae rutas arbitrarias. Los archivos de audio y el prefijo permanecen exclusivamente en `.gradle/yezvi-stt-research/confidence-corpus/`; no son assets del APK, no se añaden a Git y no hay audio privado en este corpus.

## Particiones congeladas antes de reconocer

| Uso | Archivos | Palabras de referencia | Duración |
| --- | ---: | ---: | ---: |
| Ajuste (`adjustment`) | 51 | 1.333 | 631,50 s |
| Evaluación independiente (`evaluation`) | 54 | 1.329 | 633,54 s |

Se agrupa por ID de frase y se ordenan los grupos por SHA-256 de `yezvi-confidence-v1:<sentenceId>`; cada grupo completo se asigna a la partición con menor total de palabras de referencia. No se consulta ninguna salida ASR para decidir el reparto. El evaluador rechaza IDs de frase o referencias normalizadas repetidas entre particiones, fixtures duplicados y referencias aproximadas.

El TSV expone género, pero no identifica de forma verificable a cada hablante: **no se afirma independencia de hablantes**. El prefijo tampoco es una muestra aleatoria del conjunto completo. El WAV privado del usuario con referencia aproximada no interviene en partición, selección del umbral ni cifras de calidad de este corpus.

## Ejecución y formato

Preparar de forma reproducible sin repetir descargas existentes:

```powershell
python scripts/Prepare-SpeechConfidenceCorpus.py
```

El manifiesto versionado y el del caché son idénticos. Cada `samples[].path` es un nombre relativo de WAV junto al manifiesto. Para el test Android se copian solo `*.wav`, `corpus-manifest.json` y, si se requiere la atribución en el dispositivo de pruebas, el texto legal; no hace falta transferir el prefijo comprimido de 64 MiB.

Evaluar el JSON privado Android y escribir exclusivamente métricas agregadas:

```powershell
python scripts/Evaluate-SpeechConfidence.py --manifest docs/INVESTIGACION/F3.06-confianza-corpus/corpus-manifest.json --report .gradle/yezvi-stt-research/confidence/confidence-private.json --baseline-report .gradle/yezvi-stt-research/confidence/baseline-private.json --output docs/INVESTIGACION/F3.06-confianza-corpus/metrics.json
python -m unittest discover -s scripts/tests -p test_speech_confidence.py -v
```

Se aceptan `rows` o `runs`. Cada fila del corpus usa `fixture`, `text`, `tokens`, `timestamps`, `tokenLogProbabilities`, `loadMs`, `inferenceMs` y `scoreReadMs`; las referencias se toman exclusivamente del manifiesto público. Las repeticiones de rendimiento usan `phase: "benchmark"`, `scoresEnabled` booleano, `round`, `cpuMs` y `pssKb` cuando estén medidos, y nunca cuentan varias veces para calibrar.

Los tokens BPE `▁` deben reconstruir exactamente el texto; se conserva el rango UTF-16 y se usa el mínimo log-score de las piezas léxicas, excluyendo tokens formados solo por puntuación. Arrays inválidos o scores ausentes desactivan el análisis del segmento. La normalización para WER ignora mayúsculas, puntuación y diacríticos, conserva el orden y no aplica alias de tecnicismos o números. Levenshtein mantiene la precedencia del scorer Kotlin: diagonal, eliminación, inserción. Las omisiones se informan por separado porque no existe una palabra emitida que resaltar.

## Puertas de utilidad y resultados

El umbral se elige **solo en ajuste** maximizando recall con precisión de alertas ≥85 % y cobertura ≤15 %; en empate gana el umbral menor, más conservador. La evaluación exige precisión ≥80 %, al menos 30 alertas y al menos 50 sustituciones/inserciones detectables. También se exigen corpus completo, ≥1.000 palabras por partición y cero archivos de scores inválidos.

`calibrationThreshold` es un candidato descriptivo; `recommendedRuntimeThreshold` solo tiene valor si se cumplen todas las puertas. En cualquier otro caso, `automaticSuggestionsEnabled` queda `false`. Sin referencia de desarrollo, un score no permite inferir el porcentaje real de acierto de una palabra.

Resultados reales en `metrics.json`: 105 archivos completos, cero scores inválidos y texto/tokens/timestamps idénticos al baseline en los 105. WER de ajuste/evaluación 5,551/7,223 %. Umbral elegido solo en ajuste `-1.0763946`: dos avisos correctos de dos en ajuste; en evaluación dos correctos y tres falsos, **40 % de precisión**, detectando **2/84 errores emitidos**. Fallan precisión >=80 % y cantidad >=30 avisos: `recommendedRuntimeThreshold=null`, sugerencias automáticas desactivadas. No se bajan los criterios ni se reajusta mirando evaluación.

Tras detectar que JNI ya convierte `▁` en espacios ASCII, se corrigieron app/host y se volvió a medir el agrupado real. La tanda final tiene cero análisis no disponibles. Benchmark: 30 pares alternados de la misma muestra, sin cambios de texto/tokens/timestamps; medianas JNI 0,034 ms y agrupado 0,652 ms. Los tiempos completos varían ampliamente en AVD; no certifican el presupuesto de latencia, RAM, CPU o temperatura del S24 Ultra. Detalle, recursos y límites en `../../IMPLEMENTACIONES/F3.06-prototipo-confianza.md`.
