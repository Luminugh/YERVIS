# F3.06 — Etapa 2: auditoría focalizada GPL del AAR existente

**Fecha:** 2026-10-12. **Alcance:** inspección documental y estática del AAR sherpa-onnx 1.13.8 y de la APK debug ya generada. No se recompiló, ejecutó código nativo, abrió micrófono, instaló APK ni publicó nada. Se reutilizaron las licencias y binarios existentes; las consultas adicionales fueron exclusivamente fuentes públicas pequeñas.

**Conclusión:** el artefacto existente conserva TTS y evidencia binaria específica de eSpeak, aunque YEZVI solo utilice ASR. La GPL permite distribución y uso comercial cumpliendo sus condiciones. Los avisos empaquetados no prueban por sí solos cumplimiento completo. Esta auditoría no determina la licencia de toda YEZVI ni certifica una distribución concreta.

## Identidad y alcance de la evidencia

Se leyeron `AGENTS.md`, `docs/ESTADO.md`, `ROADMAP.md`, `docs/IMPLEMENTACIONES/F3.06-etapa1-runtime.md` y `avisos-terceros-manifest.json`. El manifiesto previo sigue siendo suplementario y mantiene `distributionAuditComplete=false`.

| Artefacto local inspeccionado | Bytes | SHA-256 completo |
| --- | ---: | --- |
| `app/libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar` | 38.691.998 | `b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471` |
| `app/build/outputs/apk/debug/app-debug.apk` | 220.095.209 | `9f0c962266592077fd7aa1f9af554460d677fbaf6cc066c777e0d16e56f382dc` |

La APK es un archivo local que podría distribuirse; su inspección **no significa que haya sido publicada**. La etiqueta/revisión de sherpa identificada en etapa 1 es `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`. No se dispone de una atestación completa del compilador/productor que ligue cada instrucción binaria a ese árbol.

## Componentes y revisiones concretas

| Componente | Revisión/identidad fijada | Licencia y fuente primaria |
| --- | --- | --- |
| sherpa-onnx | 1.13.8; `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf` | Apache-2.0 para su código propio; dependencias conservan sus condiciones |
| Piper phonemize | 1.2.0 en CMake; `f3ff95afc03640bc1399e113e83361192a2fafb4` | MIT para su código propio; enlaza eSpeak y, en build estático, UCD |
| eSpeak NG, fork de csukuangfj | CMake declara 1.52.0.1; `ed530aa113046142eb5115cf2fc9157854d0ffe1` | GPL-3.0-or-later según README y cabecera de `src/libespeak-ng/error.c` |
| UCD-tools incorporado en ese árbol eSpeak | Misma revisión `ed530aa113046142eb5115cf2fc9157854d0ffe1`, subdirectorio `src/ucd-tools/` | GPL-3.0-or-later explícita en `src/ucd-tools/src/ctype.c`; `COPYING` contiene el texto GPLv3 |

