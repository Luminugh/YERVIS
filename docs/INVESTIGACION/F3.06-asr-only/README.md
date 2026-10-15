# F3.06 — Fuentes y avisos del runtime ASR/VAD sin TTS

**Fecha de inspección:** 2026-10-14. **Alcance:** árbol sherpa 1.13.8 fijado, configuración x86_64 y dependencias efectivamente resueltas por CMake; preparación de avisos para el rebuild autorizado. Esta auditoría documental no compila ni descarga, no mide inferencia, no abre micrófono ni aprueba publicación. Los resultados de la integración se registran en [F3.06-runtime-asr-only.md](../../IMPLEMENTACIONES/F3.06-runtime-asr-only.md).

## Artefactos de este expediente

- [sources.json](sources.json): 8 dependencias fuente con URL/versión/SHA-256 verificado sobre los archives locales, ORT dinámico, toolchain, 12 archivos fuente críticos con hash, configuración observada, exclusiones y límites.
- [licenses-manifest.json](licenses-manifest.json): paquete de **27 avisos**, con `notices[{file,sha256,bytes,...}]`. `file` es basename para poder reutilizarlo en `assets/stt/licenses/`; copiar avisos y manifiesto es tarea de integración, no realizada por esta auditoría. El aviso 27 incorpora la revisión posterior de headers Eigen usados; `sources.json` conserva su snapshot previo de 26 textos.
- [licenses/](licenses/): textos originales conservados, incluidos Eigen, OpenFst julio, NDK y un suplemento de copyrights. No contiene audio, referencias ni transcripciones privadas.

`distributionAuditComplete=false` permanece explícito en ambos JSON. Son inventarios de evidencia concreta, no un SBOM exhaustivo del APK/AAB firmado ni un certificado jurídico.

## 1. Fuente y configuración examinadas

Árbol local: `.gradle/yezvi-stt-research/asr-only/source/sherpa-onnx`; revisión fijada **`11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`**, versión 1.13.8. Se identifican individualmente los CMake relevantes; no se afirma haber verificado un archive completo original de sherpa ni una reconstrucción bit a bit de todo el árbol.

El `CMakeCache.txt` de `build/x86_64/` inspeccionado declara:

```text
SHERPA_ONNX_ENABLE_TTS=OFF
SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF
SHERPA_ONNX_ENABLE_JNI=ON
SHERPA_ONNX_ENABLE_C_API=OFF
SHERPA_ONNX_ENABLE_BINARY=OFF
SHERPA_ONNX_ENABLE_PORTAUDIO=OFF
SHERPA_ONNX_ENABLE_WEBSOCKET=OFF
BUILD_SHARED_LIBS=OFF
ANDROID_STL=c++_static
```

Se fijan flags `max-page-size=16384`, `common-page-size=16384` y mapa de enlace `sherpa-jni.map`. Esto describe configuración observada, no demuestra todavía ejecución 16 KB ni ausencia de código en cada binario final.

**ORT sigue siendo dinámico:** el build puede enlazar sherpa/core estáticos y consumir el `libonnxruntime.so` externo proporcionado por las rutas `SHERPA_ONNXRUNTIME`. `BUILD_SHARED_LIBS=OFF` no convierte ese prebuilt en un runtime estático. Deben empaquetarse únicamente las bibliotecas resueltas y evitar otra dependencia ONNX Runtime duplicada.

El nombre de trabajo «ASR-only» significa aquí **TTS y diarización desactivados**, con ASR/VAD retenidos. El core de esta revisión conserva otros helpers/capacidades de audio, ASR y embeddings; no es una poda que compile exclusivamente FastConformer. No se modifica el modelo o contrato STT por esta auditoría.

## 2. Dependencias y selección efectiva

