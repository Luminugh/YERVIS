# Índice de evidencias y protocolo ante un requerimiento de derechos

## Evidencia versionada

| Evidencia | Qué respalda | Qué no demuestra |
| --- | --- | --- |
| docs/INVESTIGACION/F3.06-asr-only/resources-manifest.json | Ruta, origen, versión, tamaño y SHA-256 del AAR release y de los recursos modelo/VAD/vocabulario. | Derechos del distribuidor, ejecución real o identidad de un APK futuro. |
| docs/INVESTIGACION/F3.06-asr-only/licenses-manifest.json | 27 licencias/notices, tamaños/hashes y alcance declarado. | Que cada fila del notice agregado corresponda a código enlazado. |
| docs/INVESTIGACION/F3.06-asr-only/sources.json | Revisiones, URL, hashes fuente/dependencias, ORT, toolchain, opciones CMake y exclusiones investigadas. | Build universalmente reproducible o auditoría integral del prebuilt ORT. |
| docs/INVESTIGACION/F3.06-asr-only/build-receipt.json | Commit/opciones del rebuild, ABIs, hashes de inputs y AAR producido. | Firma, aprobación o publicación. |
| docs/INVESTIGACION/F3.06-asr-only/release-audit.json | Resultado técnico del release observado, cotejo AAR→APK, avisos, modelos, manifest, ELFs y gates abiertos. | Certificación legal, SBOM exhaustiva, aprobación del titular o conformidad futura. |
| docs/INVESTIGACION/F3.06-asr-only/eigen-used-headers.json | Headers Eigen en dependencias del compilador x86_64 y avisos por archivo. | Liveness por todas las ABIs ni sustituto del source form. |
| docs/INVESTIGACION/F3.06-confianza-runtime/source-manifest.json, runtime-audit.json, release-audit.json, build-receipt.json | Patch/bridge experimental debug, hashes y separación respecto del AAR release baseline. | Aprobación para publicar ese runtime modificado. |
| docs/INVESTIGACION/F3.06-vosk/recursos-manifest.json, license-manifest.json, native-audit.json, release-audit.json | Identidad/avisos del prototipo Vosk y chequeos técnicos observados. | Origen exacto de objetos prebuilt cuando el script deja ramas sin SHA. |
| app/src/main/assets/stt/ATTRIBUTIONS.md, licenses-manifest.json y licenses/ | Avisos empaquetados para el runtime/modelo release observado. | Que un artefacto futuro no cambió o que el cumplimiento ocurre automáticamente. |
| docs/IMPLEMENTACIONES/F3.06-runtime-asr-only.md, F3.06-prototipo-confianza.md, F3.06-plan-auditoria-distribucion.md, D-012/D-013 | Alcance, resultados y límites anotados durante la implementación. | Asesoría legal ni decisión de distribución del titular. |

El release audit fechado 2026-10-14 registra legalCertification=false, distributionApproved=false y publicationReady=false; tres bloqueos ELF 16 KB y cuatro gates manuales. No trasladar resultados a otro artefacto/hash.

## Avisos y archivos que hay que poder recuperar

1. **Avisos entregados con la app:** los 27 textos de app/src/main/assets/stt/licenses/; el manifest de licencias y release-audit identifican su alcance observado.
2. **Fuentes fijadas:** revisiones/archives en sources.json, build-receipt.json, resources-manifest.json y manifests Vosk/confidence.
3. **MPL/Eigen:** archive 5.0.1, SHA-256 e9c326dc8c05cd1e044c71f30f1b2e34a6161a3b6ecf445d56b53ff1669e3dec; paquete de revisión local en .gradle/yezvi-stt-research/asr-only/license-review-2026-10-14/, ignorado por Git. Protegerlo antes de limpiar y generar una oferta/paquete de source form vinculado a cada release afectado. Una ruta de PC no es acceso de destinatarios.
4. **Modelo NVIDIA:** model card/commit y texto CC BY 4.0, atribución y transformaciones en ATTRIBUTIONS.md; hashes de ONNX, tokens y BPE en resources-manifest.
5. **ORT/NDK:** LICENSE, ThirdPartyNotices, NOTICE y NOTICE.toolchain exactos; separar notices conservadores de componentes probados en ELF.
6. **Audio/transcripciones:** la grabación y referencia personal son privadas y no son parte del bundle. No adjuntarlas en respuesta rutinaria; preservar solo si el usuario lo autoriza y el caso lo requiere específicamente.

