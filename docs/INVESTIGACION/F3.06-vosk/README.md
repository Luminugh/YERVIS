# Vosk: inventario de licencias y auditoría nativa

Consulta: 2026-10-13. Este inventario cubre la alternativa STT Vosk; no sustituye el inventario de todas las dependencias del APK final. No se ejecutó ningún binario descargado durante esta auditoría. Se inspeccionaron las licencias, manifests, headers ELF y metadatos publicados.

## Recursos fijados

| Recurso | Procedencia oficial | Bytes descargados | SHA-256 local |
|---|---|---:|---|
| Vosk Android 0.3.75 AAR | [Maven Central](https://repo.maven.apache.org/maven2/com/alphacephei/vosk-android/0.3.75/vosk-android-0.3.75.aar) | 13.472.638 | `ab2f8b91ac8051561aa325546b35fed9a68b36b8121bac5c6fb927525c4adfad` |
| JNA 5.18.1 AAR | [Maven Central](https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.18.1/jna-5.18.1.aar) | 522.677 | `7f053e3ec99e14dd71259c82c1c8a02738d64a13c31226b2acc170f3060951e0` |
| Modelo pequeño español 0.42 | [Alpha Cephei](https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip) | 39.817.833 | `09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f` |

Los recursos se encuentran en `.gradle/yezvi-stt-research/vosk/`. El modelo ocupa 60.286.598 bytes descomprimido. El [SHA-256 oficial del AAR](https://repo.maven.apache.org/maven2/com/alphacephei/vosk-android/0.3.75/vosk-android-0.3.75.aar.sha256) coincide. JNA coincide con el [SHA-1 oficial](https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.18.1/jna-5.18.1.aar.sha1), `8e93d068c4eab70ada4c79e010198a112aa3c581`; su `.sha256` devolvió 404. No se encontró checksum o firma publicado en las URLs `.zip.sha256`/`.zip.sha1` del modelo: el hash local fija las siguientes compilaciones, pero no constituye firma del autor.

[Maven metadata](https://repo.maven.apache.org/maven2/com/alphacephei/vosk-android/maven-metadata.xml) marca 0.3.75 como latest y release, publicado el 8 de diciembre de 2025. El commit [625e44c62607a3b48fec6d72a30118448909e83f](https://github.com/alphacep/vosk-api/commit/625e44c62607a3b48fec6d72a30118448909e83f) actualiza la versión Android y JNA. No se debe confundir esta versión Maven con el último tag genérico Vosk `v0.3.50`.

## Licencias y avisos que deben acompañar el APK Vosk

`licenses/` conserva avisos originales y `license-manifest.json` registra URL, revisión y hash de cada aviso. La aplicación puede escoger **Apache-2.0** para JNA porque el autor permite elegir entre Apache-2.0 y LGPL-2.1-or-later. La mera aparición de la palabra LGPL en ese aviso dual no impone automáticamente LGPL si se ejerce la opción Apache.

| Componente | Licencia verificada | Avisos conservados |
|---|---|---|
| Vosk API | Apache-2.0 | `VOSK-APACHE-2.0.txt` |
| Pesos y grafo small-es-0.42 | Apache-2.0 según catálogo oficial | Apache-2.0 + `VOSK-SMALL-ES-0.42-README.txt`; evidencia de la fila en `model-license-evidence.json` |
| Kaldi fork | Apache-2.0 | `KALDI-COPYING.txt`, incluidos autores y material de terceros |
| OpenFst fork | Apache-2.0 | `OPENFST-COPYING.txt` |
| OpenBLAS v0.3.20 | BSD-3-Clause | `OPENBLAS-LICENSE.txt` |
| CLAPACK v3.2.1 | BSD-3-Clause | `CLAPACK-COPYING.txt` |
| libf2c | Permiso de AT&T/Lucent/Bellcore | `F2C-NOTICE.txt` |
| JNA 5.18.1 | Apache-2.0 elegida de licencia dual | `JNA-LICENSE-CHOICE.txt` + `JNA-APACHE-2.0.txt` |
| libffi de JNA | MIT | `LIBFFI-LICENSE.txt` |
| SimpleJSON incorporado en `src/json.h` | WTFPL, versión no indicada en header original | `SIMPLEJSON-LICENSE-HEADER.txt` |
| Runtime LLVM/libc++ y compiler-rt | Apache-2.0 con LLVM-exception y avisos legacy de los archivos originales | `LIBCXX-LICENSE.txt`, `COMPILER-RT-LICENSE.txt`, evidencia conservadora; revisión exacta del toolchain binario todavía no identificada |

Los avisos BSD/MIT/f2c deben acompañar los binarios en documentación o materiales de distribución. Apache-2.0 exige entregar su texto, conservar los avisos aplicables y señalar modificaciones de archivos de terceros; si existe NOTICE aplicable, conservar su contenido. No afirmar patrocinio de Alpha Cephei, Google, NVIDIA u otros titulares. El copyright del README del modelo se conserva exactamente como lo entrega el archivo, `Copyright 2022-2050 AC Technologies LLC`.

Para el APK: copiar **todos los archivos de `licenses/`** y un índice de atribuciones legible a assets de la variante Vosk, accesibles en la documentación que acompañe la distribución. Conservar las licencias aun si se excluyen metadatos duplicados de Gradle. La licencia del modelo está documentada en el catálogo; el ZIP no incluye el texto Apache, por eso el paquete debe añadirlo explícitamente. No son necesarias obligaciones GPL por elegir esta cadena Vosk documentada; cualquier cambio futuro de binarios, modelo o dependencias requiere otra auditoría.

Límite de procedencia: el [script de compilación de Vosk](https://github.com/alphacep/vosk-api/blob/625e44c62607a3b48fec6d72a30118448909e83f/android/lib/build-vosk.sh) fija OpenBLAS y CLAPACK, pero clona los forks Kaldi/OpenFst por branch sin SHA. Los snapshots fijados en nuestro manifiesto son evidencia de sus licencias; no prueban por sí solos reproducción bit a bit del AAR. Para una publicación final, guardar fuente/toolchain exactos de esos componentes o construir el runtime desde revisiones fijadas. Los avisos LLVM elegidos son conservadores y no identifican la revisión precisa usada por el AAR.

## Auditoría nativa y privacidad

`native-audit.json` contiene tamaños/hashes por `.so`, PT_LOAD, GNU_RELRO y DT_NEEDED. Vosk AAR contiene arm64-v8a, armeabi-v7a, x86 y x86_64; JNA añade ABIs antiguos que conviene filtrar a los admitidos por la app. El manifest Vosk declara `minSdk=21`; es compatible por ese requisito con `minSdk=24` de YEZVI. Vosk y JNA no declaran permisos de Internet, micrófono ni otros permisos. La carga real y la ejecución en API 24, 35, 36 y 37 son verificaciones distintas del minSdk declarado.

Los `.so` de Vosk en ARM64 y x86_64 requieren solo `liblog.so`, `libm.so`, `libdl.so` y `libc.so` como bibliotecas dinámicas. No se observaron strings GPL/LGPL/eSpeak en esos dos binarios. Esto no constituye prueba completa de ausencia de código estático: la fuente, las licencias y el build recipe son la evidencia principal. No se detectó un componente GPL en la cadena oficial Vosk inspeccionada.

### Hallazgo técnico: RELRO en dispositivos de páginas de 16 KB

| Binario 64 bits | PT_LOAD | `(GNU_RELRO.VirtAddr + MemSiz) % 16384` |
|---|---|---:|
| Vosk ARM64 `libvosk.so` | 16.384 bytes, congruente | 12.288 |
| Vosk x86_64 `libvosk.so` | 16.384 bytes, congruente | 8.192 |
| JNA ARM64 `libjnidispatch.so` | 16.384 bytes, congruente | 0 |
| JNA x86_64 `libjnidispatch.so` | 16.384 bytes, congruente | 0 |

La [documentación oficial de Android](https://developer.android.com/guide/practices/page-sizes#check-relro) indica que un final de RELRO no alineado a 16 KB puede causar SIGSEGV al cargar la biblioteca y requiere reconstrucción. Por ello, PT_LOAD de 16 KB o un `zipalign` exitoso **no bastan** para declarar este Vosk 0.3.75 apto para dispositivos de 16 KB. El AVD existente de 4 KB permite verificar el prototipo; antes de publicar hay que comprobar página real con `adb shell getconf PAGE_SIZE`, rehacer el runtime con flags adecuados/revisiones fijadas o conseguir artefacto corregido, y probar en un entorno de 16 KB.

El [issue oficial #1989](https://github.com/alphacep/vosk-api/issues/1989) describe un problema anterior en JNA 5.13 de Vosk 0.3.70; JNA 5.18.1 incorpora los arreglos 16 KB de 5.16/5.17 según su [CHANGES](https://github.com/java-native-access/jna/blob/5.18.1/CHANGES.md). No se encontró una versión posterior de Vosk Android publicada en Maven que resolviera nuestro hallazgo RELRO. No atribuir este hallazgo al crash histórico de Sherpa: son runtimes distintos y no se ha reproducido aquel cierre.

Este gate técnico de publicación no elimina el permiso de redistribución concedido por las licencias; sí impide afirmar compatibilidad Store/16 KB o distribuir como versión final validada todavía. El APK Vosk final debe excluir los binarios y assets Sherpa pendientes de GPL, además de verificar los avisos y hashes de todo lo efectivamente empaquetado.

## Comparación útil y límites

Vosk pequeño usa un reconocedor neuronal Kaldi con transcripción incremental nativa, endpoints y resultados parciales. La [API oficial](https://github.com/alphacep/vosk-api/blob/625e44c62607a3b48fec6d72a30118448909e83f/java/lib/src/main/java/org/vosk/Recognizer.java) acepta PCM16 mono, exige sample rate coincidente y `getFinalResult()` vacía el último fragmento. No necesita ONNX Runtime ni Silero para su endpoint básico. La velocidad relativa debe medirse con el mismo PCM, mismo dispositivo y parámetros, separando carga del modelo e inferencia; no se infiere a partir de sus 39 MB.

El catálogo publica WER 16,02 % en CV, 16,72 % en mtedx y 11,21 % en MLS para small-es-0.42; el README además publica 42,63 % en su prueba telefónica. Estos son resultados del proveedor en otros corpora y no son comparables directamente con nuestro WAV ni con el WER 24,4 % de FastConformer en YEZVI. La RAM aproximada de 300 MB que menciona el catálogo es genérica para modelos pequeños, no una medición de esta app/S24 Ultra.

Vosk emplea vocabulario fonético cerrado: una gramática en runtime permite seleccionar/reponderar palabras existentes, pero [no introduce palabras nuevas fuera del diccionario](https://alphacephei.com/vosk/adaptation). No prometer soporte de YEZVI, Kotlin, Compose o nombres inventados si el modelo no los tiene, ni reemplazar artificialmente la salida. El mecanismo BPE de FastConformer y los hotwords Vosk no son equivalentes. Se recomienda Vosk como alternativa mínima y medible con licencias permisivas; la selección principal final depende del A/B real y de resolver el gate técnico 16 KB.
