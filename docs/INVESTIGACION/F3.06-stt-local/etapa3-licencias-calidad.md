# F3.06 — Etapa 3: licencias, referencias y límites de calidad

**Fecha:** 2026-10-12. **Alcance:** síntesis de evidencia existente de etapas 1/2 para la validación de AudioRecord/VAD. Este documento no aporta resultados nuevos de inferencia, VAD o micrófono. Solo se leyeron fuentes locales públicas, manifiestos y el resumen agregado autorizado del caso privado; no se abrió ni copió el WAV del usuario ni su transcripción privada.

La etapa 2 demuestra reconocimiento humano español desde archivos en Pixel 8 API 35 x86_64. Las cuatro muestras públicas permiten repetir una comparación pequeña de ASR/contexto. Los positivos acústicos de YEZVI, Kotlin y Jetpack Compose siguen pendientes. La distribución del AAR completo conserva obligaciones GPL pendientes de resolver.

## 1. Procedencia y licencia por material

| Material | Identidad comprobada previamente | Licencia y límite de alcance |
| --- | --- | --- |
| Runtime sherpa propio | AAR oficial 1.13.8; revisión `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`; SHA AAR `b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471` | Apache-2.0 para sherpa; el AAR combinado también incluye dependencias con sus condiciones. No clasificar todo el AAR como Apache/MIT. |
| ONNX Runtime incorporado | 1.28.2, sin un segundo ORT añadido | MIT; conservar licencia y avisos de terceros. `ThirdPartyNotices` es un conjunto agregado, no un SBOM exacto de los binarios. |
| Pesos españoles encoder/decoder/joiner | NVIDIA `stt_es_fastconformer_hybrid_large_pc`, ficha fijada en `d3fcff82e2ddec13517256402aa5da86400c5893`; conversión sherpa RNNT int8 | CC-BY-4.0. Se documentaron extracción de rama RNNT y cuantización; atribuir a NVIDIA e indicar cambios. Las cifras de la ficha original no son mediciones de esta conversión ni de YEZVI. |
| `tokens.txt` | 1.025 filas, SHA `6191b4853e3654f053c42f6fd184ca53b402987aefdd6ff6baa68034d803ee85`; extraído del paquete RNNT verificado | Vocabulario derivado del modelo CC-BY-4.0, conservado con atribución del modelo. No asignarle la licencia Apache del exportador. |
| `bpe.vocab` | SHA `99fa642e935e8eb5ce835cf1e4fbd238afafb10f6cbcbbe3f1511650124f5597`; generado localmente como token/TAB/`-1.0`/LF desde `tokens.txt` | Derivado CC-BY-4.0; declarar esta transformación. No se recuperaron scores ni normalizador Unigram originales. |
| Tokenizador nativo | simple-sentencepiece v0.7, revisión `62dd423df1d51da5ea06f1c3a046fc04f01b4f39` | Apache-2.0; `darts.h` conserva BSD-2-Clause. La licencia del código y la del vocabulario son distintas. |
| Silero VAD | ONNX existente, 643.854 B, SHA `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6` | MIT upstream Silero, texto conservado. El asset se fija por SHA; no se afirma una versión upstream que no consta. |
| Lista contextual y helper YEZVI | `LocalSpeechVocabulary.kt` y listas del experimento; términos proporcionados/locales | Código propio y selección de términos no adquieren automáticamente la licencia de sherpa/modelo. No se encontró una licencia general de YEZVI en la búsqueda de archivos de licencia; esta auditoría no elige una. Los nombres en la lista no son una grabación positiva ni una fuente fonética. |
| Audio y referencia públicos | Google FLEURS `es_419`, revisión `70bb2e84b976b7e960aa89f1c648e09c59f894dd`, cuatro WAV y textos de TSV oficial | CC-BY-4.0 para corpus audio/texto; atribución Conneau et al./Google y conversión Float32→PCM16 ya conservadas en assets de androidTest. |
| Grabación y referencia del usuario | Caso local de 66,01 s; nombre del archivo no describe el contenido | Autorización específica para pruebas locales; no consta licencia pública ni autorización para difundir grabación o transcripción. El resumen agregado existente no equivale a autorización de publicar el fixture/testAPK. |
| Ejemplo `test_wavs/es-spanish.wav` del paquete sherpa | Ya identificado y excluido | Autoría/licencia del audio no demostradas; continúa fuera del corpus. La licencia de los pesos/runtime no autoriza automáticamente una grabación incluida en su archive. |

