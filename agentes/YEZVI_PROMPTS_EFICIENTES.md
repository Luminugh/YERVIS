# YEZVI — Prompts eficientes para agentes (edición económica OpenCode Go)

**Propósito:** consumir menos tokens sin omitir validación, privacidad ni documentación. Complementa `AGENTS.md`, `ROADMAP_MAESTRO.md` y `docs/ESTADO.md`; no cambia sus tareas ni responsables.

## 1. Reglas permanentes de ahorro de tokens

1. **Un ticket por ejecución.** No avanzar automáticamente al siguiente. El ID determina alcance, responsable y modelo recomendado.
2. **No repetir contexto.** Si el cliente ya carga `AGENTS.md`, no volver a pegarlo ni imprimirlo; localizar solo el ticket específico del roadmap y leer el estado reciente del proyecto.
3. **Lectura selectiva.** Abrir únicamente archivos de la funcionalidad afectada; evitar dumps de código completo, listados recursivos, revisiones globales o lecturas repetidas. En una primera sesión nueva sí comprobar el estado y dependencias imprescindibles.
4. **MCP/Internet bajo demanda.** Consultar Context7 solo ante APIs cambiantes, errores concretos o incertidumbre; hacer preguntas específicas, no investigaciones generales si la API ya está confirmada.
5. **Cambios pequeños y verificados.** No añadir dependencias, pantallas, refactors ni archivos ajenos al ticket. Ejecutar compilación y pruebas pertinentes al terminar; no relanzarlas sin cambios.
6. **Respuesta corta.** Entregar máximo 8–12 líneas: estado, cambios, pruebas reales, riesgos y próximo ID. No pegar archivos completos en el chat.
7. **Documentar en disco, no duplicar en chat.** Actualizar `docs/ESTADO.md` brevemente; crear `docs/IMPLEMENTACIONES/Fx.xx-tema.md` y ADR solo para cambios relevantes según `AGENTS.md`/roadmap.
8. **No sacrificar seguridad.** Pedir aprobación para operaciones destructivas, permisos amplios, transferencias de datos privados o cambios de contrato público. Probar con datos sintéticos.

## 2. Modelos económicos por tipo de tarea (nuevo)

| Tipo de tarea | Primer intento | Respaldo / escalamiento |
|---|---|---|
| Compose, navegación, timeline, componentes | **O-M: MiMo-V2.6-Flash** | O-D si hay un bloqueo demostrado |
| Ajustes pequeños, pruebas, documentación | **O-M: MiMo-V2.6-Flash** | Ninguno en condiciones normales |
| Investigación UX, animaciones o accesibilidad | **O-D: DeepSeek V4.1 Flash** | O-M si es una tarea sencilla |
| Error difícil que persiste tras dos intentos | **Detenerse y explicar** | O-P: MiMo-V2.6-Pro **solo con aprobación** |

No usar Kimi K2.7 Code o Qwen3.8 Max como opción predeterminada. Consulta `MODELOS_Y_ROLES.md` para referencias de precios y el significado de los códigos. **El número de tokens por sí solo no equivale al porcentaje de cuota**: cuenta también el precio por modelo, el contexto, el caching y las ventanas del plan.

## 3. Prompt breve reutilizable — OpenCode Go

Reemplaza únicamente `[ID]` (por ejemplo, `F1.06`):

```text
YEZVI · Ejecuta SOLO [ID] de ROADMAP_MAESTRO.md.
Respeta AGENTS.md y docs/ESTADO.md; consulta únicamente el ticket y los archivos relacionados.
Rol: UI/UX Compose. Usa contratos existentes; NO cambies Room, dominio, IA ni Gradle fuera del ticket.
Usa Context7 solo si surge una duda técnica específica. Implementa el cambio mínimo, en español y accesible.
Verifica build/tests pertinentes y emulador si aplica. Documenta avances reales en docs/ESTADO.md; si el cambio es importante, crea documento de implementación/ADR.
Responde en máximo 6 líneas: estado, archivos, pruebas, bloqueo y siguiente ID. No avances otra tarea.
```

**Aún más corto en sesiones continuas:** si el agente ya conoce el repositorio y sus instrucciones, basta con:

```text
Ejecuta solo F1.09 según ROADMAP_MAESTRO.md con MiMo-V2.6-Flash. Lee solo los archivos pertinentes; cambio mínimo, prueba y registra en docs/ESTADO.md. Responde en 6 líneas; no avances al siguiente ID.
```

## 4. Prompt breve reutilizable — Codex

```text
YEZVI · Ejecuta SOLO [ID] de ROADMAP_MAESTRO.md.
Aplica AGENTS.md y el estado reciente de docs/ESTADO.md; lee únicamente los archivos pertinentes.
Rol: arquitectura/lógica. No rediseñes la UI ni alteres contratos públicos sin coordinar.
Implementa lo mínimo verificable, ejecuta build/tests correspondientes y documenta resultados reales.
Para Room, voz, IA, seguridad, permisos o integraciones importantes crea documento de implementación y ADR si hay decisión duradera.
Responde en 8 líneas: estado, archivos, pruebas, riesgos y siguiente ID. No ejecutes otra tarea.
```

## 5. Ejecución diaria con mínima fricción

En la terminal dentro de `C:\Users\Rick\Documents\GitHub\YEZVI`:

```powershell
opencode
# o, en otra sesión:
codex
```

- En OpenCode usa **MiMo-V2.6-Flash** por defecto en `/models`; DeepSeek V4.1 Flash solo cuando la tarea lo justifique. Codex mantiene su selección en `/model`; **no cambies por cada mensaje**.
- Pega el prompt corto con un ID. No adjuntes los 123 tickets en cada conversación.
- Tras completar, revisa la evidencia y autoriza expresamente el siguiente ID.
- Para entregas grandes, usa Git como checkpoint y documenta solo las diferencias importantes.

## 6. Registro mínimo de una tarea

Añadir a `docs/ESTADO.md`:

```md
### [fecha] — F1.07 — OpenCode — modelo realmente usado
- Estado: terminado / parcial / bloqueado
- Cambios: rutas de archivos (sin pegar código)
- Pruebas: comando y resultado; emulador sí/no
- Riesgos/decisiones: solo si existen
- Siguiente: F1.08 (requiere aprobación)
```

Para **implementaciones importantes**, completar también `docs/IMPLEMENTACIONES/PLANTILLA.md` con decisiones, dependencias, seguridad/privacidad, pruebas y reversión. No sustituirlo por un informe superficial para reducir tokens.
