# YERVIS — ROADMAP MAESTRO OPERATIVO

**Versión:** 1.1 (solo asignación económica de modelos OpenCode) · **Fecha de planificación:** 2026-10-11 · **Ámbito:** Android nativo / Samsung · **Estado:** plan, NO tareas ejecutadas.

> Este documento amplía las fases F0–F8 de `ROADMAP.md` original. No sustituye el código, no certifica funciones no comprobadas y no cambia decisiones previas. Las integraciones F8 están sujetas a estudios de viabilidad.

## Cómo se ejecuta

- IDs estables `F0.01` a `F8.xx`. Ejecutar un ticket por vez; no marcar completo sin pruebas.
- **Owner** es el único agente que escribe los archivos afectados durante ese ticket. Revisiones cruzadas se hacen después.
- **C-A/C-S/C-L/O-M/O-D/O-P** son recomendaciones; deben verificarse en el selector de modelos realmente disponibles.
- Dependen de la finalización del ticket previo cuando afectan al mismo contrato; separar solicitudes paralelas solo en archivos independientes.
- `docs/ESTADO.md` se actualiza con resultados reales, bloqueos, archivos y el siguiente ticket. Para cambios importantes crear `docs/IMPLEMENTACIONES/<ID>-<tema>.md`, y ADR en `docs/DECISIONES.md`.
- F0 debe verificar `Run` real: por capturas constan `assembleDebug` y emulador `adb`, pero no se ha demostrado que la app haya abierto.

## Leyenda de modelos

| Código | Modelo recomendado | Uso |
|---|---|---|
| **C-A** | Codex · GPT-6 Astra (alto) | Arquitectura, seguridad o integración compleja |
| **C-S** | Codex · GPT-6.1 Sol (alto) | Código complejo y pruebas |
| **C-L** | Codex · GPT-6 Luna (medio) | Tarea acotada/documentación |
| **O-M** | OpenCode Go · MiMo-V2.6-Flash | UI Compose, navegación, componentes y tareas habituales |
| **O-D** | OpenCode Go · DeepSeek V4.1 Flash | UX complejo, depuración puntual y revisión visual |
| **O-P** | OpenCode Go · MiMo-V2.6-Pro | Escalamiento excepcional, con aprobación previa |

> **Disponibilidad y costo:** validar modelos reales con `/models` antes de usar; MiMo-V2.6-Flash es predeterminado y DeepSeek V4.1 Flash es respaldo para tareas que lo justifiquen. No subir a Pro/Kimi/Qwen Max sin autorización. Esta tabla prescribe modelos futuros; no cambia los modelos realmente utilizados en tareas ya terminadas. Codex no cambia de asignación. Consulta `MODELOS_Y_ROLES.md` y `YEZVI_PROMPTS_EFICIENTES.md`.

## Ahorro de tokens para OpenCode Go (regla de ejecución)

> **Estas reglas no cambian las 123 tareas, modelos, responsables ni criterios de aceptación del plan.** Para ejecutarlas usa `YEZVI_PROMPTS_EFICIENTES.md`.
>
> - **Un ID por petición.** Escribe solo `Ejecuta F1.06` usando la plantilla breve; no pegues de nuevo el roadmap ni toda la documentación.
> - **Contexto mínimo:** respetar `AGENTS.md` ya cargado; consultar solo la entrada de `docs/ESTADO.md`, el ticket y los archivos directamente relacionados. Abrir `docs/UX_UI.md` solamente en tareas visuales que lo necesiten.
> - **Sin exploraciones redundantes:** no releer archivos completos, no listar el proyecto recursivamente y usar Context7/búsqueda solo ante una duda técnica concreta o API cambiante.
> - **Modelo proporcional:** `O-M` para cambios pequeños y Compose, `O-D` para diseño o depuración compleja; conservar el seleccionado durante el ticket. Los nombres son sugerencias, no acceso garantizado.
> - **Una entrega breve:** resultado, archivos, pruebas, bloqueo y siguiente ID; evitar volver a imprimir código o documentación ya guardados.
> - **Calidad intacta:** compilar y probar lo pertinente, registrar evidencia en `docs/ESTADO.md` y documentar cambios importantes en `docs/IMPLEMENTACIONES/`. No sacrificar seguridad o verificaciones para ahorrar tokens.

## Inventario completo de tareas planificables

### F0 — Entorno, contexto, verificación

**Puerta de salida:** No iniciar F1 hasta compilar, ejecutar y verificar el estado de los MCP.

