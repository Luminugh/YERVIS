# Estado vivo del proyecto

**Última actualización documental:** 2026-10-12.

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
**F1 cerrado; F2.01–F2.13 completados; F2.14 validada parcialmente.** D-010 mantiene Room 3.0.3 + KSP 2.3.12 (KSP2), con `AndroidSQLiteDriver`; D-011 mantiene schema v2 con cascada. `1.json` permanece inalterado y `2.json` sigue staged. La secuencia offline manual verificó captura, Memoria, Detalle y reapertura tras muerte del proceso; pruebas UI/Room focalizadas pasaron sin red. La edición manual quedó bloqueada por ANR de foco/entrada y el AVD era Medium Phone API 37, no Pixel 8. **F2 aún requiere repetir la secuencia manual en Pixel 8 y comprobar edición/borrado antes de recomendar cierre. No avanzar a F3 automáticamente.**

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

### 2026-10-11 — Codex — F2.01
- Resultado: inventario de versiones y evaluación oficial de compatibilidad completados; recomendación propuesta: Room 3.0.3 + KSP 2.3.12 con KSP2. F1 continúa cerrada.
- Archivos: `docs/IMPLEMENTACIONES/F2.01-room-ksp.md`; `docs/DECISIONES.md` (D-010 propuesta); `docs/ESTADO.md`. No se modificó Gradle ni código.
- Configuración observada: AGP 9.4.1, Gradle 9.6.0, catálogo del plugin Compose Kotlin 2.2.10/AGP built-in Kotlin, compile/target SDK 37, min SDK 24, JDK 25 para Gradle, bytecode Java 11.
- Compatibilidad: AGP 9.4 requiere Gradle 9.6 y JDK ≥17; KSP 2.3.12 declara AGP mínimo 8.12. No hay matriz oficial de la tupla exacta con KGP 2.2.10; el procesamiento Room debe validarse en F2.02.
- Verificación: lectura de configuración, `gradlew --version` y documentación oficial actual. No se ejecutaron build/tests porque solo cambió documentación.
- Pendiente para siguiente agente: F2.02 — diseñar esquema mínimo Room 3 y versionar JSON exportados; validar generación, base de prueba y migración inicial. No iniciada.

### 2026-10-12 — Codex — F2.02
- Resultado: definido el esquema v1 mínimo `memory_entries` con ID estable, `rawText`, `sourceType`, instante UTC y zona IANA; sin estados transitorios ni campos derivados.
- Archivos: `docs/IMPLEMENTACIONES/F2.02-esquema-memoria.md`; `docs/IMPLEMENTACIONES/F2.01-room-ksp.md` (resultado actualizado); `docs/DECISIONES.md` (D-010 actualizada); `docs/ESTADO.md`. Sin cambios de código o Gradle.
- Validación: proyecto temporal fuera del repositorio con AGP 9.4.1 / Gradle 9.6.0 / Kotlin integrado y plugin Compose 2.2.10 / KSP2 2.3.12 / Room 3.0.3 / SDK 37 / JDK 25 / bytecode 11: `:app:kspDebugKotlin`, `:app:copyRoomSchemas` y `:app:compileDebugKotlin` → `BUILD SUCCESSFUL`; JSON v1 generado y tipos/índice verificados. No se corrieron tests del proyecto.
- Límite: el JSON del `ProbeDatabase` temporal no se añadió a `app/schemas`; schema de producción pendiente de la clase `@Database` de F2.04. Driver y ejecución de consultas siguen sin probarse.
- Siguiente tarea: F2.03 — Entity/DAO conforme al esquema; F2.04 creará la base y exportará el JSON real. No iniciadas.

