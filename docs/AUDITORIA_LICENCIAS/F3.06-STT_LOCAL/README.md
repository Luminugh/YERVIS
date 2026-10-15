# Expediente de licencias y procedencia — STT local F3.06

**Corte documental:** 2026-10-14. **Proyecto:** YEZVI, Android, paquete com.yezvi.assistant.

Este expediente reúne la evidencia de los motores y modelos STT/VAD, los cambios hechos para integrarlos y los avisos que se deben conservar. Sirve como índice técnico para una revisión de derechos o licencias; **no es asesoramiento jurídico, una SBOM exhaustiva, una certificación ni una autorización de publicación**.

## Lectura rápida

- **Release auditado localmente:** runtime propio de sherpa-onnx 1.13.8 sin TTS ni diarización, con FastConformer español RNNT int8, Silero VAD y ONNX Runtime 1.28.2. El AAR base observado mide 41.208.011 bytes y su SHA-256 es afaf0053ec5a94513cbfb08c4b5e7b2d995cad3d07f5dbd93ac4d8c59c506396.
- **Debug:** sustituye el AAR base por el prototipo Sherpa de scores experimentales y añade Vosk/JNA y el modelo español pequeño para diagnóstico/comparación. No son una ruta cloud ni el motor release. Los artefactos y hashes se detallan en la matriz.
- **Audio privado:** el WAV del usuario y la referencia aproximada se excluyen de Git, de los assets de distribución y de este expediente. Los fixtures públicos de FLEURS se reservan a pruebas.
- **Estado de distribución:** bloqueado. La auditoría local marca legalCertification=false, distributionApproved=false, publicationReady=false, tres bloqueos ELF de 16 KB y cuatro gates manuales pendientes. La aprobación del titular y la revisión legal siguen siendo necesarias.
- **F3.06:** continúa abierta para aceptación de voz humana y cierre de la revisión de distribución. La prueba manual del diagnóstico debug puede ejecutarse con conectividad activa; el flujo ASR descrito sigue siendo local.

## Documentos del expediente

- [Componentes, licencias y obligaciones](COMPONENTES_Y_LICENCIAS.md)
- [Procedencia, arquitectura y cambios](PROCEDENCIA_Y_CAMBIOS.md)
- [Índice de evidencias y protocolo de atención](EVIDENCIAS_Y_REQUERIMIENTOS.md)

## Registro técnico relacionado

- [Implementación F3.06 ASR-only](../../IMPLEMENTACIONES/F3.06-runtime-asr-only.md)
- [Plan de auditoría y distribución](../../IMPLEMENTACIONES/F3.06-plan-auditoria-distribucion.md)
- [Prototipo de scores por palabra](../../IMPLEMENTACIONES/F3.06-prototipo-confianza.md)
- [Decisión D-012: Sherpa ASR-only y Vosk debug](../../DECISIONES.md#d-012--runtime-stt-sin-tts-y-comparación-local-vosk-f306)
- [Decisión D-013: incertidumbre experimental](../../DECISIONES.md#d-013--incertidumbre-stt-experimental-y-separada-del-producto-f306)
- [Avisos incluidos en los assets release](../../../app/src/main/assets/stt/ATTRIBUTIONS.md)
- [Manifiesto de recursos con SHA-256](../../../docs/INVESTIGACION/F3.06-asr-only/resources-manifest.json)
- [Manifiesto de licencias/notices](../../../docs/INVESTIGACION/F3.06-asr-only/licenses-manifest.json)
- [Auditoría del artefacto release observado](../../../docs/INVESTIGACION/F3.06-asr-only/release-audit.json)

Los JSON y avisos anteriores conservan los detalles por archivo. Este expediente no copia modelos ni bibliotecas binarias para evitar duplicar artefactos y divergencias entre manifiestos.