| Dependencia efectiva | Pin | SHA-256 del archive local, cotejado con `URL_HASH` | Licencia identificada |
| --- | --- | --- | --- |
| kaldi-native-fbank | v1.22.3 | `9176cc66fc7ce1edf85cf355b06e320c57db6297df74277f575183468893cf61` | Apache-2.0 |
| KissFFT | `febd4caeed32e33ad8b2e0bb5ea77542c40f18ec` | `497103e664168ebe39580b757adbe616f6cf85a16572af581ca7bc42d0ab13fd` | BSD-3-Clause |
| kaldi-decoder | v0.3.0 | `b9f34cfb4fd3b1344100eead79ef4d37aa15962274b9e3056de345021f76a1b0` | Apache-2.0 |
| kaldifst | v1.8.0 | `3f247b7e5a2409071202f5e2bc6200060f66728c0a3443c03923ad2723e040b3` | Apache-2.0 |
| OpenFst fork | **v1.8.5-2026-07-09** | `2ff712a32952fcb01d351121a6bc8ccf4fdc6b2aa06ce8df2b3095dedd518c0e` | Apache-2.0 |
| simple-sentencepiece | v0.7 | `1748a822060a35baa9f6609f84efc8eb54dc0e74b9ece3d82367b7119fdc75af` | Apache-2.0 y BSD-2-Clause de `darts.h` |
| nlohmann/json | v3.12.0 | `4b92eb0c06d10683f7447ce9406cb97cd4b453be18d7279320f7b2f025c10187` | MIT; avisos propios/terceros |
| Eigen | **5.0.1** | `e9c326dc8c05cd1e044c71f30f1b2e34a6161a3b6ecf445d56b53ff1669e3dec` | **MPL-2.0**, con otras licencias compatibles por archivo |

Los archives locales fueron leídos y sus hashes recalculados, no descargados otra vez. Las URLs originales, bytes y recetas exactas están en [sources.json](sources.json). Un hash fija identidad; no es una firma del editor.

**OpenFst julio es deliberado:** al resolver `include(openfst)` prevalece el módulo de `source/sherpa-onnx/cmake/openfst.cmake`. El módulo transitorio de kaldifst menciona abril de 2026, pero no es el elegido por esta configuración. Por ello se conserva el COPYING del **source efectivo de julio**; el aviso anterior de abril no acredita este rebuild.

Eigen se resuelve mediante `cmake/eigen.cmake`. Incluso sin diarización, `core/math.cc` y `math.h` incluyen `Eigen/Dense`: no se puede eliminar su MPL del inventario porque `fast-clustering` esté desactivado. El CMake Android aplica además el patch upstream para retirar threading de simple-sentencepiece; debe conservarse esa receta dentro de la fuente/build correspondiente, distinguiéndola de cambios propios posteriores.

### ONNX Runtime

Paquete dinámico 1.28.2 publicado por `csukuangfj/onnxruntime-libs`: **33.765.715 B**, SHA-256 **`01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d`**; [URL fijada](https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-1.28.2.zip). Hash local cotejado con el valor fijado en CMake.

No se encontraron entradas de nombre LICENSE/NOTICE/third-party dentro del ZIP inspeccionado; se reutilizan el MIT upstream y `ThirdPartyNotices` 1.28.2 ya conservados. Ese documento agregado es un superconjunto y no prueba qué dependencias quedaron en cada `.so`. ORT **no se recompiló desde fuente por esta tarea**; su procedencia completa de fuente/configuración/toolchain requiere revisión independiente del rebuild propio de sherpa.

## 3. Evidencia de exclusión de TTS y diarización

| Punto de la revisión sherpa fijada | Con OFF |
| --- | --- |
| `CMakeLists.txt:569–574` | No ejecuta `include(espeak-ng-for-piper)` ni `include(piper-phonemize)`. |
| `csrc/CMakeLists.txt:287–321` y `:479–482` | No añade las fuentes TTS ni enlaza `piper_phonemize` al core. |
| `jni/CMakeLists.txt:37–41` | No compila `offline-tts.cc` JNI. |
| `CMakeLists.txt:576–578` | No incluye hclust-cpp cuando diarización está OFF. |
| `csrc/CMakeLists.txt:335–346` y `jni/CMakeLists.txt:43–47` | No añade fuentes de diarización ni su binding JNI. |