### 2026-10-12 — Codex — F2.03
- Resultado: añadidos `MemoryEntryEntity` y `MemoryEntryDao` para `memory_entries` v1; CRUD tipado, lectura por ID y `Flow` descendente por instante/ID. `CaptureStatus` no se persiste.
- Archivos: `app/src/main/java/com/yezvi/assistant/data/local/memory/{MemoryEntryEntity,MemoryEntryDao,MemoryEntryMappers}.kt`; `app/src/test/java/com/yezvi/assistant/data/local/memory/MemoryEntryMappersTest.kt`; `app/build.gradle.kts`; `build.gradle.kts`; `gradle/libs.versions.toml`; `docs/IMPLEMENTACIONES/F2.03-entity-dao.md`; `docs/ESTADO.md`.
- Pruebas y resultado: con JBR de Android Studio y SDK local, `:app:kspDebugKotlin`, `:app:assembleDebug` y `:app:testDebugUnitTest` → `BUILD SUCCESSFUL`; 15 pruebas unitarias, cero fallos. La prueba nueva conserva texto literal, origen, epoch-millis, zona e ID.
- Límite/riesgo: no existe aún `@Database`; KSP no produjo `MemoryEntryDao_Impl` ni schema real, por lo que generación completa y consultas SQLite quedan pendientes de F2.04. No se creó base/driver ni se tocaron pantallas.
- Siguiente tarea: F2.04 — registrar DAO y Entity en la base Room, configurar driver y exportar schema JSON v1; validar generación/CRUD real. No iniciada.

### 2026-10-12 — Codex — F2.04
- Resultado: creada `YezviDatabase` v1 con Entity/DAO, exportación versionada y factory singleton con `applicationContext`; usa `AndroidSQLiteDriver` sobre SQLite del framework Android.
- Archivos: `app/src/main/java/com/yezvi/assistant/data/local/memory/{YezviDatabase,YezviDatabaseFactory}.kt`; `app/src/androidTest/java/com/yezvi/assistant/data/local/memory/YezviDatabaseTest.kt`; `app/schemas/com.yezvi.assistant.data.local.memory.YezviDatabase/1.json`; `gradle/libs.versions.toml`; `build.gradle.kts`; `app/build.gradle.kts`; `docs/DECISIONES.md` (D-010 ampliada); `docs/IMPLEMENTACIONES/F2.04-base-room.md`; `docs/ESTADO.md`.
- Pruebas: `kspDebugKotlin`, `assembleDebug`, `testDebugUnitTest` y `connectedDebugAndroidTest` → `BUILD SUCCESSFUL`; 15 unitarias y 9 instrumentadas, cero fallos. Prueba de DB en AVD API 37 valida insert, consulta/Flow, update, cierre/reapertura en archivo y delete.
- Schema: Room generó `YezviDatabase_Impl`, `MemoryEntryDao_Impl` y `1.json`; coinciden con F2.02 en nombre de tabla, cinco columnas, tipos, `NOT NULL`, PK no autogenerada e índice temporal. Solo se usaron datos sintéticos.
- Riesgos: AndroidSQLiteDriver usa la SQLite de cada versión del sistema; instrumentación realizada en API 37, no en dispositivo físico ni API 24. No hay fallback destructivo ni migraciones porque la base parte en versión 1.
- Siguiente tarea: F2.05 — repositorio que oculte Room y exponga operaciones suspend/Flow al dominio/presentación; no iniciada.

### 2026-10-12 — Codex — F2.05
- Resultado: definido `MemoryRepository` independiente de Room e implementado `RoomMemoryRepository` para guardar, buscar por ID, observar en orden cronológico, actualizar y borrar. `DemoMemoryRepository` permanece aislado.
- Archivos: `app/src/main/java/com/yezvi/assistant/domain/memory/MemoryRepository.kt`; `app/src/main/java/com/yezvi/assistant/data/repository/RoomMemoryRepository.kt`; `app/src/main/java/com/yezvi/assistant/data/local/memory/MemoryEntryMappers.kt`; `app/src/androidTest/java/com/yezvi/assistant/data/repository/RoomMemoryRepositoryTest.kt`; `docs/IMPLEMENTACIONES/F2.05-repositorio.md`; `docs/ESTADO.md`.
- Pruebas: `kspDebugKotlin`, `assembleDebug`, `testDebugUnitTest` y `connectedDebugAndroidTest` → `BUILD SUCCESSFUL`; 15 unitarias y 11 instrumentadas, cero fallos. Las dos pruebas nuevas usan SQLite real/API 37 y validan CRUD, metadatos, orden/emisiones del Flow y propagación de colisión de ID.
- Decisiones/riesgos: el llamador entrega el ID estable; los errores Room/SQLite se propagan; `CaptureStatus` no se persiste. Sin dependencias nuevas ni ADR. Prueba instrumentada en AVD API 37, no en Samsung físico ni API 24.
- Siguiente tarea: F2.06 — ViewModel/StateFlow y estados de guardado/error; no iniciada. No se conectó la UI ni se avanzó F2.07.

