# YEZVI — Modelos y roles (edición económica para OpenCode Go)

**Actualización:** 2026-10-11. **Cambio acotado:** solo política de modelos OpenCode; se conservan F0–F8, las 123 tareas y las responsabilidades originales. Los códigos de Codex permanecen intactos.

## Modelos por defecto

| Clave | Agente | Modelo recomendado | Cuándo |
|---|---|---|---|
| **O-M** | OpenCode Go | **MiMo-V2.6-Flash** | **Predeterminado:** Compose, navegación, timeline, textos y cambios pequeños |
| **O-D** | OpenCode Go | **DeepSeek V4.1 Flash** | Revisión UX, depuración no trivial, accesibilidad, segunda opción ante fallo concreto |
| **O-P** | OpenCode Go | **MiMo-V2.6-Pro** | **Solo con permiso del usuario:** bloqueo que persiste después de un intento focalizado con O-M/O-D |
| **C-S** | Codex | Modelo fuerte de programación disponible, esfuerzo alto | Arquitectura, dominio, Room, IA y lógica compleja |
| **C-A** | Codex | Modelo más capaz disponible, esfuerzo alto | Seguridad, migraciones, voz y cambios críticos |
| **C-L** | Codex | Modelo económico disponible, esfuerzo medio | Pruebas o documentación acotada |

> Los códigos **C-S/C-A/C-L** son los mismos del roadmap anterior. Esta actualización no cambia sus tareas ni exige cambiar tu configuración de Codex. No asumas IDs internos de modelo: selecciona el nombre **exactamente como se muestre en `/models`**.

## Referencia de costo público de OpenCode Go

Precios publicados en USD por 1 millón de tokens; pueden cambiar y no representan por sí solos el porcentaje de cuota consumida.

| Modelo | Entrada | Salida | Observación |
|---|---:|---:|---|
| MiMo-V2.6-Flash | $0.14 | $0.28 | Prioridad 1 |
| DeepSeek V4.1 Flash | $0.15 | $0.60 | Tarifa *off-peak*; puede duplicarse en horario pico |
| MiMo-V2.6-Pro | $0.435 | $0.87 | Solo escalamiento autorizado |
| Kimi K2.7 Code | $0.95 | $4.00 | No usar como valor por defecto |
| Qwen3.8 Max | $2.00 | $6.00 | No usar como valor por defecto |

OpenCode Go aplica a cada modelo ventanas del **20% de su límite mensual durante 5 horas**, del **50% semanal** y del **100% mensual**. Por ello, una barra del 20% puede reflejar una ventana temporal; comprueba qué indicador muestra tu cuenta. Los precios/solicitudes son estimaciones, no garantizan ahorro exacto ni calidad equivalente.

**Fuentes:** https://opencode.ai/docs/go/ · https://opencode.ai/docs/models/

## Protocolo de gasto mínimo

1. **Un ID por ejecución.** Seleccionar **O-M** de forma predeterminada, incluso para `F1.09`.
2. **Leer poco:** AGENTS, estado reciente, ticket y archivos involucrados. Sin barridos del repositorio ni volcado de documentación salvo necesidad.
3. **No investigar de nuevo** tecnologías ya justificadas. Consultar Context7 solamente ante duda concreta; una consulta dirigida antes que varias búsquedas amplias.
4. **Primera implementación corta:** cambios mínimos, sin refactor global ni dependencia innecesaria. Reutilizar modelos, tokens y componentes existentes.
5. **Validar una vez después de cambios:** tests pertinentes y build; extraer errores relevantes, no volcar logs gigantes. Si hay errores, corregir causa concreta y volver a validar.
6. **Escalar con control:** ante dos intentos fallidos o bloqueo explícito, detenerse, resumir error y pedir permiso antes de O-P, Kimi o Qwen Max.
7. **Documentar en archivos:** resumen breve en `docs/ESTADO.md`; documentación detallada solo cuando cambie un contrato importante, permisos, Room, voz, IA, seguridad o integración Samsung.
8. **Responder en 5–8 líneas** y no ejecutar otras tareas automáticamente.

## Distribución de responsabilidades (sin cambios)

- **OpenCode Go:** interfaz, navegación, experiencia, animaciones y accesibilidad.
- **Codex:** contratos, almacenamiento Room, voz, IA, seguridad, integraciones y lógica.
- Contratos públicos y archivos compartidos: un solo responsable por ticket.
- Sin publicación de código, cambios de permisos o envío de datos privados sin autorización.
