# Renovación extraordinaria de UX y laboratorio Cristal

Fecha: 15-10-2026. Trabajo extraordinario de presentación autorizado después del cierre de F4.10. **No es una fase nueva, no completa F6 y no modifica las 123 tareas.**

## Alcance y referencias

Se revisaron AGENTS, ESTADO, PRODUCTO, ARQUITECTURA, UX_UI, WORKFLOW, los informes recientes F3 y F4.07–F4.10, el código Compose y las pruebas existentes. F4.10 consta cerrado técnicamente; su evaluación de un proveedor de IA sigue sin ejecutarse y queda fuera de este trabajo.

La guía adjunta y las láminas se usaron como dirección estética: teal, jerarquía de lectura, iconos coherentes, superficies suaves y acción de captura accesible. Los tres archivos `lamina_01` adjuntos muestran la misma lámina. No se encontraron los 32 PNG individuales mencionados en la guía en su ubicación relativa. Los moodboards contienen agenda, categorías y funciones futuras; no se trasladaron a la aplicación como capacidades reales.

Stack conservado: Kotlin/Compose, BOM 2026.02.01, Material 3 **1.4.0**, navegación existente y cuatro destinos principales. No hay actualización de bibliotecas, fuentes, shaders ni dependencias nuevas. Se emplean componentes Material 3 y tokens propios compatibles; no se fuerza una migración a APIs experimentales de Material Expressive ni a transiciones compartidas sobre textos editables.

## Línea base y problemas observados

Se guardaron fuente UI y APK anteriores en `.gradle/ux-renewal/baseline/`. La compilación debug y de instrumentación anterior al rediseño pasó. La medición utiliza únicamente **Pixel_8 AVD API 35**, serial explícito `emulator-5554`, resolución 1080×2400, densidad 420, y el paquete existente de pruebas `com.yezvi.assistant.persistenceisolated`.

Hallazgos en código:

- Hoy presentaba tareas y recuentos sintéticos en producción; la captura estaba rodeada de contenido de demostración.
- Timeline: construcción repetida de formateadores por tarjeta, recálculo de todos los IDs al seleccionar, falta de `contentType` y cabeceras sticky sin fondo opaco.
- Detalle: Crossfade del estado completo podía componer dos listas al cambiar un `Loaded`; historial entero dentro de un solo ítem lazy; original repetido cuando no había corrección.
- Propuestas: una propuesta completa, con todos sus campos, evidencias y fuentes, se componía como un único ítem grande. Búsquedas de relaciones y fechas se repetían; la información técnica dominaba la lectura.
- Captura: `animateContentSize` sobre el panel completo podía mover el editor con cada parcial; controles inactivos redundantes en reposo; revisión sin desplazamiento; fila de permiso rígida y padding IME repetido.
- Reducción de movimiento: el ajuste Android se leía una sola vez y exigía reiniciar la aplicación.

No se detectó búsqueda/filtros implementados en Memoria. No se añadieron controles ficticios para aparentar esa funcionalidad de fases posteriores.

## Implementación

| Superficie | Resultado |
|---|---|
| Hoy | Recuerdos reales del flujo local existente, tres entradas recientes y recuento real; acceso al detalle y a Memoria; una acción principal de captura cerca del pulgar. Desaparecen tareas, métricas y tarjetas de demostración. |
| Memoria | Iconos de origen, hora memorizada, cabeceras opacas con conteo real por grupo, espaciado adaptable y expansión localizada de texto largo. Conserva selección múltiple y borrado explícito. |
| Detalle/corrección | Texto actual prominente en superficie sólida; original preservado sin duplicación cuando es la versión actual; metadatos agrupados; historial lazy con claves estables. El editor conserva su composición al recibir estado. |
| Captura | Editor sólido y selección conservada; voz compacta con controles por estado real; literal de voz desplegable; parcial de dos líneas sin animar el tamaño del editor; permiso contextual adaptable y revisión desplazable. |
| Revisión de propuestas | Once iconos para los once conceptos del contrato, lista aplanada por cabecera/campo/fuentes/decisión, mapas y fechas recordados. Evidencia y fuentes bajo disclosure. Ediciones y decisiones siguen rotuladas como locales/no guardadas en esta demostración debug. |
| Ajustes | Apariencia real, voz existente, privacidad y acceso debug. Tema, estilo, densidad y movimiento reaccionan inmediatamente; la preferencia se guarda fuera de Room. |
| Navegación | Cuatro iconos con etiqueta, selección accesible y cápsula única que toma la altura de la fila real; admite fuente grande y RTL. Entrada y regreso de detalle/revisión tienen dirección coherente. |