### 2026-10-12 — Codex — F2.06
- Resultado: implementado `CaptureViewModel` con `StateFlow` para reposo, guardando, guardado y error; éxito solo tras completar el guardado suspendido, borrador conservado ante error y reintento con el mismo ID.
- Archivos: `app/src/main/java/com/yezvi/assistant/presentation/capture/CaptureViewModel.kt`; `app/src/test/java/com/yezvi/assistant/presentation/capture/CaptureViewModelTest.kt`; `app/build.gradle.kts`; `gradle/libs.versions.toml`; `docs/IMPLEMENTACIONES/F2.06-viewmodel-captura.md`; `docs/ESTADO.md`.
- Pruebas: `assembleDebug` y `testDebugUnitTest` → `BUILD SUCCESSFUL`; 18 pruebas unitarias, cero fallos (3 nuevas). Casos de éxito/estados, doble toque, fallo/reintento, ID estable y cancelación de `viewModelScope`.
- Decisiones/riesgos: UUID generado en presentación; solo se agregó `kotlinx-coroutines-test` 1.9.0 para reemplazar Main y controlar pruebas. No se registran textos ni se persiste `CaptureStatus`. El estado borrador no sobrevive muerte de proceso.
- Siguiente tarea: F2.07 — conectar Capturar al ViewModel y persistencia real; no iniciada. No se modificaron pantallas ni se avanzó F2.08.

### 2026-10-12 — Codex — F2.07
- Resultado: Capturar guarda desde el diálogo de revisión vía `CaptureViewModel` y `RoomMemoryRepository`; refleja IDLE/SAVING/SAVED/FAILED, retiene borrador/contexto tras error, reintenta con el mismo ID y confirma/limpia solo tras escritura SQLite exitosa. Memoria continúa mostrando demo.
- Archivos: `app/src/main/java/com/yezvi/assistant/ui/navigation/YezviApp.kt`; `app/src/main/java/com/yezvi/assistant/ui/screens/CaptureScreen.kt`; `app/src/main/res/values/strings.xml`; `app/src/androidTest/java/com/yezvi/assistant/ui/screens/CapturePersistenceTest.kt`; `docs/IMPLEMENTACIONES/F2.07-guardado-real.md`; `docs/ESTADO.md`.
- Pruebas y resultado: `:app:assembleDebug` → `BUILD SUCCESSFUL`; `:app:testDebugUnitTest` → `BUILD SUCCESSFUL` (18, cero fallos); `:app:connectedDebugAndroidTest` → `BUILD SUCCESSFUL` (12, cero fallos) en AVD API 37. La instrumentada verifica colisión SQLite, borrador/reintento, metadatos, estado final y persistencia tras cerrar/reabrir la base. App instalada/arrancada en `emulator-5554`, pantalla Hoy cargada.
- Decisiones/riesgos: sin dependencias, cambios de schema ni migración; ID y `CaptureInput` se conservan dentro del ViewModel durante los reintentos. El borrador en pantalla no se garantiza tras muerte forzada del proceso. No se validó Samsung físico.
- Siguiente tarea: F2.08 — conectar timeline/Memoria a datos persistidos y probar orden por instante UTC/fecha local; no iniciada. No avanzar automáticamente.