Fuentes: [README eSpeak fijado](https://github.com/csukuangfj/espeak-ng/blob/ed530aa113046142eb5115cf2fc9157854d0ffe1/README.md#license-information), [versión CMake eSpeak](https://github.com/csukuangfj/espeak-ng/blob/ed530aa113046142eb5115cf2fc9157854d0ffe1/CMakeLists.txt), [cabecera GPL UCD ctype.c](https://github.com/csukuangfj/espeak-ng/blob/ed530aa113046142eb5115cf2fc9157854d0ffe1/src/ucd-tools/src/ctype.c), [Piper CMake fijado](https://github.com/csukuangfj/piper-phonemize/blob/f3ff95afc03640bc1399e113e83361192a2fafb4/CMakeLists.txt).

La identificación UCD como GPL-3.0-or-later afina el rótulo genérico `GPL-3.0` del inventario anterior: un texto COPYING GPLv3 no especifica por sí solo si el titular permite versiones posteriores; la cabecera del archivo sí lo indica. **Unicode data/COPYING.UCD y UCD-tools son materiales diferentes**: la licencia de datos Unicode no sustituye la GPL del código UCD-tools.

Cadena de build fijada:

1. [CMake raíz sherpa](https://github.com/k2-fsa/sherpa-onnx/blob/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf/CMakeLists.txt) habilita TTS por defecto. Bajo esa condición incluye `espeak-ng-for-piper`, establece `ESPEAK_NG_DIR` al árbol eSpeak y después incluye Piper. Esto evita tomar como revisión efectiva la URL alternativa de eSpeak que contiene el CMake autónomo de Piper.
2. [Fetch eSpeak fijado](https://github.com/k2-fsa/sherpa-onnx/blob/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf/cmake/espeak-ng-for-piper.cmake) usa `ed530aa…`, archive SHA-256 declarado `e4e262cbe34f7fe21f91f1ba3397f2728e1f30eafbae7853f2b753a9ed13f0dd`. Es un hash declarado por el build, no un archive descargado/verificado en esta auditoría.
3. [Fetch Piper fijado](https://github.com/k2-fsa/sherpa-onnx/blob/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf/cmake/piper-phonemize.cmake) usa `f3ff95a…`, archive SHA-256 declarado `d9cca4e2bdc7d6dd8dffb96a4668283dbd3f77a9c194a3e530c1e8eba9406a5d`; tampoco se descargó.
4. [Core sherpa](https://github.com/k2-fsa/sherpa-onnx/blob/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf/sherpa-onnx/csrc/CMakeLists.txt) añade las fuentes TTS y enlaza `piper_phonemize`; Piper enlaza `espeak-ng` y `ucd` cuando `BUILD_SHARED_LIBS=OFF`.
5. [UCD CMake](https://github.com/csukuangfj/espeak-ng/blob/ed530aa113046142eb5115cf2fc9157854d0ffe1/src/ucd-tools/CMakeLists.txt) crea `ucd` con `case.c`, `categories.c`, `ctype.c`, `proplist.c`, `scripts.c`, `tostring.c`.

## Evidencia del binario realmente empaquetado

Se abrió cada ZIP con Python estándar, sin extraer ni ejecutar las bibliotecas. Se calcularon SHA-256 sobre bytes descomprimidos y se analizaron headers/secciones ELF de 32/64 bits y entradas de símbolos. Las cuatro bibliotecas de la APK son **idénticas byte a byte** a las entradas `jni/<ABI>/libsherpa-onnx-jni.so` del AAR; empaquetado no retiró el código TTS identificado.

| ABI; entrada APK `lib/<ABI>/libsherpa-onnx-jni.so` | Bytes | SHA-256 |
| --- | ---: | --- |
| arm64-v8a | 24.169.352 | `b4a53d915c19cb6d09b1cdc9a8084be54419b647aab9097ffdfb46f30c3c4dd8` |
| armeabi-v7a | 16.437.284 | `2c0350f9c23dda60318c0d195fcc02d505f05cdbfba89492a4468d2c4276be28` |
| x86 | 5.540.732 | `914ce3d8e77c4ec13b178a8657b2bb9d6187ea6deddbdd519be0373c71eb681d` |
| x86_64 | 27.359.872 | `968e24f61d1e4dae0d5e7860aded42fee45b0f804e1c5a42dfa49186864cefc4` |

Cada ELF tiene `.dynsym` y carece de `.symtab`. En las cuatro ABIs aparecen ocho funciones JNI TTS **definidas** (`STT_FUNC=2`, `st_shndx` no cero: 15, o 16 en ARM32):

```text
Java_com_k2fsa_sherpa_onnx_OfflineTts_delete
Java_com_k2fsa_sherpa_onnx_OfflineTts_generateWithCallbackImpl
Java_com_k2fsa_sherpa_onnx_OfflineTts_generateImpl
Java_com_k2fsa_sherpa_onnx_OfflineTts_getSampleRate
Java_com_k2fsa_sherpa_onnx_OfflineTts_newFromAsset
Java_com_k2fsa_sherpa_onnx_OfflineTts_newFromFile
Java_com_k2fsa_sherpa_onnx_OfflineTts_getNumSpeakers
Java_com_k2fsa_sherpa_onnx_OfflineTts_generateWithConfigImpl
```

**No aparecen nombres de símbolo que empiecen exactamente por `espeak_`, `espeak_ng_` o `ucd_` en las tablas disponibles.** Tampoco se hallan las cadenas `espeak_Initialize` ni `ucd_lookup_category`. Ausencia de nombres después de ocultar/eliminar símbolos no prueba ausencia de código. El patrón de búsqueda debe anclarse: `OfflineSpeakerDiarization` contiene la secuencia inglesa “espeak” al unir `Offline` y `Speaker`; no es un símbolo de eSpeak y se excluyó.

Además de los exports TTS, se encontró la cadena distintiva `Wrong version of espeak-ng-data`, presente en [la función de errores GPL de eSpeak fijada](https://github.com/csukuangfj/espeak-ng/blob/ed530aa113046142eb5115cf2fc9157854d0ffe1/src/libespeak-ng/error.c), y las cadenas de integración de fonemización eSpeak. Offsets de archivo ELF decimal, **no direcciones virtuales**:

| ABI | `Wrong version of espeak-ng-data` | `Failed to phonemize '%s' with espeak-ng voice '%s': %s` | `/tmp/espeakXXXXXX` |
| --- | ---: | ---: | ---: |
| arm64-v8a | 2.350.097 | 2.417.234 | 2.026.937 |
| armeabi-v7a | 2.222.206 | 2.285.512 | 2.795.696 |
| x86 | 332.635 | 343.453 | 290.196 |
| x86_64 | 2.237.576 | 2.304.054 | 1.916.848 |

Esto constituye evidencia estática de TTS/eSpeak retenido en el artefacto entregable, más precisa que citar solo valores por defecto de CMake. Para **UCD**, se comprobó su vínculo explícito en fuentes fijadas, pero no se identificó una función UCD por nombre en el ELF. Su presencia exacta y qué unidades sobrevivieron al linker siguen inferidas de la cadena de enlace; se necesitaría mapa de enlace, binario sin eliminar símbolos o cotejo adicional de código/tablas para atribución binaria independiente. No se presenta esa inferencia como un hallazgo de símbolos UCD.

## Licencias ya presentes: hashes comprobados

Los dos textos GPL y el aviso Unicode siguientes se compararon con sus copias en `assets/stt/licenses/` de la APK: todos son idénticos byte a byte a la caché local. No se volvió a descargar ninguno.

| Archivo existente bajo `.gradle/yezvi-stt-research/licenses/` | Bytes | SHA-256 |
| --- | ---: | --- |
| `espeak-ng-ed530aa-COPYING-GPL3.txt` | 35.147 | `8ceb4b9ee5adedde47b31e975c1d90c73ad27b6b165a1dcd80c7c545eb65b903` |
| `espeak-ng-ed530aa-ucd-tools-COPYING.txt` | 35.147 | `8ceb4b9ee5adedde47b31e975c1d90c73ad27b6b165a1dcd80c7c545eb65b903` |
| `espeak-ng-ed530aa-COPYING.UCD.txt` | 2.788 | `be029c50df83105a810e391778b6edcb522d6cb4b4e142332aca54437a499bf7` |

Sus URLs originales y las demás licencias (Piper MIT, excepciones específicas de eSpeak, sherpa/ORT/modelos) ya constan en `avisos-terceros-manifest.json` y los inventarios de etapa 1. Incluir `COPYING.APACHE` no cambia la licencia general de eSpeak: el alcance de cada aviso exige cotejar los archivos a los que se aplica.

## Distribución permitida y condiciones pendientes

La GPL **permite vender copias y distribuir versiones modificadas** manteniendo los derechos de quienes las reciben. “Hay GPL” no significa “está prohibido distribuir” ni “está prohibido uso comercial”. La [FAQ oficial GNU, uso comercial](https://www.gnu.org/licenses/gpl-faq.en.html#GPLCommercially) confirma ambas cosas. Uso local y hacer modificaciones privadas, sin entregar copias a terceros, no activan por sí solos la obligación general de distribución de fuente (GPLv3 §2).

El texto primario usado para las condiciones es la copia GPLv3 completa ya descargada y comprobada arriba; referencia oficial: [GNU GPLv3](https://www.gnu.org/licenses/gpl-3.0.html). El acceso directo a algunas URLs GNU agotó el tiempo del lector web; la búsqueda pública sí recuperó la FAQ oficial. No se sustituyó el texto local por una fuente secundaria.

Para distribuir este artefacto, faltan concretar y comprobar al menos:

- **Fuente correspondiente del trabajo cubierto**: código necesario para generar, instalar, ejecutar y modificar el binario; incluye cambios reales, interfaces y scripts de control (§1). Un enlace al README o solo al código original de eSpeak no certifica la correspondencia con el AAR combinado. Documentar configuración, patches y cadena de build; herramientas generales/librerías de sistema tienen exclusiones definidas en la licencia, no una exención automática para cualquier dependencia.
- **Forma efectiva de entrega de fuente** (§6). En descarga de APK, §6(d) permite acceso equivalente a la fuente, sin cargo adicional, con instrucciones claras junto al binario y disponibilidad mantenida por quien distribuye, incluso si usa un servidor tercero. Una oferta escrita de tres años pertenece a modalidades específicas; no es reemplazo universal de cualquier distribución online.
- **Avisos y licencia** (§§4–5): conservar avisos pertinentes de copyright, licencia y ausencia de garantía; suministrar copia GPL; señalar modificaciones y fecha donde corresponde. Evaluar los avisos legales adecuados de interfaces interactivas según §5(d). Tener textos escondidos en assets no establece que el usuario tenga la forma requerida de consultarlos.
- **Derechos de los destinatarios** (§10): preservar facultades de copiar, modificar y redistribuir el trabajo cubierto; términos de distribución, EULA o tienda no deben imponer restricciones incompatibles. Cobrar por una copia no elimina esos derechos.
- **Alcance del trabajo combinado** (§5): el enlace estático eSpeak/Piper/core dentro del mismo `.so` requiere evaluar el trabajo cubierto completo y compatibilidad de todas sus partes. También debe evaluarse la integración JNI con la app. No se presupone que baste relicenciar únicamente un archivo ni se elige aquí la licencia de toda YEZVI. Falta inventario íntegro y revisión de la forma concreta de distribución.
- **Información de instalación, cuando se cumpla el supuesto de §6**: métodos/procedimientos necesarios para instalar y ejecutar versiones modificadas en un User Product. No se afirma que compartir por separado cualquier APK active automáticamente este requisito; se debe examinar si la entrega forma parte de la transferencia de posesión/uso del producto y las demás condiciones de esa sección.

Que STT no llame a TTS no retira del APK los bytes y funciones observados. Tampoco MIT de Piper ni Apache de sherpa eliminan las condiciones de eSpeak/UCD al combinarlos. Esta lectura delimita tareas de cumplimiento; **no acredita que la aplicación esté incumpliendo ni que ya cumpla**.

## Handoff y límites

Mantener el runtime solicitado para el prototipo local y resolver una decisión distributiva antes de publicar. Las opciones revisables son preparar una entrega que satisfaga las condiciones aplicables del artefacto actual, o evaluar un artefacto distinto ASR sin TTS. Si se autorizara ese build futuro con `SHERPA_ONNX_ENABLE_TTS=OFF`, habría que verificar fuentes/dependencias, todos los ELF/ABIs/hashes y carga ASR; no basta cambiar una flag en un documento o asumir que se obtuvo un artefacto permisivo.

No hay SBOM completo del APK, inventario exacto de NDK/libc++ ni fuente correspondiente reconstruida. No se obtuvieron archivos fuente completos, toolchains o maps de enlace; no se descargaron modelos, AAR o licencias de nuevo. No se hicieron tests/Gradle ni validación visual, innecesarios para esta inspección documental; sus resultados de etapa 1 no se convierten en resultados nuevos. Único archivo escrito por esta auditoría: este documento. Código, Gradle, assets, contratos, Room y `docs/ESTADO.md` quedaron fuera de la edición.
