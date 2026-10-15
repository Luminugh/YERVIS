# Procedencia, arquitectura y cambios de F3.06 STT

## Arquitectura por capas

La cadena del motor principal es **AudioRecord PCM mono 16 kHz → Silero VAD → sherpa-onnx FastConformer RNNT int8 → texto de borrador**. La sesión es controlada por VoiceCaptureSessionController y SpeechToTextEngine. La UI de diagnóstico está en src/debug. La voz no se guarda automáticamente en Room. No existe fallback cloud en esta ruta. El diagnóstico puede ejecutarse con conectividad activa, pero el indicador de red no es una ruta de envío.

| Código/archivo | Responsabilidad y procedencia |
| --- | --- |
| app/src/main/java/com/yezvi/assistant/domain/speech/SpeechToTextEngine.kt | Contrato neutro de proveedor, F3.03. |
| app/src/main/java/com/yezvi/assistant/data/speech/AudioRecordPcmSource.kt | Captura PCM tras acción explícita y permiso runtime. |
| app/src/main/java/com/yezvi/assistant/data/speech/LifecyclePcmCapture.kt | Propiedad y cierre de captura al cambiar ciclo de vida. |
| app/src/main/java/com/yezvi/assistant/data/speech/SileroPcmSegmenter.kt | VAD ONNX local; sus pesos son otro componente. |
| app/src/main/java/com/yezvi/assistant/data/speech/SherpaOfflineRecognizerAdapter.kt | Carga/configuración del RNNT y lectura de resultado/tokens. |
| app/src/main/java/com/yezvi/assistant/data/speech/SherpaOfflineSpeechToTextEngine.kt | Adaptador de sesión y recursos para AudioRecord/VAD/Sherpa. |
| app/src/main/java/com/yezvi/assistant/data/speech/LocalSpeechModelFiles.kt | Preparación/verificación de recursos en almacenamiento privado. |
| app/src/main/assets/stt/ | Pesos y avisos Sherpa/NVIDIA/Silero distribuidos desde main; hashes en manifest. |
| app/src/debug/java/com/yezvi/assistant/developer/LocalSpeechDiagnosticsActivity.kt | Diagnóstico debug, referencia/WER y comparación local. No se declara en release. |
| app/src/debug/java/com/yezvi/assistant/data/speech/ | Motor, modelo y assets Vosk de comparación; dependencias debugImplementation. |
| app/src/debug/java/com/yezvi/assistant/ui/screens/SettingsScreenContent.kt | Entrada a herramientas desarrollador visible solo en debug. |
| app/build.gradle.kts | Separa AAR base release del prototipo confidence y Vosk/JNA debug; verifica assets/avisos/ABI. |

F3.07 no se considera implementada por esta ruta: el usuario revisa el resultado en el diagnóstico, sin guardado de voz en Room ni sustitución de Capturar textual.

## Cronología de cambios técnicos

1. **F3.01–F3.03:** investigación de SpeechRecognizer, sherpa y modelos; contrato STT neutro y fake determinista. SpeechRecognizer no garantizaba un modelo español local disponible en todos los dispositivos.
2. **F3.04–F3.05:** permiso contextual y ciclo controlado. El inicio requiere acción explícita y permiso; Detener pide resultado final; Cancelar descarta; ciclo de vida cierra recursos.
3. **F3.06 etapas 1–2:** sherpa-onnx 1.13.8, FastConformer RNNT español int8, tokens, vocabulario derivado y Silero VAD. Pruebas offline con fixtures autorizados; precisión de tokenizer/mediciones y límites constan en los informes por etapa.
4. **F3.06 etapas 3–5:** integración AudioRecord → VAD → Sherpa y pruebas de PCM/WAV. Se distinguen de voz humana real. La aceptación con micrófono humano y frase de referencia exacta sigue pendiente; WAV no demuestra captura viva.
5. **Rebuild ASR-only, D-012:** ante el riesgo GPL de la cadena TTS/eSpeak/UCD del AAR completo investigado, sherpa se reconstruyó desde una revisión fijada, con TTS y diarización desactivados. Se conservaron RNNT, VAD, pesos y contratos; “ASR-only” no significa que solo haya código FastConformer. El NDK r28c fue herramienta del PC y no asset de la app.
6. **Prototipo de scores, D-013:** modificación experimental debug de Sherpa que captura log-score del token seleccionado antes del hotwords boost y lo expone por JNI. Patch SHA-256 27699515005af79815bf47e41f0cd2cb2598c8f39062f81222a63521fe72dd3a; fuente JNI añadida SHA-256 bb1c3cb40baf3520b7ef31aa00ec2a8f86acc4aab423ad070589876731441c99. Archivos upstream tocados: offline-transducer-modified-beam-search-nemo-decoder.cc, jni/CMakeLists.txt y jni/sherpa-onnx-symbols.lds; hashes originales y modificados en docs/INVESTIGACION/F3.06-confianza-runtime/source-manifest.json. No se altera ranking de salida ni se producen probabilidades calibradas; el switch inicia apagado y solo está en debug.
7. **Vosk debug:** Vosk 0.3.75/JNA 5.18.1 y modelo small-es-0.42 se añadieron para comparación de licencia/latencia/calidad. No reemplazaron Sherpa: el A/B documentado mostró peor velocidad y WER para Vosk. La procedencia de algunos objetos prebuilt no está completamente fijada; no promoverlo a release sin reconstrucción/auditoría.
8. **Diagnóstico con red activa, 2026-10-14:** se quitó del Activity debug el gate que impedía iniciar con red activa y se dejó la referencia opcional. Se actualizó el texto de Ajustes: audio/reconocimiento siguen en el dispositivo. Tests UI verifican inicio habilitado sin desconexión ni referencia. No cambia modelo, inferencia, permisos, esquema, ni añade cliente cloud/HTTP.

