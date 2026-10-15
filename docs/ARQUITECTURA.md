# Arquitectura objetivo (mínima y evolutiva)

## Stack
Android nativo: Kotlin, Jetpack Compose / Material 3, Gradle Kotlin DSL. Room o tecnología persistente compatible tras comprobar versiones; Kotlin Coroutines + Flow/StateFlow. App de un módulo al inicio; modularizar solo por necesidad real.

## Flujo de datos (fuente de verdad local)
```
User text or microphone
  -> local transcription (if audio)
  -> ORIGINAL local capture (saved first)
  -> optional extractor (local OR user-consented cloud text)
  -> schema + evidence + date validation + dedupe
  -> proposed typed items for user review
  -> local records and links
  -> repository/Flow -> ViewModel -> Compose
  -> optional local TTS
```

## División de capas
- `ui/`: Composables puros; estados de UI, callback de acciones; no acceder directamente a DAOs ni clientes HTTP.
- `presentation/` o `ui/*/ViewModel`: une flujos del repositorio, controla eventos y estados.
- `domain/`: tipos estables e interfaces de casos de uso cuando la complejidad lo exija.
- `data/local/`: entidades Room/DAO, migraciones, datos locales.
- `data/repository/`: API de lectura/escritura y origen de datos local como fuente de verdad.
- `voice/`: capture/STT/TTS intercambiables, permiso contextual y errores.
- `intelligence/`: interfaces extractoras, parser y validación independiente del proveedor.
No crear paquetes vacíos prematuramente.

## Contrato mínimo (F1.01)
- `CaptureInput(rawText, sourceType, capturedAtEpochMillis, timezoneId)` representa el texto original y su contexto antes de crear un registro; no lleva ID persistente y rechaza texto o zona en blanco.
- `MemoryEntry(id, rawText, sourceType, capturedAtEpochMillis, timezoneId, status)` representa un registro con ID estable asignado al crearlo; rechaza ID, texto o zona en blanco.
- `rawText` conserva literalmente el texto recibido. No se normaliza ni se sustituye por texto derivado.
- `capturedAtEpochMillis` es el instante UTC en milisegundos desde Unix epoch; `timezoneId` conserva el contexto horario como identificador de zona IANA no vacío.
- `CaptureSourceType`: `TEXT` o `VOICE`. `VOICE` solo identifica el origen; no implementa grabación ni transcripción.
- `CaptureStatus`: `IDLE`, `SAVING`, `SAVED` o `FAILED`. Los estados de escucha/transcripción se definirán junto con F3 si hacen falta.
- Los modelos son Kotlin puro, sin dependencias de Android o Compose. El modelo UI consume `List<MemoryEntry>` y callbacks, no DAO.

## Acciones públicas de UI (F1.02)
- `MemoryUiCallbacks` agrupa `onCaptureRequested(CaptureInput)`, `onNavigateTo(AppDestination)` y `onMemoryEntrySelected(MemoryEntry.id)`.
- Las acciones expresan intención; el host/presentation las maneja. No implementan navegación ni acceso a datos.
- Se usan callbacks simples en lugar de una jerarquía de eventos sellada porque no hay reducer ni despacho central que la necesite.

## Persistencia (F2)
- Identificadores estables; separar `capturedAt` (instante) de `eventAt` (fecha interpretada opcional).
- Conservar `rawText` literal y, para voz, texto aceptado por el usuario.
- Cada dato extraído conserva referencia a `sourceEntryId` y `evidenceText`.
- Estados de verificación: explicit / inferred / confirmed (sin confundir alta confianza con hecho).
- Estados de extracción: unprocessed / pending / processed / failed, sin hacer depender lectura de red.
- Migraciones de Room probadas; no borrar BD para resolver fallos durante uso real.

## IA y privacidad
- STT offline en el dispositivo si hay motor instalado; evaluar alternativa independiente cuando no exista.
- Una API de IA remota nunca es «privada 100%»: los fragmentos autorizados salen del teléfono. Modo local mantiene funciones básicas sin IA remota.
- No enviar audio a la nube para interpretar notas. Minimizar/redactar los fragmentos de texto cuando proceda, pero la redacción no garantiza anonimato.
- No empotrar una clave maestra del proveedor en el APK. Para pruebas personales evaluar credencial del usuario, su custodia y riesgos, o un proxy seguro si alguna vez hay distribución. No decidir ahora.
- La IA propone JSON; el código valida el schema, limita operaciones y conserva evidencia; no escribe directamente en Room.
- Restringir logs y respaldos; documentar el comportamiento de Auto Backup antes de probar datos personales.

## Agenda inteligente: conceptos que deben guiar F4, F5 y F8

No convertir la nota original en una única fila polimórfica con docenas de columnas opcionales. Mantener el recuerdo como fuente inmutable y almacenar información organizada como entidades derivadas con tipo explícito, versión/estado, procedencia y relación a una o varias entradas. F4.01 define el esquema lógico y decide después si conviene una tabla común más extensiones tipadas o tablas separadas; no hacer migraciones antes de revisar su efecto sobre Room v2.

