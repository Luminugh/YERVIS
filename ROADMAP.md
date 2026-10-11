# Roadmap de YEZVI

**Fecha base:** 11 de octubre de 2026  
**Horizonte:** MVP personal Android primero; Samsung/IA avanzada después.  
**Convención:** `[ ]` pendiente / `[~]` parcial / `[x]` validado. Nunca marcar un hito como terminado sin evidencia.

## Principios de priorización
- Valor del producto: capturar -> conservar -> recuperar -> organizar -> asistir.
- Vertical slices demostrables; sin nube obligatoria ni backend propio al inicio.
- OpenCode Go (UI/UX) y Codex (núcleo) trabajan secuencialmente cuando tocan contratos o archivos compartidos.
- Cada hito termina con una prueba verificable en emulador y un registro de entrega en `docs/ESTADO.md`.

## F0 — Entorno y acuerdo de agentes (activo)
**Dueño principal:** persona desarrolladora; ayuda de ambos.  
**Alcance:** [x] Proyecto Kotlin/Compose creado; [x] `assembleDebug` exitoso; [x] Pixel 8 API35 reconocido por ADB; [ ] app instalada y abierta con Run comprobada; [ ] Context7 probado en OpenCode; [ ] Context7 probado en Codex; [ ] repo Git protegido con primer commit; [ ] documento compartido incorporado.

**Puerta de salida:** demo «Hello Android!» ejecutándose y herramientas verificadas, sin romper build. No dar por conectados los MCP basándose solo en que existe `mcp --help`.

## F1 — Contrato mínimo y primera UX (PRIMERA TAREA DE DESARROLLO)
**Orden:** Codex (contrato) -> OpenCode (pantallas con fake data) -> integración conjunta.
- **F1.1 Codex:** definir `CaptureInput`, `MemoryEntry`, `CaptureStatus` y acciones de UI; timestamps, timezone, texto original y origen. No crear Room aún salvo justificación.
- **F1.2 OpenCode:** diseño de Home + captura rápida por texto + timeline por día + estados vacíos/errores, usando datos sintéticos.
- **F1.3 OpenCode:** sistema visual coherente (Material3, dinámica Samsung inspirada en One UI, oscuro/claro, tipografía, semántica y transiciones discretas).
- **F1.4 Ambos:** conectar pantalla al contrato estable; pruebas Compose del guardado representado y navegación.
**No incluye:** IA, micro, login, sincronización, gráficos excesivos, sistemas de recomendaciones.
**Puerta de salida:** app navegable en Pixel 8 con UI usable a una mano, textos en español, datos simulados claramente identificados, sin errores de build. Captura falsa nunca se anuncia como persistida.

## F2 — Persistencia local real (MVP base)
**Dueño:** Codex, revisión UI por OpenCode.
- Evaluar Room estable compatible con versiones del proyecto; usar KSP si aplica.
- Entity, DAO, base de datos y Repository con insert/read/update/delete.
- Captura con `createdAt`, `timezone`, `rawText`, `sourceType`; mantener evento/fecha interpretada separados.
- ViewModel / StateFlow; reemplazar fake repository por implementación real.
- Tests de DAO, migraciones y orden temporal; prueba de reinicio de app.
**Puerta de salida:** una nota real creada desde la UI sobrevive cierre/reapertura y aparece en el día correcto sin Internet. Borrado/edición comprobados.

## F3 — Voz local y respuestas básicas
**Dueño:** Codex en audio/STT/TTS; OpenCode en estados de micrófono y animaciones.
- Permiso de micrófono contextual y estado visible.
- Evaluar `SpeechRecognizer` on-device en Samsung y emulador; usar motor independiente offline si no cumple calidad/fiabilidad en español.
- Estados: idle -> listening -> transcribing -> editable draft -> saved / error.
- Nunca activar grabación persistente de fondo en este hito.
- TextToSpeech local opcional para confirmaciones breves.
**Puerta de salida:** transcribir y guardar una nota en español en Samsung **sin conectar servicios de voz cloud**; si el dispositivo no tiene motor adecuado, reportar bloqueo y validar alternativa. Comprobar permiso denegado y cancelación.

