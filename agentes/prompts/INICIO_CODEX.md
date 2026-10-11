# Primera instrucción para Codex (pegar como prompt)

Lee `AGENTS.md`, `ROADMAP.md`, `docs/ESTADO.md`, `docs/ARQUITECTURA.md`, `docs/PRODUCTO.md` y `docs/WORKFLOW.md` del repositorio YEZVI. Revisa el estado real del proyecto y no supongas que los MCP o el Run ya están confirmados.

Tu responsabilidad principal será arquitectura, persistencia, procesamiento de voz, IA y pruebas. **Primera tarea: F1.1 únicamente.** Inspecciona el código Kotlin/Compose y define un contrato mínimo entre captura, entradas de memoria y estados para que OpenCode pueda implementar la UI con datos sintéticos. Mantén el contrato simple; no agregues Room, cliente HTTP, STT ni un backend todavía.

Investiga solo la documentación cambiante necesaria utilizando las herramientas de consulta disponibles. Presenta un plan corto antes de modificar contratos. Escribe tests de lógica cuando aporten valor. Ejecuta `.\gradlew.bat assembleDebug` y registra resultados verificables, archivos afectados y entrega a OpenCode en `docs/ESTADO.md`. No edites masivamente las pantallas.
