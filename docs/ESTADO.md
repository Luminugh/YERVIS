# Estado vivo del proyecto

**Última actualización documental:** 2026-10-12 (preparado como guía; no reemplaza las verificaciones locales).

## Verificado en documentación y sesiones
- [x] YEZVI creado en `C:\Users\Rick\Documents\GitHub\YEZVI`; paquete `com.yezvi.assistant`.
- [x] Gradle Wrapper ejecutó `assembleDebug` con `BUILD SUCCESSFUL`.
- [x] Emulador Pixel 8, API 35 iniciado y `adb devices` detectó `emulator-5554`.
- [x] Aplicación instalada y abierta en el emulador durante la validación de F1.07; el uso del botón Run de Android Studio no consta.
- [x] Consulta real a Context7 desde Codex en esta conversación; la lista MCP de CLI no se ejecutó.

## Aún pendiente de comprobar
- [ ] `opencode mcp list`: Context7 conectado e invocado.
- [x] `git status` verificado: rama `main` con upstream `origin/main`; cambios F1.03 visibles.
- [ ] Checkpoint Git de F1.03: pendiente; no se creó commit.
- [ ] Samsung físico conectado y prueba local STT.

## Hito activo
**F1.13 completado; F1 cerrado.** Los contratos F1.01–F1.03 están integrados con las pantallas F1.06–F1.12. `assembleDebug`, `testDebugUnitTest` y `connectedDebugAndroidTest` pasan en el emulador API 37. **Siguiente tarea: F2.01, no iniciada. No se avanza a F2 sin asignación explícita.**

## Registro de entregas (añadir entradas, no reescribir historia)
Formato sugerido:
```
### AAAA-MM-DD HH:mm — [Codex|OpenCode] — F#.#
- Resultado:
- Archivos:
- Pruebas y resultado:
- Decisiones / riesgos:
- Pendiente para siguiente agente:
```

### 2026-10-11 — OpenCode (deepseek-v4-flash) — F1.07
- Resultado: Inicio «Hoy» con jerarquía del día y datos demo inequívocos. Render verificado en emulador.
- Archivos: `app/src/main/java/com/yezvi/assistant/ui/today/TodayScreen.kt` (nuevo); `app/src/main/res/values/strings.xml`; `app/src/main/java/com/yezvi/assistant/MainActivity.kt`.
- Pruebas y resultado: `.\gradlew.bat assembleDebug` → BUILD SUCCESSFUL; `.\gradlew.bat testDebugUnitTest` → BUILD SUCCESSFUL; APK instalado y abierto en `emulator-5554` (Pixel 8 API35); volcado de UI confirma banner demo, resumen y secciones; logcat sin crash de la app.
- Decisiones / riesgos: modelo `DemoTodayItem`/`DemoItemKind` es `internal` y vive solo en UI (no es contrato de dominio); `onCaptureClick` es no-op hasta F1.06/F1.08. Build requiere `JAVA_HOME` = JBR de Android Studio (Java 25). Ver `docs/IMPLEMENTACIONES/F1.07-inicio-hoy.md`.
- Pendiente para siguiente agente: F1.01 (contratos, Codex) y F1.06 (navegación) siguen abiertos; F1.07 ya no bloquea. Siguiente sugerido: F1.06.

### 2026-10-11 — OpenCode — F1.04
- Resultado: dirección visual única definida (One UI + Material 3): Material You dinámico con respaldo teal, elevación tonal, zona de interacción inferior, fecha colapsable y reglas de accesibilidad/movimiento.
- Archivos: `docs/IMPLEMENTACIONES/F1.04-investigacion-visual.md` (nuevo); `docs/UX_UI.md` (sección «Dirección visual F1.04»).
- Pruebas y resultado: sin cambios de código; no se ejecutó Gradle. Fuentes: Samsung One UI Overview y Material 3 AndroidX (Context7). m3.material.io y developer.android.com no cargaron.
- Decisiones / riesgos: dynamic color por defecto; semilla teal solo como respaldo; tokens concretos quedan para F1.05. Mejoras a «Hoy» listadas, no aplicadas.
- Pendiente para siguiente agente: F1.05 (tokens visuales) requiere aprobación; F1.06 sigue sugerido si se prioriza navegación.

