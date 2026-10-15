# Runtime experimental de scores por token — F3.06

Adaptación propia de Sherpa 1.13.8 (commit
`11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`) bajo Apache-2.0, para debug.
El parche y el puente JNI están en `scripts/sherpa-confidence/`; no se altera el
checkout upstream limpio, el AAR baseline ni sus builds/recibos anteriores.

El script `Build-SherpaAsrOnly.ps1 -ConfidencePrototype` copia los 1.179 archivos
tracked necesarios de un checkout sparse local verificado limpio. Esta opción
no descarga objetos Git ausentes ni recursos; un intento inicial de git archive
falló al buscar un objeto de .github no disponible y se reemplazó por la copia
inventariada. El inventario conserva hashes SHA-256 de los bytes originales;
solo los tres archivos del parche se normalizan a LF antes de aplicarlo. Una
configuración core.autocrlf=false en el repositorio de cache evita que git apply
cambie esos bytes. El checkout principal y su índice permanecen intactos.

El decoder NeMo MBS conserva un buffer float reutilizable para guardar el
LogSoftmax anterior al boost de hotwords. Solo copia ese buffer si existe un
grafo contextual; ranking, texto, tokens y timestamps usan la ruta original.
No se almacenan logits completos ni se hace otra llamada de inferencia.

El JNI lee por referencia el resultado de un OfflineStream aún vivo. Devuelve
null para ptr0, vectores vacíos/desalineados, más de 2.048 scores, valores no
finitos o superiores a 1e-5. La capa Kotlin conserva propiedad y exclusión del
stream: un puntero no cero que ya fue liberado no es una entrada válida.
La llamada no registra texto, audio ni scores y no realiza tráfico de red.

El AAR experimental conserva el classes.jar oficial y las 27 licencias/notices
del paquete ASR-only. Incluye README, Apache-2.0, parche, fuente nueva, manifiesto
y recibo de overlay bajo META-INF/yezvi-stt-confidence. La licencia de los pesos,
VAD, Eigen y ORT no cambia. Ningún archivo Eigen/ORT se modifica. Continúan los
límites de auditoría ORT y compatibilidad ELF de 16 KB del runtime baseline.

Los scores representan el token elegido por una hipótesis condicionada por la
secuencia anterior. No son probabilidad calibrada de acierto por palabra y no
permiten detectar directamente una palabra omitida. El experimento no habilita
publicación ni declara completada F3.06.

La revisión Git embebida por las funciones genéricas upstream queda vacía en el
overlay sin commits locales. La procedencia verificable del prototipo está en el
commit fijado, snapshot, parche, fuente JNI y recibos de compilación; no se
presenta el runtime modificado como un binario upstream sin cambios.

## Artefacto completo y comprobación final

Cuatro ABIs terminaron 269 pasos cada una. AAR experimental: 41.222.198 bytes,
SHA-256 `29f4800294ed2492c5e503e48249f2e80a253c0b3f9eab9499b03f83006e02c6`.
`build-receipt.json` conserva flags, hashes, overlay y recipe; el binario queda
ignorado. `Prepare-SpeechConfidenceRuntime.ps1` lo valida y provisiona solo
debug, sin descargas ni sustitución del runtime release.

`runtime-audit.json`: cero problemas técnicos, puente exportado en cuatro ABIs,
ORT/clases oficiales idénticos, 27 avisos retenidos y native/source/link audit.
JNI x86_64 tiene el mismo digest de la versión provisional que ejecutó la primera
tanda: `ffeb57a316bf5a0e55d7ba351c7b0d2958caf50c1c0aa27183c7b78896e05cba`.
`release-audit.json` coteja el APK final con el AAR baseline sin modificar y
mantiene tres bloqueos ELF16KB y cuatro gates manuales. No es aprobación legal
ni prueba de ejecución en ARM64/Samsung. Resultados de inferencia/calibración
en `../../IMPLEMENTACIONES/F3.06-prototipo-confianza.md`.