| ID | Tarea | Responsable | Modelo | Criterio de aceptación mínimo |
|---|---|---|---|---|
| **F0.01** | Auditar repositorio y documentación cargada | Codex | C-L | Salida de git status y lista de documentos; no modificar proyecto |
| **F0.02** | Verificar JAVA_HOME, Gradle Wrapper y AGP sin actualizar versiones | Codex | C-L | assembleDebug concluye sin errores |
| **F0.03** | Verificar SDK, ADB y emulador Pixel 8 API 35 | Codex | C-L | adb devices registra el emulador con estado device |
| **F0.04** | Ejecutar YERVIS realmente dentro del emulador | Codex | C-L | Captura o verificación de pantalla con aplicación abierta |
| **F0.05** | Verificar MCP Context7 en OpenCode con invocación real | OpenCode | O-M | Consulta real demuestra uso de herramienta MCP, no solo lista |
| **F0.06** | Verificar MCP Context7 en Codex con invocación real | Codex | C-L | Consulta real demuestra uso de herramienta MCP, no solo lista |
| **F0.07** | Comprobar modelos visibles en cada cliente | Codex | C-L | Registrar modelos efectivos disponibles, variantes y fallback |
| **F0.08** | Configurar Git y checkpoint sin subir nada | Codex | C-L | git status verificado; commit solo con autorización |
| **F0.09** | Reconciliar ROADMAP original y plan maestro | Codex | C-S | Mapa F0-F8 y decisiones previas preservadas |
| **F0.10** | Confirmar permisos de investigación y reglas de datos privados | Codex | C-S | Reglas de red, credenciales y archivos asentadas; sandbox preservado |

### F1 — Contratos mínimos y experiencia de interfaz

**Puerta de salida:** Pantallas con datos sintéticos; nunca anunciar datos simulados como guardados.

| ID | Tarea | Responsable | Modelo | Criterio de aceptación mínimo |
|---|---|---|---|---|
| **F1.01** | Definir contratos CaptureInput, MemoryEntry, CaptureStatus | Codex | C-S | Contratos Kotlin simples con timestamp, zona horaria, fuente y rawText |
| **F1.02** | Diseñar eventos/callbacks públicos de captura y timeline | Codex | C-S | UI sin dependencias de DAO/HTTP; contratos documentados |
| **F1.03** | Crear fixtures sintéticos y repositorio simulado | Codex | C-L | Pruebas y previews no contienen información real |
| **F1.04** | Auditar patrones One UI públicos y Material 3 | OpenCode | O-D | Reglas visuales y accesibilidad registradas en UX_UI.md |
| **F1.05** | Definir tokens visuales tipografía, color, espaciado y formas | OpenCode | O-D | Tokens reutilizables claro/oscuro con contraste |
| **F1.06** | Crear estructura de navegación Inicio, Memoria, Capturar, Ajustes | OpenCode | O-M | Navegación funcional sin múltiples pestañas innecesarias |
| **F1.07** | Implementar inicio Hoy con jerarquía del día | OpenCode | O-M | Dashboard legible con datos demo inequívocos |
| **F1.08** | Implementar captura textual rápida con estados simulados | OpenCode | O-M | Teclado, enfoque y cancelación correctos; sin falsa persistencia |
| **F1.09** | Implementar timeline agrupado por día con datos demo | OpenCode | O-M | Fechas visibles y grupos ordenados correctamente |
| **F1.10** | Implementar pantalla de detalle y estados vacío, error, carga | OpenCode | O-M | UI coherente en todos los estados |
| **F1.11** | Animaciones discretas y accesibilidad Compose | OpenCode | O-D | TalkBack, 48dp, fuente grande y movimiento reducido |
| **F1.12** | Pruebas UI/Compose y previews para estados clave | OpenCode | O-M | Pruebas ejecutadas y emulador validado |
| **F1.13** | Integrar contratos y preparar entrega a persistencia | Codex | C-S | Build y tests; handoff preciso y contrato congelado |

### F2 — Persistencia local y captura real

**Puerta de salida:** La entrada original sobrevive reinicios, edición y borrado sin red.