### 2026-10-11 — OpenCode — F1.05
- Resultado: sistema de diseño «Memoria tranquila» implementado: dynamic color por defecto, fallback teal #00696B claro/oscuro accesible, tokens de espaciado/formas/movimiento y tipografía F1.04.
- Archivos: `ui/theme/Color.kt`, `ui/theme/Type.kt`, `ui/theme/Tokens.kt` (nuevo), `ui/theme/Theme.kt`; docs: `docs/IMPLEMENTACIONES/F1.05-sistema-diseno.md`.
- Pruebas y resultado: `assembleDebug` + `testDebugUnitTest` → BUILD SUCCESSFUL; verificado en emulador en claro y oscuro (captura oscura revisada, sin crash). Dynamic color activo en el emulador; teal aplica como fallback.
- Decisiones / riesgos: ADR D-006 (dynamic color + identidad teal). Sin componentes nuevos por falta de uso real. `TodayScreen.kt` intacto.
- Pendiente para siguiente agente: F1.06 (navegación) sugerido; F1.11 aplicará `Motion` con respeto a reducción de animaciones.

### 2026-10-11 — OpenCode — F1.06
- Resultado: navegación funcional de 4 destinos (Hoy, Memoria, Capturar, Ajustes) con Navigation Compose 2.10.2 y barra inferior custom; FAB de Hoy conectado a Captura.
- Archivos: `ui/navigation/{YezviDestinations,YezviBottomBar,YezviApp}.kt` (nuevos); `ui/screens/PlaceholderScreens.kt` (nuevo); `ui/today/TodayScreen.kt` (parámetro `bottomBar`); `MainActivity.kt`; `strings.xml`; `gradle/libs.versions.toml`; `app/build.gradle.kts`.
- Pruebas y resultado: `assembleDebug` + `testDebugUnitTest` → BUILD SUCCESSFUL; secuencia en emulador verificada (Hoy ↔ Memoria ↔ Ajustes, FAB → Capturar, Atrás vuelve a Hoy, segunda Atrás sale sin crash).
- Decisiones / riesgos: bottom bar con texto sin iconos para evitar dependencia extra; `popUpTo(Hoy)` con `saveState`/`restoreState`. No ADR: Navigation Compose es estándar para Android.
- Pendiente para siguiente agente: F1.08 (captura real) y F1.09 (timeline) reemplazarán placeholders; F1.11 animaciones de transición.

### 2026-10-12 — OpenCode — F1.08
- Resultado: pantalla de captura textual funcional, con borrador temporal, revisión con metadatos `CaptureInput` de F1.01, descarte en Atrás/cambio de pestaña y honestidad de no-guardado. Sin persistencia real.
- Archivos: `ui/screens/CaptureScreen.kt` (nuevo); `ui/screens/PlaceholderScreens.kt` (eliminado placeholder Capturar); `res/values/strings.xml`.
- Pruebas y resultado: `assembleDebug` + `testDebugUnitTest` → BUILD SUCCESSFUL; emulador: FAB → Capturar, campo de texto, revisión con origen/fecha/zona, Atrás con descarte, vuelta a Hoy. Popup de stylus del emulador al usar `input text`, no afecta la app.
- Decisiones / riesgos: `Modifier.imePadding()` en el `bottomBar` para mantener acciones visibles con teclado; `BackHandler` y diálogo de descarte; reutiliza `CaptureInput`/`CaptureSourceType` de F1.01 solo para preview. No ADR.
- Pendiente para siguiente agente: F1.02 callbacks pueden integrarse en el host cuando se añada persistencia/demo repository; F2 para guardado real.