En el AAR completo anterior, Piper conduce al fork eSpeak NG y UCD-tools GPL-3.0-or-later. La nueva configuración evita esa ruta de resolución/enlace. hclust es BSD-2-Clause; su exclusión reduce código no utilizado, no es el motivo de retirar la GPL de TTS.

Falta cotejar **por ABI entregada** el mapa de enlace/objetos, ELF, exports JNI y APK/AAB efectivo. Una bandera OFF o un `strings` negativo no bastan para certificar ausencia. No trasladar automáticamente los bytes/evidencia GPL del AAR antiguo al nuevo; tampoco declararlo libre de cualquier componente copyleft sin revisar dependencias como Eigen.

## 4. Paquete de avisos y obligaciones que conserva

Los 27 textos en `licenses/` cubren:

- Apache sherpa, kaldi-native-fbank, kaldi-decoder, kaldifst y simple-sentencepiece; COPYING actual OpenFst julio; MIT JSON y sus avisos de terceros/Hedley; BSD KissFFT y `darts.h`.
- MIT ONNX Runtime y `ThirdPartyNotices` upstream completo.
- Eigen 5.0.1: `LICENSE`, `COPYING.README`, `COPYING.MPL2`, `COPYING.APACHE`, `COPYING.BSD`, `COPYING.MINPACK`. Conservar el conjunto evita perder avisos compatibles de terceros; no afirma que cada archivo de Eigen se use en el binario.
- `NOTICE` y `NOTICE.toolchain` **reales del NDK r28c instalado**, sin sustituirlos por avisos de una revisión LLVM aproximada. Son superconjuntos conservadores; no se deduce que cada herramienta/licencia que mencionan forme parte del APK.
- Un suplemento con comentarios iniciales de copyright/licencia de **263** unidades de traducción configuradas y headers seleccionados (Eigen, darts, Hedley). Es evidencia de autores/avisos, no lista exacta de objetos enlazados ni de todos los headers alcanzables.
- Silero MIT, la ficha fijada del modelo español NVIDIA y CC-BY-4.0 íntegra, reutilizados y cotejados con los hashes del inventario previo. Estos tres textos corresponden a **pesos/VAD/vocabularios asociados**, no prueban que los pesos estén dentro del AAR ni asignan CC-BY al código del runtime.
- El suplemento posterior `eigen-used-header-notices.txt`: bloques completos sin truncar de copyright/licencia de los 204 headers Eigen registrados realmente en depfiles x86_64, conservando autores y condiciones específicas de TensorFlow, Intel, Fabian Giesen, Willow Garage y Open Source Robotics Foundation. No se atribuye una única licencia a todos esos archivos.

El archivo sherpa LICENSE del árbol materializado usa CRLF y mide 11.560 B; el texto previo fijado LF mide 11.358 B. Se comprobó que son iguales al retirar CR. El manifiesto registra el hash real del texto copiado, sin presentarlo como SHA del recurso LF original.

No se copiaron avisos eSpeak/Piper/UCD/hclust al paquete nuevo como si esas dependencias estuvieran confirmadas en él. La evidencia del AAR antiguo y sus condiciones permanece en [la auditoría previa](../F3.06-stt-local/etapa2-gpl-auditoria.md). Conservar los textos de pesos NVIDIA **CC-BY-4.0**, tokens/BPE derivados y Silero **MIT** no sustituye incluir atribución legible, origen e indicación de extracción RNNT/exportación/cuantización/BPE que acompañe los pesos realmente distribuidos.

### Eigen MPL-2.0

