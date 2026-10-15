# YERVIS — instrucciones compartidas de agentes

> Contexto estable para OpenCode Go y Codex. Mantener conciso. Leer los documentos específicos solo cuando correspondan a la tarea.

## Propósito
YERVIS es el nombre temporal del asistente personal de memoria para Android/Samsung: captura por texto o voz, convierte voz a texto **localmente**, conserva el original y organiza información con privacidad y control del usuario. Rutinas, eventos, tareas, personas y hechos son ejes principales de la futura sección Agenda; las ideas son complementarias. No es un chatbot genérico ni una app de notas convencional.

## Estado actual verificado (14-10-2026)
- Raíz Git comprobada el 15-10-2026: `C:\Users\Rick\Documents\GitHub\YERVIS`. Las rutas YEZVI/YERVIZ de informes anteriores son históricas.
- Nombre visible temporal actual: **YERVIS**. Se conserva `applicationId`/namespace `com.yezvi.assistant`, el paquete Kotlin y la identidad de Room para preservar instalaciones, datos y schemas existentes; no renombrarlos como parte de esta actualización de marca.
- `applicationId`/namespace conservados: `com.yezvi.assistant`.
- Kotlin + Jetpack Compose + Gradle Kotlin DSL; `assembleDebug`, `testDebugUnitTest` y `assembleDebugAndroidTest` dieron `BUILD SUCCESSFUL` en los ajustes de interfaz del 14-10-2026.
- ADB reconoció un Samsung Galaxy S21 FE (`SM-G990E`, API 35) para pruebas instrumentadas focalizadas.
- No cambiar package name, Gradle/Java ni herramientas que ya compilan sin motivo verificable.

## Responsabilidades
- **OpenCode Go = UI/UX:** navegación, Compose, componentes, animaciones con significado, accesibilidad, temas, prototipos y pruebas de interfaz. No editar entidades Room, DAOs, repositorios, reglas de privacidad o conexiones de IA sin coordinar.
- **Codex = núcleo técnico:** contratos de dominio, Room, repositorios, ViewModels compartidos, transcripción, IA y validaciones, seguridad y pruebas de lógica. No rediseñar pantallas ni modificar identidad visual sin coordinar.
- Contratos públicos compartidos se aprueban antes de editar ambos lados. Una sola persona/agente modifica el mismo archivo a la vez.

## Documentación del proyecto
- `docs/PRODUCTO.md`: objetivos, decisiones y límites de producto.
- `ROADMAP.md`: hitos, entregables, dependencias, puertas de salida.
- `docs/ARQUITECTURA.md`: límites de capas, flujo de datos y privacidad.
- `docs/UX_UI.md`: criterios de interfaz, accesibilidad y movimiento.
- `docs/WORKFLOW.md`: coordinación, calidad, handoffs y acceso a Internet.
- `docs/ESTADO.md`: fuente de verdad de avances, bloqueos y siguientes tareas; actualizar al concluir trabajo real.
- `docs/DECISIONES.md`: registro de decisiones técnicas, con fecha y justificación.

## Reglas no negociables
1. Primero guardar la entrada original y su fecha/zona horaria en local. El resultado de IA es derivado, editable y auditable, jamás reemplaza el original.
2. Base local = fuente de verdad. La app debe guardar, consultar y editar sin Internet. La IA en la nube es opcional y requiere consentimiento informado por envío.
3. No enviar notas, audio, logs con datos personales, secretos ni historiales a documentación MCP, servicios de desarrollo o modelos externos sin autorización específica. Usar datos sintéticos en pruebas.
4. No introducir backend remoto, autenticación, base de datos cloud, suscripciones ni permisos peligrosos sin una decisión explícita.
5. IA devuelve propuestas estructuradas; Kotlin valida fechas, schema, duplicados y evidencia antes de guardar.
6. No implementar micrófono siempre activo ni prometer activación por palabra clave con pantalla bloqueada; investigar viabilidad Samsung después del MVP.
7. Evitar bibliotecas innecesarias, abstracciones prematuras, animación decorativa o módulos que todavía no aportan valor.
8. Permitir investigación por Internet (documentación pública/MCP) y compilación; conservar aprobaciones para operaciones destructivas, acceso fuera del proyecto, credenciales y publicación.

## Verificación antes de declarar terminado
- `./gradlew.bat assembleDebug` (Windows PowerShell: `.\gradlew.bat assembleDebug`).
- Tests correspondientes: `.\gradlew.bat testDebugUnitTest`; `connectedDebugAndroidTest` cuando existan pruebas instrumentadas y emulador disponible.
- Preferencia vigente de pruebas: Samsung físico mediante ADB Wi-Fi; Pixel 8 para las demás pruebas/inspección visual. No exigir USB ni cortar Wi-Fi del Samsung sin una vía manual acordada; informar limitaciones reales. Para documentación sin cambios de código/configuración no ejecutar Gradle ni pruebas de dispositivo innecesarias.
- Reportar archivos modificados, comportamiento verificado, pruebas realizadas, errores, decisiones y próximo handoff.
- Antes de iniciar una nueva sesión, leer `docs/ESTADO.md` y la tarea activa de `ROADMAP.md`.

## Planes
Para trabajo que afecte múltiples capas, altere el esquema de datos o necesite investigación técnica, proponer un plan corto con archivos afectados y pruebas; pedir revisión antes de cambios de contrato. Los agentes deben usar `docs/WORKFLOW.md` para la entrega entre roles.
