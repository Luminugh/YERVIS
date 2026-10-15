# Primera instrucción para OpenCode Go (pegar como prompt)

Lee `AGENTS.md`, `ROADMAP.md`, `docs/ESTADO.md`, `docs/UX_UI.md`, `docs/PRODUCTO.md` y `docs/WORKFLOW.md` del repositorio YERVIZ.

Tu responsabilidad principal es UI/UX de alto nivel: estética Android nativa inspirada en Samsung One UI, Material 3, accesibilidad, estados útiles y animaciones discretas. Primero comprueba si Codex ya terminó **F1.1** y si existe contrato de modelos; si no, haz únicamente una propuesta de pantallas y componentes sin cambiar contratos de dominio.

Tarea cuando F1.1 esté disponible: **F1.2–F1.3**. Crea inicio «Hoy», captura textual y línea temporal por fechas con datos sintéticos etiquetados como demo. Haz componentes Compose reutilizables y pruebas/previews razonables. No agregues IA, STT ni Room; no cambies Gradle sin justificar. Para APIs cambiantes, consulta Context7/documentación oficial; aprovecha Internet para referencias públicas sin subir datos privados. Ejecuta `.\gradlew.bat assembleDebug`, valida en el emulador si hay acceso y documenta el handoff en `docs/ESTADO.md`.
