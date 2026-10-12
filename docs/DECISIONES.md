# Registro de decisiones (ADR ligero)

## D-001 — Android nativo / Kotlin + Compose
**Estado:** Aceptada. **Motivo:** integración con Android y Samsung, audio, widgets y APIs de sistema sin puentes multinivel. **Consecuencia:** iOS no es objetivo actual.

## D-002 — Memoria local como fuente de verdad
**Estado:** Aceptada. **Motivo:** privacidad, acceso sin Internet, resiliencia a límites de modelos gratuitos. **Consecuencia:** IA nunca guarda directamente ni elimina originales.

## D-003 — IA híbrida opcional
**Estado:** Aceptada como dirección; proveedor NO elegido. **Motivo:** transcripción local, interpretación opcional sin obligar a llevar un LLM pesado en el móvil. **Condición:** autorización específica al transferir fragmentos de texto y política clara.

## D-004 — Roles de agentes
**Estado:** Propuesta operativa. **OpenCode Go:** UI/UX. **Codex:** arquitectura/lógica. Contratos primero, commits y handoffs explícitos.

## D-005 — Integraciones profundas Samsung
**Estado:** Diferida. **Motivo:** la capacidad de hotword/assistant con pantalla apagada depende de permisos y hardware; no prometer compatibilidad hasta probar modelo real.

## D-006 — Sistema de diseño: dynamic color + fallback teal (F1.05)
**Estado:** Aceptada. **Motivo:** Material You integra la app con el fondo del usuario en One UI/Android 12+; la semilla teal `#00696B` con acento ámbar da identidad propia y coherencia cuando dynamic color no está disponible. **Consecuencia:** los roles `tertiaryContainer` (avisos de honestidad) y `secondaryContainer` (distintivos) quedan reservados por convención; duraciones de animación centralizadas en `Motion` (200/300 ms).

## D-006 — Representación del contrato de captura F1.01
**Estado:** Aceptada para el contrato inicial. **Motivo:** mantener el modelo Kotlin independiente de Android y compatible con `minSdk 24` sin activar desugaring ni añadir dependencias.
- `capturedAtEpochMillis` representa el instante UTC como milisegundos desde Unix epoch; `timezoneId` conserva el identificador IANA de la zona al capturar.
- `MemoryEntry.id` es el identificador estable del registro. `CaptureInput` no tiene ID persistente porque representa una petición anterior a crear el registro; el formato y generación del ID se deciden en la capa que lo crea.
- `rawText` conserva exactamente el texto aceptado. `CaptureSourceType.VOICE` indica origen, pero no incorpora audio ni activa reconocimiento de voz.
- Los estados iniciales de captura son `IDLE`, `SAVING`, `SAVED` y `FAILED`; los estados específicos de voz quedan para F3.

## D-007 — Callbacks mínimos de UI F1.02
**Estado:** Aceptada para el contrato inicial. **Motivo:** conservar una interfaz de salida pequeña y desacoplada, sin introducir navegación ni un reducer.
- `MemoryUiCallbacks` agrupa las tres intenciones públicas: solicitar captura con `CaptureInput`, navegar a un `AppDestination` inicial y seleccionar un registro por su ID estable.
- `AppDestination` enumera `TODAY`, `MEMORY`, `CAPTURE` y `SETTINGS`; el consumidor decide cómo resolver cada destino.
- La selección transmite el ID, no copia el registro completo. Se reutilizan `CaptureInput` y `CaptureStatus` de F1.01; no se duplican estados ni se crea una jerarquía de eventos sellada.

## D-008 — Dependencias de testing para Android 16 / API 37 (F1.12)
**Estado:** Aceptada. **Motivo:** Espresso 3.5.1 refleja `android.hardware.input.InputManager.getInstance()`, método eliminado en Android 16 (API 36.1/37), provocando `NoSuchMethodException` en cada test instrumentado Compose. **Consecuencia:** se actualizaron `androidx.test.espresso:espresso-core` a **3.7.0** y `androidx.test.ext:junit` a **1.3.0** en `gradle/libs.versions.toml`. Solo afecta variantes de test; el APK de producción no cambia.

