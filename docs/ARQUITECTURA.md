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

## Diseño y dependencias
- Interfaz moderna Compose con Material 3, temas dinámicos y tratamiento visual inspirado en las guías públicas One UI.
- AnimateVisibility/transitions para estados útiles; respetar ajustes de accesibilidad y movimiento.
- Investigar dependencias y versiones actualizadas con Context7 o la documentación oficial y comprobar compatibilidad antes de añadirlas.
- Separar APIs del sistema Android de las interfaces del dominio para poder probarlas en emulador y Samsung.
