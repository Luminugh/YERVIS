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
- [ ] `git status` y checkpoint: esta ruta no se reconoce como repositorio Git; no se inicializó.
- [ ] Samsung físico conectado y prueba local STT.

## Hito activo
**F0 continúa abierto** por las comprobaciones pendientes anteriores y la reconciliación de roadmaps. F1.01 y F1.02 se completaron por instrucción explícita. Siguiente contrato del `ROADMAP_MAESTRO.md`: **F1.03** (Codex); no se inició.

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