Trazabilidad primaria ya conservada: [manifiesto de recursos de producción](../../../app/src/main/assets/stt/resources-manifest.json), [atribuciones STT](../../../app/src/main/assets/stt/ATTRIBUTIONS.md), [manifiesto de fuentes](fuentes-manifest.json), [avisos de terceros](avisos-terceros-manifest.json), [manifiesto de licencias empaquetadas](../../../app/src/main/assets/stt/licenses-manifest.json), [corpus y hashes](../../../app/src/androidTest/assets/stt-stage2/audio-manifest.json). No hubo nuevas descargas ni consultas legales externas en esta revisión.

### Dependencias del runtime y GPL

El inventario existente identifica kaldi-native-fbank 1.22.3, kaldi-decoder 0.3.0, kaldifst 1.8.0 y OpenFST fijado como Apache-2.0; KissFFT como BSD-3-Clause; hclust-cpp como BSD-2-Clause; JSON 3.12.0 como MIT. Se preservan los avisos Hedley (MIT en header, referencia CC0 en documentación) sin resolver silenciosamente esa discrepancia. Piper phonemize 1.2.0 (`f3ff95a…`) es MIT para su código; uni-algo conserva dominio público con alternativa MIT.

La [auditoría GPL de etapa 2](etapa2-gpl-auditoria.md) fija **eSpeak NG fork 1.52.0.1, `ed530aa113046142eb5115cf2fc9157854d0ffe1`, GPL-3.0-or-later**. En las cuatro ABIs del AAR/APK se hallaron funciones JNI TTS definidas y cadenas eSpeak distintivas. No usar TTS no elimina esos bytes. **UCD-tools de esa misma revisión es GPL-3.0-or-later**, según la cabecera de `ctype.c`; el rótulo genérico GPL-3.0 del manifiesto previo fue afinado en aquella auditoría. UCD se sustenta en la cadena fuente de enlace: no hubo atribución binaria independiente tras stripping. Los datos Unicode tienen aviso propio; no sustituyen la GPL de UCD-tools.

La GPL permite distribuir cumpliendo sus condiciones. Para una publicación siguen pendientes el alcance del trabajo combinado y JNI, compatibilidad/licencia del trabajo cubierto, fuente correspondiente exacta, scripts/configuración/patches de build, entrega efectiva de fuente, avisos accesibles y derechos de los destinatarios; información de instalación cuando aplique. No hay SBOM completo, NDK exacto ni atestación integral del build del productor. Los avisos empaquetados no certifican cumplimiento completo. Una futura compilación sin TTS necesitaría verificación nueva de fuentes, ELF, ABIs, hashes y ASR; una flag declarada no prueba eliminación de GPL. AudioRecord/VAD no cambia esta conclusión por sí solo.

## 2. Referencias de calidad y evidencia reutilizable

| Referencia | Qué permite comparar | Límite |
| --- | --- | --- |
| FLEURS: cuatro referencias oficiales | Texto ASR frente a texto esperado; nombres Carpanedo/Plaza de San Pedro/Luna; falsas inserciones de hotwords ausentes; WER descriptivo con normalización explícita | Voz humana leída es_419, 34,56 s y 60 palabras. Texto del TSV oficial, sin auditoría independiente palabra por palabra escuchando audio en esta revisión. No representa español espontáneo/peruano, acento del usuario, micrófono o Samsung. |
| Referencia privada aportada por usuario | Presencia temática de Discord/CPU/Edge y conteos exactos de salida ya resumidos; controles negativos de YEZVI/Kotlin/Jetpack Compose/Nalvero | Referencia aproximada: no se conocen conteos exactos de verdad para asignar todos los TP/FN ni un WER definitivo. El nombre “Guarda esto en YEZVI.wav” no es evidencia de que YEZVI se pronuncie. |
| Probe BPE nativo existente | Representabilidad de términos y piezas/IDs sin `<unk>` en los casos probados | No predice reconocimiento acústico. Es probe separado del AAR; no demuestra equivalencia al Unigram original. |