## D-009 — Estrategia de testabilidad UI (F1.12)
**Estado:** Aceptada con nota de riesgo. **Motivo:** los clics instrumentados sobre `ExtendedFloatingActionButton` y `Card` no fueron estables en el emulador API 37 (evento perdido con `NO_INPUT_CHANNEL` al inicio / inyección de Espresso). **Consecuencia:** se añadieron `testTag` semánticos y se priorizaron aserciones de existencia/no-idling (`onAllNodes(...).fetchSemanticsNodes()`) sobre `waitForIdle()` cuando el teclado virtual interfiere. El flujo de captura se verifica navegando desde la barra inferior y el detalle desde `createComposeRule`; el comportamiento real de FAB/tarjetas se validó manualmente en emulador y queda documentado para futura estabilización.

## D-010 — Persistencia Room/KSP y driver SQLite (F2.01–F2.04)
**Estado:** Aceptada para F2. **Decisión:** Room 3.0.3 + KSP 2.3.12 (KSP2) y `AndroidSQLiteDriver` de `androidx.sqlite:sqlite-framework:2.7.1`; se mantienen las versiones de Kotlin, AGP y Gradle. **Motivo:** app nueva exclusivamente Kotlin, sin Room 2.x ni consumidor conocido de SupportSQLite. El driver usa las APIs SQLite del framework Android, cubre `minSdk 24` y evita empaquetar otro SQLite nativo; se prefiere el motor del sistema para esta app Android-only. **Validación:** spike Room/KSP previo más build y prueba instrumentada F2.04 en AVD API 37; KSP generó la base/DAO y Room exportó schema v1. La prueba en archivo confirmó CRUD y persistencia tras cierre/reapertura. No existe matriz oficial que certifique la tupla completa AGP/KGP/KSP. **Riesgo remanente:** la versión del motor SQLite depende del sistema operativo; validar comportamiento en API 24 y dispositivos Samsung cuando haya entorno disponible.

## D-011 — Historial persistente de correcciones (F2.09)
**Estado:** Aceptada e implementada (2026-10-12). Se conserva inmutable `memory_entries.rawText` y se almacena cada corrección explícita como una fila append-only en `memory_entry_corrections` (`correctionId INTEGER PRIMARY KEY AUTOINCREMENT`, FK `memoryEntryId`, `correctedText` y `modifiedAtEpochMillis` UTC). `YezviDatabase` v2 incorpora tabla e índice con migración aditiva 1→2, sin backfill, borrado ni `fallbackToDestructiveMigration`; `1.json` permanece intacto y Room exportó `2.json`. El ID creciente define la versión vigente y el historial; sin correcciones, la versión vigente es `rawText`. Para impedir la mutación del original, se retiraron las operaciones genéricas `MemoryRepository.update`/`MemoryEntryDao.update`; solo la interfaz de correcciones puede añadir revisiones. La FK usa cascada cuando un futuro flujo F2.10 borre el padre. Motivo: conservar el texto y los metadatos originales auditables. La migración, rollback, FK, correcciones y reapertura se validan con SQLite real; detalles y resultado de la suite en `docs/IMPLEMENTACIONES/F2.09-edicion-segura.md` y `docs/ESTADO.md`.

## Decisiones por resolver
- Qué motor STT on-device funciona offline con buen español en el Samsung real.
- Migraciones y conservación de datos desde cada schema soportado en F2.12; la base actual empieza en v1.
- Cifrado, política de backup, exportación y eliminación, según modelo de amenazas.
- Proveedor gratuito cloud y política de datos; límites/costos y custodia de la credencial.
- Diseño definitivo de icono/nombre y personalidad TTS.
