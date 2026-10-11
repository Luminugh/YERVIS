# Definición de producto — YEZVI

## Visión
Asistente personal de memoria en Android/Samsung. El usuario habla o escribe de manera natural, YEZVI conserva la información original, propone una organización útil, permite recuperarla por fechas/contextos y actúa con autorización. La interfaz debe sentirse moderna y ligera, no como un bloc de notas con carpetas.

**Problema:** pensamientos, avances, citas y tareas se pierden porque capturarlos, clasificarlos y encontrarlos exige esfuerzo.  
**Promesa:** «Dilo o escríbelo una vez; YEZVI te ayuda a encontrarlo y recordarlo después».

## Trabajos principales del usuario
1. Capturar una idea o compromiso en segundos, por texto o voz.
2. Consultar qué ocurrió/qué dijo en un día determinado.
3. Encontrar tareas, decisiones y proyectos sin organizar carpetas manualmente.
4. Preguntar con lenguaje natural y obtener respuestas respaldadas por entradas originales.
5. Corregir, borrar y controlar inferencias, notificaciones y uso de Internet.

## Tipos de información prioritarios
- Entrada original de diario (siempre preservada).
- Tarea (pendiente/completada/cancelada, con fecha si existe evidencia).
- Evento o cita (hora/fecha y zona horaria, con estado de certeza).
- Idea o reflexión (no imponer recordatorio).
- Persona/proyecto (como relación respaldada por fuente).

## Principios de interacción
- Voz primero, pero texto como alternativa completa.
- Fricción mínima: un acto de captura; confirmación solo ante consecuencias o ambigüedad importante.
- Nunca tratar conjeturas de IA como hechos: fuente visible y corrección sencilla.
- Las funciones básicas siguen operando sin conexión y sin proveedor de IA.
- Estados honestos: «guardado localmente», «pendiente de analizar», «sugerencia de IA».
- Respuestas habladas opcionales, discretas y desactivables.

## Producto inicial (MVP)
- Captura por texto; captura por voz y transcripción offline cuando validada.
- Línea temporal agrupada por fecha; vista detalle, editar y borrar.
- Extracción estructurada opcional con revisión del usuario; lista de pendientes.
- Búsqueda por texto y fechas; TTS breve opcional.

## Fuera de alcance inicial
- Escuchar permanentemente «Oye YEZVI» con pantalla apagada.
- Integración privilegiada con Bixby/Samsung o control arbitrario de otras apps.
- Servidor remoto obligatorio, backend cloud, monetización o cuentas.
- Diagnósticos personales, análisis invasivos, agentes que actúan sin confirmación.

## Criterios cualitativos
Una nota nunca debe desaparecer por un fallo de IA o red; las tareas no se inventan; las respuestas indican qué registros las respaldan; la UI prioriza recuperar información sobre mostrar un chat continuo.

## Datos abiertos para investigación futura
Modelo de Samsung real (RAM y One UI), motor offline STT compatible, proveedor IA con política de datos aceptable, nivel de cifrado y backup, grado de personalización de asistente.