## Build, variantes y paquetes

Pines CMake, NDK, ORT, dependencias, flags, ABIs, mapas y recibos están en docs/INVESTIGACION/F3.06-asr-only/sources.json y build-receipt.json; la receta está en scripts/Build-SherpaAsrOnly.ps1. Se verificaron ocho archives por URL_HASH; el AAR fija timestamps y sustituye las bibliotecas nativas originales.

- Release observado: AAR base Sherpa propio y assets RNNT/VAD comunes. Hashes del AAR y recursos en resources-manifest.json; el hash del APK release histórico está en F3.06-runtime-asr-only.md y release-audit.json. No es la identidad de un futuro APK firmado.
- Debug observado: AAR confidence experimental, Vosk/JNA y materiales de diagnóstico/test. El APK debug es mayor y no es un artefacto de publicación.
- AAR confidence: 41.222.198 bytes, SHA-256 29f4800294ed2492c5e503e48249f2e80a253c0b3f9eab9499b03f83006e02c6. AAR base release: 41.208.011 bytes, SHA-256 afaf0053ec5a94513cbfb08c4b5e7b2d995cad3d07f5dbd93ac4d8c59c506396.
- Vosk AAR: 13.472.638 bytes, SHA-256 ab2f8b91ac8051561aa325546b35fed9a68b36b8121bac5c6fb927525c4adfad; JNA AAR: 522.677 bytes, SHA-256 7f053e3ec99e14dd71259c82c1c8a02738d64a13c31226b2acc170f3060951e0.
- Encoder int8: 131.113.475 bytes, supera el límite GitHub de 100 MiB. No añadirlo a Git normal ni distribuirlo fuera del gate de recursos.

La auditoría release observada no reportó incidencias técnicas de identidad/exclusión, pero **no concluye cumplimiento legal**. Registra tres bloqueos ELF 16 KB en libandroidx.graphics.path.so arm64/x86_64 y libonnxruntime.so arm64; también faltan pruebas por ABI/API mínima, ejecución en página 16 KB, revisión legal/fuentes y aprobación/firma del titular. Un zipalign exitoso no elimina esos bloqueos.

## Privacidad y límites de datos

- Ningún componente del motor usa fallback cloud; no hay llamadas de red en la interfaz STT/AudioRecord/Sherpa.
- El WAV personal y su referencia no se copian a este expediente, Git, assets o logs.
- FLEURS lleva manifest y atribución propios; los assets de androidTest y el test APK no son material de producto release.
- El teléfono conectado a Internet no implica envío de audio. Esta auditoría cubre la ruta STT documentada, no certifica toda ruta de red de futuras versiones de la app.

## Fuente propia, parches y trazabilidad

- Revisión Sherpa base: 11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf. El classes.jar se fijó por SHA en el recibo; las bibliotecas nativas upstream se sustituyeron.
- El paquete se recompila con NDK r28c, CMake, flags y archivos fijados. Paths absolutos/timestamps no demuestran reproducibilidad universal entre hosts; el AAR reempaquetado conserva SHA con los inputs y el entorno declarados.
- El patch confidence no modifica el checkout upstream limpio ni el AAR base. No se describe como binario upstream intacto.
- Fuente Eigen y paquete de revisión viven localmente en .gradle/yezvi-stt-research/asr-only/ y se excluyen de Git. Antes de limpiar cachés, preservar el source package cubierto en un canal de fuente/oferta autorizado y registrar su hash; una ruta local ignorada no es acceso para destinatarios.