### 2026-10-12 — OpenCode — F1.09
- Resultado: pantalla Memoria reemplazada por línea temporal con entradas agrupadas por fecha local, orden descendente, encabezados pegajosos, tarjetas con origen/hora/vista previa, banner de datos de demostración y estado vacío.
- Archivos: `ui/screens/MemoryScreen.kt` (nuevo); `ui/screens/PlaceholderScreens.kt` (placeholder Memory eliminado); `res/values/strings.xml`.
- Pruebas y resultado: `assembleDebug` + `testDebugUnitTest` → BUILD SUCCESSFUL; emulador: Hoy → Memoria → Capturar → Hoy, agrupación y orden correctos, etiqueta demo visible.
- Decisiones / riesgos: agrupación con `SimpleDateFormat` y `TimeZone` de la entrada; encabezados `stickyHeader`; sin detalle ni persistencia. No ADR.
- Pendiente para siguiente agente: F1.10 detalle de entrada; F2 persistencia real.

### 2026-10-11 — Codex — F1.01
- Resultado: definidos `CaptureInput`, `MemoryEntry`, `CaptureSourceType` y `CaptureStatus` como contratos Kotlin puros; texto original intacto, origen tipado, instante UTC, zona horaria e ID estable.
- Archivos: `app/src/main/java/com/yezvi/assistant/domain/memory/MemoryContracts.kt`; `app/src/test/java/com/yezvi/assistant/domain/memory/MemoryContractsTest.kt`; `docs/ARQUITECTURA.md`; `docs/DECISIONES.md`; `docs/IMPLEMENTACIONES/F1.01-contratos-memoria.md`; `docs/ESTADO.md`. `TodayScreen.kt` no se modificó.
- Pruebas y resultado: `JAVA_HOME` temporal apuntó a `C:\Program Files\Android\Android Studio\jbr`; `assembleDebug` → `BUILD SUCCESSFUL`; `testDebugUnitTest` → `BUILD SUCCESSFUL` (6 pruebas, 0 fallos).
- Decisiones / riesgos: timestamp como `Long` Unix epoch ms para `minSdk 24` sin nueva dependencia/desugaring; `CaptureInput` no tiene ID persistente; `VOICE` solo etiqueta origen. La carpeta no es un repositorio Git y `git status` no pudo ejecutarse.
- Pendiente para siguiente agente: F1.02 — eventos y callbacks públicos de captura/timeline (Codex); respetar la reconciliación pendiente F0.09 y coordinar después con OpenCode.

### 2026-10-11 — Codex — F1.02
- Resultado: definidos `MemoryUiCallbacks` y `AppDestination` para solicitar captura, destino inicial y selección por ID estable; callbacks sin navegación ni persistencia.
- Archivos: `app/src/main/java/com/yezvi/assistant/presentation/actions/MemoryUiCallbacks.kt`; `app/src/test/java/com/yezvi/assistant/presentation/actions/MemoryUiCallbacksTest.kt`; `docs/ARQUITECTURA.md`; `docs/DECISIONES.md`; `docs/IMPLEMENTACIONES/F1.02-acciones-ui.md`; `docs/ESTADO.md`. `TodayScreen.kt` y `MainActivity.kt` no se modificaron.
- Pruebas y resultado: `JAVA_HOME` temporal apuntó a `C:\Program Files\Android\Android Studio\jbr`; `assembleDebug` → `BUILD SUCCESSFUL`; `testDebugUnitTest` → `BUILD SUCCESSFUL` (9 pruebas, 0 fallos).
- Decisiones / riesgos: tres callbacks simples agrupados, sin eventos sellados; destino enum; selección transmite `MemoryEntry.id`; se reutiliza `CaptureInput` y no se duplica `CaptureStatus`. No se añadieron dependencias.
- Pendiente para siguiente agente: F1.03 — fixtures sintéticos y repositorio simulado (Codex); no iniciado. `git status` continúa bloqueado porque esta ruta no es un repositorio Git.