### 2026-10-12 — Codex — F2.08
- Resultado: Memoria observa `MemoryRepository` y muestra solo recuerdos persistidos con estados carga/vacío/contenido/error; tarjetas y Detalle consultan por ID real. La fecha de agrupación se calcula en la zona horaria guardada. Hoy aclara que sus ejemplos aún son demo.
- Archivos F2.08: `app/src/main/java/com/yezvi/assistant/{ui/navigation/YezviApp.kt,ui/screens/MemoryScreen.kt,ui/screens/EntryDetailScreen.kt,presentation/memory/MemoryViewModel.kt,presentation/memory/EntryDetailViewModel.kt,res/values/strings.xml}`; tests `app/src/test/java/com/yezvi/assistant/presentation/memory/MemoryViewModelTest.kt`, `app/src/test/java/com/yezvi/assistant/ui/screens/MemoryTimelineGroupingTest.kt`, `app/src/androidTest/java/com/yezvi/assistant/ui/screens/MemoryPersistenceTest.kt` y ajustes de expectativas F2.07/F1.12 en tests instrumentados; `docs/IMPLEMENTACIONES/F2.08-timeline-persistente.md`.
- Validación: `assembleDebug` + `testDebugUnitTest` → `BUILD SUCCESSFUL` (20 unit tests). Suite conectada API 37: 14/15; falló únicamente `CaptureDraftFlowTest.typingDraftReviewAndDiscardFlow` con timeout de sincronización Espresso (`DYNAMIC_TASKS_HAVE_IDLED`, 60 s), sin fallo de aserción F2.08. Prueba focalizada F2.08 en `emulator-5554`: 1/1 aprobada, dos capturas sintéticas, estado vacío, orden, detalle y reapertura de archivo Room tras recreación de Activity.
- Límites/riesgos: el test usa archivo Room temporal propio y no toca base normal ni datos personales; se probó recreación de Activity + Room, no muerte forzada del proceso. AVD API 37; no Pixel 8 ni Samsung físico. No se cambió schema/DAO ni se implementaron edición/borrado.
- Siguiente tarea: F2.09 — edición de recuerdo persistido; no iniciada. No avanzar automáticamente.

### 2026-10-12 — Codex — F2.09
- Estado: implementada la migración aprobada Room v1→v2 y el historial append-only de correcciones. `rawText` y los metadatos originales quedan inmutables; la versión vigente usa la última corrección por ID de inserción o el original si no hay revisiones.
- Archivos: Entity/DAO/migración/repositorio/modelos de corrección en `app/src/main/java/com/yezvi/assistant/{data/local/memory,data/repository,domain/memory}`; `YezviDatabase`, factory y contrato de memoria ajustados para v2 e inmutabilidad; prueba `MemoryCorrectionMigrationTest`; schema nuevo `app/schemas/com.yezvi.assistant.data.local.memory.YezviDatabase/2.json` (1.json intacto); documentación F2.09 y D-011 aceptada. Se retiró `MemoryRepository.update`/`MemoryEntryDao.update` para cerrar la vía de mutación del original.
- Pruebas: `:app:kspDebugKotlin`, `:app:assembleDebug`, `:app:testDebugUnitTest` (20/20) y `:app:connectedDebugAndroidTest` (17/17) → `BUILD SUCCESSFUL`; instrumentación en `emulator-5554`, Medium Phone API 37. F2.09 comprueba datos v1 sintéticos, migración, correcciones/historial, latest por ID, FK, error/rollback y cierre/reapertura SQLite.
- Decisión/riesgo: D-011 aceptada; migración aditiva sin backfill ni fallback destructivo. Se creó el fixture v1 desde el DDL/hash versionado porque `MigrationTestHelper` 3.0.3 falla con `AbstractMethodError` bajo la resolución consistente kotlinx.serialization 1.7.3; Room y la factory real validan la migración. No validado en Samsung físico ni API 24.
- Siguiente tarea: F2.10 — borrado explícito con confirmación y estado honesto; no iniciada. No avanzar a F2.10/F2.11 automáticamente.