Al distribuir un ejecutable con código cubierto, MPL exige hacer disponible su fuente y explicar cómo obtenerla, conservando derechos y avisos; permite un trabajo mayor con otros términos si se cumplen las condiciones del código cubierto. Conservar **fuentes/headers Eigen 5.0.1 exactos**, cambios si los hubiera y el texto MPL; ofrecer acceso durable junto al binario. No basta etiquetarlo «Apache/MIT». [MPL oficial, §§3.1–3.4](https://www.mozilla.org/en-US/MPL/2.0/).

La receta propone este acceso de fuente: [archive Eigen 5.0.1](https://gitlab.com/libeigen/eigen/-/archive/5.0.1/eigen-5.0.1.tar.gz), con el SHA de la tabla. La copia permanece en caché privada y el enlace upstream existe; **no se publicó un bundle de fuente**. Antes de distribuir hay que comprobar disponibilidad efectiva para receptores, instrucciones y preservación de cambios/avisos. Esto no elige la licencia de todo YEZVI. Revisar los depfiles reales para limitar con precisión el alcance de cada licencia por header.

### LLVM y runtime del NDK

El build observado usa `c++_static`; parte de libc++/libc++abi/compiler-rt puede incorporarse al ELF aunque no aparezca en `DT_NEEDED`. Conservar avisos/excepciones LLVM y legacy aplicables del NDK exacto, y relacionarlos con los objetos enlazados. No clasificar todas las dependencias como Apache sin excepciones ni asumir que solo importan las bibliotecas compartidas.

## 5. Toolchain fijado y compatibilidad 16 KB

- **Android NDK r28c / 28.2.13676358**, ZIP Windows **748.118.221 B**, SHA-256 `6bec98ac2354d8a919760889a1a41d020132e5e8cfa1b1fe51610a72c36a466b`; [distribución oficial](https://dl.google.com/android/repository/android-ndk-r28c-windows.zip). Descarga grande previamente autorizada, no realizada por esta auditoría.
- **Clang 19.0.1**, Android `r530567e`; `clang_source_info.md` identifica base LLVM `3b5e7c83a6e226d5bd7ed2e9b67449b64812074c` y patches Android `e727bfb014bd436f581a66a450c939a6983a1fc3`. Se registra el hash de ese archivo y de `source.properties`.
- **CMake Android SDK 3.22.1**, ZIP **16.116.742 B**, SHA-256 `c9a9d568452a20cf27d703cb21ef9529dd67bda6be048c4d4b884acb3ac3a2b8`; [distribución oficial](https://dl.google.com/android/repository/cmake-3.22.1-windows.zip). Herramienta de build, no runtime que se entregue dentro del APK.

Alineación y compatibilidad deben comprobarse para **todos los `.so` entregados**: sherpa reconstruido, ORT prebuilt y dependencias previas de la app. En particular, el [inventario AndroidX graphics-path](../F3.06-vosk/app-native-audit.json) conserva hallazgos GNU_RELRO pendientes en bibliotecas preexistentes ajenas al STT. Resolver sherpa no demuestra que todo YEZVI cumpla 16 KB.

Cerrar el gate requiere PT_LOAD/RELRO apropiados, `zipalign`/configuración AAB del artefacto final y ejecución real con `getconf PAGE_SIZE=16384`; no confundirlos con pruebas del AVD Pixel 8 que usa 4096 B. Conservar límites API/ABI/Samsung reales. Este expediente no declara ninguna ejecución 16 KB o Samsung.

## 6. Siguiente auditoría antes de publicación

1. Archivar receta completa de rebuild y patches, fuente efectiva, toolchain y mapa de enlace de cada ABI; verificar exclusión Piper/eSpeak/UCD/hclust en fuentes resueltas y binarios entregados.
2. Cotejar source/objetos/header depfiles con todos los avisos y licencias, incluidos Eigen/MPL y excepciones LLVM. Resolver procedencia y aviso de dependencias del ORT prebuilt.
3. Copiar el paquete de avisos y atribuciones de modelos a la variante final, verificar hashes y ofrecer fuente cubierta MPL e instrucciones de acceso a los receptores. No añadir copias de audio privado/test ni resultados de usuario.
4. Auditar APK/AAB **final identificado por hash**: variantes, ausencia del AAR antiguo, dependencias duplicadas, manifest sin INTERNET/cloud y todos los `.so`, incluidos los de UI anteriores.
5. Resolver gate 16 KB de la app completa y ejecutar regresiones/inferencia offline, calidad y latencias sobre el mismo PCM; por separado validar micrófono humano en vivo. No inventar rendimiento por recompilar con NDK nuevo.
6. Revisar términos/canal de distribución y autorización de publicación. Mantener `distributionAuditComplete=false` hasta cerrar la evidencia aplicable.

Referencia de procedimiento: [plan de auditoría y distribución](../../IMPLEMENTACIONES/F3.06-plan-auditoria-distribucion.md). No hay commit/push, cambio Room ni staging de binarios por esta tarea. La autorización de reconstruir no equivale a autorizar publicación; F3.07 no se inicia automáticamente.

## 7. Suplemento: headers Eigen realmente alcanzados en x86_64

Después de la compilación nativa se ejecutó únicamente `ninja -C .../build/x86_64 -t deps`, herramienta de consulta de solo lectura. La base de dependencias produjo **259 registros VALID** y **204 rutas Eigen únicas**; se leyó cada archivo y se conservó su hash, copyright, anotaciones y comentario inicial íntegro en [eigen-used-headers.json](eigen-used-headers.json). Esto mejora el inventario anterior de fuentes configuradas: describe inclusión efectiva por el compilador, sin demostrar que cada plantilla se instancie o sobreviva al linker.

Resultado de lectura: **185** headers con MPL explícita; **17** sin aviso individual, sujetos al aviso general de Eigen `COPYING.README`; **2** con condiciones específicas identificadas: `BFloat16.h` Apache-2.0/TensorFlow y `MKL_support.h` BSD-3-Clause/Intel. No aparecieron rutas `unsupported/` ni menciones GPL/LGPL/AGPL en los 204 archivos examinados. La inclusión de un header llamado MKL no demuestra que la biblioteca Intel MKL se enlace: sus bloques dependen de macros.

Hay avisos adicionales compatibles que deben conservarse: `Half.h` contiene el permiso propio de Fabian Giesen para rutinas de conversión; `AlignedBox.h` tiene BSD de Willow Garage/Open Source Robotics Foundation para una función indicada. Dos archivos de eigenvalues también documentan procedencia JAMA de dominio público sin cambiar por ello la MPL del archivo completo. El `COPYING.BSD` general de Eigen es de Intel y no sustituye automáticamente los otros autores/condiciones. Los originales de los primeros 26 avisos y `sources.json` permanecieron intactos; se autorizó añadir **un aviso 27**, [eigen-used-header-notices.txt](licenses/eigen-used-header-notices.txt), y su SHA/bytes al manifiesto. Conserva completos los bloques de comentarios de licencia/copyright encontrados en los headers usados, además de las referencias y hashes por archivo. Así se preservan conservadoramente esos avisos para que la integración los acompañe al binario, sin afirmar que toda plantilla se haya enlazado. Los requisitos BSD de aviso binario no quedan cumplidos solo por enlazar fuentes.

Esta tarea no cambió headers Eigen. Mantener acceso e instrucciones a la fuente exacta **MPL-2.0 sin modificar**, y a cualquier cambio futuro real, continúa siendo requisito del ejecutable cubierto. Ausencia de GPL en estos headers no certifica todo el cierre nativo: el prebuilt ORT, el runtime LLVM y las bibliotecas previas de la app conservan sus auditorías independientes. El alcance es x86_64, no ARM64 ni validación 16 KB; no se recompiló ni se accedió a audio durante este suplemento.