| ID | Tarea | Responsable | Modelo | Criterio de aceptación mínimo |
|---|---|---|---|---|
| **F2.01** | Seleccionar Room/KSP según compatibilidad real | Codex | C-S | Decisión documentada con versiones, alternativas y riesgos |
| **F2.02** | Definir esquema mínimo Room con IDs, capturedAt, timezone, rawText | Codex | C-S | Schema exportado/documentado y sin campos derivados prematuros |
| **F2.03** | Implementar Entity/DAO de entradas | Codex | C-S | CRUD tipado, transacciones correctas |
| **F2.04** | Implementar base y configuración inicial de Room | Codex | C-S | DB abre offline sin acceso a nube |
| **F2.05** | Implementar repositorio y contratos suspend/Flow | Codex | C-S | Repository oculta Room al UI y emite actualizaciones |
| **F2.06** | Implementar ViewModel/StateFlow/errores de guardado | Codex | C-S | Guardando/guardado/error refleja IO real |
| **F2.07** | Conectar captura de texto a persistencia | Codex | C-S | Entrada visible tras reiniciar la app |
| **F2.08** | Conectar timeline/orden por capturedAt y zona horaria | Codex | C-S | Prueba UTC/local y salto de día |
| **F2.09** | Editar texto conservando original/historial de corrección según diseño | Codex | C-S | Edición explícita no destruye fuente sin política acordada |
| **F2.10** | Implementar borrado con confirmación y estado honesto | Codex | C-S | El usuario elimina nota; UI se actualiza |
| **F2.11** | Pantallas de edición/borrado y fallos de guardado | OpenCode | O-M | Flujo táctil accesible y estados correctos |
| **F2.12** | Tests de DAO, repositorio y temporalidad | Codex | C-S | Tests automáticos ejecutados |
| **F2.13** | Estrategia de esquema y migraciones iniciales | Codex | C-A | Documento de implementación y tests de migración base |
| **F2.14** | Prueba integral offline: cerrar, abrir, editar y borrar | Codex | C-S | Evidencia en Pixel 8, sin conectividad |

### F3 — Voz offline y respuesta hablada

**Puerta de salida:** No enviar audio a nube; STT offline real, editable antes de guardar.

| ID | Tarea | Responsable | Modelo | Criterio de aceptación mínimo |
|---|---|---|---|---|
| **F3.01** | Investigar SpeechRecognizer on-device por versión Android y Samsung | Codex | C-S | Informe de soporte/limitaciones con documentación oficial |
| **F3.02** | Evaluar alternativa sherpa-onnx y modelo español | Codex | C-A | Benchmark de tamaño, RAM, latencia y calidad antes de elegir |
| **F3.03** | Definir interfaz SpeechToTextEngine y estados de captura | Codex | C-S | Motor desacoplado de UI y del origen de datos |
| **F3.04** | Solicitar permiso micrófono contextual | Codex | C-S | Denegado y revocado manejados sin falla |
| **F3.05** | Implementar inicio/parada/cancelación de grabación | Codex | C-S | Sin escucha permanente ni acciones ocultas |
| **F3.06** | Implementar STT totalmente offline verificable | Codex | C-A | Transcribe español con red desactivada en dispositivo compatible |
| **F3.07** | Normalizar borrador sin cambiar transcripción aceptada | Codex | C-S | El usuario revisa y corrige texto antes de persistir |
| **F3.08** | Implementar UI de grabación estados y animación significativa | OpenCode | O-M | Escuchando/transcribiendo/error representan estados reales |
| **F3.09** | Implementar transición a borrador editable | OpenCode | O-M | Se puede corregir y guardar con texto |
| **F3.10** | Añadir TextToSpeech local para confirmación opcional | Codex | C-S | Confirmación hablada solo tras guardado real |
| **F3.11** | Diseñar controles TTS/voz y ajustes de accesibilidad | OpenCode | O-D | Puede silenciarse; volumen/idioma según soporte |
| **F3.12** | Probar pausa, silencio, cancelación, falta de modelo y permiso | Codex | C-S | Casos documentados con errores visibles |
| **F3.13** | Validar rendimiento en Samsung físico y contrastar con emulador | Codex | C-A | Métricas y límites reales; si no hay Samsung, bloquear validación |

### F4 — Extracción inteligente y consentimiento cloud

**Puerta de salida:** La IA solo propone, Kotlin valida; la memoria original nunca sale sin consentimiento.