Se reutiliza un `MemoryViewModel` en el host para Hoy y Memoria. Ambas pantallas consumen el mismo StateFlow, con colección ligada al ciclo de vida, sin crear una segunda fuente de verdad ni cambiar consultas o repositorios.

### Sistema visual y cristal

`YervisIcons.kt` contiene vectores nativos de trazo consistente. `YervisSurfaces.kt` concentra paneles y fondo. Las métricas cómoda/compacta controlan márgenes, espaciado y padding; los objetivos táctiles mantienen al menos 48 dp.

El respaldo teal define fondos y superficies neutrales completos para claro/oscuro. Material You continúa habilitado por defecto en Android 12+. Los estados usan roles Material semánticos: error para fallos, contenedores de sugerencia/demostración y estados expresados con texto.

**Cristal experimental** combina fondo tonal estático, transparencia moderada, borde con reflejo gradual y elevación ligera en navegación y paneles destacados. Lectura, editor y evidencia conservan superficies sólidas. No utiliza `Modifier.blur`, backdrop blur, RenderEffect, shaders, imágenes de fondo ni bucles decorativos. Es una interpretación Android reversible, no una reproducción de otra plataforma.

Activación: **Ajustes → Apariencia → Estilo visual → Cristal experimental**. El mismo selector vuelve inmediatamente a **Clásico YERVIS**.

### Preferencias

| Opción | Predeterminado | Efecto |
|---|---|---|
| Tema | Sistema | Sistema, claro u oscuro; iconos de barras del sistema acordes al tema elegido. |
| Colores del dispositivo | Activados | Material You en API 31+; desactivados usan teal. En dispositivos antiguos se indica el respaldo. |
| Estilo | Clásico YERVIS | Cristal limitado a superficies destacadas; reversible sin recrear el editor. |
| Espaciado | Cómodo | Cómodo o compacto, sin reducir el mínimo táctil. |
| Movimiento | Seguir al sistema | Reducido elimina los movimientos propios. Android sin animaciones siempre prevalece. |

Se utiliza un archivo SharedPreferences exclusivo, `yervis_appearance`, siguiendo la infraestructura local ya existente. Carga y escrituras serializadas se realizan en IO; la selección de UI se refleja inmediatamente. Valores de enumeración desconocidos recuperan su opción segura. No se amplía el contrato de preferencias de voz ni se toca Room. No se marca la fase F6 como terminada.

### Movimiento

| Interacción | Tratamiento |
|---|---|
| Pestañas y cápsula | 200 ms, dirección y recorrido breve; desplazamiento de cápsula leído en `graphicsLayer`. |
| Detalle/revisión y regreso | Entrada/salida direccional breve de 200 ms. |
| Texto largo en timeline | `animateContentSize` localizado, 200 ms. |
| Evidencia y fuentes | `AnimatedVisibility`, 180 ms; estado de expansión expresado semánticamente. |
| Estado de voz | Crossfade solo del rótulo de estado, 200 ms; el parcial no anima el tamaño del panel. |
| Botones y selecciones | Feedback nativo Material y semántica de selección; el éxito sigue dependiendo del resultado real. |

Un único observador por tema reacciona a cambios de `ANIMATOR_DURATION_SCALE`; las APIs nativas conservan la escala Android. No se aplica una transición al contenido completo de un editor ni se finge streaming de IA/voz.

## Validación y evidencia

La validación final pasó `assembleDebug`, `testDebugUnitTest` (**417/417 JVM**, cero fallos/errores/omitidas), `assembleRelease` y `verifyReleaseSpeechIsolation`: **BUILD SUCCESSFUL en 2 min 5 s**. La corrida Compose completa pasó **46/46**, en 265,581 s. Después del último ajuste de etiquetas de campos relacionados se recompilaron los APK aislados (**24 s**) y se repitió navegación/propuestas: **5/5**, en 69,983 s. Las siete pruebas nuevas son de UI/preferencias; los tests y el corpus F4.10 permanecen intactos.