### 2026-10-11 — Codex — F1.03
- Resultado: añadidos cuatro `MemoryEntry` sintéticos, deterministas, ordenados y una fuente demo de solo lectura con lookup por ID.
- Archivos: `app/src/main/java/com/yezvi/assistant/data/demo/DemoMemoryFixtures.kt`; `app/src/main/java/com/yezvi/assistant/data/demo/DemoMemoryRepository.kt`; `app/src/test/java/com/yezvi/assistant/data/demo/DemoMemoryRepositoryTest.kt`; `docs/IMPLEMENTACIONES/F1.03-datos-sinteticos.md`; `docs/ESTADO.md`. `TodayScreen.kt` no se modificó.
- Pruebas y resultado: `JAVA_HOME` temporal apuntó a `C:\Program Files\Android\Android Studio\jbr`; `assembleDebug` → `BUILD SUCCESSFUL`; `testDebugUnitTest` → `BUILD SUCCESSFUL` (12 pruebas, 0 fallos).
- Decisiones / riesgos: IDs `demo-`, fechas fijas con zona `America/Lima` y `CaptureStatus.IDLE` para no afirmar que los datos están guardados. El objeto demo no escribe ni precarga Room; no se creó interfaz común.
- Pendiente para siguiente agente: F1.04 — auditoría pública de One UI/Material 3 (OpenCode); no iniciada. `git status` confirma rama `main` con upstream `origin/main`; los cambios F1.03 siguen sin commit.

### 2026-10-12 — OpenCode — F1.10
- Resultado: pantalla de detalle de entrada por ID con texto original, fecha, hora, origen, zona horaria y estado; estados Loading/NotFound/Error implementados; tarjetas de Memoria navegables.
- Archivos: `ui/screens/EntryDetailScreen.kt` (nuevo); `ui/navigation/{YezviDestinations,YezviApp}.kt`; `ui/screens/MemoryScreen.kt` (tarjetas navegables, helpers internal); `res/values/strings.xml`.
- Pruebas y resultado: `assembleDebug` + `testDebugUnitTest` → BUILD SUCCESSFUL; emulador: Memoria → tarjeta → Detalle → Atrás → Memoria correcto. Un screenshot mostró Hoy por timing de reinicio; prueba repetida con éxito.
- Decisiones / riesgos: ruta `entry/{entryId}` con `NavType.StringType`; `reloadTick` para retry; `SourceBadge`/`formatEntryTime` promovidos a `internal`. Estados vacío/error no accesibles con fixtures actuales. No ADR.
- Pendiente para siguiente agente: F1.11 animaciones/accesibilidad; F1.12 pruebas UI.

### 2026-10-12 — OpenCode — F1.11
- Resultado: transiciones de navegación y Crossfade de estados a 200 ms; soporte de movimiento reducido; encabezados y agrupación semántica (TalkBack); barra inferior tolerante a fuente grande.
- Archivos: `ui/theme/MotionSupport.kt` (nuevo); `ui/navigation/{YezviApp,YezviBottomBar}.kt`; `ui/screens/{EntryDetailScreen,MemoryScreen,CaptureScreen}.kt`; `ui/today/TodayScreen.kt`.
- Pruebas y resultado: `assembleDebug` + `testDebugUnitTest` → BUILD SUCCESSFUL. Emulador: font_scale 1.3 sin recortes; animator_duration_scale 0 sin crash y navegación instantánea; TalkBack enfoca el título «Hoy» (`heading()`).
- Decisiones / riesgos: movimiento reducido leyendo `ANIMATOR_DURATION_SCALE` en composición (requiere reabrir app al cambiarlo); sin animaciones infinitas/decorativas; sin dependencias. No ADR.
- No comprobado: salida de voz de TalkBack y medición formal de 48 dp con auditor. Pendiente para siguiente agente: F1.12 pruebas UI/Compose y previews.

### 2026-10-12 — OpenCode — F1.12
- Resultado: pruebas instrumentadas de UI/Compose para navegación principal, flujo de captura, estados de detalle, accesibilidad (headings, objetivos táctiles ≥48 dp, fuente grande) y previews. Todas pasan en el emulador API 37.
- Archivos:
  - Tests: `app/src/androidTest/java/com/yezvi/assistant/ui/navigation/YezviAppNavigationTest.kt`; `app/src/androidTest/java/com/yezvi/assistant/ui/screens/CaptureDraftFlowTest.kt`; `app/src/androidTest/java/com/yezvi/assistant/ui/screens/EntryDetailAndAccessibilityTest.kt`; `app/src/test/java/com/yezvi/assistant/ui/theme/MotionSupportTest.kt`.
  - Producción: `ui/screens/{CaptureScreen,EntryDetailScreen,MemoryScreen}.kt`; `ui/today/TodayScreen.kt`; `ui/navigation/YezviBottomBar.kt`; `ui/screens/PlaceholderScreens.kt`; `gradle/libs.versions.toml`.
  - Documentación: `docs/ESTADO.md`; `docs/DECISIONES.md` (decisión D-00X sobre dependencias de test); `docs/IMPLEMENTACIONES/F1.12-pruebas-ui.md`.
