# Cómo colocar este contexto en YERVIS (Windows)

## Copiar
El ZIP está armado con `AGENTS.md`, `ROADMAP.md`, `docs/` y `prompts/` directamente en su raíz. Extraer el contenido en:

`C:\Users\Rick\Documents\GitHub\YERVIZ`

**Si ya existen esos archivos, respaldarlos/comparar contenido antes de sobrescribir.** Esta entrega no cambia automáticamente tu proyecto local.

## Cómo trabajar
1. Abrir la terminal de Android Studio en YERVIZ.
2. Verificar `git status`, `opencode mcp list` y `codex mcp list`; no se asume MCP listo.
3. Abrir Codex: `codex`. Pegar `prompts/INICIO_CODEX.md` para F1.1.
4. Cuando termine y documente su handoff, abrir OpenCode: `opencode`. Pegar `prompts/INICIO_OPENCODE.md` para F1.2/F1.3.
5. Ejecutar `.\gradlew.bat assembleDebug` y probar en Pixel 8. Revisar `docs/ESTADO.md` antes de cada nueva sesión.

## Internet y permisos
- OpenCode: las herramientas de websearch/webfetch están documentadas oficialmente; preservar configuración actual del proveedor Go y `opencode.json`. No reemplazarlo con uno mínimo.
- Codex: en configuraciones tradicionales se puede habilitar Internet para el sandbox de escritura del workspace con `sandbox_workspace_write.network_access = true`, pero las configuraciones nuevas pueden usar perfiles `permissions`; **verificar `codex --version`, config actual y documentación antes de tocarla**. Mantener aprobaciones y aislamiento: no usar opciones de bypass total.
- Context7 permite consultar documentación, **no coordina agentes**.

## Nota
No ejecutar OpenCode y Codex editando el mismo archivo simultáneamente. Hacer checkpoints Git. El estado real del código siempre prevalece sobre los documentos si estos no están actualizados; registrar diferencias en `docs/ESTADO.md`.