Fuentes de mediciones existentes: [16 resultados públicos literales](etapa2-resultados-publicos.json), [resumen de resultados sin transcripción privada](etapa2-resultados-resumen.json), [recibo del tokenizador](etapa2-tokenizacion.json), [informe de etapa 2](../../IMPLEMENTACIONES/F3.06-etapa2-vocabulario.md). La instrumentación compara MBS4/CPU2/mono16k con la misma entrada PCM, baseline sin hotwords y sesgos 1/1,5/2; no aplica sustituciones a la salida.

### Matriz de términos: resultados ya medidos en etapa 2

Los cuatro valores de cada fila siguen el orden **baseline / sesgo 1 / sesgo 1,5 / sesgo 2**. “Exacto” aquí ignora mayúsculas y exige palabra/frase completa; no corrige fonética ni grafía. Para la referencia pública se conocen los positivos esperados. Para la privada se muestran menciones de salida, sin convertirlas en recall.

| Término | Positivos disponibles | Exactos en su muestra positiva | Falsas inserciones en negativos disponibles | Interpretación |
| --- | --- | --- | --- | --- |
| YEZVI | Ninguno | N/A | 0 en cada perfil en los cuatro públicos y el privado | Positivo bloqueado por falta de audio autorizado que lo pronuncie. |
| Kotlin | Ninguno | N/A | 0 en cada perfil en los cuatro públicos y el privado | Tokenización posible; calidad acústica pendiente. |
| Compose / Jetpack Compose | Ninguno | N/A | 0 en cada perfil en los cuatro públicos y el privado | La hotword probada es **Jetpack Compose**; no afirmar prueba positiva de Compose aislado. |
| CPU | Privado: presencia según referencia aproximada; multiplicidad no fijada | Menciones de salida **0 / 0 / 0 / 1** | 0 en los cuatro públicos por perfil, donde CPU no se incluyó como hotword | Sesgo 2 recuperó una mención; no demuestra recall completo. |
| Carpanedo | Público: una mención | **0 / 0 / 0 / 0**; salida `Carpaneao` en los cuatro | 0 en los otros tres públicos por perfil | Sustitución persistente; sesgo probado sin beneficio en el nombre. |
| Luna | Público: una mención | **1 / 1 / 1 / 1** | 0 en los otros tres públicos por perfil | Correcta en este caso pequeño. |
| Plaza de San Pedro | Público: una frase | **1 / 1 / 1 / 1** | 0 en los otros tres públicos por perfil | Baseline `plaza`; sesgos `Plaza`. Misma exactitud al ignorar caso; mejora de mayúscula no reduce WER normalizado. |
| Discord | Privado: presencia según referencia aproximada; multiplicidad no fijada | Menciones de salida **0 / 0 / 0 / 0** | 0 en los cuatro públicos por perfil, donde Discord no se incluyó como hotword | No reconocido exacto en la grabación existente; variantes no son aciertos exactos. |
| Edge | Privado: presencia según referencia aproximada; multiplicidad no fijada | Menciones de salida **0 / 0 / 0 / 0** | 0 en los cuatro públicos por perfil, donde Edge no se incluyó como hotword | No reconocido exacto. |

Nalvero también está ausente en ambos corpus y tiene cero inserciones por perfil. Los negativos públicos para YEZVI/Kotlin/Jetpack Compose/Nalvero sí llevan esas hotwords en los perfiles con sesgo. Un resultado 0 en cuatro negativos no establece una tasa poblacional de falsas inserciones igual a cero. Carpanedo/Luna/Plaza no se evaluaron como hotwords en el caso privado actualizado.