| Suite Compose explícita | Resultado |
|---|---:|
| VisualRenewalTest | 5/5 |
| AppearancePersistenceTest | 2/2 |
| YezviAppNavigationTest | 5/5 |
| CaptureVoiceUiTest | 17/17 |
| CaptureDraftFlowTest | 1/1 |
| EntryDetailAndAccessibilityTest | 11/11 |
| EntryDetailEditingTest | 1/1 |
| VoiceSettingsSectionTest | 4/4 |

La primera corrida fue 43/45: dos tests de Ajustes interactuaban con voz sin desplazar el nuevo contenido. Se actualizaron para alcanzar el control real antes del clic o la espera. El test de accesibilidad de Memoria también se corrigió para inspeccionar una tarjeta: montaba una pantalla aislada pero buscaba la barra del host. No se cambiaron expectativas de guardado, cancelación, evidencia o persistencia.

La inspección de capturas detectó texto por defecto negro sobre el fondo oscuro de un Scaffold transparente y contenido detrás del botón de Hoy. Se fijó el color de contenido en el tema y se dio fondo sólido a esa barra. La nueva prueba consulta el color real del `TextLayoutResult`, además de comprobar contraste; el CTA ya no deja leer contenido por debajo. Los campos relacionados y elementos de arrays muestran etiquetas comprensibles, sin exponer sus índices técnicos como nombre de campo; sus punteros y datos siguen intactos.

La revisión incluye navegación y alineación de cápsula a fuente 200%, conservación de texto/foco/selección al cambiar estilo/tema, reversibilidad de opciones, contraste de texto en ambos temas, restauración de preferencias y regresiones existentes de captura, revisión y detalle. Capturas nuevas usan exclusivamente recuerdos sintéticos en memoria; no abren Room.

### Galería de capturas nativas

Los PNG no son renders generados ni prototipos. La matriz nueva utiliza 120 entradas **sintéticas en memoria** para mostrar contenido; el recuento no representa datos del usuario. Se conservan los píxeles originales del AVD.

| Pantalla | Antes (APK anterior) | Clásico claro | Clásico oscuro | Cristal claro | Cristal oscuro |
|---|---|---|---|---|---|
| Hoy | [Antes](UX-evidencia/antes-inicio.png) | [Captura](UX-evidencia/Light-Classic-today.png) | [Captura](UX-evidencia/Dark-Classic-today.png) | [Captura](UX-evidencia/Light-Crystal-today.png) | [Captura](UX-evidencia/Dark-Crystal-today.png) |
| Memoria | [Antes](UX-evidencia/antes-memoria.png) | [Captura](UX-evidencia/Light-Classic-memory.png) | [Captura](UX-evidencia/Dark-Classic-memory.png) | [Captura](UX-evidencia/Light-Crystal-memory.png) | [Captura](UX-evidencia/Dark-Crystal-memory.png) |
| Captura | [Antes](UX-evidencia/antes-captura.png) | [Captura](UX-evidencia/Light-Classic-capture.png) | [Captura](UX-evidencia/Dark-Classic-capture.png) | [Captura](UX-evidencia/Light-Crystal-capture.png) | [Captura](UX-evidencia/Dark-Crystal-capture.png) |
| Ajustes | [Antes](UX-evidencia/antes-ajustes.png) | [Captura](UX-evidencia/Light-Classic-settings.png) | [Captura](UX-evidencia/Dark-Classic-settings.png) | [Captura](UX-evidencia/Light-Crystal-settings.png) | [Captura](UX-evidencia/Dark-Crystal-settings.png) |

Además: [fuente 200%](UX-evidencia/font200-home.png), [detalle](UX-evidencia/detail-synthetic.png) y [editor con selección conservada](UX-evidencia/crystal-dark-editor.png).

La APK aislada también se abrió directamente con su base sintética vacía: [Inicio](UX-evidencia/Inicio.png), [Memoria](UX-evidencia/Recuerdos.png), [Captura](UX-evidencia/Captura.png), [Ajustes](UX-evidencia/Ajustes.png). No aparecen recuentos o pendientes inventados.