- Pruebas y resultado: `assembleDebug` + `testDebugUnitTest` + `connectedDebugAndroidTest` → BUILD SUCCESSFUL. Emulador `Medium_Phone_API_37.0(AVD) - 17`: 8 tests instrumentados (1 ejemplo + 7 nuevos), 14 tests unitarios.
- Decisiones / riesgos:
  - Espresso 3.5.1 falla en API 37 con `NoSuchMethodException: android.hardware.input.InputManager.getInstance`; se actualizó a **Espresso 3.7.0** y **androidx.test.ext:junit 1.3.0** (ver `docs/DECISIONES.md`).
  - Los botones de la barra inferior ahora usan `fillMaxHeight()` para garantizar objetivos táctiles ≥48 dp.
  - Se añadieron `testTag` en FAB, botones de navegación, campo de captura, tarjetas de timeline y valor de origen del detalle.
  - Los clics en `ExtendedFloatingActionButton` y `Card` no fueron estables en este emulador (pérdida de evento `NO_INPUT_CHANNEL` al inicio / posible bug de inyección de Espresso); el flujo de captura se verifica navegando desde la barra inferior y el detalle se verifica directamente con `createComposeRule`.
- Pendiente para siguiente agente: esperar asignación del siguiente ticket; no avanzar a F2 sin coordinar.

### 2026-10-12 — Codex — Corrección de barra inferior
- Resultado: eliminada la expansión vertical de la barra de navegación; los botones mantienen altura táctil mínima de 48 dp dentro de una barra de al menos 80 dp.
- Archivos: `app/src/main/java/com/yezvi/assistant/ui/navigation/YezviBottomBar.kt`; `docs/ESTADO.md`.
- Causa: `fillMaxHeight()` en cada `TextButton` expandía su superficie seleccionada bajo las restricciones del `Scaffold`.
- Verificación: `assembleDebug`, `testDebugUnitTest` y `connectedDebugAndroidTest` → `BUILD SUCCESSFUL`; 8 pruebas instrumentadas en API 37. Capturas de Hoy y Ajustes confirman que el indicador seleccionado queda limitado al botón; prueba de regresión exige altura entre 48 y 80 dp.

### 2026-10-11 — Codex — F1.13
- Resultado: integrados los contratos de captura, entradas, callbacks y fixtures con navegación, captura, timeline y detalle. F1 queda cerrado; no se implementó persistencia.
- Archivos: `app/src/main/java/com/yezvi/assistant/ui/navigation/{YezviApp,YezviBottomBar,YezviDestinations}.kt`; `app/src/main/java/com/yezvi/assistant/ui/screens/{CaptureScreen,MemoryScreen,EntryDetailScreen,PlaceholderScreens}.kt`; pruebas de captura, navegación y detalle bajo `app/src/androidTest/java/com/yezvi/assistant/ui/`; `docs/IMPLEMENTACIONES/F1.13-integracion-contratos.md`; `docs/ESTADO.md`.
- Pruebas y resultado: `assembleDebug`, `testDebugUnitTest`, `connectedDebugAndroidTest` → `BUILD SUCCESSFUL`; 14 pruebas unitarias y 8 instrumentadas, cero fallos. Instrumentadas en `Medium_Phone_API_37.0(AVD) - 17`; no es validación Samsung física.
- Decisiones / riesgos: captura conserva el texto original y su contexto temporal; las pantallas reciben datos/callbacks desde el host y seleccionan por ID. Demo permanece aislada y `TodayScreen.kt`/`DemoTodayItem` no se migran. `onCaptureRequested` queda sin invocar hasta que exista una acción explícita de guardado. Sin ADR ni dependencias nuevas.
- Pendiente para siguiente agente: F2.01 — evaluar Room/KSP para las versiones existentes, documentar alternativas y riesgos; no iniciada.