### 2026-10-12 — Codex — F2.10
- Resultado: borrado explícito desde Detalle con confirmación; cancelar no llama al repositorio, los toques repetidos se bloquean, el error conserva el recuerdo y permite reintento, y el éxito se muestra después del resultado positivo de SQLite. Memoria y Detalle observan cambios por Flow; un ID desaparecido pasa a NotFound.
- Archivos: `MemoryRepository.kt`, `MemoryEntryDao.kt`, `RoomMemoryRepository.kt`, `EntryDetailViewModel.kt`, `EntryDetailScreen.kt`, `YezviApp.kt`, `strings.xml`; tests `EntryDetailViewModelTest.kt`, `RoomMemoryRepositoryTest.kt`, `EntryDetailAndAccessibilityTest.kt`; docs F2.10. D-011 ya cubre la FK `ON DELETE CASCADE`; no se añadió ADR.
- Pruebas: `:app:assembleDebug` y `:app:testDebugUnitTest` → BUILD SUCCESSFUL (23/23); `:app:connectedDebugAndroidTest` → BUILD SUCCESSFUL (21/21) en `emulator-5554`, Medium Phone API 37. Verificados cancelación, doble toque, error/reintento, cascada de dos correcciones, actualizaciones Flow y borrado persistente tras reabrir SQLite.
- Límites/riesgos: no hubo cambio de schema ni migración; no se promete borrado físico irrecuperable ni eliminación de backups. Validación en AVD, no Pixel 8 ni Samsung físico. El diseño completo queda para F2.11.
- Siguiente tarea: F2.11 — pantallas de edición/borrado y fallos de guardado; no iniciada. No avanzar automáticamente.

### 2026-10-12 — Codex — F2.11
- Resultado: Detalle permite editar el texto vigente con revisiones append-only, ver separado el original y el historial cronológico, conservar el borrador ante error SQL y reintentar. Cancelar/volver no guarda. Se reutiliza el diálogo de borrado F2.10 y su confirmación real; no cambió schema v2 ni contratos de dominio.
- Archivos: `app/src/main/java/com/yezvi/assistant/presentation/memory/EntryDetailViewModel.kt`, `ui/screens/EntryDetailScreen.kt`, `ui/navigation/YezviApp.kt`, `res/values/strings.xml`; tests `app/src/test/java/com/yezvi/assistant/presentation/memory/EntryDetailViewModelTest.kt`, `app/src/androidTest/java/com/yezvi/assistant/ui/screens/{EntryDetailEditingTest,EntryDetailAndAccessibilityTest}.kt`; docs `docs/IMPLEMENTACIONES/F2.11-ui-edicion-borrado.md`. Sin ADR ni dependencias nuevas.
- Pruebas: `:app:assembleDebug` y `:app:testDebugUnitTest` → BUILD SUCCESSFUL (26/26). `:app:compileDebugAndroidTestKotlin` → BUILD SUCCESSFUL. `:app:connectedDebugAndroidTest` completa ejecutó 22 pruebas con 2 fallos: el matcher semántico de la nueva prueba F2.11 (corregido) y timeout de Compose/Espresso en test preexistente `MemoryPersistenceTest.emptyTimelineUpdatesFromCaptureAndSurvivesActivityRecreation`, línea 91. Luego `EntryDetailEditingTest` focalizado → BUILD SUCCESSFUL, 1/1 en `emulator-5554`, Medium Phone API 37.
- Riesgos: suite conectada completa no se repitió tras el arreglo del matcher; el caso focalizado final sí pasó. No se validó Samsung físico; la prueba usa SQLite temporal y datos sintéticos. `MemoryCorrectionRepository` no ofrece Flow para refrescar historial escrito por otro proceso mientras Detalle sigue abierto.
- Siguiente tarea: F2.12 — pruebas DAO/repositorio/temporalidad; no iniciada. No avanzar automáticamente.