Se verificaron el [cristal con colores dinámicos y barras oscuras](UX-evidencia/cristal-nativo-oscuro.png), su [restauración tras reiniciar el proceso](UX-evidencia/cristal-reinicio.png), [Hoy horizontal](UX-evidencia/horizontal-inicio.png), [Captura horizontal](UX-evidencia/horizontal-captura.png), [revisión de propuestas](UX-evidencia/propuestas-resumen.png) y [evidencia desplegada](UX-evidencia/propuestas-evidencia.png). Se restauraron modo clásico, tema sistema y orientación libre del AVD. En horizontal, Captura conserva las acciones fijas y requiere desplazar el contenido; no se certifica aquí la escritura con IME horizontal o todas las combinaciones de pantalla dividida.

### Medición local exploratoria

`scripts/Invoke-UxVisualCheck.ps1` verifica AVD/API, identidad aislada del APK, hash instalado y paquete personal antes/después. Espera el encabezado de cada pantalla antes de capturar. Una primera corrida con espera fija mostró una captura todavía en transición; se conservó como diagnóstico y se descartó para la comparación. La línea base válida se repitió con verificación de ruta.

| Medida | Antes válido | Después |
|---|---:|---:|
| 3 arranques de proceso (`am start -W`, ms) | 5716 / 5828 / 5351 | 5474 / 4595 / 5597 |
| Frames renderizados / lentos (`gfxinfo`) | 549 / 147 | 186 / 176 |
| Percentiles de frame p50 / p90 / p95 | 27 / 40 / 57 ms | 113 / 250 / 400 ms |

Son muestras **debug en emulador**, con 12 cambios de pestaña verificados. Hoy cambia de contenido sintético a contenido local real, por lo que el recorrido visual no es idéntico. JIT, carga del host y renderer del AVD afectan las cifras. No es un Macrobenchmark ni demuestra una mejora porcentual o el rendimiento del Samsung. Los percentiles GPU extremos del emulador no se interpretan como métricas fiables. No se midió consumo bajo inferencia STT real ni se promete una tasa de frames concreta.

Se reporta el `TotalTime` de `am start -W`, sin presentarlo como tiempo para la UI completamente lista. La automatización visual espera por separado el contenido esperado y la finalización de la transición; una captura inicial demasiado temprana se repitió antes de aceptarla como evidencia.

El resultado de frames empeoró y se investigó antes de cerrar. En ese momento el host tenía aproximadamente **0,8 GB libres de 16 GB**, y el AVD utilizaba swap. La app aislada en reposo registró 0% CPU y unos 93.369 KiB PSS en una observación; no se observó un bucle continuo de UI. Esto es una condición de la sesión, no una explicación causal demostrada.

Para distinguir una regresión del código del estado del entorno se reinstalaron **ambas APK** bajo las mismas condiciones actuales. Cada una recorrió las cuatro pestañas para calentarlas y luego 12 cambios con espera de 1.200 ms, sin dumps de accesibilidad ni screenshots dentro de la ventana medida:

| Medición posterior comparable en método | APK anterior | APK renovada |
|---|---:|---:|
| Frames renderizados / lentos | 171 / 158 | 173 / 159 |
| p50 / p90 / p95 | 97 / 150 / 250 ms | 97 / 200 / 250 ms |
| Mediana desde HandleInputStart a SyncQueued | 6,92 ms | 6,31 ms |
| Registros válidos de esa última mediana | 80 | 75 |

La mediana de trabajo UI se calculó solo para `Flags=0` en el buffer final de framestats (máximo 120 registros), no para todos los frames de la corrida. La APK anterior reproduce la lentitud grave en este mismo entorno. **No hay evidencia suficiente para atribuir ese empeoramiento al rediseño ni para certificar una ganancia global de fluidez.** Se conservan todos los resultados, incluidos los desfavorables, en `.gradle/ux-renewal/`. Las optimizaciones descritas son cambios concretos de composición/layout; su ganancia bajo STT real necesita una medición posterior en un entorno estable.