| ID | Tarea | Responsable | Modelo | Criterio de aceptación mínimo |
|---|---|---|---|---|
| **F4.01** | Definir taxonomía: tarea/evento/rutina/hábito/recordatorio/idea/hecho/decisión/objetivo/persona/proyecto | Codex | C-S | Rutinas/eventos/tareas/personas/hechos principales para futura Agenda; patrón declarado/observado, atribución, conclusiones acotadas y evidencia; definición en docs/IMPLEMENTACIONES/F4.01-taxonomia-informacion.md |
| **F4.02** | Definir esquema versionado de propuestas IA y evidencia por campo | Codex | C-A | JSON 1.0.0 estricto: once variantes, fuente+revisión+cita/rango UTF-16 por campo, fechas/recurrencia, modalidad/polaridad separadas de aceptación; Hechos pasados/Agenda sin inventar fecha; docs/IMPLEMENTACIONES/F4.02-esquema-propuestas.md |
| **F4.03** | Crear interfaz InformationExtractor y fake deterministic | Codex | C-S | Pruebas sin red y sin proveedor |
| **F4.04** | Implementar validador JSON estricto y límites | Codex | C-A | Rechaza campos/tipos/estados inválidos, recurrencias imposibles y propuestas sin evidencia; JSON válido no basta |
| **F4.05** | Implementar resolutor de referencias temporales | Codex | C-A | Fechas relativas ancladas al instante/zona de captura; día/semana, cambios de zona y ambigüedad sin inventar hora |
| **F4.06** | Definir estados de propuesta, patrón y compromiso | Codex | C-S | Candidate/proposed/confirmed/rejected/expired y ocurrencias distinguen completada, pospuesta, cancelada y desconocida |
| **F4.07** | Diseñar revisión de eventos, tareas y rutinas propuestas | OpenCode | O-D | Mostrar citas y fechas inferidas; editar/rechazar/confirmar por campo; no crear alarma implícita |
| **F4.08** | Implementar aceptación/rechazo sin mutar originales | Codex | C-S | Datos derivados y sus relaciones versionados, revisables, ligados a varias fuentes si procede |
| **F4.09** | Deduplicar propuestas y actualizar recurrencias conservadoramente | Codex | C-A | No confundir repetición de evidencia con intención; cambios/excepciones no duplican ni fusionan elementos distintos |
| **F4.10** | Crear corpus sintético de agenda y patrones semanales | Codex | C-S | Hechos pasados/fecha desconocida, acciones/rutinas/excepciones, negación/tiempo, falsas alarmas y personas: homónimos, atribución, contradicciones, falsas conclusiones de personalidad; partir de F4.01/F4.02 |
| **F4.11** | Evaluar proveedor/modelo según calidad, RAG y privacidad | Codex | C-A | Coste/cuota/JSON, retención, entrenamiento, región, borrado, uso de fragmentos y proveedor sin fijar hasta validar términos |
| **F4.12** | Diseñar consentimiento contextual por envío cloud | OpenCode | O-D | Muestra proveedor, consulta y snippets recuperados exactos; permite retirar/editar y cancelar cada petición |
| **F4.13** | Diseñar custodia de claves y límites de red | Codex | C-A | Sin credenciales maestras embebidas; riesgos registrados |
| **F4.14** | Implementar adaptador cloud opcional detrás de interfaz | Codex | C-S | Recibe solo texto/snippets consentidos; contrato provider-neutral, sin acceso directo a Room/base completa |
| **F4.15** | Reintentos idempotentes, límites y borrado de contexto remoto | Codex | C-S | Reintentos no duplican propuestas; expira/quita payload temporal y tolera modo offline |
| **F4.16** | Probar invención, rutina falsa, alarma falsa y errores cloud | Codex | C-A | Evidencia por campo; cero alarmas/acciones no confirmadas, perfiles sensibles inferidos o fusiones arbitrarias de personas; contradicciones explícitas y fallo sin perder notas |
| **F4.17** | E2E: recuerdo → recuperación RAG → propuesta → revisión | Codex | C-S | Reproduce citas, aceptación/corrección y original intacto; no requiere cloud para captura/consulta local básica |

### F5 — Búsqueda y memoria contextual

**Puerta de salida:** Respuestas auditables que enlazan fuente original.