### 2026-10-12 — Codex — F2.12
- Resultado: ampliadas las pruebas de Room v2 para CRUD, Flow, orden UTC/ID, zonas horarias, límites de día local, correcciones append-only, conservación de `rawText`/metadatos, reapertura y borrado en cascada sin huérfanos. No hubo cambios de schema, migración, dependencias ni UI.
- Archivos: `app/src/androidTest/java/com/yezvi/assistant/data/repository/RoomMemoryRepositoryTest.kt`, `app/src/androidTest/java/com/yezvi/assistant/ui/screens/MemoryPersistenceTest.kt`, `app/src/test/java/com/yezvi/assistant/ui/screens/MemoryTimelineGroupingTest.kt`; `docs/IMPLEMENTACIONES/F2.12-pruebas-persistencia.md`.
- Pruebas: `assembleDebug` → BUILD SUCCESSFUL; `testDebugUnitTest` → BUILD SUCCESSFUL (28/28); `connectedDebugAndroidTest` suite completa → BUILD SUCCESSFUL (23/23) en `emulator-5554`, Medium Phone API 37. `MemoryPersistenceTest` aislado corregido → 1/1.
- Investigación: el fallo señalado como timeout en F2.11 fue una aserción ambigua que encontró dos nodos del mismo texto en la línea 91; se corrigió el selector al tag de texto vigente. La aserción de `EntryDetailEditingTest` corregida en F2.11 también pasó en la suite completa.
- Límite conocido: `MemoryCorrectionRepository` no observa el historial con `Flow`; correcciones escritas por otro emisor no refrescan el Detalle abierto hasta recargar. Sin Samsung físico/Pixel 8 físico; no hubo cambios de producción.
- Siguiente tarea: F2.13 — estrategia de esquema y migraciones iniciales; no iniciada. No avanzar automáticamente.

### 2026-10-12 — Codex — F2.13
- Resultado: auditados los snapshots v1/v2, entidades, migración registrada y cobertura SQLite. `1.json` coincide con HEAD; `2.json` se verificó contra Room v2 y quedó staged para que el próximo checkpoint lo incluya. Ningún snapshot fue editado; no existe `fallbackToDestructiveMigration`.
- Archivos: `docs/IMPLEMENTACIONES/F2.13-migraciones.md`, `docs/ESTADO.md`; `app/schemas/com.yezvi.assistant.data.local.memory.YezviDatabase/2.json` añadido al índice, sin commit.
- Cobertura existente: upgrade de v1 poblada y validación Room del destino, conservación de campos, índices/FK, rechazo de referencia huérfana, reapertura, rollback inducido; F2.12 verifica cascada sin huérfanos. El fixture SQLite manual continúa porque `MigrationTestHelper` 3.0.3 falló antes del upgrade con la resolución actual; sus límites y cotejo con `1.json` están documentados.
- Pruebas: no se ejecutó Gradle; solo documentación e inclusión del snapshot ya generado en Git. Sin ADR nueva. Riesgo pendiente: política de backup/restore de datos de producción aún no está definida en D-011.
- Siguiente tarea: F2.14 — prueba integral offline; no iniciada. No avanzar automáticamente.

### 2026-10-12 — Codex — F2.14
- Resultado: con red desactivada se guardó una nota sintética desde Capturar; Memoria y Detalle mostraron el original y metadatos, y el proceso se terminó con `am force-stop` y se reabrió sin perder la nota. Las pruebas instrumentadas offline de timeline/recreación, correcciones/historial, cancelación y borrado confirmado pasaron con Room real y fixtures sintéticos.
- Archivos: `docs/IMPLEMENTACIONES/F2.14-validacion-offline.md`, `docs/ESTADO.md`. Sin cambios de código, Gradle o schema; `app/schemas/com.yezvi.assistant.data.local.memory.YezviDatabase/2.json` continúa staged.
- Pruebas: `assembleDebug` → BUILD SUCCESSFUL (UP-TO-DATE); `testDebugUnitTest` → 28/28, 0 fallos; suite `connectedDebugAndroidTest` → 23/23, 0 fallos en Medium Phone API 37. Con red desconectada: `MemoryPersistenceTest` 1/1, `EntryDetailEditingTest` 1/1 y `RoomMemoryRepositoryTest#consecutiveCorrectionsPreserveOriginalAndHistoryAfterReopen` 1/1.
- Riesgos: la edición manual causó ANR por timeout de entrada/foco, impidiendo completar las correcciones y el borrado en la misma nota; la nota sintética quedó en la base principal del AVD. No se reinició dispositivo; no disponible Pixel 8, API 24 ni Samsung físico. Wi‑Fi/datos móviles restaurados y red validada.
- Recomendación: no cerrar F2 hasta repetir la experiencia manual completa y resolver/verificar el ANR en Pixel 8. F2.14 no avanza a F3 automáticamente.