## F4 — Organización inteligente con consentimiento
**Dueño:** Codex; OpenCode representa propuestas editables y estados.
- `InformationExtractor` como interfaz con implementación simulada y proveedor opcional.
- Contrato JSON validado: tareas, eventos, ideas, hechos, personas; evidencia literal y nivel de certeza.
- Diferenciar fecha de captura vs. fecha del evento, referencias como «mañana» con zona horaria.
- Propuesta -> revisión/corrección/aceptación -> persistencia; deduplicación básica y manejo de errores.
- Selección de modelo y proveedor solo tras pruebas de calidad/coste/privacidad; no claves embebidas en APK.
**Puerta de salida:** batería de 30-50 frases sintéticas: correcta distinción entre «tengo que», «terminé» y «quizá»; sin alteración del original; app sigue usable con cuota agotada.

## F5 — Recuperación y memoria contextual
**Dueño:** Codex en consultas y relaciones; OpenCode en vistas de resultados.
- Búsqueda local por fecha, palabra, estado, persona y proyecto.
- Consultas del tipo «¿qué dije ayer?» con citas al registro fuente.
- Relaciones entre registros solo con evidencia; resúmenes etiquetados como derivados.
- Evaluar búsquedas semánticas locales únicamente si las textuales son insuficientes.
**Puerta de salida:** una pregunta recupera y enlaza el registro de origen; cambios posteriores son visibles y consistentes.

## F6 — Integración Samsung y personalización
**Dueño:** OpenCode UI/UX; Codex integración del sistema.
- Widget de captura rápida, Android Sharesheet y Quick Settings Tile según necesidad validada.
- Colores dinámicos, contraste, tamaños, configuración de voz, vibración y reducir movimiento.
- Pruebas en dispositivo Samsung real; emulador Pixel no reproduce One UI.
**Puerta de salida:** iniciar una captura desde otra pantalla en pocos pasos, sin otorgar permisos privilegiados ni interferir con otras aplicaciones.

## F7 — Privacidad, resiliencia y pruebas para uso diario
**Dueño:** Codex; OpenCode usabilidad y accesibilidad.
- Política explícita de backup/autobackup, borrado, exportación y secretos.
- Revisar amenaza local: dispositivo perdido, respaldos, proveedores cloud, logs.
- WorkManager si hay tareas de interpretación pendientes con reintentos limitados; no duplicar guardado.
- Pruebas de pérdida de red, reinicio, permisos denegados, fuentes grandes, TalkBack y dispositivos lentos.
**Puerta de salida:** el usuario puede recuperar, corregir, exportar y eliminar su información; no se produce transferencia cloud sin consentimiento.

## F8 — Investigación avanzada (NO COMENZAR AÚN)
- Asistente predeterminado de Android y activación por voz/palabra clave con pantalla apagada: verificar restricciones por modelo Samsung/versión de One UI.
- Modelo de IA local cuantizado si el hardware lo soporta.
- Integración de calendario y sugerencias no invasivas; seguimiento de ciclos abiertos.
- Memoria semántica y sincronización cifrada opcional (requieren decisiones separadas).
**Puerta de salida:** una investigación de viabilidad y prototipo independiente antes de prometer funciones.

## Backlog próximo (orden bloqueante)
1. [ ] Confirmar `Run app` en Pixel 8 y `adb devices` en la sesión actual.
2. [ ] `opencode mcp list` y `codex mcp list`; verificar invocación real de Context7.
3. [ ] Codex: documentar e implementar únicamente contratos F1.1.
4. [ ] OpenCode: construir F1.2–F1.3 contra datos simulados.
5. [ ] Compilar/testear; documentar entrega; iniciar F2 con Codex.