### Reproducción segura

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat assembleDebug testDebugUnitTest assembleRelease verifyReleaseSpeechIsolation --offline --no-daemon
.\gradlew.bat assembleDebug assembleDebugAndroidTest -PisolatedPersistenceTests=true --offline --no-daemon
# Copiar ambos APK aislados a .gradle/ux-renewal/isolated-app.apk y isolated-tests.apk.
.\scripts\Invoke-UxComposeTests.ps1
.\scripts\Invoke-UxVisualCheck.ps1 -Phase after -AppApk .gradle/ux-renewal/isolated-app.apk
.\scripts\Invoke-UxVisualCheck.ps1 -Phase before-warm -AppApk .gradle/ux-renewal/baseline/app.apk -WarmNavigation
.\scripts\Invoke-UxVisualCheck.ps1 -Phase after-warm -AppApk .gradle/ux-renewal/isolated-app.apk -WarmNavigation
```

No usar `connectedDebugAndroidTest` con selección automática: hay Samsung y Pixel conectados. Los scripts rechazan un serial físico y un APK personal. No se ejecuta la suite completa del dispositivo ni pruebas de motores de voz.

## Integridad, límites y siguiente validación

Se registraron hashes de 163 archivos existentes de data/domain/presentation, schemas, contratos y corpus/tests F4.10. La comprobación final dio **cero modificaciones** y el hash del índice Git no cambió. `git diff --check` terminó sin errores de whitespace; Git solo avisó de conversiones LF/CRLF configuradas. No hay commit/push ni cambio de staging. La identidad normal sigue siendo `com.yezvi.assistant`; se reutiliza el sufijo aislado de pruebas ya existente, sin cambiar Gradle.

Ninguna APK nueva se instala sobre el paquete personal. No se abre su base, no se ejecuta `pm clear`, uninstall o reset, ni se cambia conectividad del Samsung. No hay modificación de STT/TTS/JNI/modelos, contratos IA, rawText, evidencias o IDs persistentes. No hay red/cloud nuevos ni envío de código o datos privados a documentación externa.

Queda pendiente comprobar en el Samsung S21 FE real el comportamiento bajo inferencia local intensiva y TalkBack audible. Requiere un entorno de datos sintéticos o la autorización de despliegue personal Room v4 pendiente. La navegación, preferencias y estados se validan primero con fixtures aislados. No se implementan agenda, recordatorios, permisos futuros, sincronización ni F4.11.

## Documentación pública consultada

- [Rendimiento de Compose](https://developer.android.com/develop/ui/compose/performance/bestpractices): recordar cálculos, claves estables y lectura diferida de estado.
- [Listas Compose](https://developer.android.com/develop/ui/compose/lists): reutilización por `contentType` e ítems lazy pequeños.
- [Material 3](https://developer.android.com/develop/ui/compose/designsystems/material3): roles semánticos y componentes compatibles.
- [Accesibilidad Compose](https://developer.android.com/develop/ui/compose/accessibility): semántica, texto escalable y objetivos táctiles.
- [Elementos compartidos](https://developer.android.com/develop/ui/compose/animation/shared-elements): alcance y costes de overlays; no se incorporan sin un caso estable que lo justifique.
- [Movimiento One UI](https://developer.samsung.com/one-ui/motion/intro.html): continuidad y respuesta funcional.

Las consultas de documentación pública/Context7 usaron preguntas genéricas. No se enviaron código, notas, audio, bases ni capturas privadas.

## Archivos de esta intervención

- Host: `MainActivity.kt`, `ui/navigation/YezviApp.kt`, `ui/navigation/YezviBottomBar.kt`.
- Sistema visual: nuevos `ui/theme/Appearance.kt`, `ui/components/YervisIcons.kt`, `ui/components/YervisSurfaces.kt`; ajustes en `ui/theme/Theme.kt` y `ui/theme/MotionSupport.kt`.
- Pantallas: `ui/today/TodayScreen.kt`, `MemoryScreen.kt`, `EntryDetailScreen.kt`, `CaptureScreen.kt`, `VoiceRecordingPanel.kt`, `ProposalReviewScreen.kt`, `PlaceholderScreens.kt`; nuevos `AppearanceSettingsSection.kt` y `PrivacySettingsSummary.kt`; `SettingsScreenContent.kt` de debug/release.
- Cadenas nuevas: `strings_ux.xml`, `strings_ux_memory.xml`, `strings_ux_capture.xml`, `strings_ux_proposals.xml`.
- Pruebas: nuevos `VisualRenewalTest.kt` y `AppearancePersistenceTest.kt`; adaptación de cuatro archivos de pruebas UI existentes para el nuevo desplazamiento y disclosure.
- Reproducción: `scripts/Invoke-UxVisualCheck.ps1`, `scripts/Invoke-UxComposeTests.ps1`.
- Documentación: este informe, galería `UX-evidencia/`, `docs/UX_UI.md` y `docs/ESTADO.md`. Los restantes cambios que ya estaban en el workspace pertenecen a trabajos anteriores y se conservaron.