| ID | Tarea | Responsable | Modelo | Criterio de aceptación mínimo |
|---|---|---|---|---|
| **F5.01** | Definir consultas por tiempo/tipo/estado/recurrencia/persona/proyecto | Codex | C-S | Próximos/pasados, semana, vencidas, ocurrencias y consultas de rutina con zona horaria explícita |
| **F5.02** | Implementar FTS/índice local y filtros de agenda | Codex | C-S | Resultados útiles offline; ediciones, correcciones y borrados actualizan índice; pruebas con empty DB |
| **F5.03** | Definir relaciones evidencia/fuentes/revisiones de datos derivados | Codex | C-A | Cita literal y sourceEntryId por campo conservan historial y abren el registro origen |
| **F5.04** | Implementar apartado Agenda: hechos, tareas, eventos y rutinas | Codex | C-S | Pasado/compromisos/patrones con personas y fuentes; fecha de suceso distinta de captura, desconocida si no consta; sin duplicar completadas ni inferir incumplimiento por ausencia |
| **F5.05** | Diseñar pantalla búsqueda con filtros y resultados | OpenCode | O-M | Búsqueda accesible, estados vacíos y errores |
| **F5.06** | Diseñar vistas de contexto personas/proyectos | OpenCode | O-D | Personas como eje de agenda: relaciones, rutinas, preferencias y afirmaciones atribuidas con fuentes/periodo; resolver homónimos, corregir/retirar conclusiones; sin rasgos psicológicos asumidos |
| **F5.07** | Implementar consulta natural con recuperación local previa a cloud | Codex | C-A | Filtros duros + FTS primero, top-k mínimo; cloud recibe contexto solo tras consentimiento; no inventa fuentes |
| **F5.08** | Implementar respuesta breve con citas verificables | Codex | C-A | Valida ID y cita contra evidencia local; cada afirmación y sugerencia abre fuente/revisión correcta |
| **F5.09** | Diseñar UI de conversación contextual y citas | OpenCode | O-M | No confundir respuesta derivada con hecho confirmado |
| **F5.10** | Implementar resúmenes diarios/semanales y rutina observada opcionales | Codex | C-S | Periodo/fuentes explícitos; repetición es hipótesis y requiere opt-in/revisión para convertirse en rutina |
| **F5.11** | Medir recuperación híbrida; evaluar embeddings si aportan | Codex | C-A | Comparar FTS/filtros/semántica con queries etiquetadas, recall, falsas rutinas, latencia y coste |
| **F5.12** | Pruebas regresión de búsqueda y actualización posterior | Codex | C-S | Ediciones reflejadas y citas correctas |

### F6 — Samsung, widgets y personalización

**Puerta de salida:** Integraciones con APIs públicas, pruebas reales de One UI.

| ID | Tarea | Responsable | Modelo | Criterio de aceptación mínimo |
|---|---|---|---|---|
| **F6.01** | Auditar versión y características del Samsung objetivo | Codex | C-S | Modelo, versión Android, One UI y restricciones registradas |
| **F6.02** | Diseñar preferencias visuales/accesibilidad y perfil personal | OpenCode | O-D | Especificación compatible con Material 3 y One UI |
| **F6.03** | Persistir preferencias en DataStore | Codex | C-S | Tema y ajustes persisten reinicio |
| **F6.04** | Implementar tema claro/oscuro/dynamic color | OpenCode | O-M | Contraste y dark mode correctos |
| **F6.05** | Implementar densidad visual, tipografía y animación reducida | OpenCode | O-D | Se adapta a fuentes grandes y accesibilidad |
| **F6.06** | Diseñar y desarrollar widget de captura rápida | OpenCode | O-M | UI widget legible y acción clara |
| **F6.07** | Conectar widget mediante mecanismos Android/Glance | Codex | C-S | Abre flujo de captura sin perder contexto |
| **F6.08** | Implementar recepción segura de Android Sharesheet | Codex | C-S | Texto compartido se recibe para revisión explícita |
| **F6.09** | Implementar Quick Settings Tile si aporta valor | Codex | C-S | Acceso rápido funciona sin permisos privilegiados |
| **F6.10** | Diseñar ajustes de personalidad, voz, vibración y notificaciones | OpenCode | O-D | Controles operativos o marcados como futuros |
| **F6.11** | Probar widgets, sharesheet y tile en Samsung real | Codex | C-S | Evidencia en One UI; no extrapolar Pixel |
| **F6.12** | Corregir adaptaciones por tamaño/plegable cuando aplique | OpenCode | O-M | Capturas y validación de formatos pertinentes |
| **F6.13** | Documentar funciones Samsung no disponibles y alternativas | Codex | C-S | Sin prometer API privadas ni integración Bixby |

### F7 — Privacidad, resiliencia y calidad de uso diario

**Puerta de salida:** MVP estable, datos bajo control, exportación y eliminación.