**WER público ya informado:** distancia Levenshtein por palabras después de `casefold` y descarte de puntuación solo para medir, **1/60 = 1,67 %**, en los cuatro perfiles. La única sustitución es Carpanedo→Carpaneao. Se mantiene el texto literal como evidencia; esa normalización no se devuelve al usuario ni se guarda como transcripción corregida.

**Tiempos ya informados:** decode público 507–1.532 ms; inicialización 1.693–2.096 ms. RTF agregado público: baseline 0,097; sesgo 1 0,135; 1,5 0,087; 2 0,097. Decode privado 7.254–8.490 ms para 66,01 s. Una ejecución por condición, orden fijo, cachés y carga del host: no prueban que un sesgo acelere el motor ni rendimiento S24 Ultra. El timer de decode excluye preparación, copia PCM e inicialización; no mide latencia de captura ni primer parcial.

## 3. Qué puede medir la etapa 3 sin otro corpus

Reutilizar los cuatro WAV públicos y sus hashes, duración y referencia. Cada resultado nuevo debe identificar configuración VAD, tamaño/orden de bloques, perfil de contexto, límites en muestras de cada segmento y tiempo por operación. Las cifras anteriores permanecen baseline de etapa 2; no se renombrarán como resultados AudioRecord/VAD.

| Medición reproducible | Procedimiento y límite |
| --- | --- |
| Integridad de entrada | Verificar SHA por WAV y PCM16/mono16k/longitud contra manifiesto; conservar la misma entrada entre perfiles. El archive completo FLEURS y su CRC final nunca se verificaron; no afirmar checksum publicado por WAV. |
| ASR por archivo frente a ASR por segmentos VAD | Comparar texto literal y WER normalizado sobre las referencias públicas; ensamblar segmentos en orden sin reemplazos léxicos. Explicitar regla de espacio/puntuación al unir y conservar texto/offsets originales de cada segmento. |
| Contexto y falsas inserciones | Repetir baseline y lista idéntica de hotwords con scores fijados; reportar exactos y ausentes por término. La normalización de métrica debe aplicarse igual a ambos lados sin alterar la salida. |
| Costo real de la ruta | Cronometrar init, VAD, decode por segmento y total procesado; RTF con denominador declarado (audio de entrada o audio seleccionado). Registrar dispositivo/hilos; no comparar tiempos parciales con total de etapa 2 sin aclaración. |
| Segmentación y cobertura de buffers | Contar segmentos, rangos de muestras, duración seleccionada, límites y vaciado al finalizar; comprobar pérdida/duplicación de buffers con entradas controladas. Estas son propiedades de la ruta, no precisión VAD humana. |
| Regresión por chunking | Alimentar el mismo PCM con bloques definidos y verificar orden/offsets/finalización. Repeticiones deben registrarse como ejecuciones, no como nuevas muestras independientes. |

Sin etiquetas temporales humanas de inicio/fin de habla, estos cuatro WAV no permiten afirmar precision/recall/F1 del VAD ni justificar que cada recorte sea correcto. El WER concatenado puede revelar degradación, pero no identifica por sí solo la causa. Una inyección de archivos al pipeline comprueba inferencia/segmentación; no demuestra adquisición por AudioRecord ni calidad del micrófono.

Siguen pendientes audio positivo autorizado con YEZVI/Kotlin/Jetpack Compose/Nalvero y referencia exacta/pronunciación, mayor corpus negativo/espontáneo, captura real offline y Samsung S24 Ultra, memoria/batería y latencia conversacional. La grabación privada existente no desbloquea esos positivos ni se publicará su transcripción en reportes de etapa 3.

## 4. Entrega y límites de esta revisión

Único archivo escrito: este suplemento. No se ejecutaron Gradle, tests, ADB, UI, inferencia ni nuevos downloads; no se editaron código, assets, contratos, Room, `docs/ESTADO.md` o informe principal. El siguiente agente puede usar la matriz y los criterios para documentar mediciones nuevas, manteniendo abierta F3.06 hasta la aceptación requerida.
