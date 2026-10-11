# Estado vivo del proyecto

**Última actualización documental:** 2026-10-11 (preparado como guía; no reemplaza las verificaciones locales).

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
**F0 continúa abierto** por las comprobaciones pendientes anteriores y la reconciliación de roadmaps. F1.01, F1.02 y F1.03 se completaron por instrucción explícita. Siguiente tarea del `ROADMAP_MAESTRO.md`: **F1.04** (OpenCode); no se inició.

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