| ID | Tarea | Responsable | Modelo | Criterio de aceptación mínimo |
|---|---|---|---|---|
| **F7.01** | Modelar amenazas y clasificación de datos personales | Codex | C-A | Amenazas y mitigaciones prioritarias documentadas |
| **F7.02** | Configurar backup/autobackup y exclusiones Android | Codex | C-A | Reglas verificadas para BD, preferencias y cachés |
| **F7.03** | Decidir cifrado/reposo y almacenamiento secretos | Codex | C-A | ADR con compromisos, soporte y recuperación |
| **F7.04** | Eliminar logs sensibles, metadatos y trazas privadas | Codex | C-S | Pruebas y revisión de logging |
| **F7.05** | Implementar exportación local de datos | Codex | C-S | Exportación legible y segura por usuario |
| **F7.06** | Implementar importación/restauración solo si tiene sentido | Codex | C-A | Validación, conflictos y prevención de corrupción |
| **F7.07** | Implementar borrado total seguro y política retención | Codex | C-A | UI confirma alcance, BD elimina y backups considerados |
| **F7.08** | Diseñar centro de privacidad y controles de nube | OpenCode | O-D | Estado de proveedor y datos compartidos transparente |
| **F7.09** | Implementar reintentos idempotentes WorkManager solo si necesario | Codex | C-S | Sin notas duplicadas ni reenvíos inesperados |
| **F7.10** | Pruebas sin red, cuotas, cortes y fallos de almacenamiento | Codex | C-S | Captura nunca se pierde silenciosamente |
| **F7.11** | Pruebas migración de BD y actualización APK | Codex | C-A | Datos conservados entre versiones |
| **F7.12** | Pruebas de accesibilidad TalkBack, tamaños, contraste, movimiento | OpenCode | O-D | Hallazgos documentados y corregidos |
| **F7.13** | Pruebas instrumentadas y benchmark de latencia/batería | Codex | C-S | Mediciones con emulador y Samsung |
| **F7.14** | Preparar versión release firmada de prueba personal | Codex | C-S | APK release instalable y claves de firma seguras |
| **F7.15** | Documentación de usuario, soporte y plan de recuperación | Codex | C-L | Manual de instalación, límites y pasos rollback |
| **F7.16** | Piloto personal 2–4 semanas con métricas no invasivas | Codex | C-S | Informe basado en uso voluntario y sin telemetría añadida |

### F8 — Investigación avanzada y crecimiento opcional

**Puerta de salida:** Cada característica futura requiere viabilidad, consentimiento y nueva decisión.

| ID | Tarea | Responsable | Modelo | Criterio de aceptación mínimo |
|---|---|---|---|---|
| **F8.01** | Evaluar rol VoiceInteractionService / asistente predeterminado | Codex | C-A | Prueba independiente y límites por Android/Samsung |
| **F8.02** | Investigar activación por palabra clave offline y batería | Codex | C-A | Conclusión por hardware, permisos y restricciones |
| **F8.03** | Prototipo opt-in de hotword si viable | Codex | C-A | Solo tras aprobación; fallback manual garantizado |
| **F8.04** | Evaluar LLM cuantizado completamente local | Codex | C-A | Benchmark RAM/CPU/modelo/calidad en Samsung |
| **F8.05** | Añadir proveedor local detrás de extractor si satisface calidad | Codex | C-A | Mismo contrato; nube sigue opcional |
| **F8.06** | Diseñar alarmas, rutinas y seguimiento no invasivo | OpenCode | O-D | Evento/tarea/rutina separados; consentimiento, precisión, snooze, pausas y controles claros; no alarmas inferidas |
| **F8.07** | Implementar recordatorios confirmados y ocurrencias rutinarias | Codex | C-S | Cancelables/idempotentes; estados de entrega honestos; AlarmManager/WorkManager según precisión y límites de API/OEM |
| **F8.08** | Evaluar integración Calendar y recurrencia | Codex | C-A | Permisos mínimos, duplicados, excepciones/zona/DST; lectura y escritura separadas |
| **F8.09** | Implementar integración de calendario opcional | Codex | C-S | Sincroniza ítems/ocurrencias consentidos con origen trazable y reversión explícita |
| **F8.10** | Evaluar memoria semántica local avanzada | Codex | C-A | Comparativa embeddings vs FTS con datos de prueba |
| **F8.11** | Diseñar sincronización cifrada opcional entre dispositivos | Codex | C-A | Threat model y resolución de conflictos antes de implementar |
| **F8.12** | Prototipo sincronización si usuario decide añadirla | Codex | C-A | Sin backend obligatorio para uso local |
| **F8.13** | Investigación personalización Samsung avanzada | OpenCode | O-D | Solo integraciones documentadas públicamente |
| **F8.14** | Evaluación UX semanal de agenda y rutinas | OpenCode | O-D | Recomendaciones explicables, editables y respaldadas por muestras/feedback voluntario; sin inferir por telemetría oculta |
| **F8.15** | Plan de mantenimiento, deprecaciones y compatibilidad | Codex | C-S | Actualización rutinaria con tests y rollback |