### Qué extraer y qué separar

- **Rutina declarada:** patrón que el usuario dice que sigue o quiere seguir. **Patrón observado:** repetición aparente detectada a través de distintas fechas. Guardar ambos como conceptos distintos. El patrón observado nace como `candidate`, con periodo observado, zona horaria, días/horas aproximados, fuentes múltiples y evidencia por campo; solo la persona puede confirmarlo, editarlo, ignorarlo o pedir seguimiento. No inferir profesión, salud, preferencia estable ni intención a partir de una sola nota.
- **Rutina y ocurrencias:** definir por separado la plantilla semanal y sus ocurrencias/excepciones. La plantilla puede expresar días de semana, hora local o ventana flexible, duración aproximada, vigencia desde/hasta, zona IANA y secuencia de pasos solo si se mencionó. Una ocurrencia se marca completada, omitida voluntariamente, pospuesta, cancelada o sin dato; ausencia de captura no equivale a omisión. Una rutina puede existir sin alarma.
- **Hábito:** conducta que el usuario quiere repetir o seguir. Mantener meta/frecuencia deseada separada del patrón descriptivo y del historial de ocurrencias. No confundir “lo hice tres veces” con “quiero hacerlo cada semana”.
- **Evento/cita:** inicio y fin/ duración, evento de día completo, zona IANA, ubicación, participantes, estado, fuente y precisión/certeza temporal cuando aparezcan. Conservar por separado la frase temporal original (“el próximo viernes por la tarde”) y la resolución calculada, anclada a instante/zona de captura. Las horas no dichas permanecen nulas; no asignar medianoche como si fuera una hora confirmada.
- **Tarea/compromiso:** acción, estado (pendiente/en curso/hecha/cancelada/bloqueada si el usuario lo permite), actor/responsable explícito, vence-cuándo, cuándo piensa hacerla, prioridad, estimación de duración, dependencia/bloqueo y contexto persona/proyecto. Vencimiento y planificación son campos distintos; una tarea recurrente no es automáticamente una rutina de bienestar.
- **Recordatorio/alarma:** intención de notificación con `targetItemId`, cuándo disparar o regla previa explícita, zona, adelanto, canal, habilitado/silenciado, snooze, entrega y resultado. Debe existir una acción explícita y revisable; no generar alarmas solo por extraer un evento, hábito, fecha o prioridad inferida. Evento sin hora exacta puede aceptar un recordatorio flexible solo después de elección del usuario.
- **Idea/reflexión:** contenido libre recuperable y etiqueta temática opcional; no fabricar acción, meta o alarma. Otros tipos útiles son hecho, decisión (elección, razones/alternativas citadas), objetivo, avance, bloqueo, seguimiento/espera, preferencia y recurso/referencia. Personas, proyectos y lugares deben ser entidades contextuales respaldadas por entradas, no meras etiquetas inferidas.

### Evidencia, certeza y actualización

Una propuesta estructurada debe poder enlazar `sourceEntryId`, intervalo/fragmento literal y revisión concreta del texto que se procesó; conservar original y corregido. La evidencia no se limita al resumen generado. Registrar por campo si es explícito o inferido, la cita que lo sustenta, certeza/calibración si existe, estado candidate/proposed/confirmed/rejected/expired y quién confirmó/cuándo. La confianza global del modelo no autoriza crear alarma. Si una frase nueva cambia hora/fecha, proponer modificación de la ocurrencia existente; nunca sobrescribirla silenciosamente. Deduplicar candidatos con claves semánticas más fuentes, ofrecer fusión cuando haya ambigüedad y nunca fusionar solo por título coincidente.

### Alarmas y ejecución Android

Persistir la intención de recordatorio local y permitir apagarla, posponerla, reprogramarla y cancelar sus pendientes. Elegir implementación Android por precisión que pidió la persona: `WorkManager` es para trabajo persistente/ diferible; no promete notificación al minuto. `AlarmManager` corresponde a alarmas y acciones con hora precisa. Las alarmas exactas despiertan el dispositivo y consumen más recursos; usarlas solo si el propósito exige precisión y tras resolver permiso/especial access según API/política, con alternativa degradada clara. Las repeticiones deben producir instancias con zona local y reglas de DST; tras editar, cancelar/recrear IDs deterministas y verificar al reiniciar/cambiar zona. Nunca ejecutar acciones externas en nombre del usuario solo por una alarma.