## Checklist para cada release futuro

Antes de publicar APK/AAB, conservar en directorio inmutable y fechado:

- variante/canal, versión, commit, fecha y responsable de build;
- SHA-256/tamaño del APK/AAB firmado, identidad de firma, hashes de AAR, SO por ABI, modelos/assets y notices realmente empaquetados;
- SBOM SPDX o CycloneDX desde dependencias efectivas, cotejada con fuentes CMake/Gradle, link maps y ZIP final;
- recibos, revisiones, fuentes modificadas, recetas y toolchain; fuente MPL cubierta o mecanismo de acceso que continúe disponible;
- manifest merger, exclusión debug/Vosk/test assets, prueba de página 16 KB y smoke tests;
- atribución de modelos, licencia exacta, cambios (export ONNX, int8, vocabulario generado) y derechos de corpus.

No reutilizar hashes históricos para reemplazar un artefacto con el mismo nombre. No marcar distributionAuditComplete=true hasta cerrar condiciones sobre un artefacto concreto identificado por su hash.

## Protocolo inicial si llega un aviso o reclamo

1. **Preservar** el mensaje original, fecha, remitente, obra/archivo señalado, canal y hash del artefacto afectado. No modificar ni borrar evidencia.
2. **Identificar alcance:** distinguir código runtime, dependencia, peso/vocabulario, VAD, fixture, compilador/NDK y código YEZVI; localizar variantes que incluyeron esos bytes.
3. **Correlacionar** hash, manifest, tag/commit, snapshot de licencia, modificaciones, build receipt, SBOM/mapa/ELF y notices. Si solo hay snapshot de licencia y no identidad de binario, declararlo.
4. **No admitir ni negar** infracción basándose solo en esta carpeta. Preparar respuesta factual y pasar decisión/respuesta formal al titular de YEZVI y asesoría jurídica competente.
5. Si se confirma omisión u origen dudoso, suspender temporalmente la distribución del artefacto afectado mientras el titular revisa la medida. Preservar evidencia, documentar corrección y generar nuevo hash/auditoría.

Este procedimiento organiza la investigación; no reemplaza plazos, jurisdicción, defensa, respuesta formal ni asesoría jurídica del caso concreto.

## Referencias oficiales primarias

- [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
- [Mozilla Public License 2.0](https://www.mozilla.org/en-US/MPL/2.0/) y [FAQ MPL](https://www.mozilla.org/en-US/MPL/2.0/FAQ/)
- [CC BY 4.0 legal code](https://creativecommons.org/licenses/by/4.0/legalcode)
- [Sherpa source revision](https://github.com/k2-fsa/sherpa-onnx/tree/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf)
- [ONNX Runtime 1.28.2 LICENSE](https://github.com/microsoft/onnxruntime/blob/v1.28.2/LICENSE) y [ThirdPartyNotices](https://github.com/microsoft/onnxruntime/blob/v1.28.2/ThirdPartyNotices.txt)
- [NVIDIA Spanish model card](https://huggingface.co/nvidia/stt_es_fastconformer_hybrid_large_pc/blob/d3fcff82e2ddec13517256402aa5da86400c5893/README.md)
- [Silero VAD LICENSE](https://github.com/snakers4/silero-vad/blob/1e261b036686cd0017d500ee96acd1c4ba572a9d/LICENSE)
- [Eigen 5.0.1 source tag](https://gitlab.com/libeigen/eigen/-/tree/5.0.1?ref_type=tags)
- [Vosk model catalog](https://alphacephei.com/vosk/models)
- [JNA 5.18.1 license choice](https://github.com/java-native-access/jna/blob/5.18.1/LICENSE)