**Total de tareas planificables:** 123.

## Hitos de entrega y secuencia

- **Hito A:** F0 + F1 = interfaz real navegable y contratos estables; los datos siguen siendo de prueba.
- **Hito B:** F2 = diario offline con notas persistentes, edición y fechas.
- **Hito C:** F3 = voz → borrador editable → nota guardada, sin nube de audio.
- **Hito D:** F4 = interpretación IA opcional con JSON validado y consentimiento.
- **Hito E:** F5 = memoria consultable con fuentes.
- **Hito F:** F6 + F7 = integración Samsung utilizable y privacidad/calidad para uso diario.
- **Exploración:** F8 solo después de validar la utilidad real y la viabilidad específica.

## Checklist transversal obligatorio para cada ticket

1. Inspeccionar `AGENTS.md`, `docs/ESTADO.md`, ROADMAP activo y documentos temáticos, más `git status`.
2. Anunciar alcance, modelo realmente elegido, dependencias, archivos y pruebas; investigación por Context7/docs cuando corresponda.
3. Implementar SOLO el ID asignado; no avanzar a fases futuras ni editar componentes fuera de responsabilidad.
4. Ejecutar `./gradlew.bat assembleDebug` desde PowerShell como `.\gradlew.bat assembleDebug`; tests adecuados y prueba visual/física según etapa.
5. Anotar salida real de build y tests, con bloqueos exactos. No confundir mock con persistencia ni emulador con Samsung.
6. Actualizar `docs/ESTADO.md` y, cuando corresponda, `docs/IMPLEMENTACIONES/<ID>-<slug>.md` más ADR en `docs/DECISIONES.md`.
7. Preparar handoff: API pública tocada, archivos modificados, siguiente tarea, riesgos, acciones manuales y decisiones pendientes.

## Criterios de documentación de implementaciones importantes

Documento requerido en cambios de: Room/esquema/migraciones, captación de audio/STT/TTS, nuevos permisos Android, cualquier IA/cloud/consentimiento, integración Samsung/widget, seguridad/cifrado/backup, arquitecturas transversales, notificaciones, sincronización y todo cambio de API pública entre agentes.

Seguir `docs/IMPLEMENTACIONES/PLANTILLA.md`: contexto, objetivo/no-objetivos, diseño, diagrama/flujo, archivos/contratos, dependencias y versiones, políticas de datos y amenazas, alternativas, tests y resultados, compatibilidad, rollback, riesgos, screenshots cuando UI, referencias y decisión vinculada.

## Fuentes oficiales para validar modelos antes de ejecutar

- https://docs.opencode.ai/docs/go/
- https://docs.opencode.ai/docs/models/
- https://developers.openai.com/api/docs/guides/model-selection
- https://developers.openai.com/api/docs/guides/code-generation
- https://developer.android.com/topic/architecture
- https://developer.samsung.com/one-ui/index.html

## Anexo futuro — Personalización ASR y organización asistida (después de F0–F8)

Este anexo es una **propuesta para considerar únicamente cuando todas las etapas F0–F8 hayan terminado y sus puertas de salida estén verificadas**. No inicia una fase activa, no recibe ID ni modifica responsables, numeración, avances o el total de **123 tareas planificables**. Requiere priorización y aprobación propias antes de ejecutarse.

### Objetivo

Permitir que cada persona adapte el reconocimiento a sus temas y que la IA organice borradores en tareas, eventos, ideas, hechos o proyectos, conservando la captura original y el uso local como base.

### Agenda, rutinas y RAG personal