Fuentes Android oficiales para escoger API y explicar límites vigentes: [Schedule alarms](https://developer.android.com/develop/background-work/services/alarms) y [Task scheduling / WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent). F8.06–F8.09 deben resolver UX, permiso, exactitud, reinicio, zona/DST, modo ahorro y Samsung antes de implementar.

## RAG de agenda con recuperación local y generación cloud opcional

La primera arquitectura recomendada es **RAG híbrido con índice y recuperación en el dispositivo**. Room local permanece como fuente de verdad; al consultar, el teléfono busca candidatos, compone el contexto mínimo y únicamente tras confirmación envía consulta + fragmentos elegidos al adaptador cloud. No subir base, transcripciones de voz/audio, historial completo, vectores de todo el usuario ni copias de seguridad. No entrenar el modelo con recuerdos.

1. **Captura y análisis opcional:** guardar `rawText` local primero. `InformationExtractor` recibe el fragmento que el usuario autorizó y devuelve propuestas estructuradas con citas/offsets, campos temporales originales y normalizados, zona de captura, tipo y evidencia. Kotlin comprueba esquema, fechas, recurrencia, citas y links. Nada se persiste como dato confirmado hasta revisión; fallo/red ausente no bloquea la nota.
2. **Almacén/índice local:** recuerdos y revisiones siguen en Room; entidades derivadas, enlaces fuente y estados se separan del original. Mantener FTS5 sobre texto original y revisión vigente, más filtros estructurados sobre tipo, estado, fecha, zona, persona/proyecto y rutinas/ocurrencias. Actualizar/eliminar el índice en la misma transacción lógica al aceptar, corregir o borrar. El MVP de recuperación empieza léxico + filtros; añadir embeddings locales solo si una evaluación por consultas semanales demuestra ganancia frente a FTS y cabe en almacenamiento/RAM.
3. **Consulta y ranking:** interpretar la intención en una capa ligera (agenda/recordatorio, tareas vencidas/abiertas, semana, rutina, evento, idea o memoria); limitar fecha/estado/tipo por filtros deterministas y zona del usuario. Recuperar primero exact matches/FTS y ampliar a semántica solo si está disponible. Rerankear pequeño top-k por coincidencia, recencia pertinente y estado; rutina requiere evidencia repetida a lo largo del periodo, no duplicados de la misma captura. Excluir descartados, eventos pasados cuando se pregunta por próximos, notas borradas y campos sensibles no autorizados.
4. **Consentimiento por petición:** mostrar destino, proveedor, consulta y resumen/snippets exactos que saldrán; excluir audio y permitir editar/eliminar fragmentos. Enviar solo top-k mínimo con IDs opacos de fuente, citas requeridas y metadatos indispensables. No enviar vectors, secretos, token de acceso de cuenta ni memoria completa. Configuración cloud persistente no sustituye consentimiento del envío actual.
5. **Respuesta revisable:** pedir salida estructurada pequeña: respuesta, afirmaciones, `sourceEntryId`, cita literal y rango/fecha; para extractores: propuestas por tipo, campos, evidencia y preguntas de aclaración. Validar ID/cita contra los fragmentos enviados, contenido y esquema localmente. Mostrar enlaces a memoria fuente y etiquetar inferencias. Si no hay evidencia suficiente, decirlo; el modelo no crea, cambia, completa ni borra Room, rutina, alarma ni calendario.
6. **Retención, borrado y seguridad:** mantener selección y embeddings en el teléfono siempre que sea viable; documentar exactamente retención del proveedor y configurar el menor plazo disponible. Las bases/vector stores administrados por proveedor se excluyen de la primera versión porque añaden una segunda copia persistente y proceso de sincronización/borrado. Borrar/exportar cuenta debe poder propagar a cachés/cloud según el consentimiento, y probarlo. No loggear texto, query, citas ni respuesta; credenciales nunca embebidas en APK. Cloud opt-out, offline, cuota agotada y error deben preservar búsqueda/uso local.

**Elección de servicio pendiente:** F4.11 compara Gemini/DeepSeek/u otros cuando se ejecute la tarea; no fijar ahora versiones, precio, ventana de retención, tratamiento de datos, uso para mejora, región o permisos de entrenamiento. Las prestaciones y condiciones cambian. A fecha de esta investigación, la documentación de Gemini File Search dice que archivos de carga temporal se eliminan a las 48 h pero documentos/embeddings del store persisten hasta borrado manual; por eso no es el backend preferido para recuerdos personales [File Search oficial](https://ai.google.dev/gemini-api/docs/file-search). Gemini soporta salida estructurada JSON pero advierte que JSON sintácticamente válido no garantiza valores verdaderos, de modo que validación Kotlin/evidencia sigue siendo obligatoria [Structured outputs](https://ai.google.dev/gemini-api/docs/structured-output). La documentación de embeddings recalca que quien carga contenido debe tener derechos sobre este [Embeddings](https://ai.google.dev/api/embeddings). Verificar estas condiciones otra vez en F4.11/F7 antes de transmitir datos reales.

## Diseño y dependencias
- Interfaz moderna Compose con Material 3, temas dinámicos y tratamiento visual inspirado en las guías públicas One UI.
- AnimateVisibility/transitions para estados útiles; respetar ajustes de accesibilidad y movimiento.
- Investigar dependencias y versiones actualizadas con Context7 o la documentación oficial y comprobar compatibilidad antes de añadirlas.
- Separar APIs del sistema Android de las interfaces del dominio para poder probarlas en emulador y Samsung.
