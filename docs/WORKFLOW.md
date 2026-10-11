# Coordinación de OpenCode Go + Codex

## Regla operativa
Ambos clientes abren **el mismo repositorio local**, pero **no deben editar los mismos archivos al mismo tiempo**. Context7 es documentación, no un coordinador de tareas, y no sincroniza automáticamente el estado de sesiones entre agentes. El contexto compartido es Git + estos Markdown.

## Ruta de trabajo
1. Leer `AGENTS.md`, `docs/ESTADO.md` y la sección activa de `ROADMAP.md`.
2. Consultar documentación oficial/Context7 para APIs cambiantes; comprobar versiones.
3. Definir resultado, archivos, pruebas y dueño. Si altera contrato, Codex lo define primero y OpenCode revisa necesidades de UI.
4. Trabajar en rama propia o commit de checkpoint; no hacer cambios no relacionados.
5. Ejecutar build/tests y pruebas visuales o de persistencia según rol.
6. Actualizar `docs/ESTADO.md` con evidencia, archivos, riesgos y siguiente acción para el otro agente.
7. Pedir revisión cruzada; el usuario decide integraciones sensibles.

## Responsabilidades y handoff
- OpenCode Go: presenta componentes Compose, capturas de pantalla de prueba sintética, revisión accesibilidad; no modifica el esquema Room sin consentimiento.
- Codex: presenta contratos, tests, migraciones y reglas de privacidad; no modifica la identidad visual salvo arreglos coordinados.
- Archivos compartidos (`MainActivity.kt`, DI, navegación, ViewModels): un solo dueño por ticket. Coordinar antes de tocar.
- Nunca afirmar que una feature está validada si solo pasa `assembleDebug`.

## Internet durante desarrollo
**Permitido:** consultar documentación pública, Context7, repositorios abiertos, descargar dependencias de Google Maven/Maven Central y buscar soluciones. La investigación abierta no significa autorización a exfiltrar datos personales ni eliminar las aprobaciones del sistema.

**No permitido sin confirmación:** hacer push/publicar código, usar notas reales en prompts externos, subir respaldos, ejecutar scripts desconocidos, cambiar permisos a acceso total sin sandbox, borrar carpetas/DBs, agregar telemetría y transmitir credenciales.

OpenCode Go dispone de websearch/webfetch; activar solamente las herramientas apropiadas. Codex puede necesitar habilitar red explícitamente dentro de workspace sandbox; revisar `codex --help` y configuración vigente antes de modificar permisos globales.

## MCP inicial
- Context7 para ambos, sólo documentación. Verificar con `opencode mcp list`, `codex mcp list` y una consulta que realmente invoque herramientas.
- ADB/Gradle por terminal ya resuelven la primera automatización Android; un MCP ADB de terceros es opcional, revisar código y limitar a emulador antes de usarlo.
- No sobrecargar el contexto con servidores MCP innecesarios.

## Checklist de cierre de ticket
- [ ] Código en scope, contrato consistente.
- [ ] Build y tests ejecutados; registrar salidas.
- [ ] UI/emulador/Samsung probado si corresponde.
- [ ] Sin claves o datos privados en el diff/logs.
- [ ] `docs/ESTADO.md` actualizado con estado real y handoff.
- [ ] Commit en Git si hay autorización.