1. **Ampliar lo que la agenda entiende:** recuerdos, eventos/citas, tareas/compromisos, ideas, hechos, decisiones, objetivos/progreso, bloqueos/seguimientos, personas, proyectos, lugares/recursos, rutinas, hábitos y recordatorios. Definir vocabulario no redundante y ejemplos negativos: “voy cada martes” describe frecuencia pero no necesariamente deseo de alerta; “recuérdame cada martes” sí solicita notificación; una reunión semanal es evento recurrente, no hábito. Fecha de captura, inicio/fin del evento, vencimiento de tarea, fecha prevista y disparo de recordatorio son instantes/roles distintos.
2. **Rutinas semanales seguras:** admitir patrón explícito y patrón observado, sin mezclarlos. La observación resume fechas repetidas de capturas/ocurrencias dentro del periodo que la persona elija, calcula distribución de días/ventanas y adjunta las fuentes usadas. Muestra la hipótesis al usuario y consulta si desea nombrarla, corregirla, hacer seguimiento o crear un recordatorio. No interpretar silencio/falta de nota como incumplimiento; soportar saltos, pausas, feriados/vacaciones, DST, zona y cambios de horario. Sin muestras suficientes, indicar “no hay datos” en vez de puntuación inventada.
3. **Esquema antes de Room:** definir modelo conceptual normalizado de `OrganizedItem`, extensiones de Evento/Tarea/Rutina/Hábito/Recordatorio y `RoutineOccurrence`/excepciones, junto con `EvidenceLink` hacia ID+revisión de la memoria original. Comparar tabla común + tablas tipadas con tablas separadas; evitar un objeto gigante opcional. Estados candidate/proposed/confirmed/rejected/expired, certeza/procedencia por campo y evolución append-only de extracción deben ser acordados. Presentar propuesta de migración Room separada y probarla antes de código; no ampliar el schema v2 durante el anexo.
4. **Recordatorios:** el sistema solo programa a pedido del usuario. Definir instante, zona, adelanto, canal, habilitado, snooze, pausa, destino, origen e idempotencia. Tras edición/cambio de zona, reprogramar y cancelar el pending intent anterior. Probar reinicio, Doze, economía de batería, cambio de zona/DST y permiso exact alarm; diferenciar hora exacta y ventana aproximada. Tratar WorkManager como trabajo diferible y AlarmManager como alarmas donde haga falta precisión; confirmar en F8 con Android/Samsung vigentes. No solicitar permisos especiales hasta que exista una función aprobada que los necesite.
5. **RAG híbrido con privacidad:** Room y FTS local son verdad e índice inicial. Para cada consulta, extraer filtros de fechas/tipo/estado/persona/proyecto/rutina; recuperar top-k snippets localmente por FTS y metadatos, luego opcionalmente rerankear/usar embedding solo tras benchmark. Presentar consulta y fragmentos exactos que se enviarían; consentimiento por envío. El adaptador cloud recibe solo pregunta + mínimo contexto seleccionado y IDs/citas opacos, nunca audio, vector store completo o historial. Devuelve respuesta/propuestas JSON con fuentes y evidencia; Kotlin valida citas contra fuentes locales y nada se escribe en Room hasta aceptación. Falla/red/cupo agotado mantiene búsqueda local. Retención y borrado remotos deben poder auditarse. Proveedor gestionado de RAG no es el default por crear otra copia persistente.
6. **Pruebas para aceptar:** corpus etiquetado español con varias semanas simuladas y frases sintéticas; medir precisión/recall de extracción, citas correctas, tasa de falsas rutinas/alarmas, horas/días/zona/DST, cambios y excepciones, duplicados, coste/latencia/tokens y degradación sin red. Comparar FTS-only vs híbrido con preguntas esperadas. Incluir casos que niegan/corrigen una rutina y semanas sin registro. Ajuste de ASR/LLM es último recurso y usa datos con derechos/consentimiento; RAG en sí recupera contexto, no entrena pesos.

### Puertas de seguridad y aceptación

- Antes de comenzar, cerrar las puertas pendientes de F3.06 (validación de micrófono y auditoría de distribución), y contar con F4 (consentimiento/extracción), F5 (recuperación trazable) y F7 (privacidad/resiliencia) implementadas y validadas.
- Congelar conjuntos de evaluación licenciados o autorizados por perfil; medir WER/CER, precisión/recall de términos, inserciones negativas, calidad de clasificación, fechas inventadas, latencia, memoria, batería y costo por operación. Separar entrenamiento, calibración y evaluación por hablante/frase.
- Comparar proveedores y precios, cuotas, tratamiento/retención de datos, licencias de código/pesos/datasets y condiciones de distribución **en el momento de ejecutar**; no fijar ahora Gemini, DeepSeek ni un modelo o versión concreta. Los niveles gratuitos se prueban solo con datos sintéticos si sus condiciones permiten revisión o mejora del proveedor.
- No publicar modelos, adaptadores ni APK hasta cerrar la auditoría de licencias/procedencia y los requisitos de distribución del runtime.

**No forma parte de este anexo:** reabrir etapas anteriores, cambiar Room/contratos antes de una propuesta aprobada, atribuir confianza por palabra sin calibración, autocorregir o guardar inferencias sin confirmación, subir voz a la nube o habilitar entrenamiento automático.
